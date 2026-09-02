package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.composer.ComposerControlState
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.ModelOption
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.modelVisibilityKey
import com.hermesagent.mobile.data.gateway.APPROVAL_MODE_REJECTED
import com.hermesagent.mobile.data.gateway.ApprovalMode
import com.hermesagent.mobile.data.gateway.ApprovalModeOutcome
import com.hermesagent.mobile.data.gateway.ApprovalModeState
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.data.prefs.TransientComposerControlsStore
import com.hermesagent.mobile.data.session.SessionCache
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the chat chrome does with the approval mode and the model shortlist.
 *
 * Virtual time throughout: the state flow is a `combine` behind
 * `WhileSubscribed`, so every assertion runs behind a live collector and a
 * `runCurrent()`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalModeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the control stays hidden until the mode is known`() = runTest(dispatcher) {
        val repository = FakeApprovalRepository()
        val viewModel = ChatViewModel(SessionCache(), repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        // Desktop shows `smart` before its own read answers
        // (`store/approval-mode.ts:32`); a control naming a security posture
        // must not name one it is guessing.
        assertNull(viewModel.uiState.value.approvalMode)

        repository.approvalMode.value = ApprovalModeState(ApprovalMode.Smart)
        runCurrent()
        assertEquals(ApprovalMode.Smart, viewModel.uiState.value.approvalMode)

        job.cancel()
    }

    @Test
    fun `a disconnected gateway hides the control even when the mode is known`() = runTest(dispatcher) {
        val repository = FakeApprovalRepository()
        repository.approvalMode.value = ApprovalModeState(ApprovalMode.Off)
        val viewModel = ChatViewModel(SessionCache(), repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(ApprovalMode.Off, viewModel.uiState.value.approvalMode)

        // `hidden: gatewayState !== 'open'` (`use-statusbar-items.tsx:569`).
        repository.connectionState.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()

        assertNull(viewModel.uiState.value.approvalMode)
        job.cancel()
    }

    @Test
    fun `connecting reads the mode once`() = runTest(dispatcher) {
        val repository = FakeApprovalRepository(
            initialConnection = GatewayConnectionState(GatewayConnectionStatus.Disconnected),
        )
        ChatViewModel(SessionCache(), repository, clock = { 1_000L })
        runCurrent()
        assertEquals(0, repository.refreshes)

        repository.connectionState.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        assertEquals(1, repository.refreshes)
    }

    @Test
    fun `selecting a mode writes once, and the same mode again writes nothing`() = runTest(dispatcher) {
        val repository = FakeApprovalRepository()
        repository.approvalMode.value = ApprovalModeState(ApprovalMode.Manual)
        val viewModel = ChatViewModel(SessionCache(), repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.selectApprovalMode(ApprovalMode.Off)
        runCurrent()
        assertEquals(listOf(ApprovalMode.Off), repository.writes)

        // The repository already holds Off, so re-picking the selected row is
        // not a second write.
        viewModel.selectApprovalMode(ApprovalMode.Off)
        runCurrent()
        assertEquals(listOf(ApprovalMode.Off), repository.writes)

        job.cancel()
    }

    @Test
    fun `a refused write explains itself instead of springing back silently`() = runTest(dispatcher) {
        val repository = FakeApprovalRepository()
        repository.approvalMode.value = ApprovalModeState(ApprovalMode.Manual)
        repository.rejectWrites = true
        val viewModel = ChatViewModel(SessionCache(), repository, clock = { 1_000L })
        val job = launch { viewModel.uiState.collect {} }
        runCurrent()

        viewModel.selectApprovalMode(ApprovalMode.Off)
        runCurrent()

        assertEquals(APPROVAL_MODE_REJECTED, viewModel.uiState.value.notice)
        // The repository owns the value, so the control still reads what the
        // host last confirmed.
        assertEquals(ApprovalMode.Manual, viewModel.uiState.value.approvalMode)
        job.cancel()
    }

    @Test
    fun `the saved shortlist reaches the picker and a toggle persists it for this scope`() =
        runTest(dispatcher) {
            val store = TransientComposerControlsStore(SCOPE)
            val repository = FakeApprovalRepository()
            val viewModel = ChatViewModel(
                SessionCache(),
                repository,
                composerControlsStore = store,
                clock = { 1_000L },
            )
            val job = launch { viewModel.uiState.collect {} }
            runCurrent()

            // Never customised is null, so the picker falls back to the curated
            // default rather than to an empty list.
            assertNull(viewModel.uiState.value.composer.visibleModels)

            viewModel.toggleModelVisible("acme", "beta")
            runCurrent()

            val expected = setOf(modelVisibilityKey("acme", "alpha"))
            assertEquals(expected, viewModel.uiState.value.composer.visibleModels)
            assertEquals(expected, store.visibleModels(SCOPE).let { flow ->
                var seen: Set<String>? = null
                val collector = launch { flow.collect { seen = it } }
                runCurrent()
                collector.cancel()
                seen
            })

            viewModel.setProviderModelsVisible("acme", visible = false)
            runCurrent()
            assertTrue(viewModel.uiState.value.composer.visibleModels.orEmpty().all { it == "acme::" })

            job.cancel()
        }

    private class FakeApprovalRepository(
        initialConnection: GatewayConnectionState =
            GatewayConnectionState(GatewayConnectionStatus.Connected),
    ) : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(initialConnection)
        override val approvalMode = MutableStateFlow(ApprovalModeState())
        override val activeTurns = MutableStateFlow<Set<String>>(emptySet())
        override val pendingInputs =
            MutableStateFlow(emptyMap<com.hermesagent.mobile.data.gateway.PendingInputKey, com.hermesagent.mobile.data.gateway.PendingInputRequest>())

        var refreshes = 0
        var rejectWrites = false
        val writes = mutableListOf<ApprovalMode>()

        override suspend fun refreshApprovalMode() {
            refreshes += 1
        }

        override suspend fun setApprovalMode(mode: ApprovalMode): ApprovalModeOutcome {
            writes += mode
            if (rejectWrites) return ApprovalModeOutcome.Rejected(APPROVAL_MODE_REJECTED)
            approvalMode.value = approvalMode.value.copy(mode = mode)
            return ApprovalModeOutcome.Applied
        }

        override suspend fun loadComposerState(durableId: String?): ComposerControlState =
            ComposerControlState(CATALOG, ModelControlsSnapshot(selection = ComposerModelSelection("alpha", "acme")))

        override suspend fun refreshSessions() = Unit
        override suspend fun openSession(durableId: String): String = durableId
        override suspend fun createSession(workspacePath: String?): String = "new-session"
        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome =
            GatewaySubmitOutcome.Accepted

        override suspend fun interrupt(durableId: String) = Unit
    }

    private companion object {
        val SCOPE = ComposerControlsScope("remote:fixture", "default")

        val CATALOG = ModelCatalog(
            providers = listOf(
                ModelProvider("acme", "Acme", listOf(ModelOption("alpha"), ModelOption("beta"))),
            ),
            effectiveSelection = ComposerModelSelection("alpha", "acme"),
        )
    }
}
