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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Rename session dialog, ported from Desktop's `RenameSessionDialog`
 * (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:637-720` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * Title: `Rename session` (`i18n/en.ts:2330`)
 * Helper: `Leave empty to clear.` (`i18n/en.ts:2331`)
 * Placeholder: `Untitled session` (`i18n/en.ts:2332`)
 * Save: `Save` (`i18n/en.ts:9`)
 * Cancel: `Cancel` (`i18n/en.ts:11`)
 *
 * The field's text is the one title on this surface that is **not** passed
 * through `redact()`, and deliberately: it is what the person is editing. A
 * redacted seed would either be saved back over the real title or have to be
 * un-redacted before it was sent, and both are worse than showing someone the
 * title they already own. Nothing here leaves the screen — [DeleteSessionDialog]
 * renders a title it merely *describes*, and that one is redacted. Ledgered as
 * a mobile-adaptation row in `docs/parity/session-actions-menu.md`.
 */
internal const val RENAME_SESSION_DIALOG_TAG = "Rename session dialog"
internal const val RENAME_SESSION_INPUT_TAG = "Rename session input"

private const val RENAME_TITLE = "Rename session"
private const val RENAME_DESC = "Leave empty to clear."
private const val UNTITLED_PLACEHOLDER = "Untitled session"
private const val SAVE_LABEL = "Save"
private const val CANCEL_LABEL = "Cancel"

@Composable
fun RenameSessionDialog(
    open: Boolean,
    onDismiss: () -> Unit,
    sessionId: String,
    currentTitle: String,
    onConfirm: suspend (newTitle: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) return

    val tokens = HermesTheme.tokens
    val scope = rememberCoroutineScope()
    var value by rememberSaveable(sessionId, currentTitle) { mutableStateOf(currentTitle) }
    var submitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (submitting) return
        val trimmed = value.trim()
        if (trimmed == currentTitle.trim()) {
            onDismiss()
            return
        }
        submitting = true
        errorMessage = null
        scope.launch {
            try {
                onConfirm(trimmed)
                onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                errorMessage = e.message ?: "Rename failed. Check the Gateway and try again."
                submitting = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = modifier
                .padding(24.dp)
                .fillMaxWidth()
                .background(tokens.cardSurface, RoundedCornerShape(10.dp))
                .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp))
                .padding(20.dp)
                .testTag(RENAME_SESSION_DIALOG_TAG),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = RENAME_TITLE,
                    style = HermesTheme.type.bodyStrong,
                    color = tokens.textPrimary,
                )
                Text(
                    text = RENAME_DESC,
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                )
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        errorMessage = null
                    },
                    singleLine = true,
                    enabled = !submitting,
                    textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .testTag(RENAME_SESSION_INPUT_TAG)
                        .semantics { contentDescription = RENAME_TITLE },
                    decorationBox = { editor ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    UNTITLED_PLACEHOLDER,
                                    style = HermesTheme.type.body,
                                    color = tokens.textQuaternary,
                                )
                            }
                            editor()
                        }
                    },
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
                        enabled = !submitting,
                        color = tokens.textTertiary,
                    )
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    PrimaryButton(
                        label = SAVE_LABEL,
                        onClick = { submit() },
                        enabled = !submitting,
                    )
                }
            }
        }
    }
}
