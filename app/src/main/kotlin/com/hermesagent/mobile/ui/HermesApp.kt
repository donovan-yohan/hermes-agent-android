package com.hermesagent.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.appearance.AppearanceScreen
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.gateway.ConnectionsUiState
import com.hermesagent.mobile.ui.gateway.GatewayScreen
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.relay.RelayScreen
import com.hermesagent.mobile.ui.relay.RelayUiState
import com.hermesagent.mobile.ui.sessions.ConnectionSwitcherBar
import com.hermesagent.mobile.ui.settings.SettingsScreen
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Chat is home. Settings has two short child surfaces, so a saved destination
 * is sufficient without a navigation graph.
 */
enum class HermesDestination { Chat, Settings, Appearance, Gateways, Relay }

@Composable
fun HermesApp(
    chatState: ChatUiState,
    gatewayState: GatewaySettingsUiState,
    sshState: SshUiState,
    appearance: AppearanceSelection,
    chatActions: ChatActions,
    appearanceActions: AppearanceActions,
    gatewayActions: GatewayActions,
    sshActions: SshActions,
    relayState: RelayUiState,
    relayActions: RelayActions,
    connectionsState: ConnectionsUiState = ConnectionsUiState(),
    connectionsActions: ConnectionsActions = ConnectionsActions(),
) {
    var destination by rememberSaveable { mutableStateOf(HermesDestination.Chat) }

    val onBack = { destination = destination.backDestination() }
    BackHandler(enabled = destination != HermesDestination.Chat) {
        onBack()
    }

    HermesTheme(appearance) {
        when (destination) {
            HermesDestination.Chat -> ChatScreen(
                state = chatState,
                actions = chatActions,
                onOpenSettings = { destination = HermesDestination.Settings },
                sidebarHeader = {
                    ConnectionSwitcherBar(
                        state = connectionsState,
                        actions = connectionsActions,
                        onManage = { destination = HermesDestination.Gateways },
                    )
                },
            )

            HermesDestination.Settings -> OverlayScaffold(
                title = "Settings",
                onBack = onBack,
            ) {
                SettingsScreen(
                    onOpenAppearance = { destination = HermesDestination.Appearance },
                    onOpenGateways = { destination = HermesDestination.Gateways },
                    onOpenRelay = { destination = HermesDestination.Relay },
                    relayAvailable = !relayState.unavailableOnGateway,
                )
            }

            HermesDestination.Appearance -> OverlayScaffold(
                title = "Appearance",
                onBack = onBack,
            ) {
                AppearanceScreen(selection = appearance, actions = appearanceActions)
            }

            HermesDestination.Gateways -> OverlayScaffold(
                title = "Gateways",
                onBack = onBack,
            ) {
                GatewayScreen(
                    state = gatewayState,
                    gatewayActions = gatewayActions,
                    sshState = sshState,
                    sshActions = sshActions,
                    connectionsState = connectionsState,
                    connectionsActions = connectionsActions,
                )
            }

            // Relay wears the same overlay chrome as its peers but supplies
            // its own back meaning: it drills one level deeper, and one header
            // whose affordance means "the pane you came from" is clearer than
            // two stacked back arrows.
            HermesDestination.Relay -> RelayScreen(
                state = relayState,
                actions = relayActions,
                onLeave = onBack,
                onOpenGateways = { destination = HermesDestination.Gateways },
            )
        }
    }
}

/**
 * Route overlays are short tasks: one back affordance, no nested chrome.
 *
 * A destination that drills deeper still gets exactly one header — it says so
 * through [backDescription] and its own `onBack`, rather than forking this
 * chrome or stacking a second back arrow inside it.
 */
@Composable
internal fun OverlayScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Second header line, for a destination whose title needs qualifying. */
    subtitle: String? = null,
    /** What back means here, when it is not simply leaving the destination. */
    backDescription: String = "Back",
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(
        modifier
            .fillMaxSize()
            .background(tokens.chatSurface)
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuietIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = backDescription,
                onClick = onBack,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = HermesTheme.type.screenTitle,
                    color = tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = HermesTheme.type.scaffold,
                        color = tokens.scaffoldMeta,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Hairline()
        content()
    }
}

internal fun HermesDestination.backDestination(): HermesDestination = when (this) {
    HermesDestination.Chat -> HermesDestination.Chat
    HermesDestination.Settings -> HermesDestination.Chat
    HermesDestination.Appearance,
    HermesDestination.Gateways,
    HermesDestination.Relay,
    -> HermesDestination.Settings
}
