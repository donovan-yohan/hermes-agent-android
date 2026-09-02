package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.attachments.OutgoingAttachment
import com.hermesagent.mobile.data.composer.CompletionResult
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ControlMutationResult
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.NewSessionComposerOverrides
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome
import com.hermesagent.mobile.data.gateway.GatewayRedirectOutcome
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ContextBreakdown
import com.hermesagent.mobile.data.session.ContextUsageCategory
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.SessionUsage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextMeterViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var cache: SessionCache
    private lateinit var repository: FakeContextRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cache = SessionCache().apply {
            upsertSessions(
                listOf(
                    summary("session-1", 2_000),
                    summary("session-2", 1_000),
                ),
            )
        }
        repository = FakeContextRepository(cache)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `gaugeUsage prioritizes breakdown figures over streamed usage`() = runTest(dispatcher) {
        val streamedUsage = SessionUsage(
            contextUsed = 1_000,
            contextMax = 8_000,
            contextPercent = 12,
            total = 5_000,
            input = 800,
            output = 200,
            calls = 1,
            model = "streamed-model",
        )
        cache.upsertSession(summary("session-1", 2_000).copy(usage = streamedUsage))

        val breakdown = ContextBreakdown(
            contextUsed = 3_000,
            contextMax = 16_000,
            contextPercent = 19,
            estimatedTotal = 7_000,
            model = "breakdown-model",
            categories = listOf(
                ContextUsageCategory("system", "System Prompt", 3_000, "#ff0000"),
            ),
        )
        repository.breakdowns["session-1"] = breakdown

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        val meter = viewModel.uiState.value.contextMeter
        assertNotNull(meter)
        assertEquals(3_000L, meter?.usage?.contextUsed)
        assertEquals(16_000L, meter?.usage?.contextMax)
        assertEquals(19, meter?.usage?.contextPercent)
        // `gaugeUsage` spreads the streamed figure and overrides only the three
        // context fields (`use-statusbar-items.tsx:254-265` @ `3ca096de`):
        // `total` and `model` are never taken from the breakdown.
        assertEquals(5_000L, meter?.usage?.total)
        assertEquals("streamed-model", meter?.usage?.model)
        assertEquals(breakdown, meter?.breakdown)
        assertEquals("3k/16k", meter?.label)
        assertEquals("[██░░░░░░░░] 19%", meter?.detail)

        job.cancel()
    }

    @Test
    fun `gaugeUsage falls back to streamed usage when breakdown is null`() = runTest(dispatcher) {
        val streamedUsage = SessionUsage(
            contextUsed = 1_500,
            contextMax = 8_000,
            contextPercent = 18,
            total = 3_000,
            model = "streamed-model",
        )
        cache.upsertSession(summary("session-1", 2_000).copy(usage = streamedUsage))
        repository.breakdowns["session-1"] = null

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        val meter = viewModel.uiState.value.contextMeter
        assertNotNull(meter)
        assertEquals(1_500L, meter?.usage?.contextUsed)
        assertEquals(8_000L, meter?.usage?.contextMax)
        assertEquals(18, meter?.usage?.contextPercent)
        assertEquals(3_000L, meter?.usage?.total)
        assertEquals("streamed-model", meter?.usage?.model)
        assertNull(meter?.breakdown)
        assertEquals("1.5k/8k", meter?.label)
        assertEquals("[██░░░░░░░░] 18%", meter?.detail)

        job.cancel()
    }

    @Test
    fun `contextMeter is null when usage is absent`() = runTest(dispatcher) {
        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        val meter = viewModel.uiState.value.contextMeter
        assertNull(meter)

        job.cancel()
    }

    @Test
    fun `breakdown load is cancelled and state is reset on session switch`() = runTest(dispatcher) {
        val breakdownGate = CompletableDeferred<Unit>()
        repository.breakdownGate = breakdownGate
        repository.breakdowns["session-1"] = ContextBreakdown(
            contextUsed = 2_000,
            contextMax = 10_000,
            contextPercent = 20,
            estimatedTotal = 2_000,
            categories = emptyList(),
        )
        repository.breakdowns["session-2"] = ContextBreakdown(
            contextUsed = 4_000,
            contextMax = 10_000,
            contextPercent = 40,
            estimatedTotal = 4_000,
            categories = emptyList(),
        )

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        // Still gated for session-1
        assertEquals(1, repository.loadBreakdownCalls.size)
        assertEquals("session-1", repository.loadBreakdownCalls.last())

        // Switch to session-2 before session-1 completes
        viewModel.selectSession("session-2")
        runCurrent()

        assertEquals(listOf("session-1", "session-2"), repository.loadBreakdownCalls)

        // Release gate
        breakdownGate.complete(Unit)
        runCurrent()

        val meter = viewModel.uiState.value.contextMeter
        assertNotNull(meter)
        assertEquals(4_000L, meter?.usage?.contextUsed)
        assertEquals(40, meter?.usage?.contextPercent)

        job.cancel()
    }

    @Test
    fun `breakdown is not fetched while busy and fetches when turn finishes`() = runTest(dispatcher) {
        // Start session in Working status
        cache.upsertSession(summary("session-1", 2_000).copy(status = SessionStatus.Working))
        repository.breakdowns["session-1"] = ContextBreakdown(
            contextUsed = 5_000,
            contextMax = 20_000,
            contextPercent = 25,
            estimatedTotal = 5_000,
            categories = emptyList(),
        )

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        // Should not have fetched while busy
        assertEquals(0, repository.loadBreakdownCalls.size)

        // Turn ends -> SessionStatus.Idle
        cache.upsertSession(summary("session-1", 2_000).copy(status = SessionStatus.Idle))
        runCurrent()

        // Now fetched
        assertEquals(1, repository.loadBreakdownCalls.size)
        assertEquals("session-1", repository.loadBreakdownCalls[0])

        val meter = viewModel.uiState.value.contextMeter
        assertNotNull(meter)
        assertEquals(5_000L, meter?.usage?.contextUsed)
        assertEquals(25, meter?.usage?.contextPercent)

        job.cancel()
    }


    @Test
    fun `a resumed session whose breakdown reports no context window stays hidden`() = runTest(dispatcher) {
        // `agent/context_breakdown.py:130-131` @ `3ca096de`: no
        // `context_compressor` means `context_max: 0`, and such a session has no
        // measured usage either. Desktop renders '' and hides the item; taking
        // `estimated_total` as `total` here would show "45k tok" instead.
        cache.upsertSession(summary("session-1", 2_000).copy(usage = SessionUsage()))
        repository.breakdowns["session-1"] = ContextBreakdown(
            contextUsed = 45_000,
            contextMax = 0,
            contextPercent = 0,
            estimatedTotal = 45_000,
            model = "breakdown-model",
            categories = emptyList(),
        )

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        assertNull(viewModel.uiState.value.contextMeter)

        job.cancel()
    }

    @Test
    fun `another session's transcript deltas never re-issue the breakdown rpc`() = runTest(dispatcher) {
        // `SessionCache.state` republishes on every transcript append of ANY
        // session, and the fetch loop collects it. Without a derived,
        // deduplicated signal a background turn's `message.delta` stream turns
        // into one `session.context_breakdown` per delta on the user's own
        // Gateway — the foreground-isolation rule guarantees such a turn can
        // stream while the active session sits idle. The active session's own
        // backend answers nothing, which is the state the old `== null`
        // condition never left.
        repository.breakdowns["session-1"] = null

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(listOf("session-1"), repository.loadBreakdownCalls)

        repeat(50) { index ->
            cache.putEntry("session-2", AssistantTurn("bg-$index", "chunk $index", 1_000L, streaming = true))
            runCurrent()
        }

        assertEquals(listOf("session-1"), repository.loadBreakdownCalls)

        job.cancel()
    }

    @Test
    fun `a backend that answers nothing is asked once, not on every republish`() = runTest(dispatcher) {
        // The old condition was `activeContextBreakdown.value == null`, which on
        // any backend that returns null is permanently true.
        repository.breakdowns["session-1"] = null

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(1, repository.loadBreakdownCalls.size)

        repeat(20) { index ->
            cache.putEntry("session-1", AssistantTurn("fg-$index", "chunk $index", 1_000L))
            runCurrent()
        }

        assertEquals(1, repository.loadBreakdownCalls.size)

        job.cancel()
    }

    @Test
    fun `the read waits for navigation to bind a runtime rather than opening one`() = runTest(dispatcher) {
        repository.runtimeReady = false
        repository.breakdowns["session-1"] = ContextBreakdown(
            contextUsed = 2_000,
            contextMax = 10_000,
            contextPercent = 20,
            estimatedTotal = 2_000,
            categories = emptyList(),
        )

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(0, repository.loadBreakdownCalls.size)

        // Navigation binds the runtime; the next thing the cache publishes is
        // what lets the read through.
        repository.runtimeReady = true
        cache.putEntry("session-1", AssistantTurn("a-1", "Ready.", 1_000L))
        runCurrent()

        assertEquals(listOf("session-1"), repository.loadBreakdownCalls)

        job.cancel()
    }

    @Test
    fun `each turn that ends re-reads the breakdown exactly once`() = runTest(dispatcher) {
        repository.breakdowns["session-1"] = ContextBreakdown(
            contextUsed = 2_000,
            contextMax = 10_000,
            contextPercent = 20,
            estimatedTotal = 2_000,
            categories = emptyList(),
        )

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        assertEquals(1, repository.loadBreakdownCalls.size)

        repeat(3) { turn ->
            cache.upsertSession(summary("session-1", 2_000).copy(status = SessionStatus.Working))
            runCurrent()
            repeat(5) { index ->
                cache.putEntry("session-1", AssistantTurn("t$turn-$index", "chunk $index", 1_000L, streaming = true))
                runCurrent()
            }
            cache.upsertSession(summary("session-1", 2_000).copy(status = SessionStatus.Idle))
            runCurrent()
            assertEquals(turn + 2, repository.loadBreakdownCalls.size)
        }

        job.cancel()
    }


    @Test
    fun `a session switch keeps loading true while the new session's read is in flight`() = runTest(dispatcher) {
        // The cancelled read still runs its `finally`, and it can run after its
        // successor already claimed the flag. Clearing it there would paint
        // "No context data yet" over a session that is loading.
        val gate = CompletableDeferred<Unit>()
        repository.breakdownGate = gate
        cache.upsertSession(
            summary("session-2", 1_000).copy(
                usage = SessionUsage(contextUsed = 1_000, contextMax = 8_000, contextPercent = 12, total = 1_000),
            ),
        )

        val viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.selectSession("session-2")
        runCurrent()

        assertEquals(listOf("session-1", "session-2"), repository.loadBreakdownCalls)
        assertEquals(true, viewModel.uiState.value.contextMeter?.loading)

        gate.complete(Unit)
        runCurrent()
        assertEquals(false, viewModel.uiState.value.contextMeter?.loading)

        job.cancel()
    }

    private fun summary(id: String, at: Long) = SessionSummary(
        id = id,
        title = "Session $id",
        preview = "",
        lastActiveAtMillis = at,
    )

    private class FakeContextRepository(
        private val cache: SessionCache,
    ) : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(status = GatewayConnectionStatus.Connected))
        override val activeTurns = MutableStateFlow<Set<String>>(emptySet())
        override val pendingInputs = MutableStateFlow(emptyMap<com.hermesagent.mobile.data.gateway.PendingInputKey, com.hermesagent.mobile.data.gateway.PendingInputRequest>())
        override val imageLoader = MutableStateFlow(null)

        val breakdowns = mutableMapOf<String, ContextBreakdown?>()
        val loadBreakdownCalls = mutableListOf<String>()
        var breakdownGate: CompletableDeferred<Unit>? = null
        var runtimeReady: Boolean = true

        override fun hasLiveRuntime(durableId: String): Boolean = runtimeReady

        /**
         * Propagates the `CancellationException` from [breakdownGate] the way
         * the live repository does now that it rethrows one
         * (`GatewaySessionRepository.loadContextBreakdown`).
         */
        override suspend fun loadContextBreakdown(durableId: String): ContextBreakdown? {
            loadBreakdownCalls += durableId
            breakdownGate?.await()
            return breakdowns[durableId]
        }

        override suspend fun refreshSessions() = Unit
        override suspend fun openSession(durableId: String): String = durableId
        override suspend fun createSession(workspacePath: String?): String = "new-session"
        override suspend fun createSession(workspacePath: String?, overrides: NewSessionComposerOverrides?): String = "new-session"
        override suspend fun loadModelOptions(durableId: String?): ModelCatalog = ModelCatalog()
        override suspend fun loadComposerControls(durableId: String?): ModelControlsSnapshot = ModelControlsSnapshot()
        override suspend fun setLiveModel(durableId: String, selection: ComposerModelSelection): ControlMutationResult = ControlMutationResult.Applied
        override suspend fun setLiveReasoning(durableId: String, effort: ReasoningEffort): ControlMutationResult = ControlMutationResult.Applied
        override suspend fun setLiveFast(durableId: String, mode: FastMode): ControlMutationResult = ControlMutationResult.Applied
        override suspend fun completeSlash(query: String): CompletionResult = CompletionResult(emptyList(), 0)
        override suspend fun completePath(durableId: String?, query: String, cwd: String): CompletionResult = CompletionResult(emptyList(), 0)
        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome = GatewaySubmitOutcome.Accepted
        override suspend fun submit(durableId: String, text: String, queued: Boolean, attachments: List<OutgoingAttachment>): GatewaySubmitOutcome = GatewaySubmitOutcome.Accepted
        override suspend fun redirect(durableId: String, text: String): GatewayRedirectOutcome = GatewayRedirectOutcome.Redirected
        override suspend fun interrupt(durableId: String) = Unit
        override suspend fun requestInterrupt(durableId: String): GatewayInterruptOutcome = GatewayInterruptOutcome.Interrupted
    }
}
