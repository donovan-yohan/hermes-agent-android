package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What a tool row shows, projected once from a [ToolActivity].
 *
 * This is Desktop's `ToolView`, ported field for field from
 * `apps/desktop/src/components/assistant-ui/tool/fallback-model/types.ts:32-64`
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`, built by the same rules as
 * `fallback-model/index.ts:1409-1499` (`buildToolView`). The renderer in
 * `Transcript.kt` reads only this, so the question "what does Desktop show
 * here?" is answered in one pure function a unit test can pin, not scattered
 * through composables.
 *
 * Android carries the tool's args and result as raw JSON text on
 * [ToolActivity] rather than as decoded objects, so every accessor here is a
 * tolerant read: a payload that is not an object, not JSON at all, or is
 * missing the field simply yields nothing. Tool output is untrusted input.
 *
 * Fields Desktop has that this slice deliberately does not carry —
 * `imageUrl`, `previewTarget`, `titleAction` — are the explicit non-goals of
 * issue #71 (no inline images, no artifact detection) plus the title grammar
 * Android already owns. `docs/parity/tool-output-fidelity.md` holds the ledger.
 */
internal data class ToolView(
    val tone: ToolTone,
    val icon: ToolIconName?,
    val status: ToolStatus,
    val countLabel: String?,
    val durationLabel: String?,
    val detail: String,
    val detailLabel: String,
    val inlineDiff: String?,
    /**
     * Set for tools whose output naturally contains ANSI escape codes
     * (`terminal` / `execute_code`) so the renderer runs them through the
     * parser instead of printing them as literals (`types.ts:41-44`).
     */
    val rendersAnsi: Boolean,
    /** Original query, shown above structured web-search results (`types.ts:45-46`). */
    val searchQuery: String?,
    val searchHits: List<SearchResultRow>,
    /**
     * When set, the renderer uses stdout + stderr as separate sections and
     * ignores the merged [detail] (`types.ts:56-58`).
     */
    val stdout: String?,
    /**
     * The backend's own stderr stream, shown as its own labelled, neutrally
     * tinted block under stdout — distinct from an error tone, because many
     * CLIs log informational output there (`types.ts:48-51`).
     */
    val stderr: String?,
    /** Terminal-only command, shown as the prompt line (`types.ts:52-53`). */
    val terminalCommand: String?,
    /** Terminal-only process exit code, when the backend reported one (`types.ts:54-55`). */
    val terminalExitCode: Int?,
    /** The row's Copy action. Its text is the UNCLAMPED payload. */
    val copy: ToolCopyAction?,
)

/** `types.ts:1` — what kind of thing the tool touched. */
internal enum class ToolTone { Agent, Browser, Default, File, Image, Terminal, Web }

/**
 * The tool-tone icon set (`components/ui/tool-icon.tsx` @ the pinned SHA,
 * keyed by the names `TOOL_META` uses at `fallback-model/index.ts:142-214`).
 *
 * Desktop draws these as filled Phosphor paths and falls back to the outline
 * Codicon of the same name; Android has the Codicon font only, so the outline
 * is the whole story here. `Brain` is the one name Codicon 0.0.45 does not
 * ship — see the deviation ledger for what stands in.
 */
internal enum class ToolIconName {
    Brain,
    Edit,
    Eye,
    File,
    FileMedia,
    Files,
    Globe,
    Question,
    Search,
    Terminal,
    Tools,
    Watch,
}

/**
 * `types.ts:2` plus one rung Desktop has no concept of.
 *
 * Desktop's thread cannot show a *stopped* tool: a user stop ends the turn and
 * the row is simply left as it was. Android models `ToolState.Stopped`
 * explicitly and already paints it, so dropping it here to match Desktop's four
 * would lose state the transcript is holding.
 */
internal enum class ToolStatus { Running, Success, Warning, Error, Stopped }

/** `types.ts:15-19` — one structured web-search hit. */
internal data class SearchResultRow(val title: String, val url: String, val snippet: String)

/**
 * A row's copy affordance: what it is called, what it says once it has run, and
 * the full text it yields.
 *
 * The confirmation is spelled out rather than derived from the label, so both
 * strings are product copy a reviewer can read in one place.
 */
internal data class ToolCopyAction(val label: String, val confirmation: String, val text: String)

private fun copyOutput(text: String) = ToolCopyAction("Copy output", "Output copied", text)
private fun copyCommand(text: String) = ToolCopyAction("Copy command", "Command copied", text)
private fun copyResults(text: String) = ToolCopyAction("Copy results", "Results copied", text)
private fun copyQuery(text: String) = ToolCopyAction("Copy query", "Query copied", text)
private fun copyFile(text: String) = ToolCopyAction("Copy file", "File copied", text)
private fun copyPath(text: String) = ToolCopyAction("Copy path", "Path copied", text)

/**
 * Each tool result is capped by the backend, but a turn over a big directory
 * stacks many rows; painting them all is what floods a transcript
 * (`fallback-model/format.ts:45-49`). Desktop's cap, unchanged.
 */
internal const val MAX_TOOL_RENDER_CHARS = 20_000

/**
 * The phone half of the same clamp. Desktop parks its payload in an 80 px box
 * that scrolls internally, so the line count never reaches layout; Compose has
 * no such box in a transcript, and a nested vertical scroller inside a
 * `LazyColumn` is the wrong trade on touch. Clamping the lines is the
 * adaptation — Copy still yields everything.
 */
internal const val MAX_TOOL_RENDER_LINES = 200

/**
 * Clamp a payload for painting. The row's Copy reads the unclamped text.
 *
 * `format.ts:51-59`, with the line cap above folded into the same cut so there
 * is one truncation sentence rather than two.
 */
internal fun clampForDisplay(value: String): String {
    val lineCut = value.nthNewlineIndex(MAX_TOOL_RENDER_LINES)
    val cut = minOf(MAX_TOOL_RENDER_CHARS, lineCut)
    if (value.length <= cut) return value

    val omitted = value.length - cut
    val count = String.format(Locale.US, "%,d", omitted)
    return value.take(cut) + "\n\n… $count more characters truncated — use Copy for the full output."
}

/** Index *of* the [count]th newline, or the whole length if there are fewer. */
private fun String.nthNewlineIndex(count: Int): Int {
    var seen = 0
    var i = 0
    while (i < length) {
        if (this[i] == '\n') {
            seen += 1
            if (seen == count) return i
        }
        i += 1
    }
    return length
}

private val ToolJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Project this activity into what the row paints. `buildToolView`, index.ts:1409. */
internal fun ToolActivity.toolView(): ToolView {
    val args = argsText.asJsonObject()
    val result = resultText.asJsonObject()
    val name = toolName.lowercase(Locale.US)
    val meta = toolMeta(name, inlineDiff != null)

    val error = toolErrorText(name, result)
    val status = toolStatus(name, error)

    // index.ts:1463-1473 — for shell/code tools the two streams are surfaced
    // separately, and stderr is deliberately not painted destructively.
    val rendersAnsi = name.rendersAnsi()
    val stdout = if (rendersAnsi) result.firstStringField("stdout") else ""
    val stderr = if (rendersAnsi) result.firstStringField("stderr") else ""
    val splitStreams = rendersAnsi && (stdout.isNotEmpty() || stderr.isNotEmpty())

    val terminal = name.isTerminal()
    val terminalCommand = if (terminal) args.shellCommand().ifEmpty { null } else null
    val terminalExitCode = if (terminal) result.numericField("exit_code") else null

    val hits = if (name.isWebSearch() && status != ToolStatus.Error) {
        result.searchResults(fallback = resultText)
    } else {
        emptyList()
    }
    val query = if (name.isWebSearch()) {
        args.firstStringField("search_term", "query").ifEmpty { args.contextValue() }
    } else {
        ""
    }

    val body = toolDetailText(name, args, result, splitStreams)
    // index.ts:1446-1451 — an error message leads the detail, and a body that
    // merely repeats it is not printed twice.
    val detail = if (error.isEmpty()) {
        body
    } else {
        listOf(error, body).filter { it.isNotEmpty() }.distinctBy { it.trim() }.joinToString("\n\n")
    }

    val paintedStdout = if (splitStreams) stdout.ifEmpty { null } else null
    val paintedStderr = if (splitStreams) stderr.ifEmpty { null } else null
    val searchQuery = query.ifEmpty { null }

    return ToolView(
        tone = meta.tone,
        icon = meta.icon,
        status = status,
        countLabel = resultCount(name, result, resultText, detail, status),
        durationLabel = durationLabel(),
        detail = detail,
        detailLabel = if (error.isNotEmpty()) "Error details" else detailLabel(name),
        inlineDiff = inlineDiff,
        rendersAnsi = rendersAnsi,
        searchQuery = searchQuery,
        searchHits = hits,
        stdout = paintedStdout,
        stderr = paintedStderr,
        terminalCommand = terminalCommand,
        terminalExitCode = terminalExitCode,
        // Built from the same locals rather than from the finished view: the
        // text Copy hands over is the payload *before* `clampForDisplay`.
        copy = copyAction(name, args, inlineDiff, detail, paintedStdout, paintedStderr, hits, searchQuery),
    )
}

// ── Tone, icon and status ────────────────────────────────────────────────────

private class ToolMeta(val tone: ToolTone, val icon: ToolIconName?)

/**
 * `index.ts:142-214` (`TOOL_META`) and `:233-236` (`PREFIX_META`).
 *
 * The exact table first, then Desktop's `browser_` / `web_` prefix rule, then
 * the substring heuristic Android already shipped — which stays because this
 * client sees tool names the Desktop table does not enumerate, and an
 * unrecognised tool losing its glyph would be a visible regression.
 */
private fun toolMeta(name: String, hasInlineDiff: Boolean): ToolMeta {
    ExactToolMeta[name]?.let { return it }

    if (name.startsWith("browser_")) return ToolMeta(ToolTone.Browser, ToolIconName.Globe)
    if (name.startsWith("web_")) return ToolMeta(ToolTone.Web, ToolIconName.Globe)

    return when {
        hasInlineDiff || name.contains("patch") || name.contains("edit") || name.contains("write_file") ->
            ToolMeta(ToolTone.File, ToolIconName.Edit)
        name.isTerminal() || name.isCodeExecution() || name.contains("command") ->
            ToolMeta(ToolTone.Terminal, ToolIconName.Terminal)
        name.contains("search") -> ToolMeta(ToolTone.Web, ToolIconName.Search)
        name.contains("browser") -> ToolMeta(ToolTone.Browser, ToolIconName.Globe)
        name.contains("web") -> ToolMeta(ToolTone.Web, ToolIconName.Globe)
        name.contains("read") || name.contains("file") -> ToolMeta(ToolTone.File, ToolIconName.File)
        name.contains("memory") -> ToolMeta(ToolTone.Agent, ToolIconName.Brain)
        else -> ToolMeta(ToolTone.Default, null)
    }
}

private val ExactToolMeta: Map<String, ToolMeta> = mapOf(
    "browser_click" to ToolMeta(ToolTone.Browser, ToolIconName.Globe),
    "browser_fill" to ToolMeta(ToolTone.Browser, ToolIconName.Globe),
    "browser_navigate" to ToolMeta(ToolTone.Browser, ToolIconName.Globe),
    "browser_snapshot" to ToolMeta(ToolTone.Browser, ToolIconName.Globe),
    "browser_take_screenshot" to ToolMeta(ToolTone.Browser, ToolIconName.FileMedia),
    "browser_type" to ToolMeta(ToolTone.Browser, ToolIconName.Globe),
    "clarify" to ToolMeta(ToolTone.Agent, ToolIconName.Question),
    "cronjob" to ToolMeta(ToolTone.Agent, ToolIconName.Watch),
    "edit_file" to ToolMeta(ToolTone.File, ToolIconName.Edit),
    "execute_code" to ToolMeta(ToolTone.Terminal, ToolIconName.Terminal),
    "image_generate" to ToolMeta(ToolTone.Image, ToolIconName.FileMedia),
    "list_files" to ToolMeta(ToolTone.File, ToolIconName.Files),
    "memory" to ToolMeta(ToolTone.Agent, ToolIconName.Brain),
    "patch" to ToolMeta(ToolTone.File, ToolIconName.Edit),
    "read_file" to ToolMeta(ToolTone.File, ToolIconName.File),
    "search_files" to ToolMeta(ToolTone.File, ToolIconName.Search),
    "session_search_recall" to ToolMeta(ToolTone.Agent, ToolIconName.Search),
    "terminal" to ToolMeta(ToolTone.Terminal, ToolIconName.Terminal),
    "todo" to ToolMeta(ToolTone.Agent, ToolIconName.Tools),
    "vision_analyze" to ToolMeta(ToolTone.Image, ToolIconName.Eye),
    "web_extract" to ToolMeta(ToolTone.Web, ToolIconName.Globe),
    "web_search" to ToolMeta(ToolTone.Web, ToolIconName.Search),
    "write_file" to ToolMeta(ToolTone.File, ToolIconName.Edit),
)

/**
 * `index.ts:707-727`.
 *
 * Android reads the run's outcome from [ToolState] — the backend already told
 * this client whether the call failed — and keeps only the part of Desktop's
 * heuristic that the state cannot express: a rejected memory write is a budget
 * negotiation, not a failure, so it stays amber rather than destructive-red
 * beside routine bookkeeping.
 */
private fun ToolActivity.toolStatus(name: String, error: String): ToolStatus = when (state) {
    ToolState.Running -> ToolStatus.Running
    ToolState.Stopped -> ToolStatus.Stopped
    ToolState.Failed -> if (name.contains("memory")) ToolStatus.Warning else ToolStatus.Error
    ToolState.Done -> if (error.isEmpty()) ToolStatus.Success else ToolStatus.Error
}

/**
 * `index.ts:666-705`, minus the arms `ToolState` already answers.
 *
 * The exit-code rule is the load-bearing part and is kept verbatim: a non-zero
 * exit alone is a weak failure signal — grep returns 1 on no match, diff
 * returns 1 on differences — so it only reads as an error when the command
 * produced no output to show.
 */
private fun ToolActivity.toolErrorText(name: String, result: JsonObject): String {
    if (state == ToolState.Running || state == ToolState.Stopped) return ""

    result.firstStringField("error").takeIf { it.isNotEmpty() }?.let { return it }

    if (result.booleanField("success") == false || result.booleanField("ok") == false) {
        return result.firstStringField("message", "reason", "detail")
            .ifEmpty { "Tool returned success=false." }
    }

    val statusText = result.firstStringField("status")
    if (statusText.isNotEmpty() && FailureWord.containsMatchIn(statusText)) {
        return result.firstStringField("message", "reason", "detail")
            .ifEmpty { "Tool returned status \"$statusText\"." }
    }

    val exit = result.numericField("exit_code")
    if (exit != null && exit != 0) {
        val hasOutput = result.firstStringField("output", "stdout", "stderr", "output_preview").isNotEmpty()
        return if (hasOutput) "" else "Command failed with exit code $exit."
    }

    return if (state == ToolState.Failed && !name.contains("memory")) "Tool returned an error." else ""
}

private val FailureWord = Regex("""\b(error|failed|failure)\b""", RegexOption.IGNORE_CASE)

// ── Detail, streams and labels ───────────────────────────────────────────────

/**
 * `index.ts:1467` — the two tools whose output naturally carries escape codes.
 */
private fun String.rendersAnsi(): Boolean = isTerminal() || isCodeExecution()

/**
 * `index.ts:1474-1475` — the `$` prompt line and the exit code are
 * terminal-only. `execute_code` shares the ANSI and stream rules but has no
 * shell transcript, so it must not grow one here.
 */
private fun String.isTerminal(): Boolean = this == "terminal" || contains("terminal")

private fun String.isCodeExecution(): Boolean = contains("exec") || contains("shell")

private fun String.isWebSearch(): Boolean = this == "web_search" || (contains("web") && contains("search"))

/** `index.ts:1066-1076` — only two tools name their detail block. */
private fun detailLabel(name: String): String = when {
    name.isWebSearch() -> "Details"
    name == "browser_snapshot" -> "Snapshot summary"
    else -> ""
}

/**
 * `index.ts:1078-1110`.
 *
 * The merged body is the fallback for when the backend did not expose the
 * streams individually; once it did, the renderer shows them separately and
 * this must not double-print them. A terminal row with no output already shows
 * its command on the `$` prompt line, so it prints nothing rather than
 * repeating the command as a detail.
 */
private fun ToolActivity.toolDetailText(name: String, args: JsonObject, result: JsonObject, splitStreams: Boolean): String {
    if (splitStreams) return ""

    if (name.rendersAnsi()) {
        val output = result.firstStringField("output", "stdout", "stderr")
        val lines = (result["lines"] as? JsonArray)
            ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
            ?.joinToString("\n")
            .orEmpty()

        val merged = listOf(output, lines).filter { it.isNotEmpty() }.joinToString("\n")
        if (merged.isNotEmpty()) return merged

        // A backend that hands back a bare string rather than an object still
        // produced output, and printing it is the whole point of the row.
        // Desktop never sees this shape because its result is already decoded.
        resultText?.takeIf { it.isNotBlank() && !it.looksLikeJson() }?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }

        // index.ts:1104-1109 — a terminal row with no output already shows its
        // command on the `$` prompt line; the generic fallback would print the
        // same string a second time.
        if (name.isTerminal()) return ""
    }

    // index.ts:1135-1147.
    if (name.contains("read_file")) {
        result.firstStringField("content", "text", "data", "body").takeIf { it.isNotEmpty() }?.let { return it }
    }
    if (name.contains("memory")) return result.firstStringField("message", "error")

    // index.ts:1149-1165 — a file edit that produced a diff says it in the diff.
    if (inlineDiff != null) return result.firstStringField("message", "summary")

    return fallbackDetailText(args, result)
}

/**
 * `index.ts:847-864`.
 *
 * The last resort for a tool this client has no shape for. Android's own
 * `ToolActivity.detail` — the gateway's short human summary — takes the place
 * of Desktop's `formatToolResultSummary`, and the raw result text stands in
 * only when it is not JSON, so an unrecognised tool still shows something
 * rather than an empty expanded row.
 */
private fun ToolActivity.fallbackDetailText(args: JsonObject, result: JsonObject): String {
    val argContext = args.contextValue()
    val resultContext = result.contextValue()

    if (resultContext.isNotEmpty() && resultContext != argContext) return resultContext
    if (argContext.isNotEmpty()) return argContext
    if (detail.isNotBlank()) return detail

    return resultText?.takeIf { it.isNotBlank() && !it.looksLikeJson() }?.trim().orEmpty()
}

/**
 * `index.ts:729-737` reads `duration_s` off the result; the gateway already
 * lands that value on [ToolActivity.elapsedSeconds]
 * (`GatewaySessionRepository.kt:2319-2322`), so this reads it from there rather
 * than parsing the same number twice.
 */
private fun ToolActivity.durationLabel(): String? {
    if (state == ToolState.Running || elapsedSeconds <= 0.0) return null
    return elapsedSeconds.durationLabel()
}

/** Match Desktop's whole-second timer and m:ss format. */
internal fun Double.elapsedLabel(): String {
    val whole = coerceAtLeast(0.0).toLong()
    return if (whole < 60L) {
        "${whole}s"
    } else {
        "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
    }
}

internal fun Double.durationLabel(): String =
    if (this in 0.0..<1.0) "${(this * 1_000).roundToInt()}ms" else elapsedLabel()

// ── Count label ──────────────────────────────────────────────────────────────

private val CountFieldNouns: List<Pair<String, String>> = listOf(
    "count" to "",
    "total" to "",
    "result_count" to "result",
    "results_count" to "result",
    "num_results" to "result",
    "match_count" to "match",
    "matches_count" to "match",
    "file_count" to "file",
    "files_count" to "file",
    "item_count" to "item",
    "items_count" to "item",
    "search_count" to "search",
    "searches_count" to "search",
    "source_count" to "source",
    "sources_count" to "source",
    "document_count" to "document",
    "documents_count" to "document",
    "updated" to "item",
    "added" to "item",
    "removed" to "item",
    "deleted" to "item",
    "created" to "item",
    "changed" to "item",
    "processed" to "item",
    "steps" to "step",
)

private val CountArrayNouns: List<Pair<String, String>> = listOf(
    "results" to "result",
    "items" to "item",
    "matches" to "match",
    "files" to "file",
    "documents" to "document",
    "sources" to "source",
    "rows" to "row",
)

private val CountExcludedKeys = setOf("duration_s", "exit_code", "status_code")

private val DefaultCountNounByTool = mapOf(
    "browser_snapshot" to "item",
    "list_files" to "file",
    "search_files" to "result",
    "session_search_recall" to "result",
    "todo" to "todo",
    "web_search" to "result",
)

/** `index.ts:500-551`. */
private fun resultCount(
    name: String,
    result: JsonObject,
    resultText: String?,
    detail: String,
    status: ToolStatus,
): String? {
    if (status == ToolStatus.Running || status == ToolStatus.Error) return null

    val fallbackNoun = DefaultCountNounByTool[name] ?: "item"

    if (name.isWebSearch()) {
        val hits = result.searchResults(fallback = resultText)
        if (hits.isNotEmpty()) return countLabel(hits.size, "result")
    }

    // index.ts:519-527 — memory puts the live total on `entry_count`, and the
    // noun stays entry/entries instead of falling through the generic path.
    if (name.contains("memory")) {
        result.countOf("entry_count")?.let { return countLabel(it, "entry") }
    }

    result.countFromRecord(fallbackNoun)?.let { return normalizeForTool(name, it) }

    val payload = result.unwrapToolPayload()
    if (payload !== result) {
        payload.countFromRecord(fallbackNoun)?.let { return normalizeForTool(name, it) }
    }

    val summary = result.firstStringField("summary", "message", "detail").ifEmpty { detail }
    return countFromText(summary, fallbackNoun)?.let { normalizeForTool(name, it) }
}

private class CountMetric(val count: Int, val noun: String)

private fun normalizeForTool(name: String, metric: CountMetric): String =
    if (name.isWebSearch()) countLabel(metric.count, "result") else countLabel(metric.count, metric.noun)

private fun countLabel(count: Int, noun: String): String {
    val singular = noun.singularizeNoun().ifEmpty { "item" }
    return "$count ${singular.pluralizeNoun(count)}"
}

/** `index.ts:441-477`. */
private fun JsonObject.countFromRecord(fallbackNoun: String): CountMetric? {
    for ((key, noun) in CountFieldNouns) {
        countOf(key)?.let { return CountMetric(it, noun.ifEmpty { fallbackNoun }) }
    }
    for ((key, noun) in CountArrayNouns) {
        countOf(key)?.let { return CountMetric(it, noun) }
    }
    for ((key, _) in entries) {
        if (key in CountExcludedKeys) continue
        val lower = key.lowercase(Locale.US)
        if (!lower.endsWith("_count") && !lower.endsWith("_total")) continue
        countOf(key)?.let { return CountMetric(it, dynamicCountNoun(lower, fallbackNoun)) }
    }
    return null
}

/** `index.ts:429-439`. */
private fun dynamicCountNoun(key: String, fallbackNoun: String): String {
    if (key == "count" || key == "total") return fallbackNoun
    val stripped = key.removeSuffix("_count").removeSuffix("_total").removePrefix("num_")
    return stripped.singularizeNoun().ifEmpty { fallbackNoun }
}

/** `index.ts:353-365` — arrays count by length, numbers round, zero is nothing. */
private fun JsonObject.countOf(key: String): Int? {
    val value = this[key] ?: return null
    if (value is JsonArray) return value.size.takeIf { it > 0 }
    val n = (value as? JsonPrimitive)?.doubleOrNull ?: return null
    if (!n.isFinite() || n <= 0.0) return null
    return n.roundToInt()
}

private val CountWithUnit = Regex(
    """\b(\d+)\s+(results?|items?|files?|matches?|documents?|sources?|searches?|steps?|rows?)\b""",
    RegexOption.IGNORE_CASE,
)

private val CountAfterVerb = Regex(
    """\b(?:did|found|returned|listed|searched|matched|updated|created|deleted|processed)\s+(\d+)\b""",
    RegexOption.IGNORE_CASE,
)

/** `index.ts:479-498`. */
private fun countFromText(value: String, fallbackNoun: String): CountMetric? {
    val text = value.trim()
    if (text.isEmpty()) return null

    CountWithUnit.find(text)?.let { match ->
        val n = match.groupValues[1].toIntOrNull() ?: return null
        return if (n > 0) CountMetric(n, match.groupValues[2].singularizeNoun()) else null
    }
    CountAfterVerb.find(text)?.let { match ->
        val n = match.groupValues[1].toIntOrNull() ?: return null
        return if (n > 0) CountMetric(n, fallbackNoun) else null
    }
    return null
}

private val PluralSuffix = Regex("""(xes|zes|ches|shes|sses)$""", RegexOption.IGNORE_CASE)
private val ConsonantY = Regex("""[^aeiou]y$""", RegexOption.IGNORE_CASE)
private val SibilantEnd = Regex("""(s|x|z|ch|sh)$""", RegexOption.IGNORE_CASE)

/** `index.ts:367-387`. */
private fun String.singularizeNoun(): String {
    val normalized = trim().lowercase(Locale.US)
    if (normalized.isEmpty()) return ""
    if (normalized.endsWith("ies") && normalized.length > 3) return normalized.dropLast(3) + "y"
    if (PluralSuffix.containsMatchIn(normalized) && normalized.length > 3) return normalized.dropLast(2)
    if (normalized.endsWith("s") && normalized.length > 2 && !normalized.endsWith("ss")) return normalized.dropLast(1)
    return normalized
}

/** `index.ts:389-407`. */
private fun String.pluralizeNoun(count: Int): String {
    if (count == 1) return this
    if (this == "search") return "searches"
    if (ConsonantY.containsMatchIn(this)) return dropLast(1) + "ies"
    if (SibilantEnd.containsMatchIn(this)) return this + "es"
    return this + "s"
}

// ── Web-search hits ──────────────────────────────────────────────────────────

private val ResultListKeys = listOf(
    "web",
    "results",
    "search_results",
    "sources",
    "web_sources",
    "items",
    "organic_results",
    "organic",
    "matches",
    "documents",
)

/** How deep [collectResultItems] will chase a nested payload. Untrusted JSON. */
private const val MAX_RESULT_DEPTH = 6

/** `index.ts:610-647`. */
private fun JsonElement?.collectResultItems(depth: Int = 0): List<JsonElement> {
    if (this == null || depth > MAX_RESULT_DEPTH) return emptyList()
    if (this is JsonArray) return this

    val record = this as? JsonObject ?: return emptyList()
    for (key in ResultListKeys) {
        when (val candidate = record[key]) {
            is JsonArray -> return candidate
            is JsonObject -> {
                val nested = candidate.collectResultItems(depth + 1)
                if (nested.isNotEmpty()) return nested
            }
            else -> Unit
        }
    }

    val payload = record.unwrapToolPayload()
    return if (payload === record) emptyList() else payload.collectResultItems(depth + 1)
}

/** `index.ts:649-664` — the same six-hit cap and the same field aliases. */
private fun JsonObject.searchResults(fallback: String?, limit: Int = 6): List<SearchResultRow> {
    val list = collectResultItems().ifEmpty {
        // A result that is a bare JSON array rather than an object never
        // survives `asJsonObject`, so retry from the raw text before giving up.
        fallback?.let { text -> runCatching { ToolJson.parseToJsonElement(text) }.getOrNull() }
            .collectResultItems()
    }

    return list.asSequence()
        .map { item -> item.asRecord() }
        .map { row ->
            SearchResultRow(
                title = row.firstStringField("title", "name"),
                url = row.firstStringField("url", "href", "link"),
                snippet = row.firstStringField("snippet", "description", "body"),
            )
        }
        .filter { it.title.isNotEmpty() || it.url.isNotEmpty() }
        .take(limit)
        .toList()
}

// ── Copy ─────────────────────────────────────────────────────────────────────

/**
 * `index.ts:1188-1281`.
 *
 * The text is always the *unclamped* payload: the display is truncated to keep
 * a chatty build log from flooding the transcript, and Copy is the affordance
 * that gives the whole thing back.
 */
private fun copyAction(
    name: String,
    args: JsonObject,
    inlineDiff: String?,
    detail: String,
    stdout: String?,
    stderr: String?,
    searchHits: List<SearchResultRow>,
    searchQuery: String?,
): ToolCopyAction? {
    val streams = listOfNotNull(stdout, stderr).joinToString("\n")
    val output = streams.ifEmpty { detail }.trim()
    val substantial = output.length > 16

    if (name.rendersAnsi()) {
        if (substantial) return copyOutput(output)
        args.shellCommand().takeIf { it.isNotEmpty() }?.let { return copyCommand(it) }
    }

    if (name.isWebSearch()) {
        if (searchHits.isNotEmpty()) {
            return copyResults(
                searchHits.joinToString("\n\n") { hit ->
                    listOf(hit.title, hit.url, hit.snippet).filter { it.isNotEmpty() }.joinToString("\n")
                },
            )
        }
        searchQuery?.let { return copyQuery(it) }
    }

    inlineDiff?.takeIf { it.isNotBlank() }?.let { return copyFile(it) }

    if (name.contains("read_file")) {
        if (substantial) return copyFile(output)
        args.firstStringField("path", "file", "filepath").takeIf { it.isNotEmpty() }?.let { return copyPath(it) }
    }

    return if (output.isNotEmpty()) copyOutput(output) else null
}

// ── Tolerant JSON reads ──────────────────────────────────────────────────────

private fun String?.asJsonObject(): JsonObject {
    val text = this?.takeIf { it.isNotBlank() } ?: return JsonObject(emptyMap())
    return runCatching { ToolJson.parseToJsonElement(text).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}

private fun JsonElement.asRecord(): JsonObject = when (this) {
    is JsonObject -> this
    is JsonPrimitive -> if (isString) content.asJsonObject() else JsonObject(emptyMap())
    else -> JsonObject(emptyMap())
}

/** `index.ts:598-608` — the first key that holds a non-blank string. */
private fun JsonObject.firstStringField(vararg keys: String): String {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        if (!value.isString) continue
        val text = value.content.trim()
        if (text.isNotEmpty()) return text
    }
    return ""
}

private fun JsonObject.booleanField(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** `index.ts:74-78` — a numeric field, or nothing. A string "0" is not a number. */
private fun JsonObject.numericField(key: String): Int? {
    val value = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    val n = value.doubleOrNull ?: return null
    return if (n.isFinite()) n.roundToInt() else null
}

/** `format.ts:85-97`. */
private fun JsonObject.unwrapToolPayload(): JsonObject {
    for (key in listOf("data", "result", "output", "response", "payload")) {
        (this[key] as? JsonObject)?.let { return it }
    }
    return this
}

/** `format.ts:31-43`. */
private fun JsonObject.contextValue(): String = firstStringField("context", "preview")

/** `index.ts:136-140`. */
private fun JsonObject.shellCommand(): String =
    firstStringField("command", "code").ifEmpty { contextValue() }

private fun String.looksLikeJson(): Boolean {
    val trimmed = trim()
    return trimmed.startsWith("{") || trimmed.startsWith("[")
}
