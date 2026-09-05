package com.hermesagent.mobile.data.voice

/** App-wide reply playback boundary: one reply plays at a time, like Desktop's `$voicePlayback` store. */
interface ReplySpeaker {
    suspend fun speak(key: VoiceSessionKey, text: String, onSpeaking: () -> Unit): Boolean
    fun stop()
}

/**
 * Sanitises → caps → `POST api/audio/speak` → plays. This uses Desktop's POST
 * rung of the three-rung voice ladder (`apps/desktop/src/lib/voice-playback.ts:100-170`
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
class GatewayReplySpeaker(
    private val repository: SpeechSynthesizer,
    private val player: SpeechPlayback
) : ReplySpeaker {

    override suspend fun speak(key: VoiceSessionKey, text: String, onSpeaking: () -> Unit): Boolean {
        val sanitized = sanitizeTextForSpeech(text)
        if (sanitized.isBlank()) return false

        val truncated = if (sanitized.length > MAX_SPOKEN_CHARS) sanitized.substring(0, MAX_SPOKEN_CHARS) else sanitized

        var audio: SpeechAudio? = null
        return try {
            audio = repository.speak(key, truncated)
            onSpeaking()
            player.play(audio)
        } finally {
            audio?.close()
        }
    }

    override fun stop() {
        player.stop()
    }
}

// A reply made of backticks expands under the sanitiser.
private const val MAX_SPOKEN_CHARS = 20_000
