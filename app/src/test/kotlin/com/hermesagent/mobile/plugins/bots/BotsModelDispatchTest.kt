package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.GatewayPluginHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsModelDispatchTest {
    private class Rpc(val beforeWire: suspend () -> Unit = {}) : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        val sent = mutableListOf<String>()
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("Unfenced")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            beforeWire()
            if (!dispatch { sent += method; true }) throw GatewayRpcException("Endpoint changed")
            return buildJsonObject { put("ok", true); put("applied", buildJsonObject { put("model", true) }) }
        }
        override fun close() {}
    }

    @Test fun `real host atomically fences every model method when endpoint changes at wire boundary`() = runTest {
        for (method in listOf("profiles.describe", "model.options", "profiles.configure", "confirmed")) {
            val gate = CompletableDeferred<Unit>()
            val first = Rpc { gate.await() }; val replacement = Rpc()
            val clients = MutableStateFlow<GatewayRpcClient?>(first)
            val endpoint = MutableStateFlow(0L); val fence = EndpointDispatchFence()
            val host = GatewayPluginHost(backgroundScope, clients, endpoint, fence)
            val repo = BotsModelRepository(host)
            val target = BotManagementTarget("worker", 0L)
            val result = async { when (method) {
                "profiles.describe" -> repo.describe(target)
                "model.options" -> repo.options(target)
                else -> repo.save(target, BotModelSelection("p", "m"), confirmed = method == "confirmed")
            } }
            runCurrent()
            fence.invalidate(); endpoint.value = 1L; clients.value = replacement
            gate.complete(Unit); runCurrent(); result.await()
            assertTrue(method, first.sent.isEmpty()); assertTrue(method, replacement.sent.isEmpty())
        }
    }
}
