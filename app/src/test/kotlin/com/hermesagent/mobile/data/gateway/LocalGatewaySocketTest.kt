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
 * The one refusal the readiness check cannot see.
 *
 * `/api/health` is on the Gateway's public allowlist at the pin
 * (`hermes_cli/dashboard_auth/public_paths.py:33-38` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`), so a wrong session token passes
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
