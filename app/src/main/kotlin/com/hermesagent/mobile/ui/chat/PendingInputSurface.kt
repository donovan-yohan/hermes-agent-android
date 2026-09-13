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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import com.hermesagent.mobile.data.gateway.ApprovalPending
import com.hermesagent.mobile.data.gateway.approvalChoiceLabel
import com.hermesagent.mobile.data.gateway.isDenial
import com.hermesagent.mobile.data.gateway.ClarifyPending
import com.hermesagent.mobile.data.gateway.ClarifyQuestion
import com.hermesagent.mobile.data.gateway.PendingInputAction
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.SecretPending
import com.hermesagent.mobile.data.gateway.SudoPending
import com.hermesagent.mobile.data.gateway.VaultCodePending
import com.hermesagent.mobile.data.gateway.VaultSaveLoginPending
import com.hermesagent.mobile.data.gateway.VaultUnlockPending
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
            // Nothing inline: every one of these is answered in
            // [SecurePendingDialog], behind `FLAG_SECURE`. A card in the
            // transcript would put a password field in a screenshot.
            is SudoPending,
            is SecretPending,
            is VaultCodePending,
            is VaultSaveLoginPending,
            is VaultUnlockPending,
            -> {}
            null -> Unit
        }
        if (background != null && pending == null) {
            BackgroundPendingBanner(background, onOpenSession)
        }
    }
}

/**
 * True when this request kind must be answered in the secure dialog.
 *
 * All three vault prompts are in: two carry a password and the third a one-time
 * code, which is a bearer credential for exactly as long as it takes to use.
 * Desktop masks the first two and deliberately leaves the code visible
 * (`components/prompt-overlays.tsx:475-477` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`) — masking is about shoulders,
 * this is about the screenshot and the recents thumbnail, and the code needs
 * that protection as much as the password does.
 */
internal fun PendingInputRequest.isSecurePrompt(): Boolean = when (this) {
    is SudoPending,
    is SecretPending,
    is VaultCodePending,
    is VaultSaveLoginPending,
    is VaultUnlockPending,
    -> true
    is ClarifyPending, is ApprovalPending -> false
}

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
                // The Gateway sends wire values — `once`, `session`, `always`,
                // `deny` — and this card used to paint them straight onto the
                // buttons, which asks somebody to press `once` or `always` and
                // work out which is which. One shared mapping with the shade,
                // so the same decision never wears two different words.
                val label = approvalChoiceLabel(choice)
                val destructive = isDenial(choice)
                TextButton(
                    label = label,
                    onClick = { onRespond(PendingInputAction.ApprovalChoice(choice)) },
                    color = if (destructive) HermesTheme.tokens.destructive else HermesTheme.tokens.accentForeground,
                    modifier = Modifier
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .semantics {
                            contentDescription = "$label for ${pending.command.take(80)}"
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
 * Everything the secure dialog renders and sends for one kind of parked
 * request, so the composable below stays one layout rather than five.
 *
 * [answer] and [refuse] build the action: the dialog owns the characters and
 * hands them over exactly once, and the repository zeroes them after the call.
 */
internal class SecurePrompt(
    val title: String,
    val body: String,
    val valueLabel: String,
    val valuePlaceholder: String,
    /** False only for the one-time code, which Desktop shows as typed. */
    val maskValue: Boolean,
    /**
     * What the IME should offer for the value field. `Password` on everything
     * masked; `Number` for the one-time code, which is Desktop's own
     * `inputMode="numeric"` (`prompt-overlays.tsx:561`). A code that is not
     * digits can still be pasted — the field filters nothing.
     */
    val keyboard: KeyboardType,
    /** Non-null only for the save-login card, which answers with a pair. */
    val identifierLabel: String?,
    val identifierPlaceholder: String,
    val refuseLabel: String,
    val confirmLabel: String,
    /**
     * Whether an empty value may be *confirmed*. Sudo and secret have always
     * allowed it; Desktop's three vault cards disable their confirm until the
     * field has something in it, and route the empty answer through the other
     * button, which says what it means.
     */
    val confirmNeedsValue: Boolean,
    val refuse: () -> PendingInputAction,
    val answer: (identifier: CharArray, value: CharArray) -> PendingInputAction,
)

/**
 * Copy and wiring per kind. Desktop's strings verbatim where they fit
 * (`apps/desktop/src/i18n/en.ts:3889-3922` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`); the two descriptions that do not
 * are rewritten rather than trimmed, because Desktop's say "on this machine"
 * and on this app the vault and the password manager are on the Gateway's host,
 * not on the phone. Both are ledgered in `docs/parity/vault-prompts.md`.
 *
 * Null for a kind that is answered in the transcript instead.
 */
internal fun securePrompt(pending: PendingInputRequest): SecurePrompt? = when (pending) {
    is SudoPending -> SecurePrompt(
        title = "Sudo password",
        body = "Hermes needs your password to continue.",
        valueLabel = "Sudo password",
        valuePlaceholder = "",
        maskValue = true,
        keyboard = KeyboardType.Password,
        identifierLabel = null,
        identifierPlaceholder = "",
        refuseLabel = "Cancel",
        confirmLabel = "Send",
        confirmNeedsValue = false,
        refuse = { PendingInputAction.SudoPassword(CharArray(0)) },
        answer = { _, value -> PendingInputAction.SudoPassword(value) },
    )

    is SecretPending -> SecurePrompt(
        title = "Secret for ${pending.envVarLabel.ifBlank { "the skill" }}",
        body = pending.prompt.ifBlank { "Enter the value Hermes asked for." },
        valueLabel = "Secret for ${pending.envVarLabel.ifBlank { "the skill" }}",
        valuePlaceholder = "",
        maskValue = true,
        keyboard = KeyboardType.Password,
        identifierLabel = null,
        identifierPlaceholder = "",
        refuseLabel = "Cancel",
        confirmLabel = "Send",
        confirmNeedsValue = false,
        refuse = { PendingInputAction.SecretValue(CharArray(0)) },
        answer = { _, value -> PendingInputAction.SecretValue(value) },
    )

    is VaultUnlockPending -> SecurePrompt(
        // `en.ts:3899` verbatim.
        title = "Unlock ${pending.displayName.ifBlank { "your password manager" }}",
        body = "Hermes wants a login saved in ${pending.displayName.ifBlank { "it" }}. " +
            "Your master password unlocks it for this session and is never stored " +
            "or shown to the agent.",
        // `en.ts:3902`.
        valueLabel = "Master password",
        valuePlaceholder = "Master password",
        maskValue = true,
        keyboard = KeyboardType.Password,
        identifierLabel = null,
        identifierPlaceholder = "",
        // `en.ts:3903-3904`.
        refuseLabel = "Keep locked",
        confirmLabel = "Unlock",
        confirmNeedsValue = true,
        refuse = { PendingInputAction.VaultUnlockPassword(CharArray(0)) },
        answer = { _, value -> PendingInputAction.VaultUnlockPassword(value) },
    )

    is VaultSaveLoginPending -> SecurePrompt(
        // `en.ts:3906` verbatim.
        title = "Save your ${pending.site.ifBlank { "site" }} login?",
        body = "Hermes reached a sign-in page at ${pending.origin.ifBlank { "this site" }} " +
            "and has no login for it. Enter it once here; your Gateway encrypts it and " +
            "fills the page without the model ever seeing the password.",
        // `en.ts:3911`.
        valueLabel = "Password",
        valuePlaceholder = "",
        maskValue = true,
        keyboard = KeyboardType.Password,
        // `en.ts:3909-3910`.
        identifierLabel = "Email or username",
        identifierPlaceholder = "you@example.com",
        // `en.ts:3913-3914`.
        refuseLabel = "Don't save",
        confirmLabel = "Save & sign in",
        confirmNeedsValue = true,
        refuse = { PendingInputAction.VaultLogin(CharArray(0), CharArray(0)) },
        answer = { identifier, value -> PendingInputAction.VaultLogin(identifier, value) },
    )

    is VaultCodePending -> SecurePrompt(
        // `en.ts:3916` verbatim.
        title = "Verification code for ${pending.site.ifBlank { "this site" }}",
        // `en.ts:3917-3918` verbatim.
        body = "${pending.site.ifBlank { "This site" }} is asking for a one-time code " +
            "(text message, email or authenticator app). Enter it here and Hermes types " +
            "it into the page; the model never sees it.",
        // `en.ts:3919`.
        valueLabel = "Code",
        // `prompt-overlays.tsx:563` verbatim.
        valuePlaceholder = "123 456",
        maskValue = false,
        keyboard = KeyboardType.Number,
        identifierLabel = null,
        identifierPlaceholder = "",
        // `en.ts:3921-3922`.
        refuseLabel = "Skip",
        confirmLabel = "Enter code",
        confirmNeedsValue = true,
        refuse = { PendingInputAction.VaultCode(CharArray(0)) },
        answer = { _, value -> PendingInputAction.VaultCode(value) },
    )

    is ClarifyPending, is ApprovalPending -> null
}

/** The characters of a field, out of Compose's [String] state and into one array. */
private fun String.toSecureChars(): CharArray {
    val chars = CharArray(length)
    forEachIndexed { index, c -> chars[index] = c }
    return chars
}

/**
 * Sudo, secret and the three vault prompts. The dialog window is flagged secure
 * while composed; state is wiped synchronously before the flag clears. Values
 * never leave this file except as the one action that answers the request.
 */
@Composable
internal fun SecurePendingDialog(
    pending: PendingInputRequest,
    isSubmitting: Boolean,
    errorText: String?,
    onRespond: (PendingInputAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val prompt = securePrompt(pending) ?: return
    Dialog(onDismissRequest = {
        // System back / scrim tap: exactly one safe empty refusal. The typed
        // characters are wiped by the card's own disposal below, which is the
        // same path every other dismissal route ends on.
        onRespond(prompt.refuse())
        onDismiss()
    }) {
        SecurePromptCard(pending, prompt, isSubmitting, errorText, onRespond, onDismiss)
    }
}

/**
 * The card itself, separated from the window it usually sits in.
 *
 * Not an abstraction for its own sake: a `BasicTextField` inside a Compose
 * `Dialog` never reaches idle under Robolectric, so a journey test that drove
 * the whole dialog could only ever time out. The card is what the assertions
 * are about — the copy, the two answers, the characters that leave — and it
 * renders identically either way, because everything it reads from the window
 * it reads through [LocalView], which resolves to the dialog's decor view when
 * there is one and to the Activity's when there is not.
 */
@Composable
internal fun SecurePromptCard(
    pending: PendingInputRequest,
    prompt: SecurePrompt,
    isSubmitting: Boolean,
    errorText: String?,
    onRespond: (PendingInputAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var entered by remember(pending.key) { mutableStateOf("") }
    var identifier by remember(pending.key) { mutableStateOf("") }
    // The secure flag must live on the dialog's own window, so the effect
    // has to run inside the Dialog composition where LocalView is the
    // dialog decor view. Wipe state before the flag clears on any disposal
    // path — one effect, so the ordering is not two effects' disposal order.
    val view = LocalView.current
    DisposableEffect(view, pending.key) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: view.findWindow()
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            entered = ""
            identifier = ""
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
        Text(prompt.title, style = HermesTheme.type.screenTitle, color = tokens.textPrimary)
        Text(prompt.body, style = HermesTheme.type.body, color = tokens.textSecondary)
        errorText?.let {
            Text(it, style = HermesTheme.type.caption, color = tokens.destructive)
        }
        // Save-login only, and above the password because Desktop puts it
        // there (`prompt-overlays.tsx:439-458` @ the pin) and because a
        // password manager's own autofill offers the pair in that order.
        prompt.identifierLabel?.let { label ->
            SecureField(
                value = identifier,
                onValueChange = { identifier = it },
                label = label,
                placeholder = prompt.identifierPlaceholder,
                masked = false,
                keyboard = KeyboardType.Text,
            )
        }
        SecureField(
            value = entered,
            onValueChange = { entered = it },
            label = prompt.valueLabel,
            placeholder = prompt.valuePlaceholder,
            masked = prompt.maskValue,
            keyboard = prompt.keyboard,
        )
        // Desktop strips spaces and dashes from a typed code before
        // sending it (`prompt-overlays.tsx:535`), because a code read off a
        // text message arrives as "123 456" and the page wants six digits.
        val value = if (prompt.maskValue) entered else entered.filterNot { it.isWhitespace() || it == '-' }
        val identifierValue = identifier.trim()
        val canConfirm = !isSubmitting && (
            !prompt.confirmNeedsValue ||
                (value.isNotEmpty() && (prompt.identifierLabel == null || identifierValue.isNotEmpty()))
            )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
            TextButton(
                label = prompt.refuseLabel,
                // Exactly one safe empty refusal per dismissal route. For
                // the vault kinds this is a real answer rather than a
                // cancellation: "" keeps the manager locked, declines the
                // save, or skips the code, and the turn carries on.
                onClick = {
                    entered = ""
                    identifier = ""
                    onRespond(prompt.refuse())
                    onDismiss()
                },
                color = tokens.textSecondary,
                modifier = Modifier
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .semantics { contentDescription = prompt.refuseLabel },
            )
            TextButton(
                label = if (isSubmitting) "Sending…" else prompt.confirmLabel,
                enabled = canConfirm,
                onClick = {
                    if (!canConfirm) return@TextButton
                    val valueChars = value.toSecureChars()
                    val identifierChars = identifierValue.toSecureChars()
                    entered = ""
                    identifier = ""
                    onRespond(prompt.answer(identifierChars, valueChars))
                },
                color = tokens.accentForeground,
                modifier = Modifier
                    .heightIn(min = HermesTheme.spacing.touchTarget)
                    .semantics {
                        contentDescription = prompt.confirmLabel
                        if (!canConfirm) disabled()
                    },
            )
        }
    }
}

/**
 * One entry field inside the secure dialog.
 *
 * [masked] is not the protection here — `FLAG_SECURE` on the window is — so the
 * one field Desktop leaves visible stays visible: a one-time code is typed from
 * a text message and a typo in six masked digits is invisible until it fails
 * (`prompt-overlays.tsx:475-477` @ the pin).
 */
@Composable
private fun SecureField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    masked: Boolean,
    keyboard: KeyboardType,
) {
    val tokens = HermesTheme.tokens
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        // `KeyboardType.Password` is not cosmetic, and it has no Desktop
        // analogue to port: Android IMEs learn what is typed into an ordinary
        // text field and offer it back as a suggestion in the next app. A
        // master password in the keyboard's dictionary is the same leak this
        // surface exists to avoid, and this flag is what tells the IME not to.
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .background(tokens.widgetSurface, RoundedCornerShape(8.dp))
            .semantics { contentDescription = "$label entry" }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        decorationBox = { inner ->
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = HermesTheme.type.body, color = tokens.textTertiary)
            }
            inner()
        },
    )
}

private fun android.view.View.findWindow(): android.view.Window? {
    var context = context
    while (context is android.content.ContextWrapper) {
        if (context is android.app.Activity) return context.window
        context = context.baseContext
    }
    return null
}
