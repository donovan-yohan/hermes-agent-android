package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.data.notifications.NotificationCopy
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Says what the OS grant is for, before the OS asks for it.
 *
 * Desktop has no equivalent — Electron notifications need no runtime grant —
 * so the copy is the settings panel's own vocabulary rather than a second
 * description of the same feature. It is shown once, because the request
 * behind it can only be made once.
 */
@Composable
fun NotificationPermissionPrompt(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = HermesTheme.tokens

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(tokens.cardSurface, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                NotificationCopy.PERMISSION_RATIONALE_TITLE,
                style = HermesTheme.type.bodyStrong,
                color = tokens.textPrimary,
            )
            Text(
                NotificationCopy.PERMISSION_RATIONALE_BODY,
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    label = NotificationCopy.PERMISSION_RATIONALE_DISMISS,
                    onClick = onDismiss,
                    color = tokens.textSecondary,
                )
                PrimaryButton(
                    label = NotificationCopy.PERMISSION_RATIONALE_ALLOW,
                    onClick = onContinue,
                )
            }
        }
    }
}
