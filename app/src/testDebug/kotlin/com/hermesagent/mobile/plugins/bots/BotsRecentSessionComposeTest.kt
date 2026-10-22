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
class BotsRecentSessionComposeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `recent menu action distinct from canonical row tap`() {
        val row = BotRosterRow(name = "researcher", lastSession = BotSessionPreview("recent"))
        val state = BotsRosterUiState(phase = BotsRosterPhase.Ready, connectionUp = true,
            managementRows = listOf(row), sections = listOf(BotSectionBlock(null,
                "section:unassigned", "Unassigned", listOf(row))))
        var recent = 0; var canonical = 0
        compose.setContent { HermesTheme(AppearanceSelection()) {
            BotsRosterScreen(state, onBack = {}, onOpenBotChat = { canonical++ },
                actions = BotsActions(onOpenRecent = { selected, _ -> assertEquals(row, selected); recent++ }))
        } }
        compose.onNodeWithContentDescription("Actions for Researcher").performClick()
        compose.onNodeWithText("Open recent session").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, recent); assertEquals(0, canonical) }
    }
    @Test fun `no listed session keeps actual disabled action without WIP`() {
        val row = BotRosterRow(name = "researcher")
        val state = BotsRosterUiState(phase = BotsRosterPhase.Ready, connectionUp = true,
            sections = listOf(BotSectionBlock(null, "section:unassigned", "Unassigned", listOf(row))))
        compose.setContent { HermesTheme(AppearanceSelection()) { BotsRosterScreen(state, onBack = {}) } }
        compose.onNodeWithContentDescription("Actions for Researcher").performClick()
        compose.onNodeWithText("Open recent session").assertIsNotEnabled()
    }
}
