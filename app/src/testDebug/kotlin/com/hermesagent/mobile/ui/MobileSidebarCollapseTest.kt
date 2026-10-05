package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.ui.gateway.ConnectionsUiState
import com.hermesagent.mobile.ui.sessions.ConnectionSwitcherBar
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.plugins.Contribution
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.sessions.SidebarNavRow
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileSidebarCollapseTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var groupClicks = 0
    private var kanbanClicks = 0
    private var createClicks = 0
    private var selectedProject: String? = null

    @Test fun `real drawer defaults collapsed and preserves primary header and footer`() {
        launch()
        open()
        compose.onNodeWithTag("sidebar-action-new-session").assertIsDisplayed().performClick()
        assertEquals(1, createClicks)
        open()
        compose.onNodeWithTag("sidebar-action-capabilities").assertDoesNotExist()
        compose.onNodeWithText("More").assertIsDisplayed()
        compose.onNodeWithContentDescription("New project").assertIsDisplayed()
        compose.onNodeWithContentDescription("Filters").assertIsDisplayed()
        compose.onNodeWithTag("fixture-footer").assertIsDisplayed()
        compose.onNodeWithTag("Project list").assertIsDisplayed()
        compose.runOnIdle { saveNativeCapture(compose.activity, "drawer-collapsed.png") }
    }

    @Test fun `expanded drawer retains action order WIP and callbacks and project scroll`() {
        launch(); open()
        compose.onNodeWithText("More").performClick()
        val tags = listOf("sidebar-action-capabilities", "sidebar-action-messaging", "sidebar-action-artifacts", "sidebar-action-scheduled-jobs", "fixture-groups", "fixture-kanban")
        compose.runOnIdle { saveNativeCapture(compose.activity, "drawer-expanded.png") }
        val bounds = tags.map { compose.onNodeWithTag(it).getUnclippedBoundsInRoot() }
        bounds.zipWithNext().forEach { (a, b) -> assertTrue(a.bottom <= b.top) }
        tags.take(4).forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.onNodeWithTag("fixture-groups").performScrollTo().performClick()
        assertEquals(1, groupClicks)
        compose.onNodeWithTag("fixture-kanban").performScrollTo().performClick()
        assertEquals(1, kanbanClicks)
        compose.onNodeWithTag("Project list").performScrollToNode(hasText("Project 29"))
        compose.onNodeWithText("Project 29").performClick()
        assertEquals("p29", selectedProject)
        compose.onNodeWithText("Less").assertIsDisplayed().performClick()
        compose.onNodeWithTag("fixture-groups").assertDoesNotExist()
    }

    @Test fun `expansion survives drawer reopen and saveable restoration`() {
        val restoration = StateRestorationTester(compose)
        launch(restoration); open()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag("sidebar-action-new-session").performClick()
        open()
        compose.onNodeWithText("Less").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        openIfClosed()
        compose.onNodeWithText("Less").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w360dp-h400dp")
    fun `small drawer keeps bounded project scroll and bottom controls in viewport`() {
        launch(); open()
        compose.onNodeWithText("More").performClick()
        val list = compose.onNodeWithTag("Project list").fetchSemanticsNode().boundsInRoot
        assertTrue("project viewport must not measure at zero", list.height > 48f * compose.density.density)
        compose.runOnIdle { saveNativeCapture(compose.activity, "drawer-small-expanded.png") }
        compose.onNodeWithTag("fixture-footer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Filters").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("Project list").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("Project list").performScrollToNode(hasText("Project 29"))
        compose.onNodeWithText("Project 29").performClick()
        assertEquals("p29", selectedProject)
    }

    @Test @Config(qualifiers = "w360dp-h280dp")
    fun `short drawer still reaches contributed actions and their callbacks`() {
        launch(realFooter = true); open()
        compose.onNodeWithText("More").assertIsDisplayed().performClick()
        compose.onNodeWithTag("fixture-groups").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, groupClicks)
        compose.onNodeWithTag("fixture-kanban").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, kanbanClicks)
        compose.runOnIdle { saveNativeCapture(compose.activity, "drawer-short-actions.png") }
        compose.onNodeWithTag("fixture-footer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("Project list").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("Project list").performScrollToNode(hasText("Project 29"))
        compose.onNodeWithText("Project 29").assertIsDisplayed().performClick()
        assertEquals("p29", selectedProject)
    }

    @Test @Config(qualifiers = "w360dp-h400dp")
    fun `expanded search reserves a usable project viewport with real footer`() {
        verifySearchBudget(selected = false, fontScale = 1f)
    }

    @Test @Config(qualifiers = "w360dp-h400dp")
    fun `expanded selected project search reserves a usable session viewport`() {
        verifySearchBudget(selected = true, fontScale = 1f)
    }

    @Test @Config(qualifiers = "w360dp-h400dp")
    fun `large font expanded project search retains navigation and footer`() {
        verifySearchBudget(selected = false, fontScale = 2f)
    }

    @Test @Config(qualifiers = "w360dp-h400dp")
    fun `large font expanded selected search retains navigation and footer`() {
        verifySearchBudget(selected = true, fontScale = 2f)
    }

    private fun verifySearchBudget(selected: Boolean, fontScale: Float) {
        launch(realFooter = true, searching = true, entered = selected, fontScale = fontScale)
        open()
        compose.onNodeWithText("More").performClick()
        val listTag = if (selected) "Session list" else "Project list"
        val list = compose.onNodeWithTag(listTag)
        val bounds = list.getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue("reserve at least two touch targets for navigation, got $height", height >= 96.dp)
        list.performScrollTo().assertIsDisplayed()
        compose.runOnIdle { saveNativeCapture(compose.activity, "drawer-search-$selected-$fontScale.png") }
        list.performScrollToNode(hasText(if (selected) "Session 29" else "Project 29"))
        compose.onNodeWithText(if (selected) "Session 29" else "Project 29").assertIsDisplayed().performClick()
        if (selected) assertEquals("s29", selectedProject) else assertEquals("p29", selectedProject)
        if (selected) open() // Selecting a session closes the real drawer.
        // Bring the bounded auxiliary scroller into the outer pane first.
        compose.onNodeWithText("Less").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fixture-groups").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, groupClicks)
        compose.onNodeWithTag("fixture-kanban").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, kanbanClicks)
        compose.onNodeWithContentDescription("Filters").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("sidebar-header-add").assertIsDisplayed()
        if (selected) compose.onNodeWithContentDescription("All projects").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Manage profiles…").performScrollTo().assertIsDisplayed()
        val profileBounds = compose.onNodeWithContentDescription("Manage profiles…").getUnclippedBoundsInRoot()
        assertTrue(profileBounds.bottom - profileBounds.top >= 48.dp)
        compose.onNodeWithTag("fixture-footer").performScrollTo().assertIsDisplayed()
        val footerBounds = compose.onNodeWithTag("fixture-footer").getUnclippedBoundsInRoot()
        assertTrue(footerBounds.bottom - footerBounds.top >= 48.dp)
        compose.onNodeWithContentDescription("Registered gateways: Fixture A").performClick()
        compose.onNodeWithTag("Connection switcher sheet").assertIsDisplayed()
    }

    private fun open() { compose.onNodeWithContentDescription("Open sessions").performClick(); compose.waitForIdle() }
    private fun openIfClosed() {
        if (compose.onAllNodes(hasText("Less")).fetchSemanticsNodes().isEmpty()) open()
    }
    private fun launch(restoration: StateRestorationTester? = null, realFooter: Boolean = false, searching: Boolean = false, entered: Boolean = false, fontScale: Float = 1f) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            HermesTheme(AppearanceSelection()) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                ChatScreen(
                    state = ChatUiState(projects = (0..29).map { ProjectSummary(id = "p$it", label = "Project $it", path = null) }, projectsAvailable = true, sidebarGrouping = SidebarGrouping.Project,
                        query = if (searching) "Project" else "",
                        selectedProject = if (entered) ProjectSummary(id = "p0", label = "Project 0", path = null) else null,
                        sessionRows = if (entered) (0..29).map { SessionListRow.Row(SessionSummary("s$it", "Session $it", "Synthetic preview", 1_700_000_000_000L)) } else emptyList(),
                        nowMillis = 1_700_000_000_000L,
                        profileRail = com.hermesagent.mobile.ui.sessions.ProfileRailState(
                            profiles = listOf(com.hermesagent.mobile.data.profiles.HermesProfile("default", isDefault = true)), loaded = true),
                        connection = com.hermesagent.mobile.data.gateway.GatewayConnectionState(status = com.hermesagent.mobile.data.gateway.GatewayConnectionStatus.Connected)),
                    actions = ChatActions(onCreateSession = { createClicks++ }, onSelectProject = { selectedProject = it }, onSelectSession = { selectedProject = it }),
                    onOpenSettings = {},
                    sidebarHeader = {
                        if (realFooter) ConnectionSwitcherBar(
                            state = ConnectionsUiState(connections = listOf(SavedConnection("a", "Fixture A", ConnectionKind.Remote), SavedConnection("b", "Fixture B", ConnectionKind.Remote)), activeId = "a", loaded = true),
                            actions = ConnectionsActions(), onManage = {}, modifier = Modifier.testTag("fixture-footer"),
                        ) else Box(Modifier.height(48.dp).fillMaxWidth().testTag("fixture-footer")) { androidx.compose.material3.Text("Gateway controls") }
                    },
                    sidebarNavigation = listOf(
                        Contribution(id = "groups", area = PluginAreas.SIDEBAR_NAV_AREA, order = 40, render = { SidebarNavRow("Group Chats", HermesIcon.Comment, true, { groupClicks++ }, "fixture-groups") }),
                        Contribution(id = "kanban", area = PluginAreas.SIDEBAR_NAV_AREA, order = 50, render = { SidebarNavRow("Kanban", HermesIcon.Checklist, true, { kanbanClicks++ }, "fixture-kanban") }),
                    ),
                )
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }
}
