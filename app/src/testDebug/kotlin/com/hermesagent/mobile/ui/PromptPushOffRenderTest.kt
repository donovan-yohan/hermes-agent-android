package com.hermesagent.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native synchronous window draws, not emulator or physical-device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptPushOffRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `synthetic handoff retains separate bubbles at successive scroll positions`() {
        val first = "First synthetic request\nCompare the example\nRecord a next action\nKeep this whole bubble\nWithout an internal cut"
        val second = "Second synthetic request\nContinue the example"
        val now = 1_755_600_000_000L
        fun response(label: String) = (1..30).joinToString("\n\n") { "$label synthetic paragraph $it." }
        val transcript = mutableStateOf(listOf(
            UserTurn("u1", first, now), AssistantTurn("a1", response("First"), now),
            UserTurn("u2", second, now), AssistantTurn("a2", response("Second"), now, streaming = true),
        ))
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    ChatUiState(
                        activeSession = SessionSummary("synthetic-push-off", "Synthetic handoff", "", now, status = SessionStatus.Working),
                        transcript = transcript.value,
                        isStreaming = true,
                    ), ChatActions(), {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Current prompt: $second").performClick()
        compose.waitForIdle()
        fun scroll(pixels: Float) {
            compose.onNodeWithTag("Transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, pixels) }
            compose.waitForIdle()
        }
        scroll(-100f * compose.density.density)
        capture("handoff-before.png")
        val before = compose.onNodeWithTag("Current prompt bubble", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        scroll(12f * compose.density.density)
        val after = compose.onNodeWithTag("Current prompt bubble", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val incoming = compose.onNodeWithContentDescription("You said: $second").fetchSemanticsNode().boundsInRoot
        assertTrue(after.bottom <= incoming.top)
        assertTrue(after.bottom < before.bottom)
        capture("handoff-pushing.png")
        // Actual streamed mutations (same durable row id), not just a static
        // streaming flag: growing text below a parked collision must not follow
        // the tail or change which prompt owns the accessible chrome.
        repeat(3) { delta ->
            compose.runOnIdle {
                transcript.value = transcript.value.map { entry ->
                    if (entry.id == "a2") (entry as AssistantTurn).copy(
                        markdown = entry.markdown + "\n\nStreamed synthetic delta $delta. " + response("Delta $delta"),
                    ) else entry
                }
            }
            compose.waitForIdle()
            val updated = compose.onNodeWithTag("Current prompt bubble", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val next = compose.onNodeWithContentDescription("You said: $second").fetchSemanticsNode().boundsInRoot
            assertEquals(after.bottom, updated.bottom, compose.density.density)
            assertEquals(incoming.top, next.top, compose.density.density)
            assertTrue(updated.bottom <= next.top)
            assertEquals(1, compose.onAllNodes(hasContentDescription("Current prompt: $first")).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodes(hasContentDescription("You said: $first")).fetchSemanticsNodes().size)
            assertEquals(1, compose.onAllNodes(hasContentDescription("You said: $second")).fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodes(hasContentDescription("Current prompt: $second")).fetchSemanticsNodes().size)
            capture("handoff-stream-$delta.png")
        }
        scroll(-12f * compose.density.density)
        val reversed = compose.onNodeWithTag("Current prompt bubble", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val incomingReversed = compose.onNodeWithContentDescription("You said: $second").fetchSemanticsNode().boundsInRoot
        assertEquals(before.bottom, reversed.bottom, compose.density.density)
        assertEquals(incomingReversed.top - incoming.top, reversed.bottom - after.bottom, compose.density.density + 1f)
        capture("handoff-reverse.png")
        scroll(122f * compose.density.density)
        compose.onNodeWithContentDescription("Current prompt: $second").fetchSemanticsNode()
        capture("handoff-complete.png")
    }

    @Test
    fun `synthetic project overview shows an inline plus on each row`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                com.hermesagent.mobile.ui.sessions.SessionList(
                    rows = emptyList(),
                    projects = listOf(
                        com.hermesagent.mobile.data.session.ProjectSummary("home", "HOME", null, sessionCount = 0),
                        com.hermesagent.mobile.data.session.ProjectSummary("example", "Example project", null, sessionCount = 0),
                    ),
                    projectsAvailable = true,
                    sidebarGrouping = com.hermesagent.mobile.data.prefs.SidebarGrouping.Project,
                    selectedProject = null, projectLoading = false,
                    activeSessionId = null, query = "", canCreate = true,
                    onQueryChange = {}, onSidebarGroupingChange = {}, onSelectProject = {},
                    onExitProject = {}, onCreateProject = { _, _ -> }, onSelect = {}, onCreate = {},
                    nowMillis = 1_755_600_000_000L,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New session in HOME").assertIsDisplayed()
        compose.onNodeWithContentDescription("New session in Example project").assertIsDisplayed()
        capture("project-inline-plus.png")
    }

    private fun capture(name: String) {
        val directory = System.getenv("PROMPT_CAPTURE_DIR") ?: return
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File(directory, name)
            output.parentFile?.mkdirs()
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
