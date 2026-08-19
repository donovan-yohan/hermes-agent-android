package com.hermesagent.mobile.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The mobile Settings path is list → detail, preserving Desktop's peer order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var backDispatcher: OnBackPressedDispatcher

    @Test
    fun `settings gear opens ordered peers and each child returns to settings`() {
        launch()

        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.onNodeWithTag(APPEARANCE).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 0f),
        )
        compose.onNodeWithTag(GATEWAYS).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, 1f),
        )
        assertEquals(0, compose.countWithText("Host & SSH"))

        compose.onNodeWithTag(APPEARANCE).performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.onNodeWithTag(GATEWAYS).performClick()
        compose.onNodeWithText("Gateways").assertIsDisplayed()
        assertEquals(0, compose.countWithText("Host & SSH"))

        backDispatcher.onBackPressed()
        compose.onNodeWithText("Settings").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Hermes").assertIsDisplayed()
    }

    @Test
    fun `app back transitions form the settings parent chain`() {
        assertEquals(HermesDestination.Chat, HermesDestination.Settings.backDestination())
        assertEquals(HermesDestination.Settings, HermesDestination.Appearance.backDestination())
        assertEquals(HermesDestination.Settings, HermesDestination.Gateways.backDestination())
        assertEquals(HermesDestination.Chat, HermesDestination.Chat.backDestination())
    }

    @Test
    fun `settings rows are button targets with one spoken description`() {
        launch()
        compose.onNodeWithContentDescription("Open settings").performClick()

        compose.onNodeWithTag(APPEARANCE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(HermesSpacing().touchTarget)
        compose.onNodeWithContentDescription("Appearance. Mode, theme, and chat chrome.").assertIsDisplayed()
    }

    private fun launch() {
        compose.setContent {
            val dispatcher = requireNotNull(
                LocalOnBackPressedDispatcherOwner.current,
            ).onBackPressedDispatcher
            SideEffect { backDispatcher = dispatcher }
            HermesApp(
                chatState = ChatUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                sshActions = SshActions(),
            )
        }
        compose.waitForIdle()
    }

    private companion object {
        const val APPEARANCE = "settings-row-appearance"
        const val GATEWAYS = "settings-row-gateways"
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.countWithText(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
