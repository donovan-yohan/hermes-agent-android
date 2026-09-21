package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.TranscriptRowId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A typed Gateway timeline event — delegation completion, model switch,
 * auto-continue, personality switch — projected into what one compact
 * disclosure row paints.
 *
 * Desktop classifies these by the **typed** `display_kind`/`display_metadata`
 * the Gateway stamps on the row (`apps/desktop/src/lib/chat-messages/
 * hydration.ts:191-225` and `:317-324` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`), never by sniffing the text: a
 * genuine user turn may quote an envelope marker, and a prefix test would hide
 * it. [classifyTimelineEvent] keeps that boundary exactly.
 *
 * @param label the row's title, already resolved (metadata `display_text`
 *   first, then the kind's own fallback — `hydration.ts:203-225`).
 * @param report the producer-owned result body for a completion event, or null
 *   when the kind carries none. Extractable only *after* [kind] licensed the
 *   classification, and never for a row that was not typed.
 */
internal data class TimelineProjection(
    val kind: TimelineKind,
    val label: String,
    val report: String?,
)

/**
 * The `display_kind` values this app renders as a timeline row.
 *
 * Desktop's system-role set is these five plus `hidden`, which is not a
 * timeline row but a drop (`hydration.ts:317-324` @ the same SHA). A kind
 * outside this set is left alone: the row keeps the role and body it was
 * stored with, which is the deliberate non-regression
 * `docs/parity/transcript-backfill.md` records.
 */
internal enum class TimelineKind(val wireName: String, val fallbackLabel: String) {
    ModelSwitch("model_switch", "model changed"),
    AutoContinue("auto_continue", "resumed interrupted turn"),
    PersonalitySwitch("personality_switch", "personality changed"),
    AsyncDelegationComplete("async_delegation_complete", "background agent work finished"),
    ProcessComplete("process_complete", "background process finished"),
    ;

    /**
     * Whether the kind names a *completed* producer unit whose own result body
     * can be extracted from the stored content
     * (`hydration.ts:432-433` takes `asyncResult` for exactly these two).
     */
    val carriesReport: Boolean
        get() = this == AsyncDelegationComplete || this == ProcessComplete

    companion object {
        private val BY_WIRE_NAME = entries.associateBy(TimelineKind::wireName)

        fun fromWireName(value: String?): TimelineKind? =
            value?.takeIf(String::isNotBlank)?.let(BY_WIRE_NAME::get)
    }
}

/**
 * Classify one stored row, or return null to leave it as it was.
 *
 * The **only** input that can licence classification is [displayKind]: the
 * row's own typed metadata. [text] is passed in because it is what a completion
 * event's report is extracted *from*, and for nothing else — this function
 * never tests it for a marker.
 *
 * `display_metadata` arrives as an object from a current Gateway and as raw
 * JSON text from one older than the client (`parseDisplayMetadata`,
 * `hydration.ts:126-145` @ the same SHA); a primitive that is neither is
 * refused rather than indexed, which is the shape that used to fail a whole
 * resume upstream.
 */
internal fun classifyTimelineEvent(
    displayKind: String?,
    displayMetadata: JsonElement?,
    text: String,
): TimelineProjection? {
    val kind = TimelineKind.fromWireName(displayKind) ?: return null
    val metadata = parseDisplayMetadata(displayMetadata)
    return TimelineProjection(
        kind = kind,
        label = timelineLabel(kind, metadata),
        report = if (kind.carriesReport) asyncResultBody(text) else null,
    )
}

/**
 * `display_metadata` as an object, whether the wire sent an object or its JSON
 * text. `hydration.ts:126-145` @ `437116f9497c80d242ce034ff7f5d81dc277a337`.
 */
internal fun parseDisplayMetadata(metadata: JsonElement?): JsonObject? {
    val element = when (metadata) {
        null, is JsonNull -> return null
        is JsonObject -> return metadata
        is JsonPrimitive -> {
            val raw = metadata.content
            if (raw.isBlank()) return null
            runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
        }

        else -> return null
    }
    return element as? JsonObject
}

/**
 * The row's label.
 *
 * The three pivot kinds have **fixed** copy upstream — `model changed`,
 * `resumed interrupted turn`, `personality changed` — and never consult
 * `display_text`; only the two completion kinds do, because only they carry a
 * producer-written title, and only `async_delegation_complete` has a second
 * fallback that says how much finished when the metadata holds a `task_count`
 * but no text (`hydration.ts:203-230` @ the same SHA). A Gateway that stamped
 * only the count still titles the row with the count it knows.
 */
private fun timelineLabel(kind: TimelineKind, metadata: JsonObject?): String {
    if (!kind.carriesReport) return kind.fallbackLabel
    metadata?.stringField("display_text")?.takeIf(String::isNotBlank)?.let { return it }
    if (kind != TimelineKind.AsyncDelegationComplete) return kind.fallbackLabel
    // `typeof count === 'number'` upstream, so a string-typed `"7"` is not a
    // count and falls through to the same fallback an absent key takes.
    val count = (metadata?.get("task_count") as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.intOrNull
        ?: return kind.fallbackLabel
    return "$count background agent${if (count == 1) "" else "s"} finished"
}

/**
 * The producer-owned result body, out of the envelope the Gateway stored.
 *
 * A port of `asyncResultBody` (`hydration.ts:167-201` @ the same SHA), and the
 * one place this app reads the stored content's *shape* — after [TimelineKind]
 * has already licensed the classification, which is what keeps it from being a
 * prefix test that can hide a user's own words. What it removes is plumbing:
 * the batch header and its per-task walls, the `[IMPORTANT: …]` process
 * wrappers, a cron job's own output banner, and the trailing
 * `Full live transcript (complete tool/assistant trace): …` footer
 * (`tools/process_registry_notifications.py:221` @ the same SHA).
 *
 * A payload with no producer-owned boundary yields nothing rather than the raw
 * envelope: the model-facing instructions above a batch's first task wall are
 * not a report, and painting them is the bug this projection exists to remove.
 */
internal fun asyncResultBody(content: String): String? {
    var bodies = emptyList<String>()

    when {
        content.startsWith(CRON_JOB_PREFIX) -> bodies = listOf(content)
        content.startsWith(IMPORTANT_PREFIX) ->
            // Background-process completion: one `[IMPORTANT: …]` block per
            // process, with a batch header first.
            bodies = content
                .split(PROCESS_BLOCK_BREAK)
                .map { block -> block.replace(IMPORTANT_BLOCK_HEAD, "").removeSuffix("]") }
                .filterNot(PROCESS_COUNT_HEADER::containsMatchIn)

        content.startsWith(ASYNC_DELEGATION_PREFIX) -> {
            if (content.startsWith(ASYNC_DELEGATION_BATCH_PREFIX)) {
                // Task goals can span lines, so the wall is matched on its own
                // line rather than stopping at the first newline.
                bodies = content.split(TASK_WALL).drop(1)
            } else {
                val result = RESULT_OR_ERROR_WALL.find(content)
                bodies = if (result == null) emptyList() else listOf(content.substring(result.range.last + 1))
            }
        }
    }

    return bodies
        .map { body ->
            val output = if (body.startsWith(CRON_JOB_PREFIX)) JOB_OUTPUT_WALL.find(body) else null
            val result = if (output == null) body else body.substring(output.range.last + 1)
            result.replace(LIVE_TRANSCRIPT_FOOTER, "").trim()
        }
        .filter(String::isNotEmpty)
        .joinToString("\n\n")
        .ifBlank { null }
}

/**
 * A metadata string field. `display_metadata` reaches this app as parsed JSON,
 * so the value is already a [JsonPrimitive]; a number or a bool wearing a
 * string's name is not one.
 */
private fun JsonObject.stringField(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val IMPORTANT_PREFIX = "[IMPORTANT: "
private const val ASYNC_DELEGATION_PREFIX = "[ASYNC DELEGATION"
private const val ASYNC_DELEGATION_BATCH_PREFIX = "[ASYNC DELEGATION BATCH COMPLETE"
private const val CRON_JOB_PREFIX = "Cron job "

/** `hydration.ts:174-178` @ the same SHA. */
private val PROCESS_BLOCK_BREAK = Regex("\\n\\n(?=\\[IMPORTANT: )")
private val IMPORTANT_BLOCK_HEAD = Regex("^\\[IMPORTANT:\\s*")
private val PROCESS_COUNT_HEADER = Regex("^\\d+ background processes completed\\.")

/** `hydration.ts:181-183` @ the same SHA — `/^--- [✓✗⚠] TASK \\d+\\/\\d+… {2}\\(status=…\\) ---$/m`. */
private val TASK_WALL = Regex(
    "^--- [\u2713\u2717\u26A0] TASK \\d+/\\d+(?:: [\\s\\S]*?)? {2}\\(status=[^\\n]*\\) ---\\r?\\n",
    RegexOption.MULTILINE,
)

/** `hydration.ts:185-186` @ the same SHA. */
private val RESULT_OR_ERROR_WALL = Regex("^--- (?:RESULT|ERROR) ---\\r?\\n", RegexOption.MULTILINE)

/** `hydration.ts:188` @ the same SHA. */
private val JOB_OUTPUT_WALL = Regex("^--- JOB OUTPUT ---\\r?\\n", RegexOption.MULTILINE)

/** `hydration.ts:193-195` @ the same SHA. */
private val LIVE_TRANSCRIPT_FOOTER = Regex("\\nFull live transcript \\(complete tool/assistant trace\\): [^\\n]*\\n*$")
