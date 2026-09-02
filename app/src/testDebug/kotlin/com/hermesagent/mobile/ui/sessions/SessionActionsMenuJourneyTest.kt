package com.hermesagent.mobile.ui.sessions

import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.common.COPY_CONFIRM_MILLIS
import com.hermesagent.mobile.ui.common.ClipboardWriter
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.ownedByProfileLabel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

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

    /**
     * The control is an overlay, so anything the row lays out underneath it is
     * unreachable: the pixels are the menu's, and a finger aiming at the title
     * or the profile tag opens the menu instead of the session. The row has to
     * reserve the control's whole width, not the distance to its glyph — the
     * glyph is centred, the hit box is not.
     */
    @Test
    fun `the row reserves the whole control, so no row content lies under it`() {
        var selected: String? = null
        launchSessionList(onSelect = { selected = it }, showAllProfiles = true)

        val control = HermesSpacing().touchTarget
        val row = compose.onNodeWithTag("Session row $FIRST_ID")
        val rowRight = row.getBoundsInRoot().right
        // The profile tag is the last thing the row lays out and it sits flush
        // against the end of the content region. It is a fixed 16dp glyph, so
        // where it stops is measurable rather than a function of text metrics.
        val contentRight = compose
            .onAllNodesWithContentDescription(ownedByProfileLabel("default"), useUnmergedTree = true)[0]
            .getBoundsInRoot()
            .right
        assertTrue(
            "row content reaches within ${rowRight - contentRight} of the row's end, " +
                "under the $control actions control",
            rowRight - contentRight >= control - LAYOUT_SLACK,
        )

        // And the last row pixel outside the control is still the session's.
        row.performTouchInput { click(Offset(width - control.toPx() - 1f, height / 2f)) }
        compose.waitForIdle()

        assertEquals(FIRST_ID, selected)
        assertEquals(0, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))
    }

    @Test
    fun `the copy verb writes the session id and confirms in place`() {
        launchSessionList()
        copySessionIdFromFirstRow()

        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(FIRST_ID, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        // Desktop's copy item keeps the menu open behind its own confirmation,
        // and the word it swaps in is `t.common.copied` verbatim.
        compose.onNodeWithText("Copied").assertIsDisplayed()
        assertEquals(1, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))
    }

    @Test
    fun `the confirmation settles back on its own rather than waiting for a dismiss`() {
        launchSessionList()
        copySessionIdFromFirstRow()
        compose.onNodeWithText("Copied").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(COPY_CONFIRM_MILLIS + 100)
        compose.waitForIdle()

        // Desktop's COPIED_RESET_MS, to the millisecond. The menu stays open —
        // only the item settles.
        compose.onNodeWithText("Copy ID").assertIsDisplayed()
        assertEquals(1, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))
    }

    @Test
    fun `a clipboard that refuses the clip says so in the item's own slot`() {
        var writes = 0
        launchControl(sessionId = FIRST_ID, writeClipboard = { _, _ -> writes++; false })

        compose.onNodeWithContentDescription(SESSION_ACTIONS_LABEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copy ID").performClick()
        compose.waitForIdle()

        assertEquals(1, writes)
        // The failure is the item's own state, in the slot the confirmation
        // would have used — not a toast, not a crash.
        compose.onNodeWithText("Could not copy session ID").assertIsDisplayed()
        assertEquals(1, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))

        // And it settles back like the confirmation does, so a retry is one tap
        // away rather than a dismiss and a reopen.
        compose.mainClock.advanceTimeBy(COPY_CONFIRM_MILLIS + 100)
        compose.waitForIdle()
        compose.onNodeWithText("Copy ID").assertIsDisplayed()
    }

    @Test
    fun `a session with no id gets no control at all`() {
        // parseSession rejects a missing id, not an empty one, so a blank id
        // reaches the UI. An empty bordered popup is chrome that lies.
        launchControl(sessionId = "", writeClipboard = { _, _ -> true })

        assertEquals(0, compose.nodesLabelled(SESSION_ACTIONS_LABEL))
        assertEquals(0, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))
    }

    /**
     * Destructive-red is Delete's alone. S15 has to be able to trust that the
     * flag paints, and S14's Rename has to be able to trust that it does not.
     */
    @Test
    fun `only a destructive item paints the destructive token`() {
        var destructive = Color.Unspecified
        var secondary = Color.Unspecified
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                destructive = HermesTheme.tokens.destructive
                secondary = HermesTheme.tokens.textSecondary
                Box {
                    SessionActionsMenu(
                        expanded = true,
                        items = { listOf(ARCHIVE, DELETE) },
                        onDismiss = {},
                        onSelect = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        // The assertion is only worth making if both roles resolved and the
        // two differ at all.
        assertNotEquals(Color.Unspecified, destructive)
        assertNotEquals(secondary, destructive)
        assertEquals(destructive, compose.inkOf(DELETE.label))
        assertEquals(secondary, compose.inkOf(ARCHIVE.label))
    }

    @Test
    fun `the chat header offers the open session the same three-item menu`() {
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
        compose.onNodeWithText("Rename…").assertIsDisplayed()
        compose.onNodeWithText("Copy ID").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        // Identical item list: the header and the row read the same spec.
        assertEquals(listOf("Rename…", "Copy ID", "Delete"), sessionActionItems(FIRST_ID).map { it.label })
    }

    @Test
    fun `renaming a session seeds the dialog with current title and saves on confirm`() {
        var renamedId: String? = null
        var renamedTitle: String? = null
        launchSessionList(
            onRenameSession = { id, title ->
                renamedId = id
                renamedTitle = title
            },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RENAME_SESSION_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText("Rename session").assertIsDisplayed()
        compose.onNodeWithText("Leave empty to clear.").assertIsDisplayed()

        compose.onNodeWithTag(RENAME_SESSION_INPUT_TAG).performTextClearance()
        compose.onNodeWithTag(RENAME_SESSION_INPUT_TAG).performTextInput("Renamed session")
        compose.waitForIdle()

        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals(FIRST_ID, renamedId)
        assertEquals("Renamed session", renamedTitle)
        assertEquals(0, compose.nodesTagged(RENAME_SESSION_DIALOG_TAG))
    }

    @Test
    fun `renaming without changes dismisses without calling onRename`() {
        var called = false
        launchSessionList(
            onRenameSession = { _, _ -> called = true },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        org.junit.Assert.assertFalse(called)
        assertEquals(0, compose.nodesTagged(RENAME_SESSION_DIALOG_TAG))
    }

    @Test
    fun `clearing the title in rename dialog saves empty string`() {
        var renamedTitle: String? = null
        launchSessionList(
            onRenameSession = { _, title -> renamedTitle = title },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RENAME_SESSION_INPUT_TAG).performTextClearance()
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals("", renamedTitle)
        assertEquals(0, compose.nodesTagged(RENAME_SESSION_DIALOG_TAG))
    }

    @Test
    fun `cancelling rename dialog closes without calling onRename`() {
        var called = false
        launchSessionList(
            onRenameSession = { _, _ -> called = true },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RENAME_SESSION_INPUT_TAG).performTextInput(" New")
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        org.junit.Assert.assertFalse(called)
        assertEquals(0, compose.nodesTagged(RENAME_SESSION_DIALOG_TAG))
    }

    @Test
    fun `rename error renders inline without closing dialog`() {
        launchSessionList(
            onRenameSession = { _, _ -> error("Rename failed. Try a different title.") },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rename…").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RENAME_SESSION_INPUT_TAG).performTextInput(" New")
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Rename failed. Try a different title.").assertIsDisplayed()
        assertEquals(1, compose.nodesTagged(RENAME_SESSION_DIALOG_TAG))
    }

    @Test
    fun `deleting a session opens confirmation dialog with redacted title and deletes on confirm`() {
        var deletedId: String? = null
        launchSessionList(
            onDeleteSession = { id -> deletedId = id },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(DELETE_SESSION_DIALOG_TAG).assertIsDisplayed()
        compose.onNodeWithText("Delete session?").assertIsDisplayed()
        compose.onNodeWithText("This will permanently delete “$FIRST_TITLE”. This cannot be undone.").assertIsDisplayed()

        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        assertEquals(FIRST_ID, deletedId)
        assertEquals(0, compose.nodesTagged(DELETE_SESSION_DIALOG_TAG))
    }

    @Test
    fun `cancelling delete dialog closes without calling onDelete`() {
        var called = false
        launchSessionList(
            onDeleteSession = { called = true },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()

        org.junit.Assert.assertFalse(called)
        assertEquals(0, compose.nodesTagged(DELETE_SESSION_DIALOG_TAG))
    }

    @Test
    fun `delete error renders inline without closing dialog`() {
        launchSessionList(
            onDeleteSession = { error("Cannot delete a running session. Stop the turn first and try again.") },
        )

        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Cannot delete a running session. Stop the turn first and try again.").assertIsDisplayed()
        assertEquals(1, compose.nodesTagged(DELETE_SESSION_DIALOG_TAG))
    }

    /**
     * Dismiss is not confirm, and a Compose `Dialog` has two ways out that no
     * button press covers. Both land on `onDismissRequest`
     * (`DeleteSessionDialog.kt:92`), which is exactly why it is worth pinning:
     * an edit that wired that lambda to the confirm path would delete a session
     * on a back gesture, and every button-press test here would still pass.
     *
     * The gesture goes to the dialog's own `OnBackPressedDispatcher` — the one
     * Compose's `DialogWrapper` registers its `dismissOnBackPress` callback on
     * — because the dialog is its own window and Robolectric has no second
     * window to press back in.
     */
    @Test
    fun `system back on the delete confirmation is not a confirm`() {
        var called = false
        launchSessionList(onDeleteSession = { called = true })
        openDeleteConfirmation()

        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        org.junit.Assert.assertFalse(called)
        assertEquals(0, compose.nodesTagged(DELETE_SESSION_DIALOG_TAG))
    }

    /**
     * The scrim half of the same rule. `dismissOnClickOutside` is on by default
     * and calls the same lambda, so a press that starts and ends off the
     * dialog's content closes it without deleting anything.
     *
     * The press is delivered at a point the dialog itself classifies as outside
     * its content, whatever geometry the window was laid out at — and if it did
     * not, nothing would dismiss and the assertions below would fail rather
     * than quietly pass.
     */
    @Test
    fun `a press outside the delete confirmation is not a confirm`() {
        var called = false
        launchSessionList(onDeleteSession = { called = true })
        openDeleteConfirmation()

        val dialog = ShadowDialog.getLatestDialog()
        compose.runOnUiThread {
            dialog.onTouchEvent(offContent(MotionEvent.ACTION_DOWN))
            dialog.onTouchEvent(offContent(MotionEvent.ACTION_UP))
        }
        compose.waitForIdle()

        org.junit.Assert.assertFalse(called)
        assertEquals(0, compose.nodesTagged(DELETE_SESSION_DIALOG_TAG))
    }

    /** Open the first row's menu and confirm nothing: just raise the dialog. */
    private fun openDeleteConfirmation() {
        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DELETE_SESSION_DIALOG_TAG).assertIsDisplayed()
    }

    /**
     * A press before the content's own origin. `DialogWrapper` compares the
     * event against its content view's bounds, and those bounds never start
     * below zero, so this is off the content on any layout.
     */
    private fun offContent(action: Int): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, -1f, -1f, 0)

    private fun launchSessionList(
        onSelect: (String) -> Unit = {},
        /** The unified view, which is the only scope that tags a row's owner. */
        showAllProfiles: Boolean = false,
        onRenameSession: (suspend (String, String) -> Unit)? = null,
        onDeleteSession: (suspend (String) -> Unit)? = null,
    ) {
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
                    onSelect = onSelect,
                    onCreate = {},
                    modifier = Modifier,
                    onRenameSession = onRenameSession,
                    onDeleteSession = onDeleteSession,
                    profileRail = ProfileRailState(
                        scope = ProfileScope(showAllProfiles = showAllProfiles),
                    ),
                )
            }
        }
        compose.waitForIdle()
    }

    /** Open the first row's menu and press its copy verb. */
    private fun copySessionIdFromFirstRow() {
        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Copy ID").performClick()
        compose.waitForIdle()
    }

    /** The control on its own, so a clipboard that refuses is reachable. */
    private fun launchControl(sessionId: String, writeClipboard: ClipboardWriter) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Box {
                    SessionActionsControl(sessionId = sessionId, writeClipboard = writeClipboard)
                }
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

    /** The colour a rendered label was actually laid out in. */
    private fun ComposeContentTestRule.inkOf(text: String): Color {
        val laidOut = mutableListOf<TextLayoutResult>()
        val read = onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
        assertTrue("no text layout to read for '$text'", read != null && read(laidOut))
        return laidOut.first().layoutInput.style.color
    }

    private companion object {
        const val NOW = 1_755_600_000_000L

        /** Layout rounds to whole pixels; half a dp of slack, not a whole one. */
        val LAYOUT_SLACK = 0.5.dp

        const val FIRST_ID = "s-menu-1"

        const val FIRST_TITLE = "Menu shell session"

        val ARCHIVE = SessionActionItem(SessionActionsGroup.Danger, HermesIcon.Archive, "Archive")

        /** S15's item, standing in early so the flag it needs is proven now. */
        val DELETE =
            SessionActionItem(SessionActionsGroup.Danger, HermesIcon.Trash, "Delete", destructive = true)
    }
}
