package com.hermesagent.mobile.data.profiles

import com.hermesagent.mobile.plugins.PluginConnectionToken
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A token is local to its host instance; it must never be transferred between hosts. */
internal class GatewayAvatarAssetSource private constructor(
    private val host: PluginHost,
    val generation: Long,
    private val token: PluginConnectionToken,
) : CapturedAvatarSource(generation) {
    override fun isCurrent(): Boolean = host.endpointGeneration.value == generation &&
        host.connectionToken.value === token && host.endpointGeneration.value == generation

    internal suspend fun request(method: String, params: JsonObject): PluginHostResult =
        host.requestAtConnection(generation, token, method, params)

    override suspend fun requestCaptured(method: String, params: JsonObject): JsonElement =
        when (val result = request(method, params)) {
            is PluginHostResult.Success -> result.result
            else -> throw AvatarReadUnavailable()
        }

    companion object {
        fun capture(host: PluginHost): GatewayAvatarAssetSource? {
            val generation = host.endpointGeneration.value
            val token = host.connectionToken.value ?: return null
            return GatewayAvatarAssetSource(host, generation, token).takeIf { it.isCurrent() }
        }
    }
}

/** No backend prose, identities or payload data in the failure. Not authoritative absence. */
internal class AvatarReadUnavailable : Exception("Avatar unavailable")
