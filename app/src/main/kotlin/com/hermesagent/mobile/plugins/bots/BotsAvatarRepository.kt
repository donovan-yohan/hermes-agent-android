package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.data.profiles.AvatarPayload
import com.hermesagent.mobile.data.profiles.AvatarLimits
import com.hermesagent.mobile.data.profiles.parseAvatarPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.Base64

sealed interface BotAvatarChange {
    data object Unchanged : BotAvatarChange
    data object Clear : BotAvatarChange
    class Image(val bytes: ByteArray) : BotAvatarChange
}
enum class BotAvatarSave { Saved, Unchanged, Rejected, Unconfirmed }
data class BotAvatarRead(val bytes: ByteArray?)

/** methods_profiles.py:408-460 @ 587e673e. No metadata/config write or local-storage fallback. */
class BotsAvatarRepository(private val host: PluginHost, private val timeoutMillis: Long = 60_000L) {
    private suspend fun call(target: BotManagementTarget, method: String, params: JsonObject, dispatchAllowed: (() -> Boolean)? = null): JsonObject? {
        if (!validBotId(target.name)) return null
        return try {
            withTimeoutOrNull(timeoutMillis) {
                val result = if (dispatchAllowed == null) host.requestAtEndpoint(target.endpoint, method, params)
                else host.requestAtEndpointGuarded(target.endpoint, method, params, dispatchAllowed)
                (result as? PluginHostResult.Success)?.result as? JsonObject
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    suspend fun read(target: BotManagementTarget): BotAvatarRead? {
        val reply = call(target, "profiles.get_asset", buildJsonObject {
            put("name", target.name); put("asset", "avatar")
        }) ?: return null
        return when (val parsed = parseAvatarPayload(reply)) {
            AvatarPayload.Missing -> BotAvatarRead(null)
            is AvatarPayload.Raster -> BotAvatarRead(parsed.bytes)
            null -> null
        }
    }
    suspend fun save(target: BotManagementTarget, original: ByteArray?, change: BotAvatarChange, dispatchAllowed: () -> Boolean = { true }): BotAvatarSave {
        if (!validBotId(target.name)) return BotAvatarSave.Rejected
        val bytes = when (change) {
            BotAvatarChange.Unchanged -> return BotAvatarSave.Unchanged
            BotAvatarChange.Clear -> if (original == null) return BotAvatarSave.Unchanged else null
            is BotAvatarChange.Image -> {
                if (change.bytes.contentEquals(original)) return BotAvatarSave.Unchanged
                if (change.bytes.size !in 8..AvatarLimits.BYTES ||
                    !change.bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)))
                    return BotAvatarSave.Rejected
                change.bytes
            }
        }
        val reply = call(target, "profiles.set_asset", buildJsonObject {
            put("name", target.name); put("asset", "avatar")
            if (bytes == null) put("clear", true)
            else put("data", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
        }, dispatchAllowed) ?: return BotAvatarSave.Unconfirmed
        if (reply["ok"] != JsonPrimitive(true) || reply["asset"] != JsonPrimitive("avatar") ||
            reply["size"] != JsonPrimitive(bytes?.size ?: 0)) return BotAvatarSave.Unconfirmed
        val actual = read(target) ?: return BotAvatarSave.Unconfirmed
        return if (actual.bytes.contentEquals(bytes)) BotAvatarSave.Saved else BotAvatarSave.Unconfirmed
    }
}
