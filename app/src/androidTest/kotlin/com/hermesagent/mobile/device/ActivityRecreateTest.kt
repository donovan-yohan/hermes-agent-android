package com.hermesagent.mobile.device

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesagent.mobile.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Activity is destroyed and rebuilt, and the app comes back where it was.
 *
 * `ActivityScenario.recreate()` runs the real `onSaveInstanceState` /
 * `onCreate(savedInstanceState)` pair against the real framework, with real
 * `Bundle` parceling and a real `ViewModelStore` handover — against the real
 * `MainActivity`, so the whole production composition root is under test rather
 * than a stand-in.
 *
 * It is still not a system-initiated process kill. See [RestoreTest] for the
 * saved-state-only half, and the physical device matrix (issue #72, S39) for
 * the real thing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ActivityRecreateTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theOpenDestinationSurvivesARealActivityRecreate() {
        compose.waitUntilAtLeastOneExists(
            hasContentDescription(DeviceLane.OPEN_SETTINGS),
            DeviceLane.PLATFORM_TIMEOUT_MILLIS,
        )
        compose.onNodeWithContentDescription(DeviceLane.OPEN_SETTINGS).performClick()
        compose.waitUntilAtLeastOneExists(
            hasTestTag(DeviceLane.GATEWAYS_ROW),
            DeviceLane.PLATFORM_TIMEOUT_MILLIS,
        )

        compose.activityRule.scenario.recreate()
        compose.waitUntilAtLeastOneExists(
            hasTestTag(DeviceLane.GATEWAYS_ROW),
            DeviceLane.PLATFORM_TIMEOUT_MILLIS,
        )

        compose.onNodeWithTag(DeviceLane.GATEWAYS_ROW).assertIsDisplayed()
        // Falling back to chat would also "work" without restoring anything.
        compose.onNodeWithContentDescription(DeviceLane.COMPOSER_FIELD).assertDoesNotExist()
    }
}
