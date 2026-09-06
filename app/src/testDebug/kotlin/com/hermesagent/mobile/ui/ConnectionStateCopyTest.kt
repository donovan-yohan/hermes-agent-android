package com.hermesagent.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewayScreen
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every connection state says which one it is, and offers one action.
 *
 * A connection state is a value the surface reads, so the sweep is a Compose
 * question rather than a device one: no density, insets or platform service is
 * involved in whether the right sentence is wired to the right state.
 *
 * Nothing here reaches a Gateway. Each state is constructed locally and no host
 * name, URL or credential appears in the fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionStateCopyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every connection state names itself in the chat chrome`() {
        var status by mutableStateOf(GatewayConnectionStatus.Disconnected)
        compose.setContent {
            HermesApp(
                chatState = ChatUiState(connection = GatewayConnectionState(status)),
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
            )
        }

        for (candidate in GatewayConnectionStatus.entries) {
            status = candidate
            compose.waitForIdle()
            compose.onNodeWithText(candidate.label).assertIsDisplayed()
        }
    }

    @Test
    fun `every connection state offers one Gateway action`() {
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
        compose.onNodeWithText(SIGN_IN).performScrollTo().assertIsDisplayed()

        connection = GatewayConnectionState(GatewayConnectionStatus.Connecting)
        compose.waitForIdle()
        compose.onNodeWithText(CONNECTING).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(CANCEL).performScrollTo().assertIsDisplayed()

        connection = GatewayConnectionState(GatewayConnectionStatus.Connected)
        compose.waitForIdle()
        compose.onNodeWithText(DISCONNECT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(FORGET).performScrollTo().assertIsDisplayed()

        // A Gateway that stopped talking explains itself and still offers the
        // one safe next action, rather than leaving the surface actionless.
        connection = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, ENDED)
        compose.waitForIdle()
        compose.onNodeWithText(ENDED).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SIGN_IN).performScrollTo().assertIsDisplayed()
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
