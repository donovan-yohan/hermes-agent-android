package com.hermesagent.mobile.data.voice

import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Typed wake-word lease protocol against the Gateway's wake.* JSON-RPC
 * methods. The backend detector is authoritative; Android only captures and
 * feeds 16 kHz PCM when the lease grants client capture to surface "gui".
 */
internal class WakeWordRepository(
    private val rpc: () -> GatewayRpcClient?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mutex = Mutex()

    suspend fun status(): WakeStatus? {
        if (!mutex.tryLock()) return null
        return try {
            val client = rpc() ?: return null
            runCatching {
                WakeStatus.from(json.parseToJsonElement(client.request("wake.status", buildJsonObject {}).toString()) as JsonObject)
            }.getOrNull()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Arms the listener. [persist] marks an explicit user gesture that may
     * flip config-enabled truth; passive rearm omits it.
     */
    suspend fun start(persist: Boolean, sessionRuntimeId: String?): WakeStart {
        if (!mutex.tryLock()) return WakeStart.Rejected("Another voice action is in progress.")
        return try {
            val client = rpc() ?: return WakeStart.Rejected("Reconnect to the Gateway first.")
            val payload = buildJsonObject {
                put("surface", "gui")
                put("client_capture", true)
                put("persist", persist)
                sessionRuntimeId?.takeIf(String::isNotEmpty)?.let { put("session_id", it) }
            }
            runCatching {
                val obj = json.parseToJsonElement(client.request("wake.start", payload).toString()) as JsonObject
                WakeStart.from(obj)
            }.getOrDefault(WakeStart.Rejected("Hermes could not arm the wake listener."))
        } finally {
            mutex.unlock()
        }
    }

    /** Explicit stop persists; passive pause/resume never touch config. */
    suspend fun stop(): Boolean = simpleCall("wake.stop")

    suspend fun pause(): Boolean = simpleCall("wake.pause")

    suspend fun resume(): Boolean = simpleCall("wake.resume")

    /** Feeds one bounded 16 kHz int16 mono frame; rejects oversize locally. */
    suspend fun feed(pcm: ByteArray, sampleRate: Int = 16_000): Boolean {
        if (sampleRate != 16_000 || pcm.isEmpty() || pcm.size > 64_000) return false
        val client = rpc() ?: return false
        val payload = buildJsonObject {
            put("pcm", Base64.getEncoder().encodeToString(pcm))
            put("sample_rate", sampleRate)
        }
        return runCatching {
            val obj = json.parseToJsonElement(client.request("wake.feed", payload).toString()) as JsonObject
            obj["fed"]?.let { it is kotlinx.serialization.json.JsonPrimitive && it.boolean } ?: false
        }.getOrDefault(false)
    }

    private suspend fun simpleCall(method: String): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            val client = rpc() ?: return false
            runCatching { client.request(method, buildJsonObject {}) }.isSuccess
        } finally {
            mutex.unlock()
        }
    }
}

sealed interface WakeStatus {
    data class Available(
        val enabledByGateway: Boolean,
        val listening: Boolean,
        val ownerSurface: String?,
        val phrase: String?,
    ) : WakeStatus
    data object Unavailable : WakeStatus

    companion object {
        fun from(obj: JsonObject): WakeStatus {
            val available = obj["available"]?.let { it is kotlinx.serialization.json.JsonPrimitive && it.booleanOrNull == true } == true
            if (!available) return Unavailable
            fun str(key: String): String? {
                val v = obj[key] as? kotlinx.serialization.json.JsonPrimitive ?: return null
                return v.contentOrNull
            }
            fun bool(key: String): Boolean {
                val v = obj[key] as? kotlinx.serialization.json.JsonPrimitive ?: return false
                return v.booleanOrNull == true
            }
            return Available(
                enabledByGateway = bool("enabled"),
                listening = bool("listening"),
                ownerSurface = str("owner") ?: str("surface"),
                phrase = str("phrase"),
            )
        }
    }
}

/** Started carries the capture mode and phrase for the feeder loop. */
sealed interface WakeStart {
    data class Started(val captureClientSide: Boolean, val phrase: String?) : WakeStart
    data class Rejected(val safeReason: String) : WakeStart

    companion object {
        fun from(obj: JsonObject): WakeStart {
            val started = obj["started"]?.let { it is kotlinx.serialization.json.JsonPrimitive && it.boolean } == true
            if (!started) {
                val reason = obj["reason"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
                return Rejected(reason?.takeIf(String::isNotBlank) ?: "Wake word is not available right now.")
            }
            fun str(key: String) = obj[key]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
            return Started(captureClientSide = str("capture") == "client", phrase = str("phrase"))
        }
    }
}
