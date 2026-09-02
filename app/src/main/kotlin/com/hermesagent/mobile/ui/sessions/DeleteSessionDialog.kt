package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Delete session confirmation dialog, ported from Desktop's `DeleteSessionDialog`
 * and `ConfirmDialog` (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:555-584`
 * and `components/ui/confirm-dialog.tsx` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * Title: `Delete session?` (`i18n/en.ts:2333`)
 * Description: `This will permanently delete “{title}”. This cannot be undone.` (`i18n/en.ts:2334`)
 * Busy: `Deleting…` (`i18n/en.ts:2335`)
 * Done: `Session deleted` (`i18n/en.ts:2336`)
 * Confirm button: `Delete` (`i18n/en.ts:24`)
 * Cancel button: `Cancel` (`i18n/en.ts:11`)
 */
internal const val DELETE_SESSION_DIALOG_TAG = "Delete session dialog"

private const val DELETE_TITLE = "Delete session?"
private const val DELETING_LABEL = "Deleting…"
private const val DELETE_LABEL = "Delete"
private const val CANCEL_LABEL = "Cancel"

private fun deleteSessionDescription(title: String): String {
    val displayTitle = redact(title.ifBlank { "Untitled session" })
    return "This will permanently delete “$displayTitle”. This cannot be undone."
}

@Composable
fun DeleteSessionDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    sessionId: String,
    sessionTitle: String,
    onConfirm: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) return

    val tokens = HermesTheme.tokens
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun runDelete() {
        if (deleting) return
        deleting = true
        errorMessage = null
        scope.launch {
            try {
                onConfirm()
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                errorMessage = e.message ?: "Delete failed. Check the Gateway and try again."
                deleting = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = modifier
                .padding(24.dp)
                .fillMaxWidth()
                .background(tokens.cardSurface, RoundedCornerShape(10.dp))
                .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp))
                .padding(20.dp)
                .testTag(DELETE_SESSION_DIALOG_TAG),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = DELETE_TITLE,
                    style = HermesTheme.type.bodyStrong,
                    color = tokens.textPrimary,
                )
                Text(
                    text = deleteSessionDescription(sessionTitle),
                    style = HermesTheme.type.body,
                    color = tokens.textSecondary,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        style = HermesTheme.type.caption,
                        color = tokens.destructive,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        label = CANCEL_LABEL,
                        onClick = onDismiss,
                        enabled = !deleting,
                        color = tokens.textTertiary,
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    PrimaryButton(
                        label = if (deleting) DELETING_LABEL else DELETE_LABEL,
                        onClick = { runDelete() },
                        enabled = !deleting,
                        container = tokens.destructive,
                    )
                }
            }
        }
    }
}
