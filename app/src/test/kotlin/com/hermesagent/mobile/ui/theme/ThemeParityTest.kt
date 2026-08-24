package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
                "${entry.name}: darkColors presence must match Desktop. A hand-tuned dark half " +
                    "cannot be replaced by synthesis, and a dark-first preset must not gain an " +
                    "invented light palette.",
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
        val tokenFields = HermesTokens::class.memberProperties.filter { it.returnType.classifier == Color::class }
        assertTrue("token contract went empty — the reflection check is broken", tokenFields.isNotEmpty())

        for (preset in BuiltinThemes.ALL) {
            for (dark in listOf(false, true)) {
                val tokens = HermesTokens.from(preset.paletteFor(dark), dark)
                for (field in tokenFields) {
                    val value = field.get(tokens) as Color
                    assertTrue(
                        "${preset.name} (${if (dark) "dark" else "light"}): token `${field.name}` is " +
                            "fully transparent, which means it resolved from a missing fallback.",
                        value.alpha > 0f,
                    )
                }
            }
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
}
