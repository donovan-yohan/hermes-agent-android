package com.hermesagent.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.chat.Composer
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.QuietIconButton
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.common.SegmentedControl
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two accessibility claims this slice makes, measured rather than asserted
 * in prose: a streaming turn announces itself, and every shared control is big
 * enough to hit.
 *
 * Both are things a sighted developer on a mouse cannot notice, which is why
 * they need a gate rather than a review pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(ChatUiState())

    // ── Streaming status ──────────────────────────────────────────────────

    @Test
    fun `a streaming turn is announced once as a polite live region`() {
        launchChat(streaming = true)

        compose.onNodeWithContentDescription(WORKING)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

        // One node, not one per dot and not one per delta: the description is a
        // constant, so growing the reply cannot re-announce it.
        assertEquals(1, compose.countWithContentDescription(WORKING))
        state = chatState(streaming = true, markdown = "Still going, with a good deal more text now.")
        compose.waitForIdle()
        assertEquals(1, compose.countWithContentDescription(WORKING))
    }

    @Test
    fun `a settled turn announces nothing`() {
        launchChat(streaming = false)
        assertEquals(0, compose.countWithContentDescription(WORKING))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w700dp-h800dp")
    fun `transient progress appears once at the live transcript tail`() {
        launchChat(
            streaming = true,
            progress = SessionProgress(kind = "thinking", text = CONTEMPLATING),
        )
        state = state.copy(
            connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
        )
        compose.waitForIdle()

        compose.onAllNodesWithText(CONTEMPLATING).assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Composer status").assertCountEquals(0)
    }

    // ── Touch targets ─────────────────────────────────────────────────────

    @Test
    fun `every shared control meets the touch target floor`() {
        val floor = HermesSpacing().touchTarget
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Column {
                    SegmentedControl(
                        options = listOf("Light", "Dark"),
                        selected = "Dark",
                        label = { it },
                        onSelect = {},
                    )
                    PrimaryButton(label = "Connect", onClick = {})
                    TextButton(label = "Forget key", onClick = {})
                    QuietIconButton(
                        icon = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        onClick = {},
                    )
                }
            }
        }

        // Assert on the tappable node, which is what a finger has to find —
        // the label inside it is allowed to be as small as the type scale says.
        compose.onNodeWithContentDescription("Dark").assertHeightIsAtLeast(floor)
        compose.onNodeWithText("Connect").assertHeightIsAtLeast(floor)
        compose.onNodeWithText("Forget key").assertHeightIsAtLeast(floor)
        compose.onNodeWithContentDescription("Settings").assertHeightIsAtLeast(floor)
    }

    @Test
    fun `segmented choices expose one radio selection`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                SegmentedControl(
                    options = listOf("Light", "Dark"),
                    selected = "Dark",
                    label = { it },
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Light")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        compose.onNodeWithContentDescription("Dark")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    @Test
    fun `editors own 48dp targets center natural content and grow for multiple lines`() {
        var draft by mutableStateOf("")
        var query by mutableStateOf("")
        val floor = HermesSpacing().touchTarget

        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Column {
                    Composer(
                        draft = draft,
                        onDraftChange = { draft = it },
                        onSend = {},
                        onStop = {},
                        isStreaming = false,
                        canSend = true,
                        connected = true,
                        statusLine = "",
                    )
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search sessions",
                    )
                }
            }
        }

        // The empty-state labels are visual, natural-height text — centered by
        // the editor's touch target instead of stretched to its full height.
        assertNaturalTextCentered("Composer field shell", "Message Hermes")
        assertNaturalTextCentered("Search field shell", "Search sessions")
        assertContentUsesShellWidth("Composer field shell", "Composer text content")
        assertContentUsesShellWidth("Search field shell", "Search text content")

        compose.onNodeWithContentDescription("Message Hermes")
            .assertHeightIsAtLeast(floor)
            .performTextInput("A centered composer line")
        compose.onNodeWithContentDescription("Search sessions")
            .assertHeightIsAtLeast(floor)
            .performTextInput("A centered search line")
        compose.waitForIdle()

        assertTypedLineCentered(
            shell = "Composer field shell",
            textContent = "Composer text content",
        )
        assertTypedLineCentered(
            shell = "Search field shell",
            textContent = "Search text content",
        )

        // This has a deliberately long first line plus more explicit lines
        // than the six-line cap. The editor must grow beyond its 48dp target,
        // then stop instead of taking over the transcript.
        compose.onNodeWithContentDescription("Message Hermes").performTextClearance()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput(MULTILINE_DRAFT)
        compose.waitForIdle()

        val cappedEditorBounds = compose.onNodeWithContentDescription("Message Hermes")
            .fetchSemanticsNode()
            .boundsInRoot
        val floorPx = with(compose.density) { floor.toPx() }
        assertTrue("a multiline draft must grow above the 48dp target", cappedEditorBounds.height > floorPx)

        compose.onNodeWithContentDescription("Message Hermes").performTextClearance()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput(SIX_LINE_DRAFT)
        compose.waitForIdle()
        val sixLineHeight = compose.onNodeWithContentDescription("Message Hermes")
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        assertEquals(
            "more than six visual lines must not grow the composer past its six-line height",
            sixLineHeight,
            cappedEditorBounds.height,
            geometryTolerancePx(),
        )
        assertContentUsesShellWidth("Composer field shell", "Composer text content")

        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", query)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private fun launchChat(streaming: Boolean, progress: SessionProgress? = null) {
        state = chatState(streaming, markdown = "Working on it.").let { current ->
            current.copy(activeSession = current.activeSession?.copy(progress = progress))
        }
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ChatScreen(state = state, actions = ChatActions(), onOpenSettings = {})
            }
        }
        compose.waitForIdle()
    }

    private fun chatState(streaming: Boolean, markdown: String) = ChatUiState(
        activeSession = SessionSummary(
            id = SESSION,
            title = "Streaming turn",
            preview = "",
            lastActiveAtMillis = NOW,
        ),
        transcript = transcript(streaming, markdown),
        isStreaming = streaming,
    )

    private fun transcript(streaming: Boolean, markdown: String): List<TranscriptEntry> = listOf(
        AssistantTurn(id = "$SESSION-a1", markdown = markdown, atMillis = NOW, streaming = streaming),
    )

    private fun assertTypedLineCentered(shell: String, textContent: String) {
        assertCenteredInShell(
            shell = shell,
            content = compose.onNodeWithTag(textContent, useUnmergedTree = true),
            description = textContent,
        )
    }

    private fun assertNaturalTextCentered(shell: String, text: String) {
        assertCenteredInShell(
            shell = shell,
            content = compose.onNodeWithText(text, useUnmergedTree = true),
            description = text,
        )
    }

    private fun assertCenteredInShell(
        shell: String,
        content: SemanticsNodeInteraction,
        description: String,
    ) {
        val shellBounds = compose.onNodeWithTag(shell).fetchSemanticsNode().boundsInRoot
        val contentBounds = content.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$description must stay vertically centered in $shell",
            kotlin.math.abs(shellBounds.center.y - contentBounds.center.y) <= geometryTolerancePx(),
        )
    }

    private fun assertContentUsesShellWidth(shell: String, textContent: String) {
        val shellBounds = compose.onNodeWithTag(shell).fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithTag(textContent, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val tolerance = geometryTolerancePx()
        val expectedWidth = shellBounds.width - with(compose.density) { 28.dp.toPx() }
        assertTrue("$textContent must use the shell's usable width", contentBounds.width >= expectedWidth - tolerance)
        assertTrue("$textContent must stay within $shell", contentBounds.left >= shellBounds.left - tolerance)
        assertTrue("$textContent must stay within $shell", contentBounds.right <= shellBounds.right + tolerance)
    }

    private fun geometryTolerancePx(): Float = with(compose.density) { 1.dp.toPx() }

    private companion object {
        const val SESSION = "s-a11y"
        const val NOW = 1_755_600_000_000L
        const val WORKING = "Hermes is working"
        const val CONTEMPLATING = "Contemplating…"
        val SIX_LINE_DRAFT = (1..6).joinToString("\n") { "Line $it" }
        val MULTILINE_DRAFT = """
            This first line deliberately has enough words to wrap across the full composer width before it ends.
            Second explicit line.
            Third explicit line.
            Fourth explicit line.
            Fifth explicit line.
            Sixth explicit line.
            Seventh explicit line.
        """.trimIndent()
    }
}

private fun ComposeContentTestRule.countWithContentDescription(description: String): Int =
    onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size
