package com.hermesagent.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class ProjectFolderSubmissionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(verify: (ProjectActionsParityActivity) -> Unit) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ProjectActionsParityActivity::class.java)
            .putExtra("visual_parity_state", "overview").putExtra("visual_parity_theme", "dark")
        val controller = Robolectric.buildActivity(ProjectActionsParityActivity::class.java, intent).setup()
        try {
            compose.waitUntil(10_000) { controller.get().fixture.ready }
            compose.onNodeWithContentDescription("Open sessions").performClick()
            compose.onNodeWithTag("Project row synthetic-project").performTouchInput { longClick() }
            compose.onNodeWithText("Add folder").performClick()
            verify(controller.get())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun authoredFolderAndLabelTrimOnlyOuterWhitespaceBeforeActualWire() = launch { activity ->
        compose.onNodeWithContentDescription("Remote folder").performTextInput("  /workspace/two words  ")
        compose.onNodeWithContentDescription("Label (optional)").performTextInput("  Two  words ✓  ")
        compose.onNodeWithText("Make primary folder").performClick()
        compose.onAllNodesWithText("Add folder").onLast().performClick()
        val writes = activity.fixture.rpc.requests.filter { it.first == "projects.add_folder" }
        assertEquals(1, writes.size)
        assertEquals(Json.parseToJsonElement("""{"id":"synthetic-project","path":"/workspace/two words","label":"Two  words ✓","is_primary":true,"profile":"default"}"""), writes.single().second)
    }
    @Test fun whitespaceOnlyOptionalLabelIsOmittedFromActualWire() = launch { activity ->
        compose.onNodeWithContentDescription("Remote folder").performTextInput(" /workspace/added ")
        compose.onNodeWithContentDescription("Label (optional)").performTextInput("   ")
        compose.onAllNodesWithText("Add folder").onLast().performClick()
        val write = activity.fixture.rpc.requests.single { it.first == "projects.add_folder" }
        assertEquals(Json.parseToJsonElement("""{"id":"synthetic-project","path":"/workspace/added","is_primary":false,"profile":"default"}"""), write.second)
    }
    @Test fun whitespaceOnlyPathCannotSubmit() = launch { activity ->
        compose.onNodeWithContentDescription("Remote folder").performTextInput("   ")
        compose.onAllNodesWithText("Add folder").onLast().assertIsNotEnabled().performClick()
        assertTrue(activity.fixture.rpc.requests.none { it.first == "projects.add_folder" })
    }
}
