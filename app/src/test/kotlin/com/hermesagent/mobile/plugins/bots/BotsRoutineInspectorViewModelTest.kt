package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsRoutineInspectorViewModelTest {
    private class Host : PluginHost {
        override val endpointGeneration = MutableStateFlow(1L)
        var calls = 0
        var result = """{"jobs":[{"job_id":"same","name":"Morning"}],"scoped":"alpha"}"""
        var refuse = false
        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            calls++
            if (refuse) return PluginHostResult.Refused(5023, "Read failed")
            return PluginHostResult.Success(Json.parseToJsonElement(result))
        }
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    @Test fun heldListInspectorHasNoRpcAndClosesWhenRowDisappears() = runTest {
        val host = Host()
        val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, endpointGeneration = host.endpointGeneration)
        vm.selectOwner("alpha")
        runCurrent()
        val target = vm.uiState.value.target(vm.uiState.value.jobs.single())!!
        val calls = host.calls
        vm.openInspector(target)
        assertEquals("Morning", vm.uiState.value.inspectedJob?.title)
        vm.closeInspector(target)
        assertNull(vm.uiState.value.inspectedJob)
        vm.openInspector(target)
        assertEquals(calls, host.calls)
        host.result = """{"jobs":[],"scoped":"alpha"}"""
        vm.refreshNow()
        assertNull(vm.uiState.value.inspectedJob)
        vm.openInspector(target)
        assertNull(vm.uiState.value.inspectedJob)
    }

    @Test fun staleHeldFactsRemainInspectableAndFreshFactsReplaceThem() = runTest {
        val host = Host()
        val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, endpointGeneration = host.endpointGeneration)
        vm.selectOwner("alpha"); runCurrent()
        val target = vm.uiState.value.target(vm.uiState.value.jobs.single())!!
        host.refuse = true
        vm.refreshNow()
        assertTrue(vm.uiState.value.stale)
        val calls = host.calls
        vm.openInspector(target)
        assertEquals("Morning", vm.uiState.value.inspectedJob?.title)
        assertEquals(calls, host.calls)
        host.refuse = false
        host.result = """{"jobs":[{"job_id":"same","name":"Updated","state":"paused"}],"scoped":"alpha"}"""
        vm.refreshNow()
        assertEquals("Updated", vm.uiState.value.inspectedJob?.title)
        assertEquals(RoutineRunState.Paused, vm.uiState.value.inspectedJob?.state)
        host.result = """{"jobs":[{"job_id":"same","name":"Other owner"}],"scoped":"beta"}"""
        vm.refreshNow()
        assertNull(vm.uiState.value.inspectedJob)
        assertNull(vm.uiState.value.inspectorTarget)
    }

    @Test fun ownerAbaAndEndpointSwitchRejectOldOpenAndCloseCallbacks() = runTest {
        val host = Host()
        val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, endpointGeneration = host.endpointGeneration)
        vm.selectOwner("alpha"); runCurrent()
        val old = vm.uiState.value.target(vm.uiState.value.jobs.single())!!
        vm.openInspector(old)
        vm.selectOwner("beta"); runCurrent()
        assertNull(vm.uiState.value.inspectedJob)
        vm.selectOwner("alpha"); runCurrent()
        vm.openInspector(old)
        assertNull(vm.uiState.value.inspectedJob)
        val current = vm.uiState.value.target(vm.uiState.value.jobs.single())!!
        vm.openInspector(current)
        vm.closeInspector(old)
        assertNotNull(vm.uiState.value.inspectedJob)
        host.endpointGeneration.value = 2L
        vm.openInspector(current) // synchronous boundary, before collector runs
        assertNull(vm.uiState.value.inspectedJob)
        vm.closeInspector(current)
        assertNull(vm.uiState.value.inspectedJob)
        vm.selectOwner("alpha"); runCurrent()
        val replacement = vm.uiState.value.target(vm.uiState.value.jobs.single())!!
        vm.openInspector(replacement)
        vm.closeInspector(current)
        vm.openInspector(current)
        assertEquals(replacement, vm.uiState.value.inspectorTarget)
        assertEquals("Morning", vm.uiState.value.inspectedJob?.title)
    }
}
