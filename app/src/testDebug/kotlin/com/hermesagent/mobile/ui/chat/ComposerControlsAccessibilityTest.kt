package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.CompletionTrigger
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.ModelOption
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
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
        compose.onNodeWithText(
            "Use a URL for now. Files, folders, and images aren't available yet.",
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
    fun `reasoning is capability gated and includes xhigh`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Composer(
                    draft = "",
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    isStreaming = false,
                    canSend = false,
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
        compose.onNodeWithText("XHigh").assertExists()
        compose.onNodeWithContentDescription(
            "Reasoning xhigh. Reasoning is not available for this model",
        ).assertHeightIsAtLeast(HermesSpacing().touchTarget)
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        compose.onNodeWithText("Reasoning is not available for this model.").assertExists()
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
                    statusLine = "",
                    controls = ComposerUiState(catalog = ComposerCatalogUiState.Loading),
                )
            }
        }

        compose.onNodeWithContentDescription("Open model controls", substring = true).performClick()
        compose.onNodeWithContentDescription(
            "Reasoning none. Reasoning availability could not be checked",
        ).assertIsNotEnabled()
        compose.onNodeWithText("Loading model choices…").assertExists()
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
}
