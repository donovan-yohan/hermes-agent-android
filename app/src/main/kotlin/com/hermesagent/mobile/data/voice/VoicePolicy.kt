package com.hermesagent.mobile.data.voice

/**
 * Named policy constants for the Android voice stack. Values follow the
 * Desktop conversation defaults; device calibration may adjust them only with
 * recorded evidence. These are policy, not UI timing.
 */
object VoicePolicy {
    /** Client raw capture ceiling, conservatively below the server 25 MiB cap. */
    const val MAX_RAW_AUDIO_BYTES = 20 * 1024 * 1024

    /** Base64 expansion + JSON envelope allowance for the wire budget. */
    const val WIRE_EXPANSION_NUMERATOR = 4
    const val WIRE_EXPANSION_DENOMINATOR = 3

    const val DICTATION_MIN_SECONDS = 1
    const val DICTATION_MAX_SECONDS = 600

    /** Desktop's bounded audio timeout baseline scaled from request size. */
    const val AUDIO_TIMEOUT_BASE_MILLIS = 180_000L
    const val AUDIO_TIMEOUT_MAX_MILLIS = 600_000L

    // Conversation VAD defaults (Desktop intent).
    const val VOICE_THRESHOLD = 0.075f
    const val SPEECH_END_SILENCE_MILLIS = 1_250L
    const val IDLE_SILENCE_MILLIS = 12_000L
    const val CONVERSATION_LISTEN_CAP_MILLIS = 60_000L

    /** Barge-in settle deadline before showing the retry state. */
    const val INTERRUPT_SETTLE_MILLIS = 5_000L

    fun wireBudgetBytes(rawBytes: Int): Int =
        (rawBytes / WIRE_EXPANSION_DENOMINATOR) * WIRE_EXPANSION_NUMERATOR + 4096

    fun audioTimeoutMillis(estimatedWireBytes: Int): Long =
        (AUDIO_TIMEOUT_BASE_MILLIS + estimatedWireBytes / 1024L).coerceAtMost(AUDIO_TIMEOUT_MAX_MILLIS)

    /**
     * Whole-utterance stop matching: true only when the normalized utterance
     * equals a configured stop phrase exactly — substantive speech containing
     * "stop" stays a message.
     */
    fun isStopUtterance(transcript: String, stopPhrases: List<String>): Boolean {
        val normalized = transcript.trim().lowercase().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return false
        return stopPhrases.any { phrase ->
            normalized == phrase.trim().lowercase().replace(Regex("\\s+"), " ")
        }
    }
}
