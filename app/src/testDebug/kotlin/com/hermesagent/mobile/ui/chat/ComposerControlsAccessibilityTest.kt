package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.AttachmentKind
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.CompletionTrigger
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.ModelOption
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.ui.common.AttachmentThumbnails
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerControlsAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `model and add controls expose 48dp touch semantics and safe add sheet`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                    controls = ComposerUiState(
                        catalog = ComposerCatalogUiState.Ready(
                            ModelCatalog(
                                providers = listOf(ModelProvider("openai", "OpenAI", listOf(ModelOption("gpt", "GPT")))),
                                effectiveSelection = ComposerModelSelection("gpt", "openai"),
                            ),
                        ),
                        controls = ModelControlsSnapshot(selection = ComposerModelSelection("gpt", "openai")),
                    ),
                )
            }
        }
        val floor = HermesSpacing().touchTarget
        compose.onNodeWithContentDescription("Open model controls", substring = true).assertHeightIsAtLeast(floor)
        compose.onNodeWithContentDescription("GPT. from OpenAI", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Add to message").assertHeightIsAtLeast(floor).performClick()
        compose.onNodeWithText("URL").assertIsDisplayed()
        compose.onNodeWithContentDescription("Files. Attach a file from this device").assertIsDisplayed()
        compose.onNodeWithText(
            "Files upload through the Gateway when you send. Folders aren't available yet.",
        ).assertExists()
    }

    @Test
    fun `snippet insertion restores editor focus`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                )
            }
        }

        compose.onNodeWithContentDescription("Add to message").performClick()
        compose.onNodeWithContentDescription("Prompt snippets. Insert a reusable prompt").performClick()
        compose.onNodeWithContentDescription("Code review. Review a change for correctness and risk.").performClick()
        compose.onNodeWithContentDescription("Message Hermes").assertIsFocused()
    }

    @Test
    fun `reasoning is capability gated and offers the full backend scale`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                    controls = ComposerUiState(
                        catalog = ComposerCatalogUiState.Ready(
                            ModelCatalog(
                                providers = listOf(
                                    ModelProvider(
                                        "openai",
                                        "OpenAI",
                                        listOf(ModelOption("gpt", "GPT", supportsReasoning = false)),
                                    ),
                                ),
                                effectiveSelection = ComposerModelSelection("gpt", "openai"),
                            ),
                        ),
                        controls = ModelControlsSnapshot(
                            selection = ComposerModelSelection("gpt", "openai"),
                            reasoning = ReasoningEffort.XHigh,
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Open model controls", substring = true).performClick()
        ReasoningEffort.LEVELS.forEach { level ->
            compose.onNodeWithText(
                when (level) {
                    ReasoningEffort.Minimal -> "Min"
                    ReasoningEffort.Medium -> "Med"
                    ReasoningEffort.XHigh -> "XHigh"
                    ReasoningEffort.Max -> "Max"
                    ReasoningEffort.Ultra -> "Ultra"
                    else -> level.wireValue.replaceFirstChar { it.uppercase() }
                },
            ).assertExists()
        }
        compose.onNodeWithContentDescription(
            "Reasoning xhigh. Reasoning is not available for this model",
        ).assertHeightIsAtLeast(HermesSpacing().touchTarget)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        compose.onNodeWithContentDescription(
            "Thinking. Reasoning is not available for this model",
        ).assertIsNotEnabled()
    }

    @Test
    fun `thinking toggle owns the off state while the scale stays levels only`() {
        var selected by mutableStateOf<ReasoningEffort?>(ReasoningEffort.None)
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                    controls = ComposerUiState(
                        catalog = ComposerCatalogUiState.Ready(
                            ModelCatalog(
                                providers = listOf(ModelProvider("acme", "Acme", listOf(ModelOption("m")))),
                                effectiveSelection = ComposerModelSelection("m", "acme"),
                            ),
                        ),
                        controls = ModelControlsSnapshot(
                            selection = ComposerModelSelection("m", "acme"),
                            reasoning = selected,
                        ),
                    ),
                    onSelectReasoning = { selected = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Open model controls", substring = true).performClick()
        compose.onNodeWithContentDescription("Thinking")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"))
            .performClick()
        assertEquals(ReasoningEffort.Medium, selected)

        compose.waitForIdle()
        compose.onNodeWithContentDescription("Thinking")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On"))
    }

    @Test
    fun `unresolved catalog does not fabricate an unsupported capability`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                    controls = ComposerUiState(catalog = ComposerCatalogUiState.Loading),
                )
            }
        }

        compose.onNodeWithContentDescription("Open model controls", substring = true).performClick()
        compose.onNodeWithContentDescription(
            "Thinking. Reasoning availability could not be checked",
        ).assertIsNotEnabled()
        compose.onNodeWithText("Loading model choices…").assertExists()
        compose.onNodeWithText("Fast mode availability could not be checked").assertExists()
    }

    @Test
    fun `model sheet labels the scope rather than a global profile change`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                    controls = ComposerUiState(
                        catalog = ComposerCatalogUiState.Ready(
                            ModelCatalog(
                                providers = listOf(ModelProvider("openai", "OpenAI", listOf(ModelOption("gpt", "GPT")))),
                                effectiveSelection = ComposerModelSelection("gpt", "openai"),
                            ),
                        ),
                        controls = ModelControlsSnapshot(selection = ComposerModelSelection("gpt", "openai")),
                        isManualNewDraft = true,
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription("Open model controls", substring = true).performClick()
        compose.onNodeWithText("Saved for new chats only.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search models").assertIsDisplayed()
    }

    @Test
    fun `hardware arrows select an accessible completion and enter accepts it`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "/",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = true,
                    connected = true,
                    statusLine = "",
                    controls = ComposerUiState(
                        catalog = ComposerCatalogUiState.Ready(ModelCatalog()),
                        completion = CompletionUiState(
                            trigger = CompletionTrigger.Slash,
                            items = listOf(
                                CompletionItem("/first", "First"),
                                CompletionItem("/second", "Second"),
                            ),
                            replaceStart = 0,
                            replaceEnd = 1,
                        ),
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("First")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        val editor = compose.onNodeWithContentDescription("Message Hermes")
        editor.performClick()
        editor.performKeyInput {
            pressKey(Key.DirectionDown)
        }
        compose.onNodeWithContentDescription("Second")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        editor.performKeyInput {
            pressKey(Key.DirectionUp)
        }
        compose.onNodeWithContentDescription("First")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        editor.performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.Enter)
        }
        editor.assertTextEquals("/second")
    }

    @Test
    fun `attachment chips expose name state and remove semantics`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                    attachments = listOf(
                        ComposerAttachmentDraft(
                            occurrenceId = "occ-1",
                            durableSessionId = "s",
                            displayName = "notes.txt",
                            kind = AttachmentKind.File,
                            stage = AttachmentStage.Ready(2048),
                        ),
                        ComposerAttachmentDraft(
                            occurrenceId = "occ-2",
                            durableSessionId = "s",
                            displayName = "pic.png",
                            kind = AttachmentKind.Image,
                            stage = AttachmentStage.Refused("That file is larger than 8 MB."),
                        ),
                    ),
                )
            }
        }

        // Chip children live in a lazy row; existence + semantics are the
        // contract here, exactly like the slice-3 completion popup.
        compose.onNodeWithText("notes.txt").assertExists()
        compose.onNodeWithText("2 KB").assertExists()
        compose.onNodeWithContentDescription("Remove notes.txt").assertExists().performClick()
        compose.onNodeWithText("That file is larger than 8 MB.").assertExists()
    }

    @Test
    fun `image chips render the decoded thumbnail`() {
        // A real 4x4 red PNG (generated, not hand-made): deterministic decoder input.
        val png = hexBytes(
            "89504e470d0a1a0a0000000d494844520000000400000004080200000026930929" +
                "0000001049444154789c63f8cfc000470cc47100ae930ff1d05f239e0000000049454e44ae426082",
        )
        val thumbnail = requireNotNull(AttachmentThumbnails.decodeComposer(png))
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
                    connected = true,
                    statusLine = "",
                    attachments = listOf(
                        ComposerAttachmentDraft(
                            occurrenceId = "occ-img",
                            durableSessionId = "s",
                            displayName = "pic.png",
                            kind = AttachmentKind.Image,
                            stage = AttachmentStage.Ready(1024),
                        ),
                    ),
                    attachmentThumbnails = mapOf("occ-img" to thumbnail),
                )
            }
        }

        compose.onNodeWithTag("Attachment thumbnail occ-img").assertExists()
        // assertExists also passes for a zero-width node; the on-device bug was
        // exactly that (weight() collapses in an unbounded LazyRow item).
        // Robolectric's compressed font metrics render this name at ~7dp, so
        // non-zero is the honest threshold here — the real rendering is
        // verified on-device.
        compose.onNodeWithText("pic.png").assertIsDisplayed().assertWidthIsAtLeast(1.dp)
        compose.onNodeWithContentDescription("Remove pic.png").assertExists()

        val removeTarget = compose.onNodeWithTag("Attachment remove occ-img").fetchSemanticsNode().boundsInRoot
        val removeLabel = compose.onNodeWithTag(
            "Attachment remove label occ-img",
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val thumbnailBounds = compose.onNodeWithTag("Attachment thumbnail occ-img")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("The caption must not consume the full touch target", removeLabel.height < removeTarget.height)
        assertEquals(thumbnailBounds.center.y, removeLabel.center.y, 1f)
    }
}

/** Decodes a hex string into bytes — deterministic fixture images without hand-built literals. */
private fun hexBytes(hex: String): ByteArray =
    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
