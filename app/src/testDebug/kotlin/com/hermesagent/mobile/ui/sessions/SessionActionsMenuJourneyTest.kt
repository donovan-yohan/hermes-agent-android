package com.hermesagent.mobile.ui.sessions

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The actions menu as a reader meets it: one always-visible control per row,
 * one on the chat header for the open session, and a row whose spoken label
 * the new control does not break apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionActionsMenuJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every row carries its own full-size actions control`() {
        launchSessionList()

        assertEquals(2, compose.nodesLabelled(SESSION_ACTIONS_LABEL))
        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0]
            .assertIsDisplayed()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
            .assertWidthIsAtLeast(HermesSpacing().touchTarget)
    }

    @Test
    fun `the control does not fragment the row's one authoritative spoken label`() {
        launchSessionList()

        // The row still speaks as exactly one node, and the control's own name
        // is a sibling rather than a fragment of it.
        assertEquals(1, compose.nodesLabelled("$FIRST_TITLE. Idle"))
        compose.onNodeWithContentDescription("$FIRST_TITLE. Idle").assertIsDisplayed()
        assertEquals(0, compose.nodesLabelled("$FIRST_TITLE. Idle. $SESSION_ACTIONS_LABEL"))
    }

    @Test
    fun `tapping the control opens the menu, and long-press does not`() {
        launchSessionList()

        // Long-press is deliberately not a path in: it belongs to text
        // selection, and Desktop's modifier chords have no touch equivalent.
        compose.onNodeWithTag("Session row $FIRST_ID").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(0, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()

        assertEquals(1, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))
        compose.onNodeWithText("Copy ID").assertIsDisplayed()
    }

    @Test
    fun `the copy verb writes the session id and confirms in place`() {
        launchSessionList()

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copy ID").performClick()
        compose.waitForIdle()

        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(FIRST_ID, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        // Desktop's copy item keeps the menu open behind its own confirmation.
        compose.onNodeWithText("Session ID copied").assertIsDisplayed()
    }

    @Test
    fun `the chat header offers the open session the same single-item menu`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(activeSession = session(FIRST_ID, FIRST_TITLE)),
                    actions = ChatActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        // Compact layout parks the session list behind a drawer, so the one
        // control on screen is the header's.
        assertEquals(1, compose.nodesLabelled(SESSION_ACTIONS_LABEL))
        compose.onNodeWithContentDescription(SESSION_ACTIONS_LABEL)
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
            .performClick()
        compose.waitForIdle()

        assertEquals(1, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))
        compose.onNodeWithText("Copy ID").assertIsDisplayed()
        // Identical item list: the header and the row read the same spec.
        assertEquals(listOf("Copy ID"), sessionActionItems(FIRST_ID).map { it.label })
    }

    private fun launchSessionList() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                SessionList(
                    rows = listOf(
                        SessionListRow.Row(session(FIRST_ID, FIRST_TITLE)),
                        SessionListRow.Row(session("s-menu-2", "Second session")),
                    ),
                    projects = emptyList(),
                    projectsAvailable = null,
                    sidebarGrouping = SidebarGrouping.Date,
                    selectedProject = null,
                    projectLoading = false,
                    activeSessionId = FIRST_ID,
                    query = "",
                    canCreate = true,
                    onQueryChange = {},
                    onSidebarGroupingChange = {},
                    onSelectProject = {},
                    onExitProject = {},
                    onCreateProject = { _, _ -> },
                    onSelect = {},
                    onCreate = {},
                    modifier = Modifier,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun session(id: String, title: String) = SessionSummary(
        id = id,
        title = title,
        preview = "",
        lastActiveAtMillis = NOW,
    )

    private fun ComposeContentTestRule.nodesLabelled(label: String) =
        onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().size

    private fun ComposeContentTestRule.nodesTagged(tag: String) =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private companion object {
        const val NOW = 1_755_600_000_000L
        const val FIRST_ID = "s-menu-1"
        const val FIRST_TITLE = "Menu shell session"
    }
}
