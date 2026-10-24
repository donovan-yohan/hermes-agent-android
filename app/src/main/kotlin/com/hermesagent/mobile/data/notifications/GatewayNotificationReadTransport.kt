package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.groups.*
import kotlinx.serialization.json.*

/** Read-only wire authority: hermes_cli/web_routers/sessions.py:182-242,736-780;
 * gateway/session_controls.py:365-394; tui_gateway/methods_groups.py:345-354,487-493
 * @ 046094df28b712f62a5e3c728a9e59da05dcbf1e. Never attaches/adopts a session. */
internal class GatewayNotificationReadTransport(
    override val endpoint: String,
    private val http: GatewayHttp,
    private val rpc: suspend (String, JsonObject) -> JsonElement,
    private val current: () -> Boolean,
    private val closeTransport: () -> Unit,
    sessionNamespace: String = endpoint,
) : NotificationReadTransport {
    private val identity = GatewaySessionIdentity(sessionNamespace)
    private var owner: Pair<String, String>? = null
    private var profiles: Map<String, String> = emptyMap()
    private var capabilities: GroupCapabilities? = null
    override fun isCurrent() = current()
    private fun guard() { check(current()) { "Notification endpoint changed." } }
    private suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject {
        guard()
        val answer = rpc(method, params) as? JsonObject ?: error("Invalid notification response.")
        guard()
        require(answer.toString().toByteArray().size <= MAX_BYTES)
        return answer
    }
    private suspend fun serving(): Pair<String, String> {
        owner?.let { return it }
        val description = call("runtime.describe")
        val id = description.requiredString("profile_id")
        val roster = roster()
        // Canonical describe has no name. Only the exact serving home in the
        // public roster may supply it; discovered siblings are not authority.
        val name = (roster.entries.singleOrNull { it.value == id } ?: error("Missing notification owner.")).key
        require(roster.entries.first().key == name)
        identity.bind(name, id, name)
        profiles = roster
        return (name to id).also { owner = it }
    }
    private suspend fun roster(profile: String? = null): Map<String, String> {
        val rows = call("profiles.list", buildJsonObject {
            profile?.let { put("profile", it) }
            put("include_sessions", false)
        }).getValue("profiles").jsonArray
        require(rows.isNotEmpty() && rows.size <= 100)
        val pairs = rows.map { it.jsonObject.let { row -> row.requiredString("name") to row.requiredString("path") } }
        require(pairs.map { it.first }.distinct().size == pairs.size && pairs.map { it.second }.distinct().size == pairs.size)
        return pairs.toMap()
    }
    private suspend fun confirm(profile: String): String {
        val serving = serving()
        if (profile == serving.first) return serving.second
        identity.ownerId(profile)?.let { return it }
        val expected = profiles[profile] ?: error("Missing notification owner.")
        val roster = roster(profile)
        val row = roster.entries.first()
        require(row.key == profile && row.value == expected)
        require(roster.all { (name, path) -> profiles[name]?.let { it == path } != false })
        val id = row.value
        identity.bind(profile, id, profile)
        return id
    }
    private suspend fun get(path: String, query: Map<String, String>): JsonObject {
        guard()
        val result = http.execute(GatewayHttpRequest(path, "GET", null, 8_000, query,
            maxResponseBytes = MAX_BYTES.toLong(), isCurrent = current))
        if (result !is GatewayHttpResult.Success) error("Notification history unavailable.")
        val value = result.consumeBody { bytes ->
            require(bytes.size <= MAX_BYTES)
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
        guard()
        return value
    }
    override suspend fun sessions(offset: Long, limit: Int): NotificationSessionPage {
        require(offset >= 0 && limit in 1..100)
        val serving = serving()
        if (offset >= HIDDEN_OFFSET) {
            val names = profiles.keys.toList()
            val index = (offset - HIDDEN_OFFSET).toInt()
            if (index >= names.size) return NotificationSessionPage(emptyList(), null)
            val profile = names[index]
            val confirmedOwner = confirm(profile)
            val hidden = call("session.list", buildJsonObject {
                if (profile != serving.first) put("profile", profile)
                put("title", "Bot Chat"); put("include_hidden", true); put("limit", 1)
            }).getValue("sessions").jsonArray
            require(hidden.size <= 1)
            return NotificationSessionPage(hidden.map { value ->
                val row = value.jsonObject
                val raw = row.string("session_id") ?: row.requiredString("id")
                NotificationSession(identity.qualify(raw, profile, profile), raw, profile, confirmedOwner, profile,
                    row.string("title").orEmpty(), row.millis("started_at"))
            }, if (index + 1 < names.size) HIDDEN_OFFSET + index + 1 else null)
        }
        val page = get("api/profiles/sessions", mapOf("profile" to "all", "offset" to offset.toString(), "limit" to limit.toString(),
            "order" to "recent", "full" to "false", "archived" to "include"))
        require((page["errors"] as? JsonArray).isNullOrEmpty())
        require((page["storage"] as? JsonObject).isNullOrEmpty())
        require(page.getValue("offset").jsonPrimitive.long == offset && page.getValue("limit").jsonPrimitive.int == limit)
        // _pinned_window appends older pins beyond the requested window. Keep
        // this scan bounded; later requested windows still visit those pins.
        val rows = page.getValue("sessions").jsonArray.take(limit).map { it.jsonObject }
        val total = page.getValue("total").jsonPrimitive.long
        require(total >= 0)
        val discoveries = mutableListOf<NotificationSession>()
        suspend fun project(row: JsonObject, hidden: Boolean = false) {
            val profile = row.string("profile") ?: if (hidden) serving.first else error("Missing owner.")
            val owner = confirm(profile)
            val rawId = if (hidden) row.string("session_id") ?: row.requiredString("id") else row.requiredString("id")
            discoveries += NotificationSession(identity.qualify(rawId, profile, profile), rawId, profile, owner,
                profile, row.string("title").orEmpty(), row.millis("started_at"))
        }
        rows.forEach { project(it) }
        // The canonical title registry is deliberately a read, never open/resume/create.
        if (offset == 0L && discoveries.size < limit) {
            val hidden = call("session.list", buildJsonObject {
                put("title", "Bot Chat"); put("include_hidden", true); put("limit", 1)
            })
            val chats = hidden.getValue("sessions").jsonArray
            require(chats.size <= 1)
            chats.forEach { project(it.jsonObject, hidden = true) }
        }
        return NotificationSessionPage(discoveries.distinctBy { it.appId },
            // session_count includes hidden rows which REST omits. An empty
            // visible window ends this lane even when total has not been met.
            if (rows.isNotEmpty() && offset + limit < total) offset + limit else HIDDEN_OFFSET)
    }
    override suspend fun messages(session: NotificationSession, offset: Long, limit: Int): NotificationMessagePage {
        val escaped = okhttp3.HttpUrl.Builder().scheme("https").host("invalid.invalid")
            .addPathSegment(session.rawId).build().encodedPath.removePrefix("/")
        val page = get("api/sessions/$escaped/messages", mapOf("profile" to session.profile,
            "offset" to offset.toString(), "limit" to limit.toString(), "order" to "latest", "inline_images" to "false"))
        require(page.requiredString("profile") == session.profile)
        // Resume/compression may change the resolved id; the canonical root is
        // retained as the app destination, while persisted row ids remain identity.
        page.requiredString("session_id")
        val pagination = page.getValue("pagination").jsonObject
        val rows = page.getValue("messages").jsonArray
        require(rows.size <= limit && pagination.getValue("returned").jsonPrimitive.int == rows.size)
        require(pagination.getValue("offset").jsonPrimitive.long == offset && pagination.getValue("limit").jsonPrimitive.int == limit)
        require(pagination.requiredString("order") == "latest")
        return NotificationMessagePage(rows.map { element ->
            val row = element.jsonObject
            val id = row.getValue("id").jsonPrimitive.takeUnless { it.isString }?.longOrNull
            require(id != null && id > 0)
            RecoveredSessionMessage(id, row.requiredString("role"), row.string("content").orEmpty(), row.millis("timestamp") ?: 0)
        })
    }
    override suspend fun rooms(offset: Long, limit: Int): NotificationRoomPage {
        val caps = capabilities ?: parseGroupCapabilities(call("groups.capabilities")).also { capabilities = it }
        if (caps.version != 2L) return NotificationRoomPage(emptyList(), null)
        val page = parseGroupList(call("groups.list", buildJsonObject { put("offset", offset); put("limit", limit) }), offset)
        require(page.rooms.size <= limit)
        return NotificationRoomPage(page.rooms.map { room ->
            require(room.authority == caps.authorityGatewayId)
            NotificationRoom(room.id, room.name, room.latest, room.members.map {
                NotificationRoomMember(it.id, it.label, it.profile.takeUnless { _ -> it.peer })
            }, room.authority)
        }, page.next)
    }
    override suspend fun roomLog(room: NotificationRoom, since: Long, limit: Int): NotificationRoomLog {
        val page = parseGroupLog(call("groups.log", buildJsonObject {
            put("room_id", room.id); put("since_seq", since); put("limit", minOf(limit, capabilities?.limit ?: limit))
        }), room.id, since)
        return NotificationRoomLog(page.events.map {
            NotificationRoomEvent(it.seq, it.eventId, it.kind, it.memberId, it.text, it.atMillis)
        }, page.cursor, page.more, page.authority)
    }
    override fun close() = closeTransport()
    private companion object { const val MAX_BYTES = 524288; const val HIDDEN_OFFSET = 1L shl 50 }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.requiredString(key: String) = string(key)?.takeIf { it.isNotBlank() } ?: error("Invalid notification identity.")
private fun JsonObject.millis(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
    ?.takeIf { it.isFinite() && it >= 0 && it < Long.MAX_VALUE / 1000.0 }?.let { (it * 1000).toLong() }
