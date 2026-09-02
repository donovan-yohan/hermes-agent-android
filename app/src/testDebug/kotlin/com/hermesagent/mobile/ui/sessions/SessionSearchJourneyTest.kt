package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.data.session.noSessionsMatch
import com.hermesagent.mobile.ui.chat.ArchivedPoolState
import com.hermesagent.mobile.ui.common.SEARCH_FIELD_GLYPH
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sessions rail's search as a reader meets it: the field's own chrome, the
 * one `Results` section a live query answers in, the placeholders while the
 * backend is still answering, and the sentence when it settles on nothing.
 *
 * Every expectation is Desktop's, at
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3` — the ledger is
 * `docs/parity/session-search.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionSearchJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * `Search sessions…` with the ellipsis (`i18n/en.ts:2201`), spoken as
     * `Search sessions` (`:2200`), behind the leading search glyph
     * (`components/ui/search-field.tsx:69`).
     */
    @Test
    fun `the field carries Desktop's placeholder, spoken name and leading glyph`() {
        launch(sessions = listOf(session("s-1", "Tunnel probe")), query = "")
        revealSearchField()

        compose.onNodeWithText("Search sessions…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search sessions").assertIsDisplayed()
        compose.onNodeWithTag(SEARCH_FIELD_GLYPH).assertIsDisplayed()
        // Desktop shows the clear button only for a non-empty field
        // (`search-field.tsx:89`).
        assertEquals(0, compose.nodesWithContentDescription("Clear search"))
    }

    /** The field is typed into, and what it emits is what the reader typed. */
    @Test
    fun `typing in the field reports the query`() {
        val queries = mutableListOf<String>()
        launch(sessions = listOf(session("s-1", "Tunnel probe")), query = "", onQueryChange = { queries += it })
        revealSearchField()

        compose.onNodeWithContentDescription("Search sessions").performTextInput("tunnel")
        compose.waitForIdle()

        assertEquals("tunnel", queries.last())
    }

    /**
     * Desktop's `close` Codicon, named `Clear search` (`search-field.tsx:92,98`,
     * `en.ts:2202`), which empties the field.
     */
    @Test
    fun `a non-empty field offers Clear search, which empties it`() {
        val queries = mutableListOf<String>()
        launch(
            sessions = listOf(session("s-1", "Tunnel probe")),
            query = "tunnel",
            onQueryChange = { queries += it },
        )

        compose.onNodeWithContentDescription("Clear search").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(listOf(""), queries)
    }

    /**
     * One section labelled `Results` (`en.ts:2204`) in place of Pinned and the
     * date buckets (`sidebar/index.tsx:1611-1638,1640,1664`).
     */
    @Test
    fun `a live query answers under Results, with no Pinned section and no buckets`() {
        launch(
            sessions = listOf(
                session("s-1", "Tunnel probe", pinned = true),
                session("s-2", "Themes"),
            ),
            query = "tunnel",
        )

        // `SectionLabel` renders its text uppercased, as every group label in
        // this rail does.
        compose.onNodeWithTag(RESULTS_SECTION_TAG).assertIsDisplayed()
        compose.onNodeWithText("RESULTS").assertIsDisplayed()
        assertEquals(0, compose.nodesTagged(PINNED_SECTION_TAG))
        assertEquals(0, compose.nodesWithText("PINNED"))
        assertEquals(0, compose.nodesWithText("TODAY"))
        compose.onNodeWithTag("Session row s-1").assertIsDisplayed()
        assertEquals(0, compose.nodesTagged("Session row s-2"))
    }

    /**
     * Desktop hangs its skeletons on the section's empty state
     * (`sidebar/index.tsx:1615-1617`), so they stand in for the backend's
     * answer only — never beside a row that already matched.
     */
    @Test
    fun `skeletons stand in while the backend is still answering an unmatched query`() {
        launch(sessions = listOf(session("s-1", "Themes")), query = "tunnel", searchPending = true)

        compose.onNodeWithTag(SEARCH_SKELETON_TAG).assertIsDisplayed()
        assertEquals(0, compose.nodesWithText(noSessionsMatch("tunnel")))
    }

    @Test
    fun `a local match answering at once is never replaced by skeletons`() {
        launch(sessions = listOf(session("s-1", "Tunnel probe")), query = "tunnel", searchPending = true)

        assertEquals(0, compose.nodesTagged(SEARCH_SKELETON_TAG))
        compose.onNodeWithTag("Session row s-1").assertIsDisplayed()
    }

    /** `No sessions match “{query}”.` (`en.ts:2203`), verbatim. */
    @Test
    fun `a settled query that matches nothing says so in Desktop's words`() {
        launch(sessions = listOf(session("s-1", "Themes")), query = "tunnel")

        compose.onNodeWithText("No sessions match “tunnel”.").assertIsDisplayed()
        assertEquals("No sessions match “tunnel”.", noSessionsMatch("tunnel"))
        assertEquals(0, compose.nodesTagged(SEARCH_SKELETON_TAG))
    }

    /**
     * A hit the app has never paged in is a row like any other: the Gateway's
     * snippet is its preview, and it is reachable by tap.
     */
    @Test
    fun `a backend hit nothing is loaded for renders as a row that opens`() {
        val opened = mutableListOf<String>()
        launch(
            sessions = emptyList(),
            query = "tunnel",
            serverMatches = listOf(
                SessionSummary(
                    id = "unloaded-1",
                    title = "",
                    preview = "the tunnel probe",
                    lastActiveAtMillis = NOW,
                    lineageRootId = "root-1",
                ),
            ),
            onSelect = { opened += it },
        )

        compose.onNodeWithText("the tunnel probe").assertIsDisplayed()
        compose.onNodeWithTag("Session row unloaded-1").performClick()
        compose.waitForIdle()

        assertEquals(listOf("unloaded-1"), opened)
    }

    /**
     * The field is behind the filter menu's own toggle on a phone rail — see
     * the ledger — so an empty-query journey opens it the way a reader does.
     */
    private fun revealSearchField() {
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Search").performClick()
        compose.waitForIdle()
    }

    private fun launch(
        sessions: List<SessionSummary>,
        query: String,
        searchPending: Boolean = false,
        serverMatches: List<SessionSummary>? = null,
        onQueryChange: (String) -> Unit = {},
        onSelect: (String) -> Unit = {},
    ) {
        val rows = buildSessionRows(
            sessions = sessions,
            nowMillis = NOW,
            query = query,
            searchPending = searchPending,
            serverMatches = serverMatches,
        )
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
                    query = query,
                    canCreate = true,
                    onQueryChange = onQueryChange,
                    onSidebarGroupingChange = {},
                    onSelectProject = {},
                    onExitProject = {},
                    onCreateProject = { _, _ -> },
                    onSelect = onSelect,
                    onCreate = {},
                    modifier = Modifier,
                    archivedVisible = false,
                    archivedPool = ArchivedPoolState.Loaded,
                    onArchivedVisibleChange = {},
                    unreadCount = 0,
                    onMarkAllRead = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun session(id: String, title: String, pinned: Boolean? = null) = SessionSummary(
        id = id,
        title = title,
        preview = "",
        lastActiveAtMillis = NOW,
        pinned = pinned,
    )

    private fun ComposeContentTestRule.nodesTagged(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun ComposeContentTestRule.nodesWithText(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun ComposeContentTestRule.nodesWithContentDescription(text: String) =
        onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().size

    private companion object {
        /** Wednesday 2026-08-19, 12:00 UTC. */
        const val NOW = 1_787_140_800_000L
    }
}
