package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.ui.common.FilledActionVariant
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Copy: apps/desktop/src/i18n/en.ts:5040-5045 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
@Composable
internal fun ModelChangeDialog(state: ComposerMutationUiState.ConfirmationRequired, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val tokens = HermesTheme.tokens
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(tokens.cardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp)).padding(20.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.model.isBlank()) "Switch models?" else "Switch to ${state.model}?", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            Text(state.safeMessage.ifBlank { "This model switch needs confirmation." }, style = HermesTheme.type.body, color = tokens.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton("Keep current model", onClick = onDismiss)
                PrimaryButton("Switch anyway", onClick = onConfirm, variant = FilledActionVariant.Destructive)
            }
        }
    }
}
