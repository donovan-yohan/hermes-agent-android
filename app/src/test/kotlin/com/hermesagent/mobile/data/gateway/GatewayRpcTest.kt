package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayRpcTest {

    @Test
    fun `requests use monotonic ids and only matching responses complete them`() = runTest {
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        val first = async { rpc.request("session.list") }
        val second = async { rpc.request("session.history") }
        runCurrent()

        assertEquals(listOf("m1", "m2"), wire.frames.map(::requestId))
        rpc.receive("""{"jsonrpc":"2.0","id":"m2","result":{"count":2}}""")
        runCurrent()
        assertFalse(first.isCompleted)
        assertEquals("2", second.await().jsonObject["count"]?.jsonPrimitive?.content)

        rpc.receive("""{"jsonrpc":"2.0","id":"m1","result":{"sessions":[]}}""")
        assertTrue(first.await().jsonObject.containsKey("sessions"))
    }

    @Test
    fun `timeout and close reject pending calls`() = runTest {
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire, timeoutMillis = 100)
        val timedOut = async { runCatching { rpc.request("session.list") }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(101)
        assertTrue(timedOut.await() is kotlinx.coroutines.TimeoutCancellationException)

        val pending = async { runCatching { rpc.request("session.history") }.exceptionOrNull() }
        runCurrent()
        rpc.close()
        assertTrue(pending.await() is GatewayRpcException)
        assertTrue(wire.closed)
    }

    @Test
    fun `a request that arrives after close never reaches the wire`() = runTest {
        // The closed check and the send are one act under the client's lock, so
        // a teardown that got there first refuses the frame instead of letting
        // an endpoint-bound caller's last dispatch slip onto a leg the app has
        // already left.
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        rpc.close()

        val failure = runCatching { rpc.request("session.create") }.exceptionOrNull()

        assertTrue(failure is GatewayRpcException)
        assertTrue("a closed leg takes no frame", wire.frames.isEmpty())
    }

    @Test
    fun `an invalidated endpoint dispatch lease never reaches the wire`() = runTest {
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        val fence = EndpointDispatchFence()
        val lease = checkNotNull(fence.leaseAt(0L) { true })
        fence.invalidate()

        val failure = runCatching {
            rpc.requestAtEndpointDispatch("session.create") { send ->
                fence.dispatchIfCurrent(lease, stillOwns = { true }, send = send)
            }
        }.exceptionOrNull()

        assertTrue(failure is GatewayRpcException)
        assertTrue("an invalid endpoint lease takes no frame", wire.frames.isEmpty())
    }

    @Test
    fun `prompt submit keeps its authoritative long acknowledgement timeout`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire())
        val prompt = async { rpc.request("prompt.submit") }
        runCurrent()

        assertEquals(1_800_000L, gatewayRpcTimeoutMillis("prompt.submit"))
        assertEquals(15_000L, gatewayRpcTimeoutMillis("session.list"))
        advanceTimeBy(15_001)
        runCurrent()
        assertFalse("the generic deadline must not reject an accepted prompt", prompt.isCompleted)

        rpc.receive("""{"jsonrpc":"2.0","id":"m1","result":{"status":"streaming"}}""")
        assertEquals("streaming", prompt.await().jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `typed errors and supported events are parsed while noise is ignored`() = runTest {
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire, eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val answer = async { runCatching { rpc.request("prompt.submit") }.exceptionOrNull() }
        runCurrent()
        rpc.receive("not json")
        rpc.receive("""{"jsonrpc":"2.0","method":"other","params":{}}""")
        rpc.receive("""{"jsonrpc":"2.0","id":"m1","error":{"code":-32000,"message":"refused"}}""")
        val error = answer.await() as GatewayRpcError
        assertEquals(-32000, error.code)
        assertEquals("refused", error.message)

        val event = async { rpc.events.first() }
        runCurrent()
        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"unknown","session_id":"r0"}}""")
        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"r1","payload":{"delta":"hi"}}}""")
        val received = event.await()
        assertEquals("message.delta", received.type)
        assertEquals("r1", received.runtimeSessionId)
        assertEquals("hi", received.payload.jsonObject["delta"]?.jsonPrimitive?.content)
    }

    @Test
    fun `status update remains on the correlated event stream with its runtime identity`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val event = async { rpc.events.first() }
        runCurrent()

        rpc.receive(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"status.update","session_id":"runtime-status","payload":{"kind":"process","text":"Refreshing work"}}}""",
        )

        val received = event.await()
        assertEquals("status.update", received.type)
        assertEquals("runtime-status", received.runtimeSessionId)
        assertEquals("process", received.payload.jsonObject["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `composer catalog and completion methods retain typed errors and short deadlines`() = runTest {
        listOf("model.options", "complete.path", "complete.slash").forEach { method ->
            val rejectedRpc = CorrelatedGatewayRpc(RecordingWire())
            val rejected = async { runCatching { rejectedRpc.request(method) }.exceptionOrNull() }
            runCurrent()
            rejectedRpc.receive(
                """{"jsonrpc":"2.0","id":"m1","error":{"code":4201,"message":"not available"}}""",
            )

            val error = rejected.await() as GatewayRpcError
            assertEquals("$method preserves the Gateway error code", 4201, error.code)
            assertEquals("$method preserves the Gateway error message", "not available", error.message)

            val timedRpc = CorrelatedGatewayRpc(RecordingWire())
            val timedOut = async { runCatching { timedRpc.request(method) }.exceptionOrNull() }
            runCurrent()
            assertEquals("$method uses the ordinary RPC deadline", 15_000L, gatewayRpcTimeoutMillis(method))
            advanceTimeBy(15_001)
            runCurrent()
            assertTrue(
                "$method must not remain pending after its short deadline",
                timedOut.await() is kotlinx.coroutines.TimeoutCancellationException,
            )
        }
    }

    @Test
    fun `a malformed matching response fails only that request`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire())
        val answer = async { runCatching { rpc.request("session.list") }.exceptionOrNull() }
        runCurrent()
        rpc.receive("""{"jsonrpc":"2.0","id":"m1","method":"not-a-response"}""")
        assertTrue(answer.await() is GatewayRpcException)
    }

    @Test
    fun `event bursts are retained before the repository subscribes`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        repeat(128) { index ->
            rpc.receive(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"r1","payload":{"delta":"$index"}}}""",
            )
        }

        val received = rpc.events.take(128).toList()

        assertEquals(128, received.size)
        assertEquals("0", received.first().payload.jsonObject["delta"]?.jsonPrimitive?.content)
        assertEquals("127", received.last().payload.jsonObject["delta"]?.jsonPrimitive?.content)
    }

    /**
     * The client's event stream is a broadcast, not a queue with one reader.
     * The app's transcript pump and a plugin's tap are both subscribers: each
     * receives every event, a subscriber that attaches later sees every event
     * after it, and disposing one leaves the other's stream intact.
     */
    @Test
    fun `every subscriber receives every event`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val appPump = mutableListOf<String>()
        val pluginTap = mutableListOf<String>()
        val app = launch { rpc.events.collect { appPump += it.payload.jsonObject.getValue("delta").jsonPrimitive.content } }
        val tap = launch { rpc.events.collect { pluginTap += it.payload.jsonObject.getValue("delta").jsonPrimitive.content } }
        runCurrent()

        repeat(4) { index -> rpc.receive(deltaFrame(index)) }
        advanceUntilIdle()

        // Four events, two readers: a competing consumer could not deliver
        // all four to both.
        assertEquals("the app's pump sees the whole stream", listOf("0", "1", "2", "3"), appPump)
        assertEquals("a plugin tap sees the whole stream", listOf("0", "1", "2", "3"), pluginTap)

        // Dropping one subscriber never ends another's subscription.
        tap.cancel()
        runCurrent()
        rpc.receive(deltaFrame(4))
        advanceUntilIdle()

        assertEquals(listOf("0", "1", "2", "3", "4"), appPump)
        assertEquals(listOf("0", "1", "2", "3"), pluginTap)

        app.cancel()
        rpc.close()
        advanceUntilIdle()
    }

    /** A `message.delta` frame carrying [index], the shape the gateway sends. */
    private fun deltaFrame(index: Int): String =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"r1","payload":{"delta":"$index"}}}"""

    @Test
    fun `remote websocket preserves reverse proxy prefix and carries only an encoded one-time ticket`() {
        val url = remoteGatewayWebSocketUrl(
            "https://gateway.example/hermes/",
            "ticket with + reserved? chars",
        )

        assertEquals("/hermes/api/ws", url.encodedPath)
        assertEquals("ticket with + reserved? chars", url.queryParameter("ticket"))
        assertEquals(null, url.queryParameter("token"))
        assertEquals(1, url.querySize)
    }

    @Test
    fun `event overflow closes instead of dropping transcript bytes`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire())
        val closed = async { rpc.closed.first() }
        runCurrent()

        repeat(1_025) { index ->
            rpc.receive(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"delta":"$index"}}}""",
            )
        }

        closed.await()
    }

    @Test
    fun `malformed error metadata remains a typed protocol error`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire())
        val answer = async { runCatching { rpc.request("prompt.submit") }.exceptionOrNull() }
        runCurrent()

        rpc.receive("""{"jsonrpc":"2.0","id":"m1","error":{"code":{},"message":"refused"}}""")

        val error = answer.await() as GatewayRpcError
        assertEquals(null, error.code)
        assertEquals("refused", error.message)
    }

    @Test
    fun `session reclaimed keeps its runtime identity in the payload`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val event = async { rpc.events.first() }
        runCurrent()

        rpc.receive(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"session.reclaimed","session_id":"","payload":{"session_id":"runtime-gone","stored_session_id":"durable-kept","reason":"lru_evict"}}}""",
        )

        val reclaimed = event.await()
        assertEquals("session.reclaimed", reclaimed.type)
        assertEquals(null, reclaimed.runtimeSessionId)
        assertEquals("runtime-gone", reclaimed.payload.jsonObject["session_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `session-less broadcasts are admitted with no runtime while an unknown type is dropped`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val received = async { rpc.events.first() }
        runCurrent()

        // A type from a newer backend is refused by the allow-list, so it can
        // never become the first delivered event.
        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"future.broadcast","session_id":""}}""")
        rpc.receive(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"bot_relay.outbox.pending","session_id":"","payload":{"queued":1}}}""",
        )

        val hint = received.await()
        assertEquals("bot_relay.outbox.pending", hint.type)
        assertEquals(null, hint.runtimeSessionId)
        assertEquals(null, hint.seq)
        assertEquals("1", hint.payload.jsonObject["queued"]?.jsonPrimitive?.content)
    }

    /**
     * The number a replay resumes from: session events are stamped with a
     * per-session `seq`, session-less broadcasts deliberately are not
     * (`tui_gateway/event_replay.py:39-60,46-49` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
     */
    @Test
    fun `a session event keeps its replay sequence`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val received = async { rpc.events.first() }
        runCurrent()

        rpc.receive(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"runtime-a","seq":12,"payload":{"delta":"hi"}}}""",
        )

        val event = received.await()
        assertEquals("runtime-a", event.runtimeSessionId)
        assertEquals(12L, event.seq)
    }

    /**
     * The allow-list is the union of the session types and everything
     * [gatewayEventLane] calls global, so a broadcast the lane handles cannot
     * be silently refused by the parser. Every one of them must come through.
     */
    @Test
    fun `every session-less broadcast the lane handles is admitted by the allow-list`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<String>()
        val pump = launch { rpc.events.collect { seen += it.type } }
        runCurrent()

        GATEWAY_GLOBAL_EVENT_TYPES.forEach { type ->
            rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":""}}""")
        }
        advanceUntilIdle()
        runCurrent()

        assertEquals(GATEWAY_GLOBAL_EVENT_TYPES, seen.toSet())
        assertEquals("each broadcast arrives once", GATEWAY_GLOBAL_EVENT_TYPES.size, seen.size)
        assertTrue(
            "the union must still admit only what the lane can classify",
            GATEWAY_GLOBAL_EVENT_TYPES.all { gatewayEventLane(it) == GatewayEventLane.Global },
        )
        pump.cancel()
    }

    /**
     * The prompt surface at the pin, pinned by shape.
     *
     * A blocking prompt is a server→client *request* frame — id `srq-…`, a
     * `method` that is never `event`, no `result` — and it must never appear on
     * the event stream; the one event the family still has is the backend
     * withdrawing one (`tui_gateway/contracts/server_requests.py:225-233` @
     * `437116f9497c80d242ce034ff7f5d81dc277a337`). The deleted `*.request`
     * pair stays refused, so a prompt cannot grow a second home.
     */
    @Test
    fun `server requests arrive on their own stream while the deleted request events stay refused`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val requests = mutableListOf<GatewayServerRequest>()
        val events = mutableListOf<String>()
        val requestPump = launch { rpc.serverRequests.collect { requests += it } }
        val eventPump = launch { rpc.events.collect { events += it.type } }
        runCurrent()

        rpc.receive(
            """{"jsonrpc":"2.0","id":"srq-1","method":"clarify","params":{"session_id":"r1","question":"Proceed?"}}""",
        )
        rpc.receive(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"clarify.request","session_id":"r1","payload":{}}}""",
        )
        rpc.receive(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"request.cancel","session_id":"r1","payload":{"id":"srq-1","method":"clarify","reason":"timeout"}}}""",
        )
        advanceUntilIdle()

        assertEquals(listOf("srq-1"), requests.map { it.id })
        assertEquals("clarify", requests.single().method)
        assertEquals("r1", requests.single().runtimeSessionId)
        assertEquals("Proceed?", requests.single().params.string("question"))
        assertEquals("the withdrawal is the only family event left", listOf("request.cancel"), events)
        requestPump.cancel()
        eventPump.cancel()
    }

    /** A question that arrives before the repository subscribes waits for it. */
    @Test
    fun `a request that arrives before anything subscribes is not lost`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))

        rpc.receive("""{"jsonrpc":"2.0","id":"srq-early","method":"sudo","params":{"session_id":"r1"}}""")

        val delivered = rpc.serverRequests.first()
        assertEquals("srq-early", delivered.id)
        assertEquals("sudo", delivered.method)
    }

    /** Both halves of a request are load-bearing; neither is invented. */
    @Test
    fun `a request frame missing its id or its method is dropped`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire(), eventPumpDispatcher = StandardTestDispatcher(testScheduler))
        val requests = mutableListOf<GatewayServerRequest>()
        val pump = launch { rpc.serverRequests.collect { requests += it } }
        val events = mutableListOf<String>()
        val eventPump = launch { rpc.events.collect { events += it.type } }
        runCurrent()

        rpc.receive("""{"jsonrpc":"2.0","method":"clarify","params":{"session_id":"r1","question":"Proceed?"}}""")
        rpc.receive("""{"jsonrpc":"2.0","id":"srq-2","method":"clarify"}""")
        advanceUntilIdle()

        assertTrue("an unanswerable question is not delivered as one", requests.isEmpty())
        assertTrue("and it is not an event either", events.isEmpty())
        pump.cancel()
        eventPump.cancel()
    }

    @Test
    fun `an answer is exactly one response frame carrying the request's own id`() = runTest {
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)

        rpc.respondToServerRequest("srq-9", buildJsonObject { put("answer", JsonPrimitive("yes")) })

        val frame = Json.parseToJsonElement(wire.frames.single()).jsonObject
        assertEquals("2.0", frame["jsonrpc"]?.jsonPrimitive?.content)
        assertEquals("srq-9", frame["id"]?.jsonPrimitive?.content)
        assertEquals("yes", frame["result"]?.jsonObject?.get("answer")?.jsonPrimitive?.content)
        assertFalse("a response frame is not a method call", frame.containsKey("method"))
    }

    @Test
    fun `a closed leg refuses a response frame`() = runTest {
        val wire = RecordingWire()
        val rpc = CorrelatedGatewayRpc(wire)
        rpc.close()

        val failure = runCatching {
            rpc.respondToServerRequest("srq-9", JsonObject(emptyMap()))
        }.exceptionOrNull()

        assertTrue(failure is GatewayRpcException)
        assertTrue("a closed leg takes no frame", wire.frames.isEmpty())
    }

    private fun requestId(frame: String): String =
        Json.parseToJsonElement(frame).jsonObject.getValue("id").jsonPrimitive.content

    private class RecordingWire : GatewayRpcWire {
        val frames = mutableListOf<String>()
        var closed = false

        override fun send(text: String): Boolean = frames.add(text)

        override fun close() {
            closed = true
        }
    }
}
