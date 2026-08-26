package com.hermesagent.mobile.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.HermesApp
import com.hermesagent.mobile.ui.RelayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.relay.RelayUiState
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
 * here says which one it is: a real display and font scale, a real
 * `AccessibilityNodeInfo` tree produced by the platform bridge, a real input
 * method raising real `WindowInsets.ime`, a real orientation change through the
 * real `Configuration`, and a real Activity destroy/recreate through real
 * saved-instance-state parceling. A test that would pass identically under
 * Robolectric does not belong here.
 *
 * **This lane does not substitute for physical acceptance.** An emulator has no
 * browser hand-off to complete PKCE against, no radio, no network handoff, no
 * TalkBack, and no media stack worth trusting. Those stay on the physical
 * device matrix (issue #72, S39). `ActivityScenario.recreate()` is a real
 * destroy/recreate with real saved-state parceling; it is *not* a
 * system-initiated process kill, and this lane never claims it is.
 *
 * No test here reaches a Gateway, and none of them names a host, a credential,
 * or a fingerprint. Every state is constructed locally.
 */
internal object DeviceLane {
    /** The Android touch-target floor the app promises. Matches `HermesSpacing.touchTarget`. */
    const val TOUCH_TARGET_DP: Int = 48

    /** Long enough for a real IME window to animate in on a cold emulator. */
    const val PLATFORM_TIMEOUT_MILLIS: Long = 15_000

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
        relayState = RelayUiState(),
        relayActions = RelayActions(),
    )
}

/**
 * Runs [block] on the main thread and returns its value.
 *
 * Window insets, the input method manager and `findFocus` are all main-thread
 * reads. Compose's `waitUntil` does not promise which thread evaluates its
 * condition, so this works from either one rather than assuming.
 */
internal fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    var captured: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { captured = runCatching(block) }
    return checkNotNull(captured) { "the main thread did not run the block" }.getOrThrow()
}

/** True when the real input method window is up, as the real window insets report it. */
internal fun imeIsVisible(activity: Activity): Boolean = onMain {
    val root = activity.window.decorView
    ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
}

/** How far the real input method window intrudes from the bottom of the real window, in px. */
internal fun imeInsetBottomPx(activity: Activity): Int = onMain {
    val root = activity.window.decorView
    ViewCompat.getRootWindowInsets(root)
        ?.getInsets(WindowInsetsCompat.Type.ime())
        ?.bottom
        ?: 0
}

/** The real window's height in px, which is what an IME inset is measured against. */
internal fun windowHeightPx(activity: Activity): Int = onMain { activity.window.decorView.height }

/**
 * Asks the platform for the soft keyboard through both public routes.
 *
 * The insets controller is the modern one; `showSoftInput` is what still works
 * when the emulator image's IME is slow to become the active method. Neither
 * fabricates the keyboard — if no input method is installed, both no-op and the
 * caller's wait fails, which is the honest outcome.
 */
internal fun requestIme(activity: Activity) {
    onMain {
        val decor = activity.window.decorView
        val focused = decor.findFocus() ?: decor
        WindowCompat.getInsetsController(activity.window, focused)
            .show(WindowInsetsCompat.Type.ime())
        activity.getSystemService(InputMethodManager::class.java)?.showSoftInput(focused, 0)
    }
}

/** True when the platform input method holds a live connection to this window. */
internal fun imeHasActiveConnection(activity: Activity): Boolean = onMain {
    val decor = activity.window.decorView
    val focused = decor.findFocus() ?: return@onMain false
    activity.getSystemService(InputMethodManager::class.java)?.isActive(focused) == true
}

/**
 * Turns on the real platform accessibility bridge for this process.
 *
 * `UiAutomation` registers as an accessibility service, which is what makes
 * `AndroidComposeView` publish its semantics as real `AccessibilityNodeInfo`
 * nodes. Without a service registered, Compose's node provider stays dormant
 * and a walk of the tree would pass by finding nothing.
 */
internal fun accessibilityBridge(): UiAutomation {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val info = automation.serviceInfo
    if (info != null) {
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
    }
    return automation
}

/** Every node the platform bridge publishes for [packageName], root first. */
internal fun UiAutomation.publishedNodes(packageName: String): List<AccessibilityNodeInfo> {
    val root = rootInActiveWindow ?: return emptyList()
    val found = mutableListOf<AccessibilityNodeInfo>()
    collectNodes(root, packageName, found)
    return found
}

private fun collectNodes(
    node: AccessibilityNodeInfo,
    packageName: String,
    into: MutableList<AccessibilityNodeInfo>,
) {
    if (node.packageName == packageName) into += node
    for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        collectNodes(child, packageName, into)
    }
}

/** What a screen reader would say for this node, or null when it would say nothing. */
internal fun AccessibilityNodeInfo.speakableText(): String? =
    listOfNotNull(contentDescription, text)
        .map { it.toString().trim() }
        .firstOrNull { it.isNotEmpty() }
