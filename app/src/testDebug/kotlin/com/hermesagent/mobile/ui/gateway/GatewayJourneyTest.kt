package com.hermesagent.mobile.ui.gateway

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import com.hermesagent.mobile.data.gateway.LocalGatewayCopy
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Remote Gateway is the default route and Managed SSH stays a separate mode`() {
        var state by mutableStateOf(GatewaySettingsUiState(loaded = true))
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = state,
                    gatewayActions = GatewayActions(onModeChange = { state = state.copy(mode = it) }),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }

        compose.onNodeWithText(GatewayModeCopy.REMOTE_TITLE).assertIsDisplayed()
        compose.onNodeWithText("Sign in and connect").assertExists()
        compose.onNodeWithText(GatewayModeCopy.SSH_TITLE).performClick()

        assertEquals(GatewayConnectionMode.Ssh, state.mode)
        // The mode cards now head the page's own scroll, as they do on
        // Desktop, so the chosen route's pane starts below them.
        compose.onNodeWithText("Connect this app to a remote Hermes Gateway over SSH.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `valid remote URL enables browser sign-in and connected state exposes sign-out`() {
        var state by mutableStateOf(GatewaySettingsUiState(loaded = true))
        var connects = 0
        var disconnects = 0
        var forgets = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = state,
                    gatewayActions = GatewayActions(
                        onRemoteUrlChange = { state = state.copy(remote = state.remote.copy(baseUrl = it)) },
                        onConnectRemote = { connects++ },
                        onDisconnect = { disconnects++ },
                        onForgetSignIn = { forgets++ },
                    ),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }

        compose.onNodeWithContentDescription("Gateway URL").performTextInput("https://gateway.example/hermes")
        compose.waitForIdle()
        assertEquals("https://gateway.example/hermes", state.remote.baseUrl)
        assertEquals(true, state.canConnectRemote)
        compose.onNode(hasText("Sign in and connect") and hasClickAction())
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        compose.waitForIdle()
        assertEquals(1, connects)

        state = state.copy(
            remote = RemoteGatewayProfile("https://gateway.example/hermes"),
            connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
        )
        compose.waitForIdle()
        compose.onNode(hasText("Disconnect") and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("Forget sign-in") and hasClickAction()).performScrollTo().performClick()

        assertEquals(1, disconnects)
        assertEquals(1, forgets)
    }

    @Test
    fun `the Local route is offered, dials on request, and says what a refused token means`() {
        var state by mutableStateOf(
            GatewaySettingsUiState(local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119"), loaded = true),
        )
        var connects = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = state,
                    gatewayActions = GatewayActions(
                        onModeChange = { state = state.copy(mode = it) },
                        onConnectLocal = { connects++ },
                    ),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }

        compose.onNodeWithText(GatewayModeCopy.LOCAL_TITLE).performClick()
        compose.waitForIdle()
        assertEquals(GatewayConnectionMode.Local, state.mode)

        // The address the row names, and the one limitation of this route.
        compose.onNodeWithText("127.0.0.1:9119").assertExists()
        compose.onNodeWithText(ConnectionsCopy.LOCAL_LIMITATION).performScrollTo().assertExists()

        compose.onNode(hasText("Connect") and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, connects)

        // A refused token is terminal, and it says what to do about it.
        state = state.copy(
            connection = GatewayConnectionState(
                GatewayConnectionStatus.NeedsAttention,
                LocalGatewayCopy.TOKEN_REFUSED,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText(LocalGatewayCopy.TOKEN_REFUSED).performScrollTo().assertExists()

        // And so does a Hermes that is no longer running in Termux.
        state = state.copy(
            connection = GatewayConnectionState(
                GatewayConnectionStatus.NeedsAttention,
                LocalGatewayCopy.NOT_ANSWERING,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText(LocalGatewayCopy.NOT_ANSWERING).performScrollTo().assertExists()
    }

    @Test
    fun `a Local route with no address anywhere says where to add one instead of offering a dial`() {
        val state = GatewaySettingsUiState(mode = GatewayConnectionMode.Local, loaded = true)
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = state,
                    gatewayActions = GatewayActions(),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }

        compose.onNodeWithText(ConnectionsCopy.LOCAL_NO_ADDRESS).assertExists()
        compose.onNode(hasText("Connect") and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }

    /**
     * The case the reference count exists for, and the only one that fails
     * without it.
     *
     * Leaving the surface entirely disposes both holders, so a naive
     * `clearFlags` on the inner one looks correct there. Switching *route* does
     * not: `SshScreen` is disposed while the Gateways page — still showing the
     * registry, whose editor takes a Local row's session token — stays on
     * screen. Without the count that disposal unprotects the window, and the
     * token field would be screenshot-able on the two routes that are not SSH.
     */
    @Test
    fun `switching away from the SSH form does not unprotect the page it was on`() {
        var state by mutableStateOf(GatewaySettingsUiState(mode = GatewayConnectionMode.Ssh, loaded = true))
        var window: Window? = null
        compose.setContent {
            val context = LocalContext.current
            SideEffect { window = context.activityWindow() }
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = state,
                    gatewayActions = GatewayActions(onModeChange = { state = state.copy(mode = it) }),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }
        val secured = requireNotNull(window) { "the test needs a real Activity window" }
        assertTrue("the SSH form holds the window secure", secured.isSecure())

        compose.onNodeWithText(GatewayModeCopy.REMOTE_TITLE).performClick()
        compose.waitForIdle()

        assertEquals(GatewayConnectionMode.Remote, state.mode)
        assertTrue(
            "the page still holds it: the registry editor here takes a session token",
            secured.isSecure(),
        )
    }

    /**
     * The route selector rewrites the *active row's kind*, so a tap it accepts
     * before the saved route has been read is not a no-op: the control is
     * showing the default rather than the truth, and the write would carry a
     * `previous` that is still the default — stranding a Local row's session
     * token behind an erase that never sees it.
     */
    @Test
    fun `the route selector is dead until the saved route has been read`() {
        var state by mutableStateOf(GatewaySettingsUiState(loaded = false))
        var modeChanges = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = state,
                    gatewayActions = GatewayActions(
                        onModeChange = {
                            modeChanges++
                            state = state.copy(mode = it)
                        },
                    ),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }

        compose.onNodeWithText(GatewayModeCopy.SSH_TITLE).performClick()
        compose.waitForIdle()
        assertEquals("a tap before the store has answered must change nothing", 0, modeChanges)
        assertEquals(GatewayConnectionMode.Remote, state.mode)

        state = state.copy(loaded = true)
        compose.waitForIdle()
        compose.onNodeWithText(GatewayModeCopy.SSH_TITLE).performClick()
        compose.waitForIdle()

        assertEquals(1, modeChanges)
        assertEquals(GatewayConnectionMode.Ssh, state.mode)
    }
}

private fun Window.isSecure(): Boolean =
    attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

/** Null in any host that is not an Activity. */
private tailrec fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.activityWindow()
    else -> null
}
