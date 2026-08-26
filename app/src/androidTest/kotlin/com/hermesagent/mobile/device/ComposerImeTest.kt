package com.hermesagent.mobile.device

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesagent.mobile.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer behaves against a real input method, not a stubbed one.
 *
 * Robolectric has no input method: `WindowInsets.ime` is always zero there, so
 * `Modifier.imePadding()` is unexercised and a composer that sat underneath the
 * keyboard would pass every existing test. This runs the real app Activity —
 * real `enableEdgeToEdge`, real `adjustResize`, real IME window — and asserts
 * the keyboard both opens and is kept out of the composer's way.
 *
 * Real typing through the keyboard's own candidate/composing pipeline, physical
 * and Bluetooth keyboards, and per-locale IMEs stay on the physical device
 * matrix (issue #72, S39). Nothing here contacts a Gateway.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ComposerImeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theComposerFieldTakesARealInputConnection() {
        focusTheComposer()

        compose.waitUntil(DeviceLane.PLATFORM_TIMEOUT_MILLIS) {
            imeHasActiveConnection(compose.activity)
        }
        assertTrue(
            "the platform input method never bound to the focused composer",
            imeHasActiveConnection(compose.activity),
        )
    }

    @Test
    fun theOpenKeyboardNeverCoversTheComposer() {
        focusTheComposer()
        requestIme(compose.activity)

        compose.waitUntil(DeviceLane.PLATFORM_TIMEOUT_MILLIS) { imeIsVisible(compose.activity) }

        val insetPx = imeInsetBottomPx(compose.activity)
        assertTrue("the IME reported a zero-height inset while visible", insetPx > 0)

        val imeTopPx = windowHeightPx(compose.activity) - insetPx
        val sendBottomPx = compose.onNodeWithContentDescription(DeviceLane.SEND)
            .fetchSemanticsNode()
            .boundsInWindow
            .bottom
        assertTrue(
            "the send control ends at $sendBottomPx px, below the keyboard's top edge at $imeTopPx px",
            sendBottomPx <= imeTopPx + DeviceLane.TOLERANCE_PX,
        )
    }

    private fun focusTheComposer() {
        compose.waitUntilAtLeastOneExists(
            hasContentDescription(DeviceLane.COMPOSER_FIELD),
            DeviceLane.PLATFORM_TIMEOUT_MILLIS,
        )
        compose.onNodeWithContentDescription(DeviceLane.COMPOSER_FIELD).performClick()
        compose.waitForIdle()
    }

}
