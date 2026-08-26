package com.hermesagent.mobile.device

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every action a finger can reach is at least 48 dp, measured on the device.
 *
 * Robolectric measures against a density it was told to use. This measures
 * against the display the app is actually running on, at the device's own font
 * scale, and it reads `touchBoundsInRoot` rather than the drawn box — the touch
 * bounds are what the gesture system hit-tests, and they are where Compose's
 * minimum-interactive expansion shows up or fails to.
 *
 * The sweep is deliberately unnamed: a control added later is covered without
 * anyone remembering to add it here.
 */
@RunWith(AndroidJUnit4::class)
class TouchTargetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyOnScreenActionMeetsTheTouchFloorAtTheDeviceDensity() {
        launchChat()
        val floorPx = with(compose.density) { DeviceLane.TOUCH_TARGET_DP.dp.toPx() }

        val onScreen = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .filter { it.isOnTheRealWindow() }
        // A sweep that finds nothing proves nothing. Two top-bar controls, the
        // editor and the add control are unconditional on this surface, so the
        // floor is a fact rather than a guess.
        assertTrue(
            "expected the chat surface to expose actions to measure, found ${onScreen.size}",
            onScreen.size >= 4,
        )

        val undersized = onScreen.filter { node ->
            val bounds = node.touchBoundsInRoot
            bounds.height + DeviceLane.TOLERANCE_PX < floorPx ||
                bounds.width + DeviceLane.TOLERANCE_PX < floorPx
        }
        assertTrue(
            "actions below the ${DeviceLane.TOUCH_TARGET_DP} dp touch floor: " +
                undersized.joinToString { "${it.describe()} ${it.touchBoundsInRoot}" },
            undersized.isEmpty(),
        )
    }

    @Test
    fun theComposerAndChromeControlsKeepTheirOwnTouchFloor() {
        launchChat()
        val floorPx = with(compose.density) { DeviceLane.TOUCH_TARGET_DP.dp.toPx() }

        // Named separately because these three are disabled or inert while the
        // app is disconnected, and a disabled control is exempt from the sweep
        // above while still having to be reachable the moment it lights up.
        val named = listOf(DeviceLane.SEND, DeviceLane.OPEN_SETTINGS, DeviceLane.COMPOSER_FIELD)
        for (description in named) {
            val bounds = compose.onNodeWithContentDescription(description)
                .fetchSemanticsNode()
                .touchBoundsInRoot
            assertTrue(
                "$description is ${bounds.height} px tall, below the $floorPx px floor",
                bounds.height + DeviceLane.TOLERANCE_PX >= floorPx,
            )
        }
    }

    private fun launchChat() {
        compose.setContent { HermesAppUnderTest() }
        compose.waitForIdle()
    }

    /**
     * The closed navigation drawer is composed off the left edge, so its rows
     * are in the semantics tree without being reachable. Only what the window
     * actually shows is a touch target today.
     */
    private fun SemanticsNode.isOnTheRealWindow(): Boolean {
        val bounds = boundsInWindow
        return bounds.left >= 0f && bounds.top >= 0f && bounds.width > 0f && bounds.height > 0f
    }

    private fun SemanticsNode.describe(): String =
        config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: "node $id"
}
