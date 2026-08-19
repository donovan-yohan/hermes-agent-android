package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ported colour arithmetic against values computed from the Desktop
 * source expressions. If a Desktop `color-mix` is ever re-read differently,
 * these fail rather than shifting the whole palette quietly.
 */
class ColorMathTest {

    private val nousBlue = Color(0xFF0053FD)

    @Test
    fun `nousTint resolves the same as CSS color-mix over white`() {
        // presets.ts:26 — color-mix(in srgb, #0053FD 5%, #FFFFFF)
        // 0.05*(0,83,253) + 0.95*(255,255,255) = (242.25, 246.4, 254.9)
        assertEquals("#f2f6ff", mixPremultiplied(nousBlue, 5f, Color.White).toHex())
        assertEquals("#edf3ff", mixPremultiplied(nousBlue, 7f, Color.White).toHex())
        assertEquals("#e6eeff", mixPremultiplied(nousBlue, 10f, Color.White).toHex())
    }

    @Test
    fun `mixing with transparent lowers alpha and keeps the hue`() {
        // presets.ts:27 — color-mix(in srgb, X n%, transparent) is "X at n% alpha"
        // because sRGB mixing is premultiplied.
        val border = mixPremultiplied(nousBlue, 22f, Color.Transparent)
        assertEquals("#0053fd", border.toHex())
        assertEquals(0.22f, border.alpha, 0.005f)
    }

    @Test
    fun `readableOn matches desktop's 0_58 luminance split`() {
        // color.ts:63-65
        assertEquals(Color(0xFF161616), readableOn(Color.White))
        assertEquals(Color(0xFFFFFFFF), readableOn(Color(0xFF0D2F86)))
    }

    @Test
    fun `mix is an opaque lerp, unlike color-mix`() {
        // color.ts:29-36 — no alpha handling at all.
        assertEquals("#808080", mix(Color.Black, Color.White, 0.5f).toHex())
        assertEquals(1f, mix(Color.Black, Color.White, 0.5f).alpha, 0f)
    }

    @Test
    fun `synthesised light palettes are derived from the preset's own ring`() {
        // context.tsx:84-118 — the accent seed is ring || primary, and every
        // synthesised value hangs off it. Two dark-first presets with different
        // rings must not produce the same light palette.
        val monoLight = synthLightColors(BuiltinThemes.Mono)
        val slateLight = synthLightColors(BuiltinThemes.Slate)

        assertEquals(BuiltinThemes.Mono.colors.ring, monoLight.primary)
        assertEquals(BuiltinThemes.Slate.colors.ring, slateLight.primary)
        assertTrue("synthesis collapsed two presets onto one palette", monoLight != slateLight)
        assertEquals(Color.White, monoLight.background)
        assertEquals(Color(0xFF161616), monoLight.foreground)
    }

    @Test
    fun `only nous has a hand-tuned light palette, the rest synthesise`() {
        // getBaseColors (context.tsx:120-129).
        assertEquals(BuiltinThemes.Nous.colors, BuiltinThemes.Nous.paletteFor(dark = false))
        assertEquals(BuiltinThemes.Nous.darkColors, BuiltinThemes.Nous.paletteFor(dark = true))

        assertEquals(BuiltinThemes.Ember.colors, BuiltinThemes.Ember.paletteFor(dark = true))
        assertEquals(synthLightColors(BuiltinThemes.Ember), BuiltinThemes.Ember.paletteFor(dark = false))
    }

    @Test
    fun `the hairline ladder descends in strength`() {
        val tokens = HermesTokens.from(BuiltinThemes.Slate.paletteFor(dark = true), dark = true)
        val surface = tokens.chatSurface
        val ratios = listOf(
            contrastRatio(tokens.strokePrimary.over(surface), surface),
            contrastRatio(tokens.strokeSecondary.over(surface), surface),
            contrastRatio(tokens.strokeTertiary.over(surface), surface),
            contrastRatio(tokens.strokeQuaternary.over(surface), surface),
        )
        assertTrue("hairlines must get quieter, not louder: $ratios", ratios.zipWithNext().all { it.first >= it.second })
    }

    @Test
    fun `the text ladder descends in strength`() {
        val tokens = HermesTokens.from(BuiltinThemes.Nous.paletteFor(dark = false), dark = false)
        val alphas = listOf(
            tokens.textPrimary.alpha,
            tokens.textSecondary.alpha,
            tokens.scaffoldText.alpha,
            tokens.textTertiary.alpha,
            tokens.scaffoldMeta.alpha,
            tokens.textQuaternary.alpha,
        )
        assertEquals(listOf(0.94f, 0.74f, 0.64f, 0.54f, 0.44f, 0.36f), alphas.map { "%.2f".format(it).toFloat() })
    }

    /** Composite a translucent colour over an opaque backdrop. */
    private fun Color.over(backdrop: Color): Color = Color(
        red = red * alpha + backdrop.red * (1 - alpha),
        green = green * alpha + backdrop.green * (1 - alpha),
        blue = blue * alpha + backdrop.blue * (1 - alpha),
    )
}
