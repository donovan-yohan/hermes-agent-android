package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The **value** half of theme parity: does every semantic surface resolve to the
 * colour Desktop paints, in both modes, for every built-in?
 *
 * [ThemeParityTest] proves the registry and the token *contract*; this proves
 * the arithmetic. It exists because a token can be present, opaque and legible
 * and still be the wrong colour — which is exactly how the first port shipped
 * raw palette fields where Desktop mixes each seed with a per-mode neutral.
 *
 * **Where the expected values come from.** Each one is derived from the pinned
 * Desktop sources (`45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`), never from
 * this app's own output, by re-walking the
 * chain by hand:
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
 * `synthLightColors` (`context.tsx:84-118`), which is why dark-first presets
 * land on the same near-white chrome and the same `#fcfcfc` bubble.
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
        Expected("nous", false, "#fffefefe", "#fff6f8fa", "#fffbfbfc", "#fffbfbfc", "#fffcfcfc", "#ffd0d7de", "#0d141414", "#e0141414"),
        Expected("nous", true, "#ff0d1015", "#ff010409", "#ff0e0f12", "#ff0c0d10", "#ff0f1621", "#ff30363d", "#12ffffff", "#e0ffffff"),
        Expected("github", false, "#fffefefe", "#fff6f8fa", "#fffbfbfc", "#fffbfbfc", "#fffcfcfc", "#ffd0d7de", "#0d141414", "#e0141414"),
        Expected("github", true, "#ff0d1015", "#ff010409", "#ff0e0f12", "#ff0c0d10", "#ff131b18", "#ff30363d", "#12ffffff", "#e0ffffff"),
        Expected("catppuccin", false, "#ffeff1f5", "#ffe6e9ef", "#fff7f8f9", "#fff7f8f9", "#fffcfcfc", "#ffacb0be", "#0d141414", "#e0141414"),
        Expected("catppuccin", true, "#ff1a1a26", "#ff181825", "#ff17171d", "#ff14141a", "#ff26232f", "#ff585b70", "#12ffffff", "#e0ffffff"),
        Expected("everforest", false, "#fffcf6e4", "#fffdf6e3", "#fffcfbf6", "#fffcfbf6", "#fffcfcfc", "#fffdf6e3", "#0d141414", "#e0141414"),
        Expected("everforest", true, "#ff252b2f", "#ff2d353b", "#ff1f2225", "#ff1b1e21", "#ff2b302e", "#ff2d353b", "#12ffffff", "#e0ffffff"),
        Expected("solarized", false, "#fffcf6e4", "#ffeee8d5", "#fff3f1ed", "#fff3f1ed", "#fffcfcfc", "#ffddd6c1", "#0d141414", "#e0141414"),
        Expected("solarized", true, "#ff03232c", "#ff001f26", "#ff0e1e23", "#ff0c1a1f", "#ff152932", "#ff234751", "#12ffffff", "#e0ffffff"),
        Expected("nous-alt", false, "#fff8f9fe", "#fff3f7ff", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#3d0053fd", "#0d141414", "#e0141414"),
        Expected("nous-alt", true, "#ff0d2667", "#ff09286f", "#ff142345", "#ff121f3d", "#ff152750", "#ff3a63bd", "#12ffffff", "#e0ffffff"),
        Expected("midnight", false, "#fffefefe", "#fff4f4f9", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffdeddee", "#0d141414", "#e0141414"),
        Expected("midnight", true, "#ff090918", "#ff06061a", "#ff13131e", "#ff11111a", "#ff151528", "#ff242466", "#12ffffff", "#e0ffffff"),
        Expected("ember", false, "#fffefefe", "#fff8f3ef", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffe9dbd1", "#0d141414", "#e0141414"),
        Expected("ember", true, "#ff140904", "#ff100600", "#ff191310", "#ff16110e", "#ff1f130d", "#ff4a2010", "#12ffffff", "#e0ffffff"),
        Expected("mono", false, "#fffefefe", "#fff5f5f5", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffe1e1e3", "#0d141414", "#e0141414"),
        Expected("mono", true, "#ff0e0e0e", "#ff0a0a0a", "#ff151516", "#ff121213", "#ff181819", "#ff363636", "#12ffffff", "#e0ffffff"),
        Expected("slate", false, "#fffefefe", "#fff2f6fa", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffd7e2f1", "#0d141414", "#e0141414"),
        Expected("slate", true, "#ff0d1015", "#ff090d13", "#ff16181c", "#ff131519", "#ff1a1f27", "#ff2e4060", "#12ffffff", "#e0ffffff"),
        Expected("cyberpunk", false, "#fffefefe", "#ffeefaf1", "#fffdfdfd", "#fffdfdfd", "#fffcfcfc", "#ffcbefd7", "#0d141414", "#e0141414"),
        Expected("cyberpunk", true, "#ff030b04", "#ff000600", "#ff0e140f", "#ff0c120d", "#ff0c150d", "#ff004800", "#12ffffff", "#e0ffffff"),
    )

    /** The six `--ui-diff-*` values, which are fixed per mode rather than per preset. */
    private data class DiffPalette(
        val dark: Boolean,
        val addBorder: String,
        val addBackground: String,
        val addForeground: String,
        val removeBorder: String,
        val removeBackground: String,
        val removeForeground: String,
    )

    private val diffPalettes = listOf(
        DiffPalette(false, "#ff1f8a65", "#1f1f8a65", "#ff166147", "#ffcf2d56", "#1fcf2d56", "#ff91203c"),
        DiffPalette(true, "#ff55a583", "#1f55a583", "#ff96c7b2", "#ffe75e78", "#1fe75e78", "#fff09bab"),
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
    fun `the selection highlight is one fixed amber per mode, never the accent`() {
        // styles.css:382 / :root.dark:564 @ DesktopThemeLedger.PINNED_SHA
        // (45fcaaa5), where every other expectation in this file is read, pins
        // color-mix(in srgb, #ffd24a 55%|38%, transparent) for every skin, so a
        // highlight cannot vanish into a warm palette or follow the brand hue.
        // Byte-identical at f82f2dba (styles.css:368 / :root.dark:545), the SHA
        // the rest of the port cites, so the two pins do not disagree here.
        for ((dark, expected) in listOf(false to "#8cffd24a", true to "#61ffd24a")) {
            for (preset in BuiltinThemes.ALL) {
                assertEquals(
                    "${preset.name}/${if (dark) "dark" else "light"}: --ui-selection-background",
                    expected,
                    HermesTokens.from(preset.paletteFor(dark), dark).selectionBackground.argb(),
                )
            }
        }
    }

    @Test
    fun `the diff palette derives from desktop's green and red in each mode`() {
        // styles.css:196-199,222-227 and `:root.dark:528-532` @
        // f82f2dbabd9e66b714f2b4f8a40447fe0c13e732 — byte-identical at upstream
        // HEAD, checked 2026-08-26. Desktop names no diff colour of its own: the
        // border IS `--ui-green`/`--ui-red`, the background is that seed at 12%,
        // and the foreground mixes the seed toward the page (70% toward #000 in
        // light, 62% toward #fff in dark). Both seeds are fixed per mode, so —
        // like inline code — a diff must read identically in every skin.
        for (row in diffPalettes) {
            for (preset in BuiltinThemes.ALL) {
                val tokens = HermesTokens.from(preset.paletteFor(row.dark), row.dark)
                val where = "${preset.name}/${if (row.dark) "dark" else "light"}"

                assertEquals("$where: --ui-diff-add-border", row.addBorder, tokens.diffAdded.argb())
                assertEquals("$where: --ui-diff-add-background", row.addBackground, tokens.diffAddedBackground.argb())
                assertEquals("$where: --ui-diff-add-foreground", row.addForeground, tokens.diffAddedForeground.argb())
                assertEquals("$where: --ui-diff-remove-border", row.removeBorder, tokens.diffRemoved.argb())
                assertEquals("$where: --ui-diff-remove-background", row.removeBackground, tokens.diffRemovedBackground.argb())
                assertEquals("$where: --ui-diff-remove-foreground", row.removeForeground, tokens.diffRemovedForeground.argb())

                // `InlineDiffPanel` shipped reading `statusUnread` (the
                // unread-session dot) and `destructive` (the destructive-action
                // red) — a different semantic that merely happened to be green
                // and red, and that moves with the palette. Asserting the
                // *inequality* makes a silent revert to them a red test rather
                // than a subtle drift. Which tokens the panel actually reads is
                // check 10 in `scripts/check-repo-invariants.sh`.
                assertNotEquals(
                    "$where: an added line must not borrow the unread-session dot",
                    tokens.statusUnread.argb(),
                    tokens.diffAdded.argb(),
                )
                assertNotEquals(
                    "$where: a removed line must not borrow the destructive-action red",
                    tokens.destructive.argb(),
                    tokens.diffRemoved.argb(),
                )
            }
        }
    }

    @Test
    fun `the ansi ladder derives from desktop's named colour set in each mode`() {
        // Desktop maps ANSI to fixed Tailwind classes (`lib/ansi.ts:144-164` @
        // f82f2dbabd9e66b714f2b4f8a40447fe0c13e732). Android cannot: those are a
        // CSS framework's palette tuned against one surface, and this app paints
        // tool output on a per-preset `widgetSurface`. So the ladder is derived
        // from Desktop's *own* named colours — `--ui-red`, `--ui-yellow`,
        // `--ui-green`, `--ui-cyan`, `--ui-blue`, `--ui-purple`
        // (`styles.css:196-202`, `:root.dark:528-530`), which cover exactly the
        // six hues ANSI names — using the diff-foreground knob Desktop already
        // uses to turn one of those seeds into legible ink
        // (`styles.css:224,227`, `:root.dark:531-532`). `bright` is that knob
        // applied a second time: intensity is prominence against the page, so a
        // bright hue goes darker still on a light page and lighter still on a
        // dark one.
        //
        // Values below are re-walked by hand from those seeds, never read back
        // from this app. `docs/parity/tool-output-fidelity.md` carries the rule
        // and what it costs.
        val light = mapOf(
            "red" to ("#ff91203c" to "#ff66162a"),
            "green" to ("#ff166147" to "#ff0f4432"),
            "yellow" to ("#ff865d23" to "#ff5e4119"),
            "blue" to ("#ff003ab1" to "#ff00297c"),
            "magenta" to ("#ff6f6895" to "#ff4e4968"),
            "cyan" to ("#ff355962" to "#ff253e45"),
        )
        val dark = mapOf(
            "red" to ("#fff09bab" to "#fff6c1cb"),
            "green" to ("#ff96c7b2" to "#ffbedccf"),
            "yellow" to ("#ffd8b380" to "#ffe7d0b0"),
            "blue" to ("#ff6194fe" to "#ff9dbdfe"),
            "magenta" to ("#ffc3bde5" to "#ffdad6ef"),
            "cyan" to ("#ffa6c1c8" to "#ffc8d9dd"),
        )

        for ((isDark, expected) in listOf(false to light, true to dark)) {
            for (preset in BuiltinThemes.ALL) {
                val ansi = HermesTokens.from(preset.paletteFor(isDark), isDark).ansi
                val where = "${preset.name}/${if (isDark) "dark" else "light"}"
                val actual = mapOf(
                    "red" to (ansi.red.argb() to ansi.brightRed.argb()),
                    "green" to (ansi.green.argb() to ansi.brightGreen.argb()),
                    "yellow" to (ansi.yellow.argb() to ansi.brightYellow.argb()),
                    "blue" to (ansi.blue.argb() to ansi.brightBlue.argb()),
                    "magenta" to (ansi.magenta.argb() to ansi.brightMagenta.argb()),
                    "cyan" to (ansi.cyan.argb() to ansi.brightCyan.argb()),
                )

                // Fixed per mode, like inline code and the diff palette: a build
                // log must read the same in all eleven skins.
                assertEquals("$where: the six hues are fixed per mode", expected, actual)
            }
        }
    }

    @Test
    fun `ansi green and red are the theme's green and red, not a second opinion`() {
        // The whole reason the ladder is derived rather than transcribed: the
        // seeds are already in the theme. If these ever diverge, a terminal's
        // green and an inline diff's green have quietly become two colours.
        for (dark in listOf(false, true)) {
            for (preset in BuiltinThemes.ALL) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"

                assertEquals("$where: ansi green", tokens.diffAddedForeground.argb(), tokens.ansi.green.argb())
                assertEquals("$where: ansi red", tokens.diffRemovedForeground.argb(), tokens.ansi.red.argb())
            }
        }
    }

    @Test
    fun `the four ansi neutrals are the text ladder, never pure black or white`() {
        // ansi.ts:145-147 — Desktop refuses to paint `#000`/`#fff` because they
        // vanish into the surface. Android's answer is the text ladder itself,
        // which is the one part of the ladder that tracks the preset.
        for (dark in listOf(false, true)) {
            for (preset in BuiltinThemes.ALL) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"

                assertEquals("$where: ansi black", tokens.textTertiary.argb(), tokens.ansi.black.argb())
                assertEquals("$where: ansi bright black", tokens.textQuaternary.argb(), tokens.ansi.brightBlack.argb())
                assertEquals("$where: ansi white", tokens.textSecondary.argb(), tokens.ansi.white.argb())
                assertEquals("$where: ansi bright white", tokens.textPrimary.argb(), tokens.ansi.brightWhite.argb())

                assertNotEquals("$where: ansi white must not be pure white", Color.White.argb(), tokens.ansi.white.argb())
                assertNotEquals("$where: ansi black must not be pure black", Color.Black.argb(), tokens.ansi.black.argb())
            }
        }
    }

    @Test
    fun `every ansi ink is distinct and readable on the tool surface`() {
        // Sixteen colours that collapse into each other are worse than none:
        // the point of painting them is that a reader can tell an error line
        // from a warning line at a glance.
        for (dark in listOf(false, true)) {
            for (preset in BuiltinThemes.ALL) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"
                val inks = tokens.ansi.all()

                assertEquals("$where: two ansi inks resolved to the same colour", 16, inks.map { it.second.argb() }.toSet().size)

                for ((name, ink) in inks) {
                    val ratio = contrastRatio(ink, tokens.widgetSurface)
                    assertTrue(
                        "$where: ansi `$name` is ${"%.2f".format(ratio)}:1 on the tool surface " +
                            "(${ink.toHex()} on ${tokens.widgetSurface.toHex()})",
                        ratio >= 3.0f,
                    )
                }
            }
        }
    }

    private fun HermesAnsiInk.all(): List<Pair<String, Color>> = listOf(
        "black" to black, "red" to red, "green" to green, "yellow" to yellow,
        "blue" to blue, "magenta" to magenta, "cyan" to cyan, "white" to white,
        "brightBlack" to brightBlack, "brightRed" to brightRed, "brightGreen" to brightGreen,
        "brightYellow" to brightYellow, "brightBlue" to brightBlue, "brightMagenta" to brightMagenta,
        "brightCyan" to brightCyan, "brightWhite" to brightWhite,
    )

    @Test
    fun `running outline uses the desktop bright stop in each rendered mode`() {
        // styles.css:1011-1040,1129-1144 @
        // 45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8: `.arc-row` keeps
        // --arc-c1 at --dt-foreground in dark mode and --dt-midground in light.
        for (preset in BuiltinThemes.ALL) {
            for (dark in listOf(false, true)) {
                val palette = preset.paletteFor(dark)
                val expected = if (dark) palette.foreground else palette.midground ?: palette.ring
                assertEquals(
                    "${preset.name}/${if (dark) "dark" else "light"}: running outline bright stop",
                    expected,
                    HermesTokens.from(palette, dark).sessionRunningOutline,
                )
            }
        }
    }

    @Test
    fun `accent foreground preserves Desktop's independent palette semantic`() {
        // context.tsx:233-240 @ 45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8:
        // --dt-accent-foreground is c.accentForeground, not the separate
        // c.midgroundForeground token.
        for (preset in BuiltinThemes.ALL) {
            for (dark in listOf(false, true)) {
                val palette = preset.paletteFor(dark)
                assertEquals(
                    "${preset.name}/${if (dark) "dark" else "light"}: accent foreground",
                    palette.accentForeground,
                    HermesTokens.from(palette, dark).accentForeground,
                )
            }
        }
    }

    @Test
    fun `Nous palette and selected-session fill match the Desktop sidebar`() {
        // `presets.ts:174-277` @ 45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8.
        assertPalette(
            BuiltinThemes.Nous.colors,
            mapOf(
                "background" to "#ffffffff", "foreground" to "#ff1f2328",
                "card" to "#fff6f8fa", "cardForeground" to "#ff1f2328",
                "muted" to "#fff6f6f6", "mutedForeground" to "#ff656d76",
                "popover" to "#ffffffff", "popoverForeground" to "#ff1f2328",
                "primary" to "#ff0053fd", "primaryForeground" to "#ffffffff",
                "secondary" to "#ffdeeaff", "secondaryForeground" to "#ff1f2328",
                "accent" to "#ffe3edff", "accentForeground" to "#ff1f2328",
                "border" to "#ffd0d7de", "input" to "#ffffffff", "ring" to "#ff0053fd",
                "destructive" to "#ffcf222e", "destructiveForeground" to "#ffffffff",
                "midground" to "#ff0053fd", "midgroundForeground" to "#ffffffff",
                "composerRing" to "#ff0053fd", "sidebarBackground" to "#fff6f8fa",
                "sidebarBorder" to "#ffd0d7de", "userBubble" to "#ffdae7fd",
                "userBubbleBorder" to "#ffd0d7de",
            ),
        )
        assertPalette(
            BuiltinThemes.Nous.paletteFor(dark = true),
            mapOf(
                "background" to "#ff0d1117", "foreground" to "#ffe6edf3",
                "card" to "#ff010409", "cardForeground" to "#ffe6edf3",
                "muted" to "#ff1a1e24", "mutedForeground" to "#ff7d8590",
                "popover" to "#ff161b22", "popoverForeground" to "#ffe6edf3",
                "primary" to "#ff4a84fe", "primaryForeground" to "#ff161616",
                "secondary" to "#ff1d2e4f", "secondaryForeground" to "#ffe6edf3",
                "accent" to "#ff17243a", "accentForeground" to "#ffe6edf3",
                "border" to "#ff30363d", "input" to "#ff0d1117", "ring" to "#ff4a84fe",
                "destructive" to "#fff85149", "destructiveForeground" to "#ffffffff",
                "midground" to "#ff4a84fe", "midgroundForeground" to "#ff161616",
                "composerRing" to "#ff4a84fe", "sidebarBackground" to "#ff010409",
                "sidebarBorder" to "#ff30363d", "userBubble" to "#ff07162c",
                "userBubbleBorder" to "#ff30363d",
            ),
        )

        val light = HermesTokens.from(BuiltinThemes.Nous.colors, dark = false)
        assertEquals("#fff6f8fa", light.sidebarSurface.argb())
        assertEquals("#f01f2328", light.textPrimary.argb())
        assertEquals("#ff0053fd", light.accent.argb())
        assertEquals("#ff0053fd", light.sessionRunningOutline.argb())
        assertEquals("#200b41ae", light.sessionRowActiveSurface.argb())

        val dark = HermesTokens.from(BuiltinThemes.Nous.paletteFor(dark = true), dark = true)
        assertEquals("#ff010409", dark.sidebarSurface.argb())
        assertEquals("#f0e6edf3", dark.textPrimary.argb())
        assertEquals("#ff4a84fe", dark.accent.argb())
        assertEquals("#ffe6edf3", dark.sessionRunningOutline.argb())
        assertEquals("#2084abfa", dark.sessionRowActiveSurface.argb())
    }

    @Test
    fun `selected-session fill falls back from midground to ring`() {
        val palette = BuiltinThemes.Nous.colors.copy(
            midground = null,
            ring = Color(0xFF7F10E8),
        )
        val tokens = HermesTokens.from(palette, dark = false)

        assertEquals("#ff7f10e8", tokens.accent.argb())
        assertEquals(
            mixPremultiplied(palette.ring, 8f, palette.foreground.withAlpha(0.05f)),
            tokens.sessionRowActiveSurface,
        )
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
        // context.tsx:148-158. It is a no-op for all built-ins — asserted so
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

    private fun assertPalette(palette: HermesPalette, expected: Map<String, String>) {
        val actual = mapOf(
            "background" to palette.background, "foreground" to palette.foreground,
            "card" to palette.card, "cardForeground" to palette.cardForeground,
            "muted" to palette.muted, "mutedForeground" to palette.mutedForeground,
            "popover" to palette.popover, "popoverForeground" to palette.popoverForeground,
            "primary" to palette.primary, "primaryForeground" to palette.primaryForeground,
            "secondary" to palette.secondary, "secondaryForeground" to palette.secondaryForeground,
            "accent" to palette.accent, "accentForeground" to palette.accentForeground,
            "border" to palette.border, "input" to palette.input, "ring" to palette.ring,
            "destructive" to palette.destructive, "destructiveForeground" to palette.destructiveForeground,
            "midground" to palette.midground, "midgroundForeground" to palette.midgroundForeground,
            "composerRing" to palette.composerRing, "sidebarBackground" to palette.sidebarBackground,
            "sidebarBorder" to palette.sidebarBorder, "userBubble" to palette.userBubble,
            "userBubbleBorder" to palette.userBubbleBorder,
        ).mapValues { (_, color) -> color?.argb() }
        assertEquals(expected, actual)
    }

    /** `#aarrggbb`, so an alpha-bearing token is compared on all four channels. */
    private fun Color.argb(): String {
        fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
        return "#${channel(alpha)}${channel(red)}${channel(green)}${channel(blue)}"
    }
}
