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
class KanbanFiltersTest {
    @Test fun `search matches title body id case insensitively and combines exact profile tenant`() {
        val a = KanbanTask("A-1", "Write spec", "todo", assignee = "alpha", body = "PEARL", tenant = "one")
        val b = KanbanTask("B-2", "Review", "todo", assignee = "beta", tenant = "two")
        val columns = listOf(KanbanColumn("todo", listOf(a, b)))
        for (q in listOf(" write ", "pearl", "a-1")) {
            assertEquals(listOf(a), KanbanFilters(q, "alpha", "one").apply(columns).single().tasks)
        }
        assertTrue(KanbanFilters("pearl", "Alpha").apply(columns).isEmpty())
        assertTrue(KanbanFilters("pearl", tenant = "two").apply(columns).isEmpty())
        assertEquals(columns, KanbanFilters().apply(columns))
        assertEquals(2, columns.single().tasks.size)
    }

    @Test fun `filter inventory parsed archived request scoped and endpoint clears filters`() = runTest {
        val generation = MutableStateFlow(0L)
        val requests = mutableListOf<Map<String, String>>()
        val repository = KanbanPluginRepository { _, options ->
            requests += options.query
            PluginRestResult.Success(200, """{"columns":[{"name":"todo","tasks":[{"id":"x","title":"Test","status":"todo","tenant":"one"}]}],"assignees":["alpha"],"tenants":["one"]}""".toByteArray())
        }
        val vm = KanbanViewModel(repository, MutableStateFlow(true), generation, backgroundScope)
        runCurrent()
        assertEquals(listOf("alpha"), vm.uiState.value.assignees)
        assertEquals("one", vm.uiState.value.columns.single().tasks.single().tenant)
        vm.setFilters(KanbanFilters(search = "Test", archived = true))
        runCurrent()
        assertEquals(mapOf("include_archived" to "true"), requests.last())
        vm.setFilters(vm.uiState.value.filters.copy(assignee = "alpha"))
        assertEquals(2, requests.size)
        generation.value = 1L
        runCurrent()
        assertEquals(KanbanFilters(), vm.uiState.value.filters)
        assertEquals(emptyMap<String, String>(), requests.last())
    }

    @Test fun `late unarchived snapshot cannot overwrite archived intent`() = runTest {
        val held = CompletableDeferred<PluginRestResult>()
        val vm = KanbanViewModel(KanbanPluginRepository { _, options ->
            if (options.query.isEmpty()) held.await()
            else PluginRestResult.Success(200, """{"columns":[]}""".toByteArray())
        }, MutableStateFlow(true), MutableStateFlow(0L), backgroundScope)
        runCurrent()
        vm.setFilters(KanbanFilters(archived = true))
        runCurrent()
        held.complete(PluginRestResult.Success(200, """{"columns":[{"name":"todo","tasks":[{"id":"old","title":"Old","status":"todo"}]}]}""".toByteArray()))
        runCurrent()
        assertEquals(KanbanPhase.Empty, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.columns.isEmpty())
    }
}
