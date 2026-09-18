package com.hermesagent.mobile.data.profiles

import android.graphics.Bitmap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface ProfileAvatar {
    data object Loading : ProfileAvatar
    data object Missing : ProfileAvatar
    data object Unavailable : ProfileAvatar
    class Ready(val bitmap: Bitmap) : ProfileAvatar
}

/**
 * A single captured connection leg, not a provider of whichever client is live later.
 * The production adapter must check its dispatch lease atomically with sending and use
 * the transport's slow-method budget. There is deliberately no repository timeout.
 * Construct this only from accepted roster provenance; retain it with those rows.
 * Endpoint is an opaque process-local namespace, never a URL or a profile label.
 */
internal abstract class CapturedAvatarSource(val endpoint: Any) {
    abstract fun isCurrent(): Boolean
    protected abstract suspend fun requestCaptured(method: String, params: JsonObject): JsonElement

    suspend fun getAvatar(rawName: String): JsonElement {
        check(isCurrent()) { "Avatar source unavailable" }
        return requestCaptured("profiles.get_asset", buildJsonObject {
            put("name", rawName)
            put("asset", "avatar")
        })
    }
}
