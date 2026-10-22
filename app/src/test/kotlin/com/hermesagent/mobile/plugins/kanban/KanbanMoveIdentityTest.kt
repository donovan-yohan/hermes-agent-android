package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.PluginRestResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KanbanMoveIdentityTest {
    @Test fun `snapshot resolves board before reading and selection never follows changed current pointer`() = runTest {
        var current = "source"
        val requests = mutableListOf<GatewayHttpRequest>()
        val row = """{"task":{"id":"x","title":"Pearl","status":"ready"}}"""
        val repository = KanbanPluginRepository { path, options ->
            val body = when (path) {
                "boards" -> """{"current":"$current"}"""
                "board" -> {
                    assertEquals("source", options.query["board"])
                    current = "clone"
                    """{"columns":[{"name":"ready","tasks":[{"id":"x","title":"Pearl","status":"ready"}]}]}"""
                }
                else -> { assertEquals("source", options.query["board"]); row }
            }
            PluginRestResult.Success(200, body.toByteArray())
        }
        val snapshot = (repository.board() as KanbanRead.Value).value
        assertEquals("source", snapshot.boardSlug)
        val selected = snapshot.columns.single().tasks.single()
        val detail = (repository.task(selected.id, selected.boardSlug) as KanbanRead.Value).value
        val http = object : EndpointDispatchingGatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unguarded")
            override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult {
                assertTrue(dispatch { true }); requests += request
                assertEquals("source", request.query["board"])
                return GatewayHttpResult.Success(200, when {
                    request.path.endsWith("board") -> """{"columns":[{"name":"ready"},{"name":"blocked"}]}"""
                    request.method == "PATCH" || requests.any { it.method == "PATCH" } -> row.replace("ready", "blocked")
                    else -> row
                }.toByteArray())
            }
        }
        val scope = GatewayKanbanMove({ http }, MutableStateFlow(0L), EndpointDispatchFence()).open(0)!!
        assertNull(scope.prepare(selected.copy(boardSlug = null)))
        assertNotNull(scope.prepare(detail.task.copy(title = "Old title")))
        assertEquals("blocked", scope.move("blocked")?.status)
        assertEquals("clone", current)
        assertTrue(requests.none { it.path.endsWith("boards") })
    }

    @Test fun `background board unavailable revokes move held at production credential admission`() = runTest {
        val board = CompletableDeferred<PluginRestResult>()
        val credentials = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var hold = false
        var writes = 0
        val row = """{"task":{"id":"x","title":"Pearl","status":"ready"}}"""
        val http = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method != "GET") writes++
            val body = if (request.url.encodedPath.endsWith("board"))
                """{"columns":[{"name":"ready"},{"name":"blocked"}]}""" else row
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build(), { "https://gateway.example" }, {
            if (hold) { entered.complete(Unit); credentials.await() }
            "Authorization" to "synthetic"
        })
        val vm = KanbanViewModel(KanbanPluginRepository { path, _ ->
            if (path == "boards") board.await() else PluginRestResult.Success(200, row.toByteArray())
        }, MutableStateFlow(false), MutableStateFlow(0L), backgroundScope,
            GatewayKanbanMove({ http }, MutableStateFlow(0L), EndpointDispatchFence()))
        vm.openTask(KanbanTask("x", "Pearl", "ready", boardSlug = "source")); runCurrent()
        vm.refreshBoard(); runCurrent(); vm.openMove(); runCurrent()
        vm.uiState.first { it.move?.loading == false }
        assertNotNull(vm.uiState.value.move?.choices)
        hold = true
        vm.moveTask("blocked"); runCurrent(); entered.await()
        board.complete(PluginRestResult.UnavailableOnGateway); runCurrent()
        assertNull(vm.uiState.value.move)
        credentials.complete(Unit); runCurrent()
        assertEquals(0, writes)
        assertNull(vm.uiState.value.move)
        assertEquals(KanbanPhase.Unavailable, vm.uiState.value.phase)
    }
}
