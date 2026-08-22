package com.hermesagent.mobile.data.voice

import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordRepositoryTest {

    private class FakeRpc : GatewayRpcClient {
        val methods = mutableListOf<String>()
        var next: String? = null
        override val events = kotlinx.coroutines.flow.MutableStateFlow(
            com.hermesagent.mobile.data.gateway.GatewayEvent("", null, kotlinx.serialization.json.JsonNull),
        )
        override suspend fun request(method: String, params: JsonObject): JsonElement {
            methods += method
            val payload = next ?: error("no scripted response")
            return Json.parseToJsonElement(payload)
        }
        override fun close() {}
    }

    @Test
    fun `status parses available listening and phrase`() = runBlocking {
        val rpc = FakeRpc()
        rpc.next = """{"available":true,"enabled":true,"listening":true,"owner":"gui","phrase":"hey hermes"}"""
        val repo = WakeWordRepository({ rpc })
        val status = repo.status()
        assertTrue(status is WakeStatus.Available)
        status as WakeStatus.Available
        assertTrue(status.enabledByGateway)
        assertTrue(status.listening)
        assertEquals("gui", status.ownerSurface)
        assertEquals("hey hermes", status.phrase)
        assertEquals(listOf("wake.status"), rpc.methods)
    }

    @Test
    fun `unavailable status maps to unavailable`() = runBlocking {
        val rpc = FakeRpc()
        rpc.next = """{"available":false}"""
        assertTrue(WakeWordRepository({ rpc }).status() is WakeStatus.Unavailable)
    }

    @Test
    fun `start sends gui client-capture surface with persist flag`() = runBlocking {
        val rpc = FakeRpc()
        rpc.next = """{"started":true,"capture":"client","phrase":"stop"}"""
        val result = WakeWordRepository({ rpc }).start(persist = true, sessionRuntimeId = null)
        assertTrue(result is WakeStart.Started)
        result as WakeStart.Started
        assertTrue(result.captureClientSide)
        assertEquals("stop", result.phrase)
        assertEquals(listOf("wake.start"), rpc.methods)
    }

    @Test
    fun `refused start keeps safe reason`() = runBlocking {
        val rpc = FakeRpc()
        rpc.next = """{"started":false,"reason":"wake word disabled in config"}"""
        val result = WakeWordRepository({ rpc }).start(persist = false, sessionRuntimeId = null)
        assertTrue(result is WakeStart.Rejected)
        assertTrue((result as WakeStart.Rejected).safeReason.contains("disabled"))
    }

    @Test
    fun `feed rejects non-16k and oversize frames locally without calling rpc`() = runBlocking {
        val rpc = FakeRpc()
        val repo = WakeWordRepository({ rpc })
        assertFalse(repo.feed(ByteArray(100), sampleRate = 8_000))
        assertFalse(repo.feed(ByteArray(64_001), sampleRate = 16_000))
        assertTrue(rpc.methods.isEmpty())
    }

    @Test
    fun `feed sends bounded 16k frame as base64 pcm`() = runBlocking {
        val rpc = FakeRpc()
        rpc.next = """{"fed":true,"reason":null}"""
        val repo = WakeWordRepository({ rpc })
        assertTrue(repo.feed(ByteArray(3_200), sampleRate = 16_000))
        assertEquals(listOf("wake.feed"), rpc.methods)
    }
}
