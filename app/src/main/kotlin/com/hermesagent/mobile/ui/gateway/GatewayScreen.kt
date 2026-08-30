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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.ConnectionsActions
import com.hermesagent.mobile.ui.GatewayActions
import com.hermesagent.mobile.ui.SshActions
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.ModeCard
import com.hermesagent.mobile.ui.common.ModeCardGrid
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.SecureScreenLifetime
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.common.WipPill
import com.hermesagent.mobile.ui.ssh.SshScreen
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * One of Desktop's four **Connection mode** cards
 * (`apps/desktop/src/app/settings/gateway-settings.tsx:1049-1082` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`).
 *
 * [mode] is `null` for a mode Desktop offers and this app cannot be on. That
 * is not an absence: the card still renders, disabled, behind a `WIP`
 * pill, because the parity gate's rule is that an unsupported Desktop control
 * ships visible and disabled rather than quietly missing
 * (`docs/workflows/review-desktop-parity.md`, "Classify every divergence").
 * It is also what makes such a card unselectable by construction rather than
 * by a check someone has to remember to write.
 */
internal data class GatewayModeCard(
    val mode: GatewayConnectionMode?,
    val title: String,
    val description: String,
    val icon: HermesIcon,
    val hint: String? = null,
)

/**
 * The four cards, in Desktop's order: Local gateway, Hermes Cloud, Remote
 * gateway, Connect via SSH (`gateway-settings.tsx:1049-1082` @ `f82f2dba`).
 *
 * Total over [GatewayConnectionMode], and asserted to be. The old segmented
 * control could render a `selected` value that was not among its `options` —
 * nothing lit, and no way back — which is why S-A2's review asked for this
 * list to be exhaustive. Cards remove that particular shape: each one computes
 * its own `active`, so there is no single selection to fall outside of. What
 * survives is the underlying hazard, one layer down: a saved route with *no
 * card* is a route the person cannot see they are on and cannot change. Being
 * total is still the fix, so `GatewayScreenTest` still fails if a route is
 * added without one.
 */
internal val GATEWAY_MODE_CARDS = listOf(
    GatewayModeCard(
        mode = GatewayConnectionMode.Local,
        title = GatewayModeCopy.LOCAL_TITLE,
        description = GatewayModeCopy.LOCAL_DESC,
        icon = HermesIcon.Monitor,
    ),
    GatewayModeCard(
        // No Android Hermes Cloud sign-in exists yet. Deliberately not a
        // `GatewayConnectionMode`: a mode the app cannot be on should be
        // unrepresentable as a saved route, not merely rejected at the tap.
        mode = null,
        title = GatewayModeCopy.CLOUD_TITLE,
        description = GatewayModeCopy.CLOUD_DESC,
        icon = HermesIcon.Cloud,
    ),
    GatewayModeCard(
        mode = GatewayConnectionMode.Remote,
        title = GatewayModeCopy.REMOTE_TITLE,
        description = GatewayModeCopy.REMOTE_DESC,
        icon = HermesIcon.Globe,
        hint = GatewayModeCopy.REMOTE_AUTH_HINT,
    ),
    GatewayModeCard(
        mode = GatewayConnectionMode.Ssh,
        title = GatewayModeCopy.SSH_TITLE,
        description = GatewayModeCopy.SSH_DESC,
        icon = HermesIcon.Terminal,
        hint = GatewayModeCopy.SSH_TRUST_HINT,
    ),
)

/** Every route the form above the registry can configure, in card order. */
internal val GATEWAY_ROUTE_OPTIONS = GATEWAY_MODE_CARDS.mapNotNull { it.mode }

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
    // Desktop keeps the mode grid in the page's own scroll, above the panel
    // for the chosen mode (`gateway-settings.tsx:1044-1089` @ `f82f2dba`).
    // This used to be a pinned header, which a single segmented control could
    // afford; four cards one-per-row on a phone cannot — they would leave the
    // route's own form a sliver of what is left. So the chooser travels into
    // whichever route is showing, exactly as the registry already does.
    val chooser: @Composable ColumnScope.() -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Desktop's own heading, in Desktop's casing: caption size, medium
            // weight, `--ui-text-secondary` (`gateway-settings.tsx:1045-1047`).
            // Not `SectionLabel`, which uppercases — this app's field-group
            // label is a different thing from Desktop's card-grid heading, and
            // shouting it would change the words.
            Text(
                text = GatewayModeCopy.MODE_TITLE,
                style = HermesTheme.type.caption.copy(fontWeight = FontWeight.Medium),
                color = tokens.textSecondary,
            )
            ModeCardGrid(GATEWAY_MODE_CARDS) { card ->
                ModeCard(
                    title = card.title,
                    description = card.description,
                    icon = card.icon,
                    hint = card.hint,
                    // A null mode never equals a real one, so the Cloud card
                    // is never the active one without a second guard.
                    active = card.mode == state.mode,
                    enabled = card.mode != null,
                    // Dead until the saved route has actually been read. Before
                    // that this control shows the default rather than the truth,
                    // so a tap lands as "change route" when the person meant "the
                    // one already selected" — and it rewrites the active row's
                    // kind from a `previous` that is still the default, so a
                    // Local row's session token is stranded by an erase that
                    // never sees it.
                    onSelect = {
                        val mode = card.mode
                        if (mode != null && state.loaded) gatewayActions.onModeChange(mode)
                    },
                    // The chip is drawn by one and spoken by the other: the
                    // card merges its descendants, so a pill that named itself
                    // would replace this card's name instead of following it.
                    status = if (card.mode == null) WIP_SPOKEN else null,
                    trailing = if (card.mode == null) {
                        { WipPill() }
                    } else {
                        null
                    },
                )
            }
        }
    }

    Column(modifier.fillMaxSize().background(tokens.chatSurface)) {
        Box(Modifier.weight(1f)) {
            // Desktop puts the registry at the foot of this same page, below the
            // window connection controls (`gateway-settings.tsx:1499-1502` @
            // `f82f2dba`). On a phone the page is one scroll per route, so the
            // section travels into whichever route is showing rather than
            // becoming a second, separately-scrolling band.
            // Whether the pane above this footer is offering Connect right now.
            // Both panes gate that button on the same controller status
            // (`SshScreen.kt`'s `when (state.connection.status)`), and a row
            // that cannot dial itself points at that button by name — so the
            // row must stop pointing at it once it is no longer there.
            val connectOffered = state.connection.status != GatewayConnectionStatus.Connected &&
                state.connection.status != GatewayConnectionStatus.Connecting
            val registry: @Composable ColumnScope.() -> Unit = {
                ConnectionsSection(
                    state = connectionsState,
                    actions = connectionsActions,
                    connectOffered = connectOffered,
                )
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
                    header = chooser,
                    footer = registry,
                )

                GatewayConnectionMode.Ssh -> SshScreen(sshState, sshActions, header = chooser, footer = registry)

                GatewayConnectionMode.Local -> LocalGatewayScreen(
                    state = state,
                    actions = gatewayActions,
                    header = chooser,
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
    header: @Composable ColumnScope.() -> Unit,
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
        header()

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
    header: @Composable ColumnScope.() -> Unit,
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
        header()

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
