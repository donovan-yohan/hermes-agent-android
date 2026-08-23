package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionBranchStripTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `branch strip announces the server-reported branch`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                SessionBranchStrip(branch = "feat/project-views")
            }
        }
        compose.onNodeWithContentDescription("Working branch feat/project-views").assertIsDisplayed()
    }

    @Test
    fun `branch strip renders detached heads`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                SessionBranchStrip(branch = "HEAD detached at f82f2dba")
            }
        }
        compose.onNodeWithContentDescription("Working branch HEAD detached at f82f2dba").assertIsDisplayed()
    }
}
