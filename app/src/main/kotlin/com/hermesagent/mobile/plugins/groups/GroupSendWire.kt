package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.plugins.PluginConnectionToken
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.PluginRefusalReason
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Supplied by the connection owner, never inferred from the selected row or a display label. */
internal data class GroupSendIdentity(val savedRow: String, val endpointBinding: String, val gateway: String) {
    init { listOf(savedRow, endpointBinding, gateway).forEach(::sendIdentifier) }
}

internal fun sendIdentifier(value: String): String = value.also {
    require(it.length in 1..128 && Regex("[A-Za-z0-9][A-Za-z0-9._:-]*").matches(it))
}

// Python str.strip whitespace, including U+0085 and the information separators.
internal fun pythonSpace(c: Char): Boolean = c in '\u0009'..'\u000d' || c in '\u001c'..'\u0020' ||
    c == '\u0085' || c == '\u00a0' || c == '\u1680' || c in '\u2000'..'\u200a' ||
    c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000'

internal data class GroupSendPayload(val text: String, val thread: String) {
    init {
        sendIdentifier(thread)
        require(text.isNotEmpty() && text == text.trim(::pythonSpace))
        require(Charsets.UTF_8.newEncoder().canEncode(text) && text.toByteArray().size <= 64 * 1024)
    }
    fun wire(): JsonObject = buildJsonObject { put("text", text); put("thread_id", thread) }
    companion object {
        fun normalize(text: String, thread: String) = GroupSendPayload(text.trim(::pythonSpace), sendIdentifier(thread))
    }
}

internal fun storedUserEventId(raw: String): String = "user:" + MessageDigest.getInstance("SHA-256")
    .digest(sendIdentifier(raw).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal enum class GroupSendProblem { TransportUncertain, ServerRefused, WorkerUnavailable, AuthorityBlocked, Expired, Unsupported, InvalidResponse }
internal class GroupSendFailure(val problem: GroupSendProblem) : Exception()

internal fun sendResult(result: PluginHostResult): JsonElement = when (result) {
    is PluginHostResult.Success -> result.result
    PluginHostResult.UnavailableOnGateway -> throw GroupSendFailure(GroupSendProblem.Unsupported)
    is PluginHostResult.Refused -> throw GroupSendFailure(when {
        result.reason == PluginRefusalReason.RoomHistoryExpired -> GroupSendProblem.Expired
        result.reason == PluginRefusalReason.AuthorityConflict -> GroupSendProblem.AuthorityBlocked
        result.code == 4123 -> GroupSendProblem.WorkerUnavailable
        result.code == 0 -> GroupSendProblem.TransportUncertain
        else -> GroupSendProblem.ServerRefused
    })
}

internal data class GroupSendReceipt(val storedId: String, val sequence: Long, val driverStarted: Boolean)

// Target coordinates for send
internal data class GroupSendTarget(val identity: GroupSendIdentity, val room: String) {
    init {
        sendIdentifier(room)
    }
}

// Complete typed send operation model
internal data class GroupSendOperation(
    val target: GroupSendTarget,
    val rawId: String,
    val payload: GroupSendPayload,
) {
    init {
        sendIdentifier(rawId)
    }

    fun request(): JsonObject = buildJsonObject {
        put("room_id", target.room)
        put("event_id", rawId)
        put("payload", payload.wire())
    }
}

// Frozen authority: tui_gateway/methods_groups.py:381-395,
// tui_gateway/hosted_room_service.py:454-466 and gateway/hosted_rooms.py:244-246
// @ d177b119e9c56c9ddc0b7379ffce52341ec06584. driver_started is not execution evidence.
internal fun matchingSendEvent(value: JsonElement, operation: GroupSendOperation): Long? {
    val e = value.obj()
    if (e.str("event_id") != storedUserEventId(operation.rawId)) return null
    require(e.str("room_id") == operation.target.room && e.str("kind") == "message.user")
    val actor = e.getValue("actor").obj()
    require(actor.str("kind") == "user" && actor.str("id") == "desktop")
    require(e.getValue("payload") == operation.payload.wire())
    require(e.num("authority_epoch") > 0)
    val created = e["created_at"] as? JsonPrimitive ?: invalid()
    require(!created.isString && created.doubleOrNull?.let { it.isFinite() && it >= 0 } == true)
    e.bool("idempotent")
    return e.num("seq").also { require(it > 0) }
}

internal fun parseSendReceipt(value: JsonElement, operation: GroupSendOperation): GroupSendReceipt {
    val o = value.obj()
    require(o.str("client_event_id") == operation.rawId && o.bool("accepted"))
    val driver = o.bool("driver_started")
    val seq = matchingSendEvent(o.getValue("event"), operation) ?: invalid()
    return GroupSendReceipt(storedUserEventId(operation.rawId), seq, driver)
}

internal data class GroupSendRefresh(val events: List<JsonObject>, val writable: Boolean, val worker: Boolean)
internal interface GroupSendConnection {
    val identity: GroupSendIdentity
    suspend fun refresh(room: String): GroupSendRefresh
    suspend fun send(operation: GroupSendOperation): JsonElement
}

/** Not registered in GroupsPlugin. Parent must bind stable identity to this exact authenticated leg. */
internal class BoundGroupSendConnection(
    private val host: PluginHost,
    override val identity: GroupSendIdentity,
    private val endpoint: Long,
    private val token: PluginConnectionToken,
    private val pageBudget: Int = 512,
) : GroupSendConnection {
    private suspend fun request(method: String, params: JsonObject = buildJsonObject {}): JsonElement =
        sendResult(host.requestAtConnection(endpoint, token, method, params))

    override suspend fun refresh(room: String): GroupSendRefresh {
        val capabilities = parseGroupCapabilities(request("groups.capabilities"))
        if (capabilities.version != 2L) throw GroupSendFailure(GroupSendProblem.Unsupported)
        if (capabilities.authorityGatewayId != identity.gateway) throw GroupSendFailure(GroupSendProblem.AuthorityBlocked)
        val state = parseGroupState(request("groups.state", buildJsonObject {
            put("room_id", room); put("include_disbanded", true)
        }), room)
        var cursor = 0L
        val events = mutableListOf<JsonObject>()
        repeat(pageBudget) {
            val raw = request("groups.log", buildJsonObject {
                put("room_id", room); put("since_seq", cursor); put("limit", capabilities.limit)
                put("include_disbanded", true)
            }).obj()
            val page = parseGroupLog(raw, room, cursor)
            events += raw.array("events").map { it.obj() }
            cursor = page.cursor
            if (!page.more) return GroupSendRefresh(events.toList(),
                !state.room.disbanded && page.authority == identity.gateway, capabilities.driver)
        }
        throw GroupSendFailure(GroupSendProblem.InvalidResponse)
    }

    override suspend fun send(operation: GroupSendOperation): JsonElement {
        require(operation.target.identity == identity)
        return request("groups.send", operation.request())
    }
}
