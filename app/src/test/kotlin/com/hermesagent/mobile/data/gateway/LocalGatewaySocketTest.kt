package com.hermesagent.mobile.data.gateway

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the Local route learns from a real socket, and only from a real socket.
 *
 * Two failures live here, both of them things a fake transport would answer by
 * assumption: the token refusal the readiness check cannot see, and the stopped
 * Hermes that never answers at all.
 *
 * ## The refusal the readiness check cannot see
 *
 * `/api/health` is on the Gateway's public allowlist at the pin
 * (`hermes_cli/dashboard_auth/public_paths.py:33-38` @
 * `29112bef099274229cadff79cdff7bf7b99c4b77`), so a wrong session token passes
 * it with a 200 exactly as a right one does. `/api/ws` is where the token is
 * actually compared (`web_server.py:15925-15931`), and the handler closes
 * *before* accepting (`:17017-17025`) — which ASGI turns into a failed
 * handshake carrying an HTTP status, so the 4401 close code the handler names
 * never reaches the client at all.
 *
 * That makes the socket the only place the Local route learns its token is
 * wrong, and it is why this drives the real OkHttp client against a real
 * loopback server rather than a fake: what the socket layer does with a refused
 * upgrade is the entire question, and a fake would answer it by assumption.
 *
 * ## The Hermes that is not running
 *
 * When `hermes serve` has been stopped — the route's most common failure, since
 * the person owns that process and Android may suspend Termux — nothing is bound
 * to the port and the connect is refused by the kernel. There is no response and
 * no status: the OkHttp call raises an `IOException`, which a status-shaped
 * mapping walks straight past. The device pass on this issue caught that landing
 * on the Remote route's "check the host", advice nobody can act on for a host
 * that is the phone in their hand. Proving the fix needs a port with nothing on
 * it, which is exactly as unfakeable as the refusal above.
 *
 * Robolectric, because the production listener logs through `android.util.Log`
 * and this module deliberately does not stub the Android platform in unit tests
 * (`app/build.gradle.kts`: no `isReturnDefaultValues`). The server is bound on
 * loopback with an ephemeral port; nothing here names a host, a credential or a
 * network anyone else can reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalGatewaySocketTest {

    @Test
    fun `a token the Gateway refuses at the socket is still a refused token`() = runBlocking {
        val server = RefusingUpgradeServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GatewayConnectionManager(
                scope = scope,
                installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
                localConnector = LocalGatewayConnector(
                    tokens = StoredToken(TOKEN),
                    // The public health route, which answers a wrong token the
                    // same way it answers a right one.
                    health = { _, _ -> },
                    rpcOpen = { baseUrl, token ->
                        OkHttpGatewayRpcClient.connectLocal(OkHttpClient(), baseUrl, token)
                    },
                    // Wired, and must stay unused: a refusal is not an empty slot.
                    scraper = { _ -> throw AssertionError("a refusal must never be answered by a scrape") },
                ),
            )

            val result = manager.connectLocal(
                LocalGatewayProfile("http://127.0.0.1:${server.port}", secretSlotId = "row-local"),
            )

            assertTrue("expected Failed, got $result", result is GatewayConnectResult.Failed)
            assertEquals(
                "the socket's refusal has to arrive as the token problem it is",
                LocalGatewayCopy.TOKEN_REFUSED,
                (result as GatewayConnectResult.Failed).message,
            )
            assertFalse("a refused token is a wrong token, not a retry", result.retryable)
            assertEquals(LocalGatewayCopy.TOKEN_REFUSED, manager.state.value.message)
            assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
            assertNull("nothing was published over a socket that never opened", manager.gatewayHttp.value)
            assertNull(manager.client.value)
            // The readiness round trip cannot have misreported this: it is
            // downstream of a socket that never opened, so it never ran.
            assertEquals(
                "GET /api/ws?token=$TOKEN HTTP/1.1",
                withTimeoutOrNull(5_000) { server.requestLine.await() },
            )
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun `a Hermes that is not running says which device stopped answering`() = runBlocking {
        val port = unboundLoopbackPort()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GatewayConnectionManager(
                scope = scope,
                installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
                localConnector = LocalGatewayConnector(
                    tokens = StoredToken(TOKEN),
                    // The production readiness check, against a real port with
                    // nothing on it. The whole question is what the OkHttp call
                    // does when the connect is refused instead of answered.
                    health = OkHttpLocalGatewayHealthCheck(OkHttpClient()),
                    rpcOpen = { _, _ -> throw AssertionError("readiness must fail before any socket is opened") },
                ),
            )

            val result = withTimeoutOrNull(20_000) {
                manager.connectLocal(
                    LocalGatewayProfile("http://127.0.0.1:$port", secretSlotId = "row-local"),
                )
            }

            assertTrue("expected Failed, got $result", result is GatewayConnectResult.Failed)
            assertEquals(
                "a stopped Hermes is not a host to check: it is this device, and it can be started",
                LocalGatewayCopy.NOT_ANSWERING,
                (result as GatewayConnectResult.Failed).message,
            )
            assertTrue("`hermes serve` can be started; this is worth another tap", result.retryable)
            assertEquals(LocalGatewayCopy.NOT_ANSWERING, manager.state.value.message)
            assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
            assertNull(manager.gatewayHttp.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a Hermes that stops between the readiness check and the socket says the same thing`() = runBlocking {
        val port = unboundLoopbackPort()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GatewayConnectionManager(
                scope = scope,
                installStore = GatewayInstallStore { "0123456789abcdef0123456789abcdef" },
                localConnector = LocalGatewayConnector(
                    tokens = StoredToken(TOKEN),
                    // Readiness passes, so the upgrade is the hop that finds the
                    // port empty — the window where the person stops the server
                    // mid-dial. The failure OkHttp reports for a refused connect
                    // carries no HTTP status, and it is that absence the mapping
                    // reads: something that answered would have one.
                    health = { _, _ -> },
                    rpcOpen = { baseUrl, token ->
                        OkHttpGatewayRpcClient.connectLocal(OkHttpClient(), baseUrl, token)
                    },
                ),
            )

            val result = withTimeoutOrNull(20_000) {
                manager.connectLocal(
                    LocalGatewayProfile("http://127.0.0.1:$port", secretSlotId = "row-local"),
                )
            }

            assertTrue("expected Failed, got $result", result is GatewayConnectResult.Failed)
            assertEquals(
                LocalGatewayCopy.NOT_ANSWERING,
                (result as GatewayConnectResult.Failed).message,
            )
            assertTrue(result.retryable)
            // The published state, not just the returned result: the sentence
            // this fix is about is the one the surface renders.
            assertEquals(LocalGatewayCopy.NOT_ANSWERING, manager.state.value.message)
            assertEquals(GatewayConnectionStatus.NeedsAttention, manager.state.value.status)
            assertNull("nothing was published over a socket that never opened", manager.gatewayHttp.value)
        } finally {
            scope.cancel()
        }
    }

    /**
     * A loopback port with nothing listening on it: claimed from the ephemeral
     * range so no other server can be answering there, then released.
     *
     * The window between releasing it and dialling it is not closable — a port
     * cannot be both bound and refusing — but a reused port fails these tests
     * rather than passing them falsely: whatever claimed it would have to
     * answer an authenticated `/api/health` and a Hermes WebSocket upgrade to
     * turn a refusal into a pass. The dials are bounded, so a host that drops
     * instead of refusing fails on the timeout rather than hanging the lane.
     */
    private fun unboundLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** A slot that always has this token, so only the socket can refuse it. */
    private class StoredToken(private val token: String) : GatewaySessionTokenStore {
        override suspend fun loadSessionToken(slot: GatewaySecretSlot): SessionTokenRead =
            SessionTokenRead.Found(token.toByteArray(Charsets.US_ASCII))

        override suspend fun saveSessionToken(slot: GatewaySecretSlot, token: ByteArray) = token.fill(0)

        override suspend fun clearSessionToken(slot: GatewaySecretSlot) = Unit
    }

    /**
     * A loopback server that answers a WebSocket upgrade the way Starlette does
     * when the handler closes before accepting it: an ordinary HTTP refusal,
     * with no upgrade and no close code.
     */
    private class RefusingUpgradeServer : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val requestLine = CompletableDeferred<String>()

        private val thread = Thread {
            runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    requestLine.complete(reader.readLine().orEmpty())
                    // Drain the rest of the handshake before answering.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    client.getOutputStream().apply {
                        write(REFUSAL.toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                }
            }.onFailure { requestLine.completeExceptionally(it) }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            runCatching { socket.close() }
            thread.interrupt()
        }

        private companion object {
            /** What uvicorn sends when an ASGI app closes a WebSocket before accepting it. */
            const val REFUSAL = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        }
    }

    private companion object {
        const val TOKEN = "fixture-session-token_not-a-real-one"
    }
}
