package com.hermesagent.mobile

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The capture stages real navigation rather than supplying an invented UI state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class SidebarChatUxParityActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun heldOwnerAwareSelectionReplacesPreviousPaneUntilResponseArrives() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), SidebarChatUxParityActivity::class.java)
            .putExtra("visual_parity_state", "switch")
            .putExtra("visual_parity_theme", "dark")
        val controller = Robolectric.buildActivity(SidebarChatUxParityActivity::class.java, intent).setup()
        try {
            compose.onNodeWithText("This is the earlier conversation.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Open sessions").performClick()
            compose.onNodeWithText("Needs your input").assertIsDisplayed()
            compose.onNodeWithTag("Session row synthetic-target").performClick()
            compose.onNodeWithTag("Session transcript skeleton").assertIsDisplayed()
            compose.onNodeWithText("This is the earlier conversation.").assertDoesNotExist()
            compose.runOnIdle { checkNotNull(SidebarChatUxRuntimeProvider.active).openGate.complete(Unit) }
            compose.onNodeWithTag("Session transcript skeleton").assertDoesNotExist()
            compose.onNodeWithText("The selected conversation is ready.").assertIsDisplayed()
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
