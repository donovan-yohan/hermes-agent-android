package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.ui.gateway.GatewayScreen
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OverlayScaffold] states the keyboard rule; these tests hold it there.
 *
 * Robolectric cannot raise a real IME, so the inset comes through
 * [OverlayScaffold]'s test seam — the technique `ChatAccessibilityLayoutTest`
 * already uses for the wide rail's navigation-bar inset. That covers every
 * route that goes through the scaffold, which is all of them but Chat — Chat
 * pads its own composer and `ChatAccessibilityLayoutTest` holds that end.
 * It deliberately does not cover the bottom sheets: a
 * `ModalBottomSheet` is its own window and reads the real `WindowInsets.ime`,
 * which is always zero under Robolectric. Their `imePadding()` is held instead
 * by `scripts/check-repo-invariants.sh`, which can only prove the modifier is
 * there — that it does the right thing on a real keyboard still owes a device
 * pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp")
class KeyboardInsetLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an overlay route gives the keyboard's height back to its content`() {
        var ime by mutableStateOf(WindowInsets(bottom = 0))
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                OverlayScaffold(title = "Gateways", onBack = {}, imeInsets = ime) {
                    Box(Modifier.fillMaxSize().testTag(PAGE))
                }
            }
        }
        compose.waitForIdle()
        val closed = compose.onNodeWithTag(PAGE).fetchSemanticsNode().boundsInRoot

        ime = WindowInsets(bottom = IME_PX)
        compose.waitForIdle()
        val open = compose.onNodeWithTag(PAGE).fetchSemanticsNode().boundsInRoot

        assertEquals(
            "the page must end exactly where the keyboard starts",
            closed.bottom - IME_PX,
            open.bottom,
            tolerancePx(),
        )
        assertTrue(
            "the page must lose height, not merely move: a shorter page is what gives a scroll container something to scroll",
            open.height < closed.height,
        )
    }

    @Test
    fun `the Gateways route can scroll to a control the keyboard covers`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                OverlayScaffold(
                    title = "Gateways",
                    onBack = {},
                    imeInsets = WindowInsets(bottom = IME_PX),
                ) {
                    GatewayScreen(
                        state = GatewaySettingsUiState(loaded = true, mode = GatewayConnectionMode.Remote),
                        gatewayActions = GatewayActions(),
                        sshState = SshUiState(),
                        sshActions = SshActions(),
                    )
                }
            }
        }
        compose.waitForIdle()

        // This control sits well below the fold on a phone: the mode cards, the
        // Gateway URL field and the provider field are all above it.
        compose.onNodeWithText(CONNECT).performScrollTo().assertIsDisplayed()

        val control = compose.onNodeWithText(CONNECT).fetchSemanticsNode().boundsInRoot
        val keyboardTop = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - IME_PX
        assertTrue(
            "a control scrolled into view must clear the keyboard, not stop underneath it",
            control.bottom <= keyboardTop + tolerancePx(),
        )
    }

    @Test
    fun `a page inside the overlay cannot pad for the same keyboard twice`() {
        val ime = WindowInsets(bottom = IME_PX)
        var padsAgain by mutableStateOf(false)
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                OverlayScaffold(title = "Gateways", onBack = {}, imeInsets = ime) {
                    // Stands in for `SshScreen`, which keeps its own
                    // `imePadding()` so it still works when a test hosts it
                    // alone. Inside the scaffold that pass must measure zero.
                    val own = if (padsAgain) Modifier.windowInsetsPadding(ime) else Modifier
                    Box(own.fillMaxSize().testTag(PAGE))
                }
            }
        }
        compose.waitForIdle()
        val once = compose.onNodeWithTag(PAGE).fetchSemanticsNode().boundsInRoot

        padsAgain = true
        compose.waitForIdle()
        val twice = compose.onNodeWithTag(PAGE).fetchSemanticsNode().boundsInRoot

        assertEquals(
            "the scaffold consumes the keyboard inset, so a child that pads for it again must not move",
            once.bottom,
            twice.bottom,
            tolerancePx(),
        )
    }

    private fun tolerancePx(): Float = compose.density.density

    private companion object {
        const val PAGE = "Overlay page content"
        const val CONNECT = "Sign in and connect"
        const val IME_PX = 320
    }
}
