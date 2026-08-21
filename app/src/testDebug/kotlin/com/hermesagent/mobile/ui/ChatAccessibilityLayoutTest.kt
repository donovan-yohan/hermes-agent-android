package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Accessibility and layout contracts that only appear in Compose semantics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h700dp")
class ChatAccessibilityLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a user bubble is one spoken node with no duplicate readable child`() {
        launch(transcript = listOf(UserTurn("u1", USER_TEXT, NOW)))

        compose.onNodeWithContentDescription("You said: $USER_TEXT").assertIsDisplayed()

        assertEquals(1, compose.nodesWithContentDescription("You said: $USER_TEXT").size)
        // The visual Text leaf is deliberately silent in *both* trees. The
        // parent keeps its label (and any future descendant action semantics),
        // rather than clearing the whole bubble and losing those actions.
        assertEquals(0, compose.nodesWithText(USER_TEXT).size)
        assertEquals(0, compose.nodesWithText(USER_TEXT, useUnmergedTree = true).size)
    }

    @Test
    fun `a streaming assistant turn announces once without reading deltas`() {
        launch(transcript = listOf(AssistantTurn("a1", "partial reply", NOW, streaming = true)))

        compose.onNodeWithContentDescription("Hermes started replying").assertIsDisplayed()
        assertEquals(1, compose.nodesWithContentDescription("Hermes started replying").size)
    }

    @Test
    fun `wide chat uses the persistent rail and respects navigation insets`() {
        launch(
            sessionRows = listOf(SessionListRow.Row(activeSession())),
            wideRailInsets = { WindowInsets(bottom = WIDE_RAIL_INSET_PX) },
        )

        val sessions = compose.onNodeWithText("SESSIONS").fetchSemanticsNode()
        val newSession = compose.onNodeWithContentDescription("New session").fetchSemanticsNode()

        // The controls are in the rail, left of the 300dp content
        // boundary. Their presence also proves this isn't the compact drawer.
        assertTrue(newSession.boundsInRoot.center.x < railBoundaryPx())
        assertTrue(sessions.boundsInRoot.right <= railBoundaryPx())
        assertFalse(compose.nodesWithContentDescription("Open sessions").isNotEmpty())

        val rail = compose.onNodeWithTag("Wide sessions rail").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("Session list").fetchSemanticsNode().boundsInRoot
        val tolerance = geometryTolerancePx()

        assertEquals("the rail surface remains 300dp wide", railBoundaryPx(), rail.width, tolerance)
        assertTrue("the session list must sit above the injected navigation inset", rail.bottom - list.bottom >= WIDE_RAIL_INSET_PX - tolerance)
        assertTrue("the session list stays within the rail surface", list.bottom <= rail.bottom + tolerance)
    }

    @Test
    fun `compact drawer ignores the wide rail navigation inset`() {
        var injectWideRailInset by mutableStateOf(false)
        launch(
            sessionRows = listOf(SessionListRow.Row(activeSession())),
            modifier = Modifier.width(411.dp).fillMaxHeight(),
            wideRailInsets = {
                if (injectWideRailInset) WindowInsets(bottom = WIDE_RAIL_INSET_PX) else WindowInsets()
            },
        )
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.waitForIdle()
        val baselineList = compose.onNodeWithTag("Session list").fetchSemanticsNode().boundsInRoot

        injectWideRailInset = true
        compose.waitForIdle()
        val injectedList = compose.onNodeWithTag("Session list").fetchSemanticsNode().boundsInRoot

        assertEquals(
            "the compact drawer must not inherit wide-rail-only bottom padding",
            baselineList.bottom,
            injectedList.bottom,
            geometryTolerancePx(),
        )
    }

    private fun launch(
        transcript: List<TranscriptEntry> = emptyList(),
        sessionRows: List<SessionListRow> = emptyList(),
        modifier: Modifier = Modifier,
        wideRailInsets: (() -> WindowInsets)? = null,
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        activeSession = activeSession(),
                        transcript = transcript,
                        sessionRows = sessionRows,
                    ),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    modifier = modifier,
                    wideRailInsets = wideRailInsets?.invoke() ?: WindowInsets.navigationBars,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun railBoundaryPx(): Float = 300 * compose.density.density

    private fun geometryTolerancePx(): Float = compose.density.density

    private fun activeSession() = SessionSummary(
        id = "s-layout",
        title = "Layout session",
        preview = "",
        lastActiveAtMillis = NOW,
    )

    private fun ComposeContentTestRule.nodesWithContentDescription(description: String) =
        onAllNodes(hasContentDescription(description)).fetchSemanticsNodes()

    private fun ComposeContentTestRule.nodesWithText(text: String, useUnmergedTree: Boolean = false) =
        onAllNodes(hasText(text), useUnmergedTree = useUnmergedTree).fetchSemanticsNodes()

    private companion object {
        const val NOW = 1_755_600_000_000L
        const val USER_TEXT = "One readable user message"
        const val WIDE_RAIL_INSET_PX = 40
    }
}
