package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CronModelOptionsTest {
    @Test fun `catalog explicitly targets the editor owner and omits unauthenticated providers`() = runTest {
        val client = Client()
        val clients = MutableStateFlow<GatewayRpcClient?>(client)
        val source = GatewayCronModelOptions(clients, MutableStateFlow(0L), EndpointDispatchFence())
        val catalog = source.load("worker_alpha")
        assertEquals(JsonPrimitive("worker_alpha"), client.params?.get("profile"))
        assertEquals(JsonPrimitive(true), client.params?.get("explicit_only"))
        assertEquals(listOf("configured"), catalog.providers.map { it.id })
        assertEquals(listOf("model-a"), catalog.providers.single().models.map { it.id })
    }

    @Test fun `same endpoint replacement before wire refuses the old catalog request`() = runTest {
        val before = CompletableDeferred<Unit>()
        val first = Client(before)
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val source = GatewayCronModelOptions(clients, MutableStateFlow(0L), EndpointDispatchFence())
        val result = async { runCatching { source.load("default") } }
        runCurrent()
        clients.value = Client()
        before.complete(Unit)
        assertTrue(result.await().isFailure)
        assertEquals(0, first.sends)
    }

    @Test fun `endpoint changes after a reply cannot return a foreign catalog`() = runTest {
        val reply = CompletableDeferred<Unit>()
        val client = Client(after = reply)
        val endpoints = MutableStateFlow(0L)
        val source = GatewayCronModelOptions(MutableStateFlow(client), endpoints, EndpointDispatchFence())
        val result = async { runCatching { source.load("default") } }
        runCurrent()
        endpoints.value = 1
        reply.complete(Unit)
        assertTrue(result.await().isFailure)
        assertEquals(1, client.sends)
    }

    private class Client(
        private val before: CompletableDeferred<Unit>? = null,
        private val after: CompletableDeferred<Unit>? = null,
    ) : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        var params: JsonObject? = null
        var sends = 0
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("unguarded")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            assertEquals("model.options", method)
            before?.await()
            if (!dispatch { sends++; this.params = params; true }) throw GatewayRpcException("stale")
            after?.await()
            return Json.parseToJsonElement("""{"providers":[{"slug":"configured","name":"Configured","authenticated":true,"models":["model-a"]},{"slug":"unconfigured","authenticated":false,"models":["model-b"]}]}""")
        }
        override fun close() = Unit
    }
}
