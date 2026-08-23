package com.hermesagent.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.chat.MarkdownTableScrollerTag
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Markdown table rendering, driven through the live [ChatScreen].
 *
 * The regression pinned here: a pipe table used to collapse into paragraph
 * text with literal `|` and `---` separators painted on screen. Assertions run
 * against *displayed text*, so raw syntax reaching the user is what fails.
 *
 * The scroll contract (the block owns a horizontal scroller, like code fences)
 * is asserted structurally rather than by swiping: Robolectric's fake font
 * metrics render any constructible token narrow enough to fit the window, so
 * an overflow choreography would measure nothing real. Column distribution
 * under a width budget is covered deterministically by [TableSizingTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class TranscriptTableViewTest {

    @get:Rule
    val compose = createComposeRule()

    private fun launch(markdown: String) {
        val state = ChatUiState(
            activeSession = SessionSummary(
                id = SESSION,
                title = "Table rendering",
                preview = "",
                lastActiveAtMillis = NOW,
                status = SessionStatus.Idle,
            ),
            transcript = listOf(
                AssistantTurn(id = "$SESSION-a1", markdown = markdown, atMillis = NOW),
            ),
            isStreaming = false,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(state = state, actions = ChatActions(), onOpenSettings = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a table renders as a grid instead of raw pipes`() {
        launch(
            """
            | Layer | State |
            |---|---|
            | models | portable |
            | gateway | rewrite |
            """.trimIndent(),
        )

        compose.onNodeWithText("Layer").assertIsDisplayed()
        compose.onNodeWithText("State").assertIsDisplayed()
        compose.onNodeWithText("portable").assertIsDisplayed()
        compose.onNodeWithText("rewrite").assertIsDisplayed()

        // The failure mode this replaces: separator row and pipes were painted
        // as literal text. Neither may exist on screen now.
        compose.onNodeWithText("|---|---|").assertDoesNotExist()
        compose.onNodeWithText("| models | portable |", substring = true).assertDoesNotExist()
    }

    /**
     * These two sit outside the `junit4.v2` alias surface, so they are spelled
     * against the base rule here rather than imported from the future package.
     */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithText(
        text: String,
        substring: Boolean = false,
    ) = onNode(hasText(text = text, substring = substring))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() {
        val found = runCatching { fetchSemanticsNode() }.isSuccess
        check(!found) { "Expected no matching node, but one exists" }
    }

    @Test
    fun `a table block owns its own horizontal scroller`() {
        launch("| First | Second |\n|---|---|\n| one | two |")

        // The scroller exists inside the table card and is on screen — the
        // block owns its overflow container, like a code fence does.
        compose.onNodeWithTag(MarkdownTableScrollerTag).assertIsDisplayed()
    }

    @Test
    fun `a wide wrappable table shrinks to the viewport instead of scrolling`() {
        // Regression guard for the review blocker: horizontalScroll hands its
        // child unbounded width, so if the viewport budget is not plumbed in
        // from outside the scroller, TableSizing sees null and this table
        // renders at full unwrapped width (~2000px). With a real budget, both
        // columns are multi-word and wrap down instead.
        launch(
            """
            | Alpha column with several ordinary words | Beta column also carrying plenty of words |
            |---|---|
            | ${"row one ".repeat(30)}| ${"row two ".repeat(30)} |
            """.trimIndent(),
        )

        val window = compose.onRoot().fetchSemanticsNode().boundsInWindow.width
        val scroller = compose.onNodeWithTag(MarkdownTableScrollerTag)
            .fetchSemanticsNode().boundsInWindow

        assertTrue(
            "table rendered ${scroller.width}px wide in a ${window}px window; " +
                "TableSizing never received a viewport budget",
            scroller.width <= window + 1f,
        )
    }

    private companion object {
        const val SESSION = "table-test"
        const val NOW = 0L
    }
}
