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
    @Test fun `submit is single flight confirmation closes form and refreshes archived board retaining filters`() = runTest {
        val held = CompletableDeferred<KanbanCreated?>()
        val queries = mutableListOf<Map<String, String>>()
        val filters = KanbanFilters(search = "Pearl", assignee = "planner", tenant = "design", archived = true)
        var submissions = 0; var reads = 0; var closed = false
        val api = object : KanbanCreate {
            override fun open(expectedEndpoint: Long) = object : KanbanCreateScope {
                override suspend fun prepare() = KanbanCreateDefaults("design", "planner")
                override suspend fun submit(draft: KanbanNewTask): KanbanCreated? { submissions++; return held.await() }
                override fun close() { closed = true }
            }
        }
        val vm = KanbanViewModel(KanbanPluginRepository { path, options ->
            if (path == "boards") return@KanbanPluginRepository PluginRestResult.Success(200, """{"current":"design"}""".toByteArray())
            reads++; queries += options.query
            PluginRestResult.Success(200, """{"columns":[],"assignees":["planner"],"tenants":["design"]}""".toByteArray()) },
            MutableStateFlow(false), MutableStateFlow(0L), backgroundScope, create = api)
        vm.setFilters(filters); runCurrent()
        vm.openCreate(); runCurrent()
        vm.submitTask(KanbanNewTask("Pearl")); vm.submitTask(KanbanNewTask("Pearl")); runCurrent()
        assertEquals(1, submissions); assertTrue(vm.uiState.value.creation!!.loading)
        held.complete(KanbanCreated(KanbanTask("x", "Pearl", "ready"), false)); runCurrent()
        assertNull(vm.uiState.value.creation); assertEquals("Task created.", vm.uiState.value.creationNotice)
        assertTrue(closed); assertEquals(2, reads)
        assertEquals(filters, vm.uiState.value.filters)
        assertEquals(listOf("planner"), vm.uiState.value.assignees)
        assertEquals(listOf("design"), vm.uiState.value.tenants)
        assertEquals(List(2) { mapOf("board" to "design", "include_archived" to "true") }, queries)
    }

    @Test fun `unavailable background board closes scope and late failure cannot restore form`() = runTest {
        val board = CompletableDeferred<PluginRestResult>()
        val submit = CompletableDeferred<KanbanCreated?>()
        var closed = false
        val api = object : KanbanCreate {
            override fun open(expectedEndpoint: Long) = object : KanbanCreateScope {
                override suspend fun prepare() = KanbanCreateDefaults("design", "planner")
                override suspend fun submit(draft: KanbanNewTask) = submit.await()
                override fun close() { closed = true }
            }
        }
        val vm = KanbanViewModel(KanbanPluginRepository { _, _ -> board.await() },
            MutableStateFlow(false), MutableStateFlow(0L), backgroundScope, create = api)
        vm.refreshBoard(); runCurrent(); vm.openCreate(); runCurrent()
        vm.submitTask(KanbanNewTask("Pearl")); runCurrent()
        board.complete(PluginRestResult.UnavailableOnGateway); runCurrent()
        assertTrue(closed); assertNull(vm.uiState.value.creation)
        submit.complete(null); runCurrent()
        assertNull(vm.uiState.value.creation)
        assertEquals(KanbanPhase.Unavailable, vm.uiState.value.phase)
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
        val vm = KanbanViewModel(KanbanPluginRepository { _, _ -> error("not read") }, MutableStateFlow(false), endpoint, backgroundScope, create = api)
        runCurrent(); vm.openCreate(); runCurrent()
        endpoint.value = 1L; runCurrent()
        held.complete(KanbanCreateDefaults("design", "planner")); runCurrent()
        assertTrue(closed); assertNull(vm.uiState.value.creation)
    }
}
