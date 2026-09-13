package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Read-only projection of the Kanban plugin's pinned board response. */
data class KanbanTask(val id: String, val title: String, val status: String, val raw: JsonObject)
data class KanbanColumn(val name: String, val tasks: List<KanbanTask>)
data class KanbanBoard(val columns: List<KanbanColumn>)
data class KanbanTaskDetail(val task: KanbanTask, val siblingCollections: Set<String>)

sealed interface KanbanRead<out T> {
    data class Value<T>(val value: T) : KanbanRead<T>
    data object Unavailable : KanbanRead<Nothing>
    data object Gone : KanbanRead<Nothing>
    data object Refused : KanbanRead<Nothing>
}

/** Only GET board and GET tasks/{encoded id}; this class has no write API. */
class KanbanPluginRepository(
    private val rest: suspend (String, PluginRestOptions) -> PluginRestResult,
) {
    suspend fun board(): KanbanRead<KanbanBoard> = decode(rest("board", options()), ::parseBoard)

    suspend fun task(id: String): KanbanRead<KanbanTaskDetail> {
        if (id.isBlank()) return KanbanRead.Refused
        val path = "tasks/${URLEncoder.encode(id, Charsets.UTF_8.name()).replace("+", "%20")}"
        return when (val result = rest(path, options(captureEnvelope = true))) {
            PluginRestResult.UnavailableOnGateway -> KanbanRead.Unavailable
            is PluginRestResult.Refused -> if (result.statusCode == 404) KanbanRead.Gone else KanbanRead.Refused
            is PluginRestResult.Success -> parseDetail(result.bodyBytes)?.let { KanbanRead.Value(it) } ?: KanbanRead.Refused
        }
    }

    private fun <T> decode(result: PluginRestResult, parser: (ByteArray) -> T?): KanbanRead<T> = when (result) {
        PluginRestResult.UnavailableOnGateway -> KanbanRead.Unavailable
        is PluginRestResult.Refused -> KanbanRead.Refused
        is PluginRestResult.Success -> parser(result.bodyBytes)?.let { KanbanRead.Value(it) } ?: KanbanRead.Refused
    }

    private fun options(captureEnvelope: Boolean = false) = PluginRestOptions(
        method = "GET", captureEnvelope = captureEnvelope, timeoutMillis = 8_000, maxResponseBytes = 1024L * 1024L,
    )
}

private fun parseBoard(bytes: ByteArray): KanbanBoard? {
    val root = objectOf(bytes) ?: return null
    val columns = root.array("columns") ?: return null
    val parsedColumns = columns.map { value ->
        val column = value as? JsonObject ?: return null
        val name = column.string("name") ?: return null
        val tasks = column.array("tasks")?.map { row ->
            parseTask(row as? JsonObject ?: return null) ?: return null
        } ?: return null
        KanbanColumn(name, tasks)
    }
    return KanbanBoard(parsedColumns)
}

private fun parseDetail(bytes: ByteArray): KanbanTaskDetail? {
    val root = objectOf(bytes) ?: return null
    // The detail envelope keeps task siblings beside `task`; accepting the task
    // itself as the root also tolerates older projected responses.
    val task = parseTask(root.obj("task") ?: root) ?: return null
    val siblings = setOf("comments", "events", "attachments", "links", "child_results", "runs")
    if (siblings.any { root[it] != null && root[it] !is JsonArray }) return null
    return KanbanTaskDetail(task, siblings.filter { root[it] is JsonArray }.toSet())
}

private fun JsonObject.obj(name: String) = this[name] as? JsonObject

private fun parseTask(row: JsonObject): KanbanTask? = KanbanTask(
    id = row.string("id") ?: return null,
    title = row.string("title") ?: return null,
    status = row.string("status") ?: return null,
    raw = row,
)
private fun objectOf(bytes: ByteArray) = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.array(name: String) = this[name] as? JsonArray
