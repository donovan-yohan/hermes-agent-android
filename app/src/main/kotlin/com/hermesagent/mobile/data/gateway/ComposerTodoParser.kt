package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.data.ssh.redact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Desktop-compatible `todo` payload parser.
 *
 * Accepted shapes are a todo array, a JSON string, or an object wrapping
 * `{todos}`. String/object recursion is bounded to the same two levels as the
 * pinned Desktop parser. Invalid rows are ignored without inventing statuses.
 */
internal fun parseComposerTodos(input: JsonElement?, depth: Int = 0): List<ComposerTodoStatus>? {
    if (input == null || depth > MAX_TODO_PARSE_DEPTH) return null
    return when (input) {
        is JsonArray -> input.mapNotNull(::parseComposerTodo)
        is JsonObject -> parseComposerTodos(input["todos"], depth + 1)
        is JsonPrimitive -> {
            if (!input.isString) return null
            val raw = input.contentOrNull?.takeIf { it.length <= MAX_TODO_PAYLOAD_CHARS } ?: return null
            val decoded = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
            parseComposerTodos(decoded, depth + 1)
        }
    }
}

/** Live Gateway order: explicit todos, completed result, then tool arguments. */
internal fun parseComposerTodosFromTool(payload: JsonObject): List<ComposerTodoStatus>? =
    TODO_PAYLOAD_KEYS.firstNotNullOfOrNull { key -> parseComposerTodos(payload[key]) }

private fun parseComposerTodo(input: JsonElement): ComposerTodoStatus? {
    val row = input as? JsonObject ?: return null
    val id = row.primitiveText("id")?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_TODO_ID) ?: return null
    val title = row.primitiveText("content")
        ?.let(::redact)
        ?.replace(TODO_WHITESPACE, " ")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_TODO_TITLE)
        ?: return null
    val state = when (row.strictString("status")) {
        "pending" -> ComposerTodoState.Pending
        "in_progress" -> ComposerTodoState.InProgress
        "completed" -> ComposerTodoState.Completed
        "cancelled" -> ComposerTodoState.Cancelled
        else -> return null
    }
    return ComposerTodoStatus(id = id, title = title, state = state)
}

private fun JsonObject.strictString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

/** Desktop stringifies primitive ids/content, while status remains a strict enum string. */
private fun JsonObject.primitiveText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private val TODO_PAYLOAD_KEYS = listOf("todos", "result", "args", "arguments", "input", "args_text")
private val TODO_WHITESPACE = Regex("\\s+")
private const val MAX_TODO_PARSE_DEPTH = 2
private const val MAX_TODO_PAYLOAD_CHARS = 128 * 1024
private const val MAX_TODO_ID = 256
private const val MAX_TODO_TITLE = 1_024
