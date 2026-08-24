package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.ssh.SshScreen
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.HermesTheme

@Composable
fun GatewayScreen(
    state: GatewaySettingsUiState,
    gatewayActions: GatewayActions,
    sshState: SshUiState,
    sshActions: SshActions,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    Column(modifier.fillMaxSize().background(tokens.chatSurface)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel("Connection")
            SegmentedControl(
                options = GatewayConnectionMode.entries,
                selected = state.mode,
                label = {
                    when (it) {
                        GatewayConnectionMode.Remote -> "Remote Gateway"
                        GatewayConnectionMode.Ssh -> "Managed SSH"
                    }
                },
                onSelect = gatewayActions.onModeChange,
                describe = {
                    when (it) {
                        GatewayConnectionMode.Remote -> "Use a host-owned Remote Gateway"
                        GatewayConnectionMode.Ssh -> "Use an app-managed Gateway over SSH"
                    }
                },
            )
        }
        Box(Modifier.weight(1f)) {
            when (state.mode) {
                GatewayConnectionMode.Remote -> RemoteGatewayScreen(state, gatewayActions)
                GatewayConnectionMode.Ssh -> SshScreen(sshState, sshActions)
            }
        }
    }
}

@Composable
private fun RemoteGatewayScreen(
    state: GatewaySettingsUiState,
    actions: GatewayActions,
) {
    val tokens = HermesTheme.tokens
    val connection = state.connection
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Remote Gateway", style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text(
                "Connect Desktop and mobile to one host-owned Hermes Gateway. This is the recommended sharing route: each client signs in independently, and this app never starts or stops the server.",
                style = HermesTheme.type.body,
                color = tokens.textSecondary,
            )
        }

        LabelledField(
            label = "Gateway URL",
            value = state.remote.baseUrl,
            placeholder = "https://hermes.example.com",
            onValueChange = actions.onRemoteUrlChange,
            keyboardType = KeyboardType.Uri,
        )
        state.remoteUrlError?.let { error ->
            Text(error, style = HermesTheme.type.caption, color = tokens.destructive)
        }
        LabelledField(
            label = "Sign-in provider (optional)",
            value = state.remote.provider,
            placeholder = "Use the Gateway default",
            onValueChange = actions.onProviderChange,
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("Authentication")
            Text(
                "Sign-in opens in your browser. This app keeps its own sign-in on this device and uses a new one-time connection ticket each time.",
                style = HermesTheme.type.body,
                color = tokens.textSecondary,
            )
        }

        connection.message?.let { message ->
            Text(
                message,
                style = HermesTheme.type.caption,
                color = if (connection.status == GatewayConnectionStatus.NeedsAttention) tokens.destructive else tokens.textSecondary,
            )
        }

        when (connection.status) {
            GatewayConnectionStatus.Connected -> {
                PrimaryButton("Disconnect", actions.onDisconnect, Modifier.fillMaxWidth())
                TextButton("Forget sign-in", actions.onForgetSignIn, color = tokens.textTertiary)
            }

            GatewayConnectionStatus.Connecting -> {
                PrimaryButton("Connecting…", {}, Modifier.fillMaxWidth(), enabled = false)
                TextButton("Cancel", actions.onDisconnect, color = tokens.textTertiary)
            }

            GatewayConnectionStatus.Disconnected,
            GatewayConnectionStatus.NeedsAttention,
            -> PrimaryButton(
                "Sign in and connect",
                actions.onConnectRemote,
                Modifier.fillMaxWidth(),
                enabled = state.canConnectRemote,
            )
        }

        Text(
            "Until the Gateway's multi-client fan-out update is installed, avoid opening or controlling the same running session from Desktop and mobile at once.",
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
        )
    }
}
