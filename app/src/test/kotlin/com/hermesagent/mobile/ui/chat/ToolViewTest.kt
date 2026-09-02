package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `ToolView` projection, against Desktop's `buildToolView`
 * (`apps/desktop/src/components/assistant-ui/tool/fallback-model/index.ts:1409-1499`
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * Every case names the upstream rule it is holding, because the point of this
 * file is that a phone shows what Desktop shows — not that this particular
 * Kotlin still does what it did last week.
 */
class ToolViewTest {

    private fun activity(
        toolName: String,
        state: ToolState = ToolState.Done,
        args: String? = null,
        result: String? = null,
        detail: String = "",
        inlineDiff: String? = null,
        elapsedSeconds: Double = 0.0,
    ) = ToolActivity(
        id = "tool-1",
        label = toolName,
        detail = detail,
        state = state,
        elapsedSeconds = elapsedSeconds,
        toolName = toolName,
        argsText = args,
        resultText = result,
        inlineDiff = inlineDiff,
    )

    // ── Streams, command and exit code ───────────────────────────────────────

    @Test
    fun `terminal output splits stdout and stderr into their own sections`() {
        val view = activity(
            "terminal",
            args = """{"command":"npm test"}""",
            result = """{"stdout":"7 passing","stderr":"npm warn config","exit_code":0}""",
        ).toolView()

        assertEquals("7 passing", view.stdout)
        assertEquals("npm warn config", view.stderr)
        // index.ts:1456-1458 — once the streams are split the merged detail is
        // not used, so nothing renders twice.
        assertEquals("", view.detail)
        assertEquals("npm test", view.terminalCommand)
        assertEquals(0, view.terminalExitCode)
        assertTrue(view.rendersAnsi)
    }

    @Test
    fun `a stream-less terminal result still merges output and lines`() {
        val view = activity(
            "terminal",
            args = """{"command":"ls"}""",
            result = """{"output":"a.txt","lines":["b.txt","c.txt"]}""",
        ).toolView()

        assertNull(view.stdout)
        assertNull(view.stderr)
        assertEquals("a.txt\nb.txt\nc.txt", view.detail)
    }

    @Test
    fun `a terminal row with no output prints nothing rather than echoing its command`() {
        // index.ts:1104-1109 — the command is already on the `$` prompt line.
        val view = activity("terminal", args = """{"command":"true"}""", result = """{"exit_code":0}""").toolView()

        assertEquals("", view.detail)
        assertEquals("true", view.terminalCommand)
        assertEquals(0, view.terminalExitCode)
    }

    @Test
    fun `a non-zero exit with output is not an error`() {
        // index.ts:689-701 — grep returns 1 on no match; that is not a failure.
        val view = activity(
            "terminal",
            args = """{"command":"grep needle haystack"}""",
            result = """{"stdout":"","stderr":"","output":"no match","exit_code":1}""",
        ).toolView()

        assertEquals(ToolStatus.Success, view.status)
        assertEquals(1, view.terminalExitCode)
    }

    @Test
    fun `a non-zero exit with no output at all reads as an error`() {
        val view = activity(
            "terminal",
            args = """{"command":"false"}""",
            result = """{"exit_code":127}""",
        ).toolView()

        assertEquals(ToolStatus.Error, view.status)
        assertEquals("Command failed with exit code 127.", view.detail)
        assertEquals("Error details", view.detailLabel)
    }

    @Test
    fun `execute_code renders ansi and splits streams, but has no prompt line`() {
        // index.ts:1467,1474-1475 — `terminalCommand` and `terminalExitCode`
        // are terminal-only; execute_code shares only the ANSI + stream rules.
        val view = activity("execute_code", result = """{"stdout":"42","stderr":"warn"}""").toolView()

        assertTrue(view.rendersAnsi)
        assertEquals("42", view.stdout)
        assertEquals("warn", view.stderr)
        assertNull(view.terminalCommand)
        assertNull(view.terminalExitCode)
    }

    @Test
    fun `a non-terminal tool never claims to render ansi`() {
        val view = activity("read_file", args = """{"path":"a.kt"}""", result = """{"content":"hello"}""").toolView()

        assertTrue("read_file must not be treated as terminal-shaped", !view.rendersAnsi)
        assertNull(view.stdout)
        assertEquals("hello", view.detail)
    }

    // ── Tone, icon and status ────────────────────────────────────────────────

    @Test
    fun `the icon table matches desktop's TOOL_META`() {
        // index.ts:142-214, entry for entry. Desktop's table also carries a
        // `tone`, which no renderer reads on either side, so only the icon half
        // is projected — see the field map in docs/parity/tool-output-fidelity.md.
        val expected = mapOf(
            "browser_click" to ToolIconName.Globe,
            "browser_take_screenshot" to ToolIconName.FileMedia,
            "clarify" to ToolIconName.Question,
            "cronjob" to ToolIconName.Watch,
            "edit_file" to ToolIconName.Edit,
            "execute_code" to ToolIconName.Terminal,
            "image_generate" to ToolIconName.FileMedia,
            "list_files" to ToolIconName.Files,
            "memory" to ToolIconName.Brain,
            "patch" to ToolIconName.Edit,
            "read_file" to ToolIconName.File,
            "search_files" to ToolIconName.Search,
            "session_search_recall" to ToolIconName.Search,
            "terminal" to ToolIconName.Terminal,
            "todo" to ToolIconName.Tools,
            "vision_analyze" to ToolIconName.Eye,
            "web_extract" to ToolIconName.Globe,
            "web_search" to ToolIconName.Search,
            "write_file" to ToolIconName.Edit,
        )

        for ((name, icon) in expected) {
            assertEquals("$name icon", icon, activity(name).toolView().icon)
        }
    }

    @Test
    fun `an unknown tool falls back through desktop's prefix rule`() {
        // index.ts:233-236.
        assertEquals(ToolIconName.Globe, activity("browser_unheard_of").toolView().icon)
        assertEquals(ToolIconName.Globe, activity("web_unheard_of").toolView().icon)
        assertNull(activity("something_else").toolView().icon)
    }

    @Test
    fun `state maps onto desktop's status vocabulary`() {
        assertEquals(ToolStatus.Running, activity("terminal", state = ToolState.Running).toolView().status)
        assertEquals(ToolStatus.Success, activity("terminal", state = ToolState.Done).toolView().status)
        assertEquals(ToolStatus.Error, activity("terminal", state = ToolState.Failed).toolView().status)
        assertEquals(ToolStatus.Stopped, activity("terminal", state = ToolState.Stopped).toolView().status)
    }

    @Test
    fun `a refused memory write is a warning, never destructive`() {
        // index.ts:723-726 — an over-budget memory batch is a negotiation.
        val view = activity("memory", state = ToolState.Failed, result = """{"message":"over budget"}""").toolView()

        assertEquals(ToolStatus.Warning, view.status)
    }

    // ── Count and duration ───────────────────────────────────────────────────

    @Test
    fun `count labels pluralise the way desktop's noun table does`() {
        // index.ts:389-407 and :441-458.
        assertEquals("1 file", activity("list_files", result = """{"files":["a"]}""").toolView().countLabel)
        assertEquals("3 files", activity("list_files", result = """{"files":["a","b","c"]}""").toolView().countLabel)
        assertEquals("2 matches", activity("search_files", result = """{"match_count":2}""").toolView().countLabel)
        assertEquals("4 searches", activity("anything", result = """{"search_count":4}""").toolView().countLabel)
        assertEquals("2 entries", activity("memory", result = """{"entry_count":2}""").toolView().countLabel)
    }

    @Test
    fun `a count of zero is no count at all`() {
        assertNull(activity("list_files", result = """{"files":[]}""").toolView().countLabel)
        assertNull(activity("list_files", result = """{"count":0}""").toolView().countLabel)
    }

    @Test
    fun `duration and exit code are never mistaken for counts`() {
        // index.ts:304 — `duration_s`, `exit_code` and `status_code` are excluded.
        assertNull(activity("terminal", result = """{"duration_s":3,"exit_code":0}""").toolView().countLabel)
    }

    @Test
    fun `a running row shows no duration and a settled one does`() {
        assertNull(activity("terminal", state = ToolState.Running, elapsedSeconds = 4.0).toolView().durationLabel)
        assertEquals("4s", activity("terminal", elapsedSeconds = 4.0).toolView().durationLabel)
        assertEquals("250ms", activity("terminal", elapsedSeconds = 0.25).toolView().durationLabel)
    }

    // ── Web search ───────────────────────────────────────────────────────────

    @Test
    fun `web search results become structured hits under the original query`() {
        val view = activity(
            "web_search",
            args = """{"search_term":"kotlin flows"}""",
            result = """{"results":[
                {"title":"Flows","url":"https://example.test/a","snippet":"About flows"},
                {"name":"More","link":"https://example.test/b","description":"Also flows"}
            ]}""",
        ).toolView()

        assertEquals("kotlin flows", view.searchQuery)
        assertEquals(2, view.searchHits.size)
        assertEquals(SearchResultRow("Flows", "https://example.test/a", "About flows"), view.searchHits[0])
        // index.ts:657-659 — `name`/`link`/`description` are the documented aliases.
        assertEquals(SearchResultRow("More", "https://example.test/b", "Also flows"), view.searchHits[1])
        assertEquals("2 results", view.countLabel)
    }

    @Test
    fun `search hits are capped at desktop's six`() {
        val rows = (1..12).joinToString(",") { """{"title":"t$it","url":"https://example.test/$it"}""" }
        val view = activity("web_search", result = """{"results":[$rows]}""").toolView()

        assertEquals(6, view.searchHits.size)
    }

    @Test
    fun `a hit with neither title nor url is dropped`() {
        val view = activity(
            "web_search",
            result = """{"results":[{"snippet":"orphan"},{"title":"kept","url":""}]}""",
        ).toolView()

        assertEquals(listOf("kept"), view.searchHits.map { it.title })
    }

    // ── Copy ─────────────────────────────────────────────────────────────────

    @Test
    fun `copy yields the uncapped output while the display is clamped`() {
        val huge = "x".repeat(MAX_TOOL_RENDER_CHARS * 2)
        val view = activity("terminal", args = """{"command":"cat big"}""", result = """{"stdout":"$huge"}""").toolView()

        assertNotNull("a clamped row must still offer the whole output", view.copy)
        assertEquals(huge, view.copy?.text)
        assertEquals("Copy output", view.copy?.label)
        assertTrue("the painted slice must be clamped", clampForDisplay(view.stdout!!).length < huge.length)
    }

    @Test
    fun `a terminal row with no output copies its command instead`() {
        // index.ts:1206-1216.
        val view = activity("terminal", args = """{"command":"./gradlew check"}""", result = """{"exit_code":0}""").toolView()

        assertEquals("Copy command", view.copy?.label)
        assertEquals("./gradlew check", view.copy?.text)
    }

    @Test
    fun `web search copies its hits, or the query when there are none`() {
        val withHits = activity(
            "web_search",
            args = """{"query":"q"}""",
            result = """{"results":[{"title":"t","url":"https://example.test/a","snippet":"s"}]}""",
        ).toolView()
        assertEquals("Copy results", withHits.copy?.label)
        assertEquals("t\nhttps://example.test/a\ns", withHits.copy?.text)

        val queryOnly = activity("web_search", args = """{"query":"q"}""", result = """{}""").toolView()
        assertEquals("Copy query", queryOnly.copy?.label)
        assertEquals("q", queryOnly.copy?.text)
    }

    @Test
    fun `a wrapped array payload still yields its hits and its count`() {
        // format.ts:85-97 unwraps any payload, not only an object. Narrowing it
        // to objects lost every hit inside a `{"data": [ … ]}` envelope.
        val view = activity(
            "web_search",
            args = """{"query":"q"}""",
            result = """{"data":[{"title":"t","url":"https://example.test/a","snippet":"s"}]}""",
        ).toolView()

        assertEquals(1, view.searchHits.size)
        assertEquals("1 result", view.countLabel)
    }

    @Test
    fun `a row with nothing to hand over offers no copy`() {
        assertNull(activity("terminal", result = """{}""").toolView().copy)
    }

    // ── The display clamp ────────────────────────────────────────────────────

    @Test
    fun `short output is returned untouched`() {
        assertEquals("hello", clampForDisplay("hello"))
    }

    @Test
    fun `the character cap truncates and says how much it dropped`() {
        val value = "x".repeat(MAX_TOOL_RENDER_CHARS + 1_500)
        val clamped = clampForDisplay(value)

        assertTrue(clamped.startsWith("x".repeat(MAX_TOOL_RENDER_CHARS)))
        assertTrue(clamped.endsWith("… 1,500 more characters truncated — use Copy for the full output."))
    }

    @Test
    fun `the line cap bites before the character cap on a tall thin log`() {
        val value = (1..MAX_TOOL_RENDER_LINES * 2).joinToString("") { "line $it\n" }
        val clamped = clampForDisplay(value)

        val kept = clamped.substringBefore("\n\n…")
        assertTrue("the last kept line must be complete", kept.endsWith("line $MAX_TOOL_RENDER_LINES\n"))
        assertTrue("the line after the cap must be gone", !kept.contains("line ${MAX_TOOL_RENDER_LINES + 1}"))
        assertTrue(clamped.contains("more characters truncated"))
        assertTrue("the cut must be well inside the character cap", value.length < MAX_TOOL_RENDER_CHARS)
    }

    @Test
    fun `a payload of exactly the line cap is left whole`() {
        // The cut lands past the last newline, so a complete 200-line log does
        // not gain a notice announcing that it lost that newline.
        val value = (1..MAX_TOOL_RENDER_LINES).joinToString("") { "line $it\n" }

        assertEquals(value, clampForDisplay(value))
    }

    @Test
    fun `a truncation smaller than its own notice is not worth announcing`() {
        val value = "x".repeat(MAX_TOOL_RENDER_CHARS + 8)

        assertEquals(value, clampForDisplay(value))
    }

    // ── Tolerant reads ───────────────────────────────────────────────────────

    @Test
    fun `payloads that are not json at all still project a view`() {
        val view = activity("terminal", args = "not json", result = "also not json", detail = "fallback").toolView()

        assertEquals(ToolStatus.Success, view.status)
        assertNull(view.stdout)
        assertEquals("also not json", view.detail)
        assertEquals("Copy output", view.copy?.label)
    }

    @Test
    fun `an empty activity projects an empty view rather than throwing`() {
        val view = activity("mystery").toolView()

        assertEquals("", view.detail)
        assertNull(view.stdout)
        assertNull(view.terminalCommand)
        assertTrue(view.searchHits.isEmpty())
    }
}
