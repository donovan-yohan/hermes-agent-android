package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.hermesagent.mobile.data.voice.VoiceUiState
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import androidx.compose.ui.unit.dp
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
            Triple("Transcribing", HermesIcon.Thinking, false)
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
            Modifier.size(28.dp).background(tokens.widgetSurface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            HermesIconGlyph(icon, color = if (enabled) tokens.textSecondary else tokens.textQuaternary)
        }
    }
}
