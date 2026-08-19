package com.hermesagent.mobile.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.demo.DemoSessions
import com.hermesagent.mobile.data.demo.DemoTurnEngine
import com.hermesagent.mobile.data.demo.TurnTiming
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The core phone journey, driven through real Compose semantics on the JVM
 * (Robolectric — no emulator, so it runs here and in CI).
 *
 * It is deliberately a *journey*, not a screenshot: open the session drawer,
 * search it, switch session, create one, type, send, and see the reply land.
 * Those are the interactions the slice claims, and this is what proves them
 * without a device.
 *
 * Assertion style note: the compact layout keeps the drawer composed while it
 * is closed, so a session title or preview legitimately exists twice in the
 * tree. Tests therefore assert on **counts and existence** for anything the
 * drawer also shows, and reserve `assertIsDisplayed` for nodes that are unique
 * to one surface.
 *
 * What still needs a physical device: real IME behaviour, gesture navigation,
 * fold/unfold, and rendering fidelity. See `docs/phase-1-architecture.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val cache = SessionCache()
    private lateinit var viewModel: ChatViewModel
    private var themeName by mutableStateOf(BuiltinThemes.DEFAULT_NAME)

    private fun launch() {
        DemoSessions.seed(cache, NOW)
        viewModel = ChatViewModel(
            cache = cache,
            turnEngine = DemoTurnEngine(TurnTiming(firstDelayMillis = 10, deltaDelayMillis = 5, toolRunMillis = 20)),
            clock = { NOW },
        )
        viewModel.selectSession(DemoSessions.INITIAL_SESSION_ID)

        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection(themeName, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(
                        onQueryChange = viewModel::setQuery,
                        onDraftChange = viewModel::setDraft,
                        onSelectSession = viewModel::selectSession,
                        onCreateSession = { viewModel.createSession() },
                        onArchiveToggle = viewModel::setArchived,
                        onRenameSession = viewModel::renameSession,
                        onSend = viewModel::submit,
                        onStop = viewModel::stop,
                        onToggleArchived = { viewModel.setShowArchived(!state.showArchived) },
                    ),
                    onOpenSettings = {},
                )
            }
        }
    }

    @Test
    fun `chat opens on the newest session with its transcript`() {
        launch()

        assertTrue("the active session's title must be on screen", compose.countWithText("SSH tunnel bring-up") >= 1)
        // The assistant's reply is unique to the transcript. `exists` rather
        // than `isDisplayed`: the transcript opens scrolled to its tail, so an
        // earlier block is legitimately off-screen.
        assertEquals(1, compose.countWithText("Termux and this app are separate", substring = true))
        assertTrue("the tool scaffold row renders", compose.countWithText("probe hermes-box:22") >= 1)
    }

    @Test
    fun `the session drawer opens, groups by date, and switches session`() {
        launch()

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("TODAY").assertIsDisplayed()

        compose.onNodeWithText("Theme parity with Desktop").performClick()
        compose.waitForIdle()

        // The transcript really swapped: the new session's reply is present and
        // the previous one's is gone.
        assertEquals(1, compose.countWithText("Append one entry to", substring = true))
        assertEquals(0, compose.countWithText("Termux and this app are separate", substring = true))
    }

    @Test
    fun `searching the drawer narrows the list`() {
        launch()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Search sessions").performTextInput("theme")
        compose.waitForIdle()

        assertEquals("the matching session stays", 1, compose.countWithText("Theme parity with Desktop"))
        assertEquals("the non-matching session leaves the list", 0, compose.countWithText("Approval flow sketch"))
    }

    @Test
    fun `submitting swaps send for stop, and stopping keeps the partial turn`() {
        launch()

        compose.onNodeWithContentDescription("Message Hermes").performTextInput("what is real?")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitForIdle()

        // The user turn paints immediately (transcript bubble + drawer preview),
        // and send has become stop, in the same position.
        assertTrue(compose.countWithText("what is real?", substring = true) >= 1)
        compose.onNodeWithContentDescription("Stop generating").assertIsDisplayed()
        assertEquals(
            "send must not be reachable while a turn runs",
            0,
            compose.countWithContentDescription("Send message"),
        )

        compose.onNodeWithContentDescription("Stop generating").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        assertEquals(0, compose.countWithContentDescription("Stop generating"))
        assertTrue("the partial reply is kept and labelled", compose.countWithText("Stopped by you") >= 1)
    }

    @Test
    fun `a turn left running lands its reply in the transcript`() {
        launch()

        compose.onNodeWithContentDescription("Message Hermes").performTextInput("what is real?")
        compose.onNodeWithContentDescription("Send message").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.countWithText("six Desktop themes", substring = true) == 1
        }
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun `creating a session opens an empty transcript`() {
        launch()

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New session").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("No messages yet").assertIsDisplayed()
    }

    @Test
    fun `the attachment affordance is present and visibly unavailable`() {
        launch()
        compose.onNodeWithContentDescription("Attach a file (not available in this build)").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1000dp-h800dp")
    fun `the wide layout shows a persistent rail and no drawer affordance`() {
        launch()

        compose.onNodeWithText("Sessions").assertIsDisplayed()
        compose.onNodeWithText("Theme parity with Desktop").assertIsDisplayed()
        assertEquals(
            "a persistent rail must not also ship a drawer button",
            0,
            compose.countWithContentDescription("Open sessions"),
        )
    }

    @Test
    fun `every builtin theme renders the home surface`() {
        // Cheap smoke over the registry: a preset whose palette or tokens fail
        // to resolve blows up here rather than on someone's phone.
        launch()
        for (preset in BuiltinThemes.ALL) {
            themeName = preset.name
            compose.waitForIdle()
            assertTrue(
                "${preset.name} failed to render the transcript",
                compose.countWithText("Termux and this app are separate", substring = true) == 1,
            )
        }
    }

    private companion object {
        const val NOW = 1_755_600_000_000L
    }
}

private fun ComposeContentTestRule.countWithText(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

private fun ComposeContentTestRule.countWithContentDescription(description: String): Int =
    onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
