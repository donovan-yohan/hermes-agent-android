package com.hermesagent.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
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

    private fun chatState(transcript: List<TranscriptEntry>, streaming: Boolean) = ChatUiState(
        activeSession = SessionSummary(
            id = SESSION,
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

        grow(paragraphs = 80)
        compose.onNodeWithText("Paragraph 1 of the reply.").assertIsDisplayed()
    }

    @Test
    fun `landing on a session opens at the tail`() {
        launch(streamed(paragraphs = 60), streaming = false)
        compose.onNodeWithText("Paragraph 60 of the reply.").assertIsDisplayed()
    }

    private companion object {
        const val SESSION = "s-follow"
        const val NOW = 1_755_600_000_000L
    }
}
