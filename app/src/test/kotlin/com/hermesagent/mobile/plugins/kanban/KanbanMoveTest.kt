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

class KanbanMoveTest {
    private val task = KanbanTask("t /+", "Pearl", "todo")
    @Test fun `production transport preserves namespace board encoding allowed lanes and readback`() = runTest {
        val calls = mutableListOf<Request>()
        var status = "todo"
        val http = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); synchronized(calls) { calls += request }
            if (request.method == "PATCH") {
                assertEquals("""{"status":"done"}""", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                status = "done"
            }
            val body = when {
                request.url.encodedPath.endsWith("/boards") -> """{"current":"design"}"""
                request.url.encodedPath.endsWith("/board") -> """{"columns":[{"name":"todo"},{"name":"ready"},{"name":"running"},{"name":"review"},{"name":"scheduled"},{"name":"done"}]}"""
                request.url.encodedPath.endsWith("/dispatch") -> "{}"
                else -> """{"task":{"id":"t /+","title":"Pearl","status":"$status"}}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build(), { "https://gateway.example" }, { "X-Hermes-Session-Token" to "synthetic" })
        val scope = GatewayKanbanMove({ http }, MutableStateFlow(0L), EndpointDispatchFence()).open(0)!!
        val choices = scope.prepare(task)!!
        assertEquals(listOf("ready", "done"), choices.statuses)
        assertNull(scope.move("running"))
        assertEquals("done", scope.move("done")!!.status)
        assertNull(scope.move("ready"))
        assertEquals(listOf("GET", "GET", "GET", "PATCH", "GET", "POST"), calls.map { it.method })
        calls.drop(1).forEach { assertEquals("design", it.url.queryParameter("board")) }
        assertTrue(calls[3].url.encodedPath.endsWith("t%20%2F%2B"))
        assertTrue(calls.all { it.header("X-Hermes-Session-Token") == "synthetic" })
        scope.close()
    }

    @Test fun `close during production credential wait revokes admission`() = runTest {
        val held = CompletableDeferred<Unit>(); var count = 0
        val http = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{}".toResponseBody()).build()
        }.build(), { "https://gateway.example" }, { held.await(); "Authorization" to "synthetic" })
        val scope = GatewayKanbanMove({ http }, MutableStateFlow(0L), EndpointDispatchFence()).open(0)!!
        val result = async(start = CoroutineStart.UNDISPATCHED) { scope.prepare(task) }
        scope.close(); held.complete(Unit)
        assertNull(result.await()); assertEquals(0, count)
    }

    @Test fun `server conflict stays failure no nudge or automatic retry`() = runTest {
        var writes = 0
        val scope = fakeScope { request ->
            if (request.method == "PATCH") { writes++; GatewayHttpResult.Rejected(409, "synthetic") }
            else successFor(request)
        }
        assertNotNull(scope.prepare(task))
        assertNull(scope.move("done")); assertNull(scope.move("done"))
        assertEquals(1, writes)
    }

    @Test fun `readback mismatch cannot claim a move succeeded`() = runTest {
        var nudges = 0
        val scope = fakeScope { request ->
            when {
                request.path.endsWith("dispatch") -> { nudges++; GatewayHttpResult.Success(200, "{}".toByteArray()) }
                request.method == "PATCH" -> GatewayHttpResult.Success(200, """{"task":{"id":"t /+","title":"Pearl","status":"done"}}""".toByteArray())
                else -> successFor(request)
            }
        }
        scope.prepare(task)
        assertNull(scope.move("done")); assertEquals(0, nudges)
    }

    @Test fun `endpoint invalidation blocks prepared move`() = runTest {
        val fence = EndpointDispatchFence(); var writes = 0
        val scope = fakeScope(fence) { request -> if (request.method == "PATCH") writes++; successFor(request) }
        scope.prepare(task); fence.invalidate()
        assertNull(scope.move("done")); assertEquals(0, writes)
    }

    private fun fakeScope(fence: EndpointDispatchFence = EndpointDispatchFence(), respond: (GatewayHttpRequest) -> GatewayHttpResult): KanbanMoveScope {
        val http = object : EndpointDispatchingGatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unguarded")
            override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult =
                if (dispatch { true }) respond(request) else GatewayHttpResult.Rejected(0, "closed")
        }
        return GatewayKanbanMove({ http }, MutableStateFlow(0L), fence).open(0)!!
    }
    private fun successFor(request: GatewayHttpRequest): GatewayHttpResult.Success = GatewayHttpResult.Success(200, when {
        request.path.endsWith("boards") -> """{"current":"design"}"""
        request.path.endsWith("board") -> """{"columns":[{"name":"todo"},{"name":"ready"},{"name":"done"}]}"""
        else -> """{"task":{"id":"t /+","title":"Pearl","status":"todo"}}"""
    }.toByteArray())
}
