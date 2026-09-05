package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicBoolean

import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayReplySpeakerTest {

    class FakeRepository : SpeechSynthesizer {
        var lastSpeakText: String? = null
        var shouldThrow: Boolean = false

        override suspend fun speak(key: VoiceSessionKey, cleanText: String): SpeechAudio {
            lastSpeakText = cleanText
            if (shouldThrow) {
                throw VoiceTransportException("Gateway failure")
            }
            return SpeechAudio("audio/mpeg", ByteArray(10) { 1 })
        }
    }

    class FakeSpeechPlayer : SpeechPlayback {
        var lastAudio: SpeechAudio? = null
        var isPlaying: Boolean = false
        var stopCalled = false

        override suspend fun play(audio: SpeechAudio): Boolean {
            lastAudio = audio
            isPlaying = true
            return true
        }

        override fun stop() {
            stopCalled = true
            isPlaying = false
        }
    }

    @Test
    fun `blank after sanitize returns false and never calls Gateway`() = runTest {
        val repo = FakeRepository()
        val player = FakeSpeechPlayer()
        val speaker = GatewayReplySpeaker(repo, player)

        val result = speaker.speak(VoiceSessionKey(1L, "session"), "   \n  \t  ") {}

        assertFalse(result)
        assertEquals(null, repo.lastSpeakText)
    }

    @Test
    fun `text handed to Gateway is sanitized text`() = runTest {
        val repo = FakeRepository()
        val player = FakeSpeechPlayer()
        val speaker = GatewayReplySpeaker(repo, player)

        speaker.speak(VoiceSessionKey(1L, "session"), "Here is the code: ```\ncode\n```") {}

        assertEquals("Here is the code: code block omitted", repo.lastSpeakText)
    }

    @Test
    fun `text longer than cap is cut to the cap`() = runTest {
        val repo = FakeRepository()
        val player = FakeSpeechPlayer()
        val speaker = GatewayReplySpeaker(repo, player)

        val longText = "a".repeat(25000)
        speaker.speak(VoiceSessionKey(1L, "session"), longText) {}

        assertEquals(20_000, repo.lastSpeakText?.length)
    }

    @Test
    fun `onSpeaking fires after audio arrives and before playback ends`() = runTest {
        val repo = FakeRepository()
        val player = FakeSpeechPlayer()
        val speaker = GatewayReplySpeaker(repo, player)
        val onSpeakingFired = AtomicBoolean(false)

        speaker.speak(VoiceSessionKey(1L, "session"), "hello") {
            onSpeakingFired.set(true)
            // FakeSpeechPlayer hasn't finished play() block because it's called after onSpeaking
            assertTrue(player.lastAudio == null)
        }

        assertTrue(onSpeakingFired.get())
        assertTrue(player.lastAudio != null)
    }

    @Test
    fun `audio is closed after playback`() = runTest {
        val repo = FakeRepository()
        val player = FakeSpeechPlayer()
        val speaker = GatewayReplySpeaker(repo, player)

        speaker.speak(VoiceSessionKey(1L, "session"), "hello") {}

        // bytes should be zeroed
        assertTrue(player.lastAudio?.bytes?.all { it == 0.toByte() } == true)
    }

    @Test
    fun `Gateway failure propagates and leaves nothing playing`() = runTest {
        val repo = FakeRepository().apply { shouldThrow = true }
        val player = FakeSpeechPlayer()
        val speaker = GatewayReplySpeaker(repo, player)

        val exception = runCatching {
            speaker.speak(VoiceSessionKey(1L, "session"), "hello") {}
        }.exceptionOrNull()

        assertTrue(exception is VoiceTransportException)
        assertFalse(player.isPlaying)
    }
}
