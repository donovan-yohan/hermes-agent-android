package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import android.graphics.Bitmap
import android.graphics.Canvas
import com.hermesagent.mobile.data.session.ContextBreakdown
import com.hermesagent.mobile.data.session.ContextUsageCategory
import com.hermesagent.mobile.data.session.SessionUsage
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's only visual mechanism is colour, and colour is the one thing a
 * text assertion cannot see.
 *
 * Every value the Gateway sends for a category at the pin is a CSS variable
 * name, never a hex string (`agent/context_breakdown.py:19-28` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), so this feeds the real wire
 * strings and reads the pixels back. A port that resolved them all to
 * `textTertiary` — one flat wash where Desktop paints eight — passes every
 * other test in this repo and fails here.
 *
 * Hence [GraphicsMode.Mode.NATIVE]: Robolectric's legacy canvas draws nothing a
 * pixel read could distinguish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextUsageSwatchInkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val breakdown = ContextBreakdown(
        contextUsed = 40_000,
        contextMax = 200_000,
        contextPercent = 20,
        estimatedTotal = 40_000,
        categories = listOf(
            ContextUsageCategory("conversation", "Conversation", 20_000, "var(--context-usage-conversation)"),
            ContextUsageCategory("skills", "Skills", 12_000, "var(--context-usage-skills)"),
            ContextUsageCategory("mcp", "MCP", 8_000, "var(--context-usage-mcp)"),
        ),
    )

    private lateinit var tokens: com.hermesagent.mobile.ui.theme.HermesTokens

    private fun render() {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                tokens = HermesTheme.tokens
                Column(Modifier.fillMaxWidth().background(HermesTheme.tokens.cardSurface)) {
                    ContextUsagePanelContent(
                        usage = SessionUsage(
                            contextUsed = 40_000,
                            contextMax = 200_000,
                            contextPercent = 20,
                            total = 40_000,
                        ),
                        breakdown = breakdown,
                        loading = false,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The pixel at the centre of the tagged node, read off a synchronous draw of
     * the window rather than `captureToImage`: Robolectric never delivers the
     * redraw callback that one waits on.
     */
    private fun inkOf(tag: String): Color {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        return Color(bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
    }

    @Test
    fun `each category swatch paints its own context usage ink`() {
        render()

        val conversation = inkOf(contextUsageSwatchTag("conversation"))
        val skills = inkOf(contextUsageSwatchTag("skills"))
        val mcp = inkOf(contextUsageSwatchTag("mcp"))

        assertEquals(tokens.contextUsage.conversation, conversation)
        assertEquals(tokens.contextUsage.skills, skills)
        assertEquals(tokens.contextUsage.mcp, mcp)

        assertNotEquals(conversation, skills)
        assertNotEquals(skills, mcp)
        assertNotEquals(conversation, tokens.textTertiary)
    }

    @Test
    fun `each bar segment paints the same ink as its swatch`() {
        render()

        assertEquals(tokens.contextUsage.conversation, inkOf(contextUsageSegmentTag("conversation")))
        assertEquals(tokens.contextUsage.skills, inkOf(contextUsageSegmentTag("skills")))
        assertNotEquals(
            inkOf(contextUsageSegmentTag("conversation")),
            inkOf(contextUsageSegmentTag("skills")),
        )
    }
}
