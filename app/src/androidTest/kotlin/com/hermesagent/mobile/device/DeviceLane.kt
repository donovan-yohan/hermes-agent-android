package com.hermesagent.mobile.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection

/**
 * What this lane is, and what it deliberately is not.
 *
 * `app/src/testDebug/` already renders every Compose surface under Robolectric,
 * on a synthetic window, at a synthetic density, with no platform accessibility
 * bridge, no input method, and no real configuration change. That is broad and
 * cheap and it is where surface behaviour belongs.
 *
 * This lane exists only for the claims Robolectric cannot make, and each test
 * here says which one it is: a real display density, a real
 * `AccessibilityNodeInfo` tree produced by the platform bridge, a real input
 * method binding to the composer, a real orientation change through the real
 * `Configuration`, and a real Activity destroy/recreate through real
 * saved-instance-state parceling. A test that would pass identically under
 * Robolectric does not belong here.
 *
 * **This lane does not substitute for physical acceptance.** An emulator has no
 * browser hand-off to complete PKCE against, no radio, no network handoff, no
 * TalkBack, and no media stack worth trusting. Those stay on the physical
 * device matrix (issue #72, S39). Neither does it prove the keyboard's own
 * window: whether a headless CI emulator draws an IME window at all depends on
 * the system image's input method and the AVD's hardware keyboard, so the
 * `imePadding` claim stays on that matrix too. `ActivityScenario.recreate()` is
 * a real destroy/recreate with real saved-state parceling; it is *not* a
 * system-initiated process kill, and this lane never claims it is.
 *
 * No test here reaches a Gateway, and none of them names a host, a credential,
 * or a fingerprint. Every state is constructed locally.
 */
internal object DeviceLane {
    /** The Android touch-target floor the app promises. Matches `HermesSpacing.touchTarget`. */
    const val TOUCH_TARGET_DP: Int = 48

    /** Long enough for the real platform to settle on a cold emulator. */
    const val PLATFORM_TIMEOUT_MILLIS: Long = 15_000

    /** How long the accessibility bridge must be quiet before a read is taken. */
    const val BRIDGE_QUIET_MILLIS: Long = 200

    /** How long to wait for that quiet window before reading anyway. */
    const val BRIDGE_IDLE_TIMEOUT_MILLIS: Long = 2_000

    /** One physical pixel of rounding slack between dp arithmetic and layout. */
    const val TOLERANCE_PX: Float = 1f

    // What the lane navigates by, spelled once. A copy change is then one edit
    // here rather than a hunt through five files.
    const val OPEN_SESSIONS: String = "Open sessions"
    const val OPEN_SETTINGS: String = "Open settings"
    const val COMPOSER_FIELD: String = "Message Hermes"
    const val SEND: String = "Send message"
    const val GATEWAYS_ROW: String = "settings-row-gateways"
}

/** A chat state in one connection state, with no session and no Gateway behind it. */
internal fun chatStateFor(status: GatewayConnectionStatus): ChatUiState =
    ChatUiState(connection = GatewayConnectionState(status))

/**
 * The whole app shell over locally constructed state and inert actions.
 *
 * The shell is the subject on purpose: navigation, the theme and the saveable
 * destination all belong to it, and a test that mounted one screen in isolation
 * would be testing something the user never sees.
 */
@Composable
internal fun HermesAppUnderTest(
    chatState: ChatUiState = chatStateFor(GatewayConnectionStatus.Disconnected),
) {
    HermesApp(
        chatState = chatState,
        gatewayState = GatewaySettingsUiState(),
        sshState = SshUiState(),
        appearance = AppearanceSelection(),
        chatActions = ChatActions(),
        appearanceActions = AppearanceActions(),
        gatewayActions = GatewayActions(),
        sshActions = SshActions(),
    )
}

/**
 * Runs [block] on the main thread and returns its value.
 *
 * Window focus, the input method manager and `findFocus` are all main-thread
 * reads. Compose's `waitUntil` does not promise which thread evaluates its
 * condition, so this works from either one rather than assuming.
 */
internal fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    var captured: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { captured = runCatching(block) }
    return checkNotNull(captured) { "the main thread did not run the block" }.getOrThrow()
}

/**
 * Runs [block] anywhere except the main thread and returns its value.
 *
 * The mirror image of [onMain], and it exists for one specific reason. Reading
 * an `AccessibilityNodeInfo` is a blocking round trip: the client hands the
 * request to the interrogated window's `AccessibilityInteractionController`,
 * which services it on that window's own UI thread, and the caller then blocks
 * until the answer arrives. Here the interrogated window belongs to this same
 * process, so making that call *from* the UI thread asks the one thread that
 * can answer to sit waiting for itself. Compose's `waitUntil` evaluates its
 * condition on the instrumentation thread today, but nothing in its contract
 * says it must, so the guard is written down rather than assumed.
 */
internal fun <T> offMain(block: () -> T): T {
    if (Looper.myLooper() != Looper.getMainLooper()) return block()
    var captured: Result<T>? = null
    val worker = Thread({ captured = runCatching(block) }, "device-lane-off-main")
    worker.start()
    worker.join()
    return checkNotNull(captured) { "the worker thread did not run the block" }.getOrThrow()
}

/** True once the real window manager has given this Activity's window input focus. */
internal fun hasWindowFocus(activity: Activity): Boolean = onMain { activity.hasWindowFocus() }

/** True when the platform input method holds a live connection to this window. */
internal fun imeHasActiveConnection(activity: Activity): Boolean = onMain {
    val focused = activity.window.decorView.findFocus() ?: return@onMain false
    activity.getSystemService(InputMethodManager::class.java)?.isActive(focused) == true
}

/**
 * What the input method actually bound to, phrased for a failure message.
 *
 * A bare `waitUntil` timeout says only that something never happened. This says
 * which of the three preconditions was missing, which is the difference between
 * a diagnosable CI failure and another round trip.
 */
internal fun describeImeBinding(activity: Activity): String = onMain {
    val focused = activity.window.decorView.findFocus()
    val manager = activity.getSystemService(InputMethodManager::class.java)
    "windowFocus=${activity.hasWindowFocus()}, " +
        "focusedView=${focused?.javaClass?.simpleName ?: "none"}, " +
        "acceptingText=${manager?.isAcceptingText}, " +
        "servedByInputMethod=${focused?.let { manager?.isActive(it) }}"
}

/**
 * Turns on the real platform accessibility bridge for this process.
 *
 * `UiAutomation` registers as an accessibility service, which is what makes the
 * platform ask `AndroidComposeView` for its semantics as real
 * `AccessibilityNodeInfo` nodes at all.
 *
 * `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` is not decoration: `rootInActiveWindow`
 * resolves whichever window currently holds input focus, so on a cold emulator
 * a read taken while the app's window is still settling would quietly be a read
 * of the launcher. With the window list available, [publishedNodes] can look
 * past whichever window happens to be active rather than depending on it.
 */
internal fun accessibilityBridge(): UiAutomation {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val info = automation.serviceInfo
    if (info != null) {
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
    }
    return automation
}

/**
 * Tells the accessibility layer that this window's subtree changed.
 *
 * Compose will not, and that is the whole reason this exists.
 * `AndroidComposeViewAccessibilityDelegateCompat.sendEvent` returns early
 * whenever `AccessibilityManager.getEnabledAccessibilityServiceList()` is
 * empty, and a `UiAutomation` bound for a test never appears in that list — it
 * is registered through `registerUiTestAutomationService`, not as an enabled
 * service. So Compose emits no `TYPE_WINDOW_CONTENT_CHANGED`, the client-side
 * accessibility cache is never told to drop the snapshot it took before
 * `setContent` ran, and every later read can be answered from that stale copy.
 *
 * The node provider itself stays live, so a genuinely fresh query returns real
 * nodes. Sending the subtree-changed notification from the decor view — which
 * has no Compose delegate in front of it, so the suppression above does not
 * apply — is what makes the next query fresh.
 */
internal fun invalidatePublishedNodes(activity: Activity) = onMain {
    val decor = activity.window.decorView
    decor.parent?.notifySubtreeAccessibilityStateChanged(
        decor,
        decor,
        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
    )
}

/**
 * Every node the platform bridge publishes for [packageName], deduplicated, and
 * — when [windowId] is given — only from that one window.
 *
 * Both the focused window and every window the bridge can see are walked: the
 * two overlap in the ordinary case, and the overlap is what stops a slow window
 * transition from being read as an empty app. The focused window is walked
 * first and the result keeps insertion order, so the first node a caller
 * matches comes from the foreground window whenever it holds focus.
 *
 * That breadth is exactly why [windowId] exists. A package name cannot tell the
 * window under test from a window another class in this same run left behind —
 * every Activity in this lane is this package, so a leftover reads as extra
 * content on the screen being asserted about. A caller that has identified its
 * own window, by taking [AccessibilityNodeInfo.getWindowId] off a node it knows
 * belongs to it, passes that id here and sees nothing else. A window left
 * behind is a different window, so the id keeps it out however late the bridge
 * is to drop it.
 */
internal fun UiAutomation.publishedNodes(
    packageName: String,
    windowId: Int? = null,
): List<AccessibilityNodeInfo> = offMain {
    runCatching {
        waitForIdle(DeviceLane.BRIDGE_QUIET_MILLIS, DeviceLane.BRIDGE_IDLE_TIMEOUT_MILLIS)
    }
    val roots = buildList {
        rootInActiveWindow?.let(::add)
        runCatching { windows }.getOrDefault(emptyList()).forEach { window ->
            window.root?.let(::add)
        }
    }.filter { windowId == null || it.windowId == windowId }
    val found = LinkedHashSet<AccessibilityNodeInfo>()
    val visited = HashSet<AccessibilityNodeInfo>()
    roots.forEach { collectNodes(it, packageName, windowId, found, visited) }
    found.toList()
}

private fun collectNodes(
    node: AccessibilityNodeInfo,
    packageName: String,
    windowId: Int?,
    into: MutableSet<AccessibilityNodeInfo>,
    visited: MutableSet<AccessibilityNodeInfo>,
) {
    // A node identifies itself by window and source id, so this both dedupes
    // the overlap between window roots and bounds the walk.
    if (!visited.add(node)) return
    // The roots were filtered by window before the walk, and below an ordinary
    // root every child is in that same window — so this second check reads as
    // redundant. It is not. An embedded hierarchy crosses the boundary: a child
    // handed over by SurfaceControlViewHost carries its own window's id, and
    // the moment one of those appears under this surface the root filter alone
    // lets exactly the stray back in that the scope exists to keep out. One
    // comparison per node is the whole cost of not depending on that.
    if (node.packageName == packageName && (windowId == null || node.windowId == windowId)) {
        into += node
    }
    for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        collectNodes(child, packageName, windowId, into, visited)
    }
}

/** What a screen reader would say for this node, or null when it would say nothing. */
internal fun AccessibilityNodeInfo.speakableText(): String? =
    listOfNotNull(contentDescription, text)
        .map { it.toString().trim() }
        .firstOrNull { it.isNotEmpty() }
