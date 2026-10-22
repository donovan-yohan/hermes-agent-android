package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.plugins.PluginRestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanCreationStateTest {
    @Test fun `submit is single flight confirmation closes form and refreshes board`() = runTest {
        val held = CompletableDeferred<KanbanCreated?>()
        var submissions = 0; var reads = 0; var closed = false
        val api = object : KanbanCreate {
            override fun open(expectedEndpoint: Long) = object : KanbanCreateScope {
                override suspend fun prepare() = KanbanCreateDefaults("design", "planner")
                override suspend fun submit(draft: KanbanNewTask): KanbanCreated? { submissions++; return held.await() }
                override fun close() { closed = true }
            }
        }
        val vm = KanbanViewModel(KanbanPluginRepository { _, _ -> reads++; PluginRestResult.Success(200, """{"columns":[]}""".toByteArray()) },
            MutableStateFlow(false), MutableStateFlow(0L), backgroundScope, api)
        vm.openCreate(); runCurrent()
        vm.submitTask(KanbanNewTask("Pearl")); vm.submitTask(KanbanNewTask("Pearl")); runCurrent()
        assertEquals(1, submissions); assertTrue(vm.uiState.value.creation!!.loading)
        held.complete(KanbanCreated(KanbanTask("x", "Pearl", "ready"), false)); runCurrent()
        assertNull(vm.uiState.value.creation); assertEquals("Task created.", vm.uiState.value.creationNotice)
        assertTrue(closed); assertEquals(1, reads)
    }

    @Test fun `endpoint change closes form and ignores late completion`() = runTest {
        val held = CompletableDeferred<KanbanCreateDefaults?>(); var closed = false
        val api = object : KanbanCreate {
            override fun open(expectedEndpoint: Long) = object : KanbanCreateScope {
                override suspend fun prepare() = held.await()
                override suspend fun submit(draft: KanbanNewTask): KanbanCreated? = error("not submitted")
                override fun close() { closed = true }
            }
        }
        val endpoint = MutableStateFlow(0L)
        val vm = KanbanViewModel(KanbanPluginRepository { _, _ -> error("not read") }, MutableStateFlow(false), endpoint, backgroundScope, api)
        runCurrent(); vm.openCreate(); runCurrent()
        endpoint.value = 1L; runCurrent()
        held.complete(KanbanCreateDefaults("design", "planner")); runCurrent()
        assertTrue(closed); assertNull(vm.uiState.value.creation)
    }
}
