package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CronLauncherTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `compact chat forwards scheduled jobs and recent run content into its drawer`() {
        var opens = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                com.hermesagent.mobile.ui.chat.ChatScreen(
                    state = com.hermesagent.mobile.ui.chat.ChatUiState(),
                    actions = com.hermesagent.mobile.ui.ChatActions(), onOpenSettings = {},
                    onOpenCron = { opens++ },
                    cronSection = {
                        Box(Modifier.height(48.dp).testTag("cron-fixture-section")) { Text("Cron jobs") }
                    },
                )
            }
        }
        compose.onNodeWithTag("cron-fixture-section").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithTag("sidebar-action-scheduled-jobs").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("cron-fixture-section").assertExists()
        assertEquals(1, opens)
    }

    @Test
    fun `cron section yields to session search and project grouping`() {
        val query = mutableStateOf("")
        val grouping = mutableStateOf(SidebarGrouping.Date)
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                SessionList(
                    rows = emptyList(), projects = emptyList(), projectsAvailable = null,
                    sidebarGrouping = grouping.value, selectedProject = null,
                    projectLoading = false, activeSessionId = null, query = query.value, canCreate = false,
                    onQueryChange = {}, onSidebarGroupingChange = {}, onSelectProject = {},
                    onExitProject = {}, onCreateProject = { _, _ -> }, onSelect = {}, onCreate = {},
                    nowMillis = 1_700_000_000_000L, onOpenCron = {},
                    cronSection = { Text("Cron jobs", Modifier.testTag("cron-fixture-section")) },
                )
            }
        }
        compose.onNodeWithTag("cron-fixture-section").assertExists()
        compose.runOnIdle { query.value = "status" }
        compose.onNodeWithTag("cron-fixture-section").assertDoesNotExist()
        compose.runOnIdle { query.value = ""; grouping.value = SidebarGrouping.Project }
        compose.onNodeWithTag("cron-fixture-section").assertDoesNotExist()
    }

    @Test
    fun `scheduled jobs opens the core surface without a plugin contribution`() {
        var opens = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                SessionList(
                    rows = emptyList(), projects = emptyList(), projectsAvailable = null,
                    sidebarGrouping = SidebarGrouping.Date, selectedProject = null,
                    projectLoading = false, activeSessionId = null, query = "", canCreate = false,
                    onQueryChange = {}, onSidebarGroupingChange = {}, onSelectProject = {},
                    onExitProject = {}, onCreateProject = { _, _ -> }, onSelect = {}, onCreate = {},
                    nowMillis = 1_700_000_000_000L,
                    onOpenCron = { opens++ },
                    cronSection = {
                        Box(Modifier.height(48.dp).testTag("cron-fixture-section")) { Text("Cron jobs") }
                    },
                )
            }
        }
        compose.onNodeWithTag("sidebar-action-scheduled-jobs").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("cron-fixture-section").assertExists()
        assertEquals(1, opens)
    }
}
