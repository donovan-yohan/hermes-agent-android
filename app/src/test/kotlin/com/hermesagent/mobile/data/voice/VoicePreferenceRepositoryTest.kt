package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePreferenceRepositoryTest {
    private class FakeHttp(
        private val responses: ArrayDeque<VoiceHttpResult>,
    ) : GatewayVoiceHttp {
        val requests = mutableListOf<Pair<String, VoiceHttpRequest?>>()
        override suspend fun execute(request: VoiceHttpRequest): VoiceHttpResult {
            requests += request.method to null
            return responses.removeFirst()
        }
    }

    @Test
    fun `auto speak reads gateway config voice record`() = runBlocking {
        val body = """{"voice":{"auto_tts":true},"model":"x"}"""
        val http = FakeHttp(ArrayDeque(listOf(VoiceHttpResult.Success(200, body.toByteArray()))))
        val repo = VoicePreferenceRepository { http }
        assertTrue(repo.loadAutoSpeak() == true)
        assertEquals("GET", http.requests.single().first)
    }

    @Test
    fun `missing voice record reads null not false`() = runBlocking {
        val http = FakeHttp(ArrayDeque(listOf(VoiceHttpResult.Success(200, "{}".toByteArray()))))
        assertNull(VoicePreferenceRepository { http }.loadAutoSpeak())
    }

    @Test
    fun `save is a read modify write of the whole record`() = runBlocking {
        val current = """{"model":"m","voice":{"thinking_sound":true,"auto_tts":false}}"""
        val http = FakeHttp(
            ArrayDeque(
                listOf(
                    VoiceHttpResult.Success(200, current.toByteArray()),
                    VoiceHttpResult.Success(200, """{"ok":true}""".toByteArray()),
                ),
            ),
        )
        val repo = VoicePreferenceRepository { http }
        assertTrue(repo.saveAutoSpeak(true))
        assertEquals(2, http.requests.size)
        assertEquals("PUT", http.requests.last().first)
    }

    @Test
    fun `rejected save returns false for optimistic rollback`() = runBlocking {
        val http = FakeHttp(
            ArrayDeque(
                listOf(
                    VoiceHttpResult.Success(200, "{}".toByteArray()),
                    VoiceHttpResult.Rejected(500, "failed"),
                ),
            ),
        )
        assertFalse(VoicePreferenceRepository { http }.saveAutoSpeak(true))
    }

    @Test
    fun `disconnected transport reads null and saves false`() = runBlocking {
        val repo = VoicePreferenceRepository { null }
        assertNull(repo.loadAutoSpeak())
        assertFalse(repo.saveAutoSpeak(true))
    }
}
