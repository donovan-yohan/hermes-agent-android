package com.hermesagent.mobile.device

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesagent.mobile.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real rotation, through the real `Configuration`.
 *
 * Robolectric changes layout by being told a different qualifier string. Here
 * the window manager really re-lays-out the app: `MainActivity` declares
 * `configChanges` for orientation, so this exercises the production path where
 * the Activity survives and Compose re-measures, rather than a recreation the
 * app never actually performs.
 *
 * The chat surface answers width, not orientation, so the assertion is that the
 * rotated window really crosses the wide breakpoint and the layout really
 * changes with it — while the connection-state copy stays put.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OrientationTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun releaseOrientation() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Test
    fun rotatingKeepsTheConnectionCopyAndMovesToTheWideLayout() {
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        compose.onNodeWithText(DISCONNECTED).assertIsDisplayed()
        compose.onNodeWithContentDescription(DeviceLane.OPEN_SESSIONS).assertIsDisplayed()
        compose.onNodeWithTag(WIDE_RAIL).assertDoesNotExist()

        rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)

        // The wide branch is only reached above 720 dp. Failing loudly here beats
        // a quietly vacuous pass if the CI emulator profile ever changes.
        val widthDp = onMain { compose.activity.resources.configuration.screenWidthDp }
        assertTrue(
            "the rotated window is $widthDp dp wide; the wide chat layout needs 720 dp, " +
                "so the CI emulator profile must stay a phone-sized device in landscape",
            widthDp >= WIDE_BREAKPOINT_DP,
        )

        compose.waitUntilAtLeastOneExists(hasTestTag(WIDE_RAIL), DeviceLane.PLATFORM_TIMEOUT_MILLIS)
        compose.onNodeWithTag(WIDE_RAIL).assertIsDisplayed()
        // The persistent rail replaces the drawer affordance rather than joining it.
        compose.onNodeWithContentDescription(DeviceLane.OPEN_SESSIONS).assertDoesNotExist()
        compose.onNodeWithText(DISCONNECTED).assertIsDisplayed()
    }

    private fun rotateTo(requested: Int, expected: Int) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
        compose.waitUntil(DeviceLane.PLATFORM_TIMEOUT_MILLIS) {
            onMain { compose.activity.resources.configuration.orientation } == expected
        }
        compose.waitForIdle()
    }

    private companion object {
        const val DISCONNECTED = "Disconnected"
        const val WIDE_RAIL = "Wide sessions rail"
        const val WIDE_BREAKPOINT_DP = 720
    }
}
