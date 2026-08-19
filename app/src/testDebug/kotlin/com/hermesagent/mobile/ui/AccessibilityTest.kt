package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two accessibility claims this slice makes, measured rather than asserted
 * in prose: a streaming turn announces itself, and every shared control is big
 * enough to hit.
 *
 * Both are things a sighted developer on a mouse cannot notice, which is why
 * they need a gate rather than a review pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ChatUiState())

    // ── Streaming status ──────────────────────────────────────────────────

    @Test
    fun `a streaming turn is announced once as a polite live region`() {
        launchChat(streaming = true)

        compose.onNodeWithContentDescription(WORKING)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        // One node, not one per dot and not one per delta: the description is a
        // constant, so growing the reply cannot re-announce it.
        assertEquals(1, compose.countWithContentDescription(WORKING))
        state = chatState(streaming = true, markdown = "Still going, with a good deal more text now.")
        compose.waitForIdle()
        assertEquals(1, compose.countWithContentDescription(WORKING))
    }

    @Test
    fun `a settled turn announces nothing`() {
        launchChat(streaming = false)
        assertEquals(0, compose.countWithContentDescription(WORKING))
    }

    // ── Touch targets ─────────────────────────────────────────────────────

    @Test
    fun `every shared control meets the touch target floor`() {
        val floor = HermesSpacing().touchTarget
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Column {
                    SegmentedControl(
                        options = listOf("Light", "Dark"),
                        selected = "Dark",
                        label = { it },
                        onSelect = {},
                    )
                    PrimaryButton(label = "Connect", onClick = {})
                    TextButton(label = "Forget key", onClick = {})
                    QuietIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        onClick = {},
                    )
                }
            }
        }

        // Assert on the tappable node, which is what a finger has to find —
        // the label inside it is allowed to be as small as the type scale says.
        compose.onNodeWithContentDescription("Dark").assertHeightIsAtLeast(floor)
        compose.onNodeWithText("Connect").assertHeightIsAtLeast(floor)
        compose.onNodeWithText("Forget key").assertHeightIsAtLeast(floor)
        compose.onNodeWithContentDescription("Settings").assertHeightIsAtLeast(floor)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun launchChat(streaming: Boolean) {
        state = chatState(streaming, markdown = "Working on it.")
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(state = state, actions = ChatActions(), onOpenSettings = {})
            }
        }
        compose.waitForIdle()
    }

    private fun chatState(streaming: Boolean, markdown: String) = ChatUiState(
        activeSession = SessionSummary(
            id = SESSION,
            title = "Streaming turn",
            preview = "",
            lastActiveAtMillis = NOW,
        ),
        transcript = transcript(streaming, markdown),
        isStreaming = streaming,
    )

    private fun transcript(streaming: Boolean, markdown: String): List<TranscriptEntry> = listOf(
        AssistantTurn(id = "$SESSION-a1", markdown = markdown, atMillis = NOW, streaming = streaming),
    )

    private companion object {
        const val SESSION = "s-a11y"
        const val NOW = 1_755_600_000_000L
        const val WORKING = "Hermes is working"
    }
}

private fun ComposeContentTestRule.countWithContentDescription(description: String): Int =
    onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
