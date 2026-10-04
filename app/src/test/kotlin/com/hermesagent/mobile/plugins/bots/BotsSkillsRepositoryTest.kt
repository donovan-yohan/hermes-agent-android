package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the real typed repository and OkHttp enqueue boundary through the editor. */
@OptIn(ExperimentalCoroutinesApi::class)
class BotsSkillsRepositoryTest {
    @Test fun `endpoint ABA before actual PUT enqueue never writes or retargets`() = runTest {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val hold = AtomicBoolean(false)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (hold.get()) queued.add(block) else Dispatchers.IO.dispatch(context, block)
            }
        }
        val prepared = CompletableDeferred<Unit>()
        val auth = AtomicInteger(); val puts = AtomicInteger()
        val transport = transport(dispatcher, auth = {
            if (auth.incrementAndGet() == 3) { hold.set(true); prepared.complete(Unit) }
            "Authorization" to "synthetic"
        }) { request ->
            if (request.method == "PUT") puts.incrementAndGet()
            """[{"name":"research","enabled":false}]"""
        }
        val generation = MutableStateFlow(0L); val fence = EndpointDispatchFence()
        val host = host(GatewayPluginSkills(backgroundScope, { transport }, generation, fence), generation)
        val vm = BotsSkillsViewModel(host, backgroundScope)
        vm.open(BotManagementTarget("worker", 0L)); vm.state.first { !it.loading }
        vm.toggle(vm.state.value, "research"); prepared.await(); runCurrent()
        fence.invalidate(); generation.value = 1; runCurrent()
        fence.invalidate(); generation.value = 2; runCurrent()
        hold.set(false)
        while (true) (queued.poll() ?: break).run()
        runCurrent()
        vm.open(BotManagementTarget("worker", 2L)); vm.state.first { !it.loading }
        assertEquals(0, puts.get())
        assertFalse(vm.state.value.rows!!.single().enabled)
    }
    @Test fun `close and reopen cannot cancel admitted PUT and fresh named read reconciles`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val enabled = AtomicBoolean(false); val puts = AtomicInteger()
        val profiles = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val transport = transport { request ->
            profiles += request.url.queryParameter("profile")
            if (request.method == "PUT") {
                puts.incrementAndGet(); entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                enabled.set(true)
                """{"ok":true,"name":"research","enabled":true}"""
            } else """[{"name":"research","enabled":${enabled.get()}}]"""
        }
        val generation = MutableStateFlow(0L)
        val vm = BotsSkillsViewModel(host(GatewayPluginSkills(backgroundScope, { transport }, generation,
            EndpointDispatchFence()), generation), backgroundScope)
        try {
            val target = BotManagementTarget("worker", 0L)
            vm.open(target); vm.state.first { !it.loading }
            vm.toggle(vm.state.value, "research"); entered.await()
            vm.close(); vm.open(target); vm.state.first { !it.loading }
            assertTrue(vm.state.value.busy)
            vm.toggle(vm.state.value, "research")
            release.countDown()
            vm.state.first { !it.loading && !it.busy }
            assertTrue(vm.state.value.rows!!.single().enabled)
            assertEquals(1, puts.get()); assertTrue(profiles.all { it == "worker" })
        } finally { release.countDown() }
    }
    @Test fun `essential echo cannot become editor success and unknown disabled names are untouched`() = runTest {
        val disabled = mutableSetOf("absent-skill", "research")
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val transport = transport { request ->
            if (request.method == "PUT") {
                val buffer = okio.Buffer(); request.body!!.writeTo(buffer); bodies += buffer.readUtf8()
                val value = Json.parseToJsonElement(bodies.last()).jsonObject
                val name = value.getValue("name").jsonPrimitive.content
                if (value.getValue("enabled").jsonPrimitive.boolean) disabled.remove(name) else disabled.add(name)
                // Upstream essential normalization refuses the disable despite echoing it.
                disabled.remove("hermes-agent")
                """{"ok":true,"name":"$name","enabled":${value.getValue("enabled")}}"""
            } else """[{"name":"hermes-agent","enabled":true},{"name":"research","enabled":${"research" !in disabled}}]"""
        }
        val generation = MutableStateFlow(0L)
        val vm = BotsSkillsViewModel(host(GatewayPluginSkills(backgroundScope, { transport }, generation,
            EndpointDispatchFence()), generation), backgroundScope)
        vm.open(BotManagementTarget("worker", 0L)); vm.state.first { !it.loading }
        vm.toggle(vm.state.value, "hermes-agent"); vm.state.first { !it.busy && !it.loading }
        assertTrue(vm.state.value.rows!!.first().enabled)
        assertTrue(vm.state.value.message!!.startsWith("Change not confirmed"))
        assertEquals(listOf("""{"name":"hermes-agent","enabled":false,"profile":"worker"}"""), bodies)
        vm.toggle(vm.state.value, "research"); vm.state.first { !it.busy && !it.loading }
        assertTrue(vm.state.value.rows!!.last().enabled)
        assertEquals(setOf("absent-skill"), disabled)
        assertEquals(2, bodies.size)
    }
    private fun host(api: PluginSkills, generation: MutableStateFlow<Long>) = object : PluginHost {
        override val skills = api
        override val endpointGeneration = generation
        override val connected = MutableStateFlow(true)
        override suspend fun request(method: String, params: kotlinx.serialization.json.JsonObject): PluginHostResult = error("No RPC")
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }
    private fun transport(dispatcher: CoroutineDispatcher = Dispatchers.IO,
        auth: suspend () -> Pair<String, String>? = { "Authorization" to "synthetic" }, reply: (Request) -> String) =
        OkHttpGatewayHttp(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(reply(chain.request()).toResponseBody()).build()
        }.build(), { "https://gateway.example" }, auth, dispatcher)
}
