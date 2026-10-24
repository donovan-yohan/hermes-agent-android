package com.hermesagent.mobile.ui.sessions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.hermesagent.mobile.data.gateway.ProjectRecoveryRow
import com.hermesagent.mobile.data.prefs.ProjectAdoptionAttempt
import com.hermesagent.mobile.ui.chat.ProjectRecoveryDialog
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Robolectric window draw, not a device or IME acceptance claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectRecoveryDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reviewFailureInventoryWarningAndAuthorizationHaveNativeTouchTargets() {
        val attempt = ProjectAdoptionAttempt("prepared", "auto", "/workspace/repository", "Synthetic repository")
        var state by mutableStateOf(ProjectRecoveryDialog(1, listOf(attempt), attempt.attemptId))
        var allows = 0
        compose.setContent { HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
            ProjectRecoveryDialog(state, ProjectActions(recoveryReviewed = { state = state.copy(reviewed = !state.reviewed) }, recoveryAllow = { allows++ }))
        } }
        compose.onNodeWithText("Allow another attempt").assertIsNotEnabled()
        capture("awaiting-refresh")
        compose.runOnIdle { state = state.copy(error = "Projects could not be loaded. Refresh projects to try again.") }
        compose.onNodeWithText("Projects could not be loaded. Refresh projects to try again.").assertIsDisplayed()
        capture("inventory-failed")
        compose.runOnIdle { state = state.copy(projects = listOf(ProjectRecoveryRow("saved", "Synthetic saved project", listOf("/workspace/repository"), true)), error = null) }
        compose.onNodeWithText("Synthetic saved project").assertIsDisplayed()
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithText("The earlier request may still finish. Another attempt could create a duplicate project.").assertIsDisplayed()
        compose.onNodeWithText("I reviewed these projects").performClick()
        compose.onNodeWithText("Allow another attempt").assertIsEnabled().assertHeightIsAtLeast(48.dpValue())
        capture("reviewed-inventory")
        compose.onNodeWithText("Allow another attempt").performClick()
        assertEquals(1, allows)
    }

    private fun Int.dpValue() = androidx.compose.ui.unit.Dp(toFloat())
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = System.getenv("PROJECT_RECOVERY_CAPTURE_DIR")?.let(::File) ?: return
        directory.mkdirs()
        compose.runOnIdle {
            val decor = WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
        }
    }
}
