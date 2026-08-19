package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The **value** half of theme parity: does every semantic surface resolve to the
 * colour Desktop paints, in both modes, for all six built-ins?
 *
 * [ThemeParityTest] proves the registry and the token *contract*; this proves
 * the arithmetic. It exists because a token can be present, opaque and legible
 * and still be the wrong colour — which is exactly how the first port shipped
 * raw palette fields where Desktop mixes each seed with a per-mode neutral.
 *
 * **Where the expected values come from.** Each one is derived from the pinned
 * Desktop sources (`f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`), never from this
 * app's own output, by re-walking the chain by hand:
 *
 * ```
 * --ui-bg-chrome  = color-mix(in srgb, background       92%|74%, #f3f3f3|#0d0d0e)
 * --ui-bg-sidebar = color-mix(in srgb, sidebarBackground     100%, #f3f3f3|#0a0a0b)
 * --ui-bg-editor  = color-mix(in srgb, card             22%|38%, #fcfcfc|#161618)
 * --ui-widget-surface-background = --ui-bg-editor
 *                 | dark: color-mix(in srgb, --ui-bg-editor 88%, #000)
 * --ui-chat-bubble-background = color-mix(in srgb, userBubble ?? popover
 *                                                        0%|46%, #fcfcfc|#161618)
 * --dt-user-bubble-border     = userBubbleBorder ?? border
 * --ui-inline-code-background = color-mix(in srgb, #141414 5%|#ffffff 7%, transparent)
 * --ui-inline-code-foreground = color-mix(in srgb, #141414|#ffffff 88%, transparent)
 * ```
 *
 * Seeds: `themes/context.tsx:198-230`. Knobs: `themes/context.tsx:166-177` and
 * `styles.css:170-177` / `:root.dark` at `styles.css:517-550`. Consumers:
 * `styles.css:346-367`. Light palettes for the five dark-first presets are
 * `synthLightColors` (`context.tsx:84-118`), which is why every one of them
 * lands on the same near-white chrome and the same `#fcfcfc` bubble.
 *
 * The one deliberate divergence: Compose stores an sRGB `Color` as 8-bit
 * channels, so each rung is quantised before it feeds the next. The expected
 * values are evaluated the same way. Only the dark widget fill nests two mixes,
 * and the gap against a browser's higher-precision `color-mix` there is at most
 * one 8-bit step.
 *
 * A red row here means Desktop moved or the port drifted — resolve it against
 * the pinned checkout, not by editing the expectation.
 */
class ThemeSemanticParityTest {

    private data class Expected(
        val name: String,
        val dark: Boolean,
        val chatSurface: String,
        val sidebarSurface: String,
        val cardSurface: String,
        val widgetSurface: String,
        val userBubble: String,
        val userBubbleBorder: String,
        val inlineCodeBackground: String,
        val inlineCodeForeground: String,
    )

    private val expectations = listOf(
        Expected("nous", false, "#fff8f9fe", "#fff3f7ff", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#3d0053fd", "#0d141414", "#e0141414"),
        Expected("nous", true, "#ff0d2667", "#ff09286f", "#ff142345", "#ff121f3d", "#ff152750", "#ff3a63bd", "#12ffffff", "#e0ffffff"),
        Expected("midnight", false, "#fffefefe", "#fff4f4f9", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffdeddee", "#0d141414", "#e0141414"),
        Expected("midnight", true, "#ff090918", "#ff06061a", "#ff13131e", "#ff11111a", "#ff151528", "#ff242466", "#12ffffff", "#e0ffffff"),
        Expected("ember", false, "#fffefefe", "#fff8f3ef", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffe9dbd1", "#0d141414", "#e0141414"),
        Expected("ember", true, "#ff140904", "#ff100600", "#ff191310", "#ff16110e", "#ff1f130d", "#ff4a2010", "#12ffffff", "#e0ffffff"),
        Expected("mono", false, "#fffefefe", "#fff5f5f5", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffe1e1e3", "#0d141414", "#e0141414"),
        Expected("mono", true, "#ff0e0e0e", "#ff0a0a0a", "#ff151516", "#ff121213", "#ff181819", "#ff363636", "#12ffffff", "#e0ffffff"),
        Expected("cyberpunk", false, "#fffefefe", "#ffeefaf1", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffcbefd7", "#0d141414", "#e0141414"),
        Expected("cyberpunk", true, "#ff030b04", "#ff000600", "#ff0e140f", "#ff0c120d", "#ff0c150d", "#ff004800", "#12ffffff", "#e0ffffff"),
        Expected("slate", false, "#fffefefe", "#fff2f6fa", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffd7e2f1", "#0d141414", "#e0141414"),
        Expected("slate", true, "#ff0d1015", "#ff090d13", "#ff16181c", "#ff131519", "#ff1a1f27", "#ff2e4060", "#12ffffff", "#e0ffffff"),
    )

    @Test
    fun `the table covers every builtin in both modes`() {
        assertEquals(
            "one row per preset per mode, in registry order",
            BuiltinThemes.ALL.flatMap { listOf(it.name to false, it.name to true) },
            expectations.map { it.name to it.dark },
        )
    }

    @Test
    fun `every semantic surface matches the pinned desktop derivation`() {
        for (row in expectations) {
            val tokens = tokensFor(row.name, row.dark)
            val where = "${row.name}/${if (row.dark) "dark" else "light"}"

            assertEquals("$where: --ui-bg-chrome", row.chatSurface, tokens.chatSurface.argb())
            assertEquals("$where: --ui-bg-sidebar", row.sidebarSurface, tokens.sidebarSurface.argb())
            assertEquals("$where: --ui-bg-editor", row.cardSurface, tokens.cardSurface.argb())
            assertEquals("$where: --ui-widget-surface-background", row.widgetSurface, tokens.widgetSurface.argb())
            assertEquals("$where: --ui-chat-bubble-background", row.userBubble, tokens.userBubble.argb())
            assertEquals("$where: --dt-user-bubble-border", row.userBubbleBorder, tokens.userBubbleBorder.argb())
            assertEquals("$where: --ui-inline-code-background", row.inlineCodeBackground, tokens.inlineCodeBackground.argb())
            assertEquals("$where: --ui-inline-code-foreground", row.inlineCodeForeground, tokens.inlineCodeForeground.argb())
        }
    }

    @Test
    fun `inline code is a fixed ink per mode, never the theme foreground`() {
        // styles.css:366 pins #141414 / #ffffff so a code span reads the same in
        // every skin. Every preset must therefore agree with every other one.
        for (dark in listOf(false, true)) {
            val inks = BuiltinThemes.ALL.map { preset ->
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                tokens.inlineCodeBackground.argb() to tokens.inlineCodeForeground.argb()
            }
            assertEquals("inline code must not vary by preset", 1, inks.distinct().size)
        }
    }

    @Test
    fun `optional bubble and sidebar seeds fall back the way desktop does`() {
        // context.tsx:209 (bubble seed is `userBubble ?? popover`), :226
        // (`userBubbleBorder ?? border`), :206 (`sidebarBackground ?? background`).
        // Asserted by *removing* the optional field rather than by restating the
        // arithmetic, so the test cannot drift with the implementation.
        for (preset in BuiltinThemes.ALL) {
            for (dark in listOf(false, true)) {
                val palette = preset.paletteFor(dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"

                assertEquals(
                    "$where: bubble seed must fall back to popover",
                    HermesTokens.from(palette.copy(userBubble = palette.popover), dark).userBubble,
                    HermesTokens.from(palette.copy(userBubble = null), dark).userBubble,
                )
                assertEquals(
                    "$where: bubble border must fall back to border",
                    palette.border,
                    HermesTokens.from(palette.copy(userBubbleBorder = null), dark).userBubbleBorder,
                )
                assertEquals(
                    "$where: sidebar seed must fall back to background",
                    HermesTokens.from(palette.copy(sidebarBackground = palette.background), dark).sidebarSurface,
                    HermesTokens.from(palette.copy(sidebarBackground = null), dark).sidebarSurface,
                )
            }
        }
    }

    @Test
    fun `the rendered mode follows the background, not the request`() {
        // context.tsx:148-158. It is a no-op for all six built-ins — asserted so
        // the parity claim covers it — but it is what keeps a future bright
        // "dark" palette a data edit instead of a component change.
        for (preset in BuiltinThemes.ALL) {
            for (requested in listOf(false, true)) {
                assertEquals(
                    "${preset.name}: rendered mode must match the request",
                    requested,
                    rendersDark(preset.paletteFor(requested).background, requested),
                )
            }
        }

        val brightDark = BuiltinThemes.Mono.colors.copy(background = Color(0xFFF5F5F5))
        assertTrue("a bright background renders light however it was asked for", !rendersDark(brightDark.background, requestedDark = true))
        assertTrue("a dark background renders dark", rendersDark(Color(0xFF0E0E0E), requestedDark = false))
    }

    @Test
    fun `text stays legible on every derived surface`() {
        // A floor, not a WCAG certification — but it now covers the surfaces the
        // derivation actually changed, not just the chat backdrop.
        for (preset in BuiltinThemes.ALL) {
            for (dark in listOf(false, true)) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"

                for ((label, surface) in listOf(
                    "chat surface" to tokens.chatSurface,
                    "user bubble" to tokens.userBubble,
                    "widget surface" to tokens.widgetSurface,
                    "sidebar surface" to tokens.sidebarSurface,
                )) {
                    val ratio = contrastRatio(tokens.textPrimary, surface)
                    assertTrue(
                        "$where: primary text on the $label is ${"%.2f".format(ratio)}:1 " +
                            "(${tokens.textPrimary.toHex()} on ${surface.toHex()})",
                        ratio >= 4.0f,
                    )
                }
            }
        }
    }

    private fun tokensFor(name: String, dark: Boolean): HermesTokens {
        val palette = BuiltinThemes.resolve(name).paletteFor(dark)
        return HermesTokens.from(palette, rendersDark(palette.background, dark))
    }

    /** `#aarrggbb`, so an alpha-bearing token is compared on all four channels. */
    private fun Color.argb(): String {
        fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
        return "#${channel(alpha)}${channel(red)}${channel(green)}${channel(blue)}"
    }
}
