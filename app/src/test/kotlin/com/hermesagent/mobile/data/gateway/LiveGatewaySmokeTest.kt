package com.hermesagent.mobile.data.gateway

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The #307 acceptance lane, and the only lane here that can be it: the
 * production Android client, over a real OkHttp WebSocket, against an actual
 * upstream Gateway process.
 *
 * The other tests in this package answer with fakes, which is what makes them
 * deterministic and also why the issue stayed open — a fake emits
 * `gateway.ready` on demand and forgets nothing, so it cannot show a capability
 * that genuinely dies with a socket. This server is real.
 *
 * Measured here, against that server, from the server's own settlement:
 * `gateway.ready` arrives and the production client reads it;
 * `client.capabilities {server_requests: true}` is acknowledged with the
 * server's real capability list; a real `srq-` request parked on an advertised
 * connection arrives carrying its session and the client's response frame
 * settles the server's blocking `send()` with the answer it sent; a connection
 * that never advertised receives no request frame at all; and a fresh
 * connection is answered again, because the server drops the advertisement with
 * the socket.
 *
 * Not measured, and not claimed: no model, tool or physical prompt was
 * accepted. The driver injects a deterministic `clarify` rather than driving a
 * turn, so this is protocol integration — a real server parking a real request
 * on a real socket — and not live model acceptance. Nothing was deployed to any
 * user's server.
 *
 * Nor is the manager's *automatic* advertisement shown here: the test calls
 * [advertiseServerRequests], the function the three production connection paths
 * call. That those paths reach it is the deterministic manager tests' claim;
 * what this lane adds is that the function is accepted by a real server.
 *
 * Opt-in. Without `-Dhermes.liveGateway=1` the test is skipped, so `check` and
 * CI start no Gateway and need no Python toolchain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveGatewaySmokeTest {

    private var driver: LiveGatewayDriver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableListOf<GatewayRpcClient>()

    /**
     * Test-only transport diagnostics.
     *
     * Every connection here goes through a plain `OkHttpClient` plus an
     * [EventListener], which is the only way to see *why* an upgrade failed: the
     * production client reports a refused WebSocket as one sentence with an
     * optional status, and an absent status cannot distinguish "nothing
     * answered" from "something answered a status OkHttp did not surface".
     *
     * Nothing here is production surface, and no token URL is recorded: the
     * request's URL is never read, only its host and path.
     */
    private val diagnostics = mutableListOf<String>()

    private fun diagnosticClient(): OkHttpClient = OkHttpClient.Builder()
        .eventListener(object : EventListener() {
            override fun connectFailed(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
                ioe: IOException,
            ) {
                diagnostics += "connectFailed ${inetSocketAddress.hostString}:${inetSocketAddress.port} " +
                    "${ioe.javaClass.simpleName}: ${ioe.message}"
            }

            override fun callFailed(call: Call, ioe: IOException) {
                diagnostics += "callFailed ${call.request().url.host}:${call.request().url.port}" +
                    "${call.request().url.encodedPath} ${ioe.javaClass.simpleName}: ${ioe.message}"
            }

            override fun responseHeadersEnd(call: Call, response: Response) {
                if (call.request().url.encodedPath.contains("ws")) {
                    diagnostics += "upgradeResponse ${response.code} ${response.message}"
                }
            }

            override fun canceled(call: Call) {
                diagnostics += "canceled ${call.request().url.host}:${call.request().url.port}"
            }
        })
        .build()

    /** The collected transport detail, with the run's token scrubbed out. */
    private fun transportDetail(live: LiveGatewayDriver): String =
        live.redact(diagnostics.joinToString(" | ").ifEmpty { "<no transport events recorded>" })

    private suspend fun connect(live: LiveGatewayDriver): GatewayRpcClient {
        val result = runCatching {
            OkHttpGatewayRpcClient.connectLocal(
                diagnosticClient(),
                live.baseUrl,
                live.sessionToken(),
            )
        }
        return result.getOrElse { refusal ->
            throw AssertionError(
                "the production client could not open a WebSocket to the real Gateway at " +
                    "${live.baseUrl}: ${live.redact(refusal.toString())}. " +
                    "Transport detail: ${transportDetail(live)}. " +
                    "Server log tail:\n${live.serverLogTail()}",
                refusal,
            )
        }.also { clients += it }
    }

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.close() } }
        runCatching { driver?.close() }
        scope.cancel()
    }

    @Test
    fun `a real Gateway answers an advertised connection and refuses an unadvertised one, per connection`() =
        runBlocking {
            val live = LiveGatewayDriver.start(LiveGatewayDriver.optIn()).also { driver = it }

            // Provenance from the server itself: a stand-in can emit the same
            // first frame, but it cannot be the pinned commit's own module.
            assertEquals(
                "the smoke must run against the pinned upstream snapshot",
                EXPECTED_UPSTREAM_SHA,
                live.snapshotSha,
            )
            assertTrue(
                "the driver must import the snapshot's own tui_gateway, not an installed copy: " +
                    live.gatewayModulePath,
                live.gatewayModulePath.startsWith(live.snapshotRoot),
            )
            assertTrue(
                "the Gateway process must still be alive after its ready marker; log:\n${live.serverLogTail()}",
                live.isAlive(),
            )

            // ── 1. A real connection: the real ready event ───────────────────
            val first = connect(live)
            awaitGatewayReady(first)
            first.advertiseServerRequests()

            // ── 2. One real request/response round trip ─────────────────────
            val sessionId = first.createSession("hm307 live smoke")
            val accepted = parkAndAnswer(first, live, sessionId)
            assertEquals("the answer sent is the answer the server read", ANSWER, accepted.answer)
            assertTrue("the real server's blocking send() must return the answer", accepted.settled)
            assertTrue("the server's gate must be open after advertisement", accepted.answerable)

            // ── 3. A connection that never advertised is sent no frame ───────
            // The collector is subscribed *before* the server is asked to park
            // anything, and stays subscribed past the settlement: a hot
            // `serverRequests` only broadcasts while someone is collecting, so
            // subscribing after the fact would observe nothing whether or not a
            // frame had been written.
            val second = connect(live)
            val secondSession = second.createSession("hm307 live smoke, unadvertised")
            val delivered = CopyOnWriteArrayList<GatewayServerRequest>()
            // UNDISPATCHED runs the collector's body on this thread up to its
            // first real suspension, which is where the production flow has
            // already reached `emitAll(sharedFlow)` and installed the
            // subscription. That is the barrier: nothing has been parked yet,
            // so no frame can be broadcast into the gap, and no latch or sleep
            // is needed to stand in for it.
            val collection = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                second.serverRequests.collect { delivered += it }
            }
            val refused = live.park(secondSession)
            // Past the server's own 10s deadline, so a frame written late would
            // still arrive before this connection is dropped.
            Thread.sleep(REFUSAL_SETTLE_MILLIS)
            collection.cancel()

            assertFalse(
                "the real server parks nothing on a client that never advertised: $refused",
                refused.settled,
            )
            assertFalse(
                "the server must report the session unanswerable, not merely an unanswered request",
                refused.answerable,
            )
            assertTrue(
                "no request frame may reach a client that never advertised, saw: $delivered",
                delivered.isEmpty(),
            )
            second.close()
            clients.remove(second)

            // ── 4. Reconnect: the advertisement is per-connection ────────────
            first.close()
            clients.remove(first)
            val reconnected = connect(live)
            awaitGatewayReady(reconnected)
            reconnected.advertiseServerRequests()
            val reconnectSession = reconnected.createSession("hm307 live smoke, reconnected")
            val afterReconnect = parkAndAnswer(reconnected, live, reconnectSession)
            assertTrue(
                "the advertisement does not survive the socket: a new connection must be answered too",
                afterReconnect.settled,
            )
            assertEquals(ANSWER, afterReconnect.answer)
        }

    /**
     * The round trip the issue turns on: one real `srq-` frame in, one response
     * frame out carrying that same id, the server's blocking `send()` returning
     * the answer.
     *
     * The collector is attached and confirmed before the request is parked, so
     * a frame cannot be broadcast into the gap between arming the server and
     * subscribing to the socket.
     */
    private suspend fun parkAndAnswer(
        client: GatewayRpcClient,
        live: LiveGatewayDriver,
        sessionId: String,
    ): LiveGatewayDriver.Settlement {
        val responder = client as GatewayServerRequestResponder
        val arrived = CompletableFuture<GatewayServerRequest>()
        // Same barrier as the refusal case: UNDISPATCHED reaches the
        // subscription before returning, so the server cannot be armed first.
        val collection = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.serverRequests.collect {
                if (it.runtimeSessionId == sessionId && !arrived.isDone) arrived.complete(it)
            }
        }
        try {
            return withTimeout(60_000) {
                // The server blocks inside `send` until the response lands, so
                // parking runs on another thread while this one answers — the
                // answer is what unblocks it.
                val parked = async(Dispatchers.IO) { live.park(sessionId) }
                val request = arrived.get(45, TimeUnit.SECONDS)
                assertEquals("the request carries the real server's method", "clarify", request.method)
                assertEquals("the request carries the session it belongs to", sessionId, request.runtimeSessionId)
                assertTrue("the request must carry an id to answer", request.id.isNotBlank())
                responder.respondToServerRequest(request.id, buildJsonObject { put("answer", ANSWER) })
                parked.await()
            }
        } finally {
            collection.cancel()
        }
    }

    /** The first frame a real server sends is its ready event. */
    private suspend fun awaitGatewayReady(client: GatewayRpcClient) = withTimeout(30_000) {
        val event = client.events.first()
        assertEquals(
            "the first real frame is gateway.ready (`tui_gateway/ws.py:299-304` @ $EXPECTED_UPSTREAM_SHA)",
            "gateway.ready",
            event.type,
        )
        Unit
    }

    private suspend fun GatewayRpcClient.createSession(title: String): String = withTimeout(60_000) {
        val created = request("session.create", buildJsonObject { put("title", title) }).jsonObject
        assertNotNull("the real server must answer session.create with a result", created)
        created.getValue("session_id").jsonPrimitive.content.also {
            assertTrue("the real server must mint a session id", it.isNotBlank())
        }
    }

    private companion object {
        const val EXPECTED_UPSTREAM_SHA = "d177b119e9c56c9ddc0b7379ffce52341ec06584"
        const val ANSWER = "b"

        /**
         * Longer than the server's own deadline for the parked request, so a
         * frame written late would still be observed rather than outrun.
         */
        const val REFUSAL_SETTLE_MILLIS = 12_000L
    }
}
