package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.reflect.full.memberProperties

/**
 * The theme parity gate.
 *
 * It answers one question: does every Desktop built-in theme at the pinned SHA
 * exist on Android, with every semantic token a component might read, in both
 * modes? A drift here is a visual regression that would otherwise only show up
 * on a device, in one skin, in one mode.
 */
class ThemeParityTest {

    @Test
    fun `every desktop builtin has an android preset with the same identity`() {
        val android = BuiltinThemes.ALL.associateBy { it.name }

        for (entry in DesktopThemeLedger.ENTRIES) {
            val preset = android[entry.name]
            assertNotNull(
                "Desktop ships `${entry.name}` (${DesktopThemeLedger.SOURCE_PATH}:${entry.sourceLines} " +
                    "@ ${DesktopThemeLedger.PINNED_SHA}) but BuiltinThemes.ALL does not.",
                preset,
            )
            assertEquals("label drift for ${entry.name}", entry.label, preset!!.label)
            assertEquals("description drift for ${entry.name}", entry.description, preset.description)
        }
    }

    @Test
    fun `android ships no theme desktop does not have`() {
        val expected = DesktopThemeLedger.ENTRIES.map { it.name }
        assertEquals(
            "Registry drift. Add the theme upstream first, or update DesktopThemeLedger as part of a sync.",
            expected,
            BuiltinThemes.ALL.map { it.name },
        )
    }

    @Test
    fun `hand-tuned dark palettes are preserved, synthesised ones are not faked`() {
        for (entry in DesktopThemeLedger.ENTRIES) {
            val preset = BuiltinThemes.resolve(entry.name)
            assertEquals(
                "${entry.name}: darkColors presence must match Desktop " +
                    "(${DesktopThemeLedger.PALETTE_SOURCE_PATH} @ ${DesktopThemeLedger.PINNED_SHA}). " +
                    "A hand-tuned dark half cannot be replaced by synthesis, and a dark-first " +
                    "preset must not gain an invented light palette.",
                entry.hasHandTunedDark,
                preset.darkColors != null,
            )
        }
    }

    @Test
    fun `default skin matches desktop`() {
        assertEquals(DesktopThemeLedger.DEFAULT_SKIN, BuiltinThemes.DEFAULT_NAME)
    }

    @Test
    fun `unknown and retired skin names fall back to the default`() {
        assertEquals(BuiltinThemes.Nous, BuiltinThemes.resolve("solarized-does-not-exist"))
        assertEquals(BuiltinThemes.Nous, BuiltinThemes.resolve(null))
    }

    @Test
    fun `every required desktop colour key is a non-null android palette field`() {
        val fields = HermesPalette::class.memberProperties.associateBy { it.name }
        for (key in DesktopThemeLedger.REQUIRED_COLOR_KEYS) {
            val field = fields[key]
            assertNotNull("HermesPalette is missing required key `$key`", field)
            assertFalse(
                "`$key` is required in DesktopThemeColors and must not be nullable on Android",
                field!!.returnType.isMarkedNullable,
            )
        }
        for (key in DesktopThemeLedger.OPTIONAL_COLOR_KEYS) {
            assertNotNull("HermesPalette is missing optional key `$key`", fields[key])
        }
    }

    @Test
    fun `every preset resolves a complete token set in both modes`() {
        for (preset in BuiltinThemes.ALL) {
            for (dark in listOf(false, true)) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                val inks = tokens.everyInk()
                assertTrue("token contract went empty — the reflection check is broken", inks.isNotEmpty())

                for ((name, value) in inks) {
                    assertTrue(
                        "${preset.name} (${if (dark) "dark" else "light"}): token `$name` is " +
                            "fully transparent, which means it resolved from a missing fallback.",
                        value.alpha > 0f,
                    )
                }
            }
        }
    }

    @Test
    fun `the reflection walk reaches tokens that live in a nested group`() {
        // A token group added as its own data class must not silently opt out of
        // the completeness check above — which is exactly what a flat
        // `returnType == Color` filter would let it do.
        val names = HermesTokens.from(BuiltinThemes.Nous.paletteFor(dark = true), dark = true)
            .everyInk()
            .map { it.first }

        assertTrue("the flat tokens must still be walked", names.contains("accent"))
        assertTrue("nested groups must be walked too, saw $names", names.contains("ansi.brightRed"))
    }

    /**
     * Every colour a component can reach through [HermesTokens], flattened.
     *
     * Walks one level into token *groups* — a data class whose properties are
     * all colours — so grouping tokens for readability never costs them their
     * coverage here.
     */
    private fun HermesTokens.everyInk(): List<Pair<String, Color>> =
        HermesTokens::class.memberProperties.flatMap { field ->
            when (val value = field.get(this)) {
                is Color -> listOf(field.name to value)
                else -> value?.let { group ->
                    group::class.memberProperties
                        .mapNotNull { nested ->
                            @Suppress("UNCHECKED_CAST")
                            val read = nested as kotlin.reflect.KProperty1<Any, *>
                            (read.get(group) as? Color)?.let { "${field.name}.${nested.name}" to it }
                        }
                }.orEmpty()
            }
        }

    @Test
    fun `optional palette fields always resolve through a fallback, never to nothing`() {
        for (preset in BuiltinThemes.ALL) {
            for (dark in listOf(false, true)) {
                val palette = preset.paletteFor(dark)
                val tokens = HermesTokens.from(palette, dark)
                val where = "${preset.name}/${if (dark) "dark" else "light"}"

                // midground falls back to ring (types.ts:32-37).
                assertEquals("$where: accent must resolve midground ?: ring", palette.midground ?: palette.ring, tokens.accent)
                // composerRing falls back to midground (types.ts:40-41).
                assertEquals(
                    "$where: composerRing must resolve composerRing ?: midground ?: ring",
                    palette.composerRing ?: palette.midground ?: palette.ring,
                    tokens.composerRing,
                )
            }
        }
    }

    // Legibility floors live with the value table in `ThemeSemanticParityTest`,
    // which checks the same chat surface plus the three other derived surfaces.

    @Test
    fun `cyberpunk keeps desktop's whole-ui monospace behaviour`() {
        // presets.ts:806-809 sets fontSans AND fontMono to Courier. Every other
        // preset only names a mono face.
        assertEquals(HermesFontFamily.Mono, BuiltinThemes.Cyberpunk.fonts.sans)
        for (preset in BuiltinThemes.ALL.filterNot { it.name == "cyberpunk" }) {
            assertEquals(
                "${preset.name} must keep a sans body; only cyberpunk goes monospace",
                HermesFontFamily.Sans,
                preset.fonts.sans,
            )
        }
    }

    /**
     * A fill/mode pair on which Desktop's own ink pair has no legible member:
     * the one pair whose best Desktop ink still misses AA, with the ratio that
     * ink achieves.
     *
     * Naming it here is the point. An unnamed exception is a silent pass; this
     * one is asserted to be a *near-miss on Desktop's two-ink set*, so the day
     * a palette edit fixes it, or the floor quietly moves, the test says so.
     *
     * `slate` dark destructive is `#CF4848`. White scores 4.49:1, Desktop's
     * near-black `#161616` 4.03:1 — nothing in Desktop's two-ink set
     * (`themes/color.ts:14` `DESKTOP_INKS`) clears 4.5:1 there, so the rule
     * cannot reach AA by picking an ink. An ink outside that pair would: pure
     * `#000000` measures 4.67:1. That is why the fix belongs on the *fill*
     * side rather than in an off-palette ink — Desktop already paints this
     * action softer in dark, at `bg-destructive/60`
     * (`components/ui/button.tsx:21` @
     * `437116f9497c80d242ce034ff7f5d81dc277a337`), where white on the
     * composite over `cardSurface` reaches 8.22:1.
     */
    private val paletteCannotCarryAA = mapOf(
        "slate/dark destructive" to 4.49f,
    )

    private data class FillCase(
        val fill: String,
        val fillColor: Color,
        val ink: Color,
        /** The palette's own partner for this fill, where it declares one. */
        val paired: Color?,
    )

    /**
     * Every filled action, in both modes, for every built-in preset: the
     * `(fill, ink, palette pairing)` triple the AA contract is measured over.
     */
    private fun fillCases(preset: HermesThemePreset): List<Pair<String, List<FillCase>>> {
        val cases = mutableListOf<Pair<String, List<FillCase>>>()
        for (requested in listOf(false, true)) {
            val palette = preset.paletteFor(requested)
            val dark = rendersDark(palette.background, requested)
            val tokens = HermesTokens.from(palette, dark)
            val where = "${preset.name}/${if (dark) "dark" else "light"}"
            cases += where to listOf(
                FillCase("accent", tokens.accent, tokens.filledActionInk, palette.midgroundForeground),
                FillCase(
                    "destructive", tokens.destructive, tokens.destructiveActionInk,
                    palette.destructiveForeground,
                ),
            )
        }
        return cases
    }

    @Test
    fun `filled action ink follows its fill and clears AA on every preset and mode`() {
        // #140. Desktop never chooses a filled action's fill and its ink
        // independently: `components/ui/button.tsx:19,21` @
        // 437116f9497c80d242ce034ff7f5d81dc277a337 writes `bg-primary
        // text-primary-foreground` and `bg-destructive text-white`. A component
        // that takes the fill as an argument and fixes the ink — what
        // `PrimaryButton` did — paints `accentForeground`, the ink for the
        // *tinted* accent surface, on a saturated fill: 2.75:1 on the default
        // skin's `Save`, and as low as 1.05:1 (`everforest` light).
        //
        // Both halves of the contract are measured here, over the light and the
        // dark palette of every built-in: the ink follows the fill, and it
        // clears WCAG AA for body text on the fill it is actually painted on.
        val failures = mutableListOf<String>()
        val honoured = mutableListOf<String>()
        val nearMisses = mutableListOf<String>()

        for (preset in BuiltinThemes.ALL) {
            for ((where, cases) in fillCases(preset)) {
                for (case in cases) {
                    val key = "$where ${case.fill}"
                    val ratio = contrastRatio(case.ink, case.fillColor)
                    val declaredFloor = paletteCannotCarryAA[key]

                    if (declaredFloor == null && ratio < AA_BODY_TEXT_CONTRAST) {
                        failures += "$key: ${case.ink.toHex()} on ${case.fillColor.toHex()} is " +
                            "${"%.2f".format(ratio)}:1, under AA"
                    }
                    // The pairing is honoured, not overridden on a hunch:
                    // wherever the palette declares Desktop's own partner for
                    // this fill and that partner is legible, it is the ink.
                    if (case.paired != null &&
                        contrastRatio(case.paired, case.fillColor) >= AA_BODY_TEXT_CONTRAST
                    ) {
                        honoured += key
                        if (case.ink != case.paired) {
                            failures += "$key: palette pairs ${case.paired.toHex()} " +
                                "(${"%.2f".format(contrastRatio(case.paired, case.fillColor))}:1) " +
                                "but the ink is ${case.ink.toHex()}"
                        }
                    }
                    // A declared near-miss is verified, not trusted: it must be
                    // close to the floor and genuinely have no legible member
                    // in Desktop's own ink pair, or it is not an exception but
                    // a silent pass.
                    if (declaredFloor != null) {
                        nearMisses += key
                        assertTrue(
                            "$key is declared a near-miss at " +
                                "%.2f".format(declaredFloor) + ":1 but measures " +
                                "%.2f".format(ratio) + ":1",
                            abs(ratio - declaredFloor) < 0.02f,
                        )
                        // Desktop's pair, not every colour that exists: pure
                        // black would clear AA here, so read the declaration as
                        // "Desktop's ink set cannot do it", never "nothing can".
                        val available = maxOf(
                            contrastRatio(case.fillColor, Color.White),
                            contrastRatio(case.fillColor, Color(0xFF161616)),
                        )
                        assertTrue(
                            "$key is declared a palette exception, but Desktop's ink pair reaches " +
                                "%.2f".format(available) + ":1 on it, so no exception is owed",
                            available < AA_BODY_TEXT_CONTRAST,
                        )
                    }
                }
            }
        }

        assertTrue(
            "filled-action ink that does not follow its fill or misses the AA floor:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
        assertEquals(
            "the named palette exceptions must be exactly the ones measured, and no wider",
            paletteCannotCarryAA.keys,
            nearMisses.toSet(),
        )
        assertTrue(
            "the palette pairing must actually be honoured somewhere or this test is vacuous; " +
                "honoured $honoured of 44 fill/mode pairs",
            honoured.size >= 20,
        )
    }

    @Test
    fun `the default skin paints desktop's own paired ink on each filled action`() {
        // The two fills the issue measured, on the skin that renders by
        // default. Desktop's `primary-foreground` for Nous light is white
        // (`apps/shared/src/theme-presets.ts:136` @
        // 437116f9497c80d242ce034ff7f5d81dc277a337) and its destructive action
        // is `text-white` (`button.tsx:21`), so both filled actions carry white
        // here — at 5.74:1 and 5.36:1.
        val light = HermesTokens.from(BuiltinThemes.Nous.colors, dark = false)

        assertEquals(Color(0xFFFFFFFF), light.filledActionInk)
        assertEquals(Color(0xFFFFFFFF), light.destructiveActionInk)
        assertEquals(5.74f, contrastRatio(light.filledActionInk, light.accent), 0.01f)
        assertEquals(5.36f, contrastRatio(light.destructiveActionInk, light.destructive), 0.01f)

        // And the ink this replaced, which is what made `Save` illegible.
        assertEquals(2.75f, contrastRatio(light.accentForeground, light.accent), 0.01f)
    }

    @Test
    fun `the two filled actions may not share one ink where their fills differ`() {
        // The regression this guards: one fixed foreground for both variants.
        // `cyberpunk` dark is the clearest case — its accent is a bright green
        // that carries near-black, its destructive a red that carries Desktop's
        // own `#000A00` (`apps/shared/src/theme-presets.ts:518` @
        // 437116f9497c80d242ce034ff7f5d81dc277a337).
        val tokens = HermesTokens.from(BuiltinThemes.Cyberpunk.colors, dark = true)

        assertEquals(Color(0xFF161616), tokens.filledActionInk)
        assertEquals(Color(0xFF000A00), tokens.destructiveActionInk)
        assertNotEquals(tokens.filledActionInk, tokens.destructiveActionInk)
    }
}
