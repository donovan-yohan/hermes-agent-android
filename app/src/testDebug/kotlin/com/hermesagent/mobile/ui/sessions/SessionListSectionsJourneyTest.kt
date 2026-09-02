package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.ALL_PINNED_NOTE
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
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
 * The list's new sections and controls as a reader meets them: a leading
 * `Pinned` group, the `Archived` view and its own row treatment, and the two
 * whole-list verbs the filter menu carries.
 *
 * Every expectation is Desktop's, at
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3` — the ledger is
 * `docs/parity/session-list-sections.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionListSectionsJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `pinned rows render under their own section label above the buckets`() {
        launch(
            sessions = listOf(
                session("s-1", "Kept chat", pinned = true),
                session("s-2", "Ordinary chat"),
            ),
        )

        compose.onNodeWithTag(PINNED_SECTION_TAG).assertIsDisplayed()
        // `SectionLabel` renders its text uppercased, as every other group
        // label in this rail does.
        compose.onNodeWithText("PINNED").assertIsDisplayed()
        compose.onNodeWithTag("Session row s-1").assertIsDisplayed()
        compose.onNodeWithTag("Session row s-2").assertIsDisplayed()
    }

    /**
     * The sentence Desktop ships for an otherwise confusing empty recents list
     * (`apps/desktop/src/i18n/en.ts:2214` @ the pin), verbatim.
     */
    @Test
    fun `an all-pinned list explains its empty recents in Desktop's words`() {
        launch(sessions = listOf(session("s-1", "Kept chat", pinned = true)))

        compose.onNodeWithText(ALL_PINNED_NOTE).assertIsDisplayed()
        assertEquals(
            "Everything here is pinned. Unpin a chat to show it in recents.",
            ALL_PINNED_NOTE,
        )
    }

    @Test
    fun `a pinned row's menu offers the way back out of the section`() {
        launch(sessions = listOf(session("s-1", "Kept chat", pinned = true)))

        openFirstRowMenu()

        compose.onNodeWithText("Unpin").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Pin"))
    }

    /**
     * One slot, both directions, and the glyph names the action: `Mark as read`
     * carries the open envelope (`session-actions-menu.tsx:314-315` @ the pin).
     */
    @Test
    fun `a read row offers to mark it unread`() {
        launch(sessions = listOf(session("s-1", "Fresh chat")))

        openFirstRowMenu()

        compose.onNodeWithText("Mark as unread").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Mark as read"))
    }

    @Test
    fun `an unread row offers to mark it read`() {
        launch(sessions = listOf(session("s-1", "Fresh chat", unread = true)))

        openFirstRowMenu()

        compose.onNodeWithText("Mark as read").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Mark as unread"))
    }

    /**
     * The read-state item reads the two *raw* sources, not the resolved dot:
     * Desktop's `unread || isUnread` (`session-actions-menu.tsx:314-315,319` @
     * the pin). A row that is working and watermarked is still unread — using
     * the dot's own precedence here would leave it offering `Mark as unread`
     * with no way to clear the watermark at all.
     */
    @Test
    fun `a working row that carries the watermark still offers to mark it read`() {
        launch(
            sessions = listOf(
                session("s-1", "Busy chat", unread = true, status = SessionStatus.Working),
            ),
        )

        openFirstRowMenu()

        compose.onNodeWithText("Mark as read").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Mark as unread"))
    }

    /** The durable watermark lights the same dot the finished-turn marker does. */
    @Test
    fun `a watermarked row speaks as finished and unread`() {
        launch(sessions = listOf(session("s-1", "Fresh chat", unread = true)))

        compose.onNodeWithContentDescription("Fresh chat. Finished, unread").assertIsDisplayed()
    }

    @Test
    fun `the filter menu carries the Archived toggle at a full touch target`() {
        launch(sessions = listOf(session("s-1", "Ordinary chat")))

        openFilterMenu()

        compose.onNodeWithTag(ARCHIVED_FILTER_OPTION)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithText("Archived").assertIsDisplayed()
    }

    /**
     * And the menu stays open, because Desktop's option rows deliberately do:
     * `keepOpen` (`filter-menu.tsx:124-126` @ the pin — "so a whole view can be
     * set up in one pass. Only the actions at the bottom dismiss it").
     */
    @Test
    fun `toggling Archived asks for the archived view and keeps the menu open`() {
        var requested: Boolean? = null
        launch(
            sessions = listOf(session("s-1", "Ordinary chat")),
            onArchivedVisibleChange = { requested = it },
        )

        openFilterMenu()
        compose.onNodeWithTag(ARCHIVED_FILTER_OPTION).performClick()
        compose.waitForIdle()

        assertEquals(true, requested)
        compose.onNodeWithTag(ARCHIVED_FILTER_OPTION).assertIsDisplayed()
    }

    /**
     * An archived session has no live status to paint, so the archive glyph
     * takes the lead slot the dot would occupy
     * (`app/chat/sidebar/session-row.tsx:284-290` @ the pin), and the row's
     * menu offers the restore.
     */
    @Test
    fun `an archived row is marked as archived and offers the restore`() {
        launch(
            sessions = listOf(session("s-1", "Filed chat", archived = true)),
            archivedVisible = true,
        )

        // The row publishes one merged spoken label, so the lead mark is read
        // out of the unmerged tree — it is paint, not a second thing to visit.
        assertEquals(1, compose.nodesTagged(ARCHIVED_ROW_MARK, useUnmergedTree = true))
        compose.onNodeWithContentDescription("Filed chat. Archived").assertIsDisplayed()

        openFirstRowMenu()
        compose.onNodeWithText("Unarchive").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Archive"))
    }

    /** Desktop's archived empty state, verbatim (`i18n/en.ts:1154-1155`). */
    @Test
    fun `an empty Archived view says nothing is archived rather than no sessions`() {
        launch(sessions = emptyList(), archivedVisible = true)

        compose.onNodeWithTag(ARCHIVED_EMPTY_STATE).assertIsDisplayed()
        compose.onNodeWithText("Nothing archived").assertIsDisplayed()
        compose.onNodeWithText("Archive a chat to hide it here.").assertIsDisplayed()
    }

    /**
     * Desktop's filter-menu item stays mounted and `disabled` at zero unread
     * (`filter-menu.tsx:411` @ the pin, `disabled={unreadIds.length === 0}`) —
     * a control that vanishes teaches nobody it exists.
     */
    @Test
    fun `mark all as read stays visible and disabled while nothing is unread`() {
        launch(sessions = listOf(session("s-1", "Ordinary chat")))

        openFilterMenu()

        compose.onNodeWithTag(MARK_ALL_READ_OPTION)
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose.onNodeWithText("Mark all as read").assertIsDisplayed()
    }

    @Test
    fun `mark all as read is offered at a full touch target once something is unread`() {
        var marked = false
        launch(
            sessions = listOf(session("s-1", "Fresh chat", unread = true)),
            unreadCount = 1,
            onMarkAllRead = { marked = true },
        )

        openFilterMenu()
        compose.onNodeWithTag(MARK_ALL_READ_OPTION)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithText("Mark all as read").assertIsDisplayed()
        compose.onNodeWithTag(MARK_ALL_READ_OPTION).performClick()
        compose.waitForIdle()

        assertTrue(marked)
    }

    @Test
    fun `pinning a row reaches the caller with the row's id`() {
        val writes = mutableListOf<Pair<String, Boolean>>()
        launch(
            sessions = listOf(session("s-1", "Ordinary chat")),
            onSetSessionPinned = { id, pinned -> writes += id to pinned },
        )

        openFirstRowMenu()
        compose.onNodeWithText("Pin").performClick()
        compose.waitForIdle()

        assertEquals(listOf("s-1" to true), writes)
        // Desktop's pin item closes the menu; only Copy ID keeps it open.
        assertEquals(0, compose.nodesTagged(SESSION_ACTIONS_MENU_TAG))
    }

    @Test
    fun `archiving a row reaches the caller with the row's id`() {
        val writes = mutableListOf<Pair<String, Boolean>>()
        launch(
            sessions = listOf(session("s-1", "Ordinary chat")),
            onSetSessionArchived = { id, archived -> writes += id to archived },
        )

        openFirstRowMenu()
        compose.onNodeWithText("Archive").performClick()
        compose.waitForIdle()

        assertEquals(listOf("s-1" to true), writes)
    }

    @Test
    fun `marking a row unread reaches the caller with the row's id`() {
        val writes = mutableListOf<Pair<String, Boolean>>()
        launch(
            sessions = listOf(session("s-1", "Ordinary chat")),
            onSetSessionUnread = { id, unread -> writes += id to unread },
        )

        openFirstRowMenu()
        compose.onNodeWithText("Mark as unread").performClick()
        compose.waitForIdle()

        assertEquals(listOf("s-1" to true), writes)
    }

    private fun openFirstRowMenu() {
        compose.onAllNodesWithContentDescription(SESSION_ACTIONS_LABEL)[0].performClick()
        compose.waitForIdle()
    }

    private fun openFilterMenu() {
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.waitForIdle()
    }

    private fun launch(
        sessions: List<SessionSummary>,
        archivedVisible: Boolean = false,
        unreadCount: Int = 0,
        onArchivedVisibleChange: (Boolean) -> Unit = {},
        onMarkAllRead: () -> Unit = {},
        onSetSessionPinned: ((String, Boolean) -> Unit)? = null,
        onSetSessionUnread: ((String, Boolean) -> Unit)? = null,
        onSetSessionArchived: ((String, Boolean) -> Unit)? = null,
    ) {
        val rows = buildSessionRows(sessions, NOW, archivedView = archivedVisible)
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                SessionList(
                    rows = rows,
                    projects = emptyList(),
                    projectsAvailable = null,
                    sidebarGrouping = SidebarGrouping.Date,
                    selectedProject = null,
                    projectLoading = false,
                    activeSessionId = null,
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
                    onSetSessionPinned = onSetSessionPinned,
                    onSetSessionUnread = onSetSessionUnread,
                    onSetSessionArchived = onSetSessionArchived,
                    archivedVisible = archivedVisible,
                    onArchivedVisibleChange = onArchivedVisibleChange,
                    unreadCount = unreadCount,
                    onMarkAllRead = onMarkAllRead,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun session(
        id: String,
        title: String,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        unread: Boolean? = null,
        status: SessionStatus = SessionStatus.Idle,
    ) = SessionSummary(
        id = id,
        title = title,
        preview = "",
        lastActiveAtMillis = NOW,
        status = status,
        pinned = pinned,
        archived = archived,
        unread = unread,
    )

    private fun ComposeContentTestRule.nodesTagged(tag: String, useUnmergedTree: Boolean = false) =
        onAllNodes(hasTestTag(tag), useUnmergedTree).fetchSemanticsNodes().size

    private fun ComposeContentTestRule.nodesWithText(text: String) =
        onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().size +
            onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
