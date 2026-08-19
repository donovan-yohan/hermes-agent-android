package com.hermesagent.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.appearance.AppearanceScreen
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.settings.SettingsScreen
import com.hermesagent.mobile.ui.ssh.SshScreen
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Chat is home. Settings has two Phase-1 children, so a saved destination is
 * sufficient without a navigation graph.
 */
enum class HermesDestination { Chat, Settings, Appearance, Gateways }

@Composable
fun HermesApp(
    chatState: ChatUiState,
    sshState: SshUiState,
    appearance: AppearanceSelection,
    chatActions: ChatActions,
    appearanceActions: AppearanceActions,
    sshActions: SshActions,
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
            )

            HermesDestination.Settings -> OverlayScaffold(
                title = "Settings",
                onBack = onBack,
            ) {
                SettingsScreen(
                    onOpenAppearance = { destination = HermesDestination.Appearance },
                    onOpenGateways = { destination = HermesDestination.Gateways },
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
                SshScreen(state = sshState, actions = sshActions)
            }
        }
    }
}

/** Route overlays are short tasks: one back affordance, no nested chrome. */
@Composable
private fun OverlayScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
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
                contentDescription = "Back",
                onClick = onBack,
            )
            Text(
                text = title,
                style = HermesTheme.type.screenTitle,
                color = tokens.textPrimary,
                modifier = Modifier.weight(1f),
            )
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
    -> HermesDestination.Settings
}
