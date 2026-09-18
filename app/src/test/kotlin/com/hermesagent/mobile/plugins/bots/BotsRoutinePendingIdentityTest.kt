package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotsRoutinePendingIdentityTest {
    private class Write(val params: JsonObject) {
        val beforeWire = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Boolean>()
        val owner get() = (params["profile"] as JsonPrimitive).content
    }

    private class Client : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        val attempts = mutableListOf<Write>()
        val wire = mutableListOf<Write>()
        val lists = mutableListOf<String>()
        val listGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val titles = mutableMapOf<String, String>()
        override fun close() {}
        override suspend fun request(method: String, params: JsonObject) = error("unfenced call")
        override suspend fun requestAtEndpointDispatch(
            method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean,
        ): kotlinx.serialization.json.JsonElement {
            val owner = (params["profile"] as JsonPrimitive).content
            if ((params["action"] as JsonPrimitive).content == "list") {
                check(dispatch { lists += owner; true })
                listGates[owner]?.await()
                return rows(owner, titles[owner] ?: "Routine")
            }
            val write = Write(params).also { attempts += it }
            write.beforeWire.await()
            check(dispatch { wire += write; true })
            return Json.parseToJsonElement("""{"success":${write.reply.await()}}""")
        }
    }

    private fun TestScope.model(client: Client): BotsRoutinesViewModel {
        val endpoint = MutableStateFlow(0L)
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow<GatewayRpcClient?>(client), endpoint)
        return BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true), endpoint)
    }
    private fun target(model: BotsRoutinesViewModel): RoutineTarget =
        model.uiState.value.let { it.target(it.jobs.single())!! }

    @Test fun `ABA while accepted request waits before wire cannot duplicate and B stays independent`() = runTest {
        exerciseAba(holdReply = false)
    }

    @Test fun `ABA while accepted request waits for reply cannot duplicate and B stays independent`() = runTest {
        exerciseAba(holdReply = true)
    }

    private fun TestScope.exerciseAba(holdReply: Boolean) {
        val client = Client()
        val model = model(client)
        model.selectOwner("A")
        runCurrent()
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        val original = client.attempts.single()
        if (holdReply) {
            original.beforeWire.complete(Unit)
            runCurrent()
        }
        model.selectOwner("B")
        runCurrent()
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        assertEquals(listOf("A", "B"), client.attempts.map { it.owner })
        client.titles["A"] = "Returned A snapshot"
        model.selectOwner("A")
        runCurrent()
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        assertEquals("returning to A dispatched a duplicate", 1, client.attempts.count { it.owner == "A" })
        assertEquals(setOf("one"), model.uiState.value.pendingJobs)
        val before = model.uiState.value.jobs
        val reconcile = CompletableDeferred<Unit>()
        client.listGates["A"] = reconcile
        original.beforeWire.complete(Unit)
        original.reply.complete(false)
        runCurrent()
        assertEquals("stale rollback replaced the new selection's rows", before, model.uiState.value.jobs)
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
        assertFalse(model.uiState.value.actionFailed)
        assertEquals(listOf("A", "B", "A", "A"), client.lists)
        reconcile.complete(Unit)
        runCurrent()
        // B's retained operation must not be erased by A's cleanup.
        model.selectOwner("B")
        runCurrent()
        assertEquals(setOf("one"), model.uiState.value.pendingJobs)
        val b = client.attempts.single { it.owner == "B" }
        b.beforeWire.complete(Unit)
        b.reply.complete(true)
        runCurrent()
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
    }

    @Test fun `completion while B selected cleans A identity without disturbing B`() = runTest {
        val client = Client()
        val model = model(client)
        model.selectOwner("A")
        runCurrent()
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        val a = client.attempts.single()
        model.selectOwner("B")
        runCurrent()
        val bState = model.uiState.value
        a.beforeWire.complete(Unit)
        a.reply.complete(true)
        runCurrent()
        assertEquals(bState, model.uiState.value)
        model.selectOwner("A")
        runCurrent()
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        assertEquals(2, client.attempts.size)
    }

    @Test fun `queued selection rejection releases identity on return before any wire write`() = runTest {
        val client = Client()
        val model = model(client)
        model.selectOwner("A")
        runCurrent()
        model.act(target(model), RoutineAction.Pause)
        model.selectOwner("B")
        model.selectOwner("A")
        runCurrent()
        assertTrue(client.attempts.isEmpty())
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        assertEquals(1, client.attempts.size)
    }

    @Test fun `cancelled mutation releases identity and does not erase a later attempt`() = runTest {
        val replies = mutableListOf<CompletableDeferred<PluginHostResult>>()
        val host = object : PluginHost {
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                if ((params["action"] as JsonPrimitive).content == "list") return PluginHostResult.Success(rows("A"))
                return CompletableDeferred<PluginHostResult>().also { replies += it }.await()
            }
        }
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true))
        model.selectOwner("A")
        runCurrent()
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        replies.single().completeExceptionally(CancellationException("cancelled operation"))
        runCurrent()
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
        assertTrue(model.uiState.value.jobs.single().active)
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        assertEquals(2, replies.size)
        assertEquals(setOf("one"), model.uiState.value.pendingJobs)
    }

    @Test fun `A completion does not invalidate B list already on wire`() = runTest {
        val client = Client()
        val model = model(client)
        model.selectOwner("A")
        runCurrent()
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        val a = client.attempts.single()
        val bList = CompletableDeferred<Unit>()
        client.listGates["B"] = bList
        model.selectOwner("B")
        runCurrent()
        a.beforeWire.complete(Unit)
        a.reply.complete(true)
        runCurrent()
        bList.complete(Unit)
        runCurrent()
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
        assertEquals("B", model.uiState.value.owner)
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
        assertEquals(listOf("A", "B"), client.lists)
    }

    @Test fun `already cancelled scope still releases a synchronously reserved operation`() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(
            backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job]),
        )
        val host = object : PluginHost {
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                assertEquals(JsonPrimitive("list"), params["action"])
                return PluginHostResult.Success(rows("A"))
            }
        }
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope, MutableStateFlow(true))
        model.selectOwner("A")
        runCurrent()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
        assertTrue(model.uiState.value.jobs.single().active)
    }

    @Test fun `old endpoint cleanup cannot erase newer same owner job operation`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val replies = mutableListOf<CompletableDeferred<PluginHostResult>>()
        val host = object : PluginHost {
            override val endpointGeneration = endpoint
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                if (params["action"] == JsonPrimitive("list")) return PluginHostResult.Success(rows("A"))
                return CompletableDeferred<PluginHostResult>().also { replies += it }.await()
            }
        }
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true), endpoint)
        model.selectOwner("A")
        runCurrent()
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        endpoint.value++
        model.selectOwner("A")
        runCurrent()
        model.act(target(model), RoutineAction.Pause)
        runCurrent()
        val replacement = model.uiState.value
        replies[0].complete(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true}""")))
        runCurrent()
        assertEquals(replacement, model.uiState.value)
        assertEquals(setOf("one"), model.uiState.value.pendingJobs)
        replies[1].complete(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true}""")))
        runCurrent()
        assertTrue(model.uiState.value.pendingJobs.isEmpty())
    }

    companion object {
        private fun rows(owner: String, title: String = "Routine") = Json.parseToJsonElement(
            """{"success":true,"scoped":"$owner","jobs":[{"job_id":"one","name":"$title","enabled":true,"state":"scheduled"}]}""",
        )
    }
}
