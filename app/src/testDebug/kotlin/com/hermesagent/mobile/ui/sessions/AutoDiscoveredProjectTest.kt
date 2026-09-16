package com.hermesagent.mobile.ui.sessions

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An auto-discovered repo lane must not read as a project somebody made.
 *
 * Desktop shipped this distinction in `03b5460ad9` @ `564aef2946`, describing
 * the exact state this app was in: the backend emits `isAuto`, the client used
 * it "for sorting, dismissal, and context-menu behavior but never surfaced it
 * visually".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AutoDiscoveredProjectTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anAutoDiscoveredRepoIsMarkedAndSaysSo() {
        launch(project("auto-repo", isAuto = true))

        // Unmerged: the row is `clickable`, so it merges its children and the
        // glyph has no node of its own in the merged tree — which is the same
        // reason its own semantics are cleared rather than spoken.
        compose.onNodeWithTag(AUTO_PROJECT_GLYPH, useUnmergedTree = true).assertIsDisplayed()
        // Desktop's own reasoning for putting the cue in the name: its glyph is
        // aria-hidden and its tooltip only speaks on hover. A phone has no
        // hover at all.
        compose.onNodeWithContentDescription("Open project auto-repo ($AUTO_DISCOVERED). 2 sessions")
            .assertIsDisplayed()
    }

    @Test
    fun anExplicitProjectIsUnchanged() {
        launch(project("Real project", isAuto = false))

        compose.onNodeWithTag(AUTO_PROJECT_GLYPH, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Open project Real project. 2 sessions").assertIsDisplayed()
    }

    /** `isAuto` absent is an explicit node: only auto lanes carry the flag. */
    @Test
    fun anAbsentFlagIsAnExplicitProject() {
        launch(ProjectSummary(id = "p1", label = "Unflagged", path = null, sessionCount = 2))

        compose.onNodeWithTag(AUTO_PROJECT_GLYPH, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun project(label: String, isAuto: Boolean) =
        ProjectSummary(id = "p-$label", label = label, path = null, sessionCount = 2, isAuto = isAuto)

    private fun launch(project: ProjectSummary) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                SessionList(
                    rows = emptyList(),
                    projects = listOf(project),
                    projectsAvailable = true,
                    sidebarGrouping = SidebarGrouping.Project,
                    selectedProject = null,
                    projectLoading = false,
                    activeSessionId = null,
                    query = "",
                    canCreate = true,
                    onQueryChange = {},
                    onSidebarGroupingChange = {},
                    onSelectProject = {},
                    onExitProject = {},
                    onCreateProject = { _, _ -> },
                    onSelect = {},
                    onCreate = {},
                    modifier = Modifier,
                    // No rows here, so no age is rendered; the value only has
                    // to be a fixed instant rather than a live read.
                    nowMillis = NOW,
                )
            }
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
