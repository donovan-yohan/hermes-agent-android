package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
        val rpc = CorrelatedGatewayRpc(wire)
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
    fun `a malformed matching response fails only that request`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire())
        val answer = async { runCatching { rpc.request("session.list") }.exceptionOrNull() }
        runCurrent()
        rpc.receive("""{"jsonrpc":"2.0","id":"m1","method":"not-a-response"}""")
        assertTrue(answer.await() is GatewayRpcException)
    }

    @Test
    fun `event bursts are retained before the repository subscribes`() = runTest {
        val rpc = CorrelatedGatewayRpc(RecordingWire())
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
        val rpc = CorrelatedGatewayRpc(RecordingWire())
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
