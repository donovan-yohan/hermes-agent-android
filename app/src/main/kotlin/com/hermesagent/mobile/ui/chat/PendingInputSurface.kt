package com.hermesagent.mobile.ui.chat

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.hermesagent.mobile.data.gateway.ApprovalPending
import com.hermesagent.mobile.data.gateway.ClarifyPending
import com.hermesagent.mobile.data.gateway.ClarifyQuestion
import com.hermesagent.mobile.data.gateway.PendingInputAction
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.SecretPending
import com.hermesagent.mobile.data.gateway.SudoPending
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Required-action surfaces for a parked turn. Requests are repository memory;
 * this only renders the pending projection and reports one deliberate action.
 */
@Composable
internal fun PendingInputSurface(
    pending: PendingInputRequest?,
    background: BackgroundPendingInput?,
    isSubmitting: Boolean,
    onRespond: (PendingInputAction) -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (pending) {
            is ClarifyPending -> ClarifyCard(pending, isSubmitting, onRespond)
            is ApprovalPending -> ApprovalCard(pending, isSubmitting, onRespond)
            is SudoPending, is SecretPending -> {}
            null -> Unit
        }
        if (background != null && pending == null) {
            BackgroundPendingBanner(background, onOpenSession)
        }
    }
}

/** True when this request kind must be answered in the secure dialog. */
internal fun PendingInputRequest.isSecurePrompt(): Boolean =
    this is SudoPending || this is SecretPending

@Composable
private fun PendingCard(
    title: String,
    body: String,
    tag: String,
    content: @Composable () -> Unit,
) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .background(tokens.widgetSurface, RoundedCornerShape(12.dp))
            .testTag(tag)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = HermesTheme.type.sectionLabel, color = tokens.textPrimary)
        Text(body, style = HermesTheme.type.body, color = tokens.textSecondary)
        content()
    }
}

@Composable
private fun ClarifyCard(
    pending: ClarifyPending,
    isSubmitting: Boolean,
    onRespond: (PendingInputAction) -> Unit,
) {
    val tokens = HermesTheme.tokens
    var answer by remember(pending.key) { mutableStateOf("") }
    val questions = pending.questions.ifEmpty {
        listOf(ClarifyQuestion("", pending.question, pending.choices, pending.multiSelect))
    }
    PendingCard(
        title = "Hermes has a question",
        body = questions.joinToString("\n") { it.question },
        tag = "Composer clarify card",
    ) {
        questions.forEach { question ->
            question.choices.forEach { choice ->
                TextButton(
                    label = choice,
                    onClick = {
                        onRespond(
                            PendingInputAction.ClarifyAnswer(
                                answers = if (question.questionId.isBlank()) {
                                    mapOf("" to choice)
                                } else {
                                    mapOf(question.questionId to choice)
                                },
                            ),
                        )
                    },
                    color = tokens.accentForeground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .semantics { contentDescription = "Answer ${question.question}: $choice" },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicTextField(
                value = answer,
                onValueChange = { answer = it },
                enabled = !isSubmitting,
                singleLine = true,
                textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(tokens.chatSurface, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "Your answer" }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                decorationBox = { inner ->
                    if (answer.isEmpty()) {
                        Text("Type an answer", style = HermesTheme.type.body, color = tokens.textTertiary)
                    }
                    inner()
                },
            )
            val canSendAnswer = !isSubmitting && answer.isNotBlank()
            TextButton(
                label = "Send",
                onClick = {
                    // A blank send must never read as the batch-wide cancel.
                    if (!canSendAnswer) return@TextButton
                    val qid = questions.singleOrNull()?.questionId.orEmpty()
                    onRespond(PendingInputAction.ClarifyAnswer(mapOf(qid to answer)))
                    answer = ""
                },
                color = tokens.accentForeground,
                modifier = Modifier
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .semantics {
                        contentDescription = "Send typed answer"
                        if (!canSendAnswer) disabled()
                    },
            )
        }
    }
}

@Composable
private fun ApprovalCard(
    pending: ApprovalPending,
    isSubmitting: Boolean,
    onRespond: (PendingInputAction) -> Unit,
) {
    var showCommand by remember(pending.key) { mutableStateOf(false) }
    PendingCard(
        title = "Hermes needs your approval to run:",
        body = if (showCommand) pending.command else "Review the command before allowing it.",
        tag = "Composer approval card",
    ) {
        TextButton(
            label = if (showCommand) "Hide command" else "Show command",
            onClick = { showCommand = !showCommand },
            color = HermesTheme.tokens.textSecondary,
            modifier = Modifier
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .semantics {
                    contentDescription = if (showCommand) "Hide command" else "Show command"
                },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pending.choices.forEach { choice ->
                val destructive = choice.equals("Reject", ignoreCase = true) ||
                    choice.equals("Deny", ignoreCase = true)
                TextButton(
                    label = choice,
                    onClick = { onRespond(PendingInputAction.ApprovalChoice(choice)) },
                    color = if (destructive) HermesTheme.tokens.destructive else HermesTheme.tokens.accentForeground,
                    modifier = Modifier
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .semantics {
                            contentDescription = "$choice for ${pending.command.take(80)}"
                            role = Role.Button
                        },
                )
            }
        }
    }
}

/** Pinned banner for a required action owned by a session that is not on screen. */
@Composable
private fun BackgroundPendingBanner(
    background: BackgroundPendingInput,
    onOpenSession: (String) -> Unit,
) {
    val tokens = HermesTheme.tokens
    Row(
        Modifier
            .fillMaxWidth()
            .background(tokens.widgetSurface, RoundedCornerShape(12.dp))
            .testTag("Composer background pending banner")
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Waiting for your answer in “${background.sessionTitle}”.",
            style = HermesTheme.type.scaffoldMeta,
            color = tokens.scaffoldMeta,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(
            label = "View",
            onClick = { onOpenSession(background.durableSessionId) },
            color = tokens.textPrimary,
            modifier = Modifier
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .semantics { contentDescription = "Open ${background.sessionTitle} to answer" },
        )
    }
}

/**
 * Sudo/secret entry. The dialog window is flagged secure while composed; state
 * is wiped synchronously before the flag clears. Values never leave this file.
 */
@Composable
internal fun SecurePendingDialog(
    pending: PendingInputRequest,
    isSubmitting: Boolean,
    errorText: String?,
    onRespond: (PendingInputAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var entered by remember(pending.key) { mutableStateOf("") }
    // The secure flag must live on the dialog's own window, so the effect has
    // to run inside the Dialog composition where LocalView is the dialog
    // decor view. Wipe state before the flag clears on any disposal path.
    val kindLabel = when (pending) {
        is SudoPending -> "Sudo password"
        is SecretPending -> "Secret for ${pending.envVarLabel.ifBlank { "the skill" }}"
        else -> return
    }
    val promptLine = when (pending) {
        is SecretPending -> pending.prompt.ifBlank { "Enter the value Hermes asked for." }
        else -> "Hermes needs your password to continue."
    }
    fun safeRefusal() = when (pending) {
        is SudoPending -> PendingInputAction.SudoPassword(CharArray(0))
        else -> PendingInputAction.SecretValue(CharArray(0))
    }
    Dialog(onDismissRequest = {
        // System back / scrim tap: exactly one safe empty refusal.
        entered = ""
        onRespond(safeRefusal())
        onDismiss()
    }) {
        val view = LocalView.current
        DisposableEffect(view, pending.key) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: view.findWindow()
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                entered = ""
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val tokens = HermesTheme.tokens
        Column(
            Modifier
                .background(tokens.cardSurface, RoundedCornerShape(16.dp))
                .testTag("Secure pending dialog")
                // Same reason as `ProjectCreateDialog`: a dialog keeps
                // `decorFitsSystemWindows`, so the keyboard resizes this window
                // instead of drawing over it and the IME inset here is zero.
                // This is the surface least able to afford the resize going
                // wrong — the keyboard is up for its whole life, and what the
                // shorter window would push out of reach is the row holding
                // Cancel. Only the scroll offset lives here; the secret does
                // not.
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(kindLabel, style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
            Text(promptLine, style = HermesTheme.type.body, color = tokens.textSecondary)
            errorText?.let {
                Text(it, style = HermesTheme.type.caption, color = tokens.destructive)
            }
            BasicTextField(
                value = entered,
                onValueChange = { entered = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = "$kindLabel entry" }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    label = "Cancel",
                    // Exactly one safe empty refusal per dismissal route.
                    onClick = {
                        val empty = when (pending) {
                            is SudoPending -> PendingInputAction.SudoPassword(CharArray(0))
                            else -> PendingInputAction.SecretValue(CharArray(0))
                        }
                        onRespond(empty)
                        onDismiss()
                    },
                    color = tokens.textSecondary,
                    modifier = Modifier.heightIn(min = HermesTheme.spacing.touchTarget),
                )
                TextButton(
                    label = if (isSubmitting) "Sending…" else "Send",
                    onClick = {
                        val chars = CharArray(entered.length)
                        entered.forEachIndexed { index, c -> chars[index] = c }
                        entered = ""
                        val action = when (pending) {
                            is SudoPending -> PendingInputAction.SudoPassword(chars)
                            else -> PendingInputAction.SecretValue(chars)
                        }
                        onRespond(action)
                    },
                    color = tokens.accentForeground,
                    modifier = Modifier.heightIn(min = HermesTheme.spacing.touchTarget),
                )
            }
        }
    }
}

private fun android.view.View.findWindow(): android.view.Window? {
    var context = context
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context.window
        context = context.baseContext
    }
    return null
}
