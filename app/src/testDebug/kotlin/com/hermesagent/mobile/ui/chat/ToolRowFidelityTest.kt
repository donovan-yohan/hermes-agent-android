package com.hermesagent.mobile.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
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
        state: ToolState = ToolState.Done,
        args: String? = null,
        result: String? = null,
        detail: String = "",
    ) = ToolActivity(
        id = "$SESSION-t1",
        label = toolName,
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
        compose.onNodeWithContentDescription("Tool Todo, stopped").performScrollTo().assertIsDisplayed()
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

        /** The JSON escape for `ESC`, so no control byte sits in this source. */
        const val ESC = "\\u001B"
        val TOUCH_FLOOR = 48.dp
    }
}
