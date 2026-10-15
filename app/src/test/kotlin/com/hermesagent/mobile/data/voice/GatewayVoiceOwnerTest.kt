package com.hermesagent.mobile.data.voice

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Audio profile selectors: hermes_cli/web_routers/audio.py:86-90,293-310 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
class GatewayVoiceOwnerTest {
    @Test
    fun `transcription and speech retain captured profile and reject stale connection before dispatch`() = runTest {
        var current = true
        val requests = mutableListOf<GatewayHttpRequest>()
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                requests += request
                val result = if (request.path.endsWith("transcribe")) """{"ok":true,"transcript":"Public words"}"""
                    else """{"data_url":"data:audio/mpeg;base64,AQI=","mime_type":"audio/mpeg"}"""
                return GatewayHttpResult.Success(200, result.toByteArray())
            }
        }
        val repository = GatewayVoiceRepository({ http }, { current })
        val key = VoiceSessionKey(3, "public-session", "work", 5)
        CapturedAudio("audio/wav", byteArrayOf(1, 2)).use { audio ->
            assertEquals(TranscriptionResult.Transcript("Public words"), repository.transcribe(key, audio))
        }
        repository.speak(key, "Public reply").close()
        assertEquals(listOf("work", "work"), requests.map { it.query["profile"] })
        assertTrue(requests.all { it.isCurrent() })
        current = false
        assertTrue(requests.none { it.isCurrent() })
        assertTrue(runCatching { repository.speak(key, "Stale reply") }.isFailure)
        assertEquals(2, requests.size)
    }
}
