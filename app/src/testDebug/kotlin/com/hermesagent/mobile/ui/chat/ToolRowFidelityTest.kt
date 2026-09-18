package com.hermesagent.mobile.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an expanded tool row actually paints.
 *
 * [ToolViewTest] pins the projection; this pins the render — that ANSI reaches
 * the screen as colour rather than as `[31m`, that stdout and stderr arrive as
 * two labelled sections, that the `$` prompt line and the exit code are there,
 * and that the Copy control hands over the output the display had to truncate.
 *
 * Desktop's own renderer is `assistant-ui/tool/fallback.tsx:597-744` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class ToolRowFidelityTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ChatUiState())

    private val clipboardText: String?
        get() = (
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private fun tool(
        toolName: String = "terminal",
        label: String = toolName,
        state: ToolState = ToolState.Done,
        args: String? = null,
        result: String? = null,
        detail: String = "",
    ) = ToolActivity(
        id = "$SESSION-t1",
        label = label,
        detail = detail,
        state = state,
        elapsedSeconds = 1.0,
        toolName = toolName,
        argsText = args,
        resultText = result,
        startedAtMillis = NOW,
    )

    private fun launch(vararg tools: ToolActivity) {
        state = ChatUiState(
            activeSession = SessionSummary(
                id = SESSION,
                title = "Tool output",
                preview = "",
                lastActiveAtMillis = NOW,
                status = SessionStatus.Idle,
            ),
            transcript = listOf<TranscriptEntry>(UserTurn("$SESSION-u1", "do the thing", NOW)) + tools,
            isStreaming = false,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(state = state, actions = ChatActions(), onOpenSettings = {})
            }
        }
        compose.waitForIdle()
    }

    private fun expand(title: String) {
        compose.onNodeWithContentDescription(title).performScrollTo().performClick()
        compose.waitForIdle()
    }

    /** Every string this screen is currently painting. */
    private fun renderedText(): String = compose
        .onAllNodes(hasText("", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .joinToString("\n") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("\n") { it.text }
        }

    // ── ANSI ─────────────────────────────────────────────────────────────────

    @Test
    fun `ansi escape codes are parsed rather than painted as literals`() {
        launch(
            tool(
                args = """{"command":"npm test"}""",
                result = """{"stdout":"${ESC}[1;31mFAILED${ESC}[0m 2 of 40${ESC}[32m ok${ESC}[0m"}""",
            ),
        )
        expand("Tool Ran npm test, done")

        val painted = renderedText()
        assertTrue("the visible output must survive: $painted", painted.contains("FAILED 2 of 40 ok"))
        assertTrue("no CSI introducer may reach the screen", !painted.contains("\u001B"))
        assertTrue("no SGR payload may reach the screen", !painted.contains("[1;31m"))
        assertTrue(!painted.contains("[0m"))
    }

    @Test
    fun `a truncated escape sequence renders instead of leaking its parameters`() {
        launch(tool(args = """{"command":"tail -f log"}""", result = """{"stdout":"still alive${ESC}[3"}"""))
        expand("Tool Ran tail -f log, done")

        val painted = renderedText()
        assertTrue(painted.contains("still alive"))
        assertTrue("a cut-off sequence must not print its digits: $painted", !painted.contains("[3"))
    }

    // ── Streams, prompt line, exit code ──────────────────────────────────────

    @Test
    fun `stdout and stderr render as two labelled sections`() {
        launch(
            tool(
                args = """{"command":"npm ci"}""",
                result = """{"stdout":"added 402 packages","stderr":"npm warn deprecated glob","exit_code":0}""",
            ),
        )
        expand("Tool Ran npm ci, done")

        // TOOL_SECTION_LABEL_CLASS is an uppercase field label (fallback.tsx:92).
        compose.onNodeWithText("STDOUT").assertIsDisplayed()
        compose.onNodeWithText("STDERR").assertIsDisplayed()
        compose.onNodeWithText("added 402 packages", substring = true).assertIsDisplayed()
        compose.onNodeWithText("npm warn deprecated glob", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a lone stdout stream needs no label to tell it apart`() {
        // fallback.tsx:662 — the `stdout` label only appears when stderr is
        // there to be distinguished from.
        launch(tool(args = """{"command":"date"}""", result = """{"stdout":"Tue 26 Aug"}"""))
        expand("Tool Ran date, done")

        compose.onNodeWithText("Tue 26 Aug", substring = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("STDOUT")).fetchSemanticsNodes().size)
    }

    @Test
    fun `the command renders as a prompt line and the exit code is shown`() {
        launch(tool(args = """{"command":"./gradlew check"}""", result = """{"stdout":"OK","exit_code":0}"""))
        expand("Tool Ran ./gradlew check, done")

        compose.onNodeWithText("$ ./gradlew check").assertIsDisplayed()
        compose.onNodeWithText("exit 0").assertIsDisplayed()
        // The `$` is the shell's prompt, not part of the command (fallback.tsx:726).
        compose.onNodeWithContentDescription("Command ./gradlew check").assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit code 0").assertIsDisplayed()
    }

    @Test
    fun `a failing exit code is still shown, and quietly`() {
        launch(tool(args = """{"command":"pytest"}""", result = """{"stdout":"1 failed","exit_code":1}"""))
        expand("Tool Ran pytest, done")

        compose.onNodeWithContentDescription("Exit code 1").assertIsDisplayed()
        compose.onNodeWithText("1 failed", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a failed command still shows the output it produced`() {
        // Upstream's error branch short-circuits the streams. It must not here:
        // a failed command is the row where its output matters most, and Copy
        // would otherwise hand over text the screen refused to paint.
        launch(
            tool(
                state = ToolState.Failed,
                args = """{"command":"./gradlew check"}""",
                result = """{"stdout":"42 tests","stderr":"AssertionError at line 9","exit_code":1}""",
            ),
        )
        expand("Tool Ran ./gradlew check, error")

        compose.onNodeWithText("42 tests", substring = true).assertIsDisplayed()
        compose.onNodeWithText("AssertionError at line 9", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Exit code 1").assertIsDisplayed()
    }

    // ── Clamp and copy ───────────────────────────────────────────────────────

    @Test
    fun `the display is clamped while copy yields the uncapped output`() {
        // Three very long lines rather than many short ones: this trips the
        // character cap while keeping the row a few lines tall, so the Copy
        // control stays on screen for the press. The line cap has its own case
        // in `ToolViewTest`.
        val body = (1..3).joinToString("\\n") { "chunk $it " + "x".repeat(7_000) } + " TAIL-MARKER"
        launch(tool(args = """{"command":"cat build.log"}""", result = """{"stdout":"$body"}"""))
        expand("Tool Ran cat build.log, done")

        val painted = renderedText()
        assertTrue("the head of the log must be painted", painted.contains("chunk 1"))
        assertTrue("the tail must be clamped away", !painted.contains("TAIL-MARKER"))
        assertTrue("the clamp must say so: ${painted.takeLast(160)}", painted.contains("more characters truncated"))

        compose.onNodeWithContentDescription("Copy output").performClick()
        compose.waitForIdle()

        val copied = clipboardText.orEmpty()
        assertTrue(
            "copy must carry the tail the display dropped; got ${copied.length} chars",
            copied.endsWith("TAIL-MARKER"),
        )
        assertTrue("copy must not carry the truncation notice", !copied.contains("more characters truncated"))
        assertTrue("copy must be longer than the painted slice", copied.length > MAX_TOOL_RENDER_CHARS)
    }

    @Test
    fun `the copy confirmation survives a streamed delta`() {
        // The tap handler used to capture a state instance keyed on the payload
        // text, so the first delta orphaned it: the clipboard filled and the
        // control never confirmed.
        launch(tool(args = """{"command":"tail -f log"}""", result = """{"stdout":"first chunk of output"}"""))
        expand("Tool Ran tail -f log, done")

        state = state.copy(
            transcript = state.transcript.dropLast(1) +
                tool(args = """{"command":"tail -f log"}""", result = """{"stdout":"first chunk of output, then more"}"""),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Copy output").performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Output copied").assertIsDisplayed()
        assertEquals("first chunk of output, then more", clipboardText)
    }

    @Test
    fun `the copy control meets the touch floor and names what it copies`() {
        launch(tool(args = """{"command":"echo hi"}""", result = """{"stdout":"hi there, this is long enough"}"""))
        expand("Tool Ran echo hi, done")

        compose.onNodeWithContentDescription("Copy output")
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(TOUCH_FLOOR)
            .assertHeightIsAtLeast(TOUCH_FLOOR)
    }

    // ── Inline diff ──────────────────────────────────────────────────────────

    /**
     * The Copy control Desktop puts over a diff payload.
     *
     * `InlineDiffPanel` carries this same slot and `toolCopyPayload` makes
     * file-edit inline diffs resolve to `copy.file`
     * (`fallback-model/index.ts:1254-1257 @ 72a3277cd7`), so this app ships the
     * full diff through `ToolCopyControl` and names it `Copy file`.
     */
    @Test
    fun `an inline diff carries a live Copy control that hands over the whole diff`() {
        launch(
            ToolActivity(
                id = "$SESSION-t1",
                label = "apply_patch",
                detail = "",
                state = ToolState.Done,
                toolName = "apply_patch",
                argsText = """{"path":"notes.md"}""",
                inlineDiff = DIFF,
                startedAtMillis = NOW,
            ),
        )

        val copy = compose.onNodeWithContentDescription("Copy file")
            .performScrollTo()
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Copy file")
            .assertIsEnabled()
            .assertWidthIsAtLeast(TOUCH_FLOOR)
            .assertHeightIsAtLeast(TOUCH_FLOOR)
            .getUnclippedBoundsInRoot()

        val firstLine = compose.onNodeWithText(REMOVED_LINE, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("the control belongs above the diff body", copy.top < firstLine.top)

        compose.onNodeWithContentDescription("Copy file")
            .performClick()
        compose.waitForIdle()
        // Desktop's `copy.file` is `view.inlineDiff` — already chrome-stripped
        // by `fallback.tsx:375` before the payload is built. Nobody wants ESC
        // bytes on a clipboard, and Desktop never puts them there.
        assertEquals(CLEANED_DIFF, clipboardText)
        assertTrue("the clipboard must carry no escape byte", clipboardText?.contains(KESC) != true)
    }

    @Test
    fun `a gateway rendered diff paints its lines without the tty chrome`() {
        launch(
            ToolActivity(
                id = "$SESSION-t1",
                label = "apply_patch",
                detail = "",
                state = ToolState.Done,
                toolName = "apply_patch",
                argsText = """{"path":"notes.md"}""",
                inlineDiff = DIFF,
                startedAtMillis = NOW,
            ),
        )

        val painted = renderedText()

        assertTrue("the diff body must survive: $painted", painted.contains(REMOVED_LINE))
        assertTrue("the diff body must survive: $painted", painted.contains(ADDED_LINE))
        assertTrue("no CSI introducer may reach the screen", !painted.contains(KESC))
        assertTrue("no truecolour payload may reach the screen: $painted", !painted.contains("[38;2"))
        assertTrue("no tinted background payload may reach the screen: $painted", !painted.contains("[48;2"))
        // `index.ts:775-781` and `diff-lines.tsx:114-163`: the TTY banner, the
        // collapsed header line and the hunk header are all chrome.
        assertTrue("the review-diff banner is chrome: $painted", !painted.contains("┊ review diff"))
        assertTrue("the hunk header is chrome: $painted", !painted.contains("@@"))
        assertTrue("the arrow header line is chrome: $painted", !painted.contains("→"))
        // `diff-lines.tsx:83-93` — the gutter marker is dropped; colour carries
        // the meaning instead.
        assertTrue("the gutter marker must be stripped: $painted", !painted.contains("-$REMOVED_LINE"))
        assertTrue("the gutter marker must be stripped: $painted", !painted.contains("+$ADDED_LINE"))
    }

    @Test
    fun `the diff header counts the change and shows no duration`() {
        launch(
            ToolActivity(
                id = "$SESSION-t1",
                label = "apply_patch",
                detail = "",
                state = ToolState.Done,
                elapsedSeconds = 4.0,
                toolName = "apply_patch",
                argsText = """{"path":"notes.md"}""",
                inlineDiff = DIFF,
                startedAtMillis = NOW,
            ),
        )

        // `fallback.tsx:585-594` — two independent slots, `+N` and `−N` with
        // U+2212, each drawn only when its own count is positive; `:596` — a
        // file edit shows no duration beside them.
        compose.onNodeWithText("+1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("\u22121", useUnmergedTree = true).assertIsDisplayed()

        val painted = renderedText()
        assertTrue("a file edit shows no duration: $painted", !painted.contains("4.0s"))
        assertTrue("a file edit shows no duration: $painted", !painted.contains("4s"))
    }

    @Test
    fun `a diff with only additions shows no removal count`() {
        launch(
            ToolActivity(
                id = "$SESSION-t1",
                label = "write_file",
                detail = "",
                state = ToolState.Done,
                toolName = "write_file",
                argsText = """{"path":"fresh.md"}""",
                inlineDiff = "$KESC[38;2;255;255;255;48;2;10;45;10m+only this$KESC[0m",
                startedAtMillis = NOW,
            ),
        )

        compose.onNodeWithText("+1", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(
            "a zero removal count has no slot at all",
            0,
            compose.onAllNodes(hasText("\u22120")).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a ten thousand line diff composes a bounded number of rows`() {
        // #71 S34's acceptance: the clamp bounds the row count before Compose
        // measures anything. The panel's body is an ordinary `Column`, not a
        // lazy one, so every row it holds is a real composition — which is
        // exactly why the count has to be bounded rather than trusted.
        val diff = "@@ -1,10000 +1,10000 @@\n" + (0 until 10_000).joinToString("\n") { "+line $it" }
        launch(
            ToolActivity(
                id = "$SESSION-t1",
                label = "apply_patch",
                detail = "",
                state = ToolState.Done,
                toolName = "apply_patch",
                argsText = """{"path":"huge.md"}""",
                inlineDiff = diff,
                startedAtMillis = NOW,
            ),
        )

        val rows = compose.onAllNodes(
            SemanticsMatcher("carries an inline diff line tag") { node ->
                node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("inline-diff-line-") == true
            },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

        assertTrue("a 10,000-line diff composed $rows rows", rows in 1..(MAX_TOOL_RENDER_LINES + 3))

        val painted = renderedText()
        assertTrue("the head of the diff is painted", painted.contains("line 0"))
        assertTrue("the tail is not: ${painted.takeLast(120)}", !painted.contains("line 9999"))
        assertTrue("the reader is told the rest was dropped", painted.contains("more characters truncated"))

        // The action rather than a tap: this panel is taller than the root, so
        // a coordinate-based click on a row inside it lands nowhere.
        compose.onNodeWithContentDescription("Copy file").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        val copied = clipboardText.orEmpty()
        assertTrue(
            "Copy carries the tail the display dropped; got ${copied.length} chars",
            copied.contains("line 9999"),
        )
        assertTrue("and is longer than what is painted", copied.length > painted.length)
    }

    @Test
    fun `sgr parameter bytes with no escape byte in front of them are painted`() {
        // The mirror of the bug: `[38;2;1;2;3m` with no ESC before it is not an
        // escape sequence, it is a line of the file being edited. Stripping it
        // would be deleting the reader's own text.
        launch(
            ToolActivity(
                id = "$SESSION-t1",
                label = "apply_patch",
                detail = "",
                state = ToolState.Done,
                toolName = "apply_patch",
                argsText = """{"path":"palette.css"}""",
                inlineDiff = "@@ -1 +1 @@\n+[38;2;1;2;3m\n-x",
                startedAtMillis = NOW,
            ),
        )

        val painted = renderedText()
        assertTrue("content that merely looks like SGR must survive: $painted", painted.contains("[38;2;1;2;3m"))
    }

    // ── Web search ───────────────────────────────────────────────────────────

    @Test
    fun `web search results render as structured hits under the query`() {
        launch(
            tool(
                toolName = "web_search",
                args = """{"search_term":"compose lazy column"}""",
                result = """{"results":[
                    {"title":"LazyColumn","url":"https://example.test/lazy","snippet":"Lists in Compose"},
                    {"title":"Scrolling","url":"https://example.test/scroll","snippet":"Scroll state"}
                ]}""",
            ),
        )
        expand("Tool Searched, done")

        compose.onNodeWithText("compose lazy column").assertIsDisplayed()
        compose.onNodeWithText("SEARCH RESULTS").assertIsDisplayed()
        compose.onNodeWithText("LazyColumn").assertIsDisplayed()
        compose.onNodeWithText("Lists in Compose").assertIsDisplayed()
        compose.onNodeWithText("Scrolling").assertIsDisplayed()

        val painted = renderedText()
        assertTrue("raw JSON must never reach the screen: $painted", !painted.contains("\"snippet\""))
        assertTrue(!painted.contains("{\"results\""))
    }

    // ── Status glyph vocabulary ──────────────────────────────────────────────

    @Test
    fun `the status glyph vocabulary is spoken on every rung`() {
        // en.ts:3152-3155 @ the pinned SHA: Running / Error / Recovered / Done.
        // `stopped` is the rung Android adds for a turn the reader ended.
        launch(
            tool(state = ToolState.Running, args = """{"command":"sleep 5"}"""),
            tool(toolName = "read_file", state = ToolState.Done, args = """{"path":"a.kt"}""").copy(id = "t2"),
            tool(toolName = "memory", state = ToolState.Failed, result = """{"message":"over budget"}""").copy(id = "t3"),
            tool(toolName = "list_files", state = ToolState.Failed).copy(id = "t4"),
            tool(toolName = "todo", state = ToolState.Stopped).copy(id = "t5"),
        )

        compose.onNodeWithContentDescription("Tool Running sleep 5, running").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Read a.kt, done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Memory, recovered").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool List files, error").performScrollTo().assertIsDisplayed()
        // The task tool's default title is Desktop's one entry for it (#284).
        compose.onNodeWithContentDescription("Tool $TODO_TITLE, stopped").performScrollTo().assertIsDisplayed()
    }

    /**
     * The task tool's two wire spellings must paint one row, title and
     * accessibility description included. `displayTitle` falls through to the
     * stored label, so raw `todo` and `todo_list` labels would otherwise read
     * "Todo" and "Todo list" — the same acceptance #284 fixed for the glyph,
     * the count noun and the composer correlation, one layer up.
     */
    @Test
    fun `the two task-tool spellings paint one title and one spoken description`() {
        launch(
            tool(toolName = "todo", result = """{"count":2}"""),
            tool(toolName = "todo_list", result = """{"count":2}""").copy(id = "$SESSION-t2"),
        )

        // Two rows carry it and nothing else does: a title mismatch would leave
        // one row unmatched here rather than pass silently.
        val descriptions = compose
            .onAllNodes(hasContentDescription("Tool $TODO_TITLE, done"))
            .fetchSemanticsNodes()
            .map { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            }

        assertEquals(2, descriptions.size)
        assertTrue(
            "both rows must speak the same description: $descriptions",
            descriptions.all { it == "Tool $TODO_TITLE, done" },
        )
        compose.onAllNodes(hasText(TODO_TITLE)).assertCountEquals(2)
        compose.onAllNodes(hasText("Todo list", substring = true)).assertCountEquals(0)
    }

    /**
     * A row's own label is its tool's default: the task tool's fallback is
     * normalised (#284) and every other label — including a label a gateway
     * wrote *for the task tool itself* — keeps the pre-existing behaviour, which
     * is the same underscore/case transform every tool already went through.
     */
    @Test
    fun `a custom tool label keeps its existing transform`() {
        launch(
            tool(toolName = "todo_list", label = "Review the plan").copy(id = "$SESSION-t1"),
            tool(toolName = "memory", label = "Custom memory label").copy(id = "$SESSION-t2"),
        )

        // Not the task tool's title: a label that is not the tool's own fallback
        // is untouched, on the pinned spelling as much as on any other tool.
        compose.onNodeWithContentDescription("Tool Review the plan, done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Custom memory label, done").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a row with nothing to show does not offer a disclosure`() {
        launch(tool(toolName = "list_files", result = """{}"""))

        compose.onNodeWithContentDescription("Tool List files, done")
            .performScrollTo()
            .assert(
                SemanticsMatcher("has no expand/collapse state") { node ->
                    node.config.getOrNull(SemanticsProperties.StateDescription) == null
                },
            )
    }

    private companion object {
        const val SESSION = "s-tool"
        const val NOW = 1_756_000_000_000L

        /** Desktop's one title entry for the task tool (`en.ts:4368` @ `d177b119`). */
        const val TODO_TITLE = "Updated todos"

        /** The JSON escape for `ESC`, so no control byte sits in this source. */
        const val ESC = "\\u001B"
        val TOUCH_FLOOR = 48.dp

        /** The Kotlin escape for `ESC`. `inlineDiff` is not a JSON payload. */
        const val KESC = "\u001B"

        /** What the panel is expected to paint once the chrome is gone. */
        const val REMOVED_LINE = "old line"
        const val ADDED_LINE = "new line"

        /**
         * What the Gateway actually sends: `tool.complete`'s `inline_diff` is the
         * TUI renderer's output (`tui_gateway/tool_progress.py:233-237` @
         * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`), so it carries the
         * `  ┊ review diff` banner (`agent/display.py:656-664`), the collapsed
         * `a/x → b/x` arrow line in place of the `---`/`+++` pair, a `@@` hunk
         * header, and truecolour SGR around every classified line — a tinted
         * background as well as a foreground on the `+`/`-` rows
         * (`agent/display.py:63-81,669-690`).
         *
         * The ESC byte is invisible in Compose, which is the whole bug: painted
         * raw, this fixture puts `[38;2;180;160;255m` on the screen.
         */
        val DIFF = listOf(
            "  ┊ review diff",
            "$KESC[38;2;180;160;255ma/notes.md → b/notes.md$KESC[0m",
            "$KESC[38;2;120;120;140m@@ -1,2 +1,2 @@$KESC[0m",
            "$KESC[38;2;255;255;255;48;2;60;10;10m-$REMOVED_LINE$KESC[0m",
            "$KESC[38;2;255;255;255;48;2;10;45;10m+$ADDED_LINE$KESC[0m",
        ).joinToString("\n")

        /**
         * `stripInlineDiffChrome(DIFF)` — Desktop's `view.inlineDiff`, which is
         * both what its body renders and what its `copy.file` hands over
         * (`fallback.tsx:375`, `fallback-model/index.ts:1254-1257`). File headers
         * survive the strip; only the escapes and the banner do not.
         */
        val CLEANED_DIFF = listOf(
            "a/notes.md → b/notes.md",
            "@@ -1,2 +1,2 @@",
            "-$REMOVED_LINE",
            "+$ADDED_LINE",
        ).joinToString("\n")
    }
}
