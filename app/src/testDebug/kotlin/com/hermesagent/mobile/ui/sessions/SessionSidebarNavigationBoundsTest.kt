package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.plugins.Contribution
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression coverage for the real sidebar composition, not contribution data alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h320dp")
class SessionSidebarNavigationBoundsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `feature launchers precede sessions and gateway footer remains reachable after profile rail`() {
        var launcherClicks = 0
        var gatewayClicks = 0
        var newSessionClicks = 0
        val launcher = Contribution(
            id = "fixture-launcher",
            area = PluginAreas.SIDEBAR_NAV_AREA,
            order = 40,
            render = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag(LAUNCHER_TAG)
                        .clickable(role = Role.Button) { launcherClicks++ },
                ) { Text("Fixture launcher") }
            },
        )
        val sessions = (0 until 40).map { index ->
            SessionListRow.Row(
                SessionSummary(
                    id = "session-$index",
                    title = "Session $index",
                    preview = "",
                    lastActiveAtMillis = NOW,
                    status = SessionStatus.Idle,
                ),
            )
        }

        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                SessionList(
                    rows = sessions,
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
                    onCreate = { newSessionClicks++ },
                    sidebarNavigation = listOf(launcher, Contribution(
                        id = "fixture-bots-mode",
                        area = PluginAreas.SIDEBAR_NAV_AREA,
                        data = SidebarModeDestination(SidebarMode.Bots) {
                            Text("Fixture bot roster", modifier = Modifier.testTag("fixture-bots-roster"))
                        },
                    )),
                    header = {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag(GATEWAY_TAG)
                                .clickable(role = Role.Button) { gatewayClicks++ },
                        ) { Text("Gateway") }
                    },
                    profileRail = ProfileRailState(
                        profiles = listOf(com.hermesagent.mobile.data.profiles.HermesProfile("default", isDefault = true)),
                        loaded = true,
                    ),
                    profileRailActions = ProfileRailActions(),
                    nowMillis = NOW,
                )
            }
        }
        compose.waitForIdle()

        val coreRows = listOf("new-session", "capabilities", "messaging", "artifacts", "scheduled-jobs")
        val coreBounds = coreRows.map {
            compose.onNodeWithTag("sidebar-action-$it").getUnclippedBoundsInRoot()
        }
        coreBounds.zipWithNext().forEach { (before, after) ->
            assertTrue("Desktop core rows retain order without overlapping", before.bottom <= after.top)
        }
        coreRows.drop(1).forEach { compose.onNodeWithTag("sidebar-action-$it").assertIsNotEnabled() }
        compose.onNodeWithTag("sidebar-action-new-session").performClick()
        assertTrue("New session is a working action", newSessionClicks == 1)

        val launcherBounds = compose.onNodeWithTag(LAUNCHER_TAG).getUnclippedBoundsInRoot()
        val firstSessionBounds = compose.onNodeWithTag("Session row session-0").getUnclippedBoundsInRoot()
        compose.onNodeWithTag(GATEWAY_TAG).performScrollTo()
        val railBounds = compose.onNodeWithTag(PROFILE_RAIL_TAG).fetchSemanticsNode().boundsInRoot
        val gatewayBounds = compose.onNodeWithTag(GATEWAY_TAG).fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot

        assertTrue("feature launcher must be above the session list", launcherBounds.bottom <= firstSessionBounds.top)
        assertTrue("Gateway footer must follow the profile rail: rail=$railBounds gateway=$gatewayBounds", railBounds.bottom <= gatewayBounds.top)
        assertTrue("Gateway footer must be reachable inside the viewport with long sessions", gatewayBounds.bottom <= rootBounds.bottom)

        compose.onNodeWithTag(LAUNCHER_TAG).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(GATEWAY_TAG).performScrollTo().assertIsDisplayed().performClick()
        assertTrue("feature launcher click-through", launcherClicks == 1)
        assertTrue("Gateway footer click-through", gatewayClicks == 1)
        compose.onNodeWithContentDescription("SESSIONS").performScrollTo().assertIsSelected()
        compose.onNodeWithContentDescription("TERMINAL. WIP").assertIsNotEnabled()
        compose.onNodeWithContentDescription("BOTS").performClick().assertIsSelected()
        compose.onNodeWithTag("fixture-bots-roster").assertIsDisplayed()
        compose.onNodeWithTag("sidebar-mode-tabs").assertIsDisplayed()
        compose.onNodeWithTag(PROFILE_RAIL_TAG).assertIsDisplayed()
        compose.onNodeWithTag(GATEWAY_TAG).assertIsDisplayed().performClick()
        assertTrue("Bots retains a working Gateway footer", gatewayClicks == 2)
        compose.onNodeWithContentDescription("SESSIONS").performClick().assertIsSelected()
        compose.onNodeWithTag("fixture-bots-roster").assertDoesNotExist()
    }

    @Test
    fun `flat navigation selection follows the active route`() {
        val route = androidx.compose.runtime.mutableStateOf("groups:route")
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                androidx.compose.foundation.layout.Column {
                    listOf("groups:route" to "Group Chats", "kanban:route" to "Kanban").forEach { (id, label) ->
                        SidebarNavRow(
                            label = label,
                            icon = com.hermesagent.mobile.ui.common.HermesIcon.Checklist,
                            enabled = true,
                            onClick = { route.value = id },
                            testTag = id,
                            selected = route.value == id,
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("groups:route").assertIsSelected()
        compose.onNodeWithTag("kanban:route").assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithTag("groups:route").assertIsNotSelected()
    }

    private companion object {
        const val LAUNCHER_TAG = "Fixture sidebar launcher"
        const val GATEWAY_TAG = "Gateway footer"
        const val NOW = 1_700_000_000_000L
    }
}
