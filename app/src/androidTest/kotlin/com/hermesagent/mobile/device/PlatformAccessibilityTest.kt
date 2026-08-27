package com.hermesagent.mobile.device

import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

        // The bridge publishes on its own schedule, and it has to be prompted:
        // see `invalidatePublishedNodes` for why Compose itself will not send
        // the notification that keeps a read fresh. Waiting for a node this
        // surface is known to own beats waiting for a count, because a count
        // that is one short cannot tell "not published yet" from "published,
        // and something is missing".
        //
        // That node also says which window this composition is in, and every
        // later read is scoped to it. Otherwise the sweep is package-wide: the
        // Activities the other classes in this run launched are the same
        // package, and a window the bridge has not finished dropping publishes
        // its own controls into a screen this test never rendered. The walk
        // reads the focused window first, so the id taken here is this rule's
        // own Activity whenever it holds focus, which is the precondition every
        // other assertion in this lane already depends on.
        var settingsWindowId: Int? = null
        try {
            compose.waitUntil(DeviceLane.PLATFORM_TIMEOUT_MILLIS) {
                invalidatePublishedNodes(compose.activity)
                settingsWindowId = automation.publishedNodes(context.packageName)
                    .firstOrNull { it.speakableText() == DeviceLane.OPEN_SETTINGS }
                    ?.windowId
                settingsWindowId != null
            }
        } catch (timeout: ComposeTimeoutException) {
            val published = automation.publishedNodes(context.packageName)
            fail(
                "the chat top bar's settings control never reached the platform tree for " +
                    "${context.packageName} (${timeout.message}); the tree held " +
                    "${published.size} nodes, announcing: " +
                    published.mapNotNull { it.speakableText() }.joinToString(),
            )
        }
        val windowUnderTest = checkNotNull(settingsWindowId) {
            "the settings control was published without a window id"
        }

        val reachable = automation.reachableNodes(context.packageName, windowUnderTest)
        assertTrue(
            "the platform tree published ${reachable.size} reachable actions, announcing " +
                "${reachable.mapNotNull { it.speakableText() }}; expected at least the chat " +
                "top bar and the composer",
            reachable.size >= EXPECTED_ACTIONS,
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
                undersized.joinToString { it.identify() },
            undersized.isEmpty(),
        )
    }

    private fun UiAutomation.reachableNodes(
        packageName: String,
        windowId: Int,
    ): List<AccessibilityNodeInfo> =
        publishedNodes(packageName, windowId)
            .filter { it.isClickable && it.isVisibleToUser && it.isEnabled }

    private fun AccessibilityNodeInfo.screenBounds(): Rect = Rect().also(::getBoundsInScreen)

    /**
     * Everything a failure needs to place a node without a rerun.
     *
     * A class name on its own says a node is unlabelled and nothing else — not
     * which window it came from, not where on the screen it is, not which view
     * it is if it is a view at all. Those three are what separate "this screen
     * has a real gap" from "this is somebody else's window", so the failure
     * message carries them rather than leaving them for the next cycle.
     */
    private fun AccessibilityNodeInfo.identify(): String = buildString {
        append(speakableText() ?: className?.toString() ?: "unnamed node")
        viewIdResourceName?.let { append(" id=$it") }
        append(" window=$windowId ")
        append(screenBounds().toShortString())
    }

    private companion object {
        /**
         * Two top-bar controls, the editor and the add control are unconditional
         * on this surface, so a sweep that finds fewer than four has found a
         * tree that is not this screen rather than a screen without actions.
         */
        const val EXPECTED_ACTIONS = 4
    }
}
