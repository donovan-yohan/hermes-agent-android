package com.hermesagent.mobile.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.robolectric.annotation.GraphicsMode
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.chat.FinalPromptDisclosure
import com.hermesagent.mobile.ui.chat.FinalUserPromptBubble
import com.hermesagent.mobile.ui.chat.LocalFinalPromptDisclosure
import com.hermesagent.mobile.ui.chat.UserTurnBubble
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalUserPromptHeightTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val prompt = (1..60).joinToString("\n") { "Synthetic prompt line $it" }
    private val state = mutableStateOf(fixture(prompt))
    private lateinit var measurer: TextMeasurer
    private lateinit var bodyStyle: TextStyle
    private lateinit var textDensity: Density

    @Test fun `final pinned prompt clips at three measured lines with one full accessible owner`() {
        launch()
        assertThreeLineClip("Current prompt bubble", prompt)
        assertEquals(1, compose.onAllNodes(hasContentDescription("You said: $prompt")).fetchSemanticsNodes().size)
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-collapsed.png") }
    }

    @Test fun `tap expands and outside tap preserves composer action while collapsing`() {
        launch()
        val owner = compose.onNodeWithContentDescription("You said: $prompt")
        val before = owner.fetchSemanticsNode().boundsInRoot.height
        owner.performClick()
        assertTrue(owner.fetchSemanticsNode().boundsInRoot.height > before)
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-expanded.png") }
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { click() }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
        compose.onNodeWithContentDescription("Message Hermes").assertIsFocused()
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-outside-collapse.png") }
    }

    @Test fun `short final prompt has no fade or disclosure action`() {
        state.value = fixture("short")
        launch()
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        compose.onNodeWithContentDescription("You said: short").assertExists()
        assertCompletePrompt("Current prompt bubble", "short")
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-short.png") }
    }

    @Test fun `session and message replacement reset expansion`() {
        launch()
        compose.onNodeWithContentDescription("You said: $prompt").performClick()
        compose.runOnIdle { state.value = fixture(prompt, "height-session", "other-message") }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
        compose.onNodeWithContentDescription("You said: $prompt").performClick()
        compose.runOnIdle { state.value = fixture(prompt, "third-session", "other-message") }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
    }

    @Test fun `drag on collapsed prompt scrolls transcript without expanding`() {
        launch()
        val before = compose.onNodeWithTag("Transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithContentDescription("You said: $prompt").performTouchInput { swipeDown() }
        compose.onNodeWithTag("Prompt clipping fade", true).assertExists()
        val after = compose.onNodeWithTag("Transcript").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("drag must reach the real transcript", after < before)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun `expanded sticky body scrolls and return reveals full selectable inline owner`() {
        launch()
        val owner = compose.onNodeWithContentDescription("You said: $prompt")
        owner.performClick()
        owner.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80f) }
        compose.waitForIdle()
        assertTrue(owner.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        owner.performCustomAccessibilityActionWithLabel("Return to prompt")
        compose.onNodeWithTag("Inline final prompt").assertIsDisplayed()
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        assertEquals(1, compose.onAllNodes(hasContentDescription("You said: $prompt")).fetchSemanticsNodes().size)
        assertFullInlinePrompt(prompt)
    }

    @Test @Config(qualifiers = "w411dp-h400dp")
    fun `small viewport still clips at three measured lines`() {
        launch()
        assertThreeLineClip("Current prompt bubble", prompt)
        compose.runOnIdle { saveNativeCapture(compose.activity, "prompt-small-collapsed.png") }
    }

    @Test fun `outside long press and drag do not collapse disclosure`() {
        launch()
        compose.onNodeWithContentDescription("You said: $prompt").performClick()
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { longClick() }
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(80f, 0f))
        }
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
    }

    @Test fun `collapsed overflow return leaves readable response space and returns by touch`() {
        launch()
        assertThreeLineClip("Current prompt bubble", prompt)
        compose.onNodeWithContentDescription("Return to prompt").performTouchInput { click() }
        compose.onNodeWithTag("Inline final prompt").assertIsDisplayed()
        assertFullInlinePrompt(prompt)
        compose.onNodeWithContentDescription("Return to prompt").assertDoesNotExist()
    }

    @Test fun `overflow pinned return has separate touch target without consuming disclosure`() {
        launch()
        val owner = compose.onNodeWithContentDescription("You said: $prompt")
        val before = owner.fetchSemanticsNode().boundsInRoot.height
        owner.performTouchInput { click() }
        assertTrue(owner.fetchSemanticsNode().boundsInRoot.height > before)
        val action = compose.onNodeWithContentDescription("Return to prompt")
        action.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        action.performTouchInput { click() }
        compose.onNodeWithTag("Inline final prompt").assertIsDisplayed()
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        action.assertDoesNotExist()
        assertFullInlinePrompt(prompt)
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { click() }
        compose.onNodeWithContentDescription("Message Hermes").assertIsFocused()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun `wrapped sticky prompt clips rendered lines and return restores full inline text`() {
        val wrapped = "Synthetic wrapping words with no explicit line breaks. ".repeat(30)
        state.value = fixture(wrapped)
        launch()
        assertThreeLineClip("Current prompt bubble", wrapped)
        compose.onNodeWithContentDescription("You said: $wrapped")
            .performCustomAccessibilityActionWithLabel("Return to prompt")
        assertFullInlinePrompt(wrapped)
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun `exactly three lines stay complete without fade or disclosure in both owners`() {
        val exact = "First synthetic line\nSecond synthetic line\nThird synthetic line"
        state.value = fixture(exact)
        launch()
        assertCompletePrompt("Current prompt bubble", exact)
        compose.onNodeWithContentDescription("You said: $exact")
            .performCustomAccessibilityActionWithLabel("Return to prompt")
        assertFullInlinePrompt(exact)
    }

    @Test fun `larger scaled monospace typography determines collapsed clip and third line fade`() {
        launch(fontScale = 1.6f, appearance = AppearanceSelection("cyberpunk"))
        assertThreeLineClip("Current prompt bubble", prompt)
    }

    @Test fun `viewport changes such as keyboard arrival do not change sticky collapsed height`() {
        val viewport = mutableStateOf(800.dp)
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                measurer = rememberTextMeasurer()
                bodyStyle = HermesTheme.type.body
                textDensity = LocalDensity.current
                CompositionLocalProvider(LocalFinalPromptDisclosure provides FinalPromptDisclosure("viewport", mutableStateOf(false))) {
                    FinalUserPromptBubble(prompt, viewport.value, Modifier.width(280.dp).testTag("Viewport prompt"), onReturn = {})
                }
            }
        }
        compose.waitForIdle()
        assertThreeLineClip("Viewport prompt", prompt)
        val before = compose.onNodeWithTag("Viewport prompt", true).fetchSemanticsNode().size.height
        compose.runOnIdle { viewport.value = 160.dp }
        assertThreeLineClip("Viewport prompt", prompt)
        assertEquals(before, compose.onNodeWithTag("Viewport prompt", true).fetchSemanticsNode().size.height)
    }

    @Test fun `earlier historical user prompt keeps its full rendered height`() {
        val earlier = "Earlier synthetic line\n".repeat(10).trimEnd()
        state.value = fixture("short").let {
            it.copy(transcript = listOf(UserTurn("historical-user", earlier, 1_700_000_000_000L), it.transcript.first()), isStreaming = false)
        }
        launch()
        compose.onNodeWithTag("Transcript").performScrollToIndex(0)
        val bubble = compose.onNodeWithTag("Transcript item historical-user").fetchSemanticsNode()
        val layout = measure(earlier, px(320f))
        assertTrue(layout.lineCount > 3)
        assertEquals(layout.size.height + px(18f), bubble.size.height.toFloat(), 1f)
    }

    @Test fun `native ink fades only third line without ellipsis or padding overlap`() {
        val ink = "MMMMMMMMMM\nMMMMMMMMMM\nMMMMMMMMMM\nMMMMMMMMMM"
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                measurer = rememberTextMeasurer()
                bodyStyle = HermesTheme.type.body
                textDensity = LocalDensity.current
                CompositionLocalProvider(LocalFinalPromptDisclosure provides FinalPromptDisclosure("ink", mutableStateOf(false))) {
                    Column {
                        FinalUserPromptBubble(ink, 800.dp, Modifier.width(280.dp).testTag("Ink clipped prompt"), onReturn = {})
                        UserTurnBubble(ink.substringBeforeLast('\n'), null, Modifier.width(280.dp).testTag("Ink reference prompt"))
                    }
                }
            }
        }
        compose.waitForIdle()
        assertThreeLineClip("Ink clipped prompt", ink)
        val clipped = compose.onNodeWithTag("Ink clipped prompt", true).fetchSemanticsNode().boundsInWindow
        val reference = compose.onNodeWithTag("Ink reference prompt", true).fetchSemanticsNode().boundsInWindow
        val layout = measure(ink, clipped.width, maxLines = 3)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val left = px(12f).roundToInt()
                val right = clipped.width.roundToInt() - left
                val top = px(9f).roundToInt()
                val thirdTop = top + ceil(layout.getLineTop(2)).toInt()
                val thirdBottom = top + ceil(layout.getLineBottom(2)).toInt()
                fun pixel(bounds: androidx.compose.ui.geometry.Rect, x: Int, y: Int) =
                    bitmap.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y)
                val background = pixel(reference, left, 2)
                fun contrast(color: Int): Int = listOf(16, 8, 0).sumOf { shift ->
                    abs((color shr shift and 255) - (background shr shift and 255))
                }
                var fadedPixels = 0
                var thirdInk = 0
                for (y in 1 until clipped.height.roundToInt() - 1) for (x in left until right) {
                    val actual = pixel(clipped, x, y)
                    val expected = pixel(reference, x, y)
                    if (y < thirdTop || y >= thirdBottom) {
                        assertEquals("line two and padding must not fade at $x,$y", expected, actual)
                    } else {
                        if (contrast(expected) > 30) thirdInk++
                        if (contrast(actual) + 6 < contrast(expected)) fadedPixels++
                        assertTrue("clipping must not add ellipsis ink", contrast(actual) <= contrast(expected) + 6)
                    }
                }
                assertTrue("third-line reference must contain actual glyph ink", thirdInk > 20)
                assertTrue("third-line glyphs must visibly fade", fadedPixels > 20)
                saveNativeCapture(compose.activity, "prompt-three-line-ink.png")
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun px(dp: Float) = with(textDensity) { dp.dp.toPx() }

    private fun measure(body: String, bubbleWidth: Float, maxLines: Int = Int.MAX_VALUE): TextLayoutResult = measurer.measure(
        AnnotatedString(body), style = bodyStyle,
        constraints = Constraints(maxWidth = (bubbleWidth - px(24f)).roundToInt()),
        maxLines = maxLines,
    )

    private fun assertThreeLineClip(tag: String, body: String) {
        val bubble = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
        val layout = measure(body, bubble.width)
        assertTrue("fixture must overflow three rendered lines", layout.lineCount > 3)
        val clipped = measure(body, bubble.width, maxLines = 3)
        val textTop = bubble.top + px(9f)
        val thirdBottom = ceil(clipped.getLineBottom(2))
        assertEquals("collapsed body must end after rendered line three", thirdBottom + px(18f), bubble.height, 1f)
        val fade = compose.onNodeWithTag("Prompt clipping fade", true).fetchSemanticsNode().boundsInRoot
        assertEquals("fade must start at line three, never line two", textTop + clipped.getLineTop(2), fade.top, 1f)
        assertEquals("fade must stop before bottom padding", textTop + thirdBottom, fade.bottom, 1f)
    }

    private fun assertFullInlinePrompt(body: String) {
        compose.onNodeWithTag("Inline final prompt").assertIsDisplayed()
        val bubble = compose.onNodeWithTag("Final prompt body", true).fetchSemanticsNode()
        val layout = measure(body, bubble.size.width.toFloat())
        assertEquals("inline original must render the entire message without expansion", layout.size.height + px(18f), bubble.size.height.toFloat(), 1f)
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        assertTrue("inline original must not require disclosure", SemanticsProperties.StateDescription !in
            compose.onNodeWithContentDescription("You said: $body").fetchSemanticsNode().config)
    }

    private fun assertCompletePrompt(tag: String, body: String) {
        val bubble = compose.onNodeWithTag(tag, true).fetchSemanticsNode().boundsInRoot
        val layout = measure(body, bubble.width)
        assertTrue(layout.lineCount <= 3)
        assertEquals("short prompt must retain every rendered line", layout.size.height + px(18f), bubble.height, 1f)
        compose.onNodeWithTag("Prompt clipping fade", true).assertDoesNotExist()
        assertTrue("short prompt must not expose expand or collapse", SemanticsProperties.StateDescription !in
            compose.onNodeWithContentDescription("You said: $body").fetchSemanticsNode().config)
    }

    private fun launch(fontScale: Float = 1f, appearance: AppearanceSelection = AppearanceSelection()) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                HermesTheme(appearance) {
                    measurer = rememberTextMeasurer()
                    bodyStyle = HermesTheme.type.body
                    textDensity = LocalDensity.current
                    ChatScreen(state.value, ChatActions(), {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun fixture(body: String, session: String = "height-session", message: String = "height-user") = ChatUiState(
        activeSession = SessionSummary(id = session, title = "Synthetic chat", preview = "", lastActiveAtMillis = 1_700_000_000_000L, status = SessionStatus.Working),
        transcript = listOf(UserTurn(message, body, 1_700_000_000_000L),
            AssistantTurn("reply", (1..80).joinToString("\n\n") { "Synthetic response paragraph $it." }, 1_700_000_000_000L, streaming = true)),
        isStreaming = true,
    )
}
