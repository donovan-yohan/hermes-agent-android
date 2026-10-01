package com.hermesagent.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotModelCaptureComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manualRequiresRealComposeControlAndPreservesOriginalPair() {
        compose.setContent { HermesTheme(AppearanceSelection()) { BotManagementParityContent("bot-model-manual") } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Model ID").assertDoesNotExist()
        compose.onNodeWithText("Enter manually").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Model ID").performScrollTo().assertTextEquals("synthetic-planner-v1")
        compose.onNodeWithContentDescription("Provider").performScrollTo().assertTextEquals("synthetic-provider")
        compose.onNodeWithText("Save model").performScrollTo().assertIsNotEnabled()
    }

    @Test fun stagedSuccessRendersProductionReadbackMessage() {
        compose.setContent { HermesTheme(AppearanceSelection()) { BotManagementParityContent("bot-model-saved") } }
        compose.waitForIdle()
        compose.onNodeWithText("Model saved.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save model").performScrollTo().assertIsNotEnabled()
    }
}
