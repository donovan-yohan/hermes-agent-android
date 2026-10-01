package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsToolsetsDispatchTest {
    private class Rpc(val beforeWire: suspend (String) -> Unit = {}) : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        val sent = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("Unfenced")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            beforeWire(method)
            if (!dispatch { sent += method to params; true }) throw GatewayRpcException("Changed")
            return Json.parseToJsonElement(if (method == "profiles.describe") toolsetsJson(pinned = true)
                else """{"ok":true,"applied":{"toolsets":true}}""")
        }
        override fun close() {}
    }
    @Test fun `unsupported guarded host fails closed without falling back to ordinary endpoint writes`() = runTest {
        val calls = mutableListOf<String>()
        val host = object : PluginHost {
            override val endpointGeneration = MutableStateFlow(7L)
            override val connected = MutableStateFlow(true)
            override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("Unfenced")
            override suspend fun requestAtEndpoint(expectedGeneration: Long, method: String, params: JsonObject): PluginHostResult {
                calls += method; return toolsetsReply(toolsetsJson())
            }
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        val repo = BotsToolsetsRepository(host); val target = BotManagementTarget("worker", 7L)
        val baseline = repo.describe(target)!!
        assertEquals(BotToolsetsSave.Unconfirmed, repo.save(target, baseline, setOf("web")) { true })
        assertFalse(calls.contains("profiles.configure"))
    }
    @Test fun `real host revokes save and reset at immediate wire when same endpoint dialog closes`() = runTest {
        for (reset in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val rpc = Rpc { if (it == "profiles.configure") gate.await() }
            val host = GatewayPluginHost(backgroundScope, MutableStateFlow<GatewayRpcClient?>(rpc), MutableStateFlow(0L), EndpointDispatchFence())
            val repo = BotsToolsetsRepository(host); val target = BotManagementTarget("worker", 0L)
            val baseline = repo.describe(target)!!; var owns = true
            val result = async { if (reset) repo.restoreDefaults(target, baseline) { owns }
                else repo.save(target, baseline, setOf("web")) { owns } }
            runCurrent(); owns = false; gate.complete(Unit); runCurrent()
            assertEquals(BotToolsetsSave.Unconfirmed, result.await())
            assertTrue(rpc.sent.none { it.first == "profiles.configure" })
        }
    }
    @Test fun `real host endpoint replacement cannot redirect held configure`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val old = Rpc { if (it == "profiles.configure") gate.await() }; val replacement = Rpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(old); val endpoint = MutableStateFlow(0L); val fence = EndpointDispatchFence()
        val host = GatewayPluginHost(backgroundScope, clients, endpoint, fence)
        val repo = BotsToolsetsRepository(host); val target = BotManagementTarget("worker", 0L)
        val baseline = repo.describe(target)!!
        val result = async { repo.save(target, baseline, setOf("web")) { true } }
        runCurrent(); fence.invalidate(); endpoint.value = 1L; clients.value = replacement
        gate.complete(Unit); runCurrent(); result.await()
        assertTrue(old.sent.none { it.first == "profiles.configure" }); assertTrue(replacement.sent.isEmpty())
    }
}
