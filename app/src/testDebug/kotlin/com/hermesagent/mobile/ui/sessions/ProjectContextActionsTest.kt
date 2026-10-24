package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.ui.theme.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ProjectContextActionsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun overviewLongPressHasAProjectActionsDoor() {
        compose.setContent { HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
            SessionList(rows = emptyList(), projects = listOf(ProjectSummary("p", "Project", "/workspace/project")),
                projectsAvailable = true, sidebarGrouping = SidebarGrouping.Project, selectedProject = null,
                projectLoading = false, activeSessionId = null, query = "", canCreate = true,
                onQueryChange = {}, onSidebarGroupingChange = {}, onSelectProject = {}, onExitProject = {},
                onCreateProject = { _, _ -> }, onSelect = {}, onCreate = {}, nowMillis = 0)
        } }
        compose.onNodeWithTag("Project row p").assertHasClickAction()
        compose.onNodeWithTag("Project row p").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))
    }
}
