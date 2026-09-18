package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BotsRoutineActionsTest {
    private class Call(val params: JsonObject) {
        val answer = CompletableDeferred<PluginHostResult>()
        val action get() = (params["action"] as JsonPrimitive).content
        fun reply(body: String) { answer.complete(success(body)) }
    }

    private class Host : PluginHost {
        override val endpointGeneration = MutableStateFlow(0L)
        val calls = mutableListOf<Call>()
        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            assertEquals("cron.manage", method)
            return Call(params).also { calls += it }.answer.await()
        }
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private class Fixture(scope: CoroutineScope) {
        val host = Host()
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope, MutableStateFlow(true), host.endpointGeneration)
        fun target(id: String = "one") = model.uiState.value.target(model.uiState.value.jobs.single { it.id == id })!!
    }

    private fun TestScope.loaded(body: String = rows()): Fixture {
        val f = Fixture(backgroundScope)
        f.model.selectOwner("Ops-Team")
        runCurrent()
        f.host.calls.single().reply(body)
        runCurrent()
        return f
    }

    @Test fun `all mutations send exact raw owner and name not job_id`() = runTest {
        RoutineAction.entries.forEach { action ->
            val host = Host()
            val target = RoutineTarget("Ops-Team", 0, 7, "one")
            val result = async { BotsPluginRepository(host).mutateRoutine(target, action) }
            runCurrent()
            assertEquals(Json.parseToJsonElement("""{"action":"${action.wire}","name":"one","profile":"Ops-Team"}"""), host.calls.single().params)
            host.calls.single().reply("""{"success":true}""")
            assertTrue(result.await())
        }
    }

    @Test fun `only literal true acknowledges a mutation`() = runTest {
        listOf("{}", "null", "[]", "{\"success\":false}", "{\"success\":\"true\"}", "{\"success\":1}").forEach { body ->
            val host = Host()
            val result = async {
                BotsPluginRepository(host).mutateRoutine(RoutineTarget("Ops-Team", 0, 1, "one"), RoutineAction.Pause)
            }
            runCurrent()
            host.calls.single().reply(body)
            assertFalse(body, result.await())
        }
    }

    @Test fun `pause resume and delete reconcile with disabled rows included`() = runTest {
        val f = loaded()
        for (action in RoutineAction.entries) {
            f.model.act(f.target(), action)
            runCurrent()
            val mutation = f.host.calls.last()
            assertEquals(action.wire, mutation.action)
            assertEquals(setOf("one"), f.model.uiState.value.pendingJobs)
            if (action != RoutineAction.Remove) assertEquals(action == RoutineAction.Resume, f.model.uiState.value.jobs.first().active)
            mutation.reply("""{"success":true}""")
            runCurrent()
            val read = f.host.calls.last()
            assertEquals("list", read.action)
            assertEquals(JsonPrimitive(true), read.params["include_disabled"])
            read.reply(if (action == RoutineAction.Remove) rows(includeOne = false) else rows(active = action == RoutineAction.Resume))
            runCurrent()
        }
        assertEquals(listOf("two"), f.model.uiState.value.jobs.map { it.id })
    }

    @Test fun `duplicate clicks are suppressed synchronously and refusal rolls back only its row`() = runTest {
        val f = loaded()
        val target = f.target()
        f.model.act(target, RoutineAction.Pause)
        f.model.act(target, RoutineAction.Pause)
        f.model.act(f.target("two"), RoutineAction.Pause)
        runCurrent()
        assertEquals(listOf("list", "pause", "pause"), f.host.calls.map { it.action })
        f.host.calls[1].reply("""{"success":false,"error":"secret backend prose"}""")
        runCurrent()
        assertTrue(f.model.uiState.value.jobs[0].active)
        assertFalse(f.model.uiState.value.jobs[1].active)
        assertEquals(setOf("two"), f.model.uiState.value.pendingJobs)
        assertTrue(f.model.uiState.value.actionFailed)
    }

    @Test fun `old list cannot undo pending pause or resurrect acknowledged deletion even when reconciliation fails`() = runTest {
        val f = loaded()
        f.model.refresh()
        runCurrent()
        val oldList = f.host.calls.last()
        f.model.act(f.target(), RoutineAction.Remove)
        runCurrent()
        f.host.calls.last().reply("""{"success":true}""")
        runCurrent()
        assertEquals(listOf("two"), f.model.uiState.value.jobs.map { it.id })
        oldList.reply(rows())
        runCurrent()
        assertEquals(listOf("two"), f.model.uiState.value.jobs.map { it.id })
        f.host.calls.last().answer.complete(PluginHostResult.Refused(0, "backend prose"))
        runCurrent()
        assertEquals(listOf("two"), f.model.uiState.value.jobs.map { it.id })
        assertTrue(f.model.uiState.value.stale)
        assertEquals(1, f.host.calls.count { it.action == "remove" })
    }

    @Test fun `list started during pending action cannot overwrite optimistic state`() = runTest {
        val f = loaded()
        f.model.act(f.target(), RoutineAction.Pause)
        runCurrent()
        f.model.refresh()
        runCurrent()
        f.host.calls.last().reply(rows())
        runCurrent()
        assertFalse(f.model.uiState.value.jobs.first().active)
        assertEquals(setOf("one"), f.model.uiState.value.pendingJobs)
    }

    @Test fun `late rollback and old callback cannot repaint owner ABA selection`() = runTest {
        val f = loaded()
        val old = f.target()
        f.model.act(old, RoutineAction.Pause)
        runCurrent()
        val mutation = f.host.calls.last()
        f.model.selectOwner("research")
        f.model.selectOwner("Ops-Team")
        runCurrent()
        f.host.calls.last().reply(rows(active = false))
        runCurrent()
        // Coalesced reselection read is authoritative too.
        if (!f.host.calls.last().answer.isCompleted) {
            f.host.calls.last().reply(rows(active = false))
            runCurrent()
        }
        mutation.reply("""{"success":false}""")
        runCurrent()
        assertFalse(f.model.uiState.value.jobs.first().active)
        assertFalse(f.model.uiState.value.actionFailed)
        val count = f.host.calls.size
        f.model.act(old, RoutineAction.Remove)
        runCurrent()
        assertEquals(count, f.host.calls.size)
    }

    @Test fun `queued action and stale callback cannot dispatch after endpoint switch`() = runTest {
        val f = loaded()
        val old = f.target()
        f.model.act(old, RoutineAction.Pause)
        f.host.endpointGeneration.value++
        runCurrent()
        assertEquals(1, f.host.calls.size)
        assertNull(f.model.uiState.value.owner)
        f.model.act(old, RoutineAction.Remove)
        runCurrent()
        assertEquals(1, f.host.calls.size)
    }

    @Test fun `late endpoint reply cannot repaint newly selected same named bot`() = runTest {
        val f = loaded()
        f.model.act(f.target(), RoutineAction.Pause)
        runCurrent()
        val mutation = f.host.calls.last()
        f.host.endpointGeneration.value++
        f.model.selectOwner("Ops-Team")
        runCurrent()
        f.host.calls.last().reply(rows(active = false))
        runCurrent()
        mutation.reply("""{"success":false}""")
        runCurrent()
        assertFalse(f.model.uiState.value.jobs.first().active)
        assertFalse(f.model.uiState.value.actionFailed)
        assertEquals(1L, f.model.uiState.value.endpointGeneration)
    }

    @Test fun `terminal toggle unknown scope stale scope and legacy rows refuse before wire`() = runTest {
        for (body in listOf(rows(state = "completed"), rows(state = "unexpected"), rows().replace("\"scoped\":\"Ops-Team\",", ""), rows(legacy = true))) {
            val f = loaded(body)
            val count = f.host.calls.size
            f.model.act(f.target(), RoutineAction.Resume)
            f.model.act(f.target(), RoutineAction.Pause)
            runCurrent()
            assertEquals(count, f.host.calls.size)
        }
        val f = loaded()
        f.model.refresh()
        runCurrent()
        f.host.calls.last().answer.complete(PluginHostResult.Refused(0, "private"))
        runCurrent()
        f.model.act(f.target(), RoutineAction.Remove)
        runCurrent()
        assertEquals(2, f.host.calls.size)
    }

    @Test fun `production host fences mutation at actual wire handoff`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val beforeWire = CompletableDeferred<Unit>()
        var writes = 0
        val client = object : com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient {
            override val events = kotlinx.coroutines.flow.emptyFlow<com.hermesagent.mobile.data.gateway.GatewayEvent>()
            override fun close() {}
            override suspend fun request(method: String, params: JsonObject): kotlinx.serialization.json.JsonElement =
                error("unfenced request")
            override suspend fun requestAtEndpointDispatch(
                method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean,
            ): kotlinx.serialization.json.JsonElement {
                val write = (params["action"] as JsonPrimitive).content != "list"
                if (write) beforeWire.await()
                if (!dispatch { if (write) writes++; true }) {
                    throw com.hermesagent.mobile.data.gateway.GatewayRpcException("stale lease")
                }
                return Json.parseToJsonElement(if (write) """{"success":true}""" else rows())
            }
        }
        val clients = MutableStateFlow<com.hermesagent.mobile.data.gateway.GatewayRpcClient?>(client)
        val host = com.hermesagent.mobile.plugins.GatewayPluginHost(backgroundScope, clients, endpoint)
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true), endpoint)
        model.selectOwner("Ops-Team")
        runCurrent()
        val state = model.uiState.value
        assertEquals(BotsRoutinesPhase.Ready, state.phase)
        model.act(state.target(state.jobs.first())!!, RoutineAction.Pause)
        runCurrent()
        endpoint.value++
        beforeWire.complete(Unit)
        runCurrent()
        assertEquals("a mutation escaped the wire fence", 0, writes)
        assertNull(model.uiState.value.owner)
    }

    companion object {
        private fun success(body: String) = PluginHostResult.Success(Json.parseToJsonElement(body))
        private fun rows(
            active: Boolean = true,
            includeOne: Boolean = true,
            state: String = if (active) "scheduled" else "paused",
            legacy: Boolean = false,
        ): String = buildJsonObject {
            put("success", true)
            put("scoped", "Ops-Team")
            put("jobs", buildJsonArray {
                if (includeOne) add(buildJsonObject {
                    put("job_id", "one")
                    put("name", "[bot:Ops-Team] Morning")
                    put("enabled", active)
                    put("state", state)
                    if (legacy) put("prompt_preview", "You are running the scheduled routine \"legacy\"")
                })
                add(buildJsonObject {
                    put("job_id", "two")
                    put("name", "Nightly")
                    put("enabled", true)
                    put("state", "scheduled")
                })
            })
        }.toString()
    }
}
