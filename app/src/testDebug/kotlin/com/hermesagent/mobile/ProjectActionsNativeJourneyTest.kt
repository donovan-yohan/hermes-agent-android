package com.hermesagent.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.ui.chat.ProjectActionPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
class ProjectActionsNativeJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(state: String = "overview", verify: (ProjectActionsParityActivity) -> Unit) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ProjectActionsParityActivity::class.java)
            .putExtra("visual_parity_state", state).putExtra("visual_parity_theme", "dark")
        val controller = Robolectric.buildActivity(ProjectActionsParityActivity::class.java, intent).setup()
        try {
            compose.waitUntil(10_000) { controller.get().fixture.ready }
            compose.onNodeWithContentDescription("Open sessions").performClick()
            verify(controller.get())
        } finally { controller.pause().stop().destroy() }
    }
    private fun menu() { compose.onNodeWithTag("Project row synthetic-project").performTouchInput { longClick() } }

    @Test fun longPressRenameAddFolderSetActiveAndDeleteAreWiredThroughRealRepository() = launch { activity ->
        menu()
        listOf("Rename…", "Add folder", "Set active", "Appearance", "Copy path", "Delete…").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Rename…").performClick()
        compose.onNodeWithContentDescription("Name").performTextReplacement("Renamed project")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithContentDescription("Open project Renamed project. 1 session").assertIsDisplayed()
        menu(); compose.onNodeWithText("Add folder").performClick()
        compose.onNodeWithContentDescription("Remote folder").performTextInput("/workspace/added")
        compose.onNodeWithContentDescription("Label (optional)").performTextInput("Added")
        compose.onNodeWithText("Make primary folder").performClick()
        compose.onAllNodesWithText("Add folder").onLast().performClick()
        menu(); compose.onNodeWithText("Set active").performClick()
        menu(); compose.onNodeWithText("Set active").assertIsNotEnabled()
        compose.onNodeWithText("Delete…").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("Project row synthetic-project").assertIsDisplayed()
        menu(); compose.onNodeWithText("Delete…").performClick(); compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithTag("Project row synthetic-project").assertDoesNotExist()
        val methods = activity.fixture.rpc.requests.map { it.first }
        assertTrue(methods.containsAll(listOf("projects.update", "projects.add_folder", "projects.set_active", "projects.delete")))
        assertEquals(1, methods.count { it == "projects.delete" })
    }

    @Test fun enteredHeaderAppearanceAndClearUseTheSameActions() = launch("entered") { activity ->
        compose.onNodeWithTag("Project header").performTouchInput { longClick() }
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithContentDescription("hsl(30 68% 58%)").performClick()
        compose.onNodeWithTag("Project header").performTouchInput { longClick() }
        compose.onNodeWithText("Appearance").performClick(); compose.onNodeWithText("No color").performClick()
        val writes = activity.fixture.rpc.requests.filter { it.first == "projects.update" }
        assertEquals(2, writes.size)
        assertEquals("\"\"", writes.last().second["color"].toString())
    }

    @Test fun autoHideRecoverySearchRestoreAndUndoRemainFunctional() = launch { activity ->
        compose.onNodeWithTag("Project row synthetic-auto").performTouchInput { longClick() }
        compose.onNodeWithText("Rename…").assertDoesNotExist()
        compose.onNodeWithText("Hide from sidebar").performClick()
        compose.onNodeWithTag("Project row synthetic-auto").assertDoesNotExist()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithTag("Project row synthetic-auto").assertIsDisplayed()
        compose.onNodeWithTag("Project row synthetic-auto").performTouchInput { longClick() }
        compose.onNodeWithText("Hide from sidebar").performClick()
        compose.onNodeWithContentDescription("Hidden projects").performClick()
        compose.onNodeWithContentDescription("Search").performTextInput("/workspace/repository")
        compose.onAllNodesWithText("/workspace/repository").onLast().assertIsDisplayed()
        compose.onNodeWithText("Restore Synthetic repository").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("Project row synthetic-auto").assertIsDisplayed()
        assertTrue(activity.fixture.rpc.requests.none { it.first == "projects.delete" })
    }

    @Test fun autoAppearanceAdoptsOnceAndOldMenuCloses() = launch { activity ->
        compose.onNodeWithTag("Project row synthetic-auto").performTouchInput { longClick() }
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithContentDescription("rocket").performClick()
        compose.onNodeWithTag("Project row synthetic-auto").assertDoesNotExist()
        compose.onNodeWithTag("Project row synthetic-adopted").assertIsDisplayed()
        assertEquals(1, activity.fixture.rpc.requests.count { it.first == "projects.create" })
    }

    @Test fun refusedWriteStaysInDialogAndCopyPathUsesExactRawPath() = launch { activity ->
        menu(); compose.onNodeWithText("Copy path").performClick()
        compose.onNodeWithText("Copied").assertIsDisplayed()
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("/workspace/project", clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Cancel").performClick()
        activity.fixture.rpc.failWrites = true
        menu(); compose.onNodeWithText("Rename…").performClick(); compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Project could not be updated. Check the Gateway and try again.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.onNodeWithTag("Project row synthetic-project").assertIsDisplayed()
    }

    @Test fun homeHasNoHeaderContextAction() = launch("home") {
        compose.onNodeWithTag("Project header").assert(SemanticsMatcher("No long click") {
            !it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        })
    }
}
