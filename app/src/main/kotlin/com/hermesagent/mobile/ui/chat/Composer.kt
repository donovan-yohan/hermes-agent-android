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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.data.voice.VoiceUiState
import com.hermesagent.mobile.ui.chat.composer.AttachmentChipRow
import com.hermesagent.mobile.ui.chat.composer.VoiceConversationControl
import com.hermesagent.mobile.ui.chat.composer.VoiceDictationControl
import com.hermesagent.mobile.ui.common.CenteredTextFieldContent
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.chat.composer.CompletionPopup
import com.hermesagent.mobile.ui.chat.composer.ComposerAddControl
import com.hermesagent.mobile.ui.chat.composer.ModelControl
import com.hermesagent.mobile.ui.chat.composer.canonicalizeComposerTextOnSpace
import com.hermesagent.mobile.ui.chat.composer.replaceComposerRange
import com.hermesagent.mobile.ui.chat.composer.visibleCompletionItems
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.ui.theme.HermesTheme

private const val IME_PROCESS_KEY_CODE = 229

internal enum class ComposerLayoutMode { Full, Compact, Stacked }
internal enum class ComposerKeyAction { None, Consume, Send, Redirect, SendNext, Queue, Stop }

/** Desktop's measured container thresholds, intentionally expressed in dp. */
internal fun composerLayoutMode(width: Dp): ComposerLayoutMode = when {
    width > 560.dp -> ComposerLayoutMode.Full
    width > 320.dp -> ComposerLayoutMode.Compact
    else -> ComposerLayoutMode.Stacked
}

internal fun composerKeyAction(
    keyCode: Int,
    isKeyDown: Boolean,
    isShiftPressed: Boolean,
    isCtrlOrMetaPressed: Boolean,
    isAltPressed: Boolean,
    isSoftKeyboard: Boolean,
    isComposing: Boolean,
    isStreaming: Boolean,
    canSend: Boolean,
    primaryAction: ComposerPrimaryAction = if (isStreaming) ComposerPrimaryAction.Stop else if (canSend) ComposerPrimaryAction.Send else ComposerPrimaryAction.None,
    canQueue: Boolean = false,
    isOverlayFocused: Boolean = false,
    isNeedsInput: Boolean = false,
): ComposerKeyAction {
    if (!isKeyDown || isSoftKeyboard || isComposing ||
        keyCode == android.view.KeyEvent.KEYCODE_UNKNOWN || keyCode == IME_PROCESS_KEY_CODE
    ) return ComposerKeyAction.None
    if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && isCtrlOrMetaPressed) {
        return if (canQueue && !isOverlayFocused) ComposerKeyAction.Queue else ComposerKeyAction.Consume
    }
    if (isShiftPressed || isCtrlOrMetaPressed || isAltPressed) return ComposerKeyAction.None
    return when (keyCode) {
        android.view.KeyEvent.KEYCODE_ENTER -> when (primaryAction) {
            ComposerPrimaryAction.Send -> ComposerKeyAction.Send
            ComposerPrimaryAction.Redirect -> ComposerKeyAction.Redirect
            ComposerPrimaryAction.SendNext -> ComposerKeyAction.SendNext
            ComposerPrimaryAction.Queue -> ComposerKeyAction.Queue
            ComposerPrimaryAction.None,
            ComposerPrimaryAction.Stop,
            -> ComposerKeyAction.None
        }
        android.view.KeyEvent.KEYCODE_ESCAPE -> if (
            isStreaming && !isOverlayFocused && !isNeedsInput
        ) ComposerKeyAction.Stop else ComposerKeyAction.None
        else -> ComposerKeyAction.None
    }
}

internal fun reconcileComposerEditorValue(
    editorValue: TextFieldValue,
    externalText: String,
    pendingLocalTexts: ArrayDeque<String>,
): TextFieldValue {
    val acknowledgedIndex = pendingLocalTexts.indexOf(externalText)
    if (acknowledgedIndex >= 0) {
        repeat(acknowledgedIndex + 1) { pendingLocalTexts.removeFirst() }
        return editorValue
    }
    if (editorValue.text == externalText) return editorValue
    pendingLocalTexts.clear()
    return TextFieldValue(externalText, TextRange(externalText.length))
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
    connected: Boolean,
    modifier: Modifier = Modifier,
    statusLine: String,
    editorIdentity: String? = null,
    controls: ComposerUiState = ComposerUiState(),
    onSelectModel: (ComposerModelSelection) -> Unit = {},
    onSelectReasoning: (ReasoningEffort) -> Unit = {},
    onSelectFast: (FastMode) -> Unit = {},
    onEditorSelectionChange: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit = { _, _, _ -> },
    onCompletionSelected: (CompletionItem) -> Unit = {},
    onInsertText: (String) -> Unit = {},
    onPickFiles: () -> Unit = {},
    attachments: List<ComposerAttachmentDraft> = emptyList(),
    attachmentThumbnails: Map<String, ImageBitmap> = emptyMap(),
    onRemoveAttachment: (String) -> Unit = {},
    voiceState: VoiceUiState = VoiceUiState.Idle,
    onToggleDictation: () -> Unit = {},
    onToggleConversation: () -> Unit = {},
    onToggleMute: () -> Unit = {},
    busyKind: ComposerBusyKind = if (isStreaming) ComposerBusyKind.Streaming else ComposerBusyKind.Idle,
    queueCount: Int = 0,
    canRedirect: Boolean = isStreaming,
    canQueue: Boolean = false,
    onRedirect: () -> Unit = {},
    onQueue: () -> Unit = {},
    onSendNext: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Boolean = { false },
    onRedo: () -> Boolean = { false },
    onHistoryOlder: () -> Boolean = { false },
    onHistoryNewer: () -> Boolean = { false },
) {
    val tokens = HermesTheme.tokens
    val editorFocusRequester = remember(editorIdentity) { FocusRequester() }
    var focusRequestGeneration by remember(editorIdentity) { mutableStateOf(0) }
    LaunchedEffect(focusRequestGeneration) {
        if (focusRequestGeneration > 0) editorFocusRequester.requestFocus()
    }
    val restoreEditorFocus = { focusRequestGeneration += 1 }
    // The + control sits in the bottom bar while cursor-aware insertion lives
    // inside the editor; the editor publishes its inserter here on identity.
    var editorInserter by remember(editorIdentity) {
        mutableStateOf<((String) -> Boolean)?>(null)
    }
    val action = composerActionState(
        connected = connected,
        busyKind = busyKind,
        hasText = draft.isNotBlank(),
        canSend = canSend,
        redirectEligible = canRedirect,
        queueCount = queueCount,
    )
    val performPrimary = {
        when (action.primary) {
            ComposerPrimaryAction.Send -> onSend()
            ComposerPrimaryAction.Redirect -> onRedirect()
            ComposerPrimaryAction.Stop -> onStop()
            ComposerPrimaryAction.SendNext -> onSendNext()
            ComposerPrimaryAction.Queue -> onQueue()
            ComposerPrimaryAction.None -> Unit
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().background(tokens.chatSurface)) {
        val layoutMode = composerLayoutMode(maxWidth)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 5.dp)
                .border(1.dp, tokens.strokeSecondary, RoundedCornerShape(16.dp))
                .background(tokens.cardSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 4.dp, vertical = 5.dp)
                .testTag("Composer shell ${layoutMode.name}"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            ComposerEditor(
                draft,
                onDraftChange,
                action.primary,
                canQueue,
                performPrimary,
                onQueue,
                onStop,
                busyKind == ComposerBusyKind.Streaming,
                busyKind == ComposerBusyKind.NeedsInput,
                onHistoryOlder,
                onHistoryNewer,
                onUndo,
                onRedo,
                editorIdentity,
                controls,
                onEditorSelectionChange,
                onCompletionSelected,
                onInsertText,
                attachments,
                attachmentThumbnails,
                onRemoveAttachment,
                voiceState,
                editorFocusRequester,
                Modifier.fillMaxWidth(),
                onRegisterInserter = { inserter -> editorInserter = inserter },
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ComposerAddControl(
                    onInsertText = { text ->
                        val inserted = editorInserter?.invoke(text) ?: false
                        if (inserted) restoreEditorFocus()
                    },
                    enabled = true,
                    onPickFiles = onPickFiles,
                    onDismiss = restoreEditorFocus,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (action.showQueueSecondary) ComposerSecondaryQueueAction(onQueue)
                    if (layoutMode == ComposerLayoutMode.Full) {
                        Text(
                            text = statusLine,
                            style = HermesTheme.type.scaffoldMeta,
                            color = tokens.scaffoldMeta,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    } else {
                        ComposerModelControl(controls, onSelectModel, onSelectReasoning, onSelectFast)
                    }
                    VoiceActionCluster(
                        voiceState = voiceState,
                        onToggleDictation = {
                            onToggleDictation()
                            restoreEditorFocus()
                        },
                        onToggleConversation = onToggleConversation,
                        onToggleMute = onToggleMute,
                    )
                    ComposerPrimaryControl(action.primary, performPrimary)
                }
            }
        }
    }
}

@Composable
private fun ComposerModelControl(
    controls: ComposerUiState,
    onSelectModel: (ComposerModelSelection) -> Unit,
    onSelectReasoning: (ReasoningEffort) -> Unit,
    onSelectFast: (FastMode) -> Unit,
) {
    ModelControl(
        catalog = (controls.catalog as? ComposerCatalogUiState.Ready)?.catalog,
        controls = controls.controls,
        isLiveSession = controls.isLiveSession,
        isManualNewDraft = controls.isManualNewDraft,
        isLoading = controls.catalog is ComposerCatalogUiState.Loading,
        error = (controls.catalog as? ComposerCatalogUiState.Error)?.safeMessage ?: (controls.mutation as? ComposerMutationUiState.Error)?.safeMessage,
        isSaving = controls.mutation is ComposerMutationUiState.Saving,
        isDeferred = controls.mutation is ComposerMutationUiState.Deferred,
        onSelectModel = onSelectModel,
        onSelectReasoning = onSelectReasoning,
        onSelectFast = onSelectFast,
        singleLine = true,
    )
}

/** Voice controls for the bottom action bar, in the user's requested order. */
@Composable
private fun VoiceActionCluster(
    voiceState: VoiceUiState,
    onToggleDictation: () -> Unit,
    onToggleConversation: () -> Unit,
    onToggleMute: () -> Unit,
) {
    if (voiceState is VoiceUiState.Conversation) {
        // Desktop parity: an active conversation replaces the regular action
        // cluster with phase/mute/end; typed composition stays recoverable.
        VoiceConversationControl(
            state = voiceState,
            onToggleConversation = onToggleConversation,
            onToggleMute = onToggleMute,
        )
    } else {
        VoiceDictationControl(
            state = voiceState,
            onToggle = onToggleDictation,
        )
    }
}

@Composable
private fun ComposerEditor(
    draft: String,
    onDraftChange: (String) -> Unit,
    primaryAction: ComposerPrimaryAction,
    canQueue: Boolean,
    onPrimary: () -> Unit,
    onQueue: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    isNeedsInput: Boolean,
    onHistoryOlder: () -> Boolean,
    onHistoryNewer: () -> Boolean,
    onUndo: () -> Boolean,
    onRedo: () -> Boolean,
    editorIdentity: String?,
    controls: ComposerUiState,
    onEditorSelectionChange: (text: String, selectionStart: Int, selectionEnd: Int) -> Unit,
    onCompletionSelected: (CompletionItem) -> Unit,
    onInsertText: (String) -> Unit,
    attachments: List<ComposerAttachmentDraft> = emptyList(),
    attachmentThumbnails: Map<String, ImageBitmap> = emptyMap(),
    onRemoveAttachment: (String) -> Unit = {},
    voiceState: VoiceUiState = VoiceUiState.Idle,
    focusRequester: FocusRequester,
    modifier: Modifier,
    onRegisterInserter: (((String) -> Boolean)?) -> Unit = {},
) {
    val tokens = HermesTheme.tokens
    val pendingLocalTexts = remember(editorIdentity) { ArrayDeque<String>() }
    var editorValue by remember(editorIdentity) { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
    var completionSelectionIndex by remember(editorIdentity) { mutableStateOf(0) }
    DisposableEffect(editorIdentity) {
        onDispose { onRegisterInserter(null) }
    }
    val completionItems = visibleCompletionItems(
        controls.completion.trigger,
        controls.completion.query,
        controls.completion.items,
    )
    LaunchedEffect(draft, editorIdentity) {
        editorValue = reconcileComposerEditorValue(editorValue, draft, pendingLocalTexts)
    }
    LaunchedEffect(
        controls.completion.trigger,
        controls.completion.query,
        controls.completion.items,
        editorIdentity,
    ) {
        completionSelectionIndex = 0
    }
    fun publish(next: TextFieldValue, notifyInsert: String? = null, completion: CompletionItem? = null) {
        editorValue = next
        pendingLocalTexts.addLast(next.text)
        onDraftChange(next.text)
        onEditorSelectionChange(next.text, next.selection.start, next.selection.end)
        notifyInsert?.let(onInsertText)
        completion?.let(onCompletionSelected)
    }
    fun insertAtSelection(value: String): Boolean {
        if (editorValue.composition != null) return false
        val updated = replaceComposerRange(editorValue.text, editorValue.selection.start, editorValue.selection.end, value)
        val cursor = editorValue.selection.start.coerceIn(0, editorValue.text.length) + value.length
        publish(TextFieldValue(updated, TextRange(cursor)), notifyInsert = value)
        return true
    }
    DisposableEffect(editorIdentity) {
        onRegisterInserter(::insertAtSelection)
        onDispose { }
    }
    fun acceptCompletion(item: CompletionItem) {
        if (editorValue.composition != null) return
        val replacement = item.text
        val updated = replaceComposerRange(
            editorValue.text,
            controls.completion.replaceStart,
            controls.completion.replaceEnd,
            replacement,
        )
        val cursor = controls.completion.replaceStart.coerceIn(0, editorValue.text.length) + replacement.length
        publish(TextFieldValue(updated, TextRange(cursor)), completion = item)
    }
    Column(modifier) {
        if (attachments.isNotEmpty()) {
            AttachmentChipRow(
                attachments = attachments,
                onRemove = onRemoveAttachment,
                thumbnails = attachmentThumbnails,
            )
        }
        BasicTextField(
            value = editorValue,
            onValueChange = { value ->
                val textChanged = value.text != editorValue.text
                val canonical = if (textChanged && value.composition == null) canonicalizeComposerTextOnSpace(value.text) else value.text
                val next = if (canonical == value.text) value else TextFieldValue(canonical, TextRange(canonical.length))
                editorValue = next
                onEditorSelectionChange(next.text, next.selection.start, next.selection.end)
                if (textChanged || canonical != value.text) {
                    pendingLocalTexts.addLast(next.text)
                    onDraftChange(next.text)
                }
            },
            textStyle = HermesTheme.type.body.copy(color = tokens.textPrimary),
            cursorBrush = SolidColor(tokens.composerRing),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(min = HermesTheme.spacing.touchTarget)
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .focusRequester(focusRequester)
                .testTag("Composer field shell")
                .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val completionKeyDown = event.type == KeyEventType.KeyDown &&
                    native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD == 0 &&
                    !native.isShiftPressed && !native.isCtrlPressed && !native.isMetaPressed &&
                    !native.isAltPressed && editorValue.composition == null && completionItems.isNotEmpty()
                if (completionKeyDown) {
                    when (native.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            completionSelectionIndex = (completionSelectionIndex + 1) % completionItems.size
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            completionSelectionIndex =
                                (completionSelectionIndex - 1 + completionItems.size) % completionItems.size
                            return@onPreviewKeyEvent true
                        }
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            acceptCompletion(completionItems[completionSelectionIndex.coerceIn(completionItems.indices)])
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                val rawHistoryKey = event.type == KeyEventType.KeyDown &&
                    native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD == 0 &&
                    editorValue.composition == null && completionItems.isEmpty()
                if (rawHistoryKey && !native.isCtrlPressed && !native.isMetaPressed && !native.isAltPressed) {
                    when (native.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> if (onHistoryOlder()) return@onPreviewKeyEvent true
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (onHistoryNewer()) return@onPreviewKeyEvent true
                    }
                }
                if (rawHistoryKey && (native.isCtrlPressed || native.isMetaPressed) && !native.isAltPressed) {
                    val handled = when (native.keyCode) {
                        android.view.KeyEvent.KEYCODE_Z -> if (native.isShiftPressed) onRedo() else onUndo()
                        android.view.KeyEvent.KEYCODE_Y -> onRedo()
                        else -> false
                    }
                    if (handled) return@onPreviewKeyEvent true
                }
                val action = composerKeyAction(
                    keyCode = native.keyCode,
                    isKeyDown = event.type == KeyEventType.KeyDown,
                    isShiftPressed = native.isShiftPressed,
                    isCtrlOrMetaPressed = native.isCtrlPressed || native.isMetaPressed,
                    isAltPressed = native.isAltPressed,
                    isSoftKeyboard = native.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD != 0,
                    isComposing = editorValue.composition != null,
                    isStreaming = isStreaming,
                    canSend = primaryAction == ComposerPrimaryAction.Send,
                    primaryAction = primaryAction,
                    canQueue = canQueue,
                    isOverlayFocused = completionItems.isNotEmpty(),
                    isNeedsInput = isNeedsInput,
                )
                when (action) {
                    ComposerKeyAction.Consume -> true
                    ComposerKeyAction.Send,
                    ComposerKeyAction.Redirect,
                    ComposerKeyAction.SendNext,
                    -> { onPrimary(); true }
                    ComposerKeyAction.Queue -> {
                        if (primaryAction == ComposerPrimaryAction.Queue) onPrimary() else onQueue()
                        true
                    }
                    ComposerKeyAction.Stop -> { onStop(); true }
                    ComposerKeyAction.None -> false
                }
            }
            .semantics { contentDescription = "Message Hermes" },
            decorationBox = { inner ->
                CenteredTextFieldContent(
                    isEmpty = editorValue.text.isEmpty(),
                    contentTag = "Composer text content",
                    horizontalPadding = 6.dp,
                    placeholder = { Text("Message Hermes", style = HermesTheme.type.body, color = tokens.textTertiary) },
                    innerTextField = inner,
                )
            },
        )
        CompletionPopup(
            trigger = controls.completion.trigger,
            query = controls.completion.query,
            items = controls.completion.items,
            isLoading = controls.completion.loading,
            error = controls.completion.error,
            selectedIndex = if (completionItems.isEmpty()) 0 else completionSelectionIndex.coerceIn(completionItems.indices),
            onSelect = ::acceptCompletion,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ComposerSecondaryQueueAction(onQueue: () -> Unit) {
    TextButton(
        label = "Queue",
        onClick = onQueue,
        modifier = Modifier.semantics { contentDescription = "Queue message" },
    )
}

@Composable
private fun ComposerPrimaryControl(action: ComposerPrimaryAction, onClick: () -> Unit) {
    val tokens = HermesTheme.tokens
    val enabled = action != ComposerPrimaryAction.None
    val description = when (action) {
        ComposerPrimaryAction.Send -> "Send message"
        ComposerPrimaryAction.Redirect -> "Redirect message"
        ComposerPrimaryAction.Stop -> "Stop generating"
        ComposerPrimaryAction.SendNext -> "Send next queued message"
        ComposerPrimaryAction.Queue -> "Queue message"
        ComposerPrimaryAction.None -> "Send message"
    }
    Box(
        Modifier
            .size(HermesTheme.spacing.touchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(if (enabled) tokens.textPrimary else tokens.textPrimary.copy(alpha = .25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (action == ComposerPrimaryAction.Stop) {
                Box(Modifier.size(9.dp).background(tokens.cardSurface, RoundedCornerShape(3.dp)))
            } else {
                HermesIconGlyph(HermesIcon.ArrowUp, color = tokens.cardSurface, size = 13.sp)
            }
        }
    }
}
