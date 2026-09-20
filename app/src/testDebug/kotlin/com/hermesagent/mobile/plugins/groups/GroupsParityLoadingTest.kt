package com.hermesagent.mobile.plugins.groups

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.GroupsParityActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class GroupsParityLoadingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun populatedCaptureActivitySettlesWithoutLoadingAlongsideTranscript() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), GroupsParityActivity::class.java)
            .putExtra("visual_parity_state", "populated")
            .putExtra("visual_parity_theme", "dark")
        ActivityScenario.launch<GroupsParityActivity>(intent).use {
            compose.onNodeWithText("Planning").performClick()
            compose.onNodeWithText("Review the release plan.").assertIsDisplayed()
            compose.onNodeWithText("Loading Group Chat…").assertDoesNotExist()
        }
    }
}
