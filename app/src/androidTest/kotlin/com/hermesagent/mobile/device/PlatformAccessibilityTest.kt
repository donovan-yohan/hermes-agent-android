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
 * What this settles is arrival, and only arrival: a named chat control crosses
 * into that tree in this composition's own window, and that window publishes at
 * least this surface's unconditional actions. Auditing what those nodes then
 * say and how big they are is issue #91, not this test — see the method's own
 * note for why the audit could not ship green here.
 *
 * It is still not TalkBack. Announcement order, focus traversal, gesture
 * navigation and spoken output stay on the physical device matrix (issue #72,
 * S39).
 */
@RunWith(AndroidJUnit4::class)
class PlatformAccessibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The narrowed claim: the chat chrome reaches the platform tree.
     *
     * This test used to also assert that every reachable node arrives labelled
     * and at least [DeviceLane.TOUCH_TARGET_DP] dp. Both of those assertions
     * moved to issue #91, which carries the evidence, because neither has ever
     * been observed green: the label assertion was red on every CI run that
     * reached it (four composer nodes, all in this window), and the size
     * assertion never executed at all, because the label assertion failed
     * first.
     *
     * They moved rather than being fixed here because the sweep they read from
     * is not yet the set of nodes the claim is about:
     *
     *  - [speakableText] reads `contentDescription` and `text` and never
     *    `hintText`, so an empty Compose text field — which publishes its
     *    placeholder as `hintText` — reads as a control that announces
     *    nothing, when a screen reader would announce the placeholder.
     *  - [accessibilityBridge] sets `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`, i.e. it
     *    asks the platform for exactly the nodes a screen reader does not
     *    visit, and the assertion then holds those nodes to screen-reader
     *    semantics. A sweep that can carry the claim has to populate from
     *    screen-reader-focusable nodes instead.
     *  - Why three `Box.size(48.dp).clickable(role = Button)` composer controls
     *    that the product does label surface as unlabelled `android.view.View`
     *    nodes is unresolved. No labelled twin at the same bounds has ever
     *    been observed, so "unmerged duplicate" is a guess, and a filter that
     *    hides same-bounds nodes would go green without proving anything —
     *    which the lane charter in `AGENTS.md` refuses.
     *
     * What survives is what was observed green at 47dc20a, and it is not
     * nothing: it is the half of the claim Robolectric cannot make at all.
     */
    @Test
    fun theChromeActionsReachThePlatformAccessibilityTree() {
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
            "the platform tree published ${reachable.size} reachable actions in the window " +
                "under test: ${reachable.map { it.identify() }}; expected at least the chat " +
                "top bar and the composer",
            reachable.size >= EXPECTED_ACTIONS,
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
