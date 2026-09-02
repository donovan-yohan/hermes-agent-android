package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextUsageJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private val cache = SessionCache()
    private val repository = JourneyRepository(cache)
    private lateinit var viewModel: ChatViewModel

    private fun launch(
        breakdown: ContextBreakdown? = null,
        usage: SessionUsage? = null,
    ) {
        val testUsage = usage ?: SessionUsage(
            contextUsed = 4_000,
            contextMax = 20_000,
            contextPercent = 20,
            total = 4_000,
            model = "test-model",
        )
        cache.upsertSessions(
            listOf(
                SessionSummary(
                    id = "session-1",
                    title = "Test Session",
                    preview = "Preview",
                    lastActiveAtMillis = 1_000L,
                    status = SessionStatus.Idle,
                    usage = testUsage,
                ),
            ),
        )
        cache.setTranscript("session-1", listOf(AssistantTurn("a-1", "Ready.", 1_000L)))
        repository.breakdown = breakdown

        viewModel = ChatViewModel(cache, repository, clock = { 1_000L })

        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(
                        onQueryChange = viewModel::setQuery,
                        onDraftChange = viewModel::setDraft,
                        onRefreshNavigation = viewModel::refreshSessionNavigation,
                        onSidebarGroupingChange = viewModel::setSidebarGrouping,
                        onSelectProject = viewModel::selectProject,
                        onExitProject = viewModel::exitProject,
                        onCreateProject = viewModel::createProject,
                        onSelectSession = viewModel::selectSession,
                        onCreateSession = viewModel::createSession,
                        onSend = viewModel::submit,
                        onStop = viewModel::stop,
                        onRedirect = viewModel::redirectDraftFromUi,
                        onQueue = viewModel::queueDraft,
                    ),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `top bar renders context meter with label, detail and its spoken name`() {
        launch()
        compose.onNodeWithText("4k/20k").assertIsDisplayed()
        compose.onNodeWithText("[\u2588\u2588\u2591\u2591\u2591\u2591\u2591\u2591\u2591\u2591] 20%").assertIsDisplayed()
        // The name rides on the click action, so a screen reader keeps reading
        // the figures a sighted user sees rather than replacing them.
        compose.onNodeWithTag(CONTEXT_METER_TAG).assert(
            SemanticsMatcher("has the Context usage click label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Context usage"
            },
        )
    }

    @Test
    fun `clicking context meter opens context usage sheet with metrics and categories`() {
        // The colours are the strings the Gateway actually sends
        // (`agent/context_breakdown.py:19-28` @ `3ca096de`), and the ids are
        // its own, so the eight `en.ts` labels are what has to render.
        val breakdown = ContextBreakdown(
            contextUsed = 4_000,
            contextMax = 20_000,
            contextPercent = 20,
            estimatedTotal = 4_000,
            model = "test-model",
            categories = listOf(
                ContextUsageCategory("system_prompt", "System prompt", 2_000, "var(--context-usage-system)"),
                ContextUsageCategory("conversation", "Conversation", 1_500, "var(--context-usage-conversation)"),
                ContextUsageCategory("mcp", "MCP", 500, "var(--context-usage-mcp)"),
            ),
        )
        launch(breakdown = breakdown)

        compose.onNodeWithTag(CONTEXT_METER_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Context Usage").assertIsDisplayed()
        compose.onNodeWithText("~4k / 20k Tokens").assertIsDisplayed()
        compose.onNodeWithText("20% Full").assertIsDisplayed()

        // Categories rendered with the pinned `en.ts:2966-2973` names and
        // compact token counts.
        compose.onNodeWithText("System prompt").assertIsDisplayed()
        compose.onNodeWithText("2k").assertIsDisplayed()
        compose.onNodeWithText("Conversation").assertIsDisplayed()
        compose.onNodeWithText("1.5k").assertIsDisplayed()
        compose.onNodeWithText("MCP").assertIsDisplayed()
        compose.onNodeWithText("500").assertIsDisplayed()
    }

    @Test
    fun `an unknown category keeps the label the gateway sent`() {
        val breakdown = ContextBreakdown(
            contextUsed = 4_000,
            contextMax = 20_000,
            contextPercent = 20,
            estimatedTotal = 4_000,
            categories = listOf(
                ContextUsageCategory("vendor_plugin", "Vendor plugin", 4_000, "var(--ui-text-tertiary)"),
            ),
        )
        launch(breakdown = breakdown)

        compose.onNodeWithTag(CONTEXT_METER_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Vendor plugin").assertIsDisplayed()
    }

    @Test
    fun `context usage sheet shows empty state when no categories exist`() {
        val breakdown = ContextBreakdown(
            contextUsed = 4_000,
            contextMax = 20_000,
            contextPercent = 20,
            estimatedTotal = 4_000,
            model = "test-model",
            categories = emptyList(),
        )
        launch(breakdown = breakdown)

        compose.onNodeWithTag(CONTEXT_METER_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("No context data yet").assertIsDisplayed()
    }

    private class JourneyRepository(private val cache: SessionCache) : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(status = GatewayConnectionStatus.Connected))
        override val activeTurns = MutableStateFlow<Set<String>>(emptySet())
        override val pendingInputs = MutableStateFlow(emptyMap<com.hermesagent.mobile.data.gateway.PendingInputKey, com.hermesagent.mobile.data.gateway.PendingInputRequest>())
        override val imageLoader = MutableStateFlow(null)

        var breakdown: ContextBreakdown? = null

        override suspend fun loadContextBreakdown(durableId: String): ContextBreakdown? = breakdown

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
