package com.hermesagent.mobile.plugins.groups

import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.PluginRefusalReason
import kotlinx.serialization.json.*

internal class GroupReadFailure(
    val unsupported: Boolean = false,
    val reason: PluginRefusalReason? = null,
) : Exception()

/** A read sequence's transport must bind every dispatch and answer to one ready leg. */
class GroupsRepository(
    private val request: suspend (String, JsonObject) -> PluginHostResult,
    private val pageBudget: Int = 512,
) {
    private suspend fun <T> read(method: String, params: JsonObject, parse: (JsonElement) -> T): T {
        return when (val result = request(method, params)) {
            is PluginHostResult.Success -> try {
                parse(result.result)
            } catch (_: IllegalArgumentException) {
                throw GroupReadFailure()
            } catch (_: NoSuchElementException) {
                throw GroupReadFailure()
            }
            PluginHostResult.UnavailableOnGateway -> throw GroupReadFailure(unsupported = true)
            is PluginHostResult.Refused -> throw GroupReadFailure(reason = result.reason)
        }
    }

    suspend fun capabilities(): GroupCapabilities = read("groups.capabilities", buildJsonObject {}, ::parseGroupCapabilities)

    suspend fun list(): List<HostedGroup> {
        var offset = 0L
        val rooms = linkedMapOf<String, HostedGroup>()
        repeat(pageBudget) {
            val page = read("groups.list", buildJsonObject {
                put("limit", 500); put("offset", offset)
            }) { parseGroupList(it, offset) }
            for (room in page.rooms) {
                if (rooms.put(room.id, room) != null) throw GroupReadFailure()
            }
            offset = page.next ?: return rooms.values.toList()
        }
        throw GroupReadFailure()
    }

    suspend fun state(id: String): GroupState = read("groups.state", buildJsonObject {
        put("room_id", id); put("include_disbanded", true)
    }) { parseGroupState(it, id) }

    /** Whole validated pages only. A later failed page leaves earlier good pages intact. */
    suspend fun log(id: String, since: Long, limit: Int, applyPage: (GroupLog) -> Unit) {
        var cursor = since
        repeat(pageBudget) {
            val page = read("groups.log", buildJsonObject {
                put("room_id", id); put("since_seq", cursor); put("limit", limit)
                put("include_disbanded", true)
            }) { parseGroupLog(it, id, cursor) }
            applyPage(page)
            cursor = page.cursor
            if (!page.more) return
        }
        throw GroupReadFailure()
    }
}
