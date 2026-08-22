package com.hermesagent.mobile.ui.chat

/** The session state that changes the meaning of the composer’s primary tap. */
enum class ComposerBusyKind { Idle, Streaming, NeedsInput, Background, OtherSessionRunning }

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

        ComposerBusyKind.Streaming -> when {
            !hasText -> ComposerActionState(ComposerPrimaryAction.Stop, showQueueSecondary = false)
            redirectEligible -> ComposerActionState(ComposerPrimaryAction.Redirect, showQueueSecondary = true)
            else -> ComposerActionState(ComposerPrimaryAction.Queue, showQueueSecondary = false)
        }

        // A required response has its own Gateway route. Ordinary composer
        // text is retained safely as a queue entry; it must not interrupt it.
        ComposerBusyKind.NeedsInput -> when {
            hasText -> ComposerActionState(ComposerPrimaryAction.Queue, showQueueSecondary = false)
            else -> ComposerActionState(ComposerPrimaryAction.None, showQueueSecondary = false)
        }

        ComposerBusyKind.Background,
        ComposerBusyKind.OtherSessionRunning,
        -> if (hasText) ComposerActionState(ComposerPrimaryAction.Queue, showQueueSecondary = false)
        else ComposerActionState(ComposerPrimaryAction.None, showQueueSecondary = false)
    }
}
