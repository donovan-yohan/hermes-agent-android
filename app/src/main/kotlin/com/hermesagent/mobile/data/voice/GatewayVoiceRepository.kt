package com.hermesagent.mobile.data.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Typed audio/wake routes over the connection-owned authenticated HTTP leg.
 * Payloads never log; response bytes are wiped by the caller's ownership.
 */
class GatewayVoiceRepository(private val http: () -> GatewayVoiceHttp?) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun transcribe(key: VoiceSessionKey, audio: CapturedAudio): TranscriptionResult =
        withContext(Dispatchers.IO) {
            val transport = http() ?: return@withContext TranscriptionResult.Silence.let {
                throw VoiceTransportException("Reconnect to the Gateway before using voice.")
            }
            val encoded = Base64.getEncoder().encodeToString(audio.bytes)
            val payload = buildJsonObject {
                put("data_url", "data:${audio.mime};base64,$encoded")
                put("mime_type", audio.mime)
            }.toString()
            val budgeted = VoicePolicy.audioTimeoutMillis(VoicePolicy.wireBudgetBytes(audio.bytes.size))
            when (val result = transport.execute(
                VoiceHttpRequest("api/audio/transcribe", "POST", payload.toRequestBody("application/json".toMediaType()), budgeted),
            )) {
                is VoiceHttpResult.Rejected -> throw VoiceTransportException(result.safeMessage)
                is VoiceHttpResult.Success -> parseTranscription(result.bodyBytes)
            }
        }

    private fun parseTranscription(body: ByteArray): TranscriptionResult {
        val text = runCatching {
            (Json.parseToJsonElement(String(body, Charsets.UTF_8)) as JsonObject)["transcript"]
        }.getOrNull()
            ?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
            ?: return TranscriptionResult.Silence
        return if (text.isBlank()) TranscriptionResult.Silence else TranscriptionResult.Transcript(text)
    }

    suspend fun speak(key: VoiceSessionKey, cleanText: String): SpeechAudio =
        withContext(Dispatchers.IO) {
            val transport = http() ?: throw VoiceTransportException("Reconnect to the Gateway before using voice.")
            val payload = buildJsonObject { put("text", cleanText) }.toString()
            val timeout = VoicePolicy.audioTimeoutMillis(VoicePolicy.wireBudgetBytes(cleanText.length))
            when (val result = transport.execute(
                VoiceHttpRequest("api/audio/speak", "POST", payload.toRequestBody("application/json".toMediaType()), timeout),
            )) {
                is VoiceHttpResult.Rejected -> throw VoiceTransportException(result.safeMessage)
                is VoiceHttpResult.Success -> parseSpeak(result.bodyBytes)
            }
        }

    private fun parseSpeak(body: ByteArray): SpeechAudio {
        val obj = runCatching { Json.parseToJsonElement(String(body, Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: throw VoiceTransportException("The voice reply was incomplete. Try again.")
        val dataUrl = obj["data_url"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
            ?: throw VoiceTransportException("The voice reply was incomplete. Try again.")
        val mime = obj["mime_type"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null } ?: "audio/mpeg"
        val base64 = dataUrl.substringAfter(";base64,", missingDelimiterValue = "")
        if (base64.isEmpty()) throw VoiceTransportException("The voice reply was incomplete. Try again.")
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
            ?: throw VoiceTransportException("The voice reply was incomplete. Try again.")
        return SpeechAudio(mime, bytes)
    }

    // Wake lease protocol — JSON-RPC via the active RPC client is added in commit D.
}

class VoiceTransportException(val safeMessage: String) : Exception(safeMessage)
