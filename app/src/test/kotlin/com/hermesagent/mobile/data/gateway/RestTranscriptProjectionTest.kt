package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paged transcript route hands back stored rows, not the Gateway's display
 * projection. These are the rules that projection applies
 * (`tui_gateway/session_history.py:180-238` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`), asserted on the row shapes
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

    /**
     * The cron tool the pin runs registers as `cronjob_manage`
     * (`model_tools.py:183` @ `437116f9497c80d242ce034ff7f5d81dc277a337`) and its
     * preview is the action (`agent/display.py:357` @ the same SHA).
     *
     * The `prompt` on the call is the trap: without the table entry the generic
     * fallback finds it and the row titles itself with the whole task text while
     * the same turn over the RPC path and on Desktop reads `create`.
     */
    @Test
    fun `a cron call previews its action and never the prompt it also carries`() {
        val projected = project(
            """{"id":23,"role":"assistant","content":"",
                "tool_calls":[{"id":"call-cron","function":{"name":"cronjob_manage",
                "arguments":"{\"action\":\"create\",\"prompt\":\"check the nightly build\"}"}}]}""",
            """{"id":24,"role":"tool","tool_call_id":"call-cron","tool_name":"cronjob_manage","content":"ok"}""",
        )

        assertEquals("create", projected.single().string("context"))
    }

    /**
     * An older Gateway persisted the pre-rename spelling. Both spellings reach
     * the one table entry — one rendered preview — while the row keeps the name
     * its transcript actually stored (`_LEGACY_TOOL_ALIASES`,
     * `model_tools.py:601-604` @ the pin).
     */
    @Test
    fun `a cron row an older gateway stored under the legacy spelling previews the same`() {
        val projected = project(
            """{"id":25,"role":"assistant","content":"",
                "tool_calls":[{"id":"call-new","function":{"name":"cronjob_manage",
                "arguments":"{\"action\":\"list\"}"}}]}""",
            """{"id":26,"role":"tool","tool_call_id":"call-new","tool_name":"cronjob_manage","content":"ok"}""",
            """{"id":27,"role":"assistant","content":"",
                "tool_calls":[{"id":"call-old","function":{"name":"cronjob",
                "arguments":"{\"action\":\"list\"}"}}]}""",
            """{"id":28,"role":"tool","tool_call_id":"call-old","tool_name":"cronjob","content":"ok"}""",
        )

        val (current, legacy) = projected
        assertEquals("cronjob_manage", current.string("name"))
        assertEquals("cronjob", legacy.string("name"))
        assertEquals("list", current.string("context"))
        assertEquals(current.string("context"), legacy.string("context"))
    }

    /**
     * One page as the pinned route ships it. `SessionDB.get_messages` builds each
     * row as `dict(row)` (`hermes_state_messages.py:753-756` @
     * `437116f9497c80d242ce034ff7f5d81dc277a337`) over a `SELECT *` (`:806` on
     * the `include_compacted` read this app always makes), so every column of
     * `messages` rides the wire — the nulls below are what a row that made no
     * call carries.
     */
    @Test
    fun `a cron turn off the pinned wire projects its stored page`() {
        val projected = project(
            """{"id":77,"role":"user","content":"schedule the nightly build check",
                "tool_calls":null,"tool_call_id":null,"tool_name":null,"reasoning":null,
                "display_kind":null,"display_content":null,"timestamp":1789000000.0}""",
            """{"id":78,"role":"assistant","content":"",
                "tool_calls":[{"id":"call-cron-1","function":{"name":"cronjob_manage",
                "arguments":"{\"action\":\"create\",\"prompt\":\"check the nightly build\"}"}}],
                "tool_call_id":null,"tool_name":null,"reasoning":null,"display_kind":null,
                "display_content":null,"timestamp":1789000001.0}""",
            """{"id":79,"role":"tool","content":"scheduled","tool_call_id":"call-cron-1",
                "tool_name":"cronjob_manage","tool_calls":null,"reasoning":null,
                "display_kind":null,"display_content":null,"timestamp":1789000002.0}""",
        )

        assertEquals(listOf("user", "tool"), projected.map { it.string("role") })
        val tool = projected.last()
        assertEquals("cronjob_manage", tool.string("name"))
        assertEquals("create", tool.string("context"))
        assertEquals("79", tool.string("row_id"))
    }

    @Test
    fun `an assistant turn with only reasoning survives`() {
        val projected = project("""{"id":13,"role":"assistant","content":"","reasoning":"weighing options"}""")

        assertEquals("weighing options", projected.single().string("reasoning"))
    }

    /**
     * The shape the route actually emits. `SessionDB.get_messages` builds each
     * row as `dict(row)` (`hermes_state_messages.py:616` @ `72a3277cd7`) over a
     * `SELECT *` (`hermes_state_messages.py:649`, and `:646` on the `include_compacted` read this
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
     * A typed display row keeps the `display_kind` and `display_metadata` it was
     * stamped with, for every kind this app renders a row for. The shared parser
     * is the one that reads them (`classifyTimelineEvent`), and it is the only
     * thing that may licence treating a row as a timeline event. The RPC
     * contract carries the same two fields (`session_history.py:237-241`), so
     * dropping them here made the two contracts disagree about one persisted
     * row — and an `async_delegation_complete` row's `[ASYNC DELEGATION BATCH
     * COMPLETE …]` body, which is addressed to the model, reached a user bubble.
     * Ledgered in `docs/parity/transcript-backfill.md`.
     */
    @Test
    fun `a typed display row keeps the metadata it is classified by`() {
        val projected = project(
            """{"id":21,"role":"user","content":"[ASYNC DELEGATION BATCH COMPLETE — done]",
                "display_kind":"async_delegation_complete","display_metadata":{"task_count":2}}""",
            """{"id":22,"role":"user","content":"[System note: Your previous turn was interrupted mid-run]",
                "display_kind":"auto_continue"}""",
        )

        assertEquals(listOf("async_delegation_complete", "auto_continue"), projected.map { it.string("display_kind") })
        assertEquals(2, projected.first().displayMetadataField("task_count"))
        assertNull(projected.last()["display_metadata"])
    }

    /**
     * The `[System: …` filter runs **before** the kind is read, on both
     * contracts (`session_history.py:195-196`), and it is why a `model_switch`
     * row — whose stored body *is* a `[System: The active model …]` marker —
     * never reaches this app as a row at all. Its [TimelineKind] entry is the
     * set Desktop itself classifies, kept whole rather than trimmed to whatever
     * survives this filter today.
     */
    @Test
    fun `a system-marker row is dropped whatever kind it was stamped with`() {
        val projected = project(
            """{"id":24,"role":"user","content":"[System: The active model for this chat has changed to acme/reasoner.]",
                "display_kind":"model_switch","display_metadata":{"model":"acme/reasoner"}}""",
        )

        assertEquals(emptyList<JsonObject>(), projected)
    }

    /**
     * A kind this app renders no row for keeps the row it was stamped on and
     * nothing else: forwarding a shape no renderer reads would invite a future
     * one to trust what no test pins. `TimelineKind.fromWireName` is the gate.
     */
    @Test
    fun `a display_kind the app does not render keeps the row it was stamped on`() {
        val projected = project(
            """{"id":23,"role":"system","content":"Compacted 40 turns",
                "display_kind":"skill_invocation","display_metadata":{"name":"work"}}""",
        )

        assertEquals("Compacted 40 turns", projected.single().string("text"))
        assertNull(projected.single().string("display_kind"))
        assertNull(projected.single()["display_metadata"])
    }

    /** The metadata object a projected row carries, read back through its own key. */
    private fun JsonObject.displayMetadataField(key: String): Int? =
        ((this["display_metadata"] as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

    @Test
    fun `a blank row with nothing to say is dropped`() {
        assertEquals(emptyList<JsonObject>(), project("""{"id":14,"role":"assistant","content":"   "}"""))
        assertEquals(emptyList<JsonObject>(), project("""{"id":15,"role":"reference","content":"internal"}"""))
    }
}
