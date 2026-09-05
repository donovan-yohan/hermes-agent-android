package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import com.hermesagent.mobile.data.gateway.ApprovalMode
import com.hermesagent.mobile.data.gateway.ApprovalModeOutcome
import com.hermesagent.mobile.data.gateway.ApprovalModeState
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.session.AssistantTurn
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The approval-mode chip in the chat top bar and the menu it opens, against
 * Desktop's statusbar item (`apps/desktop/src/app/shell/approval-mode-menu.tsx`
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`) and the copy at
 * `apps/desktop/src/i18n/en.ts:2897-2906`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalModeJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private val cache = SessionCache()
    private val repository = JourneyRepository()
    private lateinit var viewModel: ChatViewModel

    private fun launch(mode: ApprovalMode?) {
        cache.upsertSessions(
            listOf(
                SessionSummary(
                    id = "session-1",
                    title = "Test Session",
                    preview = "Preview",
                    lastActiveAtMillis = 1_000L,
                    status = SessionStatus.Idle,
                    // The context meter is the chip's left-hand neighbour, so
                    // the row has to render both for the order to be assertable.
                    usage = SessionUsage(
                        contextUsed = 4_000,
                        contextMax = 20_000,
                        contextPercent = 20,
                        total = 4_000,
                        model = "test-model",
                    ),
                ),
            ),
        )
        cache.setTranscript("session-1", listOf(AssistantTurn("a-1", "Ready.", 1_000L)))
        repository.approvalMode.value = ApprovalModeState(mode)

        viewModel = ChatViewModel(cache, repository, clock = { 1_000L })
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(
                        onSelectSession = viewModel::selectSession,
                        onSelectApprovalMode = viewModel::selectApprovalMode,
                    ),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the chip is absent until the host has answered`() {
        launch(mode = null)
        compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).assertDoesNotExist()
    }

    @Test
    fun `the chip names the mode and speaks Desktop's own accessible name`() {
        launch(ApprovalMode.Smart)

        compose.onNodeWithText("Smart").assertIsDisplayed()
        // `ariaLabel: mode => \`Approval mode: ${mode}\`` (`en.ts:2899`). It
        // rides the click action so the merged row keeps speaking the mode word
        // a sighted reader sees.
        compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).assert(
            SemanticsMatcher("has the Approval mode click label") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Approval mode: Smart"
            },
        )
    }

    @Test
    fun `the chip sits after the context meter, the order Desktop's statusbar uses`() {
        launch(ApprovalMode.Smart)

        // `coreRightStatusbarItems` is ordered `context-usage` (547-559),
        // `session-timer` (560-567), then `approval-mode` (568-572)
        // (`apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx`), and the
        // array is laid out in a plain flex row, so array order is left to
        // right (`apps/desktop/src/app/shell/statusbar-controls.tsx:119-123`).
        val meter = compose.onNodeWithTag(CONTEXT_METER_TAG).assertIsDisplayed().getBoundsInRoot()
        val chip = compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).assertIsDisplayed().getBoundsInRoot()

        assertEquals(true, meter.left < chip.left)
    }

    /**
     * Desktop's `DropdownMenuLabel` is left-aligned at the same inset as the
     * words in its rows, because Desktop's selected mark is trailing
     * (`components/ui/dropdown-menu.tsx:169-198` @ `3ca096de`). This app's mark
     * leads the row, so the heading follows the words rather than the box, and
     * wears the app's own uppercase panel-label treatment. Ledgered in
     * `docs/parity/approval-mode.md`.
     */
    @Test
    fun `the menu heading is uppercase, centred in its band and on the rows' text column`() {
        launch(ApprovalMode.Off)

        compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).performClick()
        compose.waitForIdle()

        val heading = compose.onNodeWithText("APPROVAL MODE", useUnmergedTree = true)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val band = compose.onNodeWithTag(APPROVAL_MODE_MENU_HEADER_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val label = compose.onNodeWithText("Manual", useUnmergedTree = true).getUnclippedBoundsInRoot()

        assertClose("the heading sits on the rows' text column", label.left, heading.left)
        assertClose(
            "the heading is centred in its band",
            (band.top + band.bottom) / 2,
            (heading.top + heading.bottom) / 2,
        )
    }

    @Test
    fun `the menu holds Desktop's title and its three rows in Desktop's order`() {
        launch(ApprovalMode.Manual)

        compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("APPROVAL MODE").assertIsDisplayed()

        // Each row speaks its bold label and its secondary description, so the
        // rows are addressed by that pair rather than by a word the chip also
        // renders. Every string verbatim (`en.ts:2900-2905`).
        val manual = compose
            .onNodeWithContentDescription("Manual. Ask before actions that require approval")
            .assertIsDisplayed()
            .getBoundsInRoot().top
        val smart = compose
            .onNodeWithContentDescription("Smart. Automatically assess actions and ask when needed")
            .assertIsDisplayed()
            .getBoundsInRoot().top
        val off = compose
            .onNodeWithContentDescription("Off. Run without approval prompts")
            .assertIsDisplayed()
            .getBoundsInRoot().top
        // `['manual', 'smart', 'off']` (`approval-mode-menu.tsx:62`).
        assertEquals(true, manual < smart)
        assertEquals(true, smart < off)
    }

    @Test
    fun `picking a row writes that mode immediately`() {
        launch(ApprovalMode.Manual)

        compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Off").performClick()
        compose.waitForIdle()

        assertEquals(listOf(ApprovalMode.Off), repository.writes)
        // Desktop highlights only `off` and it is the label the chip now shows.
        compose.onNodeWithTag(APPROVAL_MODE_CHIP_TAG).assertIsDisplayed()
    }

    private fun assertClose(what: String, expected: Dp, actual: Dp) {
        assertEquals("$what: expected $expected, was $actual", 0f, (expected - actual).value, 1f)
    }

    private class JourneyRepository : GatewaySessionRepository {
        override val connectionState =
            MutableStateFlow(GatewayConnectionState(status = GatewayConnectionStatus.Connected))
        override val approvalMode = MutableStateFlow(ApprovalModeState())
        override val activeTurns = MutableStateFlow<Set<String>>(emptySet())
        override val pendingInputs = MutableStateFlow(emptyMap<PendingInputKey, PendingInputRequest>())

        val writes = mutableListOf<ApprovalMode>()

        override suspend fun setApprovalMode(mode: ApprovalMode): ApprovalModeOutcome {
            writes += mode
            approvalMode.value = approvalMode.value.copy(mode = mode)
            return ApprovalModeOutcome.Applied
        }

        override suspend fun refreshSessions() = Unit
        override suspend fun openSession(durableId: String): String = durableId
        override suspend fun createSession(workspacePath: String?): String = "new-session"
        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome =
            GatewaySubmitOutcome.Accepted

        override suspend fun interrupt(durableId: String) = Unit
    }
}
