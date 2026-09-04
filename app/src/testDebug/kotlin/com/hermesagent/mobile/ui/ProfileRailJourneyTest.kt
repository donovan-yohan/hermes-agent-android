package com.hermesagent.mobile.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.ProfileRouting
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.data.profiles.ProfileRepository
import com.hermesagent.mobile.data.prefs.TransientProfileScopeStore
import com.hermesagent.mobile.data.profiles.ProfileRosterState
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.sessions.ProfileRail
import com.hermesagent.mobile.ui.sessions.ProfileRailActions
import com.hermesagent.mobile.ui.sessions.ProfileRailState
import com.hermesagent.mobile.ui.sessions.profilePickerRowTag
import com.hermesagent.mobile.ui.sessions.PROJECT_PROFILE_SCOPE_NOTE
import com.hermesagent.mobile.ui.relay.RelayUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The profile rail as a reader meets it: at the sidebar foot, switching scope,
 * tagging rows in the unified view, and opening the read-only roster.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ProfileRailJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private val cache = SessionCache()
    private lateinit var repository: RailRepository
    private lateinit var viewModel: ChatViewModel

    @Test
    fun `the rail pins default and manage around the named profiles`() {
        launch()
        openSessions()

        compose.onNodeWithContentDescription("Show all profiles").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to work").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to Lab bench").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage profiles…").assertIsDisplayed()
    }

    @Test
    fun `every rail control is a full touch target`() {
        launch()
        openSessions()

        for (label in listOf("Show all profiles", "Switch to work", "Manage profiles…")) {
            compose.onNodeWithContentDescription(label)
                .assertHeightIsAtLeast(HermesSpacing().touchTarget)
                .assertWidthIsAtLeast(HermesSpacing().touchTarget)
        }
    }

    @Test
    fun `switching profile scopes the session list`() {
        launch()
        openSessions()
        assertEquals(1, compose.rows("home-row"))
        assertEquals(0, compose.rows("work-row"))

        compose.onNodeWithContentDescription("Switch to work").performClick()
        compose.waitForIdle()

        assertEquals(1, compose.rows("work-row"))
        assertEquals(0, compose.rows("home-row"))
        assertEquals("work", repository.routing.activeProfile)
    }

    @Test
    fun `the unified view tags every row with its owning profile`() {
        launch()
        openSessions()
        assertEquals(0, compose.nodesWithDescription("Profile: work"))

        compose.onNodeWithContentDescription("Show all profiles").performClick()
        compose.waitForIdle()

        assertEquals(1, compose.rows("home-row"))
        assertEquals(1, compose.rows("work-row"))
        assertEquals(1, compose.nodesWithDescription("Profile: work"))
        assertEquals(1, compose.nodesWithDescription("Profile: default"))
    }

    @Test
    fun `a single-profile scope hides the owning-profile tag`() {
        launch()
        openSessions()
        compose.onNodeWithContentDescription("Show all profiles").performClick()
        compose.waitForIdle()
        assertEquals(1, compose.nodesWithDescription("Profile: work"))

        // The left pill reads "layers" in the unified view; returning to the
        // default profile is what takes the tags away again.
        compose.onNodeWithContentDescription("Switch to default").performClick()
        compose.waitForIdle()

        assertEquals(0, compose.nodesWithDescription("Profile: work"))
    }

    @Test
    fun `switching scope leaves another profile's running turn alone`() {
        launch()
        cache.upsertSession(requireNotNull(cache.session("home-row")).copy(status = SessionStatus.Working))
        compose.waitForIdle()
        openSessions()

        compose.onNodeWithContentDescription("Switch to work").performClick()
        compose.waitForIdle()

        assertEquals(emptyList<String>(), repository.interrupted)
        assertEquals(SessionStatus.Working, cache.session("home-row")?.status)
        // The running turn's own transcript is not painted into the new scope.
        assertEquals(0, compose.countWithText("Home reply"))
        assertEquals(0, compose.rows("home-row"))
    }

    @Test
    fun `manage profiles opens the read-only roster`() {
        launch()
        openSessions()

        compose.onNodeWithContentDescription("Manage profiles…").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("3 profiles").assertIsDisplayed()
        assertTrue(compose.countWithText("Lab bench") >= 1)
        assertEquals(1, compose.countWithText("Search profiles..."))
        compose.onNodeWithText("Default").assertIsDisplayed()
        compose.onNodeWithText("Model").assertIsDisplayed()
        compose.onNodeWithText("Skills").assertIsDisplayed()

        compose.onNodeWithText("Lab bench").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not set").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Open sessions").assertIsDisplayed()
    }

    @Test
    fun `the last profile that fits still gets its own square`() {
        launch(profiles = teamRoster(RAIL_CAPACITY))
        openSessions()

        assertEquals(RAIL_CAPACITY, compose.squares())
        assertEquals(0, compose.nodesWithDescription("Profiles"))
    }

    @Test
    fun `one profile past the budget collapses the whole strip to a picker sheet`() {
        launch(profiles = teamRoster(RAIL_CAPACITY + 1))
        openSessions()

        assertEquals(0, compose.squares())
        compose.onNodeWithContentDescription("Profiles").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("team-4").performClick()
        compose.waitForIdle()

        assertEquals("team-4", repository.routing.activeProfile)
    }

    @Test
    fun `a persisted named scope keeps its way out when the roster never loads`() {
        // profiles.list is a slow-lane call that an older or refusing Gateway
        // may never answer. The scope is persisted, so without this the sidebar
        // opens inside a profile with no control to leave it and no route to
        // the roster.
        launch(profiles = emptyList(), rosterLoaded = false, scope = ProfileScope(activeProfile = "work"))
        openSessions()

        compose.onNodeWithContentDescription("Switch to default").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage profiles…").assertIsDisplayed()

        compose.onNodeWithContentDescription("Switch to default").performClick()
        compose.waitForIdle()

        assertNull(repository.routing.activeProfile)
    }

    /**
     * Every rail state that has no default profile to render must still render
     * the way out. Driven directly, because the ViewModel reconciles a scope an
     * answered roster does not contain and this state would not hold still.
     */
    @Test
    fun `no default profile to render still leaves the way out of a named scope`() {
        var rosterLoaded by mutableStateOf(false)
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ProfileRail(
                    state = ProfileRailState(
                        profiles = emptyList(),
                        scope = ProfileScope(activeProfile = "work"),
                        loaded = rosterLoaded,
                    ),
                    actions = ProfileRailActions(),
                )
            }
        }
        compose.waitForIdle()

        // The rail is visible and reserves a slot for this pill whether or not
        // the roster answered; a guard narrower than that reserves the slot and
        // renders nothing into it.
        compose.onNodeWithContentDescription("Switch to default").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage profiles…").assertIsDisplayed()

        rosterLoaded = true
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Switch to default").assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage profiles…").assertIsDisplayed()
    }

    @Test
    fun `nothing paints before the first answer in the Gateway's own scope`() {
        launch(profiles = emptyList(), rosterLoaded = false)
        openSessions()

        assertEquals(0, compose.nodesWithDescription("Manage profiles…"))
    }

    @Test
    fun `the unified view says the project catalog is one profile's`() {
        launch()
        openSessions()
        compose.onNodeWithContentDescription("Show all profiles").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Project").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(PROJECT_PROFILE_SCOPE_NOTE).assertIsDisplayed()
        // The catalog stays browsable in the unified view; only a named scope
        // has nothing there to browse.
        assertEquals(1, compose.countWithText("Hermes mobile"))
    }

    @Test
    fun `a named scope hides the catalog and says where it went`() {
        launch()
        openSessions()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Project").performClick()
        compose.waitForIdle()
        assertEquals(1, compose.countWithText("Hermes mobile"))

        compose.onNodeWithContentDescription("Switch to work").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(PROJECT_PROFILE_SCOPE_NOTE).assertIsDisplayed()
        assertEquals(0, compose.countWithText("Hermes mobile"))
    }

    /**
     * The reason the sheet carries the default row at all: the pill it
     * collapsed away from is a default-to-all toggle whose face reads the
     * *scope*, so from the unified view the only route home wears a `layers`
     * mark. Desktop's fleet groups head a gateway's list with its default agent
     * for the same reason (`profile-switcher.tsx:808-824` @ `3ca096de`).
     */
    @Test
    fun `the collapsed sheet heads its list with the default profile`() {
        launch(profiles = teamRoster(RAIL_CAPACITY + 1))
        openSessions()

        compose.onNodeWithContentDescription("Profiles").performClick()
        compose.waitForIdle()

        // `isDefault` is what gives the row the home mark rather than a tinted
        // initial (`ProfileGlyph`), and `ProfilePickerDefaultRowTest` pins that
        // flag; the glyph itself clears its own semantics, so what is assertable
        // here is the row, its place and its state.
        compose.onNodeWithTag(profilePickerRowTag("default")).assertIsDisplayed()
        compose.onNodeWithTag(profilePickerRowTag("default")).assertIsSelected()
        assertTrue(
            compose.onNodeWithTag(profilePickerRowTag("default")).fetchSemanticsNode().positionInRoot.y <
                compose.onNodeWithTag(profilePickerRowTag("team-1")).fetchSemanticsNode().positionInRoot.y,
        )
    }

    @Test
    fun `the sheet's default row leaves a named profile and closes`() {
        launch(profiles = teamRoster(RAIL_CAPACITY + 1), scope = ProfileScope(activeProfile = "team-2"))
        openSessions()

        compose.onNodeWithContentDescription("Profiles").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(profilePickerRowTag("default")).assertIsNotSelected()

        compose.onNodeWithTag(profilePickerRowTag("default")).performClick()
        compose.waitForIdle()

        // `default` is "whatever this Gateway launched with", so the session
        // RPCs carry no profile parameter again.
        assertNull(repository.routing.activeProfile)
        assertEquals(0, compose.onAllNodesWithTag(profilePickerRowTag("default")).fetchSemanticsNodes().size)
    }

    private fun launch(
        profiles: List<HermesProfile> = ROSTER,
        rosterLoaded: Boolean = true,
        scope: ProfileScope = ProfileScope(),
    ) {
        cache.upsertSessions(
            listOf(
                SessionSummary("home-row", "Home planning", "", NOW, remoteProfile = null),
                SessionSummary("work-row", "Work planning", "", NOW - 1_000, remoteProfile = "work"),
            ),
        )
        cache.setTranscript(
            "home-row",
            listOf(com.hermesagent.mobile.data.session.AssistantTurn("a1", "Home reply", NOW)),
        )
        cache.replaceProjectOverview(
            listOf(ProjectSummary("project-mobile", "Hermes mobile", "/work/mobile", sessionCount = 1)),
            activeProjectId = null,
        )
        repository = RailRepository()
        viewModel = ChatViewModel(
            cache = cache,
            repository = repository,
            profileScopeStore = TransientProfileScopeStore(scope),
            profileRepository = RailProfiles(profiles, rosterLoaded),
            clock = { NOW },
        )
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesApp(
                chatState = state,
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(
                    onSidebarGroupingChange = viewModel::setSidebarGrouping,
                    onSelectSession = viewModel::selectSession,
                    onSelectProfile = viewModel::selectProfile,
                    onShowAllProfiles = viewModel::showAllProfiles,
                ),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                relayState = RelayUiState(),
                relayActions = RelayActions(),
            )
        }
        compose.waitForIdle()
    }

    /** A default profile plus [named] named ones, so only the strip's budget varies. */
    private fun teamRoster(named: Int) = listOf(HermesProfile(name = "default", isDefault = true)) +
        (1..named).map { HermesProfile(name = "team-$it") }

    private fun openSessions() {
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.waitForIdle()
    }

    private class RailProfiles(rows: List<HermesProfile>, loaded: Boolean) : ProfileRepository {
        override val roster = MutableStateFlow(ProfileRosterState(rows, loaded = loaded))
        override suspend fun refreshProfiles(): Boolean = true
        override fun connectionChanged(state: com.hermesagent.mobile.data.profiles.GatewayProfileConnectionState) = Unit
    }

    private class RailRepository : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        var routing = ProfileRouting()
        val interrupted = mutableListOf<String>()

        override fun setProfileRouting(routing: ProfileRouting) {
            this.routing = routing
        }

        override suspend fun refreshSessions() = Unit
        override suspend fun openSession(durableId: String): String = durableId
        override suspend fun createSession(workspacePath: String?): String = "created"
        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome =
            GatewaySubmitOutcome.Accepted

        override suspend fun interrupt(durableId: String) {
            interrupted += durableId
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L

        /**
         * How many 48dp squares fit in the drawer at `w411dp` beside the two
         * pinned pills. The drawer is `min(360dp, width - 56dp)` = 355dp, less
         * 8dp of rail inset and two 48dp pills, over 48dp per square.
         */
        const val RAIL_CAPACITY = 5
        val ROSTER = listOf(
            HermesProfile(name = "default", isDefault = true, model = "a-model", provider = "a-provider", skillCount = 7),
            HermesProfile(name = "work", model = "b-model", skillCount = 2),
            HermesProfile(name = "lab", displayName = "Lab bench", skillCount = 0),
        )
    }
}

private fun ComposeContentTestRule.countWithText(text: String): Int =
    onAllNodes(hasText(text)).fetchSemanticsNodes().size

private fun ComposeContentTestRule.nodesWithDescription(text: String): Int =
    onAllNodesWithContentDescription(text).fetchSemanticsNodes().size

/** How many named-profile squares the strip actually rendered. */
private fun ComposeContentTestRule.squares(): Int =
    onAllNodesWithContentDescription("Switch to team-", substring = true).fetchSemanticsNodes().size

/** The sidebar row itself, so chrome that repeats a session title cannot count. */
private fun ComposeContentTestRule.rows(durableId: String): Int =
    onAllNodesWithTag("Session row $durableId").fetchSemanticsNodes().size
