package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.chat.CHAT_HEADER_NEW_SESSION_TAG
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.sessions.SIDEBAR_HEADER_ADD_TAG
import com.hermesagent.mobile.ui.sessions.SessionList
import com.hermesagent.mobile.ui.theme.AppearanceSelection
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
 * The two direct "start a session here" `+` controls Desktop draws and this app
 * was missing.
 *
 * Desktop renders the same action in two places on the chat route:
 *
 *  - the sessions header's `+`, beside the filter menu
 *    (`apps/desktop/src/app/chat/sidebar/index.tsx:1875-1895`, the button itself
 *    at `apps/desktop/src/app/chat/sidebar/chrome.tsx:54-101`, labelled
 *    `s.nav['new-session']` / `s.projects.newButton` —
 *    `apps/desktop/src/i18n/en.ts:3057,3093`);
 *  - the chat pane's own strip `+`, after the last tab and before the pane's
 *    trailing controls
 *    (`apps/desktop/src/components/pane-shell/tree/renderer/tree-group.tsx:313-319,726-742`,
 *    labelled `t.zones.newSessionTab` — `apps/desktop/src/i18n/en.ts:4250`).
 *
 * Both are read at `95f20517c25ee418da5337f4ead347008baaa2b3`.
 *
 * What has to hold here is not "a control exists" but that it is *the same
 * action* the rest of the app already uses — `ChatActions.onCreateSession`, the
 * one the sidebar nav row and the live-owner refusal's escape both call — so the
 * draft flush and foreground isolation `createSession` owns are inherited rather
 * than reimplemented at a second door.
 *
 * The chat header's control is named `New session`, not Desktop's
 * `New session tab`: a phone has no tab strip, and `New session` is Desktop's
 * own word for the same action on the two sidebar controls. Ledgered in
 * `docs/parity/desktop-sidebar.md`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class SessionCreateAffordancesTest {

    @get:Rule
    val compose = createComposeRule()

    // ── Sidebar header `+` ────────────────────────────────────────────────────

    /**
     * The regression itself. Commit `0185a4ef` restored the flat `New session`
     * *nav row* and, in the same edit, narrowed this header `+` to
     * project-overview mode only — dropping the flat-list door Desktop keeps.
     */
    @Test
    fun `the flat sessions header offers New session beside the filter control`() {
        var created = 0
        mountSidebar(onCreate = { created += 1 })

        val header = compose.onNodeWithTag(SIDEBAR_HEADER_ADD_TAG)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("New session")

        // Desktop's header cluster is `+` then the filter menu, in that order.
        val add = header.getUnclippedBoundsInRoot()
        val filters = compose.onNodeWithContentDescription("Filters").getUnclippedBoundsInRoot()
        assertTrue(
            "the add control must precede the filter menu, was add=$add filters=$filters",
            add.right.value <= filters.left.value + 1f,
        )

        header.performClick()
        assertEquals("the header control must reuse the existing create action", 1, created)
    }

    /**
     * Desktop swaps this one control's label with the view rather than adding a
     * second button: `ariaLabel={agentsGrouped ? s.projects.newButton :
     * s.nav['new-session']}` (`sidebar/index.tsx:1876`).
     */
    @Test
    fun `the same header control reads New project in the project overview`() {
        var created = 0
        mountSidebar(
            grouping = SidebarGrouping.Project,
            projects = listOf(ProjectSummary("p-one", "Synthetic project", "/synthetic", sessionCount = 0)),
            projectsAvailable = true,
            onCreate = { created += 1 },
        )

        compose.onAllNodesWithTag(SIDEBAR_HEADER_ADD_TAG).assertCountEquals(1)
        compose.onNodeWithTag(SIDEBAR_HEADER_ADD_TAG).assertContentDescriptionEquals("New project")
        compose.onNodeWithTag(SIDEBAR_HEADER_ADD_TAG).performClick()

        // What this control *becomes* is the project dialog, whose own open path
        // is `EmptyStateJourneyTest`'s claim (the dialog is a separate window and
        // adding a second idle-wait on it here would prove the same thing twice).
        // This test owns the swap: the same slot, labelled for the view it is in,
        // and never reaching the session-create action.
        assertEquals("the project-overview control never creates a session", 0, created)
    }

    /** Project-scoped creation is a direct mobile affordance, including drill-in. */
    @Test
    fun `an entered project offers its own scoped new session control`() {
        mountSidebar(
            grouping = SidebarGrouping.Project,
            selectedProject = ProjectSummary("p-one", "Synthetic project", "/synthetic", sessionCount = 1),
            projectsAvailable = true,
        )

        compose.onNodeWithTag(SIDEBAR_HEADER_ADD_TAG).assertContentDescriptionEquals("New session in Synthetic project").assertIsDisplayed()
        compose.onNodeWithContentDescription("All projects").assertIsDisplayed()
    }

    /** Visible and inert, not silently absent — the repo's rule for a gated control. */
    @Test
    fun `the flat header add control is inert while no Gateway is connected`() {
        var created = 0
        mountSidebar(canCreate = false, onCreate = { created += 1 })

        compose.onNodeWithTag(SIDEBAR_HEADER_ADD_TAG).assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals("a disconnected Gateway must not start a session", 0, created)
    }

    @Test
    fun `each project row selects its own project before creating a session`() {
        val events = mutableListOf<String>()
        mountSidebar(
            grouping = SidebarGrouping.Project,
            projects = listOf(
                ProjectSummary("home", "HOME", null, sessionCount = 0),
                ProjectSummary("p-one", "Synthetic project", "/synthetic", sessionCount = 0),
            ),
            projectsAvailable = true,
            onSelectProject = { events += it },
            onCreate = { events += "create" },
        )
        compose.onNodeWithContentDescription("New session in HOME").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("New session in Synthetic project").assertIsDisplayed().performClick()
        assertEquals(listOf("home", "create", "p-one", "create"), events)
    }

    // ── Chat header `+` ───────────────────────────────────────────────────────

    @Test
    fun `the chat header offers New session and reuses the create action`() {
        var created = 0
        mountChat(onCreateSession = { created += 1 })

        compose.onNodeWithTag(CHAT_HEADER_NEW_SESSION_TAG)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("New session")
            .performClick()

        assertEquals(1, created)
    }

    /**
     * The chat pane's strip `+` is present whenever the pane is — Desktop's main
     * workspace pane cannot be closed, so its strip never loses the control. A
     * chat with nothing homed yet is exactly when a person needs it.
     */
    @Test
    fun `the chat header add control survives an empty chat`() {
        mountChat(activeSession = false)

        compose.onNodeWithTag(CHAT_HEADER_NEW_SESSION_TAG).assertIsDisplayed()
    }

    /**
     * One action, one door per surface. The compact layout composes the closed
     * drawer off-screen, so the session-pane doors are in the tree at negative
     * x — the claim is not "one node in the tree" but "no surface silently
     * gained a second control", which each tag proves for its own surface.
     */
    @Test
    fun `each surface offers the create action exactly once`() {
        mountChat()

        compose.onAllNodesWithTag(CHAT_HEADER_NEW_SESSION_TAG).assertCountEquals(1)
        compose.onAllNodesWithTag(SIDEBAR_HEADER_ADD_TAG).assertCountEquals(1)
        compose.onAllNodesWithTag("sidebar-action-new-session").assertCountEquals(1)
        compose.onNodeWithTag(CHAT_HEADER_NEW_SESSION_TAG).assertContentDescriptionEquals("New session")
        compose.onNodeWithTag(SIDEBAR_HEADER_ADD_TAG).assertContentDescriptionEquals("New session")
    }

    @Test
    fun `the chat header add control is inert while no Gateway is connected`() {
        var created = 0
        mountChat(connected = false, onCreateSession = { created += 1 })

        compose.onNodeWithTag(CHAT_HEADER_NEW_SESSION_TAG).assertIsDisplayed().assertIsNotEnabled().performClick()
        assertEquals(0, created)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    @Test
    @Config(sdk = [34], qualifiers = "w1000dp-h800dp")
    fun `the wide chat header uses the same create action`() {
        var created = 0
        mountChat(onCreateSession = { created += 1 })
        compose.onNodeWithTag(CHAT_HEADER_NEW_SESSION_TAG).assertIsDisplayed().performClick()
        assertEquals(1, created)
    }

    private fun mountSidebar(
        grouping: SidebarGrouping = SidebarGrouping.Date,
        projects: List<ProjectSummary> = emptyList(),
        projectsAvailable: Boolean? = null,
        selectedProject: ProjectSummary? = null,
        canCreate: Boolean = true,
        onCreate: () -> Unit = {},
        onSelectProject: (String) -> Unit = {},
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
                SessionList(
                    rows = listOf(SessionListRow.Row(session("session-a", "Synthetic session"))),
                    projects = projects,
                    projectsAvailable = projectsAvailable,
                    sidebarGrouping = grouping,
                    selectedProject = selectedProject,
                    projectLoading = false,
                    activeSessionId = null,
                    query = "",
                    canCreate = canCreate,
                    onQueryChange = {},
                    onSidebarGroupingChange = {},
                    onSelectProject = onSelectProject,
                    onExitProject = {},
                    onCreateProject = { _, _ -> },
                    onSelect = {},
                    onCreate = onCreate,
                    nowMillis = NOW,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun mountChat(
        connected: Boolean = true,
        activeSession: Boolean = true,
        onCreateSession: () -> Unit = {},
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        activeSession = if (activeSession) session("session-a", "Synthetic session") else null,
                        connection = GatewayConnectionState(
                            if (connected) GatewayConnectionStatus.Connected else GatewayConnectionStatus.Disconnected,
                        ),
                        nowMillis = NOW,
                    ),
                    actions = ChatActions(onCreateSession = onCreateSession),
                    onOpenSettings = {},
                    wideRailInsets = WindowInsets(0, 0, 0, 0),
                    imeInsets = WindowInsets(0, 0, 0, 0),
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
        status = SessionStatus.Idle,
    )

    private companion object {
        const val NOW = 1_755_600_000_000L
    }
}
