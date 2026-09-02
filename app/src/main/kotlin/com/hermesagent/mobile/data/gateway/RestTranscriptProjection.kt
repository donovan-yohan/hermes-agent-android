package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/**
 * The two transcript contracts do not ship the same rows.
 *
 * `session.history` ships the Gateway's own **display projection** — one
 * `{role, text, row_id, timestamp}` per visible turn, tool rows already
 * resolved to `{role, name, context, args}`
 * (`tui_gateway/server.py:9720-9823` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). The paged REST route
 * `GET /api/sessions/{id}/messages` ships the **stored rows** instead —
 * `SELECT * FROM messages` with compaction display applied and nothing else
 * (`hermes_cli/web_routers/sessions.py:672-708` →
 * `hermes_state.py:12869-13016`).
 *
 * This is that projection, ported, so one parser reads both contracts and a
 * page fetched over REST merges into a transcript hydrated either way. The
 * durable address is the row's own `messages.id` — the same integer the RPC
 * forwards as `row_id` (`server.py:9799-9800`).
 *
 * Two places where this is deliberately NOT byte-for-byte the RPC's projection,
 * both ledgered in `docs/parity/transcript-backfill.md`:
 *
 * - A **tool row keeps its `content`, `row_id` and `timestamp`.** The RPC ships
 *   `{role, name, context, args}` and nothing else (`server.py:9755-9769`), so a
 *   tool row hydrated over the RPC has no stored result to expand. Desktop's own
 *   REST reader does attach it (`storedToolMessagePart`), and dropping the
 *   durable address here would make the one row this window cannot dedupe by id.
 *   So the tool row follows the **REST** contract, and a tool row is richer on
 *   this path than on the RPC path.
 * - `display_kind`/`display_metadata` beyond `"hidden"` are **not** forwarded.
 *   Nothing on Android reads them on either contract, so this is not a
 *   regression against the RPC path — but Desktop renders `model_switch`,
 *   `auto_continue`, `personality_switch` and `async_delegation_complete` as
 *   system timeline rows (`apps/desktop/src/lib/chat-messages/hydration.ts:94-116,197-208`),
 *   and Android does not, on either path.
 */
internal fun projectRestTranscriptRows(rows: List<JsonObject>): List<JsonObject> {
    val projected = mutableListOf<JsonObject>()
    // Scoped to this page, as upstream's is scoped to one read
    // (`server.py:9740-9752`). A tool row whose assistant call row fell on the
    // other side of the window boundary therefore renders with its stored
    // `tool_name` and no argument preview, rather than the call's name — the
    // page boundary's one visible cost, ledgered rather than carried, because a
    // map that outlived a page would be per-session repository state with a
    // lifetime nothing else in this file has.
    val toolCalls = mutableMapOf<String, RestToolCall>()

    for (row in rows) {
        val role = row.string("role") ?: continue
        if (role !in PROJECTED_TRANSCRIPT_ROLES) continue
        // Model-facing scaffolding: compaction references and interrupted-turn
        // checkpoints (`server.py:9733-9739`). The REST route stamps this on a
        // compaction row it could not project (`sessions.py:696-698`).
        if (row.string("display_kind") == "hidden") continue

        // The route replaces a compaction summary's visible body in place and
        // leaves the physical content for export (`sessions.py:700-704`).
        val content = row["display_content"]?.takeUnless { it is JsonNull } ?: row["content"]
        val text = content.coerceMessageText()
        // Gateway bookkeeping notices are persisted as `role=user` `[System: …]`
        // rows so strict providers accept them mid-history; they are runtime
        // metadata and never a user bubble (`server.py:9639-9650`).
        if (role == "user" && text.trimStart().startsWith(GATEWAY_SYSTEM_MARKER)) continue

        if (role == "assistant") {
            (row["tool_calls"] as? JsonArray)?.forEach { element ->
                val call = element as? JsonObject ?: return@forEach
                val id = call.string("id")?.takeIf(String::isNotEmpty) ?: return@forEach
                val function = call["function"] as? JsonObject ?: return@forEach
                val name = function.string("name")?.takeIf(String::isNotEmpty) ?: return@forEach
                toolCalls[id] = RestToolCall(name, function.toolCallArguments())
            }
            // An assistant row that only carries tool calls has nothing to say
            // (`server.py:9743-9754`).
            //
            // The presence test is the ARRAY, never the key. `SessionDB.get_messages`
            // builds each row as `dict(row)` (`hermes_state.py:13001-13002` @
            // `3ca096de`) over a `SELECT *` (`:12926`, and `:12943` on the
            // `include_compacted` read this app always makes), so every column of
            // the `messages` table rides the wire and `"tool_calls": null` is on
            // every row that made no call. Reading the key's presence would drop every
            // reasoning-only assistant turn — the row upstream keeps deliberately
            // ten lines below (`server.py:9770-9787`).
            if ((row["tool_calls"] as? JsonArray)?.isNotEmpty() == true && text.isBlank()) continue
        }

        if (role == "tool") {
            val known = row.string("tool_call_id")?.let(toolCalls::get)
            val name = known?.name
                ?: row.string("tool_name")?.takeIf(String::isNotEmpty)
                ?: "tool"
            val args = known?.arguments
            projected += buildJsonObject {
                put("role", JsonPrimitive("tool"))
                put("name", JsonPrimitive(name))
                if (args != null && args.isNotEmpty()) {
                    put("args", args)
                    toolArgumentPreview(name, args)?.let { put("context", JsonPrimitive(it)) }
                }
                content?.takeUnless { it is JsonNull }?.let { put("content", it) }
                row.rowIdPrimitive()?.let { put("row_id", it) }
                row["timestamp"]?.takeUnless { it is JsonNull }?.let { put("timestamp", it) }
            }
            continue
        }

        // An extended-thinking turn is persisted with its reasoning fields and
        // no visible text; dropping it as empty makes it vanish from a resumed
        // transcript while the disclosure has nothing to render
        // (`server.py:9770-9787`).
        val reasoning = REASONING_KEYS.filter { key ->
            role == "assistant" && row[key]?.takeUnless { it is JsonNull } != null
        }
        if (text.isBlank() && reasoning.isEmpty()) continue

        projected += buildJsonObject {
            put("role", JsonPrimitive(role))
            // A `/skill` turn is stored expanded; the invocation is what any
            // surface may render (`server.py:9801-9808`).
            put("text", JsonPrimitive(if (role == "user") skillInvocationText(text) ?: text else text))
            row["timestamp"]?.takeUnless { it is JsonNull }?.let { put("timestamp", it) }
            row.rowIdPrimitive()?.let { put("row_id", it) }
            reasoning.forEach { key -> row[key]?.let { put(key, it) } }
        }
    }
    return projected
}

/** One assistant tool call, as the rows that follow it are read against. */
private data class RestToolCall(val name: String, val arguments: JsonObject?)

/**
 * The `arguments` of a stored tool call: a JSON *string* on every provider that
 * follows the OpenAI shape, an object on the ones that do not
 * (`server.py:9744-9752` parses the string and tolerates a failure as `{}`).
 */
private fun JsonObject.toolCallArguments(): JsonObject? {
    (this["arguments"] as? JsonObject)?.let { return it }
    val raw = string("arguments") ?: return null
    return runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}

/**
 * The argument preview a tool row's collapsed title shows — what the RPC
 * projection ships as `context` (`server.py:7740-7756` →
 * `agent/display.py:446`).
 *
 * Ported: the primary-argument table (`display.py:457-468`) and the generic
 * tail (`:576-595`) — which argument stands for the call, one line, truncated
 * at 80 with an ellipsis. NOT ported: the per-tool phrasings above that tail
 * (`terminal`, `read_file`, `todo`, `memory`, `process`, `delegate_task`,
 * `browser_exec`, `session_search`, `send_message`, `skill_view`), which
 * rephrase the same argument rather than name a different one, and would be a
 * second copy of upstream's tool table to keep in step. The full call is still
 * reachable: `args` rides the projected row and the expanded tool view renders
 * it.
 *
 * Also NOT ported: `redact_tool_args_for_display`, which upstream runs FIRST
 * (`display.py:456`, defined `:400-414`) so a recognizable API key or token
 * typed into a browser field is masked before it reaches a preview. Its masking
 * is `redact_sensitive_text(..., force=True)` — thirteen credential patterns
 * (`agent/redact.py:831-900`), which this app does not carry and must not
 * half-carry: a partial copy would mask the shapes it knows and print the rest
 * while looking like it had checked. So the tools upstream redacts before
 * previewing get NO preview here ([TOOL_PREVIEW_REDACTED]); the argument itself
 * still rides the row as `args` for the expanded view, exactly as it does on
 * every other tool.
 */
private fun toolArgumentPreview(toolName: String, args: JsonObject): String? {
    if (toolName in TOOL_PREVIEW_REDACTED) return null
    val key = TOOL_PRIMARY_ARGS[toolName]
        ?: TOOL_FALLBACK_ARGS.firstOrNull { it in args }
        ?: return null
    val value = args[key] ?: return null
    val first = (value as? JsonArray)?.firstOrNull() ?: value
    val preview = first.coerceMessageText().replace(PREVIEW_WHITESPACE, " ").trim()
    if (preview.isEmpty()) return null
    return if (preview.length > TOOL_PREVIEW_MAX) preview.take(TOOL_PREVIEW_MAX - 3) + "..." else preview
}

private fun JsonObject.rowIdPrimitive(): JsonPrimitive? =
    (this["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let(::JsonPrimitive)

/** Roles the display projection carries (`server.py:9730-9732`). */
private val PROJECTED_TRANSCRIPT_ROLES = setOf("user", "assistant", "tool", "system")

/** Assistant reasoning the projection forwards verbatim (`server.py:9777-9782`). */
private val REASONING_KEYS =
    listOf("reasoning", "reasoning_content", "reasoning_details", "codex_reasoning_items")

private const val GATEWAY_SYSTEM_MARKER = "[System:"
private const val TOOL_PREVIEW_MAX = 80
private val PREVIEW_WHITESPACE = Regex("\\s+")

/** `agent/display.py:457-468` @ `3ca096de`, verbatim. */
private val TOOL_PRIMARY_ARGS = mapOf(
    "terminal" to "command",
    "web_search" to "query",
    "web_extract" to "urls",
    "read_file" to "path",
    "write_file" to "path",
    "patch" to "path",
    "search_files" to "pattern",
    "browser_navigate" to "url",
    "browser_click" to "ref",
    "browser_type" to "text",
    "image_generate" to "prompt",
    "text_to_speech" to "text",
    "vision_analyze" to "question",
    "skill_view" to "name",
    "skills_list" to "category",
    "cronjob" to "action",
    "execute_code" to "code",
    "browser_exec" to "code",
    "delegate_task" to "goal",
    "clarify" to "question",
    "skill_manage" to "name",
)

/** `agent/display.py:578`, in order. */
private val TOOL_FALLBACK_ARGS =
    listOf("query", "text", "command", "path", "name", "prompt", "code", "goal")

/**
 * Tools whose preview argument upstream masks before building the preview
 * (`redact_tool_args_for_display`, `agent/display.py:400-414`). The masking is
 * not ported, so the preview is not built — `browser_type`'s primary argument
 * is `text`, the field a password or API key is typed into, and the generic
 * fallback would reach for the same `text` if the table entry were simply
 * removed.
 */
private val TOOL_PREVIEW_REDACTED = setOf("browser_type")

/** The text of a message body, whatever shape the row carries it in. */
internal fun JsonElement?.coerceMessageText(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    is JsonArray -> joinToString("") { item ->
        when (item) {
            is JsonPrimitive -> item.content
            is JsonObject -> item.string("text") ?: item.string("content") ?: ""
            else -> ""
        }
    }

    is JsonObject -> string("text") ?: string("content") ?: ""
}
