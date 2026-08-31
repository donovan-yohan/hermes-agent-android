package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TurnTermination
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The transcript, rather than only its copy mapper, renders each termination notice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptTerminationRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `user requested termination renders the Desktop stop copy`() {
        launch(TurnTermination.UserRequested)

        compose.onNodeWithText("Stopped by you").assertIsDisplayed()
    }

    @Test
    fun `external termination renders the Gateway ended copy`() {
        launch(TurnTermination.WsOrphanReap)

        compose.onNodeWithText("The Gateway ended this turn. You can try again.").assertIsDisplayed()
    }

    private fun launch(termination: TurnTermination) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Transcript(
                    entries = listOf(
                        AssistantTurn(
                            id = "reply",
                            markdown = "Partial response",
                            atMillis = 0L,
                            termination = termination,
                        ),
                    ),
                    listState = rememberLazyListState(),
                )
            }
        }
        compose.waitForIdle()
    }
}
