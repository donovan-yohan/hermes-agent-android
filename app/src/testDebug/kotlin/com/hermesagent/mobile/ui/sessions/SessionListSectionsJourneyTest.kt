package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
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
import com.hermesagent.mobile.ui.chat.ArchivedPoolState
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
 * `437116f9497c80d242ce034ff7f5d81dc277a337` — the ledger is
 * `docs/parity/session-list-sections.md`.
 * The archived action is compared against the per-session source at that pin;
 * the list's existing pool contract remains recorded in its own ledger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionListSectionsJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Desktop's default row metadata is `['preview', 'updated']`
     * (`store/layout.ts:308` @ `437116f9497c80d242ce034ff7f5d81dc277a337`), so
     * the age is not opt-in: every row carries it unless a reader turns the
     * field off. This pins that default-on behaviour to the rendered row, so a
     * later WIP toggle cannot quietly drop the field on the way in.
     */
    @Test
    fun `session rows show Desktop's default relative age metadata`() {
        launch(
            sessions = listOf(
                session("s-1", "Fresh chat", lastActiveAtMillis = NOW - 12 * MINUTE),
                session("s-2", "Older chat", lastActiveAtMillis = NOW - 9 * HOUR),
            ),
        )

        // Every row, not just the first: the metadata is the list's default.
        assertEquals(2, compose.nodesTagged(SESSION_ROW_AGE_META, useUnmergedTree = true))
        // And each row carries its own age, read off the same bucket rule.
        compose.ageIn("s-1").assertTextEquals("12m")
        compose.ageIn("s-2").assertTextEquals("9h")
        compose.onNodeWithContentDescription("Fresh chat. Idle. Updated 12 minutes ago")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Older chat. Idle. Updated 9 hours ago")
            .assertIsDisplayed()
    }

    /**
     * Desktop renders the age in the row's trailing figures slot, right-aligned
     * against the action control (`session-row.tsx:220-245` @ the pin). Anchored
     * on the row's right edge rather than on the label's own centre, because the
     * point of the assertion is which side of the row it sits on.
     */
    @Test
    fun `the age sits in the row's trailing slot, right-aligned`() {
        launch(
            sessions = listOf(session("s-1", "Fresh chat", lastActiveAtMillis = NOW - 12 * MINUTE)),
        )

        val row = compose.onNodeWithTag(sessionRowTag("s-1")).fetchSemanticsNode().boundsInRoot
        val age = compose.ageIn("s-1").fetchSemanticsNode().boundsInRoot

        assertTrue("the age must sit in the row's trailing half", age.center.x > row.center.x)
        assertTrue("the age must not overflow the row", age.right <= row.right)
    }

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

        compose.onNodeWithContentDescription("Fresh chat. Finished, unread. Updated just now").assertIsDisplayed()
    }

    /**
     * The archived view keeps Desktop's `Pinned` section: its gate is
     * `!trimmedQuery` with no `showArchived` term
     * (`sidebar/index.tsx:1652-1674` @ `437116f9`), so a chat that is pinned and
     * then archived keeps the pin's only visible effect. The section leads the
     * archived rows, and the pinned row is rendered once — under the caption,
     * not again among the rows below it. Both rows are reached by tag, and the
     * count proves the single rendering.
     */
    @Test
    fun `the archived view keeps the pinned section above its rows`() {
        launch(
            sessions = listOf(
                session("s-1", "Filed chat", archived = true),
                session("s-2", "Kept and filed", archived = true, pinned = true),
            ),
            archivedVisible = true,
        )

        compose.onNodeWithTag(PINNED_SECTION_TAG).assertIsDisplayed()
        compose.onNodeWithText("PINNED").assertIsDisplayed()
        compose.onNodeWithTag("Session row s-2").assertIsDisplayed()
        compose.onNodeWithTag("Session row s-1").assertIsDisplayed()

        // The pinned row is one row: no second copy under the section.
        assertEquals(1, compose.nodesTagged(sessionRowTag("s-2")))
        assertEquals(1, compose.nodesTagged(sessionRowTag("s-1")))

        // And the section is above the archived rows, as Desktop orders it.
        val pinned = compose.onNodeWithTag(PINNED_SECTION_TAG).fetchSemanticsNode().boundsInRoot
        val pinnedRow = compose.onNodeWithTag(sessionRowTag("s-2")).fetchSemanticsNode().boundsInRoot
        val otherRow = compose.onNodeWithTag(sessionRowTag("s-1")).fetchSemanticsNode().boundsInRoot
        assertTrue("the caption must sit above the archived rows", pinned.top < pinnedRow.top)
        assertTrue("the pinned row must sit above the archived rows", pinnedRow.top < otherRow.top)
    }

    /**
     * The rows under that section stay flat: `grouping='none'` while archived
     * (`sidebar/index.tsx:1736` @ `437116f9`) settles the dividers, and it still
     * does so now that the section above them is back. The two archived rows
     * here fall in different calendar buckets — a `LastWeek` divider would be
     * the regression — and none may render.
     */
    @Test
    fun `the archived view draws no date dividers under the pinned section`() {
        launch(
            sessions = listOf(
                session("s-1", "Filed today", archived = true, lastActiveAtMillis = NOW - 12 * MINUTE),
                session("s-2", "Filed last week", archived = true, lastActiveAtMillis = NOW - 9 * DAY),
                session("s-3", "Kept and filed", archived = true, pinned = true, lastActiveAtMillis = NOW - HOUR),
            ),
            archivedVisible = true,
        )

        compose.onNodeWithTag(PINNED_SECTION_TAG).assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("LAST WEEK"))
        assertEquals(0, compose.nodesWithText("TODAY"))
        compose.onNodeWithTag(sessionRowTag("s-1")).assertIsDisplayed()
        compose.onNodeWithTag(sessionRowTag("s-2")).assertIsDisplayed()
    }

    /**
     * And with every archived row pinned, the section's `allPinned` sentence is
     * the one Desktop picks for the recents slot it leaves empty
     * (`sidebar/index.tsx:1710-1712` @ `437116f9`, no `showArchived` term) —
     * the same string the live list shows, not a rewritten one.
     */
    @Test
    fun `an archived view with everything pinned carries Desktop's own sentence`() {
        launch(
            sessions = listOf(session("s-1", "Kept and filed", archived = true, pinned = true)),
            archivedVisible = true,
        )

        compose.onNodeWithTag(PINNED_SECTION_TAG).assertIsDisplayed()
        compose.onNodeWithText(ALL_PINNED_NOTE).assertIsDisplayed()
        assertEquals(
            "Everything here is pinned. Unpin a chat to show it in recents.",
            ALL_PINNED_NOTE,
        )
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
     * (`app/chat/sidebar/session-row.tsx:284-290` @ `437116f9`). The row's
     * own Archived-view action keeps restore reachable while the per-session
     * menu preserves Desktop's unconditional `Archive`
     * (`app/chat/sidebar/session-actions-menu.tsx:431-440` @ `437116f9`).
     */
    @Test
    fun `an archived row keeps Archive in its menu and offers restore beside the row`() {
        val writes = mutableListOf<Boolean>()
        launch(
            sessions = listOf(session("s-1", "Filed chat", archived = true)),
            archivedVisible = true,
            onSetSessionArchived = { _, archived -> writes += archived },
        )

        // The row publishes one merged spoken label, so the lead mark is read
        // out of the unmerged tree — it is paint, not a second thing to visit.
        assertEquals(1, compose.nodesTagged(ARCHIVED_ROW_MARK, useUnmergedTree = true))
        compose.onNodeWithContentDescription("Filed chat. Archived. Updated just now").assertIsDisplayed()

        compose.onNodeWithTag(ARCHIVED_RESTORE_ACTION)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithText(UNARCHIVE).assertIsDisplayed()
        compose.onNodeWithContentDescription("Filed chat. Archived. Updated just now").assertIsDisplayed()
        compose.onNodeWithTag(ARCHIVED_RESTORE_ACTION).performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), writes)

        openFirstRowMenu()
        compose.onNodeWithText(ARCHIVE).assertIsDisplayed()
        compose.onNodeWithTag(SESSION_ACTIONS_MENU_TAG).assertIsDisplayed()
    }

    /**
     * Desktop's archived empty state, verbatim (`i18n/en.ts:1154-1155`) — and
     * only once the pool has actually answered.
     */
    @Test
    fun `an empty Archived view says nothing is archived rather than no sessions`() {
        launch(sessions = emptyList(), archivedVisible = true, archivedPool = ArchivedPoolState.Loaded)

        compose.onNodeWithTag(ARCHIVED_EMPTY_STATE).assertIsDisplayed()
        compose.onNodeWithText("Nothing archived").assertIsDisplayed()
        compose.onNodeWithText("Archive a chat to hide it here.").assertIsDisplayed()
    }

    /**
     * `Nothing archived` is a claim about the account, so an unanswered pool
     * must not make it. Desktop keeps the same marker but renders nothing with
     * it (`$archivedSessionsLoading`, `store/sidebar-archive.ts:12,19,28` @ the
     * pin); this list waits, exactly as it already waits on a project
     * drill-in.
     */
    @Test
    fun `an Archived view whose pool has not answered says it is loading`() {
        launch(sessions = emptyList(), archivedVisible = true, archivedPool = ArchivedPoolState.Loading)

        compose.onNodeWithTag(ARCHIVED_LOADING_STATE).assertIsDisplayed()
        compose.onNodeWithText("Loading archived chats…").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Nothing archived"))
    }

    /** A read that failed is not an account with nothing archived. */
    @Test
    fun `an Archived view whose pool failed says so and offers the way back`() {
        launch(sessions = emptyList(), archivedVisible = true, archivedPool = ArchivedPoolState.Failed)

        compose.onNodeWithTag(ARCHIVED_FAILED_STATE).assertIsDisplayed()
        compose.onNodeWithText("Couldn’t load archived chats").assertIsDisplayed()
        compose.onNodeWithText("Check the Gateway, then turn Archived off and on again.").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Nothing archived"))
    }

    /**
     * And a Gateway serving only `session.list` — which has no archived filter
     * (`tui_gateway/methods_session.py:246-266,267-282` @ the pin) — gets the
     * sentence about the Gateway, never the one about the account.
     */
    @Test
    fun `an Archived view on a Gateway that cannot answer says so`() {
        launch(sessions = emptyList(), archivedVisible = true, archivedPool = ArchivedPoolState.Unsupported)

        compose.onNodeWithTag(ARCHIVED_UNSUPPORTED_STATE).assertIsDisplayed()
        compose.onNodeWithText("Archived chats unavailable").assertIsDisplayed()
        compose.onNodeWithText("Archived chats need a newer Hermes on this Gateway.").assertIsDisplayed()
        assertEquals(0, compose.nodesWithText("Nothing archived"))
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

    /**
     * The other half of "a flag write survives the row leaving the screen": the
     * control itself must not own the write.
     *
     * Each of these verbs takes the row off the list it was pressed on, so a
     * coroutine started in the row's own composition scope is cancelled
     * mid-`PATCH`. The press therefore has to *return* with the write already
     * handed on — which is what a non-suspending callback guarantees, and what
     * this asserts by reading the recorder before Compose is pumped at all. A
     * `suspend` callback launched into a `rememberCoroutineScope` would still
     * be sitting on the frame dispatcher here.
     */
    @Test
    fun `the row menu hands off each write before the press returns`() {
        val pins = mutableListOf<Pair<String, Boolean>>()
        val unreads = mutableListOf<Pair<String, Boolean>>()
        val archives = mutableListOf<Pair<String, Boolean>>()
        launch(
            sessions = listOf(session("s-1", "Ordinary chat")),
            onSetSessionPinned = { id, value -> pins += id to value },
            onSetSessionUnread = { id, value -> unreads += id to value },
            onSetSessionArchived = { id, value -> archives += id to value },
        )

        openFirstRowMenu()
        compose.onNodeWithText("Pin").performClick()
        assertEquals(listOf("s-1" to true), pins)

        openFirstRowMenu()
        compose.onNodeWithText("Mark as unread").performClick()
        assertEquals(listOf("s-1" to true), unreads)

        openFirstRowMenu()
        compose.onNodeWithText("Archive").performClick()
        assertEquals(listOf("s-1" to true), archives)
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
        archivedPool: ArchivedPoolState = ArchivedPoolState.Loaded,
        nowMillis: Long = NOW,
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
                    nowMillis = nowMillis,
                    archivedVisible = archivedVisible,
                    archivedPool = archivedPool,
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
        lastActiveAtMillis: Long = NOW,
    ) = SessionSummary(
        id = id,
        title = title,
        preview = "",
        lastActiveAtMillis = lastActiveAtMillis,
        status = status,
        pinned = pinned,
        archived = archived,
        unread = unread,
    )

    private fun ComposeContentTestRule.nodesTagged(tag: String, useUnmergedTree: Boolean = false) =
        onAllNodes(hasTestTag(tag), useUnmergedTree).fetchSemanticsNodes().size

    /**
     * The age label inside one row. Every row carries the same tag, so the match
     * has to be scoped by the row it belongs to rather than taken by tag alone.
     */
    private fun ComposeContentTestRule.ageIn(sessionId: String) =
        onNode(
            hasTestTag(SESSION_ROW_AGE_META) and hasAnyAncestor(hasTestTag(sessionRowTag(sessionId))),
            useUnmergedTree = true,
        )

    private fun ComposeContentTestRule.nodesWithText(text: String) =
        onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().size +
            onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
    }
}
