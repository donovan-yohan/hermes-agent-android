package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TurnErrorDetails
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/** Synthetic presentation fixture; retry records a callback, never contacts a Gateway. */
class TurnErrorParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = if (intent.getStringExtra("visual_parity_theme") == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent {
            var retried by remember { mutableStateOf(false) }
            HermesTheme(AppearanceSelection("mono", mode)) {
                ChatScreen(
                    state = ChatUiState(
                        activeSession = SessionSummary(
                            id = "recovery-fixture", title = if (retried) "Retry callback received" else "Error recovery",
                            preview = "", lastActiveAtMillis = 1,
                        ),
                        transcript = listOf(
                            UserTurn("source", "Try this", 1),
                            AssistantTurn(
                                "failed-reply", "", 2,
                                error = "Hermes hit an internal problem starting this reply. Send your message again. If it keeps happening, use Desktop to send diagnostics.",
                                errorDetails = TurnErrorDetails("Internal reply startup failed.", "gateway", "internal_start"),
                            ),
                        ),
                        isStreaming = false,
                        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
                    ),
                    actions = ChatActions(onRegenerateReply = { retried = true }),
                    onOpenSettings = {},
                )
            }
        }
    }
}
