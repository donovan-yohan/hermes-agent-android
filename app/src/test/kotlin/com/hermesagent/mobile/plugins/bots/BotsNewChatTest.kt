package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BotsNewChatTest {
    private class Host : PluginHost {
        override val connected = MutableStateFlow(true)
        override val endpointGeneration = MutableStateFlow(0L)
        override suspend fun request(method: String, params: JsonObject): PluginHostResult =
            PluginHostResult.Success(Json.parseToJsonElement("""{"profiles":[{"name":"researcher"}]}"""))
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    @Test fun `new chat captures owner and endpoint and coalesces taps through completion`() = runTest {
        val host = Host()
        val vm = BotsViewModel(BotsPluginRepository(host), backgroundScope,
            connected = host.connected, endpointGeneration = host.endpointGeneration)
        runCurrent(); vm.refreshNow()
        val origin = vm.uiState.value
        val row = origin.managementRows.single()
        var calls = 0
        var finish: ((Boolean) -> Unit)? = null
        vm.newChat(row, origin) { profile, endpoint, done ->
            assertEquals("researcher", profile); assertEquals(0L, endpoint)
            calls++; finish = done
        }
        vm.newChat(row, origin) { _, _, _ -> calls++ }
        assertEquals(1, calls)
        assertEquals(row.rosterKey, vm.uiState.value.openingBotKey)
        finish!!(false)
        assertNull(vm.uiState.value.openingBotKey)
        assertEquals("A new chat could not be started. Try again.", vm.uiState.value.botChatMessage)
        finish!!(true)
        assertNotNull(vm.uiState.value.botChatMessage)
    }

    @Test fun `stale roster cannot create and late endpoint callback cannot paint`() = runTest {
        val host = Host()
        val vm = BotsViewModel(BotsPluginRepository(host), backgroundScope,
            connected = host.connected, endpointGeneration = host.endpointGeneration)
        runCurrent(); vm.refreshNow()
        val origin = vm.uiState.value; val row = origin.managementRows.single()
        var finish: ((Boolean) -> Unit)? = null
        vm.newChat(row, origin) { _, _, done -> finish = done }
        host.endpointGeneration.value = 1L
        runCurrent()
        finish!!(false)
        assertNull(vm.uiState.value.botChatMessage)
        var calls = 0
        vm.newChat(row, origin) { _, _, _ -> calls++ }
        assertEquals(0, calls)
    }
}
