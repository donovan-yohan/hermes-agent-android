package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PluginMcpSafetyTest {
    @Test fun `ordinary and absent hosts fail closed without touching generic REST or RPC`() = runTest {
        val host = object : PluginHost {
            override suspend fun request(method: String, params: kotlinx.serialization.json.JsonObject): PluginHostResult = error("RPC escape")
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        assertEquals(McpResult.Failure(McpFailure.MissingCapability), host.mcp.openScope(0, "writer").listServers())
        val unfenced = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unfenced dispatch")
        }
        for (http in listOf(null, unfenced)) {
            val scope = GatewayPluginMcp(backgroundScope, { http }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
            assertEquals(McpResult.Failure(McpFailure.MissingCapability), scope.setEnabled("skill", false))
        }
    }

    @Test fun `only unscoped read-only 404 proves absent core route`() = runTest {
        for (probeStatus in listOf(200, 403, 404, 500)) {
            val requests = mutableListOf<Request>()
            val transport = transport { request ->
                requests += request
                if (request.url.queryParameter("profile") != null) 404 to "secret scoped failure"
                else probeStatus to """{"servers":[]}"""
            }
            val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
            assertEquals(McpResult.Failure(if (probeStatus == 404) McpFailure.UnavailableOnGateway else McpFailure.Refused), scope.listServers())
            assertEquals(listOf("writer", null), requests.map { it.url.queryParameter("profile") })
            assertTrue(requests.all { it.method == "GET" && it.url.encodedPath == "/api/mcp/servers" })
        }
    }

    @Test fun `queued actual transport refuses endpoint ABA and never resolves replacement credentials`() = runTest {
        val io = QueuedDispatcher()
        val calls = AtomicInteger()
        val auth = AtomicInteger()
        val original = transport(io, auth = { auth.incrementAndGet(); "Authorization" to "original" }) {
            calls.incrementAndGet(); 200 to """{"servers":[]}"""
        }
        val replacement = transport(auth = { error("replacement credential resolved") }) { error("replacement wire") }
        var live: GatewayHttp? = original
        val generation = MutableStateFlow(0L)
        val fence = EndpointDispatchFence()
        val api = GatewayPluginMcp(backgroundScope, { live }, generation, fence)
        val scope = api.openScope(0, "writer")
        val task = async { scope.listServers() }
        runCurrent() // production transport is queued immediately before OkHttp admission
        assertEquals(1, auth.get())
        fence.invalidate(); generation.value = 1; live = replacement
        fence.invalidate(); generation.value = 2; live = original
        io.drain(); runCurrent()
        assertEquals(McpResult.Failure(McpFailure.StaleScope), task.await())
        assertEquals(0, calls.get())
    }

    @Test fun `dialog close reopen on same endpoint never revives queued PUT`() = runTest {
        val atPut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = AtomicInteger()
        val methods = mutableListOf<String>()
        val transport = transport(auth = {
            if (auth.incrementAndGet() == 2) { atPut.complete(Unit); release.await() }
            "Authorization" to "original"
        }) { request -> methods += request.method; 200 to """{"servers":[{"name":"skill","source":"config","enabled":true}]}""" }
        val api = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        val first = api.openScope(0, "writer")
        val task = async { first.setEnabled("skill", false) }
        atPut.await()
        first.close()
        api.openScope(0, "other").close()
        val reopened = api.openScope(0, "writer")
        release.complete(Unit)
        assertEquals(McpResult.Failure(McpFailure.StaleScope), task.await())
        assertEquals(listOf("GET"), methods)
        assertTrue(reopened.listServers() is McpResult.Success)
    }

    @Test fun `held PUT reply does not hold endpoint or dialog locks and cannot publish success`() = runTest {
        val atPut = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val transport = transport { request ->
            requests.incrementAndGet()
            if (request.method == "PUT") {
                atPut.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                200 to """{"ok":true,"name":"skill","enabled":false}"""
            } else 200 to """{"servers":[{"name":"skill","source":"config","enabled":true}]}"""
        }
        val fence = EndpointDispatchFence()
        val generation = MutableStateFlow(0L)
        val scope = GatewayPluginMcp(backgroundScope, { transport }, generation, fence).openScope(0, "writer")
        val task = async { scope.setEnabled("skill", false) }
        try {
            atPut.await()
            // These MUST finish while the network reply is still held.
            withContext(Dispatchers.Default) { fence.invalidate(); generation.value = 1; scope.close() }
        } finally { release.countDown() }
        assertEquals(McpResult.Failure(McpFailure.StaleScope, true), task.await())
        assertEquals(2, requests.get()) // no stale readback/replay
    }

    @Test fun `invalid scope malformed inventory and raw errors never cross typed host`() = runTest {
        for ((code, body) in listOf(
            403 to "Authorization: Bearer do-not-publish",
            200 to "{\"raw\":\"do-not-publish\"}",
            200 to """{"servers":[{"name":"skill","source":"config","enabled":"true"}]}""",
            200 to """{"servers":[{"name":"skill","source":"config","enabled":true},{"name":"skill","source":"config","enabled":false}]}""",
        )) {
            val calls = AtomicInteger()
            val transport = transport { calls.incrementAndGet(); code to body }
            val api = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
            assertEquals(McpResult.Failure(McpFailure.InvalidScope), api.openScope(0, "../writer").listServers())
            assertEquals(0, calls.get())
            val result = api.openScope(0, "writer").listServers()
            assertTrue(result is McpResult.Failure)
            assertFalse(result.toString().contains("do-not-publish"))
        }
    }

    @Test fun `current alias is not an explicit named profile authority`() = runTest {
        val transport = transport { error("alias must not dispatch") }
        val api = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        assertEquals(McpResult.Failure(McpFailure.InvalidScope), api.openScope(0, "current").listServers())
    }

    @Test fun `inconclusive route probe preserves original scoped refusal`() = runTest {
        val auth = AtomicInteger()
        val transport = transport(auth = {
            if (auth.incrementAndGet() == 2) throw IllegalStateException("private credential failure")
            "Authorization" to "synthetic"
        }) { 404 to "private profile failure" }
        val api = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        assertEquals(McpResult.Failure(McpFailure.Refused), api.openScope(0, "writer").listServers())
    }

    @Test fun `cancelled plugin owner cannot admit HTTP`() = runTest {
        val job = Job().also { it.cancel() }
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val transport = transport { error("cancelled owner dispatch") }
        val scope = GatewayPluginMcp(owner, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
        try { scope.listServers(); fail("must cancel") } catch (_: CancellationException) { }
    }


    @Test fun `non config provenance and unknown names never admit mutation`() = runTest {
        for (source in listOf("plugin", "future_CANARY", "", "config")) {
            val methods = mutableListOf<String>()
            val transport = transport { request ->
                methods += request.method
                200 to """{"servers":[{"name":"known","enabled":true,"source":"$source"}]}"""
            }
            val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
            val result = scope.setEnabled(if (source == "config") "unknown" else "known", false)
            assertTrue(result is McpResult.Failure)
            assertEquals(listOf("GET"), methods)
            assertFalse(result.toString().contains("CANARY"))
        }
    }

    @Test fun `path separators and dot segments are refused without wire admission`() = runTest {
        val transport = transport { error("invalid name must not dispatch") }
        val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
        for (name in listOf(".", "..", "a/b", "a\\b")) {
            assertEquals(McpResult.Failure(McpFailure.InvalidScope), scope.setEnabled(name, false))
        }
    }

    @Test fun `retired same endpoint transport cannot admit a queued mutation`() = runTest {
        val atPut = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val auth = AtomicInteger()
        val methods = mutableListOf<String>()
        val original = transport(auth = {
            if (auth.incrementAndGet() == 2) { atPut.complete(Unit); release.await() }
            "Authorization" to "original"
        }) { request -> methods += request.method; 200 to """{"servers":[{"name":"server","enabled":true,"source":"config"}]}""" }
        var live: GatewayHttp? = original
        val api = GatewayPluginMcp(backgroundScope, { live }, MutableStateFlow(0L), EndpointDispatchFence())
        val scope = api.openScope(0, "writer")
        val task = async { scope.setEnabled("server", false) }
        atPut.await()
        live = transport(auth = { error("replacement credentials must not resolve") }) { error("replacement dispatch") }
        release.complete(Unit)
        assertEquals(McpResult.Failure(McpFailure.StaleScope), task.await())
        assertEquals(listOf("GET"), methods)
    }

    @Test fun `mutation rejection is uncertain and never probes or replays including redirects`() = runTest {
        for (status in listOf(302, 307, 404, 503)) {
            val methods = mutableListOf<String>()
            val transport = transport { request ->
                methods += request.method
                if (request.method == "PUT") status to "ERROR_CANARY"
                else 200 to """{"servers":[{"name":"server","enabled":true,"source":"config"}]}"""
            }
            val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
            val result = scope.setEnabled("server", false)
            assertEquals(McpResult.Failure(McpFailure.Refused, true), result)
            assertEquals(listOf("GET", "PUT"), methods)
            assertFalse(result.toString().contains("CANARY"))
        }
    }

    @Test fun `raw thrown errors never cross typed boundary`() = runTest {
        val transport = transport(auth = { throw IllegalStateException("URL_CMD_ARGS_ENV_CANARY") }) { error("no wire") }
        val scope = GatewayPluginMcp(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
        val result = scope.listServers()
        assertEquals(McpResult.Failure(McpFailure.Unreachable), result)
        assertFalse(result.toString().contains("CANARY"))
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        fun drain() { while (true) (queued.poll() ?: return).run() }
    }

    private fun transport(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        auth: suspend () -> Pair<String, String>? = { "Authorization" to "synthetic" },
        reply: (Request) -> Pair<Int, String>,
    ) = OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("synthetic")
            .body(body.toResponseBody()).build()
    }.build(), { "https://gateway.example" }, auth, dispatcher)
}
