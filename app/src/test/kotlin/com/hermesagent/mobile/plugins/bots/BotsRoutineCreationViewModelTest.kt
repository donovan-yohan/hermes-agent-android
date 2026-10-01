package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BotsRoutineCreationViewModelTest {
    private class Host(
        private val result: PluginHostResult,
        override val endpointGeneration: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) : PluginHost {
        val calls = mutableListOf<JsonObject>()
        val gate = CompletableDeferred<Unit>()
        var hold = false

        var listReply: PluginHostResult? = null
        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            check(method == "cron.manage")
            calls += params
            if ((params["action"] as? JsonPrimitive)?.content == "list") {
                return listReply ?: PluginHostResult.Success(buildJsonObject {
                    put("success", true); put("scoped", params.getValue("profile")); put("jobs", buildJsonArray { })
                })
            }
            if (hold) gate.await()
            return result
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun listResult(owner: String): PluginHostResult.Success = PluginHostResult.Success(
        buildJsonObject {
            put("success", true)
            put("scoped", owner)
            put("jobs", buildJsonArray { })
        },
    )

    private fun action(params: JsonObject): String = (params["action"] as JsonPrimitive).content

    private fun draft() = RoutineCreationDraft(title = "Nightly", instruction = "Do work")

    @Test fun `confirmed ready permits creation but stale ready revokes admission`() = runTest {
        val host = Host(listResult("ops")).apply {
            listReply = PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"scoped":"ops","jobs":[{"job_id":"existing","name":"Task"}]}"""))
        }
        val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true), host.endpointGeneration)
        vm.selectOwner("ops"); runCurrent()
        assertEquals(BotsRoutinesPhase.Ready, vm.uiState.value.phase); assertTrue(vm.uiState.value.canCreate)
        vm.openCreation(); vm.updateCreationDraft(draft())
        host.listReply = PluginHostResult.Refused(0, "private")
        vm.refreshNow(); vm.submitCreation(); runCurrent()
        assertEquals(BotsRoutinesPhase.Ready, vm.uiState.value.phase)
        assertFalse(vm.uiState.value.canCreate); assertTrue(host.calls.none { action(it) == "add" })
    }

    @Test fun `selection rejects explicit departed provenance even when names repeat`() = runTest {
        val host = Host(listResult("ops"))
        val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true), host.endpointGeneration)
        host.endpointGeneration.value = 1L
        vm.selectOwner("ops", originatingEndpoint = 0L); runCurrent()
        assertEquals(null, vm.uiState.value.owner)
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `creation refuses unscoped mismatched rejected malformed and failed lists`() = runTest {
        val bodies = listOf("""{"success":true,"jobs":[]}""", """{"success":true,"scoped":"other","jobs":[]}""",
            """{"success":false,"scoped":"ops","jobs":[]}""", """{"success":true,"scoped":"ops","jobs":42}""")
        val replies = bodies.map { PluginHostResult.Success(Json.parseToJsonElement(it)) } +
            listOf(PluginHostResult.Refused(0, "private"), PluginHostResult.UnavailableOnGateway)
        for (reply in replies) {
            val host = Host(listResult("ops")).apply { listReply = reply }
            val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true), host.endpointGeneration)
            vm.selectOwner("ops"); runCurrent(); vm.openCreation(); vm.updateCreationDraft(draft()); vm.submitCreation(); runCurrent()
            assertEquals("$reply", RoutineCreationPhase.Hidden, vm.uiState.value.creation.phase)
            assertTrue(host.calls.none { action(it) == "add" })
        }
    }

    @Test fun `scope and connection are rechecked at submit and before dispatch`() = runTest {
        for (queued in listOf(false, true)) {
            val host = Host(listResult("ops"))
            val connected = MutableStateFlow(true)
            val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, connected, host.endpointGeneration)
            vm.selectOwner("ops"); runCurrent(); vm.openCreation(); vm.updateCreationDraft(draft())
            assertEquals(RoutineCreationPhase.Editing, vm.uiState.value.creation.phase)
            if (queued) vm.submitCreation()
            connected.value = false // do not run the collector
            vm.submitCreation(); runCurrent()
            assertTrue(host.calls.none { action(it) == "add" })
        }
    }

    @Test fun `failed refresh revokes creation even when scoped empty was confirmed`() = runTest {
        val host = Host(listResult("ops"))
        val vm = BotsRoutinesViewModel(BotsPluginRepository(host), backgroundScope, MutableStateFlow(true), host.endpointGeneration)
        vm.selectOwner("ops"); runCurrent(); vm.openCreation(); vm.updateCreationDraft(draft())
        host.listReply = PluginHostResult.Refused(0, "private")
        vm.refreshNow(); vm.submitCreation(); runCurrent()
        assertTrue(host.calls.none { action(it) == "add" })
    }

    @Test
    fun `empty advanced schedule and control characters cannot enable Create`() {
        assertFalse(RoutineCreationUiState(RoutineCreationPhase.Editing,
            draft().copy(schedule = RoutineScheduleDraft(frequency = RoutineFrequency.Advanced))).canSubmit)
        assertFalse(RoutineCreationUiState(RoutineCreationPhase.Editing,
            draft().copy(title = "bad\u0000title")).canSubmit)
    }

    @Test
    fun `dismissing a confirmed creation permits a distinct new routine`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"new-1"}""")))
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        advanceUntilIdle()
        model.closeCreation()
        model.openCreation()
        assertEquals(RoutineCreationPhase.Editing, model.uiState.value.creation.phase)
        assertEquals("", model.uiState.value.creation.draft.title)
        assertEquals(1, host.calls.count { action(it) == "add" })
    }

    @Test
    fun `unconfirmed creation can be dismissed to inspect list but reopening cannot duplicate`() = runTest {
        val host = Host(PluginHostResult.Refused(0, "safe refusal"))
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        advanceUntilIdle()
        model.closeCreation()
        assertEquals(RoutineCreationPhase.Hidden, model.uiState.value.creation.phase)
        model.openCreation()
        assertEquals(RoutineCreationPhase.Unconfirmed, model.uiState.value.creation.phase)
        model.submitCreation()
        advanceUntilIdle()
        assertEquals(1, host.calls.count { action(it) == "add" })
    }

    @Test
    fun `rejected creation preserves editable fields for an explicit corrected retry`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":false}""")))
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        advanceUntilIdle()
        assertEquals(draft(), model.uiState.value.creation.draft)
        model.updateCreationDraft(draft().copy(title = "Corrected"))
        assertEquals(RoutineCreationPhase.Editing, model.uiState.value.creation.phase)
        assertTrue(model.uiState.value.creation.canSubmit)
    }

    @Test
    fun `submit sends one add and exposes pending then created identity`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"new-1"}""")))
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()

        model.openCreation()
        model.updateCreationDraft(draft())
        host.hold = true
        model.submitCreation()
        runCurrent()

        assertEquals(2, host.calls.size)
        assertEquals("add", action(host.calls.last()))
        assertEquals(RoutineCreationPhase.Pending, model.uiState.value.creation.phase)

        host.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(RoutineCreationPhase.Created, model.uiState.value.creation.phase)
        assertEquals("new-1", model.uiState.value.creation.jobId)
    }

    @Test
    fun `once submits explicit one shot schedule`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"unused"}""")))
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()

        model.openCreation()
        model.updateCreationDraft(draft().copy(schedule = RoutineScheduleDraft(frequency = RoutineFrequency.Once)))
        model.submitCreation()

        advanceUntilIdle()
        assertEquals(RoutineCreationPhase.Created, model.uiState.value.creation.phase)
        val add = host.calls.single { action(it) == "add" }
        assertEquals(JsonPrimitive("in 30m"), add["schedule"])
    }

    @Test
    fun `saved registration failure is safe and retains exact job id`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":false,"job_id":"saved-1","job_saved":true,"scheduler_registered":false,"retry_create":false}""")))
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()

        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        advanceUntilIdle()

        assertEquals(RoutineCreationPhase.SavedRegistrationFailed, model.uiState.value.creation.phase)
        assertEquals("saved-1", model.uiState.value.creation.jobId)
        assertFalse(model.uiState.value.creation.canSubmit)
    }

    @Test
    fun `held creation completion does not repaint B`() = runTest {
        val reply = CompletableDeferred<PluginHostResult>()
        val host = object : PluginHost {
            override val endpointGeneration = MutableStateFlow(0L)
            val calls = mutableListOf<JsonObject>()
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                calls += params
                if (action(params) == "add") return reply.await()
                return listResult((params["profile"] as JsonPrimitive).content)
            }
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        runCurrent()
        model.selectOwner("research")
        runCurrent()
        reply.complete(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"ops-1"}""")))
        advanceUntilIdle()

        assertEquals("research", model.uiState.value.owner)
        assertEquals(RoutineCreationPhase.Hidden, model.uiState.value.creation.phase)
    }

    @Test
    fun `cancellation settles the admitted creation as unconfirmed and releases the token`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"unused"}""")))
        val worker = scope()
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), worker, MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        host.hold = true
        model.submitCreation()
        runCurrent()
        assertEquals(RoutineCreationPhase.Pending, model.uiState.value.creation.phase)

        worker.cancel()
        runCurrent()

        assertEquals(RoutineCreationPhase.Unconfirmed, model.uiState.value.creation.phase)
        assertFalse(model.uiState.value.creation.canSubmit)
        assertEquals(2, host.calls.size)
    }

    @Test
    fun `reopening A during a held A-B-A operation stays pending and cannot duplicate`() = runTest {
        val reply = CompletableDeferred<PluginHostResult>()
        val host = object : PluginHost {
            override val endpointGeneration = MutableStateFlow(0L)
            val calls = mutableListOf<JsonObject>()
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                calls += params
                if (action(params) == "add") return reply.await()
                return listResult((params["profile"] as JsonPrimitive).content)
            }
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        val worker = scope()
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), worker, MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        runCurrent()

        model.selectOwner("research")
        runCurrent()
        model.selectOwner("ops")
        runCurrent()
        model.openCreation()
        model.submitCreation()

        assertEquals(RoutineCreationPhase.Pending, model.uiState.value.creation.phase)
        assertEquals(1, host.calls.count { action(it) == "add" })

        reply.complete(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"old-owner-1"}""")))
        advanceUntilIdle()
        assertEquals(RoutineCreationPhase.Created, model.uiState.value.creation.phase)
        assertEquals("old-owner-1", model.uiState.value.creation.jobId)
    }

    @Test
    fun `navigation before wire rejects queued creation without dispatch`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"unused"}""")))
        val worker = scope()
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), worker, MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        model.selectOwner("research")
        runCurrent()

        assertEquals(2, host.calls.size)
        assertEquals(0, host.calls.count { action(it) == "add" })
        assertEquals(RoutineCreationPhase.Hidden, model.uiState.value.creation.phase)

        model.selectOwner("ops")
        runCurrent()
        model.openCreation()
        assertEquals(RoutineCreationPhase.Unconfirmed, model.uiState.value.creation.phase)
        assertEquals(0, host.calls.count { action(it) == "add" })
    }

    @Test
    fun `endpoint replacement isolates held old creation from the new owner operation`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val oldReply = CompletableDeferred<PluginHostResult>()
        val newReply = CompletableDeferred<PluginHostResult>()
        val host = object : PluginHost {
            override val endpointGeneration = endpoint
            val calls = mutableListOf<JsonObject>()
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                calls += params
                if (action(params) != "add") return listResult((params["profile"] as JsonPrimitive).content)
                return if (endpoint.value == 0L) oldReply.await() else newReply.await()
            }
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        val worker = scope()
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), worker, MutableStateFlow(true), endpoint)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        runCurrent()
        assertEquals(1, host.calls.count { action(it) == "add" })

        endpoint.value = 1L
        model.selectOwner("ops")
        runCurrent()
        model.openCreation()
        model.updateCreationDraft(draft().copy(title = "Replacement"))
        model.submitCreation()
        runCurrent()
        assertEquals(2, host.calls.count { action(it) == "add" })
        assertEquals(RoutineCreationPhase.Pending, model.uiState.value.creation.phase)

        oldReply.complete(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"old"}""")))
        runCurrent()
        assertEquals(RoutineCreationPhase.Pending, model.uiState.value.creation.phase)

        newReply.complete(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"new"}""")))
        advanceUntilIdle()
        assertEquals(RoutineCreationPhase.Created, model.uiState.value.creation.phase)
        assertEquals("new", model.uiState.value.creation.jobId)
    }

    @Test
    fun `already cancelled scope does not leave creation pending`() = runTest {
        val host = Host(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"unused"}""")))
        val worker = scope()
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), worker, MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        worker.cancel()

        model.submitCreation()
        runCurrent()

        assertEquals(RoutineCreationPhase.Unconfirmed, model.uiState.value.creation.phase)
        assertEquals(1, host.calls.size)
    }

    @Test
    fun `accepted creation survives A-B-A and reopens with its retained outcome`() = runTest {
        val reply = CompletableDeferred<PluginHostResult>()
        val host = object : PluginHost {
            override val endpointGeneration = MutableStateFlow(0L)
            val calls = mutableListOf<JsonObject>()
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                calls += params
                if (action(params) == "add") return reply.await()
                return listResult((params["profile"] as JsonPrimitive).content)
            }
            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        val model = BotsRoutinesViewModel(BotsPluginRepository(host), scope(), MutableStateFlow(true), host.endpointGeneration)
        model.selectOwner("ops")
        advanceUntilIdle()
        model.openCreation()
        model.updateCreationDraft(draft())
        model.submitCreation()
        runCurrent()
        model.selectOwner("research")
        runCurrent()
        assertEquals("research", model.uiState.value.owner)
        assertEquals(RoutineCreationPhase.Hidden, model.uiState.value.creation.phase)
        reply.complete(PluginHostResult.Success(Json.parseToJsonElement("""{"success":true,"job_id":"old-owner-1"}""")))
        advanceUntilIdle()

        assertEquals("research", model.uiState.value.owner)
        assertEquals(RoutineCreationPhase.Hidden, model.uiState.value.creation.phase)
        assertTrue(host.calls.any { action(it) == "add" })
    }
}
