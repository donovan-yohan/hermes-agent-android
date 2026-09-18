package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.GatewayPluginHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsConnectionTest {
    private class Rpc(val beforeSend: (String) -> Unit = {}) : EndpointDispatchingGatewayRpcClient {
        val sent = mutableListOf<String>()
        override val events: Flow<GatewayEvent> = emptyFlow()
        override fun close() {}
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("Unfenced request")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            beforeSend(method)
            if (!dispatch { sent += method; true }) throw GatewayRpcException("Leg replaced")
            return when (method) {
                "groups.capabilities" -> capabilityWire
                "groups.list" -> wire("""{"rooms":[${roomWire()}],"next_offset":null}""")
                else -> error(method)
            }
        }
    }

    @Test fun capabilityCheckUseRaceRestartsOnReplacementBeforeAnyListDispatch() = runTest {
        val clients = MutableStateFlow<GatewayRpcClient?>(null)
        val replacement = Rpc()
        val old = Rpc { method -> if (method == "groups.list") clients.value = replacement }
        clients.value = old
        val endpoint = MutableStateFlow(0L)
        val host = GatewayPluginHost(backgroundScope, clients, endpoint)
        val vm = GroupsViewModel(backgroundScope, groupConnections(host, backgroundScope), endpoint)
        vm.setForeground(true)
        runCurrent()
        assertEquals(listOf("groups.capabilities"), old.sent)
        assertEquals(listOf("groups.capabilities", "groups.list"), replacement.sent)
        assertEquals(GroupsPhase.Ready, vm.uiState.value.phase)
        assertEquals("Planning", vm.uiState.value.rooms.single().name)
    }
}
