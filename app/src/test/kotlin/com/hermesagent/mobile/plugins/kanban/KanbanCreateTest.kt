package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class KanbanCreateTest {
    @Test fun `production transport uses namespace explicit board defaults and confirms before dispatch`() = runTest {
        val calls = mutableListOf<Request>()
        val payloads = mutableListOf<String>()
        val http = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            synchronized(calls) { calls += request; payloads += request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty() }
            val body = when (request.url.encodedPath) {
                "/api/plugins/kanban/boards" -> """{"current":"design"}"""
                "/api/plugins/kanban/orchestration" -> """{"resolved_default_assignee":"planner"}"""
                "/api/plugins/kanban/dispatch" -> "{}"
                else -> """{"task":{"id":"new-1","title":"Pearl","status":"ready","assignee":"planner"}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build(), { "https://gateway.example" }, { "Authorization" to "synthetic" })
        val scope = GatewayKanbanCreate({ http }, MutableStateFlow(0L), EndpointDispatchFence()).open(0)!!
        assertEquals(KanbanCreateDefaults("design", "planner"), scope.prepare())
        assertEquals("new-1", scope.submit(KanbanNewTask(" Pearl ", "Description", 3))!!.task.id)
        assertNull(scope.submit(KanbanNewTask("Pearl")))
        assertEquals(listOf("GET", "GET", "POST", "GET", "POST"), calls.map { it.method })
        assertTrue(calls.all { it.header("Authorization") == "synthetic" })
        calls.drop(2).forEach { assertEquals("design", it.url.queryParameter("board")) }
        val payload = kotlinx.serialization.json.Json.parseToJsonElement(payloads[2]).toString()
        assertTrue(payload.contains("\"assignee\":\"planner\""))
        assertTrue(payload.contains("\"idempotency_key\""))
        assertFalse(payload.contains("workspace_path"))
        scope.close()
    }

    @Test fun `close during credential resolution prevents any wire request`() = runTest {
        val held = CompletableDeferred<Unit>()
        var count = 0
        val http = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
        }.build(), { "https://gateway.example" }, { held.await(); "Authorization" to "synthetic" })
        val scope = GatewayKanbanCreate({ http }, MutableStateFlow(0L), EndpointDispatchFence()).open(0)!!
        val result = async(start = CoroutineStart.UNDISPATCHED) { scope.prepare() }
        scope.close(); held.complete(Unit)
        assertNull(result.await()); assertEquals(0, count)
    }

    @Test fun `endpoint fence revokes prepared create before mutation`() = runTest {
        val fence = EndpointDispatchFence()
        val requests = mutableListOf<GatewayHttpRequest>()
        val http = object : EndpointDispatchingGatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unguarded")
            override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult {
                if (!dispatch { requests += request; true }) return GatewayHttpResult.Rejected(0, "closed")
                return GatewayHttpResult.Success(200, (if (request.path.endsWith("boards")) """{"current":"design"}"""
                    else """{"resolved_default_assignee":"planner"}""").toByteArray())
            }
        }
        val scope = GatewayKanbanCreate({ http }, MutableStateFlow(0L), fence).open(0)!!
        assertNotNull(scope.prepare()); fence.invalidate()
        assertNull(scope.submit(KanbanNewTask("Pearl")))
        assertEquals(2, requests.size)
    }

    @Test fun `unconfirmed response is never nudged or automatically resubmitted`() = runTest {
        var writes = 0
        val http = object : EndpointDispatchingGatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unguarded")
            override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult {
                dispatch { true }
                val body = when {
                    request.path.endsWith("boards") -> """{"current":"design"}"""
                    request.path.endsWith("orchestration") -> """{"resolved_default_assignee":"planner"}"""
                    else -> { writes++; """{"task":null}""" }
                }
                return GatewayHttpResult.Success(200, body.toByteArray())
            }
        }
        val scope = GatewayKanbanCreate({ http }, MutableStateFlow(0L), EndpointDispatchFence()).open(0)!!
        scope.prepare()
        assertNull(scope.submit(KanbanNewTask("Pearl")))
        assertNull(scope.submit(KanbanNewTask("Pearl")))
        assertEquals(1, writes)
    }
}
