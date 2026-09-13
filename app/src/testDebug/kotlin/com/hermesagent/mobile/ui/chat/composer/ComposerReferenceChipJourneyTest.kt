package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Modifier
import com.hermesagent.mobile.ui.chat.Composer
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerReferenceChipJourneyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pastedUrlPaintsChipAndKeepsWireDraft() {
        var draft by mutableStateOf("")

        composeTestRule.setContent {
            HermesTheme {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onStop = {}, isStreaming = false, canSend = true, connected = true, statusLine = "",
                    modifier = Modifier
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Message Hermes")
            .performTextInput("see https://example.dev/a ")

        composeTestRule.waitForIdle()

        assertEquals("see @url:`https://example.dev/a` ", draft)

        val config = composeTestRule.onNodeWithContentDescription("Message Hermes").fetchSemanticsNode().config
        val inputText = config[SemanticsProperties.InputText].text
        assertEquals(draft, inputText)

        val editableText = config[SemanticsProperties.EditableText].text
        assertNotEquals(draft, editableText)
        assertTrue(editableText.contains("example.dev/a"))
        assertFalse(editableText.contains("https://"))
        assertFalse(editableText.any { it in '\uE000'..'\uF8FF' })
    }

    @Test
    fun pastedUrlOverSelectionPaintsChipAndSendsWireText() {
        var draft by mutableStateOf("see replace now")
        var sent = ""

        composeTestRule.setContent {
            HermesTheme {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { sent = draft },
                    onStop = {}, isStreaming = false, canSend = true, connected = true, statusLine = "",
                    modifier = Modifier
                )
            }
        }

        val node = composeTestRule.onNodeWithContentDescription("Message Hermes")
        node.performTextInputSelection(TextRange(4, 11))
        node.performTextInput("https://example.dev/a")
        composeTestRule.waitForIdle()

        assertEquals("see @url:`https://example.dev/a` now", draft)
        val config = node.fetchSemanticsNode().config
        val editableText = config[SemanticsProperties.EditableText].text
        assertTrue(editableText.contains("example.dev/a"))
        assertFalse(editableText.contains("https://"))

        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        composeTestRule.waitForIdle()
        assertEquals(draft, sent)
    }

    @Test
    fun pastedUrlOverAUrlWithSharedTextStillPaintsAndSendsTheChip() {
        var draft by mutableStateOf("see https://example.dev/path/old/item now")
        var sent = ""

        composeTestRule.setContent {
            HermesTheme {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = { sent = draft },
                    onStop = {}, isStreaming = false, canSend = true, connected = true, statusLine = "",
                    modifier = Modifier
                )
            }
        }

        val node = composeTestRule.onNodeWithContentDescription("Message Hermes")
        node.performTextInputSelection(TextRange(4, 37))
        node.performTextInput("https://example.dev/path/new/item")
        composeTestRule.waitForIdle()

        assertEquals("see @url:`https://example.dev/path/new/item` now", draft)
        val config = node.fetchSemanticsNode().config
        assertEquals(draft, config[SemanticsProperties.InputText].text)
        assertEquals(TextRange(44), config[SemanticsProperties.TextSelectionRange])
        val editableText = config[SemanticsProperties.EditableText].text
        assertTrue(editableText.contains("example.dev/path/new/item"))
        assertFalse(editableText.contains("https://"))

        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        composeTestRule.waitForIdle()
        assertEquals(draft, sent)
    }

    @Test
    fun addSheetInsertsPaddedUrlChip() {
        var draft by mutableStateOf("")

        composeTestRule.setContent {
            HermesTheme {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onStop = {}, isStreaming = false, canSend = true, connected = true, statusLine = "",
                    modifier = Modifier
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Add to message").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Add a remote URL reference", substring = true).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("URL to add").performTextInput("https://example.dev/a")
        composeTestRule.onNodeWithContentDescription("Add URL reference").performClick()
        composeTestRule.waitForIdle()

        assertEquals("@url:`https://example.dev/a` ", draft)
        composeTestRule.onNodeWithContentDescription("Message Hermes").assertIsFocused()
    }

    @Test
    fun backspaceRemovesChipWhole() {
        var draft by mutableStateOf("")

        composeTestRule.setContent {
            HermesTheme {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onStop = {}, isStreaming = false, canSend = true, connected = true, statusLine = "",
                    modifier = Modifier
                )
            }
        }

        val node = composeTestRule.onNodeWithContentDescription("Message Hermes")
        node.performTextInput("see https://example.dev/a ")
        composeTestRule.waitForIdle()
        assertEquals("see @url:`https://example.dev/a` ", draft)

        node.performKeyInput { pressKey(Key.Backspace) }
        composeTestRule.waitForIdle()
        assertEquals("see @url:`https://example.dev/a`", draft)

        node.performKeyInput { pressKey(Key.Backspace) }
        composeTestRule.waitForIdle()
        assertEquals("see ", draft)
    }

    @Test
    fun caretInsideChipSnapsToEdge() {
        var draft by mutableStateOf("")

        composeTestRule.setContent {
            HermesTheme {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = {},
                    onStop = {}, isStreaming = false, canSend = true, connected = true, statusLine = "",
                    modifier = Modifier
                )
            }
        }

        val node = composeTestRule.onNodeWithContentDescription("Message Hermes")
        node.performTextInput("see https://example.dev/a ")
        composeTestRule.waitForIdle()

        node.performTextInputSelection(TextRange(12))
        composeTestRule.waitForIdle()

        val config = node.fetchSemanticsNode().config
        val selection = config[SemanticsProperties.TextSelectionRange]
        assertTrue(selection == TextRange(4) || selection == TextRange(32))

        node.performTextInputSelection(TextRange(6, 12))
        composeTestRule.waitForIdle()

        val config2 = node.fetchSemanticsNode().config
        val selection2 = config2[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(4, 32), selection2)
    }
}
