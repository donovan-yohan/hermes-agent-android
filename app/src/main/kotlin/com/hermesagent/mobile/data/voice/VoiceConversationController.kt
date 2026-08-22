package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Pure conversation state machine: Listening -> Transcribing -> Thinking ->
 * Speaking -> Listening, with mute/end/stop/barge exits. Owns no media:
 * capture and playback are delegated through injected callbacks; Gateway turns
 * go through the typed submit/interrupt callbacks so the existing
 * single-unscoped-turn guard stays intact. Timing is injected for virtual-time
 * tests; every cycle is fenced by a monotonic operation id so late callbacks
 * from an ended conversation cannot revive it.
 */
class VoiceConversationController(
    private val scope: CoroutineScope,
    private val stopPhrases: () -> List<String>,
    private val startCapture: suspend () -> Boolean,
    private val stopCapture: suspend () -> Unit,
    private val transcribe: suspend () -> TranscriptionResult,
    private val submitTurn: suspend (String) -> Boolean,
    private val interruptTurn: suspend () -> Boolean,
    private val speak: suspend (String) -> Unit,
    private val stopPlayback: suspend () -> Unit,
    private val onStateChange: (VoiceUiState.Conversation) -> Unit,
    /** Host arms its barge detector when the conversation enters Thinking/Speaking. */
    private val armBargeMonitor: () -> Unit = {},
    /** Host disarms the barge detector on Listening/Ended. */
    private val disarmBargeMonitor: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var state = VoiceUiState.Conversation(VoiceUiState.ConversationPhase.Listening, muted = false)
    private var operation = 0L

    val currentState: VoiceUiState.Conversation get() = state

    /** Starts one listening cycle. Returns false if a cycle is already live. */
    fun beginCycle(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            operation += 1
            setState(VoiceUiState.ConversationPhase.Listening)
            scope.launch { runListenCycle(operation) }
            return true
        } finally {
            mutex.unlock()
        }
    }

    /** User stop-listening: ends capture and transcribes what was heard. */
    fun stopListening() {
        val myOperation = operation
        scope.launch { finishListening(myOperation) }
    }

    fun toggleMute(): Boolean {
        state = state.copy(muted = !state.muted)
        onStateChange(state)
        if (state.muted) {
            operation += 1
            scope.launch { stopCapture() }
        } else {
            beginCycle()
        }
        return state.muted
    }

    /** Ends the whole conversation: fences all cycles and stops media. */
    fun end() {
        operation += 1
        scope.launch {
            stopCapture()
            stopPlayback()
        }
        setState(VoiceUiState.ConversationPhase.Ended)
    }

    private fun runListenCycle(myOperation: Long) {
        scope.launch {
            delay(VoicePolicy.CONVERSATION_LISTEN_CAP_MILLIS)
            withLockOrNull {
                if (myOperation == operation && state.phase == VoiceUiState.ConversationPhase.Listening) {
                    finishListening(myOperation)
                }
            }
        }
    }

    private suspend fun finishListening(myOperation: Long) {
        withLockOrNull {
            if (myOperation != operation || state.phase != VoiceUiState.ConversationPhase.Listening) return@withLockOrNull
            setState(VoiceUiState.ConversationPhase.Transcribing)
        } ?: return
        stopCapture()
        val result = transcribe()
        withLockOrNull {
            if (myOperation != operation) return@withLockOrNull
            when (result) {
                is TranscriptionResult.Silence -> rearmLocked(myOperation)
                is TranscriptionResult.Transcript -> {
                    val text = result.text.trim()
                    if (VoicePolicy.isStopUtterance(text, stopPhrases())) {
                        end()
                        return@withLockOrNull
                    }
                    setState(VoiceUiState.ConversationPhase.Thinking)
                    scope.launch { submitTurnAndAwaitReply(myOperation, text) }
                }
            }
        }
    }

    private suspend fun submitTurnAndAwaitReply(myOperation: Long, text: String) {
        val accepted = submitTurn(text)
        withLockOrNull {
            if (myOperation != operation) return@withLockOrNull
            if (!accepted) {
                rearmLocked(myOperation)
                return@withLockOrNull
            }
            // Speaking begins once the caller feeds the completed reply via
            // onReplyReady; Thinking holds until then.
        }
    }

    /** The host feeds a completed assistant reply for speaking. */
    suspend fun onReplyReady(myOperation: Long, replyText: String) {
        withLockOrNull {
            if (myOperation != operation || state.phase != VoiceUiState.ConversationPhase.Thinking) return@withLockOrNull
            setState(VoiceUiState.ConversationPhase.Speaking)
        } ?: return
        speak(replyText)
        // Rearm after normal playback drain; explicit stop never rearms here
        // because stop bumps the operation fence before stopping playback.
        withLockOrNull {
            if (myOperation == operation && state.phase == VoiceUiState.ConversationPhase.Speaking) {
                rearmLocked(myOperation)
            }
        }
    }

    /** Barge-in during thinking or speaking: interrupt then one captured utterance. */
    suspend fun bargeIn(myOperation: Long, capturedTranscript: String?) {
        withLockOrNull {
            if (myOperation != operation) return@withLockOrNull
            if (state.phase == VoiceUiState.ConversationPhase.Thinking ||
                state.phase == VoiceUiState.ConversationPhase.Speaking
            ) {
                stopPlayback()
                interruptTurn()
            }
        } ?: return
        val text = capturedTranscript?.trim().orEmpty()
        var shouldSubmit = false
        withLockOrNull {
            if (myOperation != operation) return@withLockOrNull
            when {
                text.isEmpty() -> rearmLocked(myOperation)
                VoicePolicy.isStopUtterance(text, stopPhrases()) -> end()
                else -> {
                    setState(VoiceUiState.ConversationPhase.Thinking)
                    shouldSubmit = true
                }
            }
        } ?: return
        // Recheck inside the fence above; only a still-current conversation
        // submits, so an ended conversation can never fire a late turn.
        if (shouldSubmit) {
            val accepted = submitTurn(text)
            if (!accepted) rearmIfCurrent(myOperation)
        }
    }

    private fun rearmIfCurrent(myOperation: Long) {
        scope.launch {
            withLockOrNull {
                if (myOperation == operation) rearmLocked(myOperation)
            }
        }
    }

    private suspend fun rearmLocked(myOperation: Long) {
        setState(VoiceUiState.ConversationPhase.Listening)
        scope.launch { runListenCycle(myOperation) }
    }

    private fun setState(phase: VoiceUiState.ConversationPhase, ended: Boolean = false) {
        state = VoiceUiState.Conversation(phase, muted = if (ended) false else state.muted)
        when (phase) {
            VoiceUiState.ConversationPhase.Thinking,
            VoiceUiState.ConversationPhase.Speaking,
            -> if (!state.muted) armBargeMonitor()
            else -> disarmBargeMonitor()
        }
        onStateChange(state)
    }

    /**
     * Runs [block] under the mutex, or returns null immediately when the lock
     * is contended (a live cycle owns it). Callers treat null as "not now".
     */
    private suspend inline fun <T> withLockOrNull(block: () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
