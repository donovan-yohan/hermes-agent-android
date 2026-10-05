package com.hermesagent.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import com.hermesagent.mobile.ui.promptContentDescription as hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.hermesagent.mobile.ui.promptNodeWithContentDescription as onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.hermesagent.mobile.ui.performPromptJourneyClick as performClick
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
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptBubbleDecorationRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `compact bubble retains rounded bottom corners and border`() {
        launch()

        val compact = currentPromptBubble()
        val compactImage = capture("compact.png")
        assertRoundedBottomDecoration(compactImage, compact)

        compose.onNodeWithContentDescription("Current prompt: $PROMPT").performClick()
        compose.waitForIdle()
        val full = inlinePrompt()
        capture("full.png")

        val intermediate = scrollUntilCurrentPromptAppears()
        capture("intermediate.png")
        assertEquals(full.height, intermediate.height, 1f)
        assertEquals(compact.height, intermediate.height, 1f)

        scrollBy(12f * compose.density.density)
        val furtherCollapsed = currentPromptBubble()
        assertEquals(intermediate.height, furtherCollapsed.height, 1f)

        scrollBy(-12f * compose.density.density)
        val reversed = currentPromptBubble()
        capture("reverse.png")
        assertRectNear(intermediate, reversed)
    }

    @Test
    fun `one line prompt settles continuously in both scroll directions`() {
        assertShortPromptContinuity("a")
    }

    @Test
    fun `four line prompt settles continuously in both scroll directions`() {
        assertShortPromptContinuity("First\nSecond\nThird\nFourth")
    }

    private fun assertShortPromptContinuity(prompt: String) {
        launch(prompt)
        compose.onNodeWithContentDescription("Current prompt: $prompt").performClick()
        compose.waitForIdle()
        fun visibleBounds(): Rect = if (
            compose.onAllNodes(hasContentDescription("Current prompt: $prompt")).fetchSemanticsNodes().isNotEmpty()
        ) {
            currentPromptBubble()
        } else {
            compose.onNodeWithTag("Final prompt body", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        val initial = visibleBounds()
        var previous = initial
        for (direction in listOf(1f, -1f)) {
            repeat(40) {
                scrollBy(direction)
                val current = visibleBounds()
                assertTrue("one pixel scroll jumped from $previous to $current", abs(current.top - previous.top) <= 1f)
                assertTrue("one pixel scroll resized $previous to $current", abs(current.bottom - previous.bottom) <= 1f)
                assertTrue("bubble width changed", abs(current.width - initial.width) <= 1f)
                previous = current
            }
        }
        assertRectNear(initial, previous)
    }

    private fun assertRoundedBottomDecoration(image: Bitmap, bounds: Rect) {
        val left = bounds.left.roundToInt()
        val top = bounds.top.roundToInt()
        val right = bounds.right.roundToInt()
        val bottom = bounds.bottom.roundToInt()
        val centerX = (left + right) / 2

        val bubbleFill = image.getPixel(centerX, top + 3)
        val sideBorder = image.getPixel(left, (top + bottom) / 2)
        val bottomBorder = image.getPixel(centerX, bottom - 1)
        val cornerArcDistance = (bottom - 7 until bottom - 1).minOf { y ->
            (left + 3 until left + 13).minOf { x ->
                colorDistance(image.getPixel(x, y), sideBorder)
            }
        }

        assertTrue(
            "the compact bubble must curve its side border into the lower corner",
            cornerArcDistance <= 20,
        )
        assertTrue(
            "the compact bubble must draw its bottom border instead of clipping the fill square",
            colorDistance(bottomBorder, sideBorder) <= 10 && colorDistance(bottomBorder, bubbleFill) >= 20,
        )
    }

    private fun capture(name: String): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        System.getenv("PROMPT_CAPTURE_DIR")?.let { directory ->
            val output = File(directory, name)
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        }
        return bitmap
    }

    private fun scrollUntilCurrentPromptAppears(): Rect {
        repeat(400) {
            if (compose.onAllNodes(hasContentDescription("Current prompt: $PROMPT")).fetchSemanticsNodes().isNotEmpty()) {
                scrollBy(8f * compose.density.density)
                return currentPromptBubble()
            }
            scrollBy(1f)
        }
        throw AssertionError("prompt never entered the pinned transition")
    }

    private fun currentPromptBubble(): Rect = compose
        .onNodeWithTag("Current prompt bubble", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun inlinePrompt(): Rect = compose
        .onNodeWithTag("Final prompt body", useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun scrollBy(pixels: Float) {
        compose.onNodeWithTag("Transcript").performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
            scroll(0f, pixels)
        }
        compose.waitForIdle()
    }

    private fun assertRectNear(expected: Rect, actual: Rect) {
        val tolerance = compose.density.density
        assertTrue(abs(expected.left - actual.left) <= tolerance)
        assertTrue(abs(expected.top - actual.top) <= tolerance)
        assertTrue(abs(expected.right - actual.right) <= tolerance)
        assertTrue(abs(expected.bottom - actual.bottom) <= tolerance)
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
            abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
            abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))

    private fun launch(prompt: String = PROMPT) {
        val now = 1_755_600_000_000L
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(
                    state = ChatUiState(
                        activeSession = SessionSummary(
                            id = "prompt-decoration-render",
                            title = "Render probe",
                            preview = "",
                            lastActiveAtMillis = now,
                            status = SessionStatus.Working,
                        ),
                        transcript = listOf(
                            UserTurn("u", prompt, now),
                            AssistantTurn("a", longReply(), now, streaming = true),
                        ),
                        isStreaming = true,
                    ),
                    actions = ChatActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun longReply(): String =
        (1..80).joinToString("\n\n") { "Synthetic response paragraph $it." }

    private companion object {
        val PROMPT = (1..9).joinToString("\n") { "Rendered line $it with stable width" }
    }
}
