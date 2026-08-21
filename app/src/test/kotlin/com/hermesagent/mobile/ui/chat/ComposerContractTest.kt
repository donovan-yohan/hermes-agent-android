package com.hermesagent.mobile.ui.chat

import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerContractTest {
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
}
