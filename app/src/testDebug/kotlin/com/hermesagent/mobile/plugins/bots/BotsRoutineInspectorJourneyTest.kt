package com.hermesagent.mobile.plugins.bots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsRoutineInspectorJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleOpensReadOnlyInspectorButSiblingControlsDoNot() {
        val job = (parseRoutineJobs(Json.parseToJsonElement("""{"jobs":[{"job_id":"one","name":"Morning","schedule":"every 1440m","prompt_preview":"Brief preview","prompt":"Never render full prompt","next_run_at":"2026-01-01T00:00:00Z"}]}""")) as RoutineJobsParse.Answered).jobs.single()
        var state by mutableStateOf(BotsRoutinesUiState(owner = "alpha", scoped = "alpha", phase = BotsRoutinesPhase.Ready, connectionUp = true, jobs = listOf(job)))
        var opened = 0
        val mutations = mutableListOf<RoutineAction>()
        compose.setContent {
            HermesTheme(AppearanceSelection("mono")) {
                BotsRoutinesScreen(state, {}, nowMillis = job.nextRunMillis!! + 900_001,
                    actions = BotsRoutinesActions(
                        onOpenInspector = { opened++; state = state.copy(inspectorTarget = it) },
                        onCloseInspector = { state = state.copy(inspectorTarget = null) },
                        onAction = { _, action -> mutations += action },
                    ))
            }
        }
        compose.onNodeWithText("Overdue since", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(BotsRoutinesCopy.PAUSE_CRON).performClick()
        compose.onNodeWithContentDescription(BotsRoutinesCopy.DELETE).performClick()
        compose.runOnIdle {
            assertEquals(0, opened)
            assertEquals(listOf(RoutineAction.Pause, RoutineAction.Remove), mutations)
        }
        compose.onNodeWithText("Morning").performClick()
        compose.onNodeWithTag("Routine inspector").assertExists()
        compose.onNodeWithText("Brief preview").performScrollTo()
        compose.onNodeWithText("Brief preview").assertIsDisplayed()
        compose.onNodeWithText("Never render full prompt").assertDoesNotExist()
        compose.onNodeWithText("Close").performScrollTo().performClick()
        compose.onNodeWithTag("Routine inspector").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
