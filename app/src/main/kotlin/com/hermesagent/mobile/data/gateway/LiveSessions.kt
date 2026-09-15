package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One row of the Gateway's live-session registry (`session.active_list`). */
data class LiveSession(
    val durableSessionId: String,
    val title: String,
    val preview: String,
    val status: LiveSessionStatus,
    val lastActiveAtMillis: Long,
)

/** The three states a *live* row can be in. Anything else is not live work. */
enum class LiveSessionStatus { Starting, Working, Waiting }

/** What the Gateway answered when asked for its live sessions. */
sealed interface LiveSessionSnapshot {
    /** The Gateway answered. An empty list is an answer: nothing is live. */
    data class Reported(val sessions: List<LiveSession>) : LiveSessionSnapshot
    /** No usable answer (socket down, method missing, malformed frame). Never invents rows. */
    data object Unavailable : LiveSessionSnapshot
}

internal fun parseLiveSessionList(result: JsonElement): List<LiveSession> {
    val root = result.asObject("session.active_list")
    val rows = root["sessions"] as? JsonArray
        ?: throw GatewayRpcException("Hermes returned a malformed live-session list.")
    return rows.mapNotNull { row ->
        val objectRow = row as? JsonObject ?: return@mapNotNull null
        val durableSessionId = objectRow.strictString("session_key")?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val status = when (objectRow.strictString("status")) {
            "working" -> LiveSessionStatus.Working
            "starting" -> LiveSessionStatus.Starting
            "waiting" -> LiveSessionStatus.Waiting
            else -> return@mapNotNull null
        }
        LiveSession(
            durableSessionId = durableSessionId,
            title = objectRow.string("title") ?: "",
            preview = objectRow.string("preview") ?: "",
            status = status,
            lastActiveAtMillis = objectRow.primitive("last_active")?.epochMillisOrNull() ?: 0L,
        )
    }
}

private fun JsonObject.strictString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.primitive(name: String): String? = (this[name] as? JsonPrimitive)?.content
