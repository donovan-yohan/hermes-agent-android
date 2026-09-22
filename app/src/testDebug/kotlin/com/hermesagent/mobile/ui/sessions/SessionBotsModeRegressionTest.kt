package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.plugins.Contribution
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.bots.BotRosterRow
import com.hermesagent.mobile.plugins.bots.BotSectionBlock
import com.hermesagent.mobile.plugins.bots.BotsRosterPhase
import com.hermesagent.mobile.plugins.bots.BotsRosterScreen
import com.hermesagent.mobile.plugins.bots.BotsRosterUiState
import com.hermesagent.mobile.plugins.bots.RosterPresentationState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp")
class SessionBotsModeRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `bots is disabled without a typed destination and removal resets selection`() {
        val available = mutableStateOf(false)
        compose.setContent { Sidebar(available.value, 600.dp) }
        compose.onNodeWithContentDescription("BOTS").assertIsNotEnabled()
        compose.onNodeWithContentDescription("SESSIONS").assertIsSelected()
        compose.runOnIdle { available.value = true }
        compose.onNodeWithContentDescription("BOTS").assertIsEnabled().performClick().assertIsSelected()
        compose.runOnIdle { available.value = false }
        compose.onNodeWithContentDescription("BOTS").assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithContentDescription("SESSIONS").assertIsSelected()
        compose.runOnIdle { available.value = true }
        compose.onNodeWithContentDescription("SESSIONS").assertIsSelected()
        compose.onNodeWithTag("bot-roster").assertDoesNotExist()
    }

    @Test fun `bots roster and footer remain reachable after viewport shrinks`() {
        val height = mutableStateOf(600.dp)
        compose.setContent { Sidebar(true, height.value) }
        compose.onNodeWithContentDescription("BOTS").performClick()
        compose.runOnIdle { height.value = 120.dp }
        compose.onNodeWithTag("bot-roster").performScrollTo()
        compose.onNodeWithTag("bot-0").assertIsDisplayed()
        compose.onNodeWithTag("bot-roster").performScrollToIndex(39)
        compose.onNodeWithTag("bot-roster").performTouchInput { swipeUp() }
        compose.onNodeWithTag("bot-39").assertIsDisplayed()
        compose.onNodeWithTag("gateway-footer").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(PROFILE_RAIL_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("SESSIONS").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun `embedded roster search and filters cannot starve its rows`() {
        compose.setContent { Sidebar(true, 120.dp, realRoster = true) }
        compose.onNodeWithContentDescription("BOTS").performClick()
        compose.onNodeWithTag("real-roster").performScrollTo()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(39)
        compose.onNodeWithTag("real-roster").performTouchInput { swipeUp() }
        compose.onNodeWithText("Bot 39").assertIsDisplayed()
        compose.onNodeWithTag("gateway-footer").performScrollTo().assertIsDisplayed()
    }

    @Composable private fun Sidebar(available: Boolean, height: Dp, realRoster: Boolean = false) {
        HermesTheme(AppearanceSelection()) {
            Box(Modifier.height(height)) {
                SessionList(
                    rows = emptyList(), projects = emptyList(), projectsAvailable = null,
                    sidebarGrouping = SidebarGrouping.Date, selectedProject = null,
                    projectLoading = false, activeSessionId = null, query = "", canCreate = true,
                    onQueryChange = {}, onSidebarGroupingChange = {}, onSelectProject = {},
                    onExitProject = {}, onCreateProject = { _, _ -> }, onSelect = {}, onCreate = {},
                    sidebarNavigation = listOf(Contribution(
                        id = "bots", area = PluginAreas.SIDEBAR_NAV_AREA,
                        data = if (available) SidebarModeDestination(SidebarMode.Bots) {
                            if (realRoster) {
                                BotsRosterScreen(
                                    state = BotsRosterUiState(
                                        phase = BotsRosterPhase.Ready,
                                        sections = listOf(BotSectionBlock(null, "unassigned", "", (0 until 40).map {
                                            BotRosterRow(name = "bot-$it", displayName = "Bot $it")
                                        })),
                                        presentation = RosterPresentationState(showRosterSearch = true, showRosterFilters = true),
                                    ),
                                    onBack = {}, embedded = true,
                                    modifier = Modifier.testTag("real-roster"),
                                )
                            } else LazyColumn(Modifier.fillMaxSize().testTag("bot-roster")) {
                                items(40) { Text("Bot $it", Modifier.height(48.dp).testTag("bot-$it")) }
                            }
                        } else "not a typed destination",
                    )),
                    header = { Text("Gateway", Modifier.height(48.dp).testTag("gateway-footer")) },
                    profileRail = ProfileRailState(
                        profiles = listOf(com.hermesagent.mobile.data.profiles.HermesProfile("default", isDefault = true)),
                        loaded = true,
                    ),
                    nowMillis = 1_700_000_000_000L,
                )
            }
        }
    }
}
