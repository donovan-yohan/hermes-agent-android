package com.hermesagent.mobile.ui.system

import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemLogsControllerTest {
    private class Host : EndpointDispatchingGatewayHttp {
        val requests = mutableListOf<GatewayHttpRequest>()
        val replies = mutableListOf<CompletableDeferred<GatewayHttpResult>>()
        var ignoreCancellation = false
        override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unguarded HTTP")
        override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult {
            if (!request.isCurrent() || !dispatch { true }) return GatewayHttpResult.Rejected(0, "refused")
            requests += request
            val reply = CompletableDeferred<GatewayHttpResult>()
            replies += reply
            return if (ignoreCancellation) withContext(NonCancellable) { reply.await() } else reply.await()
        }
        fun answer(index: Int, body: String): ByteArray = body.encodeToByteArray().also {
            replies[index].complete(GatewayHttpResult.Success(200, it))
        }
    }

    @Test fun `defaults and selected file level use bounded scoped authenticated GET`() = runTest {
        val host = Host()
        val owners = MutableStateFlow(SystemLogsOwner(host, 4, "work"))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        assertTrue(controller.state.value.loading)
        val first = host.requests.single()
        assertEquals("/api/logs", first.path)
        assertEquals("GET", first.method)
        assertNull(first.body)
        assertEquals(mapOf("file" to "agent", "lines" to "200", "profile" to "work"), first.query)
        assertEquals(524288L, first.maxResponseBytes)
        assertEquals(15000L, first.timeoutMillis)
        controller.selectFile("desktop"); controller.selectLevel("warning"); runCurrent()
        assertFalse(first.isCurrent())
        assertEquals("desktop", host.requests.last().query["file"])
        assertEquals("WARNING", host.requests.last().query["level"])
        val bytes = host.answer(host.replies.lastIndex, """{"lines":["WARN retry","ready"]}""")
        runCurrent()
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(listOf("WARN retry", "ready"), controller.state.value.lines)
        controller.filter("  ReTrY  ")
        assertEquals(listOf("WARN retry"), controller.state.value.filteredLines)
        assertEquals(2, host.requests.size)
        controller.filter("missing")
        assertTrue(controller.state.value.filteredLines.isEmpty())
        controller.pause()
    }

    @Test fun `legacy unspecified profile is omitted but explicit default remains explicit`() = runTest {
        val host = Host()
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, null))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        controller.selectFile("arbitrary"); controller.selectLevel("debug"); controller.selectFile("agent")
        runCurrent()
        assertEquals(1, host.requests.size)
        assertFalse(host.requests.single().query.containsKey("profile"))
        owners.value = owners.value.copy(profile = "default")
        runCurrent()
        assertEquals("default", host.requests.last().query["profile"])
        controller.pause()
    }

    @Test fun `old file result cannot replace newly selected file even when cancellation is ignored`() = runTest {
        val host = Host().apply { ignoreCancellation = true }
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, null))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        controller.selectFile("errors"); runCurrent()
        host.answer(1, """{"lines":["new"]}"""); runCurrent()
        val old = host.answer(0, """{"lines":["old"]}"""); runCurrent()
        assertEquals(listOf("new"), controller.state.value.lines)
        assertTrue(old.all { it == 0.toByte() })
        controller.pause()
    }

    @Test fun `same endpoint reconnect and profile switch invalidate admission before observer runs`() = runTest {
        val host = Host().apply { ignoreCancellation = true }
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, "work"))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        val oldRequest = host.requests.single()
        owners.value = owners.value.copy(profile = "personal")
        assertFalse(oldRequest.isCurrent())
        runCurrent()
        assertEquals("personal", host.requests.last().query["profile"])
        host.answer(0, """{"lines":["private old profile"]}"""); runCurrent()
        assertTrue(controller.state.value.lines.isEmpty())
        val replacement = Host()
        val previous = host.requests.last()
        owners.value = owners.value.copy(http = replacement)
        assertFalse(previous.isCurrent())
        runCurrent()
        host.answer(1, """{"lines":["old leg"]}"""); runCurrent()
        replacement.answer(0, """{"lines":["current leg"]}"""); runCurrent()
        assertEquals(listOf("current leg"), controller.state.value.lines)
        controller.pause()
    }

    @Test fun `rapid profile round trip is fenced even when intermediate flow value is conflated`() = runTest {
        val host = Host().apply { ignoreCancellation = true }
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, "work", 1))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        val pending = host.requests.single()
        owners.value = owners.value.copy(profile = "personal", profileGeneration = 2)
        owners.value = owners.value.copy(profile = "work", profileGeneration = 3)
        assertFalse(pending.isCurrent())
        runCurrent()
        assertEquals(2, host.requests.size)
        host.answer(0, """{"lines":["outdated round trip"]}"""); runCurrent()
        assertTrue(controller.state.value.lines.isEmpty())
        host.answer(1, """{"lines":["fresh"]}"""); runCurrent()
        assertEquals(listOf("fresh"), controller.state.value.lines)
        controller.pause()
    }

    @Test fun `endpoint switch disconnect and pause wipe content and revoke pending calls`() = runTest {
        val host = Host()
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, null))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        host.answer(0, """{"lines":["old host"]}"""); runCurrent()
        owners.value = SystemLogsOwner(null, 2, null); runCurrent()
        assertTrue(controller.state.value.lines.isEmpty())
        assertNotNull(controller.state.value.error)
        owners.value = SystemLogsOwner(host, 2, null); runCurrent()
        val pending = host.requests.last()
        controller.pause()
        assertFalse(pending.isCurrent())
        assertEquals(SystemLogsState(), controller.state.value)
        controller.refresh(); runCurrent()
        assertEquals(2, host.requests.size)
        controller.resume(); runCurrent()
        assertEquals(3, host.requests.size)
        controller.pause()
    }

    @Test fun `errors malformed replies and retries are explicit not an empty success`() = runTest {
        val host = Host()
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, null))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        val envelope = "sensitive exception".encodeToByteArray()
        host.replies[0].complete(GatewayHttpResult.Rejected(401, "raw private error", envelope)); runCurrent()
        assertEquals(SystemLogsController.LOAD_FAILED, controller.state.value.error)
        assertTrue(envelope.all { it == 0.toByte() })
        controller.refresh(); runCurrent()
        host.answer(1, """{"lines":[4]}"""); runCurrent()
        assertNotNull(controller.state.value.error)
        controller.refresh(); runCurrent()
        host.answer(2, """{"file":"agent","lines":[]}"""); runCurrent()
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.lines.isEmpty())
        controller.pause()
    }

    @Test fun `parser refuses malformed oversized row counts and preserves valid empty`() {
        assertNull(parseLogTail("{}".encodeToByteArray()))
        assertNull(parseLogTail("invalid".encodeToByteArray()))
        assertNull(parseLogTail("{\"lines\":[null]}".encodeToByteArray()))
        assertNull(parseLogTail(("{\"lines\":[" + List(201) { "\"x\"" }.joinToString(",") + "]}").encodeToByteArray()))
        assertEquals(emptyList<String>(), parseLogTail("{\"lines\":[]}".encodeToByteArray()))
    }

    @Test fun `redaction spans lines and precedes cap for credentials network identities and PEM`() {
        val result = sanitizeLogTail(listOf(
            "INFO Authorization: Bearer synthetic-bearer",
            "Authorization: Basic synthetic-basic",
            "hostname=synthetic-machine fingerprint=synthetic-print IPv6 2001:db8::8",
            "session_token='synthetic session value' api_key=synthetic-key",
            "host=synthetic.example IP 192.0.2.9 SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "https://synthetic.example/path?token=synthetic-query",
            "-----BEGIN PRIVATE KEY-----", "synthetic-key-body", "-----END PRIVATE KEY-----",
            "x".repeat(4000) + " password=synthetic-tail",
        )).joinToString("\n")
        for (value in listOf("synthetic-bearer", "synthetic-basic", "synthetic-machine", "synthetic-print", "2001:db8::8", "synthetic session value", "synthetic-key", "synthetic.example", "192.0.2.9", "SHA256:AAAA", "synthetic-query", "synthetic-key-body", "synthetic-tail")) {
            assertFalse(value, result.contains(value))
        }
        assertTrue(result.lines().all { it.length <= 1000 })
        assertTrue(result.contains("INFO"))
    }

    @Test fun `raw structured private data crosses JSON parser to UI state only after masking`() = runTest {
        val pemFence = "-".repeat(5)
        val completePem = "$pemFence${"BEGIN PRIVATE KEY"}$pemFence\nsynthetic-key-body\n$pemFence${"END PRIVATE KEY"}$pemFence"
        val truncatedPem = "$pemFence${"BEGIN RSA PRIVATE KEY"}$pemFence\nsynthetic-unterminated-key"
        val raw = listOf(
            "2026-10-22 12:34:56,123 INFO reading /Users/synthetic-person/private/project",
            "INFO failed at /home/synthetic-person/workspace",
            "INFO Windows C:\\Users\\synthetic-person\\private\\project",
            "INFO quoted \"C:\\Users\\synthetic-person\\private space\\project\"",
            "INFO connected to synthetic-host",
            "INFO server=synthetic-server peer: synthetic-peer",
            "aws_secret_access_key=synthetic-aws-secret aws_access_key_id=synthetic-aws-id",
            "aws_session_token=synthetic-aws-session aws_security_token=synthetic-aws-security",
            "Authorization: Bearer synthetic-bearer",
            "Authorization: Basic c3ludGhldGljLWJhc2lj",
            "api_key='synthetic first secret\nsynthetic continuation'",
            completePem,
            "INFO after complete key",
            truncatedPem,
        )
        assertTrue(completePem.contains("synthetic-key-body"))
        assertTrue(completePem.startsWith(pemFence + "BEGIN PRIVATE KEY"))
        assertTrue(truncatedPem.contains("synthetic-unterminated-key"))
        assertTrue(raw.any { it.contains("synthetic-bearer") })
        assertTrue(raw.any { it.contains("c3ludGhldGljLWJhc2lj") })
        val host = Host()
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, null))
        val controller = SystemLogsController(backgroundScope, owners, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) { owners.value }
        controller.resume(); runCurrent()
        val envelope = kotlinx.serialization.json.JsonObject(mapOf("lines" to
            kotlinx.serialization.json.JsonArray(raw.map { kotlinx.serialization.json.JsonPrimitive(it) }))).toString()
        val bytes = host.answer(0, envelope)
        runCurrent()
        val visible = controller.state.value.lines.joinToString("\n")
        assertNull(controller.state.value.error)
        assertTrue(visible.startsWith("2026-10-22 12:34:56,123 INFO"))
        assertTrue(visible.contains("INFO after complete key"))
        assertFalse(visible.contains("synthetic"))
        assertFalse(visible.contains("c3ludGhldGljLWJhc2lj"))
        // Shared SSH redaction can retain a PEM-shaped placeholder. Its markers
        // are not secret material; assert on actual synthetic body bytes above.
        assertFalse(visible.contains("synthetic-key-body"))
        assertFalse(visible.contains("synthetic-unterminated-key"))
        assertTrue(bytes.all { it == 0.toByte() })
        controller.pause()
    }

    @Test fun `decode queued on separate dispatcher is cancelled and acquired bytes wiped on pause`() = runTest {
        val queued = java.util.ArrayDeque<Runnable>()
        val decoder = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                queued.addLast(block)
            }
        }
        val host = Host()
        val owners = MutableStateFlow(SystemLogsOwner(host, 1, null))
        val controller = SystemLogsController(backgroundScope, owners, decoder) { owners.value }
        controller.resume(); runCurrent()
        val bytes = host.answer(0, """{"lines":["INFO ready"]}""")
        runCurrent()
        assertTrue(controller.state.value.loading)
        controller.pause()
        while (queued.isNotEmpty()) queued.removeFirst().run()
        runCurrent()
        assertEquals(SystemLogsState(), controller.state.value)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test(timeout = 5000) fun `alphabetic and almost credential nonmatches consume a bounded tail`() {
        val alphabetic = "a".repeat(250000)
        val almost = "a".repeat(250000) + "_tokenn=harmless"
        var checkpoints = 0
        val safe = sanitizeLogTail(listOf(alphabetic, almost)) { checkpoints++ }
        assertEquals(2, safe.size)
        assertTrue(safe.all { it.length <= 1000 })
        assertTrue(checkpoints > 100)
    }

    @Test fun `sanitizer checks cancellation during token scanning`() {
        var checkpoints = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            sanitizeLogTail(listOf("word ".repeat(100000))) {
                if (++checkpoints == 10) throw kotlinx.coroutines.CancellationException("synthetic cancellation")
            }
        }
    }

    @Test fun `truncated quoted credentials redact the remainder rather than leaking words`() {
        for (quote in listOf("'", "\"")) {
            val tail = sanitizeLogTail(listOf("api_key=${quote}synthetic first secret", "synthetic continuation"))
                .joinToString("\n")
            assertFalse(tail.contains("synthetic"))
            assertFalse(tail.contains("continuation"))
        }
    }

    @Test fun `long plain alphabetic records stay bounded without repeated suffix scans`() {
        val safe = sanitizeLogTail(listOf("x".repeat(32768))).single()
        assertTrue(safe.isNotEmpty())
        assertTrue(safe.length <= 1000)
        assertTrue(safe.all { it == 'x' })
    }

    @Test fun `IP masking preserves ordinary log timestamps`() {
        val line = "2026-10-22 12:34:56,123 INFO ready from 2001:db8::8"
        val safe = sanitizeLogTail(listOf(line)).single()
        assertTrue(safe.startsWith("2026-10-22 12:34:56,123 INFO"))
        assertFalse(safe.contains("2001:db8::8"))
    }

    @Test fun `untrusted long separator and domain shaped lines cannot overflow regex stack`() {
        val lines = sanitizeLogTail(listOf(":".repeat(12000), "a.".repeat(6000)))
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.length <= 1000 })
    }
}
