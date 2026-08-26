package com.hermesagent.mobile.ui.chat

import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerContractTest {
    @Test
    fun `primary action preserves send redirect stop needs-input queue and idle drain contract`() {
        assertEquals(
            ComposerPrimaryAction.Send,
            actionState(ComposerBusyKind.Idle, hasText = true).primary,
        )
        assertEquals(
            ComposerPrimaryAction.Send,
            actionState(ComposerBusyKind.Idle, hasText = false, canSend = true).primary,
        )
        assertEquals(
            ComposerPrimaryAction.Redirect,
            actionState(ComposerBusyKind.Streaming, hasText = true).primary,
        )
        assertTrue(actionState(ComposerBusyKind.Streaming, hasText = true).showQueueSecondary)
        assertEquals(
            ComposerPrimaryAction.Queue,
            actionState(ComposerBusyKind.Streaming, hasText = true, hasAttachments = true).primary,
        )
        assertTrue(actionState(ComposerBusyKind.Streaming, hasText = true, hasAttachments = true).showStopSecondary)
        assertEquals(
            ComposerPrimaryAction.Queue,
            actionState(ComposerBusyKind.Streaming, hasText = false, hasAttachments = true).primary,
        )
        assertTrue(actionState(ComposerBusyKind.Streaming, hasText = false, hasAttachments = true).showStopSecondary)
        assertEquals(
            ComposerPrimaryAction.Stop,
            actionState(ComposerBusyKind.Streaming, hasText = false, canSend = true).primary,
        )
        // Stop is a safety control: queued entries alone never replace it
        // with send-next while a turn is live.
        assertEquals(
            ComposerPrimaryAction.Stop,
            actionState(ComposerBusyKind.Streaming, hasText = false, queueCount = 2).primary,
        )
        assertEquals(
            ComposerPrimaryAction.Queue,
            actionState(ComposerBusyKind.NeedsInput, hasText = true).primary,
        )
        assertEquals(
            ComposerPrimaryAction.None,
            actionState(ComposerBusyKind.NeedsInput, hasText = false).primary,
        )
        assertEquals(
            ComposerPrimaryAction.SendNext,
            actionState(ComposerBusyKind.Idle, hasText = false, queueCount = 1).primary,
        )
    }

    @Test
    fun `nonredirectable text never uses a different rpc`() {
        // Desktop steer semantics: while streaming, text keeps the steer
        // primary; ineligibility is handled by the steer fallback into the
        // local queue, not by a silent primary swap to the queue RPC.
        assertEquals(
            ComposerPrimaryAction.Redirect,
            actionState(ComposerBusyKind.Streaming, hasText = true, redirectEligible = false).primary,
        )

        assertEquals(
            ComposerPrimaryAction.None,
            composerActionState(
                connected = false,
                busyKind = ComposerBusyKind.Idle,
                hasText = true,
                hasAttachments = false,
                canSend = true,
                redirectEligible = true,
                queueCount = 0,
            ).primary,
        )
    }
    @Test
    fun `layout thresholds use measured composer width exactly`() {
        assertEquals(ComposerLayoutMode.Full, composerLayoutMode(561.dp))
        assertEquals(ComposerLayoutMode.Compact, composerLayoutMode(560.dp))
        assertEquals(ComposerLayoutMode.Compact, composerLayoutMode(321.dp))
        assertEquals(ComposerLayoutMode.Stacked, composerLayoutMode(320.dp))
        assertEquals(ComposerLayoutMode.Stacked, composerLayoutMode(319.dp))
    }

    @Test
    fun `plain hardware enter sends only when idle and sendable`() {
        assertEquals(ComposerKeyAction.Send, action(KeyEvent.KEYCODE_ENTER))
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ENTER, isStreaming = true))
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ENTER, canSend = false))
    }

    @Test
    fun `modified soft composing and process enter stay native`() {
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ENTER, isShiftPressed = true))
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ENTER, isAltPressed = true))
        assertEquals(ComposerKeyAction.Consume, action(KeyEvent.KEYCODE_ENTER, isCtrlOrMetaPressed = true))
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ENTER, isSoftKeyboard = true))
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ENTER, isComposing = true))
        assertEquals(ComposerKeyAction.None, action(229))
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ENTER, isKeyDown = false))
    }

    @Test
    fun `escape stops only a selected streaming turn`() {
        assertEquals(ComposerKeyAction.Stop, action(KeyEvent.KEYCODE_ESCAPE, isStreaming = true))
        assertEquals(ComposerKeyAction.None, action(KeyEvent.KEYCODE_ESCAPE, isStreaming = false))
        assertEquals(
            ComposerKeyAction.None,
            action(KeyEvent.KEYCODE_ESCAPE, isStreaming = true, isShiftPressed = true),
        )
    }

    @Test
    fun `hardware primary and queue shortcuts preserve composition overlay and needs-input guards`() {
        assertEquals(
            ComposerKeyAction.Redirect,
            composerKeyAction(
                keyCode = KeyEvent.KEYCODE_ENTER,
                isKeyDown = true,
                isShiftPressed = false,
                isCtrlOrMetaPressed = false,
                isAltPressed = false,
                isSoftKeyboard = false,
                isComposing = false,
                isStreaming = true,
                canSend = false,
                primaryAction = ComposerPrimaryAction.Redirect,
                canQueue = true,
            ),
        )
        assertEquals(
            ComposerKeyAction.Queue,
            composerKeyAction(
                keyCode = KeyEvent.KEYCODE_ENTER,
                isKeyDown = true,
                isShiftPressed = false,
                isCtrlOrMetaPressed = true,
                isAltPressed = false,
                isSoftKeyboard = false,
                isComposing = false,
                isStreaming = true,
                canSend = false,
                primaryAction = ComposerPrimaryAction.Redirect,
                canQueue = true,
            ),
        )
        assertEquals(
            ComposerKeyAction.Consume,
            composerKeyAction(
                keyCode = KeyEvent.KEYCODE_ENTER,
                isKeyDown = true,
                isShiftPressed = false,
                isCtrlOrMetaPressed = true,
                isAltPressed = false,
                isSoftKeyboard = false,
                isComposing = false,
                isStreaming = true,
                canSend = false,
                primaryAction = ComposerPrimaryAction.Redirect,
                canQueue = true,
                isOverlayFocused = true,
            ),
        )
        assertEquals(
            ComposerKeyAction.Stop,
            composerKeyAction(
                keyCode = KeyEvent.KEYCODE_ESCAPE,
                isKeyDown = true,
                isShiftPressed = false,
                isCtrlOrMetaPressed = false,
                isAltPressed = false,
                isSoftKeyboard = false,
                isComposing = false,
                isStreaming = true,
                canSend = false,
                primaryAction = ComposerPrimaryAction.Redirect,
                canQueue = true,
            ),
        )
        assertEquals(
            ComposerKeyAction.None,
            composerKeyAction(
                keyCode = KeyEvent.KEYCODE_ESCAPE,
                isKeyDown = true,
                isShiftPressed = false,
                isCtrlOrMetaPressed = false,
                isAltPressed = false,
                isSoftKeyboard = false,
                isComposing = false,
                isStreaming = true,
                canSend = false,
                primaryAction = ComposerPrimaryAction.Stop,
                canQueue = true,
                isOverlayFocused = true,
            ),
        )
        assertEquals(
            ComposerKeyAction.None,
            composerKeyAction(
                keyCode = KeyEvent.KEYCODE_ESCAPE,
                isKeyDown = true,
                isShiftPressed = false,
                isCtrlOrMetaPressed = false,
                isAltPressed = false,
                isSoftKeyboard = false,
                isComposing = false,
                isStreaming = false,
                canSend = false,
                primaryAction = ComposerPrimaryAction.Stop,
                canQueue = true,
                isNeedsInput = true,
            ),
        )
    }

    @Test
    fun `intermediate draft acknowledgements do not reset newer editor text or composition`() {
        val pending = ArrayDeque(listOf("a", "ab"))
        val editor = TextFieldValue("ab", selection = TextRange(2), composition = TextRange(0, 2))

        val afterIntermediate = reconcileComposerEditorValue(editor, "a", pending)
        val afterLatest = reconcileComposerEditorValue(afterIntermediate, "ab", pending)

        assertEquals(editor, afterLatest)
        assertEquals(emptyList<String>(), pending.toList())
    }

    @Test
    fun `external draft replacement resets editor and clears pending acknowledgements`() {
        val pending = ArrayDeque(listOf("local"))
        val replaced = reconcileComposerEditorValue(TextFieldValue("local"), "restored", pending)

        assertEquals(TextFieldValue("restored", TextRange(8)), replaced)
        assertEquals(emptyList<String>(), pending.toList())
    }

    private fun action(
        keyCode: Int,
        isKeyDown: Boolean = true,
        isShiftPressed: Boolean = false,
        isCtrlOrMetaPressed: Boolean = false,
        isAltPressed: Boolean = false,
        isSoftKeyboard: Boolean = false,
        isComposing: Boolean = false,
        isStreaming: Boolean = false,
        canSend: Boolean = true,
    ) = composerKeyAction(
        keyCode = keyCode,
        isKeyDown = isKeyDown,
        isShiftPressed = isShiftPressed,
        isCtrlOrMetaPressed = isCtrlOrMetaPressed,
        isAltPressed = isAltPressed,
        isSoftKeyboard = isSoftKeyboard,
        isComposing = isComposing,
        isStreaming = isStreaming,
        canSend = canSend,
    )

    private fun actionState(
        busyKind: ComposerBusyKind,
        hasText: Boolean,
        hasAttachments: Boolean = false,
        canSend: Boolean = hasText,
        redirectEligible: Boolean = true,
        queueCount: Int = 0,
    ) = composerActionState(
        connected = true,
        busyKind = busyKind,
        hasText = hasText,
        hasAttachments = hasAttachments,
        canSend = canSend,
        redirectEligible = redirectEligible,
        queueCount = queueCount,
    )
}
