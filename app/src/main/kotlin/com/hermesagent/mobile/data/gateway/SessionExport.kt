package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/** Desktop lib/session-export.ts:29-45 @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa. */
internal suspend fun collectSessionExport(
    sessionId: String,
    title: String?,
    exportedAt: Long,
    requireCurrent: () -> Unit,
    fetch: suspend (Int) -> GatewaySessionMessagePage,
): ByteArray {
    val messages = mutableListOf<JsonObject>()
    var offset = 0
    var encodedBytes = 0
    var resolvedId: String? = null
    while (true) {
        requireCurrent()
        val page = fetch(offset)
        requireCurrent()
        check(page.offset == offset.toLong() && page.order == "oldest" && page.limit == 100L) { "Invalid export page" }
        check(resolvedId == null || resolvedId == page.sessionId) { "Session changed during export" }
        resolvedId = page.sessionId
        check(page.messages.size <= 100) { "Invalid export page size" }
        page.messages.forEach {
            encodedBytes += it.toString().toByteArray(Charsets.UTF_8).size
            check(encodedBytes <= 8 * 1024 * 1024) { "Export is too large" }
        }
        messages += page.messages
        check(messages.size <= 10_000) { "Export is too large" }
        if (page.messages.size < 100) break
        offset += page.messages.size
        check(offset <= 10_000) { "Export is too large" }
    }
    requireCurrent()
    return buildJsonObject {
        put("exported_at", JsonPrimitive(Instant.ofEpochMilli(exportedAt).toString()))
        put("session_id", JsonPrimitive(resolvedId ?: sessionId))
        put("title", title?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
        put("session", kotlinx.serialization.json.JsonNull)
        put("message_count", JsonPrimitive(messages.size))
        put("messages", JsonArray(messages))
    }.toString().toByteArray(Charsets.UTF_8).also {
        check(it.size <= 8 * 1024 * 1024) { "Export is too large" }
    }
}
