package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole local audio resource arbiter. At most one microphone owner exists
 * among dictation, conversation, barge monitor and wake feeder; playback is
 * sequence-fenced so stale completions cannot revive stopped audio.
 */
class VoiceEngine(
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val micMutex = Mutex()

    /** True when this caller became the exclusive microphone owner. */
    suspend fun acquireMic(): Boolean =
        micMutex.tryLock()

    suspend fun releaseMic() {
        micMutex.unlock()
    }

    suspend fun <T> withMic(block: suspend () -> T): T =
        micMutex.withLock { withContext(ioDispatcher) { block() } }

    private suspend fun <T> withContext(
        dispatcher: CoroutineDispatcher,
        block: suspend () -> T,
    ): T = kotlinx.coroutines.withContext(dispatcher) { block() }
}

/** Playback sequence fencing shared by read-aloud and conversation speech. */
class PlaybackSequencer {
    private var sequence = 0L

    /** Claims the next slot, invalidating all prior ones. */
    fun next(): Long = ++sequence

    /** True when [candidate] is still the live slot. */
    fun isCurrent(candidate: Long): Boolean = candidate == sequence

    /** Invalidates everything (stop path). */
    fun invalidate() {
        sequence += 1
    }
}
