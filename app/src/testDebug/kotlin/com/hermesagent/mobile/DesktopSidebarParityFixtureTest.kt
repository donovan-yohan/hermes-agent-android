package com.hermesagent.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.ui.sessions.SidebarMode
import com.hermesagent.mobile.ui.sessions.SidebarModeDestination
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

/** Debug fixture contract only; no live Gateway and no replacement production rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class DesktopSidebarParityFixtureTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun realPluginRegistrationsAreScopedAndDisposed() {
        compose.runOnIdle {
            val plugins = DesktopSidebarFixturePlugins()
            try {
                val rows = plugins.registry.getArea(PluginAreas.SIDEBAR_NAV_AREA)
                assertEquals(listOf("bots:sidebar-mode-bots", "groups:sidebar-nav", "kanban:sidebar-nav"), rows.map { it.id })
                assertEquals(listOf("plugin:bots", "plugin:groups", "plugin:kanban"), rows.map { it.source })
                assertEquals(SidebarMode.Bots, (rows.first().data as SidebarModeDestination).mode)
                assertTrue(rows.first().render == null)
                assertTrue(rows.drop(1).all { it.render != null })
            } finally {
                plugins.close()
            }
            assertTrue(plugins.registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).isEmpty())
            assertTrue(plugins.registry.getArea(PluginAreas.ROUTES_AREA).isEmpty())
        }
    }

    @Test
    fun darkSessionsNavigationUsesCoreRowsAndRealLauncherCallbacks() {
        mount(DesktopSidebarFixtureState.SessionsNavigation, HermesThemeMode.Dark)
        compose.onNodeWithContentDescription("SESSIONS").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithContentDescription("BOTS").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("TERMINAL. WIP").assertIsDisplayed().assertIsNotEnabled()
        val tags = listOf("new-session", "capabilities", "messaging", "artifacts", "scheduled-jobs", "group-chats", "kanban")
        val bounds = tags.map { tag ->
            compose.onNodeWithTag("sidebar-action-$tag").assertIsDisplayed().getUnclippedBoundsInRoot()
        }
        bounds.zipWithNext().forEach { (before, after) -> assertTrue(before.bottom <= after.top) }
        tags.subList(1, 5).forEach { compose.onNodeWithTag("sidebar-action-$it").assertIsNotEnabled() }
        val groups = compose.onNodeWithTag("sidebar-action-group-chats")
        val kanban = compose.onNodeWithTag("sidebar-action-kanban")
        groups.assertIsNotSelected().assertIsEnabled().performClick().assertIsSelected()
        kanban.assertIsNotSelected().assertIsEnabled().performClick().assertIsSelected()
        groups.assertIsNotSelected()
        compose.onNodeWithTag("sidebar-action-new-session").performClick()
        kanban.assertIsNotSelected()
        compose.onNodeWithContentDescription("Synthetic design review. Idle. Updated 12 minutes ago").assertIsDisplayed()
    }

    @Test
    fun lightSelectedStateHasOnlyGroupLauncherSelected() {
        mount(DesktopSidebarFixtureState.GroupsSelected, HermesThemeMode.Light)
        compose.onNodeWithContentDescription("SESSIONS").assertIsSelected()
        compose.onNodeWithTag("sidebar-action-group-chats").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("sidebar-action-kanban").assertIsNotSelected()
        compose.onNodeWithTag("Session row synthetic-sidebar-design").assertIsNotSelected()
        compose.onNodeWithTag("Session row synthetic-sidebar-release").assertIsNotSelected()
    }

    @Test
    fun stateParsingRejectsUncataloguedInput() {
        DesktopSidebarFixtureState.entries.forEach { assertEquals(it, DesktopSidebarFixtureState.parse(it.wireValue)) }
        listOf(null, "", "bots-ready").forEach { input ->
            assertTrue(runCatching { DesktopSidebarFixtureState.parse(input) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    private fun mount(state: DesktopSidebarFixtureState, theme: HermesThemeMode) {
        compose.setContent {
            HermesTheme(AppearanceSelection("mono", theme)) {
                DesktopSidebarParityFixture(state, Modifier.fillMaxSize())
            }
        }
    }
}
