package com.hermesagent.mobile

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Launches the actual debug Activity, not a duplicate composable or supplied ChatUiState. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class SidebarProjectionParityActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun overviewMountsProductionViewModelAndDraftEditsReuseItsProjection() {
        launch("projection-draft-reuse") { activity ->
            compose.waitUntil(10_000) { activity.fixture.ready }
            assertEquals(20, activity.fixture.observedDraftEdits)
            assertTrue(activity.fixture.overviewReused)
            assertTrue(activity.fixture.previewsReused)
            assertEquals("Synthetic draft 19", activity.fixture.viewModel.uiState.value.draft)
            compose.onNodeWithContentDescription("Open sessions").performClick()
            compose.onNodeWithContentDescription("Open project Synthetic project. 2 sessions").assertIsDisplayed()
            compose.onNodeWithTag("Session row synthetic-design").assertIsDisplayed()
        }
    }

    @Test
    fun selectedProjectIsLoadedThroughProductionViewModel() {
        launch("projection-selected") { activity ->
            compose.waitUntil(10_000) { activity.fixture.ready }
            assertEquals("synthetic-project", activity.fixture.viewModel.uiState.value.selectedProject?.id)
            assertTrue(activity.fixture.viewModel.uiState.value.projects.isEmpty())
            compose.onNodeWithContentDescription("Open sessions").performClick()
            compose.onNodeWithText("All projects").assertIsDisplayed()
            compose.onNodeWithText("Synthetic release checklist").assertIsDisplayed()

        }
    }

    @Test
    fun queryMissInvalidatesProductionProjection() {
        launch("projection-search-miss") { activity ->
            compose.waitUntil(10_000) { activity.fixture.ready }
            assertEquals("zqxvparitynomatch", activity.fixture.viewModel.uiState.value.query)
            assertTrue(activity.fixture.viewModel.uiState.value.projects.isEmpty())
            compose.onNodeWithContentDescription("Open sessions").performClick()
            compose.onNodeWithText("Nothing matches").assertIsDisplayed()
        }
    }

    private fun launch(state: String, verify: (SidebarProjectionParityActivity) -> Unit) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), SidebarProjectionParityActivity::class.java)
            .putExtra("visual_parity_state", state)
            .putExtra("visual_parity_theme", "dark")
        val controller = Robolectric.buildActivity(SidebarProjectionParityActivity::class.java, intent).setup()
        try {
            compose.waitForIdle()
            org.junit.Assert.assertSame(controller.get().fixture, SidebarProjectionRuntimeProvider.active)
            verify(controller.get())
        } finally { controller.pause().stop().destroy() }
    }
}
