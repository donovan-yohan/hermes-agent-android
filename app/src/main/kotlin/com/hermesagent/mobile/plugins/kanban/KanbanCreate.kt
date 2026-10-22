package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.consumeBody
import com.hermesagent.mobile.data.gateway.consumeEnvelope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/** Typed plugin namespace capability. No origin, credentials or arbitrary core route escapes. */
interface KanbanCreate {
    fun open(expectedEndpoint: Long): KanbanCreateScope?
}
interface KanbanCreateScope : AutoCloseable {
    suspend fun prepare(): KanbanCreateDefaults?
    suspend fun submit(draft: KanbanNewTask): KanbanCreated?
    override fun close()
}
data class KanbanCreateDefaults(val board: String, val assignee: String, val workspaceKind: String = "scratch")
data class KanbanNewTask(val title: String, val body: String = "", val priority: Int = 0)
data class KanbanCreated(val task: KanbanTask, val dispatcherWarning: Boolean)
object UnavailableKanbanCreate : KanbanCreate {
    override fun open(expectedEndpoint: Long): KanbanCreateScope? = null
}

/** Contract: plugin_api.py:414-457; Desktop board.tsx:627-675, api.ts:442-443
 * @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa. */
internal class GatewayKanbanCreate(
    private val http: () -> GatewayHttp?,
    private val endpoint: StateFlow<Long>,
    private val fence: EndpointDispatchFence,
) : KanbanCreate {
    override fun open(expectedEndpoint: Long): KanbanCreateScope? {
        val transport = http() as? EndpointDispatchingGatewayHttp ?: return null
        val owns = { endpoint.value == expectedEndpoint && http() === transport }
        val lease = fence.leaseAt(expectedEndpoint, owns) ?: return null
        return BoundCreate(transport, fence, lease, owns)
    }
}

private class BoundCreate(
    private val transport: EndpointDispatchingGatewayHttp,
    private val fence: EndpointDispatchFence,
    private val lease: Long,
    private val owns: () -> Boolean,
) : KanbanCreateScope {
    private val lock = Any()
    private var closed = false
    private var submitted = false
    private var defaults: KanbanCreateDefaults? = null
    // One dialog, one idempotent create even when the reply is lost. Never auto-retry.
    private val idempotency = UUID.randomUUID().toString()
    override fun close() { synchronized(lock) { closed = true } }
    private fun current() = synchronized(lock) { !closed && owns() } && fence.leaseAt(lease, owns) != null

    override suspend fun prepare(): KanbanCreateDefaults? {
        val boards = exchange("boards") ?: return null
        val board = boards.text("current")?.takeIf { it.isNotBlank() } ?: return null
        val metadata = (boards["boards"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.singleOrNull { it.text("slug") == board } ?: return null
        val workspaceKind = metadata.text("default_workspace_kind")
            ?.takeIf { it in listOf("scratch", "dir", "worktree") } ?: return null
        val orchestration = exchange("orchestration") ?: return null
        val assignee = orchestration.text("resolved_default_assignee")?.takeIf { it.isNotBlank() } ?: return null
        return if (current()) KanbanCreateDefaults(board, assignee, workspaceKind).also { defaults = it } else null
    }

    override suspend fun submit(draft: KanbanNewTask): KanbanCreated? {
        val held = defaults ?: return null
        if (draft.title.isBlank() || !current()) return null
        synchronized(lock) { if (submitted || closed) return null; submitted = true }
        val payload = buildJsonObject {
            put("title", draft.title.trim()); put("body", draft.body.trim().ifEmpty { null })
            put("priority", draft.priority); put("assignee", held.assignee)
            put("triage", false); put("goal_mode", false)
            put("parents", JsonArray(emptyList())); put("idempotency_key", idempotency)
            // Explicit recommended kind; omit path so default_workdir-only boards inherit too.
            put("workspace_kind", held.workspaceKind)
        }
        val reply = exchange("tasks", "POST", held.board, payload) ?: return null
        val task = parseCreated(reply["task"] as? JsonObject ?: return null) ?: return null
        if (task.id == "." || task.id == "..") return null
        val fresh = exchange("tasks/${encodeKanbanId(task.id)}", board = held.board)
            ?.get("task") as? JsonObject ?: return null
        val confirmed = parseCreated(fresh)?.takeIf { it.id == task.id && it.title == draft.title.trim() } ?: return null
        // Same namespace and board as the confirmed create; best effort, never replay the create.
        val dispatch = exchange("dispatch", "POST", held.board, buildJsonObject {})
        return if (current()) KanbanCreated(confirmed, reply["warning"] is JsonPrimitive || dispatch == null) else null
    }

    private suspend fun exchange(
        path: String, method: String = "GET", board: String? = null, body: JsonObject? = null,
    ): JsonObject? {
        if (!current()) return null
        val request = GatewayHttpRequest(
            path = "api/plugins/kanban/$path", method = method,
            body = body?.toString()?.toRequestBody("application/json".toMediaType()),
            timeoutMillis = 8_000, maxResponseBytes = 1024L * 1024,
            query = board?.let { mapOf("board" to it) } ?: emptyMap(), isCurrent = ::current,
        )
        val result = transport.executeAtDispatch(request) { send ->
            fence.dispatchIfCurrent(lease, owns) { synchronized(lock) { !closed && owns() && send() } }
        }
        return when (result) {
            is GatewayHttpResult.Success -> result.consumeBody { bytes ->
                if (!current()) null else runCatching {
                    Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
                }.getOrNull()
            }
            is GatewayHttpResult.Rejected -> result.consumeEnvelope { null }
        }
    }
}
private fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun parseCreated(row: JsonObject): KanbanTask? = KanbanTask(
    id = row.text("id")?.takeIf { it.isNotBlank() } ?: return null,
    title = row.text("title")?.takeIf { it.isNotBlank() } ?: return null,
    status = row.text("status")?.takeIf { it.isNotBlank() } ?: return null,
    assignee = row.text("assignee"), body = row.text("body"),
    priority = (row["priority"] as? JsonPrimitive)?.intOrNull,
)
private fun encodeKanbanId(id: String) = java.net.URLEncoder.encode(id, Charsets.UTF_8.name()).replace("+", "%20")
