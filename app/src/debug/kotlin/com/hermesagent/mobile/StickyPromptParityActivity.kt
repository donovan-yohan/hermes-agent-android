package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode

/** Debug-only synthetic transcript for sticky-current-prompt visual capture. */
class StickyPromptParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = if (intent.getStringExtra(EXTRA_THEME_MODE).equals("light", ignoreCase = true)) HermesThemeMode.Light else HermesThemeMode.Dark
        setContent { HermesTheme(AppearanceSelection("nous", mode)) { ChatScreen(stickyPromptFixture(), ChatActions(), {}) } }
    }

    private companion object { const val EXTRA_THEME_MODE = "theme_mode" }
}

private fun stickyPromptFixture(): ChatUiState {
    val now = 1_755_600_000_000L
    val session = SessionSummary(
        id = "synthetic-sticky-prompt",
        title = "Synthetic prompt continuity",
        preview = "Visual parity fixture",
        lastActiveAtMillis = now,
        status = SessionStatus.Working,
    )
    fun response(prefix: String) = (1..96).joinToString("\n\n") { "$prefix response paragraph $it keeps the synthetic transcript taller than the viewport." }
    return ChatUiState(
        activeSession = session,
        sessionRows = buildSessionRows(sessions = listOf(session), nowMillis = now),
        transcript = listOf(
            UserTurn("synthetic-user-first", "Summarize the first synthetic task.", now),
            AssistantTurn("synthetic-assistant-first", response("First"), now),
            UserTurn("synthetic-user-current", "Continue the current synthetic task by comparing the visible response context, recording the concrete next action, keeping the transcript readable while new paragraphs arrive, and returning to this exact request when the pinned context is tapped. This deliberately long, sanitized fixture prompt exercises the four-line clamp and soft overflow fade without using a real session, host, path, credential, or private conversation.", now),
            AssistantTurn("synthetic-assistant-current", response("Current"), now, streaming = true),
        ),
        isStreaming = true,
        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
    )
}
