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
class PluginSkillsSafetyTest {
    @Test fun `ordinary and absent hosts fail closed without touching generic REST or RPC`() = runTest {
        val host = object : PluginHost {
            override suspend fun request(method: String, params: kotlinx.serialization.json.JsonObject): PluginHostResult = error("RPC escape")
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        assertEquals(SkillsResult.Failure(SkillsFailure.MissingCapability), host.skills.openScope(0, "writer").listInstalled())
        val unfenced = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("unfenced dispatch")
        }
        for (http in listOf(null, unfenced)) {
            val scope = GatewayPluginSkills(backgroundScope, { http }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
            assertEquals(SkillsResult.Failure(SkillsFailure.MissingCapability), scope.toggleInstalled("skill", false))
        }
    }

    @Test fun `only unscoped read-only 404 proves absent core route`() = runTest {
        for (probeStatus in listOf(200, 403, 404, 500)) {
            val requests = mutableListOf<Request>()
            val transport = transport { request ->
                requests += request
                if (request.url.queryParameter("profile") != null) 404 to "secret scoped failure"
                else probeStatus to "[]"
            }
            val scope = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
            assertEquals(SkillsResult.Failure(if (probeStatus == 404) SkillsFailure.UnavailableOnGateway else SkillsFailure.Refused), scope.listInstalled())
            assertEquals(listOf("writer", null), requests.map { it.url.queryParameter("profile") })
            assertTrue(requests.all { it.method == "GET" && it.url.encodedPath == "/api/skills" })
        }
    }

    @Test fun `queued actual transport refuses endpoint ABA and never resolves replacement credentials`() = runTest {
        val io = QueuedDispatcher()
        val calls = AtomicInteger()
        val auth = AtomicInteger()
        val original = transport(io, auth = { auth.incrementAndGet(); "Authorization" to "original" }) {
            calls.incrementAndGet(); 200 to "[]"
        }
        val replacement = transport(auth = { error("replacement credential resolved") }) { error("replacement wire") }
        var live: GatewayHttp? = original
        val generation = MutableStateFlow(0L)
        val fence = EndpointDispatchFence()
        val api = GatewayPluginSkills(backgroundScope, { live }, generation, fence)
        val scope = api.openScope(0, "writer")
        val task = async { scope.listInstalled() }
        runCurrent() // production transport is queued immediately before OkHttp admission
        assertEquals(1, auth.get())
        fence.invalidate(); generation.value = 1; live = replacement
        fence.invalidate(); generation.value = 2; live = original
        io.drain(); runCurrent()
        assertEquals(SkillsResult.Failure(SkillsFailure.StaleScope), task.await())
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
        }) { request -> methods += request.method; 200 to """[{"name":"skill","enabled":true}]""" }
        val api = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        val first = api.openScope(0, "writer")
        val task = async { first.toggleInstalled("skill", false) }
        atPut.await()
        first.close()
        api.openScope(0, "other").close()
        val reopened = api.openScope(0, "writer")
        release.complete(Unit)
        assertEquals(SkillsResult.Failure(SkillsFailure.StaleScope), task.await())
        assertEquals(listOf("GET"), methods)
        assertTrue(reopened.listInstalled() is SkillsResult.Success)
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
            } else 200 to """[{"name":"skill","enabled":true}]"""
        }
        val fence = EndpointDispatchFence()
        val generation = MutableStateFlow(0L)
        val scope = GatewayPluginSkills(backgroundScope, { transport }, generation, fence).openScope(0, "writer")
        val task = async { scope.toggleInstalled("skill", false) }
        try {
            atPut.await()
            // These MUST finish while the network reply is still held.
            withContext(Dispatchers.Default) { fence.invalidate(); generation.value = 1; scope.close() }
        } finally { release.countDown() }
        assertEquals(SkillsResult.Failure(SkillsFailure.StaleScope, true), task.await())
        assertEquals(2, requests.get()) // no stale readback/replay
    }

    @Test fun `invalid scope malformed inventory and raw errors never cross typed host`() = runTest {
        for ((code, body) in listOf(
            403 to "Authorization: Bearer do-not-publish",
            200 to "{\"raw\":\"do-not-publish\"}",
            200 to """[{"name":"skill","enabled":"true"}]""",
            200 to """[{"name":"skill","enabled":true},{"name":"skill","enabled":false}]""",
        )) {
            val calls = AtomicInteger()
            val transport = transport { calls.incrementAndGet(); code to body }
            val api = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
            assertEquals(SkillsResult.Failure(SkillsFailure.InvalidScope), api.openScope(0, "../writer").listInstalled())
            assertEquals(0, calls.get())
            val result = api.openScope(0, "writer").listInstalled()
            assertTrue(result is SkillsResult.Failure)
            assertFalse(result.toString().contains("do-not-publish"))
        }
    }

    @Test fun `current alias is not an explicit named profile authority`() = runTest {
        val transport = transport { error("alias must not dispatch") }
        val api = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        assertEquals(SkillsResult.Failure(SkillsFailure.InvalidScope), api.openScope(0, "current").listInstalled())
    }

    @Test fun `inconclusive route probe preserves original scoped refusal`() = runTest {
        val auth = AtomicInteger()
        val transport = transport(auth = {
            if (auth.incrementAndGet() == 2) throw IllegalStateException("private credential failure")
            "Authorization" to "synthetic"
        }) { 404 to "private profile failure" }
        val api = GatewayPluginSkills(backgroundScope, { transport }, MutableStateFlow(0L), EndpointDispatchFence())
        assertEquals(SkillsResult.Failure(SkillsFailure.Refused), api.openScope(0, "writer").listInstalled())
    }

    @Test fun `cancelled plugin owner cannot admit HTTP`() = runTest {
        val job = Job().also { it.cancel() }
        val owner = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        val transport = transport { error("cancelled owner dispatch") }
        val scope = GatewayPluginSkills(owner, { transport }, MutableStateFlow(0L), EndpointDispatchFence()).openScope(0, "writer")
        try { scope.listInstalled(); fail("must cancel") } catch (_: CancellationException) { }
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
