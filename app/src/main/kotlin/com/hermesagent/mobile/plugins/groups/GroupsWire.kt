package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.data.ssh.redact
import kotlinx.serialization.json.*

// Wire authority: tui_gateway/contracts/groups_bot_relay.py:148-277 and
// gateway/hosted_room_discussion.py:60-75 @ d177b119e9c56c9ddc0b7379ffce52341ec06584.
data class GroupMember(val id: String, val profile: String, val label: String, val peer: Boolean)
data class HostedGroup(
    val id: String, val name: String, val members: List<GroupMember>, val latest: Long,
    val disbanded: Boolean, val authority: String,
)
data class GroupEvent(val seq: Long, val kind: String, val memberId: String?, val text: String)
data class GroupState(
    val room: HostedGroup, val working: Boolean = false, val blocked: Boolean = false,
    val peerRoutes: List<String> = emptyList(), val pending: List<String> = emptyList(),
)
data class GroupLog(val events: List<GroupEvent>, val cursor: Long, val more: Boolean)
data class GroupCapabilities(val version: Long, val driver: Boolean, val limit: Int)
data class GroupList(val rooms: List<HostedGroup>, val next: Long?)

internal class InvalidGroupWire : IllegalArgumentException()
internal fun invalid(): Nothing = throw InvalidGroupWire()
internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: invalid()
internal fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: invalid()
internal fun JsonObject.num(key: String): Long = (get(key) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: invalid()
internal fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)
    ?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
internal fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: invalid()
private fun JsonObject.timestamp(key: String) {
    val value = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: invalid()
    if (!value.isFinite() || value < 0) invalid()
}
private fun safe(value: String): String = redact(value)

internal fun parseGroupCapabilities(value: JsonElement): GroupCapabilities {
    val o = value.obj()
    val version = o.num("protocol_version")
    // An explicitly different version is unsupported, not a malformed v2 response.
    if (version != 2L) return GroupCapabilities(version, false, 1)
    val driver = o.bool("driver")
    o.bool("persistent_process")
    o.str("authority_gateway_id")
    o.getValue("room_link").obj().bool("enabled")
    for (key in listOf("features", "methods")) o.array(key).forEach {
        if (it !is JsonPrimitive || !it.isString) invalid()
    }
    val methods = o.array("methods").map { it.jsonPrimitive.content }.toSet()
    if (!methods.containsAll(listOf("groups.capabilities", "groups.list", "groups.state", "groups.log"))) invalid()
    val limit = o.num("max_log_limit").takeIf { it > 0 } ?: invalid()
    return GroupCapabilities(version, driver, minOf(limit, 500).toInt())
}

internal fun parseHostedGroup(value: JsonElement): HostedGroup {
    val o = value.obj()
    o.num("authority_epoch"); o.num("revision")
    o.timestamp("created_at"); o.timestamp("updated_at"); o.bool("idempotent")
    val disbanded = o["disbanded_at"]?.takeUnless { it == JsonNull } != null
    if (disbanded) o.timestamp("disbanded_at")
    val members = o.array("members").map {
        val m = it.obj()
        val target = m["target"]?.obj()
        val kind = target?.str("kind") ?: "local"
        if (kind !in setOf("local", "peer")) invalid()
        val profile = m.str("profile")
        if (target != null) {
            if (target.str("profile") != profile) invalid()
            if (kind == "peer") listOf("peer_id", "installation_id", "capability_digest").forEach(target::str)
        }
        val display = m["display_name"]?.takeUnless { it == JsonNull }?.let { m.str("display_name") }
        GroupMember(m.str("member_id"), profile, safe(display ?: "@${m.str("handle")}"), kind == "peer")
    }
    if (members.map { it.id }.distinct().size != members.size) invalid()
    return HostedGroup(o.str("room_id"), safe(o.str("name")), members, o.num("latest_seq"),
        disbanded, o.str("authority_gateway_id"))
}

internal fun parseGroupList(value: JsonElement, offset: Long): GroupList {
    val o = value.obj()
    val rooms = o.array("rooms").map(::parseHostedGroup)
    if (!o.containsKey("next_offset")) invalid()
    val next = if (o["next_offset"] == JsonNull) null else o.num("next_offset")
    if (next != null && (next <= offset || rooms.isEmpty())) invalid()
    if (rooms.any { it.disbanded } || rooms.map { it.id }.distinct().size != rooms.size) invalid()
    return GroupList(rooms, next)
}

internal fun parseGroupState(value: JsonElement, id: String): GroupState {
    val o = value.obj()
    val room = parseHostedGroup(o.getValue("room"))
    if (room.id != id) invalid()
    val driver = o["driver_status"]?.takeUnless { it == JsonNull }?.obj() ?: return GroupState(room)
    driver.bool("running")
    driver.getValue("counts").obj().keys.forEach { driver.getValue("counts").obj().num(it) }
    val pending = driver.array("pending_actions").map {
        val action = it.obj()
        action.str("task_id")
        when (action.str("kind")) {
            "retry" -> "Retry"
            "approval" -> {
                listOf("member_id", "run_id", "session_id", "request_id").forEach(action::str)
                action.num("execution_generation")
                action.getValue("approval").obj().array("choices").forEach { choice ->
                    if (choice !is JsonPrimitive || !choice.isString || choice.content !in setOf("once", "deny")) invalid()
                }
                "Approval required"
            }
            else -> "Action required"
        }
    }
    val peers = driver.array("peer_routes").map {
        val route = it.obj()
        if (route.str("room_id") != id) invalid()
        val member = room.members.firstOrNull { m -> m.id == route.str("member_id") } ?: invalid()
        val status = when (route.str("status")) {
            "ready" -> "Ready"
            "unavailable" -> "Unavailable"
            "needs_reauthorization" -> "Needs authorization"
            else -> "Unknown status"
        }
        "${member.label}: $status"
    }
    return GroupState(room, driver.bool("working"), driver.bool("blocked"), peers, pending)
}

internal fun parseGroupLog(value: JsonElement, id: String, since: Long): GroupLog {
    val o = value.obj()
    val authority = o.getValue("authority").obj()
    authority.str("gateway_id"); authority.num("epoch")
    val cursor = o.num("cursor")
    val latest = o.num("latest_seq")
    val more = o.bool("has_more")
    var previous = since
    val events = o.array("events").map {
        val e = it.obj()
        if (e.str("room_id") != id) invalid()
        val seq = e.num("seq")
        if (previous == Long.MAX_VALUE || seq != previous + 1) invalid()
        previous = seq
        e.str("event_id")
        if (!e.containsKey("authority_epoch")) invalid()
        if (e["authority_epoch"] != JsonNull) e.num("authority_epoch")
        e.timestamp("created_at"); e.bool("idempotent")
        val actor = e.getValue("actor").obj()
        actor.str("id")
        val actorKind = actor.str("kind")
        val kind = e.str("kind")
        val p = e.getValue("payload").obj()
        val member = if (kind == "message.member" || kind.startsWith("turn.")) {
            if (kind in setOf("message.member", "turn.settled", "turn.failed", "turn.cancelled", "turn.deferred")) {
                listOf("thread_id", "discussion_event_id", "turn_id", "member_id", "task_id").forEach(p::str)
                p.num("member_index"); p.num("round_index")
                if (kind.startsWith("turn.")) p.num("seen_through_seq")
                p.str("member_id")
            } else null
        } else null
        val text = when (kind) {
            "message.user" -> { p.str("thread_id"); safe(p.str("text")) }
            "message.member" -> safe(p.str("text"))
            "turn.settled" -> {
                p.num("seen_through_seq")
                if (!p.containsKey("message_event_id")) invalid()
                if (p["message_event_id"] != JsonNull) p.str("message_event_id")
                if (p.bool("passed")) "Passed" else "Finished"
            }
            "turn.failed" -> { p.str("error"); "Turn failed" }
            "turn.cancelled" -> { p.str("reason"); "Stopped" }
            "turn.deferred" -> { p.num("execution_generation"); p.str("reason"); "Member unavailable" }
            "room.activity" -> {
                p.str("thread_id"); p.str("discussion_event_id"); p.str("reason_code")
                when (p.str("status")) { "bounded" -> "Discussion limit reached"; "settled" -> "Discussion complete"; else -> invalid() }
            }
            "room.stop_requested" -> { p.str("cancel_id"); "Stopped" }
            "room.renamed" -> "Renamed to ${safe(p.str("name"))}"
            "room.disbanded" -> { if (p.str("room_id") != id) invalid(); "Group Chat disbanded" }
            "authority.claimed", "authority.lost" -> {
                p.str("previous_gateway_id"); p.str("authority_gateway_id"); p.num("authority_epoch")
                if (kind == "authority.lost") "Managed by another Gateway" else "Group Chat authority changed"
            }
            else -> "Group Chat updated"
        }
        GroupEvent(seq, kind, member, if (actorKind in setOf("user", "member", "system")) text else "Group Chat updated")
    }
    if (cursor != previous || latest < cursor || more != (cursor < latest) || (more && cursor <= since)) invalid()
    return GroupLog(events, cursor, more)
}
