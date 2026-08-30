package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
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
 * route that goes through the scaffold, which is all of them but Chat. Chat's
 * own seam is here too, because its sidebar carries the app's other text field
 * and neither the drawer nor the rail is inside the scaffold.
 * It deliberately does not cover the bottom sheets: a
 * `ModalBottomSheet` is its own window and reads the real `WindowInsets.ime`,
 * which is always zero under Robolectric. Their `imePadding()` is held instead
 * by `scripts/check-repo-invariants.sh`, which can only prove the modifier is
 * there — that it does the right thing on a real keyboard still owes a device
 * pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h700dp")
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

    @Test
    fun `the compact sessions drawer lifts its list above the keyboard`() {
        var ime by mutableStateOf(WindowInsets(bottom = 0))
        launchChat(imeInsets = { ime }, modifier = Modifier.width(411.dp).fillMaxHeight())
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.waitForIdle()
        val closed = compose.onNodeWithTag(SESSION_LIST).fetchSemanticsNode().boundsInRoot

        ime = WindowInsets(bottom = IME_PX)
        compose.waitForIdle()
        val open = compose.onNodeWithTag(SESSION_LIST).fetchSemanticsNode().boundsInRoot

        assertEquals(
            "the drawer's session list must end where the keyboard starts, or search hides its own matches",
            closed.bottom - IME_PX,
            open.bottom,
            tolerancePx(),
        )
    }

    @Test
    fun `the wide sessions rail owes the taller of the keyboard and the navigation bar`() {
        var ime by mutableStateOf(WindowInsets(bottom = 0))
        launchChat(
            imeInsets = { ime },
            // A rail already holding a navigation-bar inset, so this also
            // settles that the two are unioned rather than summed.
            wideRailInsets = WindowInsets(bottom = RAIL_NAV_PX),
        )
        val closed = compose.onNodeWithTag(SESSION_LIST).fetchSemanticsNode().boundsInRoot

        ime = WindowInsets(bottom = IME_PX)
        compose.waitForIdle()
        val open = compose.onNodeWithTag(SESSION_LIST).fetchSemanticsNode().boundsInRoot

        assertEquals(
            "the rail owes max(navigation bar, keyboard) — the keyboard draws over the bar, it does not stack on it",
            closed.bottom - (IME_PX - RAIL_NAV_PX),
            open.bottom,
            tolerancePx(),
        )
    }

    @Test
    fun `a wide rail too short for its own chrome scrolls to its search field`() {
        // The device case this comes from: landscape, keyboard up, the rail
        // left shorter than switcher plus title row plus search field. The
        // field was clipped to a sliver with nothing on screen that scrolled,
        // so `performScrollTo` is the assertion — it fails outright when no
        // scrollable ancestor exists, which is exactly the bug.
        launchChat(imeInsets = { WindowInsets(bottom = CRAMPING_IME_PX) }, query = "keyboard")

        compose.onNodeWithContentDescription(SEARCH_FIELD).performScrollTo().assertIsDisplayed()

        val field = compose.onNodeWithContentDescription(SEARCH_FIELD).fetchSemanticsNode().boundsInRoot
        val keyboardTop = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom - CRAMPING_IME_PX
        assertTrue(
            "the search field must sit wholly above the keyboard once scrolled to, not straddle it",
            field.bottom <= keyboardTop + tolerancePx(),
        )
    }

    private fun launchChat(
        imeInsets: () -> WindowInsets,
        wideRailInsets: WindowInsets = WindowInsets(bottom = 0),
        modifier: Modifier = Modifier,
        query: String = "",
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = ChatUiState(
                        query = query,
                        sessionRows = listOf(
                            SessionListRow.Row(
                                SessionSummary(
                                    id = "s-keyboard",
                                    title = "Keyboard session",
                                    preview = "",
                                    lastActiveAtMillis = NOW,
                                ),
                            ),
                        ),
                    ),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    modifier = modifier,
                    wideRailInsets = wideRailInsets,
                    imeInsets = imeInsets(),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun tolerancePx(): Float = compose.density.density

    private companion object {
        const val PAGE = "Overlay page content"
        const val SESSION_LIST = "Session list"
        const val CONNECT = "Sign in and connect"
        const val SEARCH_FIELD = "Search sessions"
        const val IME_PX = 320
        const val RAIL_NAV_PX = 40

        /**
         * Leaves the rail ~100dp: under `RAIL_SCROLLS_BELOW`, and under its
         * fixed chrome, which is the condition the device hit.
         */
        const val CRAMPING_IME_PX = 600
        const val NOW = 1_755_600_000_000L
    }
}
