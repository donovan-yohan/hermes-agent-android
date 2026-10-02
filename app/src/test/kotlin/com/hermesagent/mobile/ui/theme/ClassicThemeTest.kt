package com.hermesagent.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden values executed from Desktop skin.ts and shared/color.ts at
 * 36922ad064d65dcf25f8f48df81e1ccf9a55de67, using presets.ts Classic seeds.
 * Not captured pixels: these assert every converter output, including rounded mixes.
 */
class ClassicThemeTest {
    @Test fun `classic follows Nous Alt in the complete Desktop inventory`() {
        assertEquals(listOf("nous", "github", "catppuccin", "everforest", "solarized", "nous-alt",
            "classic", "midnight", "ember", "mono", "slate", "cyberpunk"), BuiltinThemes.ALL.map { it.name })
        val classic = BuiltinThemes.resolve("classic")
        assertEquals("Classic Hermes", classic.label)
        assertEquals("Gold on navy, the CLI's original look", classic.description)
    }
    @Test fun `classic light equals every Desktop converter output`() {
        val palette = BuiltinThemes.resolve("classic").paletteFor(false)
        assertEquals("background", "#f5f5f5", palette.background.toHex())
        assertEquals("foreground", "#2b2109", palette.foreground.toHex())
        assertEquals("card", "#f0f0ef", palette.card.toHex())
        assertEquals("cardForeground", "#2b2109", palette.cardForeground.toHex())
        assertEquals("muted", "#ededec", palette.muted.toHex())
        assertEquals("mutedForeground", "#b8860b", palette.mutedForeground.toHex())
        assertEquals("popover", "#ebeae9", palette.popover.toHex())
        assertEquals("popoverForeground", "#2b2109", palette.popoverForeground.toHex())
        assertEquals("primary", "#825d02", palette.primary.toHex())
        assertEquals("primaryForeground", "#ffffff", palette.primaryForeground.toHex())
        assertEquals("secondary", "#e5e0d3", palette.secondary.toHex())
        assertEquals("secondaryForeground", "#2b2109", palette.secondaryForeground.toHex())
        assertEquals("accent", "#e7e3d8", palette.accent.toHex())
        assertEquals("accentForeground", "#2b2109", palette.accentForeground.toHex())
        assertEquals("border", "#cd7f32", palette.border.toHex())
        assertEquals("input", "#f5f5f5", palette.input.toHex())
        assertEquals("ring", "#825d02", palette.ring.toHex())
        assertEquals("midground", "#825d02", palette.midground?.toHex())
        assertEquals("midgroundForeground", "#ffffff", palette.midgroundForeground?.toHex())
        assertEquals("composerRing", "#825d02", palette.composerRing?.toHex())
        assertEquals("destructive", "#c62828", palette.destructive.toHex())
        assertEquals("destructiveForeground", "#ffffff", palette.destructiveForeground.toHex())
        assertEquals("sidebarBackground", "#f3f2f2", palette.sidebarBackground?.toHex())
        assertEquals("sidebarBorder", "#cd7f32", palette.sidebarBorder?.toHex())
        assertEquals("userBubble", "#e7e3d8", palette.userBubble?.toHex())
        assertEquals("userBubbleBorder", "#cd7f32", palette.userBubbleBorder?.toHex())
    }
    @Test fun `classic dark equals every Desktop converter output`() {
        val palette = BuiltinThemes.resolve("classic").paletteFor(true)
        assertEquals("background", "#1a1a2e", palette.background.toHex())
        assertEquals("foreground", "#fff8dc", palette.foreground.toHex())
        assertEquals("card", "#232335", palette.card.toHex())
        assertEquals("cardForeground", "#fff8dc", palette.cardForeground.toHex())
        assertEquals("muted", "#282738", palette.muted.toHex())
        assertEquals("mutedForeground", "#b8860b", palette.mutedForeground.toHex())
        assertEquals("popover", "#2c2c3c", palette.popover.toHex())
        assertEquals("popoverForeground", "#fff8dc", palette.popoverForeground.toHex())
        assertEquals("primary", "#ffbf00", palette.primary.toHex())
        assertEquals("primaryForeground", "#161616", palette.primaryForeground.toHex())
        assertEquals("secondary", "#5a4821", palette.secondary.toHex())
        assertEquals("secondaryForeground", "#fff8dc", palette.secondaryForeground.toHex())
        assertEquals("accent", "#433826", palette.accent.toHex())
        assertEquals("accentForeground", "#fff8dc", palette.accentForeground.toHex())
        assertEquals("border", "#cd7f32", palette.border.toHex())
        assertEquals("input", "#1a1a2e", palette.input.toHex())
        assertEquals("ring", "#ffbf00", palette.ring.toHex())
        assertEquals("midground", "#ffbf00", palette.midground?.toHex())
        assertEquals("midgroundForeground", "#161616", palette.midgroundForeground?.toHex())
        assertEquals("composerRing", "#ffbf00", palette.composerRing?.toHex())
        assertEquals("destructive", "#ef5350", palette.destructive.toHex())
        assertEquals("destructiveForeground", "#161616", palette.destructiveForeground.toHex())
        assertEquals("sidebarBackground", "#1f1e31", palette.sidebarBackground?.toHex())
        assertEquals("sidebarBorder", "#cd7f32", palette.sidebarBorder?.toHex())
        assertEquals("userBubble", "#433826", palette.userBubble?.toHex())
        assertEquals("userBubbleBorder", "#cd7f32", palette.userBubbleBorder?.toHex())
    }
}
