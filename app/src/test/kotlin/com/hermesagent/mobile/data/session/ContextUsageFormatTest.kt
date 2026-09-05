package com.hermesagent.mobile.data.session

import androidx.compose.ui.graphics.Color
import com.hermesagent.mobile.ui.chat.ContextUsageCopy
import com.hermesagent.mobile.ui.chat.compactNumber
import com.hermesagent.mobile.ui.chat.contextBar
import com.hermesagent.mobile.ui.chat.contextBarLabel
import com.hermesagent.mobile.ui.chat.parseHexColor
import com.hermesagent.mobile.ui.chat.resolveCategoryColor
import com.hermesagent.mobile.ui.chat.usageContextLabel
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTokens
import com.hermesagent.mobile.ui.theme.paletteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextUsageFormatTest {

    @Test
    fun `compactNumber formats numbers across all scales matching Desktop rules`() {
        assertEquals("0", compactNumber(null))
        assertEquals("0", compactNumber(0))
        assertEquals("0", compactNumber(-10))
        assertEquals("999", compactNumber(999))
        assertEquals("999", compactNumber(999.4))
        assertEquals("1k", compactNumber(999.5))
        assertEquals("1k", compactNumber(1000))
        assertEquals("1.2k", compactNumber(1200))
        assertEquals("1.2k", compactNumber(1230))
        assertEquals("1.3k", compactNumber(1280))
        assertEquals("10k", compactNumber(10000))
        assertEquals("128.2k", compactNumber(128200))
        assertEquals("241.4k", compactNumber(241400))
        assertEquals("272k", compactNumber(272000))
        assertEquals("286.6k", compactNumber(286600))
        assertEquals("999.9k", compactNumber(999949))
        assertEquals("1M", compactNumber(999950))
        assertEquals("1M", compactNumber(1000000))
        assertEquals("1.5M", compactNumber(1500000))
    }

    @Test
    fun `contextBar creates expected glyph bars`() {
        assertEquals("░░░░░░░░░░", contextBar(0.0))
        assertEquals("░░░░░░░░░░", contextBar(null))
        assertEquals("█████░░░░░", contextBar(47.0))
        assertEquals("█████████░", contextBar(89.0))
        assertEquals("██████████", contextBar(100.0))
        assertEquals("██████████", contextBar(120.0))
    }

    @Test
    fun `usageContextLabel formats max and total branches`() {
        assertEquals("128.2k/272k", usageContextLabel(128200L, 272000L, 0L))
        assertEquals("0/200k", usageContextLabel(null, 200000L, 100L))
        assertEquals("1.3k tok", usageContextLabel(null, null, 1280L))
        assertEquals("", usageContextLabel(null, null, 0L))
        assertEquals("", usageContextLabel(null, 0L, 0L))
    }

    @Test
    fun `contextBarLabel formats glyph bar and rounded percent`() {
        assertEquals("[█████░░░░░] 47%", contextBarLabel(47.0, 272000L))
        assertEquals("[█████████░] 89%", contextBarLabel(88.6, 272000L))
        assertEquals("", contextBarLabel(47.0, null))
        assertEquals("", contextBarLabel(47.0, 0L))
    }

    /**
     * What the compact meter speaks, in both of the shapes `session.info` can
     * arrive in.
     *
     * The second is the edge: a Gateway may send `context_percent` and
     * `context_max` with no `context_used` at all. The meter used to substitute
     * a zero for the missing figure and speak "0 of 200k, 40%" — a reading in
     * which two of the three numbers contradict the third, heard only by the
     * people who cannot see the ring that would have corrected it.
     */
    @Test
    fun `the spoken meter reads both figures, or the proportion alone when there is no count`() {
        assertEquals("30k of 200k, 40%", ContextUsageCopy.spokenUsage("30k", "200k", 40))
        assertEquals("40%", ContextUsageCopy.spokenPercent(40))
        assertEquals("0%", ContextUsageCopy.spokenPercent(0))
    }

    @Test
    fun `parseHexColor parses 3, 6, and 8 character hex and returns null for CSS variables`() {
        assertEquals(Color(0xFF26C6DA), parseHexColor("#26c6da"))
        assertEquals(Color(0xFFFFFFFF), parseHexColor("#fff"))
        assertEquals(Color(0x80123456), parseHexColor("#80123456"))
        assertNull(parseHexColor("var(--context-usage-system)"))
        assertNull(parseHexColor("var(--ui-text-tertiary)"))
        assertNull(parseHexColor("invalid"))
        assertNull(parseHexColor(null))
    }

    @Test
    fun `ContextUsageCopy category mapping resolves known IDs and falls back to server label`() {
        assertEquals("System prompt", ContextUsageCopy.categoryLabel("system_prompt", "Fallback"))
        assertEquals("Tool definitions", ContextUsageCopy.categoryLabel("tool_definitions", "Fallback"))
        assertEquals("Rules", ContextUsageCopy.categoryLabel("rules", "Fallback"))
        assertEquals("Skills", ContextUsageCopy.categoryLabel("skills", "Fallback"))
        assertEquals("MCP", ContextUsageCopy.categoryLabel("mcp", "Fallback"))
        assertEquals("Subagent definitions", ContextUsageCopy.categoryLabel("subagent_definitions", "Fallback"))
        assertEquals("Memory", ContextUsageCopy.categoryLabel("memory", "Fallback"))
        assertEquals("Conversation", ContextUsageCopy.categoryLabel("conversation", "Fallback"))
        assertEquals("Custom Plugin", ContextUsageCopy.categoryLabel("custom_plugin", "Custom Plugin"))
    }

    /**
     * The eight strings the Gateway actually sends. `_CATEGORY_COLORS` in
     * `agent/context_breakdown.py:19-28` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3` maps every known id to one of
     * these, and `:155` defaults an unknown id to `var(--ui-text-tertiary)` —
     * there is no hex value anywhere on this wire, which is why a test that fed
     * hand-written hex proved nothing about what ships.
     */
    private val wireColors = mapOf(
        "system_prompt" to "var(--context-usage-system)",
        "tool_definitions" to "var(--context-usage-tools)",
        "rules" to "var(--context-usage-rules)",
        "skills" to "var(--context-usage-skills)",
        "mcp" to "var(--context-usage-mcp)",
        "subagent_definitions" to "var(--context-usage-subagents)",
        "memory" to "var(--context-usage-memory)",
        "conversation" to "var(--context-usage-conversation)",
    )

    private fun tokens(dark: Boolean): HermesTokens {
        val palette = BuiltinThemes.resolve(BuiltinThemes.DEFAULT_NAME).paletteFor(dark)
        return HermesTokens.from(palette, dark)
    }

    @Test
    fun `every css variable the gateway sends resolves to its own context usage ink`() {
        for (dark in listOf(false, true)) {
            val tokens = tokens(dark)
            val ink = tokens.contextUsage
            val expected = mapOf(
                "system_prompt" to ink.system,
                "tool_definitions" to ink.tools,
                "rules" to ink.rules,
                "skills" to ink.skills,
                "mcp" to ink.mcp,
                "subagent_definitions" to ink.subagents,
                "memory" to ink.memory,
                "conversation" to ink.conversation,
            )
            val painted = wireColors.mapValues { (_, wire) -> resolveCategoryColor(wire, tokens) }

            for ((id, wire) in wireColors) {
                assertEquals("$id ($wire)", expected.getValue(id), painted.getValue(id))
                assertNotEquals(
                    "$id must not fall back to the tertiary ink",
                    tokens.textTertiary,
                    painted.getValue(id),
                )
            }
            assertEquals("all eight inks distinct", painted.size, painted.values.toSet().size)
        }
    }

    @Test
    fun `an unknown category, a malformed value and a null all fall back to the tertiary ink`() {
        val tokens = tokens(dark = true)
        // `context_breakdown.py:155` — the default for an id this build of the
        // Gateway does not know.
        assertEquals(tokens.textTertiary, resolveCategoryColor("var(--ui-text-tertiary)", tokens))
        assertEquals(tokens.textTertiary, resolveCategoryColor("var(--context-usage-unheard-of)", tokens))
        assertEquals(tokens.textTertiary, resolveCategoryColor("chartreuse", tokens))
        assertEquals(tokens.textTertiary, resolveCategoryColor("#12345", tokens))
        assertEquals(tokens.textTertiary, resolveCategoryColor("", tokens))
        assertEquals(tokens.textTertiary, resolveCategoryColor(null, tokens))
    }

    @Test
    fun `a literal hex still paints itself`() {
        val tokens = tokens(dark = true)
        assertEquals(Color(0xFF26C6DA), resolveCategoryColor("#26c6da", tokens))
        assertEquals(Color(0xFF26C6DA), resolveCategoryColor("  #26C6DA  ", tokens))
    }
}
