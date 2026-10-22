package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

interface KanbanMove {
    fun open(expectedEndpoint: Long): KanbanMoveScope?
}
interface KanbanMoveScope : AutoCloseable {
    suspend fun prepare(task: KanbanTask): KanbanMoveChoices?
    suspend fun move(status: String): KanbanTask?
    override fun close()
}
data class KanbanMoveChoices(val board: String, val task: KanbanTask, val statuses: List<String>)
object UnavailableKanbanMove : KanbanMove {
    override fun open(expectedEndpoint: Long): KanbanMoveScope? = null
}

/** Namespace-bound typed host door; plugin_api.py:638-716; api.ts:439-440
 * @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa. */
internal class GatewayKanbanMove(
    private val http: () -> GatewayHttp?,
    private val endpoint: StateFlow<Long>,
    private val fence: EndpointDispatchFence,
) : KanbanMove {
    override fun open(expectedEndpoint: Long): KanbanMoveScope? {
        val transport = http() as? EndpointDispatchingGatewayHttp ?: return null
        val owns = { endpoint.value == expectedEndpoint && http() === transport }
        val lease = fence.leaseAt(expectedEndpoint, owns) ?: return null
        return BoundMove(transport, fence, lease, owns)
    }
}
private class BoundMove(
    private val transport: EndpointDispatchingGatewayHttp,
    private val fence: EndpointDispatchFence,
    private val lease: Long,
    private val owns: () -> Boolean,
) : KanbanMoveScope {
    private val lock = Any()
    private var closed = false
    private var submitted = false
    private var choices: KanbanMoveChoices? = null
    override fun close() { synchronized(lock) { closed = true } }
    private fun current() = synchronized(lock) { !closed && owns() } && fence.leaseAt(lease, owns) != null

    override suspend fun prepare(task: KanbanTask): KanbanMoveChoices? {
        if (task.id.isBlank() || task.id == "." || task.id == "..") return null
        val board = exchange("boards")?.text("current")?.takeIf { it.isNotBlank() } ?: return null
        val snapshot = exchange("board", board = board) ?: return null
        val columns = snapshot["columns"] as? JsonArray ?: return null
        val named = exchange("tasks/${encodeMoveId(task.id)}", board = board)?.get("task") as? JsonObject ?: return null
        val fresh = parseMoved(named)?.takeIf { it.id == task.id && it.title == task.title } ?: return null
        // Server enumerates the lanes. Desktop ui.tsx:48-55 forbids system-owned targets.
        val statuses = columns.mapNotNull { (it as? JsonObject)?.text("name") }
            .filter { it != fresh.status && it !in listOf("running", "review", "scheduled") }.distinct()
        return if (current()) KanbanMoveChoices(board, fresh, statuses).also { choices = it } else null
    }

    override suspend fun move(status: String): KanbanTask? {
        val held = choices ?: return null
        if (status !in held.statuses || !current()) return null
        synchronized(lock) { if (submitted || closed) return null; submitted = true }
        val path = "tasks/${encodeMoveId(held.task.id)}"
        val reply = exchange(path, "PATCH", held.board, buildJsonObject { put("status", status) }) ?: return null
        val receipt = parseMoved(reply["task"] as? JsonObject ?: return null)
            ?.takeIf { it.id == held.task.id && it.status == status } ?: return null
        val fresh = exchange(path, board = held.board)?.get("task") as? JsonObject ?: return null
        val confirmed = parseMoved(fresh)?.takeIf { it.id == receipt.id && it.status == status } ?: return null
        exchange("dispatch", "POST", held.board, buildJsonObject {}) // same best-effort nudge as Desktop
        return if (current()) confirmed else null
    }

    private suspend fun exchange(path: String, method: String = "GET", board: String? = null, body: JsonObject? = null): JsonObject? {
        if (!current()) return null
        val request = GatewayHttpRequest("api/plugins/kanban/$path", method,
            body?.toString()?.toRequestBody("application/json".toMediaType()), 8_000,
            query = board?.let { mapOf("board" to it) } ?: emptyMap(),
            maxResponseBytes = 1024L * 1024, isCurrent = ::current)
        val result = transport.executeAtDispatch(request) { send ->
            fence.dispatchIfCurrent(lease, owns) { synchronized(lock) { !closed && owns() && send() } }
        }
        return when (result) {
            is GatewayHttpResult.Success -> result.consumeBody { bytes ->
                if (!current()) null else runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            }
            is GatewayHttpResult.Rejected -> result.consumeEnvelope { null }
        }
    }
}
private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun parseMoved(row: JsonObject): KanbanTask? = KanbanTask(
    id = row.text("id")?.takeIf { it.isNotBlank() } ?: return null,
    title = row.text("title")?.takeIf { it.isNotBlank() } ?: return null,
    status = row.text("status")?.takeIf { it.isNotBlank() } ?: return null,
    assignee = row.text("assignee"), body = row.text("body"),
    priority = (row["priority"] as? JsonPrimitive)?.intOrNull,
)
private fun encodeMoveId(id: String) = java.net.URLEncoder.encode(id, Charsets.UTF_8.name()).replace("+", "%20")
