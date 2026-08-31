package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chat chrome reports a connection that needs signing in twice — in the
 * header subtitle and, where the composer is wide enough to carry it, on the
 * composer itself. Neither used to be a door: reaching the sign-in meant
 * Settings, then Gateways, then a scroll (#116 S-U3).
 *
 * Both surfaces are asserted at the width that actually renders them: the
 * subtitle at phone width, the composer status at the width its `Full` layout
 * needs (`composerLayoutMode`, > 560dp for the composer's own box).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class GatewayFailureAffordanceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the header subtitle opens Gateways while the connection needs attention`() {
        var opened = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = needsAttention(),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = { opened += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription(
            "${GatewayConnectionStatus.NeedsAttention.label}. ${ConnectionsCopy.MANAGE_GATEWAYS}",
        ).assertExists().performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a disconnected header is a door too, because its line already names Gateways`() {
        var opened = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = ChatUiState(connection = GatewayConnectionState(GatewayConnectionStatus.Disconnected)),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = { opened += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription(
            "${GatewayConnectionStatus.Disconnected.label}. ${ConnectionsCopy.MANAGE_GATEWAYS}",
        ).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `a connected header is not a door`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = ChatUiState(connection = GatewayConnectionState(GatewayConnectionStatus.Connected)),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }

        compose.onNodeWithText(GatewayConnectionStatus.Connected.label).assertExists()
        compose.onNodeWithContentDescription(
            "${GatewayConnectionStatus.Connected.label}. ${ConnectionsCopy.MANAGE_GATEWAYS}",
        ).assertDoesNotExist()
    }

    @Test
    fun `a connection that is still dialling is not a door out of itself`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = ChatUiState(connection = GatewayConnectionState(GatewayConnectionStatus.Connecting)),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }

        compose.onNodeWithContentDescription(
            "${GatewayConnectionStatus.Connecting.label}. ${ConnectionsCopy.MANAGE_GATEWAYS}",
        ).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1200dp-h900dp")
    fun `the composer status line opens Gateways where it is rendered`() {
        var opened = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = needsAttention(),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = { opened += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription("$SIGN_IN_MESSAGE. ${ConnectionsCopy.MANAGE_GATEWAYS}")
            .assertExists()
            .performClick()

        assertEquals(1, opened)
    }

    private fun needsAttention() = ChatUiState(
        connection = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, SIGN_IN_MESSAGE),
    )

    private companion object {
        /**
         * What a Remote row with no usable sign-in reports, verbatim
         * (`data/gateway/RemoteGateway.kt:424`). Not a fixture sentence: the
         * point of this file is that *this* line is the one with no door.
         */
        const val SIGN_IN_MESSAGE = "Sign in to this Gateway before reconnecting."
    }
}
