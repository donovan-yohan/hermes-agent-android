package com.hermesagent.mobile.data.voice

/**
 * Immutable, non-serializable voice domain types. No audio sample, base64
 * string, URI, provider detail, token, or remote path may appear in any type
 * projected to the UI, persistence, logs, or accessibility text.
 */

/** Fences every voice operation to a connection + durable session identity. */
data class VoiceSessionKey(
    val connectionGeneration: Long,
    val durableSessionId: String,
)

/** Monotonic per-operation fence; late callbacks with an old id are dropped. */
typealias VoiceOperationId = Long

/** Truthful UI state for all voice surfaces; carries no media or provider data. */
sealed interface VoiceUiState {
    data object Idle : VoiceUiState

    data class DictationRecording(
        val elapsedMillis: Long,
        /** Normalized 0..1 meter level; display-only, never VAD truth. */
        val level: Float,
    ) : VoiceUiState

    data object DictationTranscribing : VoiceUiState

    enum class ConversationPhase { Listening, Transcribing, Thinking, Speaking, Ended }

    data class Conversation(
        val phase: ConversationPhase,
        val muted: Boolean,
    ) : VoiceUiState

    enum class AutoSpeakPhase { Preparing, Speaking }

    data class AutoSpeak(val phase: AutoSpeakPhase) : VoiceUiState

    enum class VoiceErrorKind {
        PermissionDenied,
        PermissionPermanentlyDenied,
        MicrophoneUnavailable,
        CaptureFailed,
        ProviderUnavailable,
        ProviderRejected,
        TimedOut,
        ConnectionLost,
        NoSpeech,
    }

    data class Error(val kind: VoiceErrorKind, val recovery: String) : VoiceUiState
}

/**
 * Bounded captured audio owned by the engine. Bytes are private, mutable and
 * zeroed on close; the type is not Parcelable/serializable and must never
 * enter persistence, logs, or UI state.
 */
class CapturedAudio(
    val mime: String,
    val bytes: ByteArray,
) : AutoCloseable {
    override fun close() = bytes.fill(0)
}

/** Result of one dictation/conversation transcription. */
sealed interface TranscriptionResult {
    data class Transcript(val text: String) : TranscriptionResult
    data object Silence : TranscriptionResult
}

/** A completed speakable audio payload; bytes are owned and wiped by the engine. */
class SpeechAudio(
    val mime: String,
    val bytes: ByteArray,
) : AutoCloseable {
    override fun close() = bytes.fill(0)
}

/** One streamed speech exchange. Implementations close socket and player. */
interface SpeechStream : AutoCloseable {
    /** @return frame bytes (PCM) or null when the stream ended. */
    suspend fun read(): SpeechStreamFrame?
}

sealed interface SpeechStreamFrame {
    data class Start(val sampleRate: Int, val channels: Int) : SpeechStreamFrame
    data class Pcm(val bytes: ByteArray) : SpeechStreamFrame
    data object End : SpeechStreamFrame
    /** Only legal before any audio: caller must fall back to complete-audio TTS. */
    data object Fallback : SpeechStreamFrame
}
