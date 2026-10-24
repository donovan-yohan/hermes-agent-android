package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ProjectHeaderSemanticsTest {
    @get:Rule val compose = createComposeRule()
    private val project = ProjectSummary("p", "Project", "/workspace/project")
    private val opens = mutableListOf<ProjectSummary>()
    private fun mount(grouping: SidebarGrouping, selected: ProjectSummary? = null) {
        compose.setContent { HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
            SessionList(rows = emptyList(), projects = listOf(project), projectsAvailable = true,
                sidebarGrouping = grouping, selectedProject = selected, projectLoading = false,
                activeSessionId = null, query = "", canCreate = true, onQueryChange = {},
                onSidebarGroupingChange = {}, onSelectProject = {}, onExitProject = {},
                onCreateProject = { _, _ -> }, onSelect = {}, onCreate = {}, nowMillis = 0,
                projectActions = ProjectActions(open = { row, current, _ -> assertTrue(current()); opens += row }))
        } }
    }
    private fun assertNoActions() {
        for (unmerged in listOf(false, true)) {
            compose.onNodeWithTag("Project header", useUnmergedTree = unmerged).assert(SemanticsMatcher("No dead header actions") {
                !it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsActions.OnLongClick)
            })
        }
        assertTrue(opens.isEmpty())
    }
    @Test fun sessionsHeaderHasNoClickOrLongClick() { mount(SidebarGrouping.Date); assertNoActions() }
    @Test fun projectsOverviewHeaderHasNoClickOrLongClick() { mount(SidebarGrouping.Project); assertNoActions() }
    @Test fun homeHeaderHasNoClickOrLongClick() { mount(SidebarGrouping.Project, project.copy(id = "home", isHome = true)); assertNoActions() }
    @Test fun enteredProjectHeaderLongPressOpensActions() {
        mount(SidebarGrouping.Project, project)
        compose.onNodeWithTag("Project header").assertHasClickAction()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).performTouchInput { longClick() }
        assertEquals(listOf(project), opens)
    }
    @Test fun enteredProjectHeaderClickOpensSameActionsInsteadOfDoingNothing() {
        mount(SidebarGrouping.Project, project)
        compose.onNodeWithTag("Project header").performClick()
        assertEquals(listOf(project), opens)
    }
}
