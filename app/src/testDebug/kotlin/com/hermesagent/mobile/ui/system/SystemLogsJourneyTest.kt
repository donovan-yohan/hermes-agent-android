package com.hermesagent.mobile.ui.system

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
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
class SystemLogsJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `file level and filter are usable selected tabs and local case insensitive search`() {
        var state by mutableStateOf(SystemLogsState(lines = listOf("INFO ready", "ERROR retry")))
        var refreshes = 0
        compose.setContent {
            HermesTheme(selection = AppearanceSelection()) {
                RecentLogsSection(state, SystemActions(
                    onLogFile = { state = state.copy(file = it) },
                    onLogLevel = { state = state.copy(level = it) },
                    onLogQuery = { state = state.copy(query = it) },
                    onLogsRefresh = { refreshes++ },
                ))
            }
        }
        compose.onNodeWithContentDescription("Log file: desktop").assertIsEnabled()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.onNodeWithText("Top of log").assertIsNotEnabled()
        compose.onNodeWithText("Bottom of log").assertIsNotEnabled()
        compose.onNodeWithText("Copy").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Level: error").performClick().assertIsSelected()
        compose.onNodeWithContentDescription(SystemCopy.LOG_SEARCH_PLACEHOLDER).performTextInput("ReTrY")
        compose.onNodeWithText("ERROR retry").assertExists()
        compose.onNodeWithText("INFO ready").assertDoesNotExist()
        compose.onNodeWithText("Previous match").assertIsNotEnabled()
        compose.onNodeWithText("Next match").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("INFO ready").assertExists()
        compose.onNodeWithText("Refresh").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun `loading empty failed retry and populated states do not masquerade as each other`() {
        var state by mutableStateOf(SystemLogsState(loading = true))
        compose.setContent { HermesTheme(selection = AppearanceSelection()) { RecentLogsSection(state, SystemActions()) } }
        compose.onNodeWithTag("system-logs-loading").assertExists()
        compose.onNodeWithTag(SYSTEM_NO_LOGS_TAG).assertDoesNotExist()
        compose.runOnIdle { state = SystemLogsState(error = SystemLogsController.LOAD_FAILED) }
        compose.onNodeWithTag("system-logs-error").assertExists()
        compose.onNodeWithText("Refresh").assertIsEnabled()
        compose.onNodeWithTag(SYSTEM_NO_LOGS_TAG).assertDoesNotExist()
        compose.runOnIdle { state = SystemLogsState() }
        compose.onNodeWithTag(SYSTEM_NO_LOGS_TAG).assertExists()
        compose.runOnIdle { state = SystemLogsState(lines = listOf("INFO ready")) }
        compose.onNodeWithText("INFO ready").assertExists()
        compose.onNodeWithTag(SYSTEM_NO_LOGS_TAG).assertDoesNotExist()
    }

    @Test fun `leaving screen revokes logs lifecycle while reentry resumes it`() {
        var shown by mutableStateOf(true)
        var starts = 0
        var stops = 0
        compose.setContent {
            HermesTheme(selection = AppearanceSelection()) {
                if (shown) SystemScreen(SystemUiState(), SystemActions(
                    onLogsResume = { starts++ }, onLogsPause = { stops++ },
                ))
            }
        }
        compose.runOnIdle { assertEquals(1, starts); shown = false }
        compose.runOnIdle { assertEquals(1, stops); shown = true }
        compose.runOnIdle { assertEquals(2, starts) }
    }
}
