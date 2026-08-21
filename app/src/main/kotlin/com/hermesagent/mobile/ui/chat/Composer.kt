package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.R
import com.hermesagent.mobile.ui.common.CenteredTextFieldContent
import com.hermesagent.mobile.ui.theme.HermesTheme

private const val IME_PROCESS_KEY_CODE = 229

internal enum class ComposerLayoutMode { Full, Compact, Stacked }
internal enum class ComposerKeyAction { None, Send, Stop }

/** Desktop's measured container thresholds, intentionally expressed in dp. */
internal fun composerLayoutMode(width: Dp): ComposerLayoutMode = when {
    width > 560.dp -> ComposerLayoutMode.Full
    width > 320.dp -> ComposerLayoutMode.Compact
    else -> ComposerLayoutMode.Stacked
}

internal fun composerKeyAction(
    keyCode: Int,
    isKeyDown: Boolean,
    hasModifier: Boolean,
    isSoftKeyboard: Boolean,
    isComposing: Boolean,
    isStreaming: Boolean,
    canSend: Boolean,
): ComposerKeyAction {
    if (!isKeyDown || hasModifier || isSoftKeyboard || isComposing ||
        keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN || keyCode == IME_PROCESS_KEY_CODE
    ) return ComposerKeyAction.None
    return when (keyCode) {
        android.view.KeyEvent.KEYCODE_ENTER -> if (!isStreaming && canSend) ComposerKeyAction.Send else ComposerKeyAction.None
        android.view.KeyEvent.KEYCODE_ESCAPE -> if (isStreaming) ComposerKeyAction.Stop else ComposerKeyAction.None
        else -> ComposerKeyAction.None
    }
}

/** Mobile adapts Desktop's dock grammar, while soft-IME Enter remains a newline. */
@Composable
fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    canSend: Boolean,
    modifier: Modifier = Modifier,
    statusLine: String,
    runningOwnerTitle: String? = null,
    onViewRunningOwner: (() -> Unit)? = null,
) {
    val tokens = HermesTheme.tokens
    BoxWithConstraints(modifier.fillMaxWidth().background(tokens.chatSurface)) {
        val layoutMode = composerLayoutMode(maxWidth)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 5.dp)
                .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(16.dp))
                .background(tokens.cardSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .testTag("Composer shell ${layoutMode.name}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (layoutMode == ComposerLayoutMode.Stacked) {
                ComposerEditor(draft, onDraftChange, onSend, onStop, isStreaming, canSend, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ComposerPrimaryAction(onSend, onStop, isStreaming, canSend)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ComposerEditor(draft, onDraftChange, onSend, onStop, isStreaming, canSend, Modifier.weight(1f))
                    ComposerTrailingSlot(layoutMode)
                    ComposerPrimaryAction(onSend, onStop, isStreaming, canSend)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = statusLine,
                    style = HermesTheme.type.scaffoldMeta,
                    color = tokens.scaffoldMeta,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (runningOwnerTitle != null && onViewRunningOwner != null) {
                    Box(
                        modifier = Modifier
                            .size(HermesTheme.spacing.touchTarget)
                            .clickable(onClick = onViewRunningOwner)
                            .semantics { contentDescription = "View running session $runningOwnerTitle" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("View", style = HermesTheme.type.scaffoldMeta, color = tokens.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerEditor(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    canSend: Boolean,
    modifier: Modifier,
) {
    val tokens = HermesTheme.tokens
    var editorValue by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    LaunchedEffect(draft) {
        if (editorValue.text != draft) editorValue = TextFieldValue(draft, TextRange(draft.length))
    }
    BasicTextField(
        value = editorValue,
        onValueChange = { value ->
            editorValue = value
            onDraftChange(value.text)
        },
        textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
        cursorBrush = SolidColor(tokens.composerRing),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
        keyboardActions = KeyboardActions(),
        maxLines = 6,
        modifier = modifier
            .widthIn(min = HermesTheme.spacing.touchTarget)
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .testTag("Composer field shell")
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val action = composerKeyAction(
                    keyCode = native.keyCode,
                    isKeyDown = event.type == KeyEventType.KeyDown,
                    hasModifier = native.isShiftPressed || native.isCtrlPressed || native.isMetaPressed || native.isAltPressed,
                    isSoftKeyboard = native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0,
                    isComposing = editorValue.composition != null,
                    isStreaming = isStreaming,
                    canSend = canSend,
                )
                when (action) {
                    ComposerKeyAction.Send -> { onSend(); true }
                    ComposerKeyAction.Stop -> { onStop(); true }
                    ComposerKeyAction.None -> false
                }
            }
            .semantics { contentDescription = "Message Hermes" },
        decorationBox = { inner ->
            CenteredTextFieldContent(
                isEmpty = draft.isEmpty(),
                contentTag = "Composer text content",
                horizontalPadding = 6.dp,
                placeholder = { Text("Message Hermes", style = HermesTheme.type.body, color = tokens.textTertiary) },
                innerTextField = inner,
            )
        },
    )
}

/** Slice 3 fills this measured control seam; slice 2 deliberately ships no fake model control. */
@Composable
private fun ComposerTrailingSlot(@Suppress("UNUSED_PARAMETER") layoutMode: ComposerLayoutMode) = Unit

@Composable
private fun ComposerPrimaryAction(onSend: () -> Unit, onStop: () -> Unit, streaming: Boolean, canSend: Boolean) {
    val tokens = HermesTheme.tokens
    val enabled = streaming || canSend
    Box(
        Modifier
            .size(HermesTheme.spacing.touchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled) { if (streaming) onStop() else onSend() }
            .semantics(mergeDescendants = true) {
                contentDescription = if (streaming) "Stop generating" else "Send message"
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .background(if (enabled) tokens.textPrimary else tokens.textPrimary.copy(alpha = .25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (streaming) {
                Box(Modifier.size(10.dp).background(tokens.cardSurface, RoundedCornerShape(3.dp)))
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_codicon_arrow_up),
                    contentDescription = null,
                    tint = tokens.cardSurface,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
