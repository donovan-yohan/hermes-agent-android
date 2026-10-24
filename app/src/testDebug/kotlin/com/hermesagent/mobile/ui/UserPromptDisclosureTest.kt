package com.hermesagent.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Synthetic production ChatScreen ownership journeys; no Gateway or device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserPromptDisclosureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = (1..20).joinToString(" ") { "Synthetic passage $it wraps naturally without explicit line breaks." }
    private val second = (1..55).joinToString("\n") { "Current synthetic line $it" }
    private var state by mutableStateOf(fixture())
    private lateinit var measurer: TextMeasurer
    private lateinit var style: TextStyle
    private lateinit var density: Density

    @Test fun `all inline users collapse measured lines and expand independently`() {
        launch()
        top()
        for (body in listOf(first, second)) assertCap(body)
        owner(first).performTouchInput { click() }
        assertFull(first)
        // The opening tap cannot collapse the newly expanded entry.
        assertExpanded(first)
        top()
        assertFull(first)
        compose.onNodeWithTag("Transcript").performScrollToIndex(2)
        owner(second).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test @Config(qualifiers = "w1100dp-h891dp")
    fun `wide scaled monospace inline cap follows actual typography`() {
        launch(scale = 1.6f, theme = "cyberpunk")
        top()
        assertCap(first)
        compose.onNodeWithTag("Transcript").performScrollToIndex(2)
        assertCap(second)
    }

    @Test fun `three and shorter inline lines have no fade or disclosure`() {
        val exact = "First line\nSecond line\nThird line"
        state = state.copy(transcript = listOf(UserTurn("u1", exact, NOW), UserTurn("u2", "Short", NOW)))
        launch(); top()
        for (body in listOf(exact, "Short")) {
            val node = owner(body).fetchSemanticsNode()
            assertFalse(SemanticsProperties.StateDescription in node.config)
            assertFalse(SemanticsActions.OnClick in node.config)
            assertFull(body)
        }
        compose.onAllNodesWithTag("Prompt clipping fade", true).assertCountEquals(0)
    }

    @Test fun `expanded historical source stays complete while reading its middle and pins only after leaving`() {
        launch(); top()
        owner(first).performTouchInput { click() }
        assertFull(first)
        val original = owner(first).fetchSemanticsNode()
        scroll(original.size.height / 2f)
        assertFull(first)
        assertExpanded(first)
        compose.onAllNodesWithTag("Pinned final prompt").assertCountEquals(0)
        assertTrue(owner(first).fetchSemanticsNode().positionInRoot.y < transcriptTop())
        // Handoff is measured from the full source, not a guessed scroll distance.
        val source = owner(first).fetchSemanticsNode()
        scroll(source.positionInRoot.y + source.size.height - transcriptTop() + 2f)
        val pinned = compose.onNode(hasContentDescription("You said: $first") and hasTestTag("Pinned final prompt"))
        pinned.assertIsDisplayed()
        assertExpanded(first)
        compose.onAllNodesWithContentDescription("You said: $first").assertCountEquals(1)
        scroll(-5f)
        compose.onAllNodesWithTag("Pinned final prompt").assertCountEquals(0)
        assertFull(first)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun `historical collapsed Return expands captured source in one action`() {
        launch(); top()
        // Advance through the first collapsed source into its reply.
        val row = compose.onNodeWithTag("Transcript item u1").fetchSemanticsNode()
        scroll(row.positionInRoot.y + row.size.height - transcriptTop() + 4f)
        val pinned = compose.onNode(hasContentDescription("You said: $first") and hasTestTag("Pinned final prompt"))
        pinned.assertIsDisplayed()
        pinned.performCustomAccessibilityActionWithLabel("Return to prompt")
        compose.waitForIdle()
        assertFull(first)
        assertExpanded(first)
        compose.onAllNodesWithContentDescription("You said: $first").assertCountEquals(1)
    }

    @Test fun `new final and history prepend preserve the prior expansion`() {
        launch(); top()
        owner(first).performTouchInput { click() }
        compose.runOnIdle {
            state = state.copy(transcript = listOf(UserTurn("older", "Older", NOW - 1)) + state.transcript +
                UserTurn("new", "New wrapped synthetic words. ".repeat(15).trim(), NOW + 1))
        }
        top()
        assertExpanded(first); assertFull(first)
        compose.onNodeWithContentDescription("You said: Older").assertIsDisplayed()
    }

    @Test fun `expanded final remains expanded when a new final arrives and lazy source remounts`() {
        state = state.copy(transcript = listOf(UserTurn("u1", first, NOW),
            AssistantTurn("a1", "Synthetic reply.\n\n".repeat(45), NOW)))
        launch(); top(); owner(first).performTouchInput { click() }
        assertExpanded(first)
        compose.runOnIdle { state = state.copy(transcript = state.transcript + listOf(
            UserTurn("new-final", second, NOW + 1), AssistantTurn("new-reply", "Synthetic new reply.\n\n".repeat(40), NOW + 1))) }
        compose.onNodeWithTag("Transcript").performScrollToIndex(3)
        top(); assertFull(first); assertExpanded(first)
        compose.onNodeWithTag("Transcript").performScrollToIndex(2)
        assertCap(second)
    }

    @Test fun `durable row reprojection and same render fallback enrichment retain expansion`() {
        state = state.copy(transcript = listOf(UserTurn("u1", first, NOW, TranscriptRowId(21))))
        launch(); top(); owner(first).performTouchInput { click() }
        compose.runOnIdle { state = state.copy(transcript = listOf(UserTurn("projected-again", first, NOW, TranscriptRowId(21)))) }
        top(); assertExpanded(first); assertFull(first)
    }

    @Test fun `same render id can enrich fallback but identical text with unrelated identity cannot`() {
        state = state.copy(transcript = listOf(UserTurn("u1", first, NOW)))
        launch(); top(); owner(first).performTouchInput { click() }
        compose.runOnIdle { state = state.copy(transcript = listOf(UserTurn("u1", first, NOW, TranscriptRowId(21)))) }
        top(); assertExpanded(first)
        compose.runOnIdle { state = state.copy(transcript = listOf(UserTurn("unrelated", first, NOW, TranscriptRowId(22)))) }
        top(); assertCap(first)
    }

    @Test fun `endpoint and session scopes cannot inherit same row expansion`() {
        state = state.copy(transcript = listOf(UserTurn("u1", first, NOW, TranscriptRowId(21))))
        launch(); top(); owner(first).performTouchInput { click() }
        compose.runOnIdle { state = state.copy(diagnosticsEndpointGeneration = 1) }
        top(); assertCap(first)
        owner(first).performTouchInput { click() }
        compose.runOnIdle { state = state.copy(activeSession = state.activeSession!!.copy(id = "other")) }
        top(); assertCap(first)
    }

    @Test fun `save and restore keeps mounted session expansion and remount disclosure`() {
        val restoration = StateRestorationTester(compose)
        state = state.copy(transcript = listOf(UserTurn("u1", first, NOW, TranscriptRowId(21))))
        restoration.setContent { content() }
        top(); owner(first).performTouchInput { click() }
        restoration.emulateSavedInstanceStateRestore()
        top(); assertFull(first); assertExpanded(first)
        owner(first).performTouchInput { click() }
        assertCap(first)
    }

    @Test fun `outside taps collapse visible historical owner without stealing composer focus`() {
        launch(); top(); owner(first).performTouchInput { click() }
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { click() }
        compose.onNodeWithContentDescription("Message Hermes").assertIsFocused()
        top(); assertCap(first)
    }

    @Test fun `one outside tap collapses each currently visible expanded source`() {
        val one = (1..5).joinToString("\n") { "First $it" }
        val two = (1..5).joinToString("\n") { "Second $it" }
        state = state.copy(transcript = listOf(UserTurn("u1", one, NOW), UserTurn("u2", two, NOW)))
        launch(); top()
        owner(one).performTouchInput { click() }
        owner(two).performTouchInput { click() }
        // Opening the second is outside the first and collapses only the captured expanded set.
        assertExpanded(two)
        owner(one).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { click() }
        owner(two).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test fun `outside tap after historical source disposal collapses visible sticky only`() {
        state = state.copy(transcript = state.transcript.map { entry ->
            if (entry is AssistantTurn && entry.id == "a1") entry.copy(markdown = "Synthetic reply.\n\n".repeat(60)) else entry
        })
        launch(); top(); owner(first).performTouchInput { click() }
        val source = owner(first).fetchSemanticsNode()
        scroll(source.positionInRoot.y + source.size.height - transcriptTop() + 5f)
        compose.onNode(hasContentDescription("You said: $first") and hasTestTag("Pinned final prompt")).assertIsDisplayed()
        assertExpanded(first)
        compose.onNodeWithContentDescription("Message Hermes").performTouchInput { click() }
        compose.onNodeWithContentDescription("Message Hermes").assertIsFocused()
        owner(first).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        top(); assertCap(first)
    }

    @Test fun `outside multitouch long press and drag are not collapse taps`() {
        launch(); top(); owner(first).performTouchInput { click() }
        val composer = compose.onNodeWithContentDescription("Message Hermes")
        composer.performTouchInput { longClick() }
        assertExpanded(first)
        composer.performTouchInput { swipe(center, center + Offset(70f, 0f)) }
        assertExpanded(first)
        composer.performTouchInput {
            down(0, center); down(1, center + Offset(15f, 0f)); advanceEventTime(40)
            up(1); up(0)
        }
        assertExpanded(first)
    }

    @Test fun `ordinary dark route renders collapsed expanded middle and sticky user owners`() = renderOwners(false)
    @Test fun `ordinary light route renders collapsed expanded middle and sticky user owners`() = renderOwners(true)

    private fun renderOwners(light: Boolean) {
        state = state.copy(transcript = state.transcript.map { entry ->
            if (entry is AssistantTurn && entry.id == "a1") entry.copy(markdown = "Synthetic intervening reply.\n\n".repeat(50)) else entry
        })
        compose.setContent { content(light = light) }
        top()
        val mode = if (light) "light" else "dark"
        compose.runOnIdle { saveNativeCapture(compose.activity, "ordinary-users-$mode-collapsed.png") }
        owner(first).performTouchInput { click() }
        assertFull(first)
        compose.runOnIdle { saveNativeCapture(compose.activity, "ordinary-users-$mode-expanded.png") }
        scroll(owner(first).fetchSemanticsNode().size.height / 2f)
        assertFull(first)
        compose.runOnIdle { saveNativeCapture(compose.activity, "ordinary-users-$mode-middle.png") }
        val source = owner(first).fetchSemanticsNode()
        scroll(source.positionInRoot.y + source.size.height - transcriptTop() + 4f)
        assertExpanded(first)
        compose.runOnIdle { saveNativeCapture(compose.activity, "ordinary-users-$mode-sticky.png") }
    }

    @Test fun `retired source bounds cannot collapse or shelter replacement sticky mount`() {
        val owner = com.hermesagent.mobile.ui.chat.PromptDisclosures()
        val entry = owner.entry(UserTurn("row", first, NOW))
        entry.expanded.value = true
        val source = Any(); val sticky = Any()
        val sourceBounds = androidx.compose.ui.geometry.Rect(0f, 0f, 300f, 400f)
        val stickyBounds = androidx.compose.ui.geometry.Rect(300f, 0f, 400f, 100f)
        owner.register(source, entry, sourceBounds)
        val beforeHandoff = owner.expandedMounts()
        owner.unregister(source)
        owner.register(sticky, entry, stickyBounds)
        owner.collapseOutside(beforeHandoff, Offset(500f, 500f), Offset(500f, 500f))
        assertTrue("retiring source has no authority over replacement", entry.expanded.value)
        owner.collapseOutside(owner.expandedMounts(), Offset(350f, 50f), Offset(350f, 50f))
        assertTrue(entry.expanded.value)
        owner.collapseOutside(owner.expandedMounts(), Offset(50f, 50f), Offset(50f, 50f))
        assertFalse("stale source bounds cannot shelter an outside tap", entry.expanded.value)
    }

    private fun owner(body: String) = compose.onNodeWithContentDescription("You said: $body")
    private fun assertExpanded(body: String) = owner(body).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    private fun assertCap(body: String) {
        val node = owner(body).fetchSemanticsNode()
        val layout = measure(body, node.size.width, 3)
        assertTrue("fixture must overflow actual rendered lines", layout.didOverflowHeight)
        assertEquals(layout.size.height + px(18f), node.size.height.toFloat(), 1f)
        owner(body).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }
    private fun assertFull(body: String) {
        val node = owner(body).fetchSemanticsNode()
        val layout = measure(body, node.size.width, Int.MAX_VALUE)
        assertEquals("full layout includes offscreen text", (layout.size.height + px(18f)).coerceAtLeast(px(48f)), node.size.height.toFloat(), 1f)
    }
    private fun measure(body: String, width: Int, lines: Int) = measurer.measure(body, style,
        maxLines = lines, constraints = Constraints(maxWidth = (width - px(24f)).roundToInt()))
    private fun px(dp: Float) = with(density) { dp.dp.toPx() }
    private fun transcriptTop() = compose.onNodeWithTag("Transcript").fetchSemanticsNode().boundsInRoot.top
    private fun scroll(y: Float) {
        compose.onNodeWithTag("Transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, y) }
        compose.waitForIdle()
    }
    private fun top() {
        compose.onNodeWithTag("Transcript").performScrollToIndex(0)
        scroll(-10000f)
    }
    private fun launch(scale: Float = 1f, theme: String = "nous") {
        compose.setContent { content(scale, theme) }
        compose.waitForIdle()
    }
    @Composable private fun content(scale: Float = 1f, theme: String = "nous", light: Boolean = false) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            HermesTheme(AppearanceSelection(theme, if (light) HermesThemeMode.Light else HermesThemeMode.Dark)) {
                measurer = rememberTextMeasurer(); style = HermesTheme.type.body; density = LocalDensity.current
                ChatScreen(state, ChatActions(), {})
            }
        }
    }
    private fun fixture() = ChatUiState(
        activeSession = SessionSummary("synthetic-disclosure", "Synthetic chat", "", NOW),
        transcript = listOf(UserTurn("u1", first, NOW),
            AssistantTurn("a1", "Synthetic intervening reply.\n\n".repeat(20), NOW),
            UserTurn("u2", second, NOW), AssistantTurn("a2", "Synthetic final reply.\n\n".repeat(40), NOW)),
    )
    private companion object { const val NOW = 1_700_000_000_000L }
}
