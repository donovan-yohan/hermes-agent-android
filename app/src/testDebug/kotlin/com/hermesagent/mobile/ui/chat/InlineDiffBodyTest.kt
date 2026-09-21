package com.hermesagent.mobile.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
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
import com.hermesagent.mobile.ui.theme.HermesTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The diff body's own treatment: a capped-height scroller, syntax ink inside it,
 * and a diff that comes from the *result* rather than the live side-channel.
 *
 * [ToolRowFidelityTest] pins the geometry and the text of an inline diff and
 * [InlineDiffPanelInkTest] pins its add/remove colour. Neither can see the three
 * things this file is for:
 *
 *  - that a `patch` result carrying only `{"success":true,"diff":"…"}` opens the
 *    panel at all — the bug `resolveEffectiveInlineDiff` exists to close;
 *  - that the body is a `max-h-[12rem]` box (`diff-lines.tsx:66-67,583-641` @
 *    `437116f9497c80d242ce034ff7f5d81dc277a337`) whose rows are still reachable
 *    by scrolling inside it;
 *  - that the change content is tokenised in the file's own language
 *    (`diff-lines.tsx:469-487`) without losing the diff's own ink underneath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class InlineDiffBodyTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var tokens: HermesTokens

    private var state by mutableStateOf(ChatUiState())

    private val clipboardText: String?
        get() = (
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    private fun tool(
        path: String = "notes.md",
        result: String? = null,
        inlineDiff: String? = null,
    ) = ToolActivity(
        id = "$SESSION-t1",
        label = "apply_patch",
        detail = "",
        state = ToolState.Done,
        elapsedSeconds = 1.0,
        toolName = "apply_patch",
        argsText = """{"path":"$path"}""",
        resultText = result,
        inlineDiff = inlineDiff,
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
            transcript = listOf<TranscriptEntry>(UserTurn("$SESSION-u1", "patch it", NOW)) + tools,
            isStreaming = false,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                tokens = HermesTheme.tokens
                ChatScreen(state = state, actions = ChatActions(), onOpenSettings = {})
            }
        }
        compose.waitForIdle()
    }

    /** Every string currently on screen. */
    private fun renderedText(): String = compose
        .onAllNodes(hasText("", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .joinToString("\n") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("\n") { it.text }
        }

    /**
     * One painted row's inks: the `Text`'s own colour, and the explicit runs
     * layered over it.
     *
     * Read through `GetTextLayoutResult` because the two arms paint differently:
     * a tokenised row carries a `SpanStyle` per run, while the plain coloured
     * diff sets the `Text`'s `color` and no spans at all — the same split as
     * `diff-lines.tsx:453-467` (Shiki paints the runs, the plain renderer sets
     * `DIFF_KIND_TEXT`).
     */
    private fun inkOf(tag: String): Pair<Color, List<Color>> {
        val laidOut = mutableListOf<TextLayoutResult>()
        val read = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
        assertTrue("no text layout to read for $tag", read != null && read(laidOut))
        val result = laidOut.first()
        return result.layoutInput.style.color to result.layoutInput.text.spanStyles.map { it.item.color }
    }

    /** Everything one painted row's `Text` says, so a token run cannot lose a character. */
    private fun paintedLine(tag: String): String {
        val laidOut = mutableListOf<TextLayoutResult>()
        val read = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
        assertTrue("no text layout to read for $tag", read != null && read(laidOut))
        return laidOut.first().layoutInput.text.text
    }

    private fun bodyHeight(): Float {
        val bounds = compose.onNodeWithTag(INLINE_DIFF_BODY_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        return (bounds.bottom - bounds.top).value
    }

    // ── The result-only diff ─────────────────────────────────────────────────

    @Test
    fun `a patch carried only in the result opens the panel instead of raw json`() {
        // `fallback.tsx:375-392` — the live side-channel is silent here, so the
        // only way to a panel is decoding the result and reading `diff`. Before
        // the resolver, this row fell through to the generic disclosure and
        // painted `{"success":true,"diff":"…"}` as JSON.
        launch(
            tool(
                result = """{"success":true,"diff":"--- a/notes.md\n+++ b/notes.md\n@@ -1 +1 @@\n-old line\n+new line"}""",
            ),
        )

        assertTrue(
            "the diff body must paint the change: ${renderedText()}",
            renderedText().contains("new line"),
        )
        assertTrue("the raw result must not be painted", !renderedText().contains("\"success\""))
        assertTrue("the hunk header is chrome", !renderedText().contains("@@"))
        // The header's own stats read the same resolved value.
        compose.onNodeWithText("+1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("\u22121", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a live side channel still wins over a result that also carries one`() {
        launch(
            tool(
                inlineDiff = "@@ -1 +1 @@\n+from the side channel",
                result = """{"diff":"@@ -1 +1 @@\n+from the result"}""",
            ),
        )

        val painted = renderedText()
        assertTrue(painted.contains("from the side channel"))
        assertTrue("the losing field must not paint: $painted", !painted.contains("from the result"))
    }

    // ── The capped-height body ───────────────────────────────────────────────

    @Test
    fun `a long diff body is capped at twelve rem and its last row is reachable by scrolling`() {
        // `diff-lines.tsx:66-67` — `max-h-[12rem]`, 192 dp. The clamp bounds the
        // row count (200 lines here), and the box is what keeps a capped payload
        // from being a wall: the tail has to be scrollable-into-view rather than
        // painted past the bottom of the screen.
        val diff = "@@ -1,200 +1,200 @@\n" + (0 until 200).joinToString("\n") { "+line $it" }
        launch(tool(inlineDiff = diff))

        val height = bodyHeight()
        assertTrue("the diff body must be capped at 192 dp, was $height", height <= 192f + 0.5f)
        assertTrue("and must actually be taller than that to be scrollable", height >= 191f)

        // The tail is reachable *inside* the box, which is the difference between
        // a scroller and a clip: the body consumes a vertical drag itself and
        // brings the last row into its own viewport. Driven through the body's
        // own scroll action rather than a gesture, because the drag a touch test
        // could make is one body-length and this payload is forty of them.
        val firstBefore = compose.onNodeWithTag(inlineDiffLineTag(0), useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top
        compose.onNodeWithTag(INLINE_DIFF_BODY_TAG, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, 1_000_000f) }
        compose.waitForIdle()

        val body = compose.onNodeWithTag(INLINE_DIFF_BODY_TAG, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val firstAfter = compose.onNodeWithTag(inlineDiffLineTag(0), useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top
        val last = compose.onNodeWithTag(inlineDiffLineTag(199), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue("the body must have scrolled its content: $firstBefore → $firstAfter", firstAfter < firstBefore)
        assertTrue(
            "the last row must land inside the body: row $last vs body $body",
            last.top >= body.top - 1.dp && last.bottom <= body.bottom + 1.dp,
        )
    }

    @Test
    fun `a short diff body takes only the height it needs`() {
        // `heightIn(max = …)` rather than a fixed height: a two-line diff must
        // not reserve 192 dp of a phone screen it does not need.
        launch(tool(inlineDiff = "@@ -1 +1 @@\n-old\n+new"))

        assertTrue("a short diff must not be padded to the cap, was ${bodyHeight()}", bodyHeight() < 192f)
    }

    // ── Syntax tokens ────────────────────────────────────────────────────────

    @Test
    fun `a kotlin diff tokenises its change content while keeping the diff ink`() {
        // `diff-lines.tsx:469-487` — Desktop hands the *content* to Shiki and
        // layers the add/remove tint on top, so one row reads as code and as a
        // change at once. The base ink of an unrecognised run is the diff's own
        // foreground, and a keyword takes `tokens.syntax.keyword`.
        launch(tool(path = "Notes.kt", inlineDiff = "@@ -1 +1 @@\n-val answer = 1\n+val answer = 42"))

        val runs = inkOf(inlineDiffLineTag(1)).second
        assertTrue("the added row must paint its keyword in the syntax ink", runs.contains(tokens.syntax.keyword))
        assertTrue("and keep the diff foreground for the rest", runs.contains(tokens.diffAddedForeground))
        assertTrue("a number takes its own ink", runs.contains(tokens.syntax.number))
    }

    @Test
    fun `a path with no lexing shape here paints the plain coloured diff`() {
        // `diff-lines.tsx:607-632` — Desktop falls back to the plain renderer
        // when it cannot highlight; an id this app carries no lexicon for is the
        // same answer, and never a wrong one.
        launch(tool(path = "notes.zzz", inlineDiff = "@@ -1 +1 @@\n-val answer = 1\n+val answer = 42"))

        val (baseInk, spans) = inkOf(inlineDiffLineTag(1))
        assertEquals("the plain renderer sets the diff ink on the Text itself", tokens.diffAddedForeground, baseInk)
        assertTrue("and invents no syntax run", spans.isEmpty())
    }

    @Test
    fun `highlighting never loses or duplicates a character of the line`() {
        // A token run that dropped a character would be a diff that no longer
        // says what the file says.
        launch(tool(path = "Notes.kt", inlineDiff = "@@ -1 +1 @@\n+val s = \"a b\" // note 42 x()"))

        // One changed line, so one painted row.
        assertEquals("val s = \"a b\" // note 42 x()", paintedLine(inlineDiffLineTag(0)))
        assertTrue(
            "the whole line must paint: ${renderedText()}",
            renderedText().contains("val s = \"a b\" // note 42 x()"),
        )
    }

    // ── Copy and the user's collapse choice ──────────────────────────────────

    @Test
    fun `copy still hands over the full diff that the body had to scroll`() {
        val diff = "@@ -1,200 +1,200 @@\n" + (0 until 200).joinToString("\n") { "+line $it" }
        launch(tool(inlineDiff = diff))

        compose.onNodeWithContentDescription("Copy file").performClick()
        compose.waitForIdle()

        val copied = clipboardText.orEmpty()
        assertTrue("Copy must carry the tail the body had to scroll to", copied.contains("line 199"))
        assertTrue("and the hunk header the body treats as chrome", copied.contains("@@"))
    }

    @Test
    fun `a user collapse survives the row being recomposed`() {
        // The default is open — Desktop opens a recognised diff
        // (`fallback.tsx:390-392`, `Boolean(inlineDiff)`) — and the user's choice
        // to close it is what must not be thrown away by a streamed delta
        // arriving under the same row id.
        launch(tool(inlineDiff = "@@ -1 +1 @@\n-old\n+new"))

        compose.onNodeWithTag(INLINE_DIFF_BODY_TAG, useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Tool Patched file, done").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(INLINE_DIFF_BODY_TAG, useUnmergedTree = true).assertDoesNotExist()

        // Same id, new value: what a live update looks like to this row.
        state = state.copy(transcript = state.transcript.map { entry ->
            if (entry is ToolActivity) entry.copy(elapsedSeconds = 2.0) else entry
        })
        compose.waitForIdle()

        compose.onNodeWithTag(INLINE_DIFF_BODY_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertTrue(
            "and a collapsed diff paints none of its rows: ${renderedText()}",
            !renderedText().contains("old") && !renderedText().contains("new"),
        )
    }

    private companion object {
        const val SESSION = "s-diff-body"
        const val NOW = 1_756_000_000_000L
    }
}
