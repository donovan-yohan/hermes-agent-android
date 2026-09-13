package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.plugins.PluginRestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() = Dispatchers.setMain(dispatcher)

    @After
    fun teardown() = Dispatchers.resetMain()

    @Test
    fun `lifecycle and endpoint changes refresh board without auto opening detail`() = runTest {
        val connected = MutableStateFlow(false)
        val endpoint = MutableStateFlow(0L)
        val vm = KanbanViewModel(repository(board), connected, endpoint)

        runCurrent()
        assertEquals(KanbanPhase.Loading, vm.uiState.value.phase)

        connected.value = true
        advanceUntilIdle()
        assertEquals(KanbanPhase.Ready, vm.uiState.value.phase)
        assertEquals(KanbanDetail.None, vm.uiState.value.detail)

        endpoint.value = 1L
        advanceUntilIdle()
        assertEquals(KanbanPhase.Ready, vm.uiState.value.phase)
    }

    @Test
    fun `manual refresh rejects an older same endpoint answer`() = runTest {
        val first = CompletableDeferred<ByteArray>()
        var read = 0
        val repository = KanbanPluginRepository { _, _ ->
            if (read++ == 0) PluginRestResult.Success(200, first.await())
            else PluginRestResult.Success(200, newer)
        }
        val vm = KanbanViewModel(repository, MutableStateFlow(false), MutableStateFlow(0))

        runCurrent()
        vm.refreshBoard()
        runCurrent()
        vm.refreshBoard()
        advanceUntilIdle()
        first.complete(board)
        advanceUntilIdle()

        assertEquals("New", vm.uiState.value.columns.single().tasks.single().title)
    }

    @Test
    fun `selection rejects a stale detail answer and keeps the later task`() = runTest {
        val first = CompletableDeferred<ByteArray>()
        var call = 0
        val repository = KanbanPluginRepository { path, _ ->
            PluginRestResult.Success(
                200,
                if (path == "board") board else if (call++ == 0) first.await() else newerDetail,
            )
        }
        val vm = KanbanViewModel(repository, MutableStateFlow(false), MutableStateFlow(0))

        vm.refreshBoard()
        advanceUntilIdle()
        val task = vm.uiState.value.columns.single().tasks.single()
        vm.openTask(task)
        runCurrent()
        vm.openTask(task)
        advanceUntilIdle()
        first.complete(detail)
        advanceUntilIdle()

        assertEquals("New", (vm.uiState.value.detail as KanbanDetail.Value).detail.task.title)
    }

    @Test
    fun `detail unavailable gone and refusal stay distinct and never expose safe message`() = runTest {
        var response: PluginRestResult = PluginRestResult.UnavailableOnGateway
        val vm = KanbanViewModel(
            KanbanPluginRepository { _, _ -> response },
            MutableStateFlow(false),
            MutableStateFlow(0),
        )
        val task = KanbanTask("x", "Task", "open")

        vm.openTask(task)
        advanceUntilIdle()
        assertEquals(KanbanDetail.Unavailable(task), vm.uiState.value.detail)

        response = PluginRestResult.Refused(404, "backend safeMessage")
        vm.openTask(task)
        advanceUntilIdle()
        assertEquals(KanbanDetail.Gone(task), vm.uiState.value.detail)

        response = PluginRestResult.Refused(500, "backend safeMessage")
        vm.openTask(task)
        advanceUntilIdle()
        assertEquals(KanbanDetail.Refused(task), vm.uiState.value.detail)
    }

    @Test
    fun `failed board refresh marks stale and successful empty refresh clears it`() = runTest {
        var response: PluginRestResult = PluginRestResult.Success(200, board)
        val vm = KanbanViewModel(
            KanbanPluginRepository { _, _ -> response },
            MutableStateFlow(false),
            MutableStateFlow(0),
        )

        vm.refreshBoard()
        advanceUntilIdle()
        response = PluginRestResult.Refused(500, "raw backend error")
        vm.refreshBoard()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.stale)
        assertEquals(KanbanPhase.Ready, vm.uiState.value.phase)

        response = PluginRestResult.Success(200, empty)
        vm.refreshBoard()
        advanceUntilIdle()
        assertEquals(KanbanPhase.Empty, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.stale)
    }

    private fun repository(answer: ByteArray) = KanbanPluginRepository { _, _ ->
        PluginRestResult.Success(200, answer)
    }

    private companion object {
        val board = """{"columns":[{"name":"Open","tasks":[{"id":"x","title":"Old","status":"open"}]}]}""".toByteArray()
        val newer = """{"columns":[{"name":"Open","tasks":[{"id":"x","title":"New","status":"open"}]}]}""".toByteArray()
        val empty = """{"columns":[]}""".toByteArray()
        val detail = """{"task":{"id":"x","title":"Old","status":"open"},"links":{"parents":[],"children":[]},"child_results":[]}""".toByteArray()
        val newerDetail = """{"task":{"id":"x","title":"New","status":"open"},"links":{"parents":[],"children":[]},"child_results":[]}""".toByteArray()
    }
}
