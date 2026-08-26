package com.hermesagent.mobile.device

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.gateway.GatewayScreen
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every connection state says which one it is, on a real display.
 *
 * Robolectric already proves the strings are wired to the states. What it
 * cannot prove is that they survive contact with a device: real density, the
 * device's own font scale, real status and navigation insets, and a real window
 * that clips. `assertIsDisplayed` is the load-bearing part — it fails when the
 * copy is laid out but pushed off the real window, which is the failure a
 * synthetic window cannot produce.
 *
 * Nothing here reaches a Gateway. Each state is constructed locally and no host
 * name, URL or credential appears in the fixture.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionStateCopyTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun everyConnectionStateNamesItselfInTheChatChrome() {
        var status by mutableStateOf(GatewayConnectionStatus.Disconnected)
        compose.setContent { HermesAppUnderTest(chatStateFor(status)) }

        for (candidate in GatewayConnectionStatus.entries) {
            status = candidate
            compose.waitForIdle()
            compose.onNodeWithText(candidate.label).assertIsDisplayed()
        }
    }

    @Test
    fun everyConnectionStateOffersOneGatewayActionOnTheRealDisplay() {
        var connection by mutableStateOf(GatewayConnectionState())
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                GatewayScreen(
                    state = GatewaySettingsUiState(connection = connection),
                    gatewayActions = GatewayActions(),
                    sshState = SshUiState(),
                    sshActions = SshActions(),
                )
            }
        }

        connection = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        compose.waitForIdle()
        compose.onNodeWithText(SIGN_IN).assertIsDisplayed()

        connection = GatewayConnectionState(GatewayConnectionStatus.Connecting)
        compose.waitForIdle()
        compose.onNodeWithText(CONNECTING).assertIsDisplayed()
        compose.onNodeWithText(CANCEL).assertIsDisplayed()

        connection = GatewayConnectionState(GatewayConnectionStatus.Connected)
        compose.waitForIdle()
        compose.onNodeWithText(DISCONNECT).assertIsDisplayed()
        compose.onNodeWithText(FORGET).assertIsDisplayed()

        // A Gateway that stopped talking explains itself and still offers the
        // one safe next action, rather than leaving the surface actionless.
        connection = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, ENDED)
        compose.waitForIdle()
        compose.onNodeWithText(ENDED).assertIsDisplayed()
        compose.onNodeWithText(SIGN_IN).assertIsDisplayed()
    }

    private companion object {
        const val SIGN_IN = "Sign in and connect"
        const val CONNECTING = "Connecting…"
        const val CANCEL = "Cancel"
        const val DISCONNECT = "Disconnect"
        const val FORGET = "Forget sign-in"

        /** A locally authored failure sentence. It names no host and no account. */
        const val ENDED = "The Gateway ended this connection."
    }
}
