package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
        // (`styles.css:224,227`, `:root.dark:531-532`). `bright` follows
        // Desktop's own direction: across those same six hues
        // (`lib/ansi.ts:149-154` against their bright rungs at `:157-162`)
        // Desktop steps the bright rung one Tailwind step *lighter* in both
        // modes (`red-700 → rose-600` at `:149`/`:157`, `emerald-300 →
        // emerald-200` at `:150`/`:158`) and never a step darker, so here it is
        // an 18 % mix toward white in both modes. The neutrals are not on that
        // ladder — `:156` steps bright-black darker in dark — and are read off
        // Desktop's fixed greys instead. The size is what the floor
        // below allows: 18 % is the largest uniform step that keeps every rung
        // at 3.0:1 as painted.
        //
        // Values below are re-walked by hand from those seeds, never read back
        // from this app. `docs/parity/tool-output-fidelity.md` carries the rule
        // and what it costs.
        val light = mapOf(
            "red" to ("#ff91203c" to "#ffa5485f"),
            "green" to ("#ff166147" to "#ff407d68"),
            "yellow" to ("#ff865d23" to "#ff9c7a4b"),
            "blue" to ("#ff003ab1" to "#ff2e5dbf"),
            "magenta" to ("#ff6f6895" to "#ff8983a8"),
            "cyan" to ("#ff355962" to "#ff59777e"),
        )
        val dark = mapOf(
            "red" to ("#fff09bab" to "#fff3adba"),
            "green" to ("#ff96c7b2" to "#ffa9d1c0"),
            "yellow" to ("#ffd8b380" to "#ffdfc197"),
            "blue" to ("#ff6194fe" to "#ff7da7fe"),
            "magenta" to ("#ffc3bde5" to "#ffcec9ea"),
            "cyan" to ("#ffa6c1c8" to "#ffb6ccd2"),
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
    fun `the four ansi neutrals are desktop's zinc rungs, never pure black or white`() {
        // `lib/ansi.ts:145-147` — Desktop refuses to paint `#000`/`#fff`
        // because they "disappear into the surface", and paints zinc greys
        // instead: 700 / 600 / 500 / 500 in light, 100 / 200 / 300 / 400 in
        // dark (`:148,155,156,163`). Those are fixed for every theme.
        //
        // Android has no Tailwind zinc, so the four rungs are plain greys at
        // zinc's lightness — dark takes zinc's four stops, light is an even
        // ramp anchored on zinc-700 and zinc-600 whose last two stops fall
        // either side of the zinc-500 Desktop ties the two bright rungs at.
        //
        // The text ladder is deliberately *not* used: its lower rungs are
        // alpha washes and, composited onto the tool surface, the quaternary
        // rung is 1.65:1 in the weakest preset — see the floor below.
        val expected = mapOf(
            false to mapOf(
                "black" to "#ff424242", "white" to "#ff555555",
                "brightWhite" to "#ff686868", "brightBlack" to "#ff7b7b7b",
            ),
            true to mapOf(
                "brightWhite" to "#fff4f4f4", "white" to "#ffe5e5e5",
                "black" to "#ffd5d5d5", "brightBlack" to "#ffa2a2a2",
            ),
        )

        for (dark in listOf(false, true)) {
            for (preset in BuiltinThemes.ALL) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"

                assertEquals(
                    "$where: the four ansi neutrals are fixed per mode",
                    expected.getValue(dark),
                    mapOf(
                        "black" to tokens.ansi.black.argb(),
                        "white" to tokens.ansi.white.argb(),
                        "brightWhite" to tokens.ansi.brightWhite.argb(),
                        "brightBlack" to tokens.ansi.brightBlack.argb(),
                    ),
                )

                assertNotEquals("$where: ansi white must not be pure white", Color.White.argb(), tokens.ansi.white.argb())
                assertNotEquals("$where: ansi black must not be pure black", Color.Black.argb(), tokens.ansi.black.argb())

                // Desktop's ordering, per mode: bright-black is the quietest
                // rung in both, and the bold rung is never fainter than it.
                assertTrue(
                    "$where: bright-black must stay the quietest neutral",
                    contrastRatio(tokens.ansi.brightBlack, tokens.widgetSurface) <=
                        contrastRatio(tokens.ansi.brightWhite, tokens.widgetSurface),
                )
            }
        }
    }

    @Test
    fun `every ansi ink is distinct and readable as painted on the tool surface`() {
        // Sixteen colours that collapse into each other are worse than none:
        // the point of painting them is that a reader can tell an error line
        // from a warning line at a glance.
        //
        // Both claims are made about the ink **as painted**. An ANSI ink can be
        // translucent, and a translucent ink's bare value is not what the
        // screen shows — measuring it un-composited is how a 1.65:1 rung once
        // passed a 3.0:1 floor. `over(widgetSurface)` is the blend the renderer
        // performs, and it is the only surface ANSI ink is ever painted on.
        //
        // Distinctness is perceptual, not exact-argb: two inks a hair apart in
        // argb are one colour to a reader. dE76 >= 3.0 is comfortably past the
        // ~2.3 just-noticeable threshold; the tightest pair today is 4.66
        // (dark `cyan` against `brightCyan`, the smallest bright step there is).
        val illegible = mutableListOf<String>()
        val collapsed = mutableListOf<String>()
        val translucent = mutableListOf<String>()

        for (dark in listOf(false, true)) {
            for (preset in BuiltinThemes.ALL) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"
                val surface = tokens.widgetSurface
                val painted = tokens.ansi.all().map { (name, ink) -> name to ink.over(surface) }

                // The invariant that keeps the floor honest rather than merely
                // careful: an opaque ink is its own composite, so no rung can
                // measure one colour here and paint another. Reintroduce a
                // translucent rung and this is where it is named.
                for ((name, ink) in tokens.ansi.all()) {
                    if (ink.alpha != 1f) {
                        translucent += "$where `$name` alpha ${"%.2f".format(ink.alpha)}"
                    }
                }

                for ((name, ink) in painted) {
                    val ratio = contrastRatio(ink, surface)
                    if (ratio < 3.0f) {
                        illegible += "$where `$name` ${"%.2f".format(ratio)}:1 " +
                            "(${ink.toHex()} on ${surface.toHex()})"
                    }
                }

                for (i in painted.indices) {
                    for (j in i + 1 until painted.size) {
                        val distance = deltaE76(painted[i].second, painted[j].second)
                        if (distance < 3.0) {
                            collapsed += "$where `${painted[i].first}`/`${painted[j].first}` " +
                                "dE ${"%.2f".format(distance)}"
                        }
                    }
                }
            }
        }

        assertTrue(
            "ansi inks below the 3.0:1 legibility floor as painted:\n" + illegible.joinToString("\n"),
            illegible.isEmpty(),
        )
        assertTrue(
            "ansi inks a reader cannot tell apart (dE76 < 3.0):\n" + collapsed.joinToString("\n"),
            collapsed.isEmpty(),
        )
        assertTrue(
            "ansi inks that are not their own composite, so the floor above " +
                "would be measuring a colour the screen never shows:\n" + translucent.joinToString("\n"),
            translucent.isEmpty(),
        )
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
        //
        // `textPrimary` is an alpha wash of the palette foreground, so the ratio
        // has to be measured on the composite the renderer produces, not on the
        // token's bare value.
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
                    val painted = tokens.textPrimary.over(surface)
                    val ratio = contrastRatio(painted, surface)
                    assertTrue(
                        "$where: primary text on the $label is ${"%.2f".format(ratio)}:1 " +
                            "(${painted.toHex()} on ${surface.toHex()})",
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

    /**
     * CIE76 colour difference in Lab. Crude next to CIEDE2000, but it is the
     * standard "can a reader tell these apart" yardstick and ~2.3 is the
     * just-noticeable difference, which is the only number this test needs.
     *
     * Both arguments must already be composited: Lab has no alpha.
     */
    private fun deltaE76(first: Color, second: Color): Double {
        fun lab(color: Color): Triple<Double, Double, Double> {
            fun linear(channel: Float): Double {
                val value = channel.toDouble()
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }

            val r = linear(color.red)
            val g = linear(color.green)
            val b = linear(color.blue)
            // sRGB -> XYZ (D65), normalised by the D65 white point.
            val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
            fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
            val fx = f(x)
            val fy = f(y)
            val fz = f(z)
            return Triple(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
        }

        val (l1, a1, b1) = lab(first)
        val (l2, a2, b2) = lab(second)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    /** `#aarrggbb`, so an alpha-bearing token is compared on all four channels. */
    private fun Color.argb(): String {
        fun channel(value: Float) = (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
        return "#${channel(alpha)}${channel(red)}${channel(green)}${channel(blue)}"
    }
}
