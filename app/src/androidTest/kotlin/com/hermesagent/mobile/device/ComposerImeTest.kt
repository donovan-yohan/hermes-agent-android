package com.hermesagent.mobile.device

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesagent.mobile.MainActivity
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer binds to a real input method, not a stubbed one.
 *
 * Robolectric has no input method at all: nothing there can tell a composer
 * that takes a real `InputConnection` from one that only looks like a text
 * field. This runs the real app Activity — real `enableEdgeToEdge`, real
 * `adjustResize`, real `InputMethodManager` — and asserts the platform input
 * method actually binds to the focused composer.
 *
 * What it deliberately does not assert is the keyboard's own window. Whether a
 * headless CI emulator ever draws one depends on the system image's input
 * method and on the AVD's hardware-keyboard setting, neither of which this test
 * controls, so `Modifier.imePadding()` keeping the composer clear of the
 * keyboard stays on the physical device matrix (issue #72, S39) along with real
 * typing through a candidate pipeline, Bluetooth keyboards and per-locale IMEs.
 * Nothing here contacts a Gateway.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComposerImeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theComposerFieldTakesARealInputConnection() {
        awaitWindowFocus()
        focusTheComposer()

        try {
            compose.waitUntil(DeviceLane.PLATFORM_TIMEOUT_MILLIS) {
                imeHasActiveConnection(compose.activity)
            }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "the platform input method never bound to the focused composer " +
                    "(${timeout.message}): ${describeImeBinding(compose.activity)}",
            )
        }
    }

    /**
     * Waits for the window manager to hand this window input focus.
     *
     * This is a precondition, not politeness. `View.onFocusChanged` tells the
     * input method about a newly focused view only under
     * `mAttachInfo != null && mAttachInfo.mHasWindowFocus`, and nothing retries
     * the notification afterwards. On a cold CI emulator the Activity resumes
     * and composes before its window is focused, so a composer that took focus
     * first is never served — `InputMethodManager.showSoftInput` then logs
     * "is not served" and every later read of `isActive` stays false.
     */
    private fun awaitWindowFocus() {
        try {
            compose.waitUntil(DeviceLane.PLATFORM_TIMEOUT_MILLIS) {
                hasWindowFocus(compose.activity)
            }
        } catch (timeout: ComposeTimeoutException) {
            fail(
                "the Activity window never gained input focus (${timeout.message}), " +
                    "so no view in it could be served by the input method",
            )
        }
    }

    private fun focusTheComposer() {
        compose.waitUntilAtLeastOneExists(
            hasContentDescription(DeviceLane.COMPOSER_FIELD),
            DeviceLane.PLATFORM_TIMEOUT_MILLIS,
        )
        // Drop whatever focus the composition already took, so the click below
        // is a real focus transition. The input method is only ever notified on
        // the transition, so re-clicking an already-focused field that focused
        // itself before the window did would change nothing.
        onMain { compose.activity.window.decorView.findFocus()?.clearFocus() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(DeviceLane.COMPOSER_FIELD).performClick()
        compose.waitForIdle()
    }
}
