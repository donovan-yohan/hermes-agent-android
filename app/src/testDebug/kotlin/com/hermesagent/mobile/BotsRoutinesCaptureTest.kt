package com.hermesagent.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.hermesagent.mobile.plugins.bots.BotsRoutinesCopy
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.TimeZone

/** These are the exact production-component states the CI capture activity renders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsRoutinesCaptureTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var scope: CoroutineScope
    private val locale = Locale.getDefault()
    private val zone = TimeZone.getDefault()

    @Before fun setup() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    @After fun cleanup() {
        scope.cancel()
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }
    private fun show(state: BotsRoutinesFixtureState) {
        compose.setContent {
            HermesTheme(AppearanceSelection()) { BotsRoutinesParityFixture(state, scope) }
        }
        compose.waitForIdle()
    }

    @Test fun completedInspectorIsTerminalAndDoesNotInventAnotherRun() {
        show(BotsRoutinesFixtureState.parse("completed"))
        compose.onNodeWithText("What this job runs, and when it runs next.").assertIsDisplayed()
        compose.onNodeWithText("Completed").assertIsDisplayed()
        compose.onNodeWithText("Succeeded").assertIsDisplayed()
        compose.onAllNodesWithText("Next run").assertCountEquals(0)
        compose.onAllNodesWithText("Overdue since").assertCountEquals(0)
    }

    @Test fun sparseInspectorOmitsAbsentFields() {
        show(BotsRoutinesFixtureState.parse("sparse"))
        compose.onNodeWithText("What this job runs, and when it runs next.").assertIsDisplayed()
        for (label in listOf("Instruction", "Last run", "Last result", "Model", "Deliver to", "Working directory")) {
            compose.onAllNodesWithText(label).assertCountEquals(0)
        }
    }

    @Test fun pausedInspectorShowsReasonButNoNextRun() {
        show(BotsRoutinesFixtureState.parse("paused"))
        compose.onNodeWithText("synthetic fixture reason").assertIsDisplayed()
        compose.onAllNodesWithText("Next run").assertCountEquals(0)
    }

    @Test fun overdueInspectorShowsStoredSlotAndExactDeliveryResult() {
        show(BotsRoutinesFixtureState.parse("overdue-inspector"))
        compose.onNodeWithText("Overdue since").assertIsDisplayed()
        compose.onNodeWithText("Ran, but delivery failed").assertIsDisplayed()
    }

    @Test fun captureFormattingDoesNotDependOnDeviceDefaults() {
        Locale.setDefault(Locale.FRANCE)
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        show(BotsRoutinesFixtureState.Inspector)
        compose.onNodeWithText("in 17 hr · 9/18/26, 9:00 AM").assertIsDisplayed()
        org.junit.Assert.assertEquals(Locale.FRANCE, Locale.getDefault())
        org.junit.Assert.assertEquals("America/New_York", TimeZone.getDefault().id)
    }

    @Test fun readFailureExercisesRealRefusal() {
        show(BotsRoutinesFixtureState.ReadFailure)
        compose.onNodeWithText("The list may still be there — this was a read failure, not a delete.").assertIsDisplayed()
    }

    @Test fun pendingPauseUsesRealOptimisticRow() {
        show(BotsRoutinesFixtureState.PausePending)
        compose.onAllNodesWithContentDescription(BotsRoutinesCopy.RESUME_CRON)[0].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription(BotsRoutinesCopy.RESUME_CRON)[1].assertIsEnabled()
        compose.onAllNodesWithContentDescription(BotsRoutinesCopy.DELETE)[0].assertIsNotEnabled()
    }

    @Test fun refusedActionRollsBackAndKeepsSafeFailure() {
        show(BotsRoutinesFixtureState.ActionRollback)
        compose.onNodeWithText(BotsRoutinesCopy.FAILED_UPDATE).assertIsDisplayed()
        compose.onNodeWithText(BotsRoutinesCopy.STALE_NOTICE).assertIsDisplayed()
        compose.onNodeWithText("Next: in 17 hr").assertIsDisplayed()
    }

    @Test fun resumeReconcilesAuthoritativeActiveState() {
        show(BotsRoutinesFixtureState.Resumed)
        compose.onNodeWithText("Nightly sweep").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(BotsRoutinesCopy.PAUSE_CRON).assertCountEquals(3)
        compose.onAllNodesWithContentDescription(BotsRoutinesCopy.RESUME_CRON).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(BotsRoutinesCopy.RESUME_CRON)[0].assertIsNotEnabled()
    }

    @Test fun deleteReconcilesWithoutInventingAConfirmationState() {
        show(BotsRoutinesFixtureState.Deleted)
        compose.onAllNodesWithText("Morning digest").assertCountEquals(0)
        compose.onNodeWithText("Nightly sweep").assertIsDisplayed()
        compose.onAllNodesWithText("Confirm").assertCountEquals(0)
    }
}
