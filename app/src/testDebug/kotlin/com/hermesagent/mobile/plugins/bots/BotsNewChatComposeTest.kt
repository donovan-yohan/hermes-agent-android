package com.hermesagent.mobile.plugins.bots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsNewChatComposeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `menu starts ordinary chat without canonical row activation`() {
        val row = BotRosterRow(name = "researcher")
        val state = BotsRosterUiState(phase = BotsRosterPhase.Ready, connectionUp = true,
            managementRows = listOf(row), sections = listOf(BotSectionBlock(null,
                "section:unassigned", "Unassigned", listOf(row))))
        var created = 0; var canonical = 0
        compose.setContent { HermesTheme(AppearanceSelection()) {
            BotsRosterScreen(state, onBack = {}, onOpenBotChat = { canonical++ },
                actions = BotsActions(onNewChat = { selected, origin ->
                    assertEquals(row, selected); assertEquals(state, origin); created++
                }))
        } }
        compose.onNodeWithContentDescription("Actions for Researcher").performClick()
        compose.onNodeWithText("New chat with this bot").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, canonical) }
    }
}
