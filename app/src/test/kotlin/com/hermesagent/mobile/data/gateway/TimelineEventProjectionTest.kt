package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.TimelineEvent
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.preservingRowIdOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed delegation-completion projection.
 *
 * `[ASYNC DELEGATION BATCH COMPLETE …]` is addressed to the model, not to the
 * person, and the Gateway persists it as a `role=user` row. Painting it in a
 * user bubble shows internal control text as if it were the reader's own turn.
 * Desktop reads the row's typed `display_kind`/`display_metadata` and projects a
 * system timeline row instead (`apps/desktop/src/lib/chat-messages/
 * hydration.ts:191-230,317-324` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`); [parseHistory] is where this app
 * does the same, and [classifyTimelineEvent] is the classification it uses.
 *
 * The one safety boundary these tests exist for: **typed metadata is the only
 * thing that licences classification.** Genuine user text can quote the marker,
 * so a prefix test would hide a real turn. The untyped case is pinned by
 * [untyped text that quotes the envelope marker stays a user turn].
 */
class TimelineEventProjectionTest {

    /**
     * One stored row, built as JSON rather than concatenated, so a body
     * containing quotes or newlines cannot silently produce a different row
     * than the one under test.
     */
    private class Row(
        val text: String,
        val rowId: Int? = 7,
        val displayKind: String? = null,
        val displayMetadata: JsonElement? = null,
        val role: String = "user",
    ) {
        fun toJson(): JsonObject = buildJsonObject {
            rowId?.let { put("row_id", JsonPrimitive(it)) }
            put("role", JsonPrimitive(role))
            put("text", JsonPrimitive(text))
            displayKind?.let { put("display_kind", JsonPrimitive(it)) }
            displayMetadata?.let { put("display_metadata", it) }
        }
    }

    /** `display_metadata` as the JSON text an older Gateway ships. */
    private fun metadataText(json: String): JsonPrimitive = JsonPrimitive(json)

    private fun metadata(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun history(vararg rows: Row): List<TranscriptEntry> {
        val wire = buildJsonObject {
            put("messages", buildJsonArray { rows.forEach { add(it.toJson()) } })
            put("count", JsonPrimitive(rows.size))
        }
        return parseHistory(wire, "runtime-a", 0L)
    }

    // ── The bug this closes ──────────────────────────────────────────────────

    private val batchEnvelope = """
        [ASYNC DELEGATION BATCH COMPLETE — 1/1 tasks finished]

        --- ✓ TASK 1/1: audit the parser  (status=completed) ---
        The parser drops the metadata field.
    """.trimIndent()

    @Test
    fun `a typed delegation completion becomes one timeline row and never a user bubble`() {
        val entries = history(
            Row(text = "carry on"),
            Row(
                text = batchEnvelope,
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":1}"""),
            ),
        )

        assertEquals(2, entries.size)
        assertEquals(listOf(UserTurn::class, TimelineEvent::class), entries.map { it::class })
        assertEquals(
            "the model-facing envelope must not be the user's bubble",
            "carry on",
            (entries.first() as UserTurn).text,
        )

        val timeline = entries.last() as TimelineEvent
        assertEquals("1 background agent finished", timeline.label)
        assertEquals("The parser drops the metadata field.", timeline.report)
        assertEquals(TranscriptRowId(7), timeline.rowId)
    }

    @Test
    fun `untyped text that quotes the envelope marker stays a user turn`() {
        val quoted = "[ASYNC DELEGATION BATCH COMPLETE — the model says this sometimes]\n\n" +
            "--- RESULT ---\nand I want to ask about it"

        val entries = history(Row(text = quoted))

        val user = entries.single() as UserTurn
        assertEquals("a marker-quoting message is still the user's own words", quoted, user.text)
    }

    @Test
    fun `a kind this app renders no row for keeps its stored role and body`() {
        val entries = history(Row(text = "Model changed", displayKind = "skill_invocation"))

        assertEquals("Model changed", (entries.single() as UserTurn).text)
    }

    @Test
    fun `a hidden row mints no timeline row`() {
        // `hidden` is dropped by the projections before it reaches this parser
        // (`RestTranscriptProjection.kt`, `session_history.py:191-192`), and it
        // is deliberately not one of the rendered kinds either.
        val entries = history(Row(text = "reference payload", displayKind = "hidden"))

        assertTrue(entries.none { it is TimelineEvent })
    }

    // ── Labels ───────────────────────────────────────────────────────────────

    @Test
    fun `every rendered kind has fixed copy, and only a completion kind reads display_text`() {
        val cases = listOf(
            "model_switch" to "model changed",
            "auto_continue" to "resumed interrupted turn",
            "personality_switch" to "personality changed",
            "async_delegation_complete" to "background agent work finished",
            "process_complete" to "background process finished",
        )

        for ((kind, label) in cases) {
            assertEquals(label, (history(Row(text = "x", displayKind = kind)).single() as TimelineEvent).label)
        }

        // A pivot kind ignores `display_text`: upstream's copy for those is fixed.
        val pivot = history(
            Row(
                text = "x",
                displayKind = "model_switch",
                displayMetadata = metadata("""{"display_text":"should be ignored"}"""),
            ),
        )
        assertEquals("model changed", (pivot.single() as TimelineEvent).label)

        val withText = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"display_text":"Subagent Task Completed: audit"}"""),
            ),
        )
        assertEquals("Subagent Task Completed: audit", (withText.single() as TimelineEvent).label)
    }

    @Test
    fun `the task-count fallback reads a number and refuses a string that looks like one`() {
        val numeric = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":3}"""),
            ),
        )
        assertEquals("3 background agents finished", (numeric.single() as TimelineEvent).label)

        val stringly = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":"3"}"""),
            ),
        )
        assertEquals("background agent work finished", (stringly.single() as TimelineEvent).label)

        val one = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":1}"""),
            ),
        )
        assertEquals("1 background agent finished", (one.single() as TimelineEvent).label)
    }

    // ── Metadata fallbacks ───────────────────────────────────────────────────

    @Test
    fun `display_metadata parses as an object or as the JSON text an older gateway ships`() {
        val asObject = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":2}"""),
            ),
        )
        assertEquals("2 background agents finished", (asObject.single() as TimelineEvent).label)

        val asText = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadataText("""{"task_count":2}"""),
            ),
        )
        assertEquals("2 background agents finished", (asText.single() as TimelineEvent).label)
    }

    @Test
    fun `a malformed or unrecognised metadata payload falls back rather than failing the resume`() {
        val malformed = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadataText("not json"),
            ),
        )
        assertEquals("background agent work finished", (malformed.single() as TimelineEvent).label)

        val array = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = Json.parseToJsonElement("[1,2]"),
            ),
        )
        assertEquals("background agent work finished", (array.single() as TimelineEvent).label)

        val absent = history(Row(text = "envelope", displayKind = "async_delegation_complete"))
        assertEquals("background agent work finished", (absent.single() as TimelineEvent).label)
    }

    // ── Report extraction ────────────────────────────────────────────────────

    @Test
    fun `the report is the producer-owned body with every plumbing wall removed`() {
        val batch = """
            [ASYNC DELEGATION BATCH COMPLETE — 2/2 tasks finished]

            The user asked me to fix the parser and I must keep going.
            --- ✗ TASK 1/2: fix the parser  (status=failed) ---
            It threw on an empty diff.
            --- ✓ TASK 2/2: add the test  (status=completed) ---
            Test added.

            Full live transcript (complete tool/assistant trace): /tmp/x.log
        """.trimIndent()

        val report = (history(
            Row(
                text = batch,
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":2}"""),
            ),
        ).single() as TimelineEvent).report

        assertEquals("It threw on an empty diff.\n\nTest added.", report)
        assertTrue("no instruction plumbing may leak", report?.contains("keep going") != true)
        assertTrue("no transcript footer may leak", report?.contains("/tmp/x.log") != true)
    }

    @Test
    fun `a single task result keeps its body`() {
        val single = """
            [ASYNC DELEGATION RESULT — audit]

            --- RESULT ---
            The audit is clean.
        """.trimIndent()

        assertEquals(
            "The audit is clean.",
            (history(
                Row(
                    text = single,
                    displayKind = "async_delegation_complete",
                    displayMetadata = metadata("""{"task_count":1}"""),
                ),
            ).single() as TimelineEvent).report,
        )
    }

    @Test
    fun `a process batch keeps one block per process and drops the count banner`() {
        val processes = """
            [IMPORTANT: 2 background processes completed.]

            [IMPORTANT: Background process 1 exited with code 0
            Output: built ok]

            [IMPORTANT: Background process 2 exited with code 1
            Output: tests failed]
        """.trimIndent()

        assertEquals(
            "Background process 1 exited with code 0\nOutput: built ok\n\n" +
                "Background process 2 exited with code 1\nOutput: tests failed",
            (history(
                Row(
                    text = processes,
                    displayKind = "process_complete",
                    displayMetadata = metadata("""{"task_count":2}"""),
                ),
            ).single() as TimelineEvent).report,
        )
    }

    @Test
    fun `an envelope with no producer-owned boundary yields no report rather than raw instructions`() {
        val preambleOnly = "[ASYNC DELEGATION BATCH COMPLETE — instructions follow]\n\n" +
            "Tell the user nothing until every task is done."

        val report = (history(
            Row(
                text = preambleOnly,
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":1}"""),
            ),
        ).single() as TimelineEvent).report

        assertNull("the model-facing preamble is not a report", report)
    }

    @Test
    fun `a pivot kind carries no report at all`() {
        val entries = history(
            Row(text = "--- RESULT ---\nlooks like a completion", displayKind = "model_switch"),
        )
        assertNull((entries.single() as TimelineEvent).report)
    }

    // ── Durable identity ─────────────────────────────────────────────────────

    @Test
    fun `the durable row id survives a re-projection and a null stamp never erases one`() {
        val stamped = history(
            Row(
                text = "envelope",
                displayKind = "async_delegation_complete",
                displayMetadata = metadata("""{"task_count":1}"""),
            ),
        ).single()
        assertEquals(TranscriptRowId(7), stamped.rowId)

        // A later projection of the same row that arrived unstamped keeps the
        // address the backend already gave us — a null row id means "not written
        // down yet", never "this row has no durable identity".
        val unstamped = TimelineEvent("other", "background agent work finished", "body", 0L)
        assertEquals(TranscriptRowId(7), unstamped.preservingRowIdOf(stamped).rowId)
    }

    @Test
    fun `a row with no durable id stays unstamped rather than minting one`() {
        val entries = history(Row(text = "envelope", rowId = null, displayKind = "async_delegation_complete"))
        assertNull("no address may be invented from the local rendering key", entries.single().rowId)
    }
}
