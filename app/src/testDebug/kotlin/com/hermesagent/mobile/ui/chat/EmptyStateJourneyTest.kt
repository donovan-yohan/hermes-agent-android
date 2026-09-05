package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.sessions.NEW_PROJECT_BUTTON
import com.hermesagent.mobile.ui.sessions.NO_SESSIONS_YET
import com.hermesagent.mobile.ui.sessions.SESSION_SKELETON_TAG
import com.hermesagent.mobile.ui.sessions.SIDEBAR_BLANK_STATE
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two vertically centred empty states, rendered.
 *
 * Desktop's originals are `Intro`
 * (`apps/desktop/src/components/chat/intro.tsx:160-179`) and
 * `SidebarBlankState` (`apps/desktop/src/app/chat/sidebar/section-states.tsx:26-42`),
 * both @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * The class runs wide enough for the persistent rail, so the session list is on
 * screen without a drawer gesture and the transcript column is beside it. The
 * narrow phone gets its own `@Config` below, because the splash's whole risk is
 * a column that cannot hold the wordmark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h800dp")
class EmptyStateJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an empty draft shows the wordmark splash instead of the plain note`() {
        launch()

        compose.onNodeWithTag(INTRO_SPLASH_TAG).assertIsDisplayed()
        // By its name, not by its text: the lettering is drawn as two lines and
        // a screen reader must still hear `HERMES AGENT` once.
        compose.onNodeWithContentDescription(INTRO_WORDMARK).assertIsDisplayed()
        compose.onAllNodesWithText("No messages yet").assertCountEquals(0)
    }

    /**
     * The narrowest phone this app supports, laid out on the **real** platform
     * face: `@GraphicsMode(NATIVE)` is what makes this a check rather than a
     * formality, because under Robolectric's default legacy graphics the
     * lettering has no width to overflow with and the old floor clamp passed
     * too. `WordmarkFitDeviceTest` carries the numbers; this carries the layout.
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the splash renders on the narrowest phone without leaving the screen`() {
        launch()

        compose.onNodeWithTag(INTRO_SPLASH_TAG).assertIsDisplayed()
        val wordmark = compose.onNodeWithContentDescription(INTRO_WORDMARK).fetchSemanticsNode().boundsInRoot
        val splash = compose.onNodeWithTag(INTRO_SPLASH_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "wordmark starts at ${wordmark.left}, left of the splash at ${splash.left}",
            wordmark.left >= splash.left - 1f,
        )
        assertTrue(
            "wordmark ends at ${wordmark.right}, right of the splash at ${splash.right}",
            wordmark.right <= splash.right + 1f,
        )
    }

    @Test
    fun `the splash draws one of Desktop's neutral lines`() {
        launch(introSeed = 2)

        // Seeded, so the assertion names a line rather than any line. The
        // production splash rolls a fresh seed per mount, exactly as Desktop's
        // `mountSeed` does.
        compose.onNodeWithText(NEUTRAL_INTRO_COPY[2]).assertIsDisplayed()
    }

    @Test
    fun `a transcript with one entry replaces the splash with the transcript`() {
        launch(
            activeSessionId = "session-a",
            transcript = listOf(AssistantTurn(id = "reply", markdown = "First reply", atMillis = NOW)),
        )

        compose.onAllNodesWithTag(INTRO_SPLASH_TAG).assertCountEquals(0)
        compose.onNodeWithText("First reply").assertIsDisplayed()
    }

    /**
     * Desktop splashes only a fresh draft (`intro-visibility.ts:12-33` @
     * `3ca096de`) because a homed session gets `ChatEmptySlot` instead — a
     * surface this app has never ported. The owner's call is that the wordmark
     * is the better thing to show there than a note that says less. The reason
     * this is safe is the message count: a session still reading its history
     * carries a non-zero one and is covered by `IntroSplashTest`.
     */
    @Test
    fun `a homed session with nothing in it shows the splash, not the plain note`() {
        launch(activeSessionId = "session-a")

        compose.onNodeWithTag(INTRO_SPLASH_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription(INTRO_WORDMARK).assertIsDisplayed()
        compose.onAllNodesWithText("No messages yet").assertCountEquals(0)
    }

    /** The toggle is still the one off switch, for a session as for a draft. */
    @Test
    fun `the toggle off returns a homed empty session to the plain note`() {
        launch(activeSessionId = "session-a", introSplashEnabled = false)

        compose.onAllNodesWithTag(INTRO_SPLASH_TAG).assertCountEquals(0)
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
    }

    /**
     * Where the session is working, under the line of copy. Desktop carries the
     * same two facts in its own chrome (`app/chat/index.tsx:419,675,734` @
     * `3ca096de`); a phone has no room for that, and the splash is the one
     * moment the session has nothing else to say.
     */
    @Test
    fun `a homed session names its project and the tail of its working directory`() {
        launch(
            activeSessionId = "session-a",
            project = ProjectSummary(id = "project-a", label = "hermes-mobile", path = null),
            worktreePath = "/data/data/com.example/files/work/hermes-mobile",
        )

        compose.onNodeWithText("hermes-mobile").assertIsDisplayed()
        compose.onNodeWithText("…/work/hermes-mobile").assertIsDisplayed()
    }

    /** A fresh draft has neither, so Desktop's own case renders unchanged. */
    @Test
    fun `a fresh draft shows no project and no path`() {
        launch()

        compose.onNodeWithTag(INTRO_SPLASH_TAG).assertIsDisplayed()
        compose.onAllNodesWithText("hermes-mobile").assertCountEquals(0)
    }

    @Test
    fun `the Appearance toggle off falls back to the plain centred note`() {
        launch(introSplashEnabled = false)

        compose.onAllNodesWithTag(INTRO_SPLASH_TAG).assertCountEquals(0)
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
        compose.onNodeWithText("Start a conversation with Hermes.").assertIsDisplayed()
    }

    @Test
    fun `an empty session list shows Desktop's sidebar blank state`() {
        launchSidebar()

        compose.onNodeWithTag(SIDEBAR_BLANK_STATE).assertIsDisplayed()
        compose.onNodeWithText(NO_SESSIONS_YET).assertIsDisplayed()
        compose.onNodeWithText(NEW_PROJECT_BUTTON).assertIsDisplayed()
        compose.onAllNodesWithText("No sessions").assertCountEquals(0)
    }

    /** A Gateway that is not there cannot make a project, and says so by shape. */
    @Test
    fun `New project is disabled and announced disabled while disconnected`() {
        launchSidebar()

        compose.onNodeWithText(NEW_PROJECT_BUTTON).assertIsNotEnabled()
        compose.onNodeWithText("Connect to a Gateway to start a session.").assertIsDisplayed()
    }

    @Test
    fun `New project opens the create dialog when the Gateway serves projects`() {
        launchSidebar(connected = true, projectsAvailable = true)

        compose.onNodeWithText(NEW_PROJECT_BUTTON).assertIsEnabled()
        compose.onAllNodesWithText("Connect to a Gateway to start a session.").assertCountEquals(0)
        compose.onNodeWithText(NEW_PROJECT_BUTTON).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Projects group sessions by a folder on the connected Gateway.")
            .assertIsDisplayed()
        compose.onNodeWithText("Create project").assertIsDisplayed()
    }

    /**
     * Desktop keeps the blank state behind `showSessionSkeletons`
     * (`sidebar/index.tsx:1426-1427,1912`) so an account that has not answered
     * yet is never described as empty. This is that rule.
     */
    @Test
    fun `a list still being read shows skeletons, never the blank state`() {
        launchSidebar(connected = true, sessionsLoading = true)

        compose.onNodeWithTag(SESSION_SKELETON_TAG).assertIsDisplayed()
        compose.onAllNodesWithTag(SIDEBAR_BLANK_STATE).assertCountEquals(0)
        compose.onAllNodesWithText(NO_SESSIONS_YET).assertCountEquals(0)
    }

    @Test
    fun `a list with a row shows no blank state`() {
        launchSidebar(
            rows = listOf(
                SessionListRow.Row(
                    SessionSummary(
                        id = "session-a",
                        title = "A saved chat",
                        preview = "",
                        lastActiveAtMillis = NOW,
                        status = SessionStatus.Idle,
                    ),
                ),
            ),
        )

        compose.onAllNodesWithTag(SIDEBAR_BLANK_STATE).assertCountEquals(0)
        compose.onNodeWithText("A saved chat").assertIsDisplayed()
    }

    private fun launch(
        introSplashEnabled: Boolean = true,
        activeSessionId: String? = null,
        transcript: List<TranscriptEntry> = emptyList(),
        introSeed: Int? = null,
        project: ProjectSummary? = null,
        worktreePath: String? = null,
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                if (introSeed != null) {
                    // The splash on its own, so the seed can be pinned. The
                    // gate that decides whether it renders is the other tests.
                    IntroSplash(seed = introSeed)
                } else {
                    ChatScreen(
                        state = ChatUiState(
                            activeSessionId = activeSessionId,
                            activeSession = activeSessionId?.let {
                                SessionSummary(
                                    id = it,
                                    title = "A live chat",
                                    preview = "",
                                    lastActiveAtMillis = NOW,
                                    status = SessionStatus.Idle,
                                    // Default zero: the Gateway has said this
                                    // session is empty, which is what lets it
                                    // splash rather than flash.
                                    messageCount = transcript.size,
                                    worktreePath = worktreePath,
                                )
                            },
                            activeSessionProject = project,
                            transcript = transcript,
                        ),
                        actions = ChatActions(),
                        onOpenSettings = {},
                        wideRailInsets = WindowInsets(0, 0, 0, 0),
                        imeInsets = WindowInsets(0, 0, 0, 0),
                        introSplashEnabled = introSplashEnabled,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The rail, which is where the session list is without opening a drawer. */
    private fun launchSidebar(
        rows: List<SessionListRow> = emptyList(),
        connected: Boolean = false,
        projectsAvailable: Boolean? = null,
        sessionsLoading: Boolean = false,
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        sessionRows = rows,
                        projectsAvailable = projectsAvailable,
                        sessionsLoading = sessionsLoading,
                        connection = GatewayConnectionState(
                            status = if (connected) {
                                GatewayConnectionStatus.Connected
                            } else {
                                GatewayConnectionStatus.Disconnected
                            },
                        ),
                    ),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    wideRailInsets = WindowInsets(0, 0, 0, 0),
                    imeInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val NOW = 1_755_600_000_000L
    }
}
