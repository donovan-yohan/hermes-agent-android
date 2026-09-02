package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paged transcript route hands back stored rows, not the Gateway's display
 * projection. These are the rules that projection applies
 * (`tui_gateway/server.py:9720-9823` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), asserted on the row shapes
 * `SELECT * FROM messages` actually produces (`hermes_state.py:13000-13016`).
 */
class RestTranscriptProjectionTest {

    private fun rows(vararg json: String): List<JsonObject> =
        json.map { Json.parseToJsonElement(it) as JsonObject }

    private fun project(vararg json: String) = projectRestTranscriptRows(rows(*json))

    @Test
    fun `a stored row carries its own id as the durable address`() {
        val projected = project("""{"id":42,"role":"user","content":"ship it","timestamp":1700001000.0}""")

        assertEquals("42", projected.single().string("row_id"))
        assertEquals("ship it", projected.single().string("text"))
    }

    @Test
    fun `gateway bookkeeping notices never render as a user turn`() {
        val projected = project(
            """{"id":1,"role":"user","content":"[System: model changed to acme/reasoner]"}""",
            """{"id":2,"role":"user","content":"carry on"}""",
        )

        assertEquals(listOf("carry on"), projected.map { it.string("text") })
    }

    @Test
    fun `a row the route could not project for display is dropped`() {
        val projected = project(
            """{"id":1,"role":"user","content":"reference payload","display_kind":"hidden"}""",
            """{"id":2,"role":"assistant","content":"answer"}""",
        )

        assertEquals(listOf("answer"), projected.map { it.string("text") })
    }

    @Test
    fun `a compaction row renders the display body the route substituted`() {
        val projected = project(
            """{"id":9,"role":"assistant","content":"raw handoff payload","display_content":"Compacted 40 turns"}""",
        )

        assertEquals("Compacted 40 turns", projected.single().string("text"))
    }

    @Test
    fun `a skill turn shows its invocation and never the loaded body`() {
        val body = "[IMPORTANT: The user has invoked the \\\"work\\\" skill. " +
            "The full skill content is loaded below.] SECRET SKILL BODY " +
            "The user has provided the following instruction alongside the skill invocation: fix the leak"
        val projected = project("""{"id":5,"role":"user","content":"$body"}""")

        assertEquals("/work fix the leak", projected.single().string("text"))
        assertTrue(projected.single().string("text")?.contains("SECRET") != true)
    }

    @Test
    fun `a tool row is named from the call that made it and previews its primary argument`() {
        val projected = project(
            """{"id":10,"role":"assistant","content":"",
                "tool_calls":[{"id":"call-1","function":{"name":"read_file","arguments":"{\"path\":\"/srv/app/main.kt\"}"}}]}""",
            """{"id":11,"role":"tool","tool_call_id":"call-1","tool_name":"unknown","content":"file body"}""",
        )

        // The assistant row carried only the call, so it says nothing itself.
        assertEquals(1, projected.size)
        val tool = projected.single()
        assertEquals("tool", tool.string("role"))
        assertEquals("read_file", tool.string("name"))
        assertEquals("/srv/app/main.kt", tool.string("context"))
        assertEquals("11", tool.string("row_id"))
    }

    @Test
    fun `a tool row with no matching call falls back to its stored tool name`() {
        val projected = project(
            """{"id":12,"role":"tool","tool_call_id":"orphan","tool_name":"terminal","content":"ok"}""",
        )

        assertEquals("terminal", projected.single().string("name"))
        assertNull(projected.single().string("context"))
    }

    @Test
    fun `an assistant turn with only reasoning survives`() {
        val projected = project("""{"id":13,"role":"assistant","content":"","reasoning":"weighing options"}""")

        assertEquals("weighing options", projected.single().string("reasoning"))
    }

    /**
     * The shape the route actually emits. `SessionDB.get_messages` builds each
     * row as `dict(row)` (`hermes_state.py:13001-13002` @ `3ca096de`) over a
     * `SELECT *` (`:12926`, and `:12943` on the `include_compacted` read this
     * app always makes), so every column rides the wire and a row that made no call
     * carries `"tool_calls": null` rather than omitting the key. Reading the
     * key's presence rather than the array's would drop exactly the row upstream
     * keeps deliberately (`server.py:9770-9787`).
     */
    @Test
    fun `a reasoning-only turn survives the null tool_calls every stored row carries`() {
        val projected = project(
            """{"id":16,"role":"assistant","content":"","tool_calls":null,"tool_call_id":null,
                "tool_name":null,"reasoning":"weighing options","reasoning_content":null,
                "display_kind":null,"display_content":null}""",
        )

        assertEquals("weighing options", projected.single().string("reasoning"))
        assertEquals("16", projected.single().string("row_id"))
    }

    @Test
    fun `an assistant row that really did only call a tool still says nothing`() {
        val projected = project(
            """{"id":17,"role":"assistant","content":"  ",
                "tool_calls":[{"id":"call-9","function":{"name":"terminal","arguments":"{\"command\":\"ls\"}"}}]}""",
            """{"id":18,"role":"assistant","content":"and here is why","tool_calls":null}""",
        )

        assertEquals(listOf("and here is why"), projected.map { it.string("text") })
    }

    /**
     * Upstream masks a `browser_type` call's `text` before building any preview
     * (`redact_tool_args_for_display`, `agent/display.py:400-414`, applied at
     * `:456`). That masking is `redact_sensitive_text(force=True)` over thirteen
     * credential patterns and is not ported, so no preview is built at all —
     * a credential typed into a browser field must not reach a collapsed title.
     */
    @Test
    fun `a browser field's typed text never becomes a collapsed tool title`() {
        val projected = project(
            """{"id":19,"role":"assistant","content":"",
                "tool_calls":[{"id":"call-2","function":{"name":"browser_type",
                "arguments":"{\"ref\":\"e7\",\"text\":\"whatever was typed here\"}"}}]}""",
            """{"id":20,"role":"tool","tool_call_id":"call-2","tool_name":"browser_type","content":"typed"}""",
        )

        val tool = projected.single()
        assertEquals("browser_type", tool.string("name"))
        assertNull(tool.string("context"))
        // The call itself still rides the row for the expanded view, exactly as
        // upstream ships it — only the always-visible preview is withheld.
        assertTrue(tool.toString().contains("whatever was typed here"))
    }

    /**
     * Only `"hidden"` drops a row. The other `display_kind` values the route
     * forwards (`server.py:9705-9717,9813-9820`) reach Desktop as system
     * timeline rows (`lib/chat-messages/hydration.ts:94-116,197-208`) and
     * Android renders no such row on either contract, so the row keeps its
     * stored role and body here. Ledgered in
     * `docs/parity/transcript-backfill.md`, and pinned so a change is a decision
     * rather than a surprise.
     */
    @Test
    fun `a display_kind the app does not render keeps the row it was stamped on`() {
        val projected = project(
            """{"id":21,"role":"system","content":"Model changed to acme/reasoner",
                "display_kind":"model_switch","display_metadata":{"model":"acme/reasoner"}}""",
            """{"id":22,"role":"user","content":"[System note: resumed the interrupted turn]",
                "display_kind":"auto_continue"}""",
        )

        assertEquals(
            listOf("Model changed to acme/reasoner", "[System note: resumed the interrupted turn]"),
            projected.map { it.string("text") },
        )
        assertNull(projected.first().string("display_kind"))
    }

    @Test
    fun `a blank row with nothing to say is dropped`() {
        assertEquals(emptyList<JsonObject>(), project("""{"id":14,"role":"assistant","content":"   "}"""))
        assertEquals(emptyList<JsonObject>(), project("""{"id":15,"role":"reference","content":"internal"}"""))
    }
}
