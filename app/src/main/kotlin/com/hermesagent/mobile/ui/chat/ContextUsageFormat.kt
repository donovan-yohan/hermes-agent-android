package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.graphics.Color
import com.hermesagent.mobile.ui.theme.HermesTokens
import java.util.Locale

/**
 * THE compact-number formatter — every user-facing count/token figure goes
 * through here. 999 → "999", 1000 → "1k", 1230 → "1.2k", 10000 → "10k",
 * 1_500_000 → "1.5M". Do not hand-roll `/ 1000` display math elsewhere.
 *
 * Pinned to upstream `apps/desktop/src/lib/format.ts:4-24` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
fun compactNumber(value: Number?): String {
    val num = value?.toDouble() ?: 0.0
    if (!num.isFinite() || num <= 0.0) {
        return "0"
    }

    fun scaled(v: Double, suffix: String): String {
        val formatted = String.format(Locale.US, "%.1f", v)
        return "${formatted.removeSuffix(".0")}$suffix"
    }

    // Thresholds sit just under the unit boundary so rounding can't produce
    // "1000k" or "1000" — those promote to the next unit instead.
    if (num >= 999_950.0) {
        return scaled(num / 1_000_000.0, "M")
    }

    if (num >= 999.5) {
        return scaled(num / 1_000.0, "k")
    }

    return Math.round(num).toString()
}

/**
 * Text-based progress bar glyph string.
 *
 * Pinned to upstream `apps/desktop/src/lib/statusbar.tsx:37-42` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
fun contextBar(percent: Double?, width: Int = 10): String {
    val bounded = (percent ?: 0.0).coerceIn(0.0, 100.0)
    val filled = Math.round((bounded / 100.0) * width).toInt().coerceIn(0, width)
    return "█".repeat(filled) + "░".repeat(width - filled)
}

/**
 * Context meter status text label (e.g. "30k/200k" or "12k tok").
 *
 * Pinned to upstream `apps/desktop/src/lib/statusbar.tsx:44-50` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
fun usageContextLabel(contextUsed: Long?, contextMax: Long?, total: Long): String {
    if (contextMax != null && contextMax > 0) {
        return "${compactNumber(contextUsed ?: 0)}/${compactNumber(contextMax)}"
    }
    return if (total > 0) "${compactNumber(total)} tok" else ""
}

/**
 * Context meter detail label (e.g. "[████░░░░░░] 40%").
 *
 * Pinned to upstream `apps/desktop/src/lib/statusbar.tsx:52-60` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
fun contextBarLabel(contextPercent: Double?, contextMax: Long?): String {
    if (contextMax == null || contextMax <= 0) {
        return ""
    }
    val pct = Math.round((contextPercent ?: 0.0).coerceIn(0.0, 100.0)).toInt()
    return "[${contextBar(contextPercent)}] $pct%"
}

/**
 * Parse a hex color string (#RGB, #RRGGBB, #AARRGGBB) into a Compose Color.
 * Returns null if the color is a CSS variable (e.g. `var(--...)`) or invalid.
 */
fun parseHexColor(hex: String?): Color? {
    if (hex == null) return null
    val clean = hex.trim()
    if (!clean.startsWith("#")) return null
    val raw = clean.substring(1)
    val parsed = raw.toLongOrNull(16) ?: return null
    return when (raw.length) {
        6 -> Color(0xFF000000 or parsed)
        8 -> Color(parsed)
        3 -> {
            val r = (parsed shr 8 and 0xF) * 0x11
            val g = (parsed shr 4 and 0xF) * 0x11
            val b = (parsed and 0xF) * 0x11
            Color(0xFF000000 or (r shl 16) or (g shl 8) or b)
        }
        else -> null
    }
}

private val CSS_VARIABLE = Regex("""^var\(\s*(--[A-Za-z0-9_-]+)\s*\)$""")

/**
 * Resolve the `color` a Gateway category carries into the ink that paints it.
 *
 * At the pin the Gateway never sends a value: `agent/context_breakdown.py:19-28`
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` maps all eight known ids to
 * `var(--context-usage-*)`, and an unknown id defaults to
 * `var(--ui-text-tertiary)` (`:155`). Desktop resolves those names against
 * `apps/desktop/src/styles.css:217-224`; Android resolves them against the
 * semantic [HermesTokens.contextUsage] group derived from the same expressions,
 * which is what makes the panel's eight colours the *only* thing it colour-codes
 * by rather than one flat wash.
 *
 * A literal hex still parses — a Gateway is free to send one — and anything
 * unrecognised falls back to `textTertiary`, the same ink Desktop's own default
 * variable resolves to.
 */
fun resolveCategoryColor(color: String?, tokens: HermesTokens): Color {
    val raw = color?.trim().orEmpty()
    val ink = tokens.contextUsage
    val named = when (CSS_VARIABLE.matchEntire(raw)?.groupValues?.get(1)) {
        "--context-usage-system" -> ink.system
        "--context-usage-tools" -> ink.tools
        "--context-usage-rules" -> ink.rules
        "--context-usage-skills" -> ink.skills
        "--context-usage-mcp" -> ink.mcp
        "--context-usage-subagents" -> ink.subagents
        "--context-usage-memory" -> ink.memory
        "--context-usage-conversation" -> ink.conversation
        "--ui-text-tertiary" -> tokens.textTertiary
        else -> null
    }
    return named ?: parseHexColor(raw) ?: tokens.textTertiary
}

/**
 * Verbatim product copy for the Context Meter and Context Usage Panel.
 *
 * Pinned to upstream `apps/desktop/src/i18n/en.ts:2963-2980` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
object ContextUsageCopy {
    /** Spoken accessibility label and status item title (`en.ts:2963`). */
    const val CONTEXT_USAGE = "Context usage"

    /** Panel heading (`en.ts:2978`). */
    const val TITLE = "Context Usage"

    /** Panel empty state (`en.ts:2975`). */
    const val EMPTY = "No context data yet"

    /** Panel loading state (`en.ts:2976`). */
    const val LOADING = "Loading breakdown…"

    /** Percent full subtitle (`en.ts:2977`). */
    fun percentFull(percent: Int): String = "$percent% Full"

    /** Token summary count in panel header (`en.ts:2979`). */
    fun tokenSummary(used: String, max: String): String = "$used / $max Tokens"

    /**
     * The compact meter's own figure. Desktop trails the same number after its
     * glyph bar (`statusbar.tsx:52-60`); the phone draws the bar as a ring and
     * this is what is left to read.
     */
    fun percent(percent: Int): String = "$percent%"

    /**
     * What the compact meter speaks, because a ring and a percentage are what
     * it draws: "30k of 200k, 40%". Desktop has room to spell the same figures
     * out beside the bar (`statusbar.tsx:44-60`).
     */
    fun spokenUsage(used: String, max: String, percent: Int): String = "$used of $max, $percent%"

    /**
     * The same meter when the host reports a proportion and no token count.
     *
     * `session.info` may carry `context_percent` without `context_used`, and
     * the ring still has a proportion to draw. Speaking a zero in the count's
     * place would name a figure the Gateway never sent and contradict the
     * percentage in the same breath, so only what is known is spoken.
     */
    fun spokenPercent(percent: Int): String = "$percent%"

    /** Standard breakdown category labels (`en.ts:2966-2973`). */
    val CATEGORIES: Map<String, String> = mapOf(
        "conversation" to "Conversation",
        "mcp" to "MCP",
        "memory" to "Memory",
        "rules" to "Rules",
        "skills" to "Skills",
        "subagent_definitions" to "Subagent definitions",
        "system_prompt" to "System prompt",
        "tool_definitions" to "Tool definitions",
    )

    fun categoryLabel(id: String, fallback: String): String =
        CATEGORIES[id] ?: fallback
}
