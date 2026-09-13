package com.hermesagent.mobile.plugins.kanban

import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class KanbanTask(
    val id: String,
    val title: String,
    val status: String,
    val assignee: String? = null,
    val priority: Int? = null,
    val body: String? = null,
    val latestSummary: String? = null,
    val result: String? = null,
)
data class KanbanColumn(val name: String, val tasks: List<KanbanTask>)
data class KanbanBoard(val columns: List<KanbanColumn>)
data class KanbanTaskDetail(
    val task: KanbanTask,
    val parentIds: List<String>,
    val childIds: List<String>,
    val childResults: List<KanbanTask>,
)

sealed interface KanbanRead<out T> {
    data class Value<T>(val value: T) : KanbanRead<T>
    data object Unavailable : KanbanRead<Nothing>
    data object Gone : KanbanRead<Nothing>
    data object Refused : KanbanRead<Nothing>
}

/** Only GET board and GET tasks/{encoded id}; this class deliberately has no write API. */
class KanbanPluginRepository(private val rest: suspend (String, PluginRestOptions) -> PluginRestResult) {
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
    return KanbanBoard(columns.map { value ->
        val column = value as? JsonObject ?: return null
        val name = column.string("name") ?: return null
        val tasks = column.array("tasks")?.map { parseTask(it as? JsonObject ?: return null) ?: return null } ?: return null
        KanbanColumn(name, tasks)
    })
}

private fun parseDetail(bytes: ByteArray): KanbanTaskDetail? {
    val root = objectOf(bytes) ?: return null
    val task = parseTask(root.obj("task") ?: root) ?: return null
    val links = root.obj("links")
    val parents = links?.stringList("parents") ?: emptyList()
    val children = links?.stringList("children") ?: emptyList()
    if (links != null && (parents.size != (links.array("parents")?.size ?: -1) || children.size != (links.array("children")?.size ?: -1))) return null
    val childResults = root.array("child_results")?.map { parseTask(it as? JsonObject ?: return null) ?: return null } ?: emptyList()
    return KanbanTaskDetail(task, parents.take(MAX_RELATED), children.take(MAX_RELATED), childResults.take(MAX_RELATED))
}

private const val MAX_RELATED = 12
private fun JsonObject.obj(name: String) = this[name] as? JsonObject
private fun parseTask(row: JsonObject): KanbanTask? = KanbanTask(
    id = row.string("id")?.takeIf(String::isNotBlank) ?: return null,
    title = row.string("title")?.takeIf(String::isNotBlank) ?: return null,
    status = row.string("status")?.takeIf(String::isNotBlank) ?: return null,
    assignee = row.string("assignee"), priority = row.int("priority"), body = row.string("body"),
    latestSummary = row.string("latest_summary"), result = row.string("result"),
)
private fun objectOf(bytes: ByteArray) = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
private fun JsonObject.array(name: String) = this[name] as? JsonArray
private fun JsonObject.stringList(name: String) = array(name)?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull } ?: emptyList()
