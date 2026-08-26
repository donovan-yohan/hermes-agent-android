package com.hermesagent.mobile.device

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the user was survives the composition being thrown away.
 *
 * The composition is rebuilt from saved state alone: nothing retained is
 * available to cover for a value that was never saved, which is the shape of a
 * process death. [ActivityRecreateTest] covers the other half — a real Activity
 * destroy and rebuild, with real `Bundle` parceling.
 *
 * Neither is a system-initiated process kill. The OS reclaiming the app under
 * memory pressure, and everything a cold restart then has to re-acquire, stays
 * on the physical device matrix (issue #72, S39).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RestoreTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun theOpenDestinationSurvivesSavedStateRestoreAlone() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HermesAppUnderTest() }

        compose.onNodeWithContentDescription(DeviceLane.OPEN_SETTINGS).performClick()
        compose.waitUntilAtLeastOneExists(
            hasTestTag(DeviceLane.GATEWAYS_ROW),
            DeviceLane.PLATFORM_TIMEOUT_MILLIS,
        )

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithTag(DeviceLane.GATEWAYS_ROW).assertIsDisplayed()
        // Falling back to chat would also "work" without restoring anything.
        compose.onNodeWithContentDescription(DeviceLane.COMPOSER_FIELD).assertDoesNotExist()
    }
}
