package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TurnTermination
import com.hermesagent.mobile.data.session.mergeOlderTranscriptPage
import com.hermesagent.mobile.data.session.graftRefreshedTailOntoBackfill
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedTurnMetadataTest {
    private fun decode(metadata: JsonElement?, rest: Boolean, kind: String? = null, text: String = "partial", id: Int = 42) =
        decodeRows(listOf(buildJsonObject {
            put("role", "assistant")
            put(if (rest) "id" else "row_id", id)
            put(if (rest) "content" else "text", text)
            metadata?.let { put("display_metadata", it) }
            kind?.let { put("display_kind", it) }
        }), rest)

    private fun decodeRows(rows: List<JsonObject>, rest: Boolean) = parseHistory(
        buildJsonObject { put("messages", JsonArray(if (rest) projectRestTranscriptRows(rows) else rows)) },
        "runtime", 1000L,
    )

    private val failure = Json.parseToJsonElement("""{"error":"password=synthetic-secret","error_surface":{"layer":"provider","code":"unavailable","retryable":false}}""")

    @Test fun `REST and RPC preserve strict interrupted metadata without local stop attribution`() {
        for (rest in listOf(false, true)) {
            val metadata = buildJsonObject { put("interrupted", true) }
            for (wire in listOf(metadata, JsonPrimitive(metadata.toString()))) {
                assertEquals(TurnTermination.InterruptedExternally,
                    decode(wire, rest).filterIsInstance<AssistantTurn>().single().termination)
            }
            for (wire in listOf(null, JsonNull, JsonPrimitive("bad"), JsonArray(emptyList()),
                buildJsonObject { put("interrupted", false) }, buildJsonObject { put("interrupted", "true") })) {
                assertNull(decode(wire, rest).filterIsInstance<AssistantTurn>().single().termination)
            }
        }
    }

    @Test fun `blank failed boundary restores one redacted synthetic error without durable address`() {
        for (rest in listOf(false, true)) for (wire in listOf(failure, JsonPrimitive(failure.toString()))) {
            val error = decode(wire, rest, "failed_turn", "").filterIsInstance<AssistantTurn>().single()
            assertNotNull(error.error)
            assertEquals("unavailable", error.errorDetails?.code)
            assertEquals(false, error.errorDetails?.retryable)
            assertFalse(error.errorDetails!!.details.contains("synthetic-secret"))
            assertNull(error.rowId)
            assertNull(error.termination)
        }
    }

    @Test fun `malformed failure descriptors fail closed and metadata alone never creates an error`() {
        for (rest in listOf(false, true)) {
            for (wire in listOf(JsonPrimitive("{"), JsonNull, JsonArray(emptyList()),
                Json.parseToJsonElement("""{"error":"unsafe","error_surface":{"layer":"invented"}}"""),
                Json.parseToJsonElement("""{"error_surface":"provider"}"""))) {
                assertTrue(decode(wire, rest, "failed_turn", "").isEmpty())
            }
            assertTrue(decode(failure, rest).filterIsInstance<AssistantTurn>().all { it.error == null })
        }
    }

    @Test fun `synthetic error neither advances paging nor changes rewind source address`() {
        val rows = listOf(
            buildJsonObject { put("id", 40); put("role", "user"); put("content", "question") },
            buildJsonObject {
                put("id", 41); put("role", "assistant"); put("content", "")
                put("display_kind", "failed_turn"); put("display_metadata", failure)
            },
        )
        val transcript = decodeRows(rows, true)
        val error = transcript.filterIsInstance<AssistantTurn>().single()
        val plan = planRegenerate(transcript, error.id) as RegeneratePlan.Ready
        assertEquals(40L, plan.sourceRowId?.value)
        assertNull(error.rowId)
        val page = com.hermesagent.mobile.data.session.transcriptPageState(0, 0, 2, rows.size)
        assertEquals(2, page.nextOffset)
        assertTrue(page.possiblyTruncated)
    }

    @Test fun `blank interruption and failed tool boundary survive REST filtering`() {
        val interrupted = buildJsonObject { put("interrupted", true) }
        for (rest in listOf(false, true)) {
            assertEquals(TurnTermination.InterruptedExternally,
                decode(interrupted, rest, text = "").filterIsInstance<AssistantTurn>().single().termination)
        }
        val rows = listOf(buildJsonObject {
            put("id", 41); put("role", "assistant"); put("content", "")
            put("tool_calls", JsonArray(listOf(buildJsonObject { put("id", "call") })))
            put("display_kind", "failed_turn"); put("display_metadata", failure)
        })
        assertNotNull(decodeRows(rows, true).filterIsInstance<AssistantTurn>().single().error)
    }

    @Test fun `same error code on different boundaries remains distinct and overlapping pages dedupe`() {
        for (rest in listOf(false, true)) {
            val first = decode(failure, rest, "failed_turn", "boundary one", 41)
            val second = decode(failure, rest, "failed_turn", "boundary two", 42)
            val combined = mergeOlderTranscriptPage(second, first + second)
            assertEquals(2, combined.filterIsInstance<AssistantTurn>().count { it.error != null })
            assertEquals(2, combined.mapNotNull { it.rowId }.distinct().size)
            assertEquals(combined, mergeOlderTranscriptPage(combined, first + second))
            assertEquals(combined, graftRefreshedTailOntoBackfill(second, combined).entries)
        }
    }
}
