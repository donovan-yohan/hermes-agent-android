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
    val showQueueSecondary: Boolean = false,
)

/**
 * One deliberate action decision for the composer. It is UI-neutral so unit
 * tests can prove that the selected session and payload choose one safe route.
 */
internal fun composerActionState(
    connected: Boolean,
    busyKind: ComposerBusyKind,
    hasText: Boolean,
    hasAttachments: Boolean,
    canSend: Boolean,
    redirectEligible: Boolean,
    queueCount: Int,
): ComposerActionState {
    if (!connected) return ComposerActionState(ComposerPrimaryAction.None)
    return when (busyKind) {
        ComposerBusyKind.Idle -> when {
            canSend -> ComposerActionState(ComposerPrimaryAction.Send)
            queueCount > 0 -> ComposerActionState(ComposerPrimaryAction.SendNext)
            else -> ComposerActionState(ComposerPrimaryAction.None)
        }

        ComposerBusyKind.Streaming -> {
            // Attachments cannot ride a redirect into a live tool result. The
            // Gateway queues the whole payload for the next turn instead.
            when {
                hasAttachments -> ComposerActionState(ComposerPrimaryAction.Queue)
                hasText -> ComposerActionState(ComposerPrimaryAction.Redirect, showQueueSecondary = true)
                else -> ComposerActionState(ComposerPrimaryAction.Stop)
            }
        }

        // A required response has its own Gateway route, and a background turn
        // owns its session's foreground. Ordinary composer text is retained
        // safely as a queue entry either way; it must not interrupt them.
        ComposerBusyKind.NeedsInput,
        ComposerBusyKind.Background,
        -> when {
            hasText || hasAttachments -> ComposerActionState(ComposerPrimaryAction.Queue)
            else -> ComposerActionState(ComposerPrimaryAction.None)
        }
    }
}
