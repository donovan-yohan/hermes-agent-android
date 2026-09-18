package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Routines surface's state: owner scope, the endpoint and owner fences,
 * stale-over-last-good, and retry.
 *
 * Everything is driven on virtual time through the real ViewModel and the real
 * repository, so what is asserted is the state a surface would render rather
 * than the data structure behind it.
 */
class BotsRoutinesViewModelTest {

    /**
     * A host that answers per call from a script and records every request.
     *
     * `onCall` runs between recording a call and answering it, which is what
     * lets an endpoint switch or a bot switch land mid-read.
     */
    private class ScriptedHost(
        override val endpointGeneration: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) : PluginHost {
        private val answers = mutableMapOf<String, ArrayDeque<PluginHostResult>>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var onCall: ((String, JsonObject) -> Unit)? = null

        fun answer(method: String, vararg results: PluginHostResult) {
            answers[method] = ArrayDeque(results.toList())
        }

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            calls += method to params
            onCall?.invoke(method, params)
            return answers[method]?.removeFirstOrNull() ?: error("no scripted answer for $method")
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    /** A host whose read stays in flight until the test releases it. */
    private class GatedHost(
        private val gate: CompletableDeferred<Unit>,
        var result: PluginHostResult,
    ) : PluginHost {
        var reads = 0

        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            reads += 1
            gate.await()
            return result
        }

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    /**
     * A scope on this test's own scheduler, deliberately not `backgroundScope`:
     * `advanceUntilIdle` never runs background-scope work.
     */
    private fun TestScope.drivenScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

    private fun profileOf(params: JsonObject): String? =
        (params["profile"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun jobs(vararg names: String): String {
        val rows = names.mapIndexed { index, name ->
            """{"job_id":"j$index","name":"$name","schedule":"every 1h","enabled":true,"state":"scheduled"}"""
        }
        return """{"success":true,"count":${rows.size},"jobs":[${rows.joinToString(",")}]}"""
    }

    private fun scopedJobs(scope: String, vararg names: String): String {
        val rows = names.mapIndexed { index, name ->
            """{"job_id":"j$index","name":"$name","schedule":"every 1h","enabled":true,"state":"scheduled"}"""
        }
        return """{"success":true,"count":${rows.size},"scoped":"$scope","jobs":[${rows.joinToString(",")}]}"""
    }

    private fun success(body: String) = PluginHostResult.Success(Json.parseToJsonElement(body))

    private fun viewModel(
        scope: CoroutineScope,
        host: PluginHost,
        connected: MutableStateFlow<Boolean> = MutableStateFlow(true),
        endpoint: MutableStateFlow<Long> = MutableStateFlow(0L),
    ) = BotsRoutinesViewModel(
        repository = BotsPluginRepository(host),
        scope = scope,
        connected = connected,
        endpointGeneration = endpoint,
    )

    // ── the request and the owner scope ───────────────────────────────────────

    @Test
    fun `selecting a bot reads its store once with the paused rows included`() = runTest {
        val host = ScriptedHost().apply { answer("cron.manage", success(jobs("[bot:ops] Morning"))) }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(1, host.calls.size)
        assertEquals("cron.manage", host.calls.single().first)
        assertEquals("list", (host.calls.single().second["action"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("ops", profileOf(host.calls.single().second))
        assertEquals(
            BotsRoutinesPhase.Ready,
            model.uiState.value.phase,
        )
        assertEquals("ops", model.uiState.value.owner)
        assertEquals(listOf("Morning"), model.uiState.value.jobs.map { it.title })
    }

    @Test
    fun `no read is issued before a bot is chosen`() = runTest {
        val host = ScriptedHost().apply { answer("cron.manage", success(jobs())) }
        val model = viewModel(drivenScope(), host)

        model.refresh()
        model.surfaceResumed()
        advanceUntilIdle()

        assertEquals(0, host.calls.size)
        assertEquals(BotsRoutinesPhase.Loading, model.uiState.value.phase)
        assertNull(model.uiState.value.owner)
    }

    @Test
    fun `re-selecting the bot already on screen refreshes instead of blanking`() = runTest {
        val host = ScriptedHost().apply {
            answer("cron.manage", success(jobs("[bot:ops] Morning")), success(jobs("[bot:ops] Nightly")))
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()
        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(2, host.calls.size)
        // The rows of the first answer are still there until the second lands:
        // a re-entry must not flash an empty screen over a list that is about to
        // be replaced.
        assertEquals(listOf("Nightly"), model.uiState.value.jobs.map { it.title })
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
    }

    @Test
    fun `switching bot resets the surface and asks the new store`() = runTest {
        val host = ScriptedHost().apply {
            answer("cron.manage", success(jobs("[bot:ops] Morning")), success(jobs("[bot:research] Digest")))
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()
        model.selectOwner("research")

        // The switch is synchronous: the previous bot's rows must not be on
        // screen under the new bot's name even for one frame.
        assertEquals("research", model.uiState.value.owner)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.jobs)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.all)
        assertEquals(BotsRoutinesPhase.Loading, model.uiState.value.phase)

        advanceUntilIdle()
        assertEquals(listOf("Digest"), model.uiState.value.jobs.map { it.title })
        assertEquals(listOf("ops", "research"), host.calls.map { profileOf(it.second) })
    }

    @Test
    fun `a late answer for a bot the person has left is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = GatedHost(gate, success(jobs("[bot:ops] Morning")))
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        runCurrent()
        assertEquals(1, host.reads)

        // The person switches bots while the first read is still on the wire.
        model.selectOwner("research")
        runCurrent()
        host.result = success(jobs("[bot:research] Digest"))
        gate.complete(Unit)
        advanceUntilIdle()

        // The first answer describes a bot that is no longer selected, so it
        // must not paint. Research's own read is what fills the surface.
        assertEquals("research", model.uiState.value.owner)
        assertEquals(listOf("Digest"), model.uiState.value.jobs.map { it.title })
    }

    // ── overlapping reads ─────────────────────────────────────────────────────

    @Test
    fun `a refresh during a read is remembered and runs straight after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = GatedHost(gate, success(jobs("[bot:ops] Morning")))
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        runCurrent()
        // The connection edge landing mid-read is the case this exists for:
        // dropping it would leave the surface in the state the edge leaves.
        model.refresh()
        model.refresh()
        runCurrent()
        assertEquals(1, host.reads)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, host.reads)
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
    }

    @Test
    fun `reads are serialized, never overlapped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val host = GatedHost(gate, success(jobs("[bot:ops] Morning")))
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        runCurrent()
        repeat(5) { model.surfaceResumed() }
        runCurrent()

        assertEquals(1, host.reads)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, host.reads)
    }

    // ── the states ────────────────────────────────────────────────────────────

    @Test
    fun `an empty store is the empty state, not a failure`() = runTest {
        val host = ScriptedHost().apply { answer("cron.manage", success("""{"success":true,"count":0,"jobs":[]}""")) }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(BotsRoutinesPhase.Empty, model.uiState.value.phase)
        assertFalse(model.uiState.value.stale)
        assertNull(model.uiState.value.filterHint)
    }

    @Test
    fun `an empty view over a store with another bot's jobs explains itself`() = runTest {
        // Desktop's `routineFilterHint`: jobs exist on the profile but none are
        // tagged for this bot, and the generic empty state would deny they exist.
        val host = ScriptedHost().apply {
            answer("cron.manage", success("""{"success":true,"count":1,"jobs":[{"job_id":"j","name":"[bot:research] Digest"}]}"""))
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(BotsRoutinesPhase.Empty, model.uiState.value.phase)
        assertEquals(listOf("research"), model.uiState.value.all.mapNotNull { it.taggedBot })
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.jobs)
        assertEquals(BotsRoutinesCopy.FILTER_HINT, model.uiState.value.filterHint)
    }

    @Test
    fun `a failed first read is the error state with a retry`() = runTest {
        val host = ScriptedHost().apply {
            answer(
                "cron.manage",
                PluginHostResult.Refused(5023, "Hermes refused that Gateway request."),
                success(jobs("[bot:ops] Morning")),
            )
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(BotsRoutinesPhase.Refused, model.uiState.value.phase)
        assertEquals("Hermes refused that Gateway request.", model.uiState.value.safeMessage)

        // Retry re-issues the identical request.
        model.refresh()
        advanceUntilIdle()
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
        assertNull(model.uiState.value.safeMessage)
        assertEquals(listOf("ops", "ops"), host.calls.map { profileOf(it.second) })
        assertEquals(
            listOf("list", "list"),
            host.calls.map { (it.second["action"] as kotlinx.serialization.json.JsonPrimitive).content },
        )
    }

    @Test
    fun `a failed refresh keeps the last list and says it is old`() = runTest {
        val host = ScriptedHost().apply {
            answer(
                "cron.manage",
                success(jobs("[bot:ops] Morning")),
                PluginHostResult.Refused(5023, "Hermes refused that Gateway request."),
            )
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()

        assertTrue(model.uiState.value.stale)
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
        assertEquals(listOf("Morning"), model.uiState.value.jobs.map { it.title })
        assertEquals("Hermes refused that Gateway request.", model.uiState.value.safeMessage)
    }

    @Test
    fun `an empty answer after a populated one is not stale-over-last-good`() = runTest {
        // A successful refresh that emptied the store must not resurrect the
        // previous list — Desktop pins this (`cron-jobs-view.test.ts:80-85`).
        val host = ScriptedHost().apply {
            answer("cron.manage", success(jobs("[bot:ops] Morning")), success("""{"success":true,"count":0,"jobs":[]}"""))
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()
        model.refresh()
        advanceUntilIdle()

        assertEquals(BotsRoutinesPhase.Empty, model.uiState.value.phase)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.jobs)
        assertFalse(model.uiState.value.stale)
    }

    @Test
    fun `a failure with no connection is the waiting state, not a failure`() = runTest {
        val connected = MutableStateFlow(false)
        val host = ScriptedHost().apply {
            answer("cron.manage", PluginHostResult.Refused(0, "Hermes could not reach the Gateway."))
        }
        val model = viewModel(drivenScope(), host, connected = connected)

        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(BotsRoutinesPhase.Loading, model.uiState.value.phase)
        assertNull(model.uiState.value.safeMessage)
        assertTrue(model.uiState.value.waits)
    }

    @Test
    fun `an unknown method is the gateway-predates state`() = runTest {
        val host = ScriptedHost().apply { answer("cron.manage", PluginHostResult.UnavailableOnGateway) }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(BotsRoutinesPhase.UnavailableOnGateway, model.uiState.value.phase)
    }

    @Test
    fun `a rejected operation is its own state inside a good envelope`() = runTest {
        val host = ScriptedHost().apply {
            answer("cron.manage", success("""{"success":false,"error":"backend prose","jobs":[]}"""))
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(BotsRoutinesPhase.Rejected, model.uiState.value.phase)
        // The backend's own text never reaches the surface.
        assertNull(model.uiState.value.safeMessage)
        assertTrue(
            model.uiState.value.let { it.safeMessage == null && it.jobs.isEmpty() },
        )
    }

    @Test
    fun `a mismatched scope shows neither another profile's jobs nor its own rows`() = runTest {
        val host = ScriptedHost().apply {
            answer(
                "cron.manage",
                success(
                    scopedJobs("research", "ordinary research cronjob").let {
                        it.replace("\"jobs\":[", "\"jobs\":[{\"job_id\":\"r0\",\"name\":\"ordinary research cronjob\"},")
                    },
                ),
            )
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()

        assertEquals(BotsRoutinesPhase.MismatchedScope, model.uiState.value.phase)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.jobs)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.all)
        assertFalse(model.uiState.value.stale)
    }

    @Test
    fun `the mismatched-scope state survives a presentation-only change`() = runTest {
        // A resume after a mismatch must not fall back to "no routines yet":
        // the state is terminal until the Gateway is asked again, and asking
        // again is what the retry does.
        val host = ScriptedHost().apply {
            answer(
                "cron.manage",
                success("""{"success":true,"scoped":"research","jobs":[{"job_id":"r","name":"ordinary cronjob"}]}"""),
            )
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(BotsRoutinesPhase.MismatchedScope, model.uiState.value.phase)

        advanceUntilIdle()
        assertEquals(BotsRoutinesPhase.MismatchedScope, model.uiState.value.phase)
    }

    // ── the connection edge ───────────────────────────────────────────────────

    @Test
    fun `the connection edge triggers the read a cold start could not make`() = runTest {
        val connected = MutableStateFlow(false)
        val host = ScriptedHost().apply {
            answer(
                "cron.manage",
                // The door itself refuses while no client exists
                // (`GatewayPluginHost.request`), which is why a cold start's
                // read lands in a refusal it could never leave on its own.
                PluginHostResult.Refused(0, "Hermes could not reach the Gateway."),
                success(jobs("[bot:ops] Morning")),
            )
        }
        val model = viewModel(drivenScope(), host, connected = connected)

        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(1, host.calls.size)
        assertEquals(BotsRoutinesPhase.Loading, model.uiState.value.phase)
        assertTrue(model.uiState.value.waits)

        connected.value = true
        advanceUntilIdle()

        assertEquals(2, host.calls.size)
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
        assertEquals(listOf("Morning"), model.uiState.value.jobs.map { it.title })
    }

    @Test
    fun `losing the connection with rows held keeps them and reports it`() = runTest {
        val connected = MutableStateFlow(true)
        val host = ScriptedHost().apply {
            answer(
                "cron.manage",
                success(jobs("[bot:ops] Morning")),
                PluginHostResult.Refused(0, "Hermes could not reach the Gateway."),
            )
        }
        val model = viewModel(drivenScope(), host, connected = connected)

        model.selectOwner("ops")
        advanceUntilIdle()

        connected.value = false
        advanceUntilIdle()

        // Rows are held and the surface says so rather than blanking: a
        // reconnect redial does not invalidate which machine those rows came
        // from, and that is the one thing that would.
        assertEquals(listOf("Morning"), model.uiState.value.jobs.map { it.title })
        assertFalse(model.uiState.value.connectionUp)
    }

    // ── the endpoint fence ────────────────────────────────────────────────────

    @Test
    fun `an endpoint switch drops the rows the previous gateway served`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val host = ScriptedHost(endpoint).apply {
            answer(
                "cron.manage",
                success(jobs("[bot:ops] Morning")),
                // The replacement endpoint's answer arrives on the same host.
                success(jobs("[bot:ops] Beta Morning")),
            )
        }
        val model = viewModel(drivenScope(), host, endpoint = endpoint)

        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(listOf("Morning"), model.uiState.value.jobs.map { it.title })

        // A leave: the app forgets what it told the plugin and bumps the
        // generation. The rows are the previous machine's.
        endpoint.value = 1L
        advanceUntilIdle()

        // The selected bot is dropped with the rows: a profile name read from
        // the machine this device left is not the new one's to read against.
        assertNull(model.uiState.value.owner)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.jobs)
        assertEquals(BotsRoutinesPhase.Loading, model.uiState.value.phase)
        assertEquals(0L, model.uiState.value.endpointGeneration)
    }

    @Test
    fun `an answer that lands after an endpoint switch is discarded`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val host = ScriptedHost(endpoint).apply {
            answer(
                "cron.manage",
                success(jobs("[bot:ops] Alpha Morning")),
                success(jobs("[bot:ops] Beta Morning")),
            )
            onCall = { _, _ -> endpoint.value = 1L }
        }
        val model = viewModel(drivenScope(), host, endpoint = endpoint)

        model.selectOwner("ops")
        advanceUntilIdle()

        // The switch landed while the read was on the wire. That answer
        // describes a machine this device has left, so nothing of it may paint.
        assertNull(model.uiState.value.owner)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.jobs)
        assertEquals(0L, model.uiState.value.endpointGeneration)
    }

    @Test
    fun `a read bound to a stale endpoint never reaches the replacement gateway`() = runTest {
        val endpoint = MutableStateFlow(1L)
        val host = ScriptedHost(endpoint).apply { answer("cron.manage", success(jobs("[bot:ops] Beta"))) }
        val model = viewModel(drivenScope(), host, endpoint = endpoint)

        // A model built after the switch reads the new endpoint, so it is the
        // *fence* under test: a caller that selected a bot while the generation
        // was 0 must not send at 1.
        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(1, host.calls.size)
        assertEquals(listOf("Beta"), model.uiState.value.jobs.map { it.title })
    }

    // ── what is never persisted ───────────────────────────────────────────────

    @Test
    fun `a failed refresh keeps a scoped store of untagged jobs visible`() = runTest {
        // The regression this guards: a successfully scoped list of *untagged*
        // jobs is this bot's own store, and re-rendering it after a failed
        // refresh with the scope receipt forgotten would run the legacy tag
        // filter over it, hide every row and read as an empty bot. The stale
        // list must survive intact.
        val host = ScriptedHost().apply {
            answer(
                "cron.manage",
                success(
                    """{"success":true,"count":1,"scoped":"ops","jobs":[
                        {"job_id":"j0","name":"ordinary profile cronjob","schedule":"every 1h",
                         "enabled":true,"state":"scheduled"}]}""",
                ),
                PluginHostResult.Refused(5023, "Hermes refused that Gateway request."),
            )
        }
        val model = viewModel(drivenScope(), host)

        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(listOf("ordinary profile cronjob"), model.uiState.value.jobs.map { it.title })

        model.refresh()
        advanceUntilIdle()

        assertTrue(model.uiState.value.stale)
        assertEquals(BotsRoutinesPhase.Ready, model.uiState.value.phase)
        // The untagged job is still shown, the scope receipt still says why, and
        // no filter hint claims the store is empty for this bot.
        assertEquals(listOf("ordinary profile cronjob"), model.uiState.value.jobs.map { it.title })
        assertEquals("ops", model.uiState.value.scoped)
        assertNull(model.uiState.value.filterHint)
    }

    @Test
    fun `a refresh after the endpoint moved sends nothing until a bot is reselected`() = runTest {
        // The regression this guards: capturing the selected owner *before*
        // applying the endpoint boundary would carry a profile name read from
        // the machine this device has left onto the replacement Gateway. The
        // boundary is applied first, so there is no owner left to read.
        val endpoint = MutableStateFlow(0L)
        val host = ScriptedHost(endpoint).apply {
            answer("cron.manage", success(jobs("[bot:ops] Alpha Morning")), success(jobs("[bot:ops] Beta Morning")))
        }
        val model = viewModel(drivenScope(), host, endpoint = endpoint)

        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(1, host.calls.size)

        // The app leaves the endpoint. No dispatcher turn is granted before the
        // refresh: this is the synchronous path, exactly as a resume or a
        // retry would take it.
        endpoint.value = 1L
        model.refresh()
        model.surfaceResumed()
        advanceUntilIdle()

        // Nothing was sent to the replacement Gateway, and ownership is clear.
        assertEquals(1, host.calls.size)
        assertNull(model.uiState.value.owner)
        assertEquals(BotsRoutinesPhase.Loading, model.uiState.value.phase)

        // Only an explicit reselection reads, and it reads the new endpoint.
        model.selectOwner("ops")
        advanceUntilIdle()
        assertEquals(2, host.calls.size)
        assertEquals(listOf("Beta Morning"), model.uiState.value.jobs.map { it.title })
    }

    @Test
    fun `a selection after the endpoint moved does not reuse the old rows`() = runTest {
        val endpoint = MutableStateFlow(0L)
        val host = ScriptedHost(endpoint).apply {
            answer("cron.manage", success(jobs("[bot:ops] Alpha Morning")), success(jobs("[bot:ops] Beta Morning")))
        }
        val model = viewModel(drivenScope(), host, endpoint = endpoint)

        model.selectOwner("ops")
        advanceUntilIdle()

        // Same bot name, different endpoint: this is a different machine's
        // profile of the same name, so the boundary resets before the name is
        // reused. The selection still stands, but it starts from nothing — no
        // rows, no scope receipt, no answer about the old machine's bot, and a
        // Loading phase rather than the previous list.
        endpoint.value = 1L
        model.selectOwner("ops")

        assertEquals("ops", model.uiState.value.owner)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.jobs)
        assertEquals(emptyList<RoutineRow>(), model.uiState.value.all)
        assertNull(model.uiState.value.scoped)
        assertEquals(BotsRoutinesPhase.Loading, model.uiState.value.phase)

        // The reset is followed by the read the selection itself issues, and it
        // asks the new endpoint.
        advanceUntilIdle()
        assertEquals(2, host.calls.size)
        assertEquals(listOf("Beta Morning"), model.uiState.value.jobs.map { it.title })
    }

    @Test
    fun `the view model declares no mutable static state`() {
        // Desktop keeps this pane's owner in module-global atoms
        // (`cron.tsx:42,1198-1207`). This app has no module globals — the owner
        // is instance state — and a `companion object var` here would make two
        // routines surfaces share one bot, which is the defect this asserts
        // against.
        val targets = buildList {
            add(BotsRoutinesViewModel::class.java)
            runCatching { add(Class.forName("${BotsRoutinesViewModel::class.java.name}\$Companion")) }
        }
        val mutableStatics = targets
            .flatMap { it.declaredFields.toList() }
            .filter { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    !java.lang.reflect.Modifier.isFinal(field.modifiers)
            }

        assertEquals(emptyList<java.lang.reflect.Field>(), mutableStatics)
    }
}
