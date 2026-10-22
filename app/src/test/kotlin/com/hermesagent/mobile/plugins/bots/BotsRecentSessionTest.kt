package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BotsRecentSessionTest {
    private class Host : PluginHost {
        override val connected = MutableStateFlow(true)
        override val endpointGeneration = MutableStateFlow(0L)
        override suspend fun request(method: String, params: JsonObject): PluginHostResult =
            PluginHostResult.Success(Json.parseToJsonElement("""{"profiles":[{"name":"researcher","canonical_session":{"id":"canonical"},"last_session":{"id":"recent","resolved_id":"different"},"worker_session":{"id":"worker"}}]}"""))
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }
    @Test fun `last listed id wins over canonical resolved and worker ids`() {
        val row = BotRosterRow("researcher", canonicalSession = BotSessionPreview("canonical"),
            lastSession = BotSessionPreview("recent", "resolved"), workerSession = BotSessionPreview("worker"))
        assertEquals("recent", recentSessionId(row))
        assertNull(recentSessionId(row.copy(lastSession = null)))
        assertNull(recentSessionId(row.copy(lastSession = BotSessionPreview(" "))))
    }
    @Test fun `recent action captures exact owner endpoint and coalesces until hydration`() = runTest {
        val host = Host()
        val vm = BotsViewModel(BotsPluginRepository(host), backgroundScope,
            connected = host.connected, endpointGeneration = host.endpointGeneration)
        runCurrent(); vm.refreshNow()
        val origin = vm.uiState.value; val row = origin.managementRows.single()
        var calls = 0; var finish: ((Boolean) -> Unit)? = null
        vm.openRecent(row, origin) { profile, id, endpoint, done ->
            assertEquals("researcher", profile); assertEquals("recent", id); assertEquals(0L, endpoint)
            calls++; finish = done
        }
        vm.openRecent(row, origin) { _, _, _, _ -> calls++ }
        assertEquals(1, calls)
        assertEquals(row.rosterKey, vm.uiState.value.openingBotKey)
        host.endpointGeneration.value = 1L; runCurrent()
        finish!!(false)
        assertNull(vm.uiState.value.botChatMessage)
        vm.openRecent(row, origin) { _, _, _, _ -> calls++ }
        assertEquals(1, calls)
    }
}
