package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.hermesagent.mobile.data.voice.VoiceUiState
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Dictation control with truthful state: start, recording meter, transcribing
 * (disabled against double tap), and error recovery. 48dp target; the spoken
 * label always reflects the live state.
 */
@Composable
internal fun VoiceDictationControl(
    state: VoiceUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val (description, icon, enabled) = when (val s = state) {
        is VoiceUiState.DictationRecording ->
            Triple("Stop dictation", HermesIcon.StopCircle, true)
        is VoiceUiState.DictationTranscribing ->
            Triple("Transcribing", HermesIcon.Mic, false)
        is VoiceUiState.Error ->
            Triple(s.recovery, HermesIcon.Error, true)
        else -> Triple("Start dictation", HermesIcon.Mic, true)
    }
    Box(
        modifier
            .size(HermesTheme.spacing.touchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .testTag("Voice dictation control"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(22.dp).background(if (state is VoiceUiState.Error) tokens.destructive.copy(alpha = 0.16f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HermesIconGlyph(icon, color = if (enabled) tokens.textSecondary else tokens.textQuaternary, size = 15.sp)
        }
    }
}

/**
 * Voice conversation controls. When a conversation is active the regular
 * composer action cluster is replaced by a phase pill plus mute and end
 * controls (Desktop's voice-activity adaptation); typed composition stays
 * recoverable after End.
 */
@Composable
internal fun VoiceConversationControl(
    state: VoiceUiState.Conversation,
    onToggleConversation: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val active = state.phase != VoiceUiState.ConversationPhase.Ended
    Box(
        modifier
            .size(HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = onToggleConversation)
            .semantics {
                contentDescription = if (active) "End voice conversation" else "Start voice conversation"
            }
            .testTag("Voice conversation control"),
        contentAlignment = Alignment.Center,
    ) {
        HermesIconGlyph(
            if (active) HermesIcon.Error else HermesIcon.Mic,
            color = tokens.textSecondary,
            size = 15.sp,
        )
    }
    if (active) {
        Text(
            text = state.phase.name.lowercase().replaceFirstChar { it.uppercase() },
            style = HermesTheme.type.caption,
            color = tokens.textSecondary,
            modifier = Modifier
                .padding(start = 6.dp)
                .semantics { contentDescription = "Voice conversation ${state.phase.name.lowercase()}" }
                .testTag("Voice conversation phase"),
        )
        Box(
            Modifier
                .size(HermesTheme.spacing.touchTarget)
                .clickable(role = Role.Button, onClick = onToggleMute)
                .semantics {
                    contentDescription =
                        if (state.muted) "Unmute microphone" else "Mute microphone"
                }
                .testTag("Voice mute control"),
            contentAlignment = Alignment.Center,
        ) {
            HermesIconGlyph(
                HermesIcon.Edit,
                color = if (state.muted) tokens.destructive else tokens.textSecondary,
                size = 15.sp,
            )
        }
    }
}
