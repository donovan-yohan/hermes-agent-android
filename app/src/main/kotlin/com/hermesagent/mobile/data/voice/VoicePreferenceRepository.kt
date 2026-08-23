package com.hermesagent.mobile.data.voice

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.consumeBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Auto-speak preference backed by the Gateway's canonical `voice.auto_tts`
 * config key (HTTP GET/PUT /api/config, the same route Desktop uses), never a
 * second local authority. Reads return null while disconnected.
 */
class VoicePreferenceRepository(private val http: () -> GatewayHttp?) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadAutoSpeak(): Boolean? = withContext(Dispatchers.IO) {
        val transport = http() ?: return@withContext null
        when (val result = transport.execute(GatewayHttpRequest("api/config", "GET", null, VoicePolicy.AUDIO_TIMEOUT_BASE_MILLIS))) {
            is GatewayHttpResult.Rejected -> null
            is GatewayHttpResult.Success -> result.consumeBody(::parseAutoSpeak)
        }
    }

    /**
     * Read-modify-write of the whole config record with only `voice.auto_tts`
     * changed, mirroring Desktop's saveHermesConfig contract. Returns false on
     * rejection so the caller rolls its optimistic toggle back.
     */
    suspend fun saveAutoSpeak(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val transport = http() ?: return@withContext false
        val current = when (val result = transport.execute(GatewayHttpRequest("api/config", "GET", null, VoicePolicy.AUDIO_TIMEOUT_BASE_MILLIS))) {
            is GatewayHttpResult.Success -> result.consumeBody { body ->
                runCatching { json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject }.getOrNull()
            }
            is GatewayHttpResult.Rejected -> null
        } ?: return@withContext false
        val voice = current["voice"] as? JsonObject ?: JsonObject(emptyMap())
        val updatedVoice = JsonObject(voice + mapOf("auto_tts" to kotlinx.serialization.json.JsonPrimitive(enabled)))
        val updated = JsonObject(current + mapOf("voice" to updatedVoice))
        val payload = buildJsonObject { put("config", updated) }.toString()
        when (val result = transport.execute(
            GatewayHttpRequest(
                "api/config",
                "PUT",
                payload.toRequestBody("application/json".toMediaType()),
                VoicePolicy.AUDIO_TIMEOUT_BASE_MILLIS,
            ),
        )) {
            is GatewayHttpResult.Success -> result.consumeBody { true }
            is GatewayHttpResult.Rejected -> false
        }
    }

    private fun parseAutoSpeak(body: ByteArray): Boolean? = runCatching {
        val obj = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        val voice = obj["voice"] as? JsonObject ?: return null
        voice["auto_tts"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.boolean else null }
    }.getOrNull()
}
