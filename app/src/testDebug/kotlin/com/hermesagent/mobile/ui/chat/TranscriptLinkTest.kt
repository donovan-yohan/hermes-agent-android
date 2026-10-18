package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptLinkTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    @Test
    fun `touching a formatted markdown label opens its exact destination`() {
        launch("Try [**open** the *sandbox*]($DESTINATION) now.")

        touchText("Try open the sandbox now.", "sandbox")

        assertEquals(listOf(DESTINATION), opened)
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
        val layouts = mutableListOf<TextLayoutResult>()
        val read = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        assertTrue("the production Text must expose its actual layout", read != null && read(layouts))
        val layout = layouts.single()
        val offset = layout.layoutInput.text.text.indexOf(target)
        assertTrue("'$target' must be visible", offset >= 0)
        val point = layout.getBoundingBox(offset).center
        node.performTouchInput { click(point) }
        compose.waitForIdle()
    }

    private companion object {
        const val NOW = 1_755_600_000_000L
        const val DESTINATION = "https://example.com:8782/activity?tab=run%20log#latest"
    }
}
