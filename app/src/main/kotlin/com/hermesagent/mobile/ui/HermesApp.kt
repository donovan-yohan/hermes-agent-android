package com.hermesagent.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.ssh.SshScreen
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Destinations. Chat is the home surface; Appearance and Host are short tasks
 * that return to it (`apps/desktop/DESIGN.md:50-56` @ `f82f2dba`), so they are
 * a single saved key rather than a navigation library — three destinations do
 * not earn a graph.
 */
enum class HermesDestination { Chat, Appearance, Host }

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

    // One cancel gesture does exactly one thing: leave the overlay.
    BackHandler(enabled = destination != HermesDestination.Chat) {
        destination = HermesDestination.Chat
    }

    HermesTheme(appearance) {
        when (destination) {
            HermesDestination.Chat -> ChatScreen(
                state = chatState,
                actions = chatActions,
                onOpenSettings = { destination = HermesDestination.Appearance },
            )

            HermesDestination.Appearance -> OverlayScaffold(
                title = "Appearance",
                onBack = { destination = HermesDestination.Chat },
                action = Pair("Host & SSH", { destination = HermesDestination.Host }),
            ) {
                AppearanceScreen(selection = appearance, actions = appearanceActions)
            }

            HermesDestination.Host -> OverlayScaffold(
                title = "Host & SSH",
                onBack = { destination = HermesDestination.Appearance },
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
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(Modifier.fillMaxSize().background(tokens.chatSurface)) {
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
            action?.let { (label, onClick) -> TextButton(label = label, onClick = onClick) }
        }
        Hairline()
        content()
    }
}
