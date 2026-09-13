package com.hermesagent.mobile.plugins.bots

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Renders the Bot roster row and ChatScreen handoff, not Activity navigation or recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsBotChatJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `row action remains visibly loading until resume then renders transcript-only Chat`() {
        var roster by mutableStateOf(rosterState())
        var route by mutableStateOf(Route.Roster)
        var completion: ((Boolean) -> Unit)? = null
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                when (route) {
                    Route.Roster -> BotsRosterScreen(
                        state = roster,
                        onBack = {},
                        onOpenBotChat = { row ->
                            roster = roster.copy(openingBotKey = row.rosterKey)
                            completion = { opened ->
                                roster = roster.copy(openingBotKey = null)
                                if (opened) route = Route.Chat else roster = roster.copy(botChatMessage = FAILED)
                            }
                        },
                    )
                    Route.Chat -> ChatScreen(readOnlyChat(), ChatActions(), onOpenSettings = {})
                    Route.OrdinaryChat -> ChatScreen(chat(readOnly = false), ChatActions(), onOpenSettings = {})
                }
            }
        }

        compose.onNodeWithText("Researcher").performClick()
        compose.onNodeWithContentDescription("Opening Bot Chat").assertIsDisplayed()
        compose.onNodeWithText("Researcher").assertIsDisplayed()

        compose.runOnIdle { completion!!.invoke(true) }
        compose.onNodeWithText("Bot Chat is read-only on mobile.").assertIsDisplayed()
        compose.onNodeWithText("canonical transcript").assertIsDisplayed()
        compose.onAllNodesWithTag("Composer field shell").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Send message").assertCountEquals(0)

        compose.runOnIdle { route = Route.OrdinaryChat }
        compose.onNodeWithTag("Composer field shell").assertIsDisplayed()
    }

    @Test
    fun `failed resume keeps the roster and shows fixed safe copy`() {
        var roster by mutableStateOf(rosterState())
        var completion: ((Boolean) -> Unit)? = null
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                BotsRosterScreen(
                    state = roster,
                    onBack = {},
                    onOpenBotChat = { row ->
                        roster = roster.copy(openingBotKey = row.rosterKey)
                        completion = { opened ->
                            roster = roster.copy(
                                openingBotKey = null,
                                botChatMessage = if (opened) null else FAILED,
                            )
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("Researcher").performClick()
        compose.onNodeWithContentDescription("Opening Bot Chat").assertIsDisplayed()
        compose.runOnIdle { completion!!.invoke(false) }
        compose.onNodeWithText("Researcher").assertIsDisplayed()
        compose.onNodeWithText(FAILED).assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Opening Bot Chat").assertCountEquals(0)
    }

    private fun rosterState() = BotsRosterUiState(
        phase = BotsRosterPhase.Ready,
        sections = listOf(
            BotSectionBlock(null, "section:unassigned", "Unassigned", listOf(BotRosterRow(name = "researcher"))),
        ),
    )

    private fun readOnlyChat() = chat(readOnly = true)

    private fun chat(readOnly: Boolean) = ChatUiState(
        activeSession = SessionSummary("bot-chat", "Bot Chat", "", 1L),
        transcript = listOf(AssistantTurn("reply", "canonical transcript", 1L)),
        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
        readOnly = readOnly,
    )

    private enum class Route { Roster, Chat, OrdinaryChat }

    private companion object {
        const val FAILED = "Bot Chat could not be opened. Check the Gateway and try again."
    }
}
