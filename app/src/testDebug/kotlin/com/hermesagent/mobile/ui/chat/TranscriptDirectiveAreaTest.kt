package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.HermesApplication
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.plugins.Contribution
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.TranscriptDirectiveContribution
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptDirectiveAreaTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: HermesApplication
        get() = ApplicationProvider.getApplicationContext()

    private fun launch(markdown: String, streaming: Boolean = false) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                Transcript(
                    entries = listOf(
                        AssistantTurn(
                            id = "reply",
                            markdown = markdown,
                            atMillis = 0L,
                            streaming = streaming,
                        ),
                    ),
                    listState = rememberLazyListState(),
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a claimed directive paragraph renders through the first matching contribution`() {
        val dispose = app.pluginRegistry.register(
            Contribution(
                id = "t1",
                area = PluginAreas.TRANSCRIPT_DIRECTIVE_AREA,
                order = 1,
                data = TranscriptDirectiveContribution(
                    name = "demo171",
                    render = { attrs, source, streaming ->
                        Text("rendered file=${attrs["file"]} source=$source streaming=$streaming")
                    },
                ),
            ),
        )
        try {
            launch("""::demo171{file="notes.md"}""", streaming = true)

            // Attr keys are lowercased by the parser; streaming is false because blocks carry no per-block flag yet.
            compose.onNodeWithText(
                """rendered file=notes.md source=::demo171{file="notes.md"} streaming=false""",
            ).assertIsDisplayed()
        } finally {
            dispose()
        }
    }

    @Test
    fun `an unclaimed directive paragraph falls back to the original paragraph rendering`() {
        launch("""::unclaimed171{a="b"}""")

        compose.onNodeWithText("""::unclaimed171{a="b"}""").assertIsDisplayed()
    }

    @Test
    fun `a mid-prose directive marker does not parse and stays plain text`() {
        val dispose = app.pluginRegistry.register(
            Contribution(
                id = "t-mid-prose",
                area = PluginAreas.TRANSCRIPT_DIRECTIVE_AREA,
                data = TranscriptDirectiveContribution(
                    name = "task",
                    render = { _, _, _ -> Text("TASK DIRECTIVE RENDERED") },
                ),
            ),
        )
        try {
            val paragraph = """see ::task{id="1"} for details"""
            launch(paragraph)

            compose.onNodeWithText(paragraph).assertIsDisplayed()
            compose.onNodeWithText("TASK DIRECTIVE RENDERED").assertDoesNotExist()
        } finally {
            dispose()
        }
    }

    @Test
    fun `a throwing directive render degrades to an inline error chip`() {
        val dispose = app.pluginRegistry.register(
            Contribution(
                id = "t2",
                area = PluginAreas.TRANSCRIPT_DIRECTIVE_AREA,
                data = TranscriptDirectiveContribution(
                    name = "boom171",
                    render = { _, _, _ -> error("boom") },
                ),
            ),
        )
        try {
            launch("""::boom171""")

            compose.onNodeWithText("Plugin render failed").assertIsDisplayed()
            compose.onNodeWithText("::boom171").assertDoesNotExist()
        } finally {
            dispose()
        }
    }

    @Test
    fun `on name collision the first contribution in registry order wins`() {
        val disposeFirst = app.pluginRegistry.register(
            Contribution(
                id = "t3-first",
                area = PluginAreas.TRANSCRIPT_DIRECTIVE_AREA,
                order = 1,
                data = TranscriptDirectiveContribution(
                    name = "winner171",
                    render = { _, _, _ -> Text("first wins") },
                ),
            ),
        )
        val disposeSecond = app.pluginRegistry.register(
            Contribution(
                id = "t3-second",
                area = PluginAreas.TRANSCRIPT_DIRECTIVE_AREA,
                order = 2,
                data = TranscriptDirectiveContribution(
                    name = "winner171",
                    render = { _, _, _ -> Text("second loses") },
                ),
            ),
        )
        try {
            launch("""::winner171""")

            compose.onNodeWithText("first wins").assertIsDisplayed()
            compose.onNodeWithText("second loses").assertDoesNotExist()
        } finally {
            disposeSecond()
            disposeFirst()
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithText(
        text: String,
        substring: Boolean = false,
    ) = onNode(hasText(text = text, substring = substring))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() {
        val found = runCatching { fetchSemanticsNode() }.isSuccess
        check(!found) { "Expected no matching node, but one exists" }
    }
}

