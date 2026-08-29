package com.hermesagent.mobile.ui.gateway

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
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
import com.hermesagent.mobile.data.gateway.LocalGatewayCopy
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
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
        var state by mutableStateOf(GatewaySettingsUiState())
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

        compose.onNodeWithContentDescription("Use a host-owned Remote Gateway").assertIsDisplayed()
        compose.onNodeWithText("Sign in and connect").assertExists()
        compose.onNodeWithContentDescription("Use an app-managed Gateway over SSH").performClick()

        assertEquals(GatewayConnectionMode.Ssh, state.mode)
        compose.onNodeWithText("Connect this app to a remote Hermes Gateway over SSH.").assertIsDisplayed()
    }

    @Test
    fun `valid remote URL enables browser sign-in and connected state exposes sign-out`() {
        var state by mutableStateOf(GatewaySettingsUiState())
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
            GatewaySettingsUiState(local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119")),
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

        compose.onNodeWithContentDescription(ConnectionsCopy.KIND_LOCAL_DESC).performClick()
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
        val state = GatewaySettingsUiState(mode = GatewayConnectionMode.Local)
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

        compose.onNodeWithText("Add a Local gateway below, then connect.").assertExists()
        compose.onNode(hasText("Connect") and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }
}
