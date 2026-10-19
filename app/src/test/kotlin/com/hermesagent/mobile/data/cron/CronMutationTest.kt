package com.hermesagent.mobile.data.cron

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CronMutationTest {
    @Test fun `create sends exact draft and confirms persisted named owner and fields`() = runTest {
        val draft = CronDraft(name = "  label  ", prompt = "  raw prompt  ", deliver = "local,custom", model = "model", provider = "provider")
        val row = """{"id":"new/%","profile":"writer","name":"label","prompt":"raw prompt","schedule":{"kind":"cron","expr":"0 9 * * *"},"deliver":"local,custom","model":"model","provider":"provider"}"""
        val wire = CronWire { _, count -> CronWire.ok(if (count == 2) "[$row]" else row) }
        val result = scope(wire).create(draft)
        assertEquals("new/%", (result as CronResult.Success).value.id)
        assertEquals(listOf("POST", "GET", "GET"), wire.requests.map { it.method })
        assertEquals("/api/cron/jobs/new%2F%25", wire.requests.last().path)
        assertEquals("  label  ", body(wire.requests.first()).text("name"))
        assertEquals("  raw prompt  ", body(wire.requests.first()).text("prompt"))
        assertEquals("provider", body(wire.requests.first()).text("provider"))
        assertTrue(wire.requests.all { it.query["profile"] == "writer" })
    }

    @Test fun `create whitespace-only name confirms generated first fifty prompt characters`() = runTest {
        val prompt = "01234567890123456789012345678901234567890123456789 more"
        val row = """{"id":"job","profile":"writer","name":"01234567890123456789012345678901234567890123456789","prompt":"$prompt","schedule":"0 9 * * *"}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.method == "GET" && request.path == "/api/cron/jobs") "[$row]" else row) }
        assertTrue(scope(wire).create(CronDraft(name = " \t ", prompt = "  $prompt  ")) is CronResult.Success)
        assertEquals(" \t ", body(wire.requests.first()).text("name"))
        assertEquals("  $prompt  ", body(wire.requests.first()).text("prompt"))
    }

    @Test fun `create generated name counts Python code points not UTF16 units`() = runTest {
        for ((prompt, expected) in listOf(
            "😀".repeat(30) + " daily report" to "😀".repeat(30) + " daily report",
            "😀".repeat(49) + "ab" to "😀".repeat(49) + "a",
        )) {
            val row = """{"id":"job","profile":"writer","name":"$expected","prompt":"$prompt","schedule":"0 9 * * *"}"""
            val wire = CronWire { request, _ -> CronWire.ok(if (request.method == "GET" && request.path == "/api/cron/jobs") "[$row]" else row) }
            assertTrue(scope(wire).create(CronDraft(prompt = "  $prompt  ")) is CronResult.Success)
            assertEquals("", body(wire.requests.first()).text("name"))
            assertEquals("  $prompt  ", body(wire.requests.first()).text("prompt"))
            assertEquals(listOf("POST", "GET", "GET"), wire.requests.map { it.method })
        }
    }

    @Test fun `create readback mismatch is uncertain and never repeats post`() = runTest {
        val row = """{"id":"saved","profile":"writer","prompt":"different","schedule":"0 9 * * *"}"""
        val wire = CronWire { _, count -> CronWire.ok(if (count == 2) "[$row]" else row) }
        assertEquals(CronResult.Failure(CronFailure.Unconfirmed, true, "saved"), scope(wire).create(CronDraft(prompt = "wanted")))
        assertEquals(1, wire.requests.count { it.method == "POST" })
    }

    @Test fun `update wraps only changed fields and preserves unknown schedule and script configuration`() = runTest {
        val original = CronJob("job", "writer", "Before", "", "opaque recurrence", model = "old", provider = "old-provider", script = "daily.sh", noAgent = true)
        val row = """{"id":"job","profile":"writer","name":"After","prompt":"","schedule":{"kind":"future","opaque":42,"display":"opaque recurrence"},"model":"old","provider":"old-provider","script":"daily.sh","no_agent":true}"""
        val wire = CronWire { request, count -> CronWire.ok(if (request.path == "/api/cron/jobs") "[${if (count == 1) row.replace("After", "Before") else row}]" else row) }
        val updated = (scope(wire).update(original, CronDraft.from(original).copy(name = "After", model = null, provider = null)) as CronResult.Success).value
        assertTrue(updated.scriptOnly)
        assertEquals(buildJsonObject { putJsonObject("updates") { put("name", "After") } }, body(wire.requests.single { it.method == "PUT" }))
        assertEquals(listOf("GET", "PUT", "GET", "GET"), wire.requests.map { it.method })
    }

    @Test fun `update padded name confirms backend trim without changing payload`() = normalizedNameUpdate("  After  ", "After")

    @Test fun `update cleared name confirms generated prompt label without changing payload`() = normalizedNameUpdate("", "keep")

    @Test fun `update whitespace name confirms generated prompt label without changing payload`() = normalizedNameUpdate(" \t ", "keep")

    private fun normalizedNameUpdate(name: String, expected: String) = runTest {
        val original = CronJob("job", "writer", name = "Before", prompt = "keep", schedule = "0 9 * * *")
        val row = """{"id":"job","profile":"writer","name":"$expected","prompt":"keep","schedule":"0 9 * * *"}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs") "[$row]" else row) }
        assertTrue("name=$name", scope(wire).update(original, CronDraft.from(original).copy(name = name)) is CronResult.Success)
        assertEquals(buildJsonObject { putJsonObject("updates") { put("name", name) } }, body(wire.requests.single { it.method == "PUT" }))
        assertEquals("/api/cron/jobs/job", wire.requests.last().path)
        assertEquals("GET", wire.requests.last().method)
    }

    @Test fun `generated update label must agree with fresh detail instead of accepting receipt alone`() = runTest {
        val original = CronJob("job", "writer", name = "Before", prompt = "keep", schedule = "0 9 * * *")
        val receipt = """{"id":"job","profile":"writer","name":"keep","prompt":"keep","schedule":"0 9 * * *"}"""
        val wire = CronWire { request, _ -> CronWire.ok(when {
            request.method == "PUT" -> receipt
            request.path == "/api/cron/jobs" -> "[$receipt]"
            else -> receipt.replace("\"name\":\"keep\"", "\"name\":\"different\"")
        }) }
        assertEquals(CronResult.Failure(CronFailure.Unconfirmed, true, "job"), scope(wire).update(original, CronDraft.from(original).copy(name = "")))
        assertEquals(1, wire.requests.count { it.method == "PUT" })
    }

    @Test fun `agent reset clears both model axes and verifies changed persisted fields`() = runTest {
        val original = CronJob("job", "writer", prompt = "keep", schedule = "0 9 * * *", model = "model", provider = "provider")
        val row = """{"id":"job","profile":"writer","prompt":"keep","schedule":"0 9 * * *","model":null,"provider":null}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs") "[$row]" else row) }
        assertTrue(scope(wire).update(original, CronDraft.from(original).copy(model = null)) is CronResult.Success)
        assertEquals(buildJsonObject { putJsonObject("updates") { put("model", JsonNull); put("provider", JsonNull) } }, body(wire.requests.single { it.method == "PUT" }))
    }

    @Test fun `mutation with vanished owner never dispatches and foreign fallback receipt is uncertain`() = runTest {
        val job = CronJob("same", "writer", prompt = "keep", schedule = "0 9 * * *")
        val vanished = CronWire { _, _ -> CronWire.ok("[]") }
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), scope(vanished).update(job, CronDraft.from(job).copy(name = "changed")))
        assertEquals(listOf("GET"), vanished.requests.map { it.method })
        var foreignMutated = false
        val fallback = CronWire { request, _ ->
            if (request.method == "PUT") foreignMutated = true // models actual backend fallback after preflight
            CronWire.ok(if (request.method == "GET") """[{"id":"same","profile":"writer"}]""" else """{"id":"same","profile":"other","name":"changed"}""")
        }
        assertEquals(CronResult.Failure(CronFailure.InvalidResponse, true), scope(fallback).update(job, CronDraft.from(job).copy(name = "changed")))
        assertTrue("client cannot roll back backend cross-profile fallback", foreignMutated)
        assertEquals(1, fallback.requests.count { it.method == "PUT" })
    }

    @Test fun `pause resume require owner receipt and persisted enabled state`() = runTest {
        for (action in listOf(CronAction.Pause, CronAction.Resume)) {
            val enabled = action == CronAction.Resume
            val row = """{"id":"job","profile":"writer","enabled":$enabled,"state":"${if (enabled) "scheduled" else "paused"}"}"""
            val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs") "[$row]" else row) }
            assertEquals(CronResult.Success(Unit), scope(wire).act(CronJob("job", "writer"), action))
            val mutation = wire.requests.single { it.method == "POST" }
            assertEquals("/api/cron/jobs/job/${if (enabled) "resume" else "pause"}", mutation.path)
            assertNotNull(mutation.body)
            assertEquals(listOf("GET", "POST", "GET", "GET"), wire.requests.map { it.method })
        }
    }

    @Test fun `delete verifies scoped absence without item fallback and retained record is unconfirmed`() = runTest {
        for (removed in listOf(true, false)) {
            val row = """[{"id":"job","profile":"writer"}]"""
            val wire = CronWire { request, count -> CronWire.ok(if (request.method == "DELETE") """{"ok":true}""" else if (count > 1 && removed) "[]" else row) }
            assertEquals(if (removed) CronResult.Success(Unit) else CronResult.Failure(CronFailure.Unconfirmed, true), scope(wire).act(CronJob("job", "writer"), CronAction.Delete))
            assertEquals(listOf("GET", "DELETE", "GET"), wire.requests.map { it.method })
            assertNull(wire.requests[1].body)
        }
    }

    @Test fun `trigger executes once checks persisted execution failure and timeout remains uncertain`() = runTest {
        for (status in listOf("ok", "failed", "timeout")) {
            val row = """{"id":"job","profile":"writer","last_run_at":"after","last_error":${if (status == "failed") "\"execution failed\"" else "null"}}"""
            val wire = CronWire { request, count ->
                if (request.method == "POST" && status == "timeout") CronWire.reject(0)
                else CronWire.ok(if (count == 1) "[${row.replace("after", "before")}]" else if (request.path == "/api/cron/jobs") "[$row]" else row)
            }
            val actual = scope(wire).act(CronJob("job", "writer", lastRunAt = "before"), CronAction.Trigger)
            assertEquals(when (status) {
                "ok" -> CronResult.Success(Unit)
                "failed" -> CronResult.Failure(CronFailure.Refused, true, "job")
                else -> CronResult.Failure(CronFailure.Unreachable, true)
            }, actual)
            assertEquals(1, wire.requests.count { it.method == "POST" })
            assertEquals(86_400_000L, wire.requests.single { it.method == "POST" }.timeoutMillis)
        }
    }

    @Test fun `instantiate posts exact key values then reads back saved identity`() = runTest {
        val row = """{"id":"saved","profile":"writer","prompt":"filled","schedule":"0 9 * * *"}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs") "[$row]" else row) }
        val result = scope(wire).instantiate("plugin:daily", mapOf("time" to "09:00", "topic" to " raw "))
        assertEquals("saved", (result as CronResult.Success).value.id)
        assertEquals("/api/cron/blueprints/instantiate", wire.requests.first().path)
        assertEquals(buildJsonObject { put("blueprint", "plugin:daily"); putJsonObject("values") { put("time", "09:00"); put("topic", " raw ") } }, body(wire.requests.first()))
        assertEquals(listOf("POST", "GET", "GET"), wire.requests.map { it.method })
    }

    @Test fun `424 retains saved identity and wipes structured envelope without retrying add`() = runTest {
        for (blueprint in listOf(false, true)) {
            val wire = CronWire { _, _ -> CronWire.reject(424, """{"detail":{"job_id":"saved/%","job_saved":true,"scheduler_registered":false,"retry_create":false,"error":"private explanation"}}""") }
            val scope = scope(wire)
            val result = if (blueprint) scope.instantiate("daily", emptyMap()) else scope.create(CronDraft(prompt = "raw"))
            assertEquals(CronResult.Failure(CronFailure.SavedRegistrationFailed, true, "saved/%"), result)
            assertEquals(1, wire.requests.size)
            assertTrue(wire.requests.single().captureEnvelope)
            assertTrue(wire.buffers.single().all { it == 0.toByte() })
            assertFalse(result.toString().contains("private explanation"))
        }
    }

    @Test fun `malformed partial save envelope stays uncertain without inventing saved identity`() = runTest {
        val wire = CronWire { _, _ -> CronWire.reject(424, """{"detail":{"job_id":"foreign","job_saved":false}}""") }
        assertEquals(CronResult.Failure(CronFailure.Unconfirmed, true), scope(wire).create(CronDraft(prompt = "raw")))
        assertTrue(wire.buffers.single().all { it == 0.toByte() })
    }

    @Test fun `natural interval create confirms normalized receipt without rewriting raw request`() = runTest {
        val row = """{"id":"job","profile":"writer","name":"keep","prompt":"keep","schedule":{"kind":"interval","minutes":60,"display":"every 1 hour"}}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs" && request.method == "GET") "[$row]" else row) }
        assertTrue(scope(wire).create(CronDraft(prompt = "keep", schedule = "every 1h")) is CronResult.Success)
        assertEquals("every 1h", body(wire.requests.first()).text("schedule"))
    }

    @Test fun `six word natural schedule create confirms normalized receipt and fresh detail`() = runTest {
        val requested = "every monday and tuesday at 9am"
        val row = """{"id":"job","profile":"writer","name":"keep","prompt":"keep","schedule":{"kind":"cron","expr":"0 9 * * 1,2","display":"$requested"}}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs" && request.method == "GET") "[$row]" else row) }
        assertEquals("0 9 * * 1,2", (scope(wire).create(CronDraft(prompt = "keep", schedule = requested)) as CronResult.Success).value.schedule)
        assertEquals(requested, body(wire.requests.first()).text("schedule"))
        assertEquals(listOf("POST", "GET", "GET"), wire.requests.map { it.method })
    }

    @Test fun `six word natural schedule update confirms normalized receipt and fresh detail`() = runTest {
        val original = CronJob("job", "writer", name = "keep", prompt = "keep", schedule = "0 9 * * *")
        val requested = "every monday and tuesday at 9am"
        val row = """{"id":"job","profile":"writer","name":"keep","prompt":"keep","schedule":{"kind":"cron","expr":"0 9 * * 1,2","display":"$requested"}}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs") "[$row]" else row) }
        assertEquals("0 9 * * 1,2", (scope(wire).update(original, CronDraft.from(original).copy(schedule = requested)) as CronResult.Success).value.schedule)
        assertEquals(requested, body(wire.requests.single { it.method == "PUT" }).getValue("updates").jsonObject.text("schedule"))
    }

    @Test fun `natural and expression schedule confirmation refuses contradictory fresh detail`() = runTest {
        for (requested in listOf("every monday and tuesday at 9am", "0 9 * * MON,TUE", "0 9 * * * 30")) {
            val normalized = if (requested.startsWith("every")) "0 9 * * 1,2" else requested
            val row = """{"id":"job","profile":"writer","name":"keep","prompt":"keep","schedule":{"kind":"cron","expr":"$normalized"}}"""
            val wire = CronWire { request, _ -> CronWire.ok(when {
                request.method == "POST" -> row
                request.path == "/api/cron/jobs" -> "[$row]"
                else -> row.replace(normalized, "0 10 * * *")
            }) }
            assertEquals(CronResult.Failure(CronFailure.Unconfirmed, true, "job"), scope(wire).create(CronDraft(prompt = "keep", schedule = requested)))
            assertEquals(1, wire.requests.count { it.method == "POST" })
        }
    }

    @Test fun `finite trigger confirms retained completed record from fresh exact detail`() = runTest {
        for (schedule in listOf("""{"kind":"once","run_at":"2026-10-19T09:00:00+00:00"}""", """{"kind":"interval","minutes":60}""")) {
            val before = """{"id":"job","profile":"writer","enabled":true,"last_run_at":"before","schedule":$schedule,"repeat":{"times":1,"completed":0}}"""
            val completed = """{"id":"job","profile":"writer","enabled":false,"state":"completed","last_run_at":"after","schedule":$schedule,"repeat":{"times":1,"completed":1},"next_run_at":null}"""
            val wire = CronWire { request, count -> CronWire.ok(when {
                count == 1 -> "[$before]"
                request.path == "/api/cron/jobs" -> "[$completed]"
                else -> completed
            }) }
            assertEquals(CronResult.Success(Unit), scope(wire).act(CronJob("job", "writer"), CronAction.Trigger))
            assertEquals("/api/cron/jobs/job", wire.requests.last().path)
            assertEquals("GET", wire.requests.last().method)
            assertEquals(1, wire.requests.count { it.method == "POST" })
        }
    }

    @Test fun `retained completed trigger uses persisted execution failure and requires a new run`() = runTest {
        for (status in listOf("failed", "unchanged")) {
            val before = """{"id":"job","profile":"writer","last_run_at":"before"}"""
            val receipt = """{"id":"job","profile":"writer","enabled":false,"state":"completed","last_run_at":"after","last_error":null}"""
            val persisted = if (status == "failed") receipt.replace("\"last_error\":null", "\"last_error\":\"execution failed\"") else receipt.replace("after", "before")
            val wire = CronWire { request, count -> CronWire.ok(when {
                count == 1 -> "[$before]"
                request.method == "POST" -> receipt
                request.path == "/api/cron/jobs" -> "[$persisted]"
                else -> persisted
            }) }
            assertEquals(CronResult.Failure(if (status == "failed") CronFailure.Refused else CronFailure.Unconfirmed, true, "job"),
                scope(wire).act(CronJob("job", "writer"), CronAction.Trigger))
            assertEquals("/api/cron/jobs/job", wire.requests.last().path)
            assertEquals(1, wire.requests.count { it.method == "POST" })
        }
    }

    @Test fun `one-shot trigger confirms completed receipt through scoped absence not fallback item`() = runTest {
        val before = """[{"id":"job","profile":"writer","last_run_at":"before"}]"""
        val wire = CronWire { request, count -> CronWire.ok(when {
            count == 1 -> before
            request.method == "POST" -> """{"id":"job","profile":"writer","enabled":false,"state":"completed","last_run_at":"before"}"""
            else -> "[]"
        }) }
        assertEquals(CronResult.Success(Unit), scope(wire).act(CronJob("job", "writer"), CronAction.Trigger))
        assertEquals(listOf("GET", "POST", "GET"), wire.requests.map { it.method })
    }

    @Test fun `create accepts backend prompt normalization and generated name without altering draft`() = runTest {
        val row = """{"id":"job","profile":"writer","name":"keep","prompt":"keep","schedule":"0 9 * * *"}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs" && request.method == "GET") "[$row]" else row) }
        assertTrue(scope(wire).create(CronDraft(prompt = "  keep  ")) is CronResult.Success)
        assertEquals("  keep  ", body(wire.requests.first()).text("prompt"))
        assertEquals("", body(wire.requests.first()).text("name"))
    }

    @Test fun `unknown schedule without display remains untouched when editing name`() = runTest {
        val job = CronJob("job", "writer", name = "Before", prompt = "keep")
        val row = """{"id":"job","profile":"writer","name":"After","prompt":"keep","schedule":{"kind":"future","opaque":42}}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs") "[$row]" else row) }
        assertTrue(scope(wire).update(job, CronDraft.from(job).copy(name = "After")) is CronResult.Success)
        assertEquals(buildJsonObject { putJsonObject("updates") { put("name", "After") } }, body(wire.requests.single { it.method == "PUT" }))
    }

    @Test fun `unrepresentable dot path segments are refused not normalized to another route`() = runTest {
        val wire = CronWire { _, _ -> error("no route") }
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), scope(wire).detail(".."))
        assertEquals(CronResult.Failure(CronFailure.InvalidScope), scope(wire).detail("."))
    }

    @Test fun `successful pause receipt cannot replace contradictory persisted readback`() = runTest {
        val wire = CronWire { request, _ -> CronWire.ok(if (request.method == "POST") """{"id":"job","profile":"writer","enabled":false,"state":"paused"}""" else if (request.path == "/api/cron/jobs") """[{"id":"job","profile":"writer","enabled":true}]""" else """{"id":"job","profile":"writer","enabled":true}""") }
        assertEquals(CronResult.Failure(CronFailure.Unconfirmed, true, "job"), scope(wire).act(CronJob("job", "writer"), CronAction.Pause))
        assertEquals(1, wire.requests.count { it.method == "POST" })
    }

    @Test fun `failure and malformed successful mutation bodies are wiped and never repeated`() = runTest {
        for (malformed in listOf(false, true)) {
            val wire = CronWire { _, _ -> if (malformed) CronWire.ok("not JSON") else CronWire.reject(403, "private refusal") }
            val result = scope(wire).create(CronDraft(prompt = "raw"))
            assertEquals(CronResult.Failure(if (malformed) CronFailure.InvalidResponse else CronFailure.Refused, true), result)
            assertEquals(1, wire.requests.size)
            assertTrue(wire.buffers.single().all { it == 0.toByte() })
        }
    }

    @Test fun `update confirms backend normalized optional pins without changing transmitted draft`() = runTest {
        val original = CronJob("job", "writer", prompt = "keep", schedule = "0 9 * * *")
        val row = """{"id":"job","profile":"writer","prompt":"keep","schedule":"0 9 * * *","model":"model","provider":"provider"}"""
        val wire = CronWire { request, _ -> CronWire.ok(if (request.path == "/api/cron/jobs") "[$row]" else row) }
        assertTrue(scope(wire).update(original, CronDraft.from(original).copy(model = " model ", provider = " provider ")) is CronResult.Success)
        assertEquals(" model ", body(wire.requests.single { it.method == "PUT" }).getValue("updates").jsonObject.text("model"))
    }

    @Test fun `delete owner race is not distinguishable from success with upstream ownerless receipt`() = runTest {
        var foreignDeleted = false
        val wire = CronWire { request, count ->
            if (request.method == "DELETE") foreignDeleted = true // A vanished after preflight; server falls back to B
            CronWire.ok(when {
                count == 1 -> """[{"id":"same","profile":"writer"}]"""
                request.method == "DELETE" -> """{"ok":true}"""
                else -> "[]"
            })
        }
        // This characterizes an upstream limitation, NOT atomic owner isolation.
        // Only a strict backend owner precondition can disambiguate these bytes.
        assertEquals(CronResult.Success(Unit), scope(wire).act(CronJob("same", "writer"), CronAction.Delete))
        assertTrue(foreignDeleted)
    }

    private fun scope(wire: GatewayHttp) = GatewayCronApi({ wire }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
    private fun body(request: GatewayHttpRequest): JsonObject = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
