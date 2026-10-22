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
class KanbanCreateRevocationTest {
    @Test fun `background unavailable board revokes create waiting for production credentials`() = runTest {
        val board = CompletableDeferred<PluginRestResult>()
        val credentials = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var hold = false
        var writes = 0
        val http = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (request.method == "POST") writes++
            val body = if (request.url.encodedPath.endsWith("boards"))
                """{"current":"design","boards":[{"slug":"design","default_workspace_kind":"dir"}]}"""
                else """{"resolved_default_assignee":"planner"}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build(), { "https://gateway.example" }, {
            if (hold) { entered.complete(Unit); credentials.await() }
            "Authorization" to "synthetic"
        })
        val vm = KanbanViewModel(KanbanPluginRepository { _, _ -> board.await() },
            MutableStateFlow(false), MutableStateFlow(0L), backgroundScope,
            GatewayKanbanCreate({ http }, MutableStateFlow(0L), EndpointDispatchFence()))
        vm.refreshBoard(); runCurrent()
        vm.openCreate(); runCurrent()
        // Interceptor completion runs on OkHttp; awaiting the scope's preparation avoids wall-clock sleeps.
        vm.uiState.first { it.creation?.loading == false }
        assertNotNull(vm.uiState.value.creation?.defaults)
        hold = true
        vm.submitTask(KanbanNewTask("Pearl")); runCurrent(); entered.await()
        board.complete(PluginRestResult.UnavailableOnGateway); runCurrent()
        assertNull(vm.uiState.value.creation)
        credentials.complete(Unit); runCurrent()
        assertEquals(0, writes)
        assertNull(vm.uiState.value.creation)
        assertEquals(KanbanPhase.Unavailable, vm.uiState.value.phase)
    }
}
