package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SessionOpenFailureLayoutTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(failedState())
    private var retries = 0

    @Test
    fun `failed open stays below populated viewport and retry clears it without losing draft`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(onRetrySessionOpen = {
                        retries++
                        state = state.copy(sessionOpen = SessionOpenState.Idle)
                    }),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()

        val failure = compose.onNodeWithTag("Session open failure").fetchSemanticsNode().boundsInWindow
        val composer = compose.onNodeWithContentDescription("Message Hermes").fetchSemanticsNode().boundsInWindow
        assertTrue("failure is a transcript-pane footer above composer", failure.bottom <= composer.top)
        compose.onNodeWithText("Paragraph 1").performScrollTo()
        compose.waitForIdle()
        val jump = compose.onNodeWithContentDescription("Scroll to bottom").fetchSemanticsNode().boundsInWindow
        assertTrue("jump control remains in the viewport above failure footer", jump.bottom <= failure.top)

        compose.onNodeWithContentDescription("Retry opening the session").performClick()
        compose.waitForIdle()
        assertEquals(1, retries)
        compose.onNodeWithTag("Session open failure").assertDoesNotExist()
        compose.onNodeWithContentDescription("Message Hermes").assertTextEquals("draft survives retry")
    }

    private fun failedState() = ChatUiState(
        activeSession = SessionSummary(SESSION, "Failed open", "", NOW, status = SessionStatus.Idle),
        activeSessionId = SESSION,
        draft = "draft survives retry",
        transcript = transcript(),
        sessionOpen = SessionOpenState.Failed(SESSION, SESSION_OPEN_FAILED_COPY, "Gateway unavailable"),
    )

    private fun transcript(): List<TranscriptEntry> = listOf(
        UserTurn("$SESSION-user", "The current prompt", NOW),
        AssistantTurn("$SESSION-answer", (1..90).joinToString("\n\n") { "Paragraph $it" }, NOW),
    )

    private companion object {
        const val SESSION = "open-failure"
        const val NOW = 1_755_600_000_000L
    }
}
