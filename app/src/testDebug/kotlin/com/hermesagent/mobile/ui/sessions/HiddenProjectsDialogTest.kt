package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.ui.chat.ProjectManagementUi
import com.hermesagent.mobile.ui.theme.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class HiddenProjectsDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun searchAndRestoreAreReachable() {
        var restored: String? = null
        compose.setContent { HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
            HiddenProjectsDialog(ProjectManagementUi(hiddenProjects = listOf(ProjectSummary("auto", "Repository", "/workspace/repository", isAuto = true)), hiddenLoading = false),
                ProjectActions(restore = { id, _ -> restored = id })) {}
        } }
        compose.onNodeWithContentDescription("Search").performTextInput("repository")
        compose.onNodeWithText("Restore Repository").performClick()
        org.junit.Assert.assertEquals("auto", restored)
    }
}
