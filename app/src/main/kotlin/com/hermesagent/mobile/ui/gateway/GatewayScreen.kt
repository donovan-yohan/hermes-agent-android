package com.hermesagent.mobile.ui.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.SecureScreenLifetime
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.ssh.SshScreen
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Every route the form above the registry can configure.
 *
 * Exhaustive on purpose, and asserted to be: [SegmentedControl] cannot render a
 * `selected` value that is not among its `options`, so a curated subset can
 * leave a saved route with no segment lit and no way to change it. Being total
 * over [GatewayConnectionMode] is what makes that unreachable —
 * `GatewayScreenTest` fails if a route is added without one.
 */
internal val GATEWAY_ROUTE_OPTIONS =
    listOf(GatewayConnectionMode.Remote, GatewayConnectionMode.Ssh, GatewayConnectionMode.Local)

@Composable
fun GatewayScreen(
    state: GatewaySettingsUiState,
    gatewayActions: GatewayActions,
    sshState: SshUiState,
    sshActions: SshActions,
    modifier: Modifier = Modifier,
    connectionsState: ConnectionsUiState = ConnectionsUiState(),
    connectionsActions: ConnectionsActions = ConnectionsActions(),
) {
    val tokens = HermesTheme.tokens
    // The whole surface is the protected one, not just the SSH form inside it:
    // the registry's editor takes a Local row's session token, and it is
    // reachable from every route. The same disposal ends that form's secret
    // lifetime, before the window stops being secure.
    SecureScreenLifetime(onLeave = connectionsActions.onLeaveScreen)
    Column(modifier.fillMaxSize().background(tokens.chatSurface)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel("Connection")
            SegmentedControl(
                options = GATEWAY_ROUTE_OPTIONS,
                selected = state.mode,
                label = {
                    when (it) {
                        GatewayConnectionMode.Remote -> "Remote Gateway"
                        GatewayConnectionMode.Ssh -> "Managed SSH"
                        GatewayConnectionMode.Local -> ConnectionsCopy.KIND_LOCAL
                    }
                },
                // Dead until the saved route has actually been read. Before
                // that this control shows the default rather than the truth, so
                // a tap lands as "change route" when the person meant "the one
                // already selected" — and it rewrites the active row's kind
                // from a `previous` that is still the default, so a Local row's
                // session token is stranded by an erase that never sees it.
                onSelect = { if (state.loaded) gatewayActions.onModeChange(it) },
                describe = {
                    when (it) {
                        GatewayConnectionMode.Remote -> "Use a host-owned Remote Gateway"
                        GatewayConnectionMode.Ssh -> "Use an app-managed Gateway over SSH"
                        GatewayConnectionMode.Local -> ConnectionsCopy.KIND_LOCAL_DESC
                    }
                },
            )
        }
        Box(Modifier.weight(1f)) {
            // Desktop puts the registry at the foot of this same page, below the
            // window connection controls (`gateway-settings.tsx:1499-1502` @
            // `f82f2dba`). On a phone the page is one scroll per route, so the
            // section travels into whichever route is showing rather than
            // becoming a second, separately-scrolling band.
            val registry: @Composable ColumnScope.() -> Unit = {
                ConnectionsSection(connectionsState, connectionsActions)
            }
            when (state.mode) {
                GatewayConnectionMode.Remote -> RemoteGatewayScreen(
                    state = state,
                    actions = gatewayActions,
                    // The registry's own save path rejects a duplicate outright
                    // (`connections-registry.tsx:120-168` @ `f82f2dba`). This
                    // form autosaves while someone is still typing, so the same
                    // rule surfaces as a warning beside the field instead.
                    duplicateOf = connectionsState.duplicateRemoteLabel(state.remote.baseUrl),
                    footer = registry,
                )

                GatewayConnectionMode.Ssh -> SshScreen(sshState, sshActions, footer = registry)

                GatewayConnectionMode.Local -> LocalGatewayScreen(
                    state = state,
                    actions = gatewayActions,
                    footer = registry,
                )
            }
        }
    }
}

/**
 * The Local route: a Hermes the person is running on this same phone.
 *
 * There is no form here beyond the action, on purpose. The address and the
 * session token belong to a saved row, and the registry below is the one writer
 * of a saved row — a second address field on this page would be a second copy
 * of the connection, which is exactly what the registry exists to prevent. So
 * this pane states where it will dial, dials it, and hands everything else to
 * the row.
 */
@Composable
private fun LocalGatewayScreen(
    state: GatewaySettingsUiState,
    actions: GatewayActions,
    footer: @Composable ColumnScope.() -> Unit,
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
            Text(ConnectionsCopy.KIND_LOCAL, style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text(
                ConnectionsCopy.LOCAL_INTRO,
                style = HermesTheme.type.body,
                color = tokens.textSecondary,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(ConnectionsCopy.URL_TITLE)
            Text(
                // A row that names no usable address has nothing to dial, so
                // the line that would show it says what to do instead.
                text = state.local.displayEndpoint?.let(::redact)
                    ?: ConnectionsCopy.LOCAL_NO_ADDRESS,
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
            GatewayConnectionStatus.Connected ->
                PrimaryButton("Disconnect", actions.onDisconnect, Modifier.fillMaxWidth())

            GatewayConnectionStatus.Connecting -> {
                PrimaryButton("Connecting…", {}, Modifier.fillMaxWidth(), enabled = false)
                TextButton("Cancel", actions.onDisconnect, color = tokens.textTertiary)
            }

            GatewayConnectionStatus.Disconnected,
            GatewayConnectionStatus.NeedsAttention,
            -> PrimaryButton(
                "Connect",
                actions.onConnectLocal,
                Modifier.fillMaxWidth(),
                enabled = state.canConnectLocal,
            )
        }

        Text(
            ConnectionsCopy.LOCAL_LIMITATION,
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
        )

        footer()
    }
}

@Composable
private fun RemoteGatewayScreen(
    state: GatewaySettingsUiState,
    actions: GatewayActions,
    duplicateOf: String?,
    footer: @Composable ColumnScope.() -> Unit,
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
        duplicateOf?.let { label ->
            Text(
                ConnectionsCopy.duplicateUrl(label),
                style = HermesTheme.type.caption,
                color = tokens.destructive,
            )
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

        footer()
    }
}
