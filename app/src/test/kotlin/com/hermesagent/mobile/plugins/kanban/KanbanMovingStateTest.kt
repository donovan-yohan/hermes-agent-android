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
class KanbanMovingStateTest {
    private val task = KanbanTask("x", "Pearl", "todo")
    private val detail = KanbanTaskDetail(task, emptyList(), emptyList(), emptyList())
    private val wire = """{"task":{"id":"x","title":"Pearl","status":"todo"}}"""

    @Test fun `move is single flight closes detail and refreshes only after confirmed result`() = runTest {
        val held = CompletableDeferred<KanbanTask?>(); var writes = 0; var closed = false
        val api = object : KanbanMove {
            override fun open(expectedEndpoint: Long) = object : KanbanMoveScope {
                override suspend fun prepare(task: KanbanTask) = KanbanMoveChoices("design", task, listOf("done"))
                override suspend fun move(status: String): KanbanTask? { writes++; return held.await() }
                override fun close() { closed = true }
            }
        }
        val vm = KanbanViewModel(KanbanPluginRepository { path, _ -> PluginRestResult.Success(200,
            (if (path == "board") """{"columns":[]}""" else wire).toByteArray()) },
            MutableStateFlow(false), MutableStateFlow(0L), backgroundScope, api)
        vm.openTask(task); runCurrent(); vm.openMove(); runCurrent()
        vm.moveTask("running"); assertEquals(0, writes)
        vm.moveTask("done"); vm.moveTask("done"); runCurrent()
        assertEquals(1, writes); assertTrue(vm.uiState.value.move!!.loading)
        held.complete(task.copy(status = "done")); runCurrent()
        assertTrue(closed); assertNull(vm.uiState.value.move)
        assertEquals(KanbanDetail.None, vm.uiState.value.detail)
        assertEquals("Task moved.", vm.uiState.value.moveNotice)
    }

    @Test fun `close invalidates late prepare without opening another surface`() = runTest {
        val held = CompletableDeferred<KanbanMoveChoices?>(); var closed = false
        val api = object : KanbanMove {
            override fun open(expectedEndpoint: Long) = object : KanbanMoveScope {
                override suspend fun prepare(task: KanbanTask) = held.await()
                override suspend fun move(status: String): KanbanTask? = error("not submitted")
                override fun close() { closed = true }
            }
        }
        val vm = KanbanViewModel(KanbanPluginRepository { _, _ -> PluginRestResult.Success(200, wire.toByteArray()) },
            MutableStateFlow(false), MutableStateFlow(0L), backgroundScope, api)
        vm.openTask(task); runCurrent(); vm.openMove(); runCurrent(); vm.closeMove()
        held.complete(KanbanMoveChoices("design", task, listOf("done"))); runCurrent()
        assertTrue(closed); assertNull(vm.uiState.value.move)
        assertTrue(vm.uiState.value.detail is KanbanDetail.Value)
    }
}
