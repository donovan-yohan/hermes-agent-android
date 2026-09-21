package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

import com.hermesagent.mobile.data.session.TimelineEvent
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a typed timeline row actually paints.
 *
 * [com.hermesagent.mobile.data.gateway.TimelineEventProjectionTest] pins the
 * classification; this pins the render — that a delegation completion is one
 * compact scaffold line rather than a user bubble, that the model-facing
 * envelope stays out of the transcript until the reader asks for it, and that a
 * real user turn beside it is untouched.
 *
 * Desktop's own renderer is
 * `apps/desktop/src/components/assistant-ui/thread/system-message.tsx:32-56` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h844dp")
class TimelineRowRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val envelope =
        "[ASYNC DELEGATION BATCH COMPLETE — 1/1 tasks finished]\n\n" +
            "The user asked me to keep this secret until the batch finished.\n" +
            "--- ✓ TASK 1/1: audit the parser  (status=completed) ---\n" +
            "The parser drops the metadata field."

    private fun launch(entries: List<TranscriptEntry>) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Transcript(entries = entries, listState = rememberLazyListState())
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a delegation completion is one compact line and never the user's own bubble`() {
        launch(
            listOf(
                UserTurn(id = "prompt", text = "ship it", atMillis = 0L),
                TimelineEvent(
                    id = "completion",
                    label = "1 background agent finished",
                    report = "The parser drops the metadata field.",
                    atMillis = 1L,
                ),
            ),
        )

        compose.onNodeWithText("1 background agent finished").assertIsDisplayed()
        // The reader's own turn is still their own row, not swallowed by the
        // completion beside it.
        compose.onNodeWithContentDescription("You said: ship it").assertIsDisplayed()
        // Nothing of the model-facing envelope is on screen while collapsed.
        compose.onAllNodesWithText(envelope, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("ASYNC DELEGATION", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the report discloses on demand and shows markdown, not raw envelope plumbing`() {
        launch(
            listOf(
                TimelineEvent(
                    id = "completion",
                    label = "1 background agent finished",
                    report = "The parser drops the metadata field.",
                    atMillis = 1L,
                ),
            ),
        )

        compose.onNodeWithText("The parser drops the metadata field.").assertDoesNotExist()

        compose.onNodeWithContentDescription("1 background agent finished, collapsed").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("The parser drops the metadata field.").assertIsDisplayed()
    }

    @Test
    fun `a completion with no report renders no disclosure affordance`() {
        launch(listOf(TimelineEvent(id = "empty", label = "model changed", report = null, atMillis = 0L)))

        compose.onNodeWithText("model changed").assertIsDisplayed()
        compose.onNodeWithContentDescription("model changed").assertIsDisplayed()
    }
}
