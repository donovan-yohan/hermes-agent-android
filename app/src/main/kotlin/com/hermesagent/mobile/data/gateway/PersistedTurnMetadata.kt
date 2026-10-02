package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.TurnErrorDetails
import com.hermesagent.mobile.data.session.parseTurnErrorDetails
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Desktop hydration.ts:180-208,223-224,580-613 @
 * e05b16348b1d06a3311237423b0a4fc30d9c5aa1. Stored metadata is an object or JSON
 * text; an interrupted bit says nothing about which client requested Stop.
 */
internal fun JsonObject.persistedDisplayMetadata(): JsonObject? = when (val raw = this["display_metadata"]) {
    is JsonObject -> raw
    is JsonPrimitive -> if (raw.isString) {
        runCatching { Json.parseToJsonElement(raw.content) as? JsonObject }.getOrNull()
    } else null
    else -> null
}

internal fun JsonObject.persistedInterrupted(): Boolean =
    (persistedDisplayMetadata()?.get("interrupted") as? JsonPrimitive)
        ?.takeUnless { it.isString }?.booleanOrNull == true

internal data class PersistedTurnFailure(val raw: String, val surface: JsonElement, val details: TurnErrorDetails)

internal fun JsonObject.persistedTurnFailure(): PersistedTurnFailure? {
    if (string("display_kind") != "failed_turn") return null
    val metadata = persistedDisplayMetadata() ?: return null
    val surface = metadata["error_surface"] as? JsonObject ?: return null
    // A recognised layer is the same validity gate as Desktop parseErrorSurface.
    val classified = parseTurnErrorDetails(null, surface)
    if (classified.layer == null) return null
    val raw = (metadata["error"] as? JsonPrimitive)?.takeIf { it.isString }
        ?.content?.takeIf(String::isNotBlank) ?: classified.code.orEmpty()
    return PersistedTurnFailure(raw, surface, parseTurnErrorDetails(raw, surface))
}
