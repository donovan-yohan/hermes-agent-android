package com.hermesagent.mobile.device

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose semantics actually arrive at the platform accessibility layer.
 *
 * This is the claim Robolectric structurally cannot make. Under Robolectric the
 * assertions read Compose's own semantics tree — the same data structure the
 * production code wrote. Here an accessibility service is really registered,
 * `AndroidComposeView`'s node provider really runs, and the assertions read the
 * `AccessibilityNodeInfo` tree the platform hands a screen reader, with bounds
 * in real screen pixels.
 *
 * It is still not TalkBack. Announcement order, focus traversal, gesture
 * navigation and spoken output stay on the physical device matrix (issue #72,
 * S39). What this proves is narrower and worth proving: nothing reachable
 * arrives unlabelled or too small once it has crossed into the platform's tree.
 */
@RunWith(AndroidJUnit4::class)
class PlatformAccessibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyReachableActionArrivesLabelledAndBigEnoughInThePlatformTree() {
        val automation = accessibilityBridge()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { HermesAppUnderTest() }
        compose.waitForIdle()

        // The bridge publishes on its own schedule once accessibility turns on,
        // so wait for a node this surface is known to own rather than guessing.
        compose.waitUntil(DeviceLane.PLATFORM_TIMEOUT_MILLIS) {
            automation.publishedNodes(context.packageName)
                .any { it.speakableText() == DeviceLane.OPEN_SETTINGS }
        }

        val reachable = automation.publishedNodes(context.packageName)
            .filter { it.isClickable && it.isVisibleToUser && it.isEnabled }
        assertTrue(
            "the platform tree published ${reachable.size} reachable actions; " +
                "expected at least the chat top bar and the composer",
            reachable.size >= 4,
        )

        val unlabelled = reachable.filter { it.speakableText() == null }
        assertTrue(
            "actions a screen reader would announce as nothing: ${unlabelled.map { it.identify() }}",
            unlabelled.isEmpty(),
        )

        val floorPx = DeviceLane.TOUCH_TARGET_DP * context.resources.displayMetrics.density
        val undersized = reachable.filter { node ->
            val bounds = node.screenBounds()
            bounds.height() + DeviceLane.TOLERANCE_PX < floorPx ||
                bounds.width() + DeviceLane.TOLERANCE_PX < floorPx
        }
        assertTrue(
            "actions the platform reports below ${DeviceLane.TOUCH_TARGET_DP} dp: " +
                undersized.joinToString { "${it.identify()} ${it.screenBounds()}" },
            undersized.isEmpty(),
        )
    }

    private fun AccessibilityNodeInfo.screenBounds(): Rect = Rect().also(::getBoundsInScreen)

    private fun AccessibilityNodeInfo.identify(): String =
        speakableText() ?: viewIdResourceName ?: className?.toString() ?: "unnamed node"
}
