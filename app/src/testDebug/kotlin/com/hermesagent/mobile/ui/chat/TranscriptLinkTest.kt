package com.hermesagent.mobile.ui.chat

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.ui.saveNativeCapture
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real ChatScreen text hit-testing, not a whole-node semantics click. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", shadows = [ShadowSelectionMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptLinkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()
    private var openFailure: RuntimeException? = null
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            openFailure?.let { throw it }
            opened += uri
        }
    }

    @Test
    fun `touching a formatted markdown label opens its exact destination`() {
        launch("Try [**open** the *sandbox*]($DESTINATION) now.")

        touchText("Try open the sandbox now.", "sandbox")

        assertEquals(listOf(DESTINATION), opened)
    }

    @Test
    fun `touching a bare URL opens the destination without trailing punctuation`() {
        launch("Visit $DESTINATION.")

        touchText("Visit $DESTINATION.", "example.com")

        assertEquals(listOf(DESTINATION), opened)
    }

    @Test
    fun `plain markdown angle and reference labels open exact destinations`() {
        launch("[plain]($DESTINATION) and <$DESTINATION> and [reference][target]\n\n[target]: $DESTINATION")
        val visible = "plain and $DESTINATION and reference"
        touchText(visible, "plain")
        touchText(visible, "example.com")
        touchText(visible, "reference")
        assertEquals(List(3) { DESTINATION }, opened)
    }

    @Test
    fun `heading bullet number and table cell links have independent touch targets`() {
        launch("# [Heading]($DESTINATION)\n\n- [Bullet]($DESTINATION)\n\n1. [Number]($DESTINATION)\n\n| [Header]($DESTINATION) |\n|---|\n| [Cell]($DESTINATION) |")
        for (label in listOf("Heading", "Bullet", "Number", "Header", "Cell")) touchText(label, label)
        assertEquals(List(5) { DESTINATION }, opened)
    }

    @Test
    fun `inline and fenced code URLs are clickable without interpreting code markdown`() {
        val inline = "run [literal]($DESTINATION) **keep**"
        val fence = "curl $DESTINATION"
        launch("`$inline`\n\n```sh\n$fence\n```")
        touchText(inline, "example.com")
        touchText(fence, "example.com")
        assertEquals(List(2) { DESTINATION }, opened)
    }

    @Test
    fun `unsafe explicit labels and plain text never launch`() {
        launch("[https://example.org/](javascript:bad) [file](file:///sdcard/test) and ordinary words")
        val visible = "https://example.org/ file and ordinary words"
        touchText(visible, "example.org")
        touchText(visible, "file")
        touchText(visible, "ordinary")
        assertTrue(opened.isEmpty())
        val text = textLayout(visible).layoutInput.text
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test
    fun `unavailable and refused URI handlers do not crash the chat`() {
        launch("[open]($DESTINATION)")
        for (failure in listOf(
            ActivityNotFoundException("No browser"),
            IllegalArgumentException("No handler"),
            SecurityException("Refused"),
        )) {
            openFailure = failure
            touchText("open", "open")
        }
        openFailure = null
        touchText("open", "open")
        assertEquals(listOf(DESTINATION), opened)
    }

    @Test
    fun `link formatting and annotation ranges preserve visible characters`() {
        launch("[**open** the *sandbox*]($DESTINATION) and `https://example.org/`")
        val text = textLayout("open the sandbox and https://example.org/").layoutInput.text
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("open the sandbox", "https://example.org/"), links.map { text.text.substring(it.start, it.end) })
        assertEquals(2, links.size)
        assertTrue(text.spanStyles.any { it.start == 0 && it.end == 4 && it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.SemiBold })
        assertTrue(text.spanStyles.any { it.start == 9 && it.end == 16 && it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic })
        assertTrue(text.spanStyles.any { it.start == 21 && it.item.fontFamily != null })
    }

    @Test
    fun `copy reply keeps markdown labels and literal code instead of destinations`() {
        launch("[**open** the *sandbox*]($DESTINATION)\n\n```sh\ncurl $DESTINATION\n```")
        compose.onNodeWithContentDescription("Copy reply").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("open the sandbox\n\ncurl $DESTINATION", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `linked prose remains selectable without changing the visible label`() {
        launch("Read [the sandbox]($DESTINATION) now.")
        val node = compose.onNodeWithText("Read the sandbox now.", useUnmergedTree = true)
        // Compose's LinkAnnotation child owns presses on the link itself;
        // start in the prose to verify the surrounding selectable Text remains.
        val point = textLayout("Read the sandbox now.").getBoundingBox(1).center
        node.performTouchInput { longClick(point) }
        compose.waitForIdle()
        assertTrue(opened.isEmpty())
        val handles = compose.onAllNodes(SemanticsMatcher("selection handle") { semantics ->
            semantics.config.any { (key, _) -> key.name == "SelectionHandleInfo" }
        }, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("a link remains selectable", handles.isNotEmpty())
    }

    @Test
    fun `all link forms render together in the production chat`() {
        launch("# Clickable agent URLs\n\nTry [**open** the *sandbox*]($DESTINATION).\n\nBare https://example.org/run, <https://example.org/angle> and [reference][target].\n\nInline `https://localhost:8782/activity`\n\n```sh\ncurl https://127.0.0.1:8782/activity\n```\n\n[target]: https://example.org/reference")
        compose.runOnIdle { saveNativeCapture(compose.activity, "transcript-links.png") }
        touchText("Try open the sandbox.", "sandbox")
        touchText("Bare https://example.org/run, https://example.org/angle and reference.", "example.org/run")
        touchText("Inline https://localhost:8782/activity", "localhost")
        touchText("curl https://127.0.0.1:8782/activity", "127.0.0.1")
        assertEquals(listOf(DESTINATION, "https://example.org/run", "https://localhost:8782/activity", "https://127.0.0.1:8782/activity"), opened)
    }

    private fun launch(markdown: String) {
        val state = ChatUiState(
            activeSession = SessionSummary(
                id = "s-links",
                title = "Links",
                preview = "",
                lastActiveAtMillis = NOW,
                status = SessionStatus.Idle,
            ),
            transcript = listOf(AssistantTurn(id = "a-links", markdown = markdown, atMillis = NOW)),
        )
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                    ChatScreen(state = state, actions = ChatActions(), onOpenSettings = {})
                }
            }
        }
        compose.waitForIdle()
    }

    private fun touchText(visibleText: String, target: String) {
        val node = compose.onNodeWithText(visibleText, useUnmergedTree = true)
        val layout = textLayout(visibleText)
        val offset = layout.layoutInput.text.text.indexOf(target)
        assertTrue("'$target' must be visible", offset >= 0)
        val point = layout.getBoundingBox(offset).center
        node.performTouchInput { click(point) }
        compose.waitForIdle()
    }

    private fun textLayout(visibleText: String): TextLayoutResult {
        val node = compose.onNodeWithText(visibleText, useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        val read = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("the production Text must expose its actual layout", read != null && read(layouts))
        return layouts.single()
    }

    private companion object {
        const val NOW = 1_755_600_000_000L
        const val DESTINATION = "https://example.com:8782/activity?tab=run%20log#latest"
    }
}
