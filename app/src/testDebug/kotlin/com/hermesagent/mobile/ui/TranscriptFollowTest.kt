package com.hermesagent.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import com.hermesagent.mobile.data.session.AssistantTurn
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
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tail following, driven by state rather than by the demo engine.
 *
 * The regression this pins: a streamed delta rewrites the *same* transcript
 * entry under the same id, so following the tail on `(id, count)` alone stops
 * after the very first delta and the rest of the reply grows below the
 * viewport. The turn here is one assistant block, long enough to outgrow the
 * screen on its own, extended in place — which is exactly the shape that used
 * to scroll once and then stop.
 *
 * State is pushed by hand so nothing depends on timing: each step is a new
 * [ChatUiState], recomposition is awaited, and the assertions are on what is
 * *displayed*, not on what exists in the tree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptFollowTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ChatUiState())

    private fun launch(transcript: List<TranscriptEntry>, streaming: Boolean = true) {
        state = chatState(transcript, streaming)
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(state = state, actions = ChatActions(), onOpenSettings = {})
            }
        }
        compose.waitForIdle()
    }

    private fun chatState(transcript: List<TranscriptEntry>, streaming: Boolean, sessionId: String = SESSION) = ChatUiState(
        activeSession = SessionSummary(
            id = sessionId,
            title = "Streaming turn",
            preview = "",
            lastActiveAtMillis = NOW,
            status = if (streaming) SessionStatus.Working else SessionStatus.Idle,
        ),
        transcript = transcript,
        isStreaming = streaming,
    )

    /** One assistant block that grows in place, as a real stream does. */
    private fun streamed(paragraphs: Int): List<TranscriptEntry> = listOf(
        UserTurn(id = "$SESSION-u1", text = "tell me something long", atMillis = NOW),
        AssistantTurn(
            id = "$SESSION-a1",
            markdown = (1..paragraphs).joinToString("\n\n") { "Paragraph $it of the reply." },
            atMillis = NOW,
            streaming = true,
        ),
    )

    private fun grow(paragraphs: Int) {
        state = chatState(streamed(paragraphs), streaming = true)
        compose.waitForIdle()
    }

    @Test
    fun `a delta that grows the last block in place keeps the tail on screen`() {
        launch(streamed(paragraphs = 2))
        compose.onNodeWithText("Paragraph 2 of the reply.").assertIsDisplayed()

        // Same entry id, same entry count, far more content: the old key never
        // changed, so the list stayed where the first delta left it.
        grow(paragraphs = 40)
        compose.onNodeWithText("Paragraph 40 of the reply.").assertIsDisplayed()

        // More than 24 viewports tall. The old fixed 24-scroll cap stopped
        // above this tail even though the reader had not left it.
        grow(paragraphs = 750)
        compose.onNodeWithText("Paragraph 750 of the reply.").assertIsDisplayed()
    }

    @Test
    fun `scrolling away from the tail stops the follow`() {
        launch(streamed(paragraphs = 40))
        compose.onNodeWithText("Paragraph 40 of the reply.").assertIsDisplayed()

        // Read something further up, then let the turn keep streaming.
        compose.onNodeWithText("Paragraph 1 of the reply.").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Scroll to bottom").assertIsDisplayed()

        grow(paragraphs = 80)
        compose.onNodeWithText("Paragraph 1 of the reply.").assertIsDisplayed()
        compose.onNodeWithText("New activity").assertIsDisplayed()
        compose.onNodeWithContentDescription("New activity. Scroll to bottom")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Paragraph 80 of the reply.").assertIsDisplayed()
    }

    /**
     * A terminal row whose output is still arriving, as the tail of the turn.
     *
     * Desktop parks stdout in its own 80 px box that tails only while the reader
     * is already at the bottom (`components/chat/terminal-output.tsx:14,45-52` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). Android has no second
     * scroller — the transcript's own follow discipline is that rule — so these
     * two cases are what prove the rule still holds when the thing growing is a
     * tool payload rather than prose.
     */
    private fun runningCommand(lines: Int): List<TranscriptEntry> = listOf(
        UserTurn(id = "$SESSION-u1", text = "run the build", atMillis = NOW),
        ToolActivity(
            id = "$SESSION-t1",
            label = "terminal",
            detail = "",
            state = ToolState.Running,
            toolName = "terminal",
            argsText = """{"command":"./gradlew check"}""",
            resultText = """{"stdout":"${(1..lines).joinToString("\\n") { "build line $it" }}"}""",
            startedAtMillis = NOW,
        ),
    )

    @Test
    fun `growing tool output follows the tail for a reader who is already there`() {
        launch(runningCommand(lines = 4))
        compose.onNodeWithContentDescription("Tool Running ./gradlew check, running").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("build line 4", substring = true).assertIsDisplayed()

        state = chatState(runningCommand(lines = 60), streaming = true)
        compose.waitForIdle()
        compose.onNodeWithText("build line 60", substring = true).assertIsDisplayed()
    }

    @Test
    fun `growing tool output never yanks a reader who has scrolled up`() {
        launch(runningCommand(lines = 60))
        compose.onNodeWithContentDescription("Tool Running ./gradlew check, running").performClick()
        compose.waitForIdle()

        // Back to the head of the command's output, which is what a reader does
        // when they want to know why the build started failing.
        compose.onNodeWithContentDescription("Tool Running ./gradlew check, running").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Scroll to bottom").assertIsDisplayed()

        state = chatState(runningCommand(lines = 140), streaming = true)
        compose.waitForIdle()

        // Still at the head of the output, not dragged to the newest build line.
        compose.onNodeWithContentDescription("Tool Running ./gradlew check, running").assertIsDisplayed()
        compose.onNodeWithText("New activity").assertIsDisplayed()
    }

    @Test
    fun `landing on a session opens at the tail`() {
        launch(streamed(paragraphs = 60), streaming = false)
        compose.onNodeWithText("Paragraph 60 of the reply.").assertIsDisplayed()
    }

    @Test
    fun `history arriving after the empty first layout opens at the tail`() {
        launch(emptyList(), streaming = false)

        state = chatState(streamed(paragraphs = 60), streaming = false)
        compose.waitForIdle()

        compose.onNodeWithText("Paragraph 60 of the reply.").assertIsDisplayed()
    }

    @Test
    fun `pinned prompt follows visible turn and excludes image references`() {
        val first = "first prompt"
        val second = "second prompt"
        fun long(prefix: String) = (1..80).joinToString("\n\n") { "$prefix paragraph $it." }
        launch(listOf(
            UserTurn("u1", first, NOW), AssistantTurn("a1", long("first"), NOW),
            UserTurn("u2", "$second\n@image:/synthetic/not-shown.png", NOW),
            AssistantTurn("a2", long("second"), NOW, streaming = true),
        ))
        compose.onNodeWithContentDescription("Current prompt: $second").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("You said: $second").assertIsDisplayed()
        compose.onNodeWithText("first paragraph 1.").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Current prompt: $first").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("You said: $first").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("@image:/synthetic/not-shown.png"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `pin is hidden while source is visible and visible at response tail`() {
        launch(listOf(UserTurn("u", "visible source", NOW)), streaming = false)
        compose.onNodeWithContentDescription("You said: visible source").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Current prompt: visible source")).fetchSemanticsNodes().size)

        state = chatState(streamed(80), streaming = true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Current prompt: tell me something long").assertIsDisplayed()
    }

    @Test
    fun `pin return disarms follow across later streaming growth`() {
        val prompt = "return to this exact prompt"
        state = chatState(listOf(UserTurn("u", prompt, NOW), AssistantTurn("a", (1..80).joinToString("\n\n") { "Initial $it." }, NOW, streaming = true)), true)
        compose.setContent { HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) { ChatScreen(state, ChatActions(), {}) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("You said: $prompt").assertIsDisplayed()
        state = chatState(listOf(UserTurn("u", prompt, NOW), AssistantTurn("a", (1..160).joinToString("\n\n") { "Later $it." }, NOW, streaming = true)), true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("You said: $prompt").assertIsDisplayed()
    }

    @Test
    fun `session switch and delayed history reset the prompt identity`() {
        launch(streamed(80))
        compose.onNodeWithContentDescription("Current prompt: tell me something long").assertIsDisplayed()
        state = chatState(emptyList(), false, "new-session")
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Current prompt: tell me something long")).fetchSemanticsNodes().size)

        // The new session's history may arrive after the empty first frame.
        // Its pin must resolve its own stable id, never the prior session's.
        val replacement = "new session delayed prompt"
        state = chatState(
            listOf(
                UserTurn("new-u", replacement, NOW),
                AssistantTurn("new-a", (1..80).joinToString("\n\n") { "New reply $it." }, NOW, streaming = true),
            ),
            true,
            "new-session",
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Current prompt: $replacement").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("You said: $replacement").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasContentDescription("You said: tell me something long")).fetchSemanticsNodes().size)
    }

    @Test
    fun `attachment only user prompt omits pin`() {
        launch(
            listOf(
                UserTurn("image", "@image:/synthetic/only.png", NOW),
                AssistantTurn("reply", (1..80).joinToString("\n\n") { "Reply $it." }, NOW, streaming = true),
            ),
        )
        assertEquals(0, compose.onAllNodes(hasContentDescription("Current prompt: @image:/synthetic/only.png")).fetchSemanticsNodes().size)
    }

    @Test
    fun `pinned prompt shares the transcript scroll action`() {
        launch(streamed(80))
        val pinned = compose.onNodeWithContentDescription("Current prompt: tell me something long")
            .assertIsDisplayed()
            .fetchSemanticsNode()

        assertTrue(pinned.config.contains(SemanticsActions.ScrollBy))
    }

    private companion object {
        const val SESSION = "s-follow"
        const val NOW = 1_755_600_000_000L
    }
}
