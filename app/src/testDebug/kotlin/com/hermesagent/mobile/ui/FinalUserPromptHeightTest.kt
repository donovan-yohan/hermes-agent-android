package com.hermesagent.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.robolectric.annotation.GraphicsMode
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalUserPromptHeightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val prompt = (1..60).joinToString("\n") { "Synthetic prompt line $it" }
    private val state = mutableStateOf(fixture(prompt))

    @Test fun `final pinned prompt has viewport cap fade and single full accessible owner`() {
        launch()
        val viewport = compose.onNodeWithTag("Transcript").fetchSemanticsNode().boundsInRoot

        val bubble = compose.onNodeWithTag("Current prompt bubble", true).fetchSemanticsNode().boundsInRoot
        assertTrue("long prompt must leave response space", bubble.height <= viewport.height * .4f)
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
        assertEquals(1, compose.onAllNodes(hasContentDescription("You said: $prompt")).fetchSemanticsNodes().size)
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-collapsed.png") }
    }

    @Test fun `tap expands and outside tap preserves composer action while collapsing`() {
        launch()
        val owner = compose.onNodeWithContentDescription("You said: $prompt")
        val before = owner.fetchSemanticsNode().boundsInRoot.height
        owner.performClick()
        assertTrue(owner.fetchSemanticsNode().boundsInRoot.height > before)
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-expanded.png") }
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { click() }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
        compose.onNodeWithContentDescription("Message Hermes").assertIsFocused()
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-outside-collapse.png") }
    }

    @Test fun `short final prompt has no fade or disclosure action`() {
        state.value = fixture("short")
        launch()
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        compose.onNodeWithContentDescription("You said: short").assertExists()
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-short.png") }
    }

    @Test fun `session and message replacement reset expansion`() {
        launch()
        compose.onNodeWithContentDescription("You said: $prompt").performClick()
        compose.runOnIdle { state.value = fixture(prompt, "height-session", "other-message") }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
        compose.onNodeWithContentDescription("You said: $prompt").performClick()
        compose.runOnIdle { state.value = fixture(prompt, "third-session", "other-message") }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
    }

    @Test fun `drag on collapsed prompt scrolls transcript without expanding`() {
        launch()
        val before = compose.onNodeWithTag("Transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithContentDescription("You said: $prompt").performTouchInput { swipeDown() }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
        val after = compose.onNodeWithTag("Transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("drag must reach the real transcript", after < before)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun `expanded body scrolls and inline handoff keeps disclosure with one owner`() {
        launch()
        val owner = compose.onNodeWithContentDescription("You said: $prompt")
        owner.performClick()
        owner.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        compose.waitForIdle()
        assertTrue(owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        owner.performCustomAccessibilityActionWithLabel("Return to prompt")
        compose.onNodeWithTag("Inline final prompt").assertIsDisplayed()
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        assertEquals(1, compose.onAllNodes(hasContentDescription("You said: $prompt")).fetchSemanticsNodes().size)
        owner.performClick()
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
    }

    @Test @Config(qualifiers = "w411dp-h400dp")
    fun `small viewport scales cap rather than keeping a fixed tall prompt`() {
        launch()
        val viewport = compose.onNodeWithTag("Transcript").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("Current prompt bubble", true).fetchSemanticsNode().boundsInRoot
        assertTrue(body.height <= viewport.height * .35f + 20f * compose.density.density)
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-small-collapsed.png") }
    }

    @Test fun `outside long press and drag do not collapse disclosure`() {
        launch()
        compose.onNodeWithContentDescription("You said: $prompt").performClick()
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { longClick() }
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
    }

    private fun launch() {
        compose.setContent { HermesTheme(AppearanceSelection()) { ChatScreen(state.value, ChatActions(), {}) } }
        compose.waitForIdle()
    }

    private fun fixture(body: String, session: String = "height-session", message: String = "height-user") = ChatUiState(
        activeSession = SessionSummary(id = session, title = "Synthetic chat", preview = "", lastActiveAtMillis = 1_700_000_000_000L, status = SessionStatus.Working),
        transcript = listOf(UserTurn(message, body, 1_700_000_000_000L),
            AssistantTurn("reply", (1..80).joinToString("\n\n") { "Synthetic response paragraph $it." }, 1_700_000_000_000L, streaming = true)),
        isStreaming = true,
    )
}
