package com.hermesagent.mobile.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp")
class PromptCollapseTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `explicit fifth line determines pinned bubble width`() {
        assertInlineAndPinnedGeometry("i\ni\ni\ni\nA much wider fifth line")
    }

    @Test
    fun `one character prompt keeps bubble geometry inside 48dp action`() {
        val prompt = "a"
        launch(prompt)

        val action = currentPromptAction(prompt)
        val pinned = currentPromptBubble()
        println("PROMPT_GEOMETRY one-char action=$action bubble=$pinned")
        val density = compose.density.density
        assertTrue(action.width >= 48f * density)
        assertTrue(action.height >= 48f * density)
        assertEquals("the action must not move the bubble's right edge", action.right, pinned.right, density)

        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()
        val inline = inlinePrompt(prompt)
        assertEquals(inline.width, pinned.width, density)
        assertEquals(inline.right, pinned.right, density)
    }

    @Test
    fun `short prompt keeps actual pinned width`() {
        assertInlineAndPinnedGeometry("Short prompt")
    }

    @Test
    fun `multi paragraph prompt keeps actual pinned width`() {
        assertInlineAndPinnedGeometry("First paragraph.\n\nSecond paragraph with a wider sentence.")
    }

    @Test
    fun `long wrapping prompt keeps actual pinned width`() {
        assertInlineAndPinnedGeometry(
            "Compare the visible response context, record the next action, and keep the transcript readable while the response continues.",
        )
    }

    @Test
    fun `show earlier row preserves prompt source geometry and return target`() {
        val prompt = (1..9).joinToString("\n") { "Paged prompt line $it" }
        launch(prompt, canShowEarlier = true)

        val compact = currentPromptBubble()
        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()

        // The leading pagination control is a LazyColumn item, not a transcript
        // entry. Returning to the prompt must account for that extra row.
        compose.onNodeWithText("Show earlier messages").assertIsNotDisplayed()
        val inline = inlinePrompt(prompt)

        val transition = scrollUntilCurrentPromptAppears(prompt)
        assertEquals(compact.height, transition.height, geometryTolerance())
        assertEquals(inline.height, transition.height, geometryTolerance())
        assertEquals(inline.width, transition.width, geometryTolerance())
        assertEquals(inline.right, transition.right, geometryTolerance())
    }

    @Test
    fun `long prompt retains full bubble height without a four line cut`() {
        val prompt = (1..9).joinToString("\n") { "Prompt line $it with stable width" }
        launch(prompt)
        val pinned = currentPromptBubble()
        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()
        val inline = inlinePrompt(prompt)
        assertEquals(inline.height, pinned.height, geometryTolerance())
        val transition = scrollUntilCurrentPromptAppears(prompt)
        assertEquals(inline.height, transition.height, geometryTolerance())
        scrollBy(12f * compose.density.density)
        assertEquals(inline.height, currentPromptBubble().height, geometryTolerance())
    }

    @Test
    fun `incoming prompt pushes outgoing bubble instead of scrolling under it`() {
        val first = "First synthetic prompt\nSecond line\nThird line\nFourth line\nFifth line"
        val second = "Second synthetic prompt"
        launch(transcript = listOf(
            UserTurn("u1", first, NOW),
            AssistantTurn("a1", longReply("First", 30), NOW),
            UserTurn("u2", second, NOW),
            AssistantTurn("a2", longReply("Second", 30), NOW, streaming = true),
        ))
        compose.onNodeWithContentDescription("Current prompt: $second").performClick()
        compose.waitForIdle()
        scrollBy(-100f * compose.density.density)
        val before = currentPromptBubble()
        val incomingBefore = inlinePrompt(second)
        assertTrue("outgoing must be above incoming, not cover its text", before.bottom <= incomingBefore.top)
        scrollBy(12f * compose.density.density)
        val after = currentPromptBubble()
        val incomingAfter = inlinePrompt(second)
        assertTrue(after.bottom <= incomingAfter.top)
        assertEquals("both bubbles move together", incomingAfter.top - incomingBefore.top, after.bottom - before.bottom, geometryTolerance() + 1f)
        assertTrue(after.bottom < before.bottom)
        assertEquals(0, compose.onAllNodes(hasContentDescription("You said: $first")).fetchSemanticsNodes().size)
        scrollBy(110f * compose.density.density)
        assertEquals(0, compose.onAllNodes(hasContentDescription("Current prompt: $first")).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasContentDescription("Current prompt: $second")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("You said: $second")).fetchSemanticsNodes().size)
    }

    @Test
    fun `assistant successor keeps one pixel prompt continuity`() {
        assertOnePixelSuccessorContinuity(AssistantTurn("next", "Done.", NOW))
    }

    @Test
    fun `tool successor keeps one pixel prompt continuity`() {
        assertOnePixelSuccessorContinuity(
            ToolActivity("next", "read_file", "", ToolState.Done, toolName = "read_file"),
        )
    }

    @Test
    fun `reasoning successor keeps one pixel prompt continuity`() {
        assertOnePixelSuccessorContinuity(
            ReasoningActivity("next", "", ToolState.Done, elapsedSeconds = 0.2),
        )
    }

    @Test
    fun `transition boundary has one accessible owner`() {
        val prompt = (1..7).joinToString("\n") { "Boundary line $it" }
        launch(prompt)
        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()

        repeat(240) {
            val inlineCount = compose.onAllNodes(hasContentDescription("You said: $prompt")).fetchSemanticsNodes().size
            val pinnedCount = compose.onAllNodes(hasContentDescription("Current prompt: $prompt")).fetchSemanticsNodes().size
            assertEquals("exactly one owner at scroll pixel $it", 1, inlineCount + pinnedCount)
            if (pinnedCount == 1) return
            scrollBy(1f)
        }
        throw AssertionError("prompt never entered the pinned transition")
    }

    @Test
    fun `next prompt owns handoff without duplicate pinned chrome`() {
        val first = "First prompt"
        val second = "Second prompt"
        launch(
            transcript = listOf(
                UserTurn("u1", first, NOW),
                AssistantTurn("a1", longReply("First", 30), NOW),
                UserTurn("u2", second, NOW),
                AssistantTurn("a2", longReply("Second", 30), NOW, streaming = true),
            ),
        )

        compose.onNodeWithContentDescription("Current prompt: $second").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("You said: $second").performScrollTo()
        compose.waitForIdle()

        assertEquals(0, compose.onAllNodes(hasContentDescription("Current prompt: $first")).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasContentDescription("Current prompt: $second")).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodes(hasContentDescription("You said: $second")).fetchSemanticsNodes().size)
    }

    private fun assertInlineAndPinnedGeometry(prompt: String) {
        launch(prompt)
        val pinned = currentPromptBubble()
        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()
        val inline = inlinePrompt(prompt)
        println("PROMPT_GEOMETRY parity prompt=${prompt.replace("\n", "\\n")} inline=$inline pinned=$pinned")

        assertEquals("inline and pinned bubbles must have the same actual width", inline.width, pinned.width, geometryTolerance())
        assertEquals("inline and pinned bubbles must keep the same right edge", inline.right, pinned.right, geometryTolerance())
    }

    private fun assertOnePixelSuccessorContinuity(successor: TranscriptEntry) {
        val prompt = (1..9).joinToString("\n") { "Continuity line $it" }
        launch(
            transcript = listOf(
                UserTurn("u", prompt, NOW),
                successor,
                AssistantTurn("tail", longReply("Tail", 80), NOW, streaming = true),
            ),
        )
        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()

        val transcriptTop = compose.onNodeWithTag("Transcript").fetchSemanticsNode().boundsInRoot.top
        val successorBounds = compose.onNodeWithTag("Transcript item ${successor.id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        scrollBy((successorBounds.bottom - transcriptTop - 1f).coerceAtLeast(0f))
        val before = currentPromptBubble()
        scrollBy(1f)
        val after = currentPromptBubble()
        println("PROMPT_GEOMETRY continuity type=${successor::class.simpleName} before=$before after=$after")

        val tolerance = geometryTolerance() + 1f
        assertTrue("left jumped across ${successor::class.simpleName}: $before -> $after", abs(after.left - before.left) <= tolerance)
        assertTrue("top jumped across ${successor::class.simpleName}: $before -> $after", abs(after.top - before.top) <= tolerance)
        assertTrue("right jumped across ${successor::class.simpleName}: $before -> $after", abs(after.right - before.right) <= tolerance)
        assertTrue("bottom jumped across ${successor::class.simpleName}: $before -> $after", abs(after.bottom - before.bottom) <= tolerance)
    }

    private fun scrollUntilCurrentPromptAppears(prompt: String): Rect {
        repeat(300) {
            if (compose.onAllNodes(hasContentDescription("Current prompt: $prompt")).fetchSemanticsNodes().isNotEmpty()) {
                scrollBy(8f * compose.density.density)
                return currentPromptBubble()
            }
            scrollBy(1f)
        }
        throw AssertionError("prompt never entered the pinned transition")
    }

    private fun currentPromptBubble(): Rect = compose
        .onNodeWithTag("Current prompt bubble", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun currentPromptAction(prompt: String): Rect = compose
        .onNodeWithContentDescription("Current prompt: $prompt")
        .fetchSemanticsNode().boundsInRoot

    private fun inlinePrompt(prompt: String): Rect = compose
        .onNodeWithContentDescription("You said: $prompt")
        .fetchSemanticsNode().boundsInRoot

    private fun scrollBy(pixels: Float) {
        compose.onNodeWithTag("Transcript").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, pixels)
        }
        compose.waitForIdle()
    }

    private fun launch(prompt: String, canShowEarlier: Boolean = false) {
        launch(
            transcript = listOf(
                UserTurn("u", prompt, NOW),
                AssistantTurn("a", longReply("Response", 80), NOW, streaming = true),
            ),
            canShowEarlier = canShowEarlier,
        )
    }

    private fun launch(transcript: List<TranscriptEntry>, canShowEarlier: Boolean = false) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        activeSession = SessionSummary(
                            id = "prompt-collapse",
                            title = "Prompt collapse",
                            preview = "",
                            lastActiveAtMillis = NOW,
                            status = SessionStatus.Working,
                        ),
                        transcript = transcript,
                        canShowEarlierMessages = canShowEarlier,
                        isStreaming = true,
                    ),
                    actions = ChatActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun longReply(prefix: String, paragraphs: Int): String =
        (1..paragraphs).joinToString("\n\n") { "$prefix paragraph $it." }

    private fun assertRectNear(expected: Rect, actual: Rect) {
        val tolerance = geometryTolerance()
        assertTrue("left differs: $expected vs $actual", abs(expected.left - actual.left) <= tolerance)
        assertTrue("top differs: $expected vs $actual", abs(expected.top - actual.top) <= tolerance)
        assertTrue("right differs: $expected vs $actual", abs(expected.right - actual.right) <= tolerance)
        assertTrue("bottom differs: $expected vs $actual", abs(expected.bottom - actual.bottom) <= tolerance)
    }

    private fun geometryTolerance(): Float = compose.density.density

    private companion object {
        const val NOW = 1_755_600_000_000L
    }
}
