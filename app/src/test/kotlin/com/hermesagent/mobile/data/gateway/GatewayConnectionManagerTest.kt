package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.AuthMethod
import com.hermesagent.mobile.data.ssh.CommandOutcome
import com.hermesagent.mobile.data.ssh.ExecOutcome
import com.hermesagent.mobile.data.ssh.HostKeyVerdict
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.data.ssh.SshForward
import com.hermesagent.mobile.data.ssh.SshOpenResult
import com.hermesagent.mobile.data.ssh.SshTransport
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val FIXTURE_REMOTE_HOME = "/srv/test-home"
private const val FIXTURE_HERMES_ROOT = "$FIXTURE_REMOTE_HOME/.hermes"

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayConnectionManagerTest {

    @Test
    fun `connected means SSH forward authenticated HTTP ownership and JSON RPC all passed`() = runTest {
        val transport = LifecycleTransport()
        val verifier = RecordingVerifier()
        val rpc = ReadinessRpc()
        val manager = manager(transport, verifier, rpc)

        val result = manager.connect(profile(), SshCredential.none())

        assertTrue("Expected Connected, got $result with state ${manager.state.value.status}", result is GatewayConnectResult.Connected)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertEquals("127.0.0.1", transport.forward.bindAddress)
        assertEquals(43117, transport.forward.remotePort)
        assertEquals("08090a0b0c0d0e0f", verifier.expectedOwnerNonce)
        assertEquals(1, rpc.calls.count { it == "session.list" })
        assertTrue(verifier.tokenCopy.any { it.toInt() != 0 })
        val remoteCommands = transport.commands.joinToString("\n")
        assertTrue(
            remoteCommands.contains(
                "env -u HERMES_PROFILE HERMES_HOME='$FIXTURE_HERMES_ROOT/profiles/test-profile' " +
                    "'/usr/local/bin/hermes' --profile 'test-profile' serve --help",
            ),
        )
        assertTrue(
            remoteCommands.contains(
                "env -u HERMES_PROFILE HERMES_HOME='$FIXTURE_HERMES_ROOT/profiles/test-profile' " +
                    "HERMES_DESKTOP=1 '/usr/local/bin/hermes' --profile 'test-profile' serve",
            ),
        )
        assertTrue(
            remoteCommands.contains(
                "$FIXTURE_HERMES_ROOT/desktop-ssh/" +
                    "0123456789abcdef0123456789abcdef/08090a0b0c0d0e0f.token",
            ),
        )
        assertTrue(
            remoteCommands.contains(
                "$FIXTURE_HERMES_ROOT/profiles/test-profile/desktop-ssh/" +
                    "0123456789abcdef0123456789abcdef/backend.lock.json",
            ),
        )
        assertEquals(
            "$FIXTURE_HERMES_ROOT/profiles/test-profile",
            transport.lastLock().getValue("hermesHome").jsonPrimitive.content,
        )

        manager.disconnect()
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
        assertTrue(transport.forward.closed)
        assertTrue(rpc.rpcClosed)
        assertTrue(transport.closed)
        assertTrue(transport.commands.any { it == "kill -TERM -- 4242" })
    }

    @Test
    fun `adopted served token authenticates WebSocket and fingerprints final lock`() = runTest {
        val transport = LifecycleTransport()
        val verifier = RecordingVerifier()
        val rpc = ReadinessRpc()
        val servedToken = "served-token_fixture-789".toByteArray(Charsets.US_ASCII)
        val expectedFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(servedToken)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            .take(32)
        var websocketToken = byteArrayOf()
        val manager = manager(
            transport = transport,
            verifier = verifier,
            rpc = rpc,
            servedTokenResolver = GatewayServedTokenResolver { servedToken },
            observeRpcToken = { websocketToken = it.copyOf() },
        )

        val result = manager.connect(profile(), SshCredential.none())

        assertTrue(result is GatewayConnectResult.Connected)
        assertTrue(verifier.tokenCopy.any { it != 0.toByte() })
        assertFalse(verifier.tokenCopy.contentEquals(websocketToken))
        assertEquals("served-token_fixture-789", websocketToken.toString(Charsets.US_ASCII))
        assertEquals(43117, transport.lastLock().getValue("port").jsonPrimitive.content.toInt())
        assertEquals(
            expectedFingerprint,
            transport.lastLock().getValue("tokenFingerprint").jsonPrimitive.content,
        )
        assertEquals(
            "~/.hermes/profiles/test-profile/desktop-ssh/" +
                "0123456789abcdef0123456789abcdef/08090a0b0c0d0e0f.log",
            transport.lastLock().getValue("logPath").jsonPrimitive.content,
        )
        assertTrue("the backend clears its adopted mutable token after WebSocket open", servedToken.all { it == 0.toByte() })
        websocketToken.fill(0)
        manager.disconnect()
    }

    @Test
    fun `a non-loopback forward fails closed before HTTP or WebSocket`() = runTest {
        val transport = LifecycleTransport(bindAddress = "0.0.0.0")
        val verifier = RecordingVerifier()
        val rpc = ReadinessRpc()
        val manager = manager(transport, verifier, rpc)

        val result = manager.connect(profile(), SshCredential.none())

        assertTrue(result is GatewayConnectResult.Failed)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertFalse(verifier.called)
        assertTrue(rpc.calls.isEmpty())
        assertTrue(transport.forward.closed)
        assertTrue(transport.closed)
    }

    @Test
    fun `host key review never starts remote lifecycle`() = runTest {
        val transport = LifecycleTransport()
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
            sshOpen = { _, _ -> SshOpenResult.HostKeyPending("SHA256:first-use", "ssh-ed25519") },
            lifecycleFactory = { error("lifecycle must not start") },
            httpVerifier = GatewayReadinessVerifier { _, _, _ -> error("HTTP must not run") },
            rpcOpen = { _, _ -> error("WebSocket must not run") },
        )

        val result = manager.connect(profile().copy(acceptedFingerprint = null), SshCredential.none())

        assertTrue(result is GatewayConnectResult.HostKeyPending)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun `WebSocket closure tears down connection and exposes reconnect`() = runTest {
        val transport = LifecycleTransport()
        val rpc = ReadinessRpc()
        val manager = manager(transport, RecordingVerifier(), rpc, this)
        manager.connect(profile(), SshCredential.none())

        runCurrent()
        assertEquals(1, rpc.closeSubscribers)
        rpc.failFromServer()
        runCurrent()

        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals("The Gateway connection closed. Reconnect to continue.", manager.state.value.message)
        manager.disconnect()
        assertTrue(transport.closed)
    }

    @Test
    fun `cancelling an in-progress connect does not leave a stale connecting state`() = runTest {
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
            sshOpen = { _, credential ->
                credential.clear()
                awaitCancellation()
            },
            lifecycleFactory = { error("lifecycle must not start") },
            httpVerifier = GatewayReadinessVerifier { _, _, _ -> error("HTTP must not run") },
            rpcOpen = { _, _ -> error("WebSocket must not run") },
        )
        val connecting = async { manager.connect(profile(), SshCredential.none()) }
        runCurrent()
        assertEquals(GatewayConnectionStatus.Connecting, manager.state.value.status)

        connecting.cancelAndJoin()
        runCurrent()

        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
    }

    @Test
    fun `cancellation after served token parsing wipes candidate and lifecycle`() = runTest {
        val transport = LifecycleTransport()
        val produced = CompletableDeferred<ByteArray>()
        var connecting: kotlinx.coroutines.Deferred<GatewayConnectResult>? = null
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        "<script>window.__HERMES_SESSION_TOKEN__=\"served-cancellation_fixture\";</script>"
                            .toResponseBody("text/html; charset=utf-8".toMediaType()),
                    )
                    .build()
            }
            .build()
        val resolver = GatewayDashboardTokenResolver(http) { candidate ->
            produced.complete(candidate)
            checkNotNull(connecting).cancel()
        }
        val manager = manager(
            transport = transport,
            verifier = RecordingVerifier(),
            rpc = ReadinessRpc(),
            servedTokenResolver = resolver,
        )
        val connectJob = async { manager.connect(profile(), SshCredential.none()) }
        connecting = connectJob
        runCurrent()

        val candidate = produced.await()
        connectJob.cancelAndJoin()
        runCurrent()

        assertTrue(connectJob.isCancelled)
        assertTrue(candidate.all { it == 0.toByte() })
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
        assertTrue(transport.forward.closed)
        assertTrue(transport.closed)
        assertTrue(transport.commands.any { it == "kill -TERM -- 4242" })
    }

    @Test
    fun `a network change during connect retains reconnect attention after cancellation`() = runTest {
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
            sshOpen = { _, credential ->
                credential.clear()
                awaitCancellation()
            },
            lifecycleFactory = { error("lifecycle must not start") },
            httpVerifier = GatewayReadinessVerifier { _, _, _ -> error("HTTP must not run") },
            rpcOpen = { _, _ -> error("WebSocket must not run") },
        )
        val connecting = async { manager.connect(profile(), SshCredential.none()) }
        runCurrent()
        assertEquals(GatewayConnectionStatus.Connecting, manager.state.value.status)

        manager.networkChanged()
        runCurrent()

        assertTrue(connecting.isCancelled)
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals("The network changed. Reconnect to the Gateway.", manager.state.value.message)
    }

    @Test
    fun `a queued stale network change cannot tear down a newer connected intent`() = runTest {
        val transport = LifecycleTransport()
        val manager = manager(transport, RecordingVerifier(), ReadinessRpc())
        manager.networkChanged()
        val result = manager.connect(profile(), SshCredential.none())
        runCurrent()

        assertTrue(result is GatewayConnectResult.Connected)
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        assertFalse(transport.closed)

        manager.disconnect()
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
    }

    @Test
    fun `managed ssh asks for reconnect after network recovery`() = runTest {
        val transport = LifecycleTransport()
        val manager = manager(transport, RecordingVerifier(), ReadinessRpc(), managerScope = this)
        assertTrue(manager.connect(profile(), SshCredential.none()) is GatewayConnectResult.Connected)

        manager.networkAvailabilityChanged(false)
        runCurrent()
        assertEquals("Waiting for a network connection.", manager.state.value.message)
        transport.closedSignal.await()

        manager.networkAvailabilityChanged(true)
        runCurrent()
        assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
        assertEquals("The network changed. Reconnect to the Gateway.", manager.state.value.message)
        manager.disconnect()
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        transport: LifecycleTransport,
        verifier: RecordingVerifier,
        rpc: ReadinessRpc,
        managerScope: CoroutineScope = backgroundScope,
        servedTokenResolver: GatewayServedTokenResolver = GatewayServedTokenResolver { null },
        observeRpcToken: (ByteArray) -> Unit = {},
    ) = GatewayConnectionManager(
        scope = managerScope,
        installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
        sshOpen = { _, _ -> SshOpenResult.Connected(transport, "SSH-2.0-test") },
        lifecycleFactory = { runner ->
            RemoteHermesLifecycle(
                runner,
                randomBytes = { size -> ByteArray(size) { index -> (size + index).toByte() } },
                wait = {},
            )
        },
        httpVerifier = verifier,
        servedTokenResolver = servedTokenResolver,
        rpcOpen = { _, token ->
            observeRpcToken(token)
            rpc
        },
    )

    private fun profile() = HostProfile(
        host = "gateway.example.invalid",
        username = "fixture-user",
        authMethod = AuthMethod.TailscaleSsh,
        acceptedFingerprint = "SHA256:test-fixture-only",
        remoteHermesProfile = "test-profile",
    )

    private class RecordingVerifier : GatewayReadinessVerifier {
        var called = false
        var expectedOwnerNonce = ""
        var tokenCopy = byteArrayOf()

        override suspend fun verify(localPort: Int, token: ByteArray, expectedOwnerNonce: String) {
            called = true
            assertEquals(38123, localPort)
            assertEquals(16, expectedOwnerNonce.length)
            this.expectedOwnerNonce = expectedOwnerNonce
            tokenCopy = token.copyOf()
        }
    }

    private class ReadinessRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        private val closure = MutableSharedFlow<GatewayCloseCause>(replay = 1)
        override val closed = closure
        val closeSubscribers: Int get() = closure.subscriptionCount.value
        val calls = mutableListOf<String>()
        var rpcClosed = false

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += method
            return Json.parseToJsonElement("""{"sessions":[]}""")
        }

        override fun close() {
            rpcClosed = true
        }

        fun failFromServer() {
            closure.tryEmit(GatewayCloseCause.TransportFailure)
        }
    }

    private class LifecycleTransport(bindAddress: String = "127.0.0.1") : SshTransport {
        override val hostKeyVerdict = HostKeyVerdict.Trusted
        override val hostKeyType = "ssh-ed25519"
        override val serverVersion = "SSH-2.0-test"
        val forward = RecordingForward(bindAddress)
        val commands = mutableListOf<String>()
        var closed = false
        val closedSignal = CompletableDeferred<Unit>()
        private var lock: JsonObject? = null
        private var termSent = false

        override fun connect(host: String, port: Int) = Unit
        override fun authenticate(profile: HostProfile, credential: SshCredential) = Unit
        override fun runCommand(command: String, maxBytes: Int, timeoutMillis: Long) = CommandOutcome("", 0)

        override fun exec(command: String, stdin: ByteArray?, maxBytes: Int, timeoutMillis: Long): ExecOutcome {
            commands += command
            if (command == "kill -TERM -- 4242") termSent = true
            if (stdin != null && stdin.toString(Charsets.UTF_8).startsWith("{")) {
                lock = Json.parseToJsonElement(stdin.toString(Charsets.UTF_8)).jsonObject
            }
            val output = when {
                command == "uname -s" -> "Linux"
                command == "uname -m" -> "x86_64"
                command.startsWith("printf '%s' \"\$HOME\"") -> FIXTURE_REMOTE_HOME
                command.contains("\${HERMES_HOME") -> FIXTURE_HERMES_ROOT
                command.startsWith("bash -lc") -> "/usr/local/bin/hermes"
                command.contains("serve --help") -> "--ssh-session-token-file --ssh-owner-nonce"
                command.contains("nohup setsid") -> "4242"
                command.startsWith("cat --") -> "HERMES_BACKEND_READY port=43117\n"
                command.contains("/proc/4242/cmdline") && termSent -> "DEAD\n"
                command.contains("/proc/4242/cmdline") -> lock?.let { ownership ->
                    "ALIVE\n" + buildList {
                        add(ownership.getValue("hermesPath").jsonPrimitive.content)
                        ownership.getValue("profile").jsonPrimitive.content
                            .takeIf(String::isNotBlank)
                            ?.let { profile ->
                                add("--profile")
                                add(profile)
                            }
                        add("serve")
                        add("--isolated")
                        add("--ssh-session-token-file")
                        add(
                            "$FIXTURE_HERMES_ROOT/desktop-ssh/" +
                                ownership.getValue("ownershipId").jsonPrimitive.content + "/" +
                                ownership.getValue("spawnNonce").jsonPrimitive.content + ".token",
                        )
                        add("--ssh-owner-nonce")
                        add(ownership.getValue("spawnNonce").jsonPrimitive.content)
                    }.joinToString("\n")
                }.orEmpty()
                else -> ""
            }
            return ExecOutcome(output.toByteArray(), byteArrayOf(), 0, false)
        }

        override fun openLoopbackForward(remotePort: Int): SshForward {
            forward.remotePort = remotePort
            return forward
        }

        override fun close() {
            forward.close()
            closed = true
            closedSignal.complete(Unit)
        }

        fun lastLock(): JsonObject = checkNotNull(lock)
    }

    private class RecordingForward(override val bindAddress: String) : SshForward {
        override val localPort = 38123
        override val isOpen: Boolean get() = !closed
        var remotePort = 0
        var closed = false

        override fun close() {
            closed = true
        }
    }
}

class GatewayHttpVerifierTest {

    @Test
    fun `ownership readiness accepts the pinned Desktop JSON contract`() = runTest {
        val requests = mutableListOf<Request>()
        val verifier = verifierFor(
            requests,
            "{\"ok\":true}",
            "{\"ok\":true,\"sshOwnerNonce\":\"0123456789abcdef\",\"protocolVersion\":1}",
        )

        verifier.verify(38123, token(), "0123456789abcdef")

        assertEquals(listOf("/api/health", "/api/ssh/ownership"), requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.header("X-Hermes-Session-Token")?.isNotBlank() == true })
    }

    @Test
    fun `ownership readiness rejects a wrong owner nonce`() = runTest {
        assertOwnershipRejected("{\"ok\":true,\"sshOwnerNonce\":\"fedcba9876543210\",\"protocolVersion\":1}")
    }

    @Test
    fun `ownership readiness rejects an unsupported protocol version`() = runTest {
        assertOwnershipRejected("{\"ok\":true,\"sshOwnerNonce\":\"0123456789abcdef\",\"protocolVersion\":2}")
        assertOwnershipRejected("{\"ok\":true,\"sshOwnerNonce\":\"0123456789abcdef\",\"protocolVersion\":\"1\"}")
    }

    @Test
    fun `ownership readiness rejects malformed JSON`() = runTest {
        assertOwnershipRejected("{\"ok\":true")
        assertOwnershipRejected("{\"ok\":{},\"sshOwnerNonce\":\"0123456789abcdef\",\"protocolVersion\":1}")
    }

    @Test
    fun `ownership readiness rejects an unsuccessful ownership response`() = runTest {
        assertOwnershipRejected("{\"ok\":false,\"sshOwnerNonce\":\"0123456789abcdef\",\"protocolVersion\":1}")
    }

    @Test
    fun `ownership readiness rejects an oversized response without reading unbounded input`() = runTest {
        assertOwnershipRejected("x".repeat(64 * 1024 + 1))
    }

    private suspend fun assertOwnershipRejected(ownershipResponse: String) {
        val verifier = verifierFor(mutableListOf(), "{\"ok\":true}", ownershipResponse)
        var failure: Throwable? = null

        try {
            verifier.verify(38123, token(), "0123456789abcdef")
        } catch (t: Throwable) {
            failure = t
        }

        assertTrue(failure is GatewayConnectionException)
    }

    // Pinned Desktop contract: 29112bef099274229cadff79cdff7bf7b99c4b77
    // hermes_cli/web_server.py:3445-3450.
    private fun verifierFor(requests: MutableList<Request>, vararg responses: String): GatewayHttpVerifier {
        val pending = ArrayDeque(responses.toList())
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                val body = pending.removeFirst().toResponseBody("application/json".toMediaType())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()
        return GatewayHttpVerifier(http)
    }

    private fun token(): ByteArray = ByteArray(64) { 0x41 }
}

class GatewayDashboardTokenResolverTest {

    @Test
    fun `exact injected JSON token is consumed and mutable HTML is wiped`() {
        val html = """<html><head><script>window.__HERMES_SESSION_TOKEN__="served\\token\"quoted";window.__HERMES_BASE_PATH__=""</script></head></html>"""
            .toByteArray(Charsets.UTF_8)

        val token = consumeInjectedDashboardToken(html)

        assertEquals("served\\token\"quoted", token?.toString(Charsets.US_ASCII))
        assertTrue(html.all { it == 0.toByte() })
        token?.fill(0)
    }

    @Test
    fun `malformed and non-exact injected forms fall back and wipe their buffers`() {
        val bodies = listOf(
            "<script>window.__HERMES_SESSION_TOKEN__=\"missing-semicolon\"</script>".toByteArray(),
            "<script>window.__HERMES_SESSION_TOKEN__ = \"loose-spacing\";</script>".toByteArray(),
            (
                "<script>window.__HERMES_SESSION_TOKEN__=\"first\";</script>" +
                    "<script>window.__HERMES_SESSION_TOKEN__=\"second\";</script>"
                ).toByteArray(),
            ("<script>window.__HERMES_SESSION_TOKEN__=\"" + "x".repeat(513) + "\";</script>").toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28),
        )

        bodies.forEach { body ->
            assertEquals(null, consumeInjectedDashboardToken(body))
            assertTrue(body.all { it == 0.toByte() })
        }
    }

    @Test
    fun `public loopback root adopts injected token without auth headers or query`() = runTest {
        val requests = mutableListOf<Request>()
        val resolver = resolverFor(
            requests,
            "<html><head><script>window.__HERMES_SESSION_TOKEN__=\"served-token_fixture\";</script></head></html>",
        )

        val token = resolver.resolve(38123)

        assertEquals("served-token_fixture", token?.toString(Charsets.US_ASCII))
        assertEquals(1, requests.size)
        assertEquals("http", requests.single().url.scheme)
        assertEquals("127.0.0.1", requests.single().url.host)
        assertEquals(38123, requests.single().url.port)
        assertEquals("/", requests.single().url.encodedPath)
        assertEquals(null, requests.single().url.query)
        assertEquals(null, requests.single().header("X-Hermes-Session-Token"))
        token?.fill(0)
    }

    @Test
    fun `failed or oversized public dashboard fetch deliberately falls back`() = runTest {
        val failed = resolverFor(mutableListOf(), "unavailable", code = 503)
        val oversized = resolverFor(mutableListOf(), "x".repeat(256 * 1024 + 1))

        assertEquals(null, failed.resolve(38123))
        assertEquals(null, oversized.resolve(38123))
    }

    private fun resolverFor(
        requests: MutableList<Request>,
        responseBody: String,
        code: Int = 200,
    ): GatewayDashboardTokenResolver {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests += chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Unavailable")
                    .body(responseBody.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
        return GatewayDashboardTokenResolver(http)
    }
}
