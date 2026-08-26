package com.hermesagent.mobile.data.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Named policy for the Android voice stack: the numeric conversation
 * constants, and the spoken stop-phrase rules that decide when an utterance
 * ends a conversation instead of becoming a turn. Values follow the Desktop
 * conversation defaults; device calibration may adjust them only with
 * recorded evidence. These are policy, not UI timing.
 *
 * The stop-phrase half is a port of Desktop's
 * `apps/desktop/src/lib/voice-stop-word.ts:1-105` and the phrase-list resolution
 * in `apps/desktop/src/store/voice-prefs.ts:15-38`
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`. Pure functions only: nothing
 * here logs, and a transcript never leaves the call it arrived on.
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

    /**
     * Canonical spoken stop commands, verbatim from Desktop
     * `voice-stop-word.ts:17-34`. This is the embedded default used whenever the
     * Gateway's `voice.stop_phrases` cannot be read, and its first entry is the
     * phrase the start notice names — the same `"stop"` Desktop falls back to.
     */
    val DEFAULT_STOP_PHRASES: List<String> = listOf(
        "stop",
        "stop listening",
        "stop it",
        "stop please",
        "please stop",
        "stop stop",
        "that is all",
        "that's all",
        "never mind",
        "nevermind",
        "end conversation",
        "end the conversation",
        "goodbye",
        "good bye",
        "bye",
        "cancel",
    )

    /**
     * Optional address prefixes, verbatim from Desktop `voice-stop-word.ts:38`,
     * so "hermes stop" / "ok stop" / "hey hermes, stop" still count. The
     * comma-suffixed entries are unreachable once punctuation is stripped; they
     * are kept so this list diffs clean against Desktop's.
     */
    private val ADDRESS_PREFIXES: List<String> =
        listOf("hey hermes", "hey hermes,", "hermes", "hermes,", "ok", "okay", "hey")

    private val UTTERANCE_PUNCTUATION_RE = Regex("""[.,!?;:…]+""")
    private val WHITESPACE_RUN_RE = Regex("""\s+""")

    fun wireBudgetBytes(rawBytes: Int): Int =
        (rawBytes / WIRE_EXPANSION_DENOMINATOR) * WIRE_EXPANSION_NUMERATOR + 4096

    fun audioTimeoutMillis(estimatedWireBytes: Int): Long =
        (AUDIO_TIMEOUT_BASE_MILLIS + estimatedWireBytes / 1024L).coerceAtMost(AUDIO_TIMEOUT_MAX_MILLIS)

    /**
     * Lowercase, drop the punctuation speech-to-text sprinkles on ("stop.",
     * "stop!", "stop…"), collapse whitespace. Locale-independent by
     * construction so a device locale cannot change what ends a conversation.
     */
    private fun normalizeUtterance(text: String): String =
        WHITESPACE_RUN_RE.replace(UTTERANCE_PUNCTUATION_RE.replace(text.lowercase(), " "), " ").trim()

    /**
     * Removes a leading address to Hermes. A bare address ("hermes", "hey") is
     * not a stop command on its own, so it is never stripped to nothing.
     */
    private fun stripAddressPrefix(text: String): String {
        for (prefix in ADDRESS_PREFIXES) {
            if (text == prefix) continue
            if (text.startsWith("$prefix ")) return text.removePrefix("$prefix ").trim()
        }
        return text
    }

    /**
     * Whole-utterance stop matching: true only when the normalized utterance —
     * with or without an address prefix — equals a configured stop phrase
     * exactly. Substantive speech that merely contains "stop" ("stop the docker
     * container") stays a message. An empty phrase list disables the feature.
     */
    fun isStopUtterance(transcript: String, stopPhrases: List<String>): Boolean {
        val normalized = normalizeUtterance(transcript)
        if (normalized.isEmpty()) return false
        val phrases = stopPhrases.mapNotNullTo(mutableSetOf()) { normalizeUtterance(it).ifEmpty { null } }
        return normalized in phrases || stripAddressPrefix(normalized) in phrases
    }

    /**
     * Typed-stop interception for the composer: a bare stop command typed while
     * the voice conversation is live ends the conversation instead of being sent
     * as a turn. Attachments mean the message is a real payload — never
     * intercepted. Outside a conversation typed text always passes through.
     */
    fun interceptsTypedStop(
        conversationActive: Boolean,
        text: String,
        stopPhrases: List<String>,
        attachmentCount: Int = 0,
    ): Boolean = conversationActive && attachmentCount == 0 && isStopUtterance(text, stopPhrases)

    /**
     * Resolves the live phrase list. `null` means the Gateway config could not be
     * read (or does not carry the key), so the embedded default applies; an
     * explicit empty list is the user turning the feature off and stays empty.
     */
    fun stopPhrasesOrDefault(configured: List<String>?): List<String> =
        configured?.map(String::trim)?.filter(String::isNotEmpty) ?: DEFAULT_STOP_PHRASES

    /**
     * Reads `voice.stop_phrases` out of a `GET /api/config` record, the same key
     * and shape Desktop reads. A null record (failed read), a missing `voice`
     * section or a missing key all fall back to [DEFAULT_STOP_PHRASES]; an
     * explicit empty list, or a scalar that is not a phrase, disables stop
     * phrases. List entries are stringified like Desktop's `String(entry)`,
     * but a bare non-string value is rejected like Desktop's `typeof` guard.
     */
    fun stopPhrasesFromConfig(config: JsonObject?): List<String> {
        val raw = (config?.get("voice") as? JsonObject)?.get("stop_phrases")
        val configured = when {
            raw == null -> null
            raw is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            raw is JsonPrimitive && raw.isString -> listOf(raw.content)
            else -> emptyList()
        }
        return stopPhrasesOrDefault(configured)
    }

    /**
     * The conversation start notice, verbatim from Desktop
     * `apps/desktop/src/i18n/en.ts:168`.
     */
    fun stopNotice(phrase: String): String = "Say \"$phrase\" to end the voice chat."

    /**
     * The notice for a resolved phrase list, or null when stop phrases are
     * disabled — an empty list suppresses the notice as well as the matching.
     */
    fun stopNoticeFor(stopPhrases: List<String>): String? =
        stopPhrases.firstOrNull { it.isNotBlank() }?.let { stopNotice(it.trim()) }
}

/**
 * Holds the once-per-conversation contract for the start notice: a conversation
 * announces how to end it exactly once, however many times the loop re-arms
 * inside it. [onConversationEnded] re-arms the notice for the next conversation.
 *
 * Not thread-safe by design — it is owned by the single conversation controller
 * that starts and ends the conversation.
 */
class VoiceStartNotice(private val stopPhrases: () -> List<String>) {
    private var announced = false

    /**
     * The notice for a conversation starting now, or null when this
     * conversation already announced it or stop phrases are disabled.
     */
    fun onConversationStarted(): String? {
        if (announced) return null
        announced = true
        return VoicePolicy.stopNoticeFor(stopPhrases())
    }

    /** Re-arms the notice so the next conversation announces itself again. */
    fun onConversationEnded() {
        announced = false
    }
}
