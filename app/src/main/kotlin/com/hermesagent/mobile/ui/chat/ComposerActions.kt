package com.hermesagent.mobile.ui.chat

/**
 * The selected session's own state that changes the meaning of the composer's
 * primary tap. Other sessions' turns never appear here: Desktop parity keeps a
 * foreground composer sendable while any background session runs.
 */
enum class ComposerBusyKind { Idle, Streaming, NeedsInput, Background }

enum class ComposerPrimaryAction { None, Send, Redirect, Stop, SendNext, Queue }

data class ComposerActionState(
    val primary: ComposerPrimaryAction,
    val showQueueSecondary: Boolean,
)

/**
 * One deliberate action decision for the composer. It is UI-neutral so unit
 * tests can prove a busy turn never falls back from redirect to steer/submit.
 */
internal fun composerActionState(
    connected: Boolean,
    busyKind: ComposerBusyKind,
    hasText: Boolean,
    redirectEligible: Boolean,
    queueCount: Int,
): ComposerActionState {
    if (!connected) return ComposerActionState(ComposerPrimaryAction.None, showQueueSecondary = false)
    return when (busyKind) {
        ComposerBusyKind.Idle -> when {
            hasText -> ComposerActionState(ComposerPrimaryAction.Send, showQueueSecondary = false)
            queueCount > 0 -> ComposerActionState(ComposerPrimaryAction.SendNext, showQueueSecondary = false)
            else -> ComposerActionState(ComposerPrimaryAction.None, showQueueSecondary = false)
        }

        ComposerBusyKind.Streaming -> {
            // Desktop parity: while a turn is live, the primary control is
            // steer for any composer text; when the text is not yet steer
            // eligible, steer falls back to queueing locally so a message
            // is never lost. With no text the primary stays stop — sending
            // the next queued entry is never a substitute for the safety
            // control, and queue entries drain on turn settle.
            ComposerActionState(
                primary = if (hasText) ComposerPrimaryAction.Redirect else ComposerPrimaryAction.Stop,
                showQueueSecondary = hasText,
            )
        }

        // A required response has its own Gateway route. Ordinary composer
        // text is retained safely as a queue entry; it must not interrupt it.
        ComposerBusyKind.NeedsInput -> when {
            hasText -> ComposerActionState(ComposerPrimaryAction.Queue, showQueueSecondary = false)
            else -> ComposerActionState(ComposerPrimaryAction.None, showQueueSecondary = false)
        }

        ComposerBusyKind.Background ->
            if (hasText) {
                ComposerActionState(ComposerPrimaryAction.Queue, showQueueSecondary = false)
            } else {
                ComposerActionState(ComposerPrimaryAction.None, showQueueSecondary = false)
            }
    }
}
