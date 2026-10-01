package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.GatewayPluginHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsAvatarDispatchTest {
    private class Rpc : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        val beforeWire = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        var stored: ByteArray? = null
        val writes = mutableListOf<String>()
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("Unfenced")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            if (method == "profiles.set_asset") beforeWire.await()
            if (!dispatch {
                if (method == "profiles.set_asset") {
                    writes += params.getValue("name").jsonPrimitive.content
                    stored = avatarPng()
                }
                true
            }) throw GatewayRpcException("Revoked")
            if (method == "profiles.set_asset") {
                reply.await()
                return buildJsonObject { put("ok", true); put("asset", "avatar"); put("size", avatarPng().size) }
            }
            return stored?.let(::avatarReply) ?: buildJsonObject { put("found", false) }
        }
        override fun close() {}
    }
    @Test fun `dismissal profile replacement and ABA at immediate wire prohibit mutation`() = runTest {
        for (transition in listOf("dismiss", "profile", "ABA")) {
            val rpc = Rpc()
            val host = GatewayPluginHost(backgroundScope, MutableStateFlow<GatewayRpcClient?>(rpc), MutableStateFlow(0L), EndpointDispatchFence())
            val vm = BotsAvatarViewModel(host, backgroundScope, {})
            val a = BotManagementTarget("worker", 0)
            vm.open(a); runCurrent()
            vm.finishPick(vm.beginPick(vm.state.value)!!, avatarPng())
            vm.save(vm.state.value); runCurrent()
            when (transition) {
                "dismiss" -> vm.close()
                "profile" -> vm.open(BotManagementTarget("other", 0))
                else -> { vm.open(BotManagementTarget("other", 0)); vm.open(a) }
            }
            runCurrent()
            rpc.beforeWire.complete(Unit); rpc.reply.complete(Unit); runCurrent()
            assertTrue(transition, rpc.writes.isEmpty())
            if (transition == "ABA") assertTrue(vm.state.value.editable)
        }
    }
    @Test fun `dispatched operation settles after reopen and fresh read restores controls without replay`() = runTest {
        val rpc = Rpc().apply { beforeWire.complete(Unit) }
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow<GatewayRpcClient?>(rpc), MutableStateFlow(0L), EndpointDispatchFence())
        val vm = BotsAvatarViewModel(host, backgroundScope, {})
        val a = BotManagementTarget("worker", 0)
        vm.open(a); runCurrent()
        vm.finishPick(vm.beginPick(vm.state.value)!!, avatarPng())
        vm.save(vm.state.value); runCurrent()
        vm.close(); vm.open(a); runCurrent()
        assertFalse(vm.state.value.editable)
        rpc.reply.complete(Unit); runCurrent()
        assertEquals(listOf("worker"), rpc.writes)
        assertTrue(vm.state.value.editable)
        assertArrayEquals(avatarPng(), vm.state.value.original!!.bytes)
        assertFalse(vm.state.value.canSave)
    }
}
