package com.hermesagent.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `Show earlier messages` as the reader meets it (#68 S25).
 *
 * Desktop's control is a plain text pill at the top of the transcript content,
 * inside the scroll, with no glyph, no spinner and no disabled state — it
 * simply stops existing once a session is exhausted
 * (`apps/desktop/src/components/assistant-ui/thread/list.tsx:834-842` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShowEarlierJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ChatUiState())
    private var presses = 0

    private fun chatState(transcript: List<TranscriptEntry>, canShowEarlier: Boolean) = ChatUiState(
        activeSession = SessionSummary(
            id = SESSION,
            title = "Long chat",
            preview = "",
            lastActiveAtMillis = NOW,
            status = SessionStatus.Idle,
        ),
        transcript = transcript,
        canShowEarlierMessages = canShowEarlier,
    )

    private fun launch(transcript: List<TranscriptEntry>, canShowEarlier: Boolean, onPress: () -> Unit = {}) {
        state = chatState(transcript, canShowEarlier)
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(
                        onShowEarlierMessages = {
                            presses++
                            onPress()
                        },
                    ),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /**
     * The window a session first paints: one ask and one reply, long enough
     * that landing on the tail leaves the top of the list off screen.
     */
    private fun tail(): List<TranscriptEntry> = listOf(
        UserTurn("row-120", "tell me something long", NOW, rowId = TranscriptRowId(120)),
        AssistantTurn(
            id = "row-121",
            markdown = (1..60).joinToString("\n\n") { "Paragraph $it of the reply." },
            atMillis = NOW,
            rowId = TranscriptRowId(121),
        ),
    )

    /**
     * Land on the top of the transcript, where the control lives, the way a
     * reader gets there: a scroll gesture, which is also what disarms the
     * tail-follow so the list stays put.
     */
    private fun scrollToTop() {
        // A drag to the head of the reply, which is the gesture that tells the
        // pane the reader has left the tail. Everything above is still
        // unmaterialized — a lazy list composes what is on screen — so the last
        // step asks the list itself for its first row.
        compose.onNodeWithText("Paragraph 1 of the reply.").performScrollTo()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        compose.waitForIdle()
    }

    private fun olderPage(): List<TranscriptEntry> =
        (1..4).map { UserTurn("row-$it", "earlier ask $it", NOW, rowId = TranscriptRowId(it.toLong())) }

    /**
     * An older page taller than the viewport, so a viewport left where it was
     * and a viewport left at the top of the list are visibly different places.
     */
    private fun tallOlderPage(): List<TranscriptEntry> = (1..3).flatMap { turn ->
        listOf(
            UserTurn("row-a$turn", "earlier ask $turn", NOW, rowId = TranscriptRowId(turn * 2L)),
            AssistantTurn(
                id = "row-b$turn",
                markdown = (1..40).joinToString("\n\n") { "Earlier paragraph $it of reply $turn." },
                atMillis = NOW,
                rowId = TranscriptRowId(turn * 2L + 1),
            ),
        )
    }

    @Test
    fun aSessionWithNothingEarlierNeverOffersTheControl() {
        launch(tail(), canShowEarlier = false)

        compose.onNodeWithText(LABEL).assertDoesNotExist()
    }

    @Test
    fun theControlSitsAboveTheFirstTurnAndAsksForThePage() {
        launch(tail(), canShowEarlier = true)
        scrollToTop()

        compose.onNodeWithText(LABEL).assertIsDisplayed()
        compose.onNodeWithText(LABEL).performClick()
        compose.waitForIdle()

        assertEquals(1, presses)
    }

    @Test
    fun aPrependedPageKeepsTheReaderWhereTheyWereAndRetiresTheControl() {
        launch(tail(), canShowEarlier = true) {
            state = chatState(olderPage() + tail(), canShowEarlier = false)
        }
        // Park the reader at the top of the window, where the control is.
        scrollToTop()
        compose.onNodeWithContentDescription(ANCHOR_TURN).assertIsDisplayed()

        compose.onNodeWithText(LABEL).performClick()
        compose.waitForIdle()

        // The prepend slid four turns in above the reader and took the leading
        // control away with it; the turn that was on top is still on screen.
        compose.onNodeWithContentDescription(ANCHOR_TURN).assertIsDisplayed()
        compose.onNodeWithText(LABEL).assertDoesNotExist()
    }

    /**
     * The case the anchor mechanism exists for, with the control still there
     * afterwards because more pages remain.
     *
     * This is the arrangement where `LazyListState`'s own key-based adjustment
     * is not enough and is in fact the wrong answer: the leading control keeps
     * its key across the update, so the list holds index 0 — the control — at
     * the top and the reader ends up looking at the page they just asked for
     * instead of the place they asked for it from. Only the recorded row anchor
     * puts them back.
     */
    @Test
    fun aPrependedPageWithMorePagesBehindItStillKeepsTheReaderWhereTheyWere() {
        launch(tail(), canShowEarlier = true) {
            state = chatState(tallOlderPage() + tail(), canShowEarlier = true)
        }
        scrollToTop()
        compose.onNodeWithContentDescription(ANCHOR_TURN).assertIsDisplayed()

        compose.onNodeWithText(LABEL).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(ANCHOR_TURN).assertIsDisplayed()
        // The page landed above the reader, so the control went off screen with
        // it rather than being retired — the session is not exhausted, and
        // scrolling back to the head finds it still on offer.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithText(LABEL).assertIsDisplayed()
    }

    /**
     * History the reader asked for is not activity that happened below them.
     */
    @Test
    fun aPrependedPageNeverReportsItselfAsNewActivity() {
        launch(tail(), canShowEarlier = true) {
            state = chatState(tallOlderPage() + tail(), canShowEarlier = true)
        }
        scrollToTop()

        compose.onNodeWithText(LABEL).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(UNSEEN_JUMP).assertDoesNotExist()
    }

    /**
     * A press that fetches nothing — the in-flight guard swallowing a second
     * tap, a refused page, a page whose rows all dedupe away — leaves the
     * control on offer and must not arm anything that a later turn arriving at
     * the tail can fire.
     */
    @Test
    fun aPressThatFetchesNothingLeavesTheControlAndTheViewportAlone() {
        // The press changes no state at all: this is the refused page.
        launch(tail(), canShowEarlier = true)
        scrollToTop()

        compose.onNodeWithText(LABEL).performClick()
        compose.waitForIdle()

        assertEquals(1, presses)
        compose.onNodeWithText(LABEL).assertIsDisplayed()
        compose.onNodeWithContentDescription(ANCHOR_TURN).assertIsDisplayed()

        // A live turn lands at the tail afterwards. The reader is still where
        // they were, not yanked to a viewport recorded for a page that never
        // arrived.
        state = chatState(
            tail() + UserTurn("row-122", "one more ask", NOW, rowId = TranscriptRowId(122)),
            canShowEarlier = true,
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription(ANCHOR_TURN).assertIsDisplayed()
    }

    /**
     * The same press that fetched nothing, with the reader then moving away from
     * the row the anchor was recorded on.
     *
     * This is what separates latching the anchor on the row's INDEX from
     * latching it on the transcript's size. A later turn appended at the tail
     * grows the transcript, so a size latch fires and re-applies a viewport the
     * reader has since left; an index latch does not, because rows arriving at
     * the tail never move the anchor row down.
     */
    @Test
    fun aTailAppendAfterAPressThatFetchedNothingLeavesTheReaderWhereTheyScrolledTo() {
        launch(tail(), canShowEarlier = true)
        scrollToTop()

        compose.onNodeWithText(LABEL).performClick()
        compose.waitForIdle()

        // Away from the anchor row, and not as far as the tail — so the pane is
        // not following, and nothing but a stale restore could move the viewport.
        compose.onNodeWithText(MID_REPLY).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText(MID_REPLY).assertIsDisplayed()

        // A live turn lands at the tail.
        state = chatState(
            tail() + UserTurn("row-122", "one more ask", NOW, rowId = TranscriptRowId(122)),
            canShowEarlier = true,
        )
        compose.waitForIdle()

        compose.onNodeWithText(MID_REPLY).assertIsDisplayed()
    }

    private companion object {
        const val SESSION = "durable-a"
        const val NOW = 1_800_000_000_000L

        /** `apps/desktop/src/i18n/en.ts:3218` @ `3ca096de`, verbatim. */
        const val LABEL = "Show earlier messages"

        /** The user turn the reader is parked on when the page is asked for. */
        const val ANCHOR_TURN = "You said: tell me something long"

        /** A paragraph deep inside the reply, well past the anchor turn. */
        const val MID_REPLY = "Paragraph 30 of the reply."

        /** `JumpToLatestButton`'s description once it claims unseen activity. */
        const val UNSEEN_JUMP = "New activity. Scroll to bottom"
    }
}
