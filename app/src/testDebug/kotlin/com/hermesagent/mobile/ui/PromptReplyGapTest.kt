package com.hermesagent.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptReplyGapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun `incoming prompt names replies between it and the previous pinned prompt`() {
        launch(listOf(
            UserTurn("first", FIRST, NOW),
            AssistantTurn("reply", response(), NOW),
            UserTurn("second", SECOND, NOW),
            AssistantTurn("tail", response(), NOW, streaming = true),
        ))
        compose.onNodeWithContentDescription("You said: $SECOND")
            .performCustomAccessibilityActionWithLabel("Return to prompt")
        scroll(-100f * compose.density.density)
        val cue = compose.onNodeWithContentDescription("Assistant replies between these user messages")
            .fetchSemanticsNode().boundsInRoot
        val outgoing = compose.onNodeWithTag("Current prompt bubble", true).fetchSemanticsNode().boundsInRoot
        val incoming = compose.onNode(promptContentDescription("You said: $SECOND")).fetchSemanticsNode().boundsInRoot
        assertTrue("cue stays below the outgoing prompt", cue.top >= outgoing.bottom)
        assertTrue("cue stays above the incoming prompt", cue.bottom <= incoming.top)
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-reply-gap.png") }
        scroll(180f * compose.density.density)
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(
            "Assistant replies between these user messages",
        )).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `consecutive user messages do not invent intervening replies`() {
        launch(listOf(
            UserTurn("first", FIRST, NOW), UserTurn("second", SECOND, NOW),
            AssistantTurn("tail", response(), NOW),
        ))
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(
            "Assistant replies between these user messages",
        )).fetchSemanticsNodes().isEmpty())
    }

    private fun scroll(pixels: Float) {
        compose.onNodeWithTag("Transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
        compose.waitForIdle()
    }

    private fun launch(entries: List<TranscriptEntry>) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(ChatUiState(
                    activeSession = SessionSummary("gap", "Synthetic chat", "", NOW, status = SessionStatus.Working),
                    transcript = entries,
                ), ChatActions(), {})
            }
        }
        compose.waitForIdle()
    }

    private fun response() = (1..30).joinToString("\n\n") { "Synthetic response paragraph $it." }

    private companion object {
        const val NOW = 1_755_600_000_000L
        const val FIRST = "First synthetic request\nA second line\nA third line\nA fourth line\nA fifth line"
        const val SECOND = "Second synthetic request\nContinue the example"
    }
}
