package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Android counterpart of Desktop's `DesktopThemeColors`
 * (`apps/desktop/src/themes/types.ts:13-48` @ `936b970e`).
 *
 * Field names and optionality match the Desktop interface one for one, so a
 * theme sync is a mechanical diff rather than a reinterpretation. Optional
 * fields keep Desktop's documented fallbacks, resolved in [HermesTokens] — the
 * fallbacks are behaviour, not defaults, so they live in exactly one place.
 *
 * Deliberately absent: `terminal` / `darkTerminal` (Desktop's xterm ANSI
 * palette). Android has no terminal surface in this slice, and inventing one
 * would be theme surface with no consumer.
 */
data class HermesPalette(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val cardForeground: Color,
    val muted: Color,
    val mutedForeground: Color,
    val popover: Color,
    val popoverForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    val border: Color,
    val input: Color,
    /** Generic focus ring — buttons, inputs, etc. */
    val ring: Color,
    val destructive: Color,
    val destructiveForeground: Color,
    /**
     * Brand-accent stroke — focus rings, streaming cursors, active session
     * pills. Falls back to [ring].
     */
    val midground: Color? = null,
    /** Auto-derived for readable contrast on [midground] when omitted. */
    val midgroundForeground: Color? = null,
    /** Composer outline / focus colour. Falls back to [midground]. */
    val composerRing: Color? = null,
    val sidebarBackground: Color? = null,
    val sidebarBorder: Color? = null,
    val userBubble: Color? = null,
    val userBubbleBorder: Color? = null,
)

/** Font families a preset asks for. Resolved to platform families in [HermesTypography]. */
data class HermesFontChoice(
    val sans: HermesFontFamily = HermesFontFamily.Sans,
    val mono: HermesFontFamily = HermesFontFamily.Mono,
)

/**
 * Which platform family a preset wants. Desktop names concrete web fonts
 * (JetBrains Mono, IBM Plex Mono, Courier Prime) and loads them from Google
 * Fonts at runtime; Android does not fetch fonts at runtime and this repo
 * bundles none, so every named mono collapses to the platform monospace.
 * `cyberpunk` is the one preset where the substitution is load-bearing: it
 * sets *both* sans and mono to Courier, so the whole UI goes monospace, and
 * that behaviour is preserved.
 */
enum class HermesFontFamily { Sans, Mono }

/**
 * One built-in theme. Mirrors Desktop's `DesktopTheme`
 * (`apps/desktop/src/themes/types.ts:88-101`).
 *
 * @param darkColors hand-tuned dark palette. Presets that ship one (only
 *   `nous` upstream) use [colors] for light; the rest are dark-first and get a
 *   light variant from [synthLightColors], exactly as Desktop does.
 */
data class HermesThemePreset(
    val name: String,
    val label: String,
    val description: String,
    val colors: HermesPalette,
    val darkColors: HermesPalette? = null,
    val fonts: HermesFontChoice = HermesFontChoice(),
)

/**
 * Desktop's `synthLightColors` (`apps/desktop/src/themes/context.tsx:84-118`),
 * ported line for line. Dark-first presets get their light mode from here, so
 * "light mono" on Android is the same colour arithmetic as "light mono" on
 * Desktop rather than a second hand-tuned palette that would drift.
 */
fun synthLightColors(seed: HermesThemePreset): HermesPalette {
    val white = Color(0xFFFFFFFF)
    val accent = seed.colors.ring
    val soft = mix(white, accent, 0.1f)
    val softer = mix(white, accent, 0.06f)
    val border = mix(Color(0xFFECECEF), accent, 0.14f)
    val midground = seed.colors.midground ?: accent

    return HermesPalette(
        background = white,
        foreground = Color(0xFF161616),
        card = white,
        cardForeground = Color(0xFF161616),
        muted = softer,
        mutedForeground = mix(Color(0xFF6B6B70), accent, 0.16f),
        popover = white,
        popoverForeground = Color(0xFF161616),
        primary = accent,
        primaryForeground = readableOn(accent),
        secondary = soft,
        secondaryForeground = mix(Color(0xFF2A2A2A), accent, 0.34f),
        accent = soft,
        accentForeground = mix(Color(0xFF2A2A2A), accent, 0.34f),
        border = border,
        input = mix(Color(0xFFE2E2E6), accent, 0.18f),
        ring = accent,
        destructive = Color(0xFFB94A3A),
        destructiveForeground = white,
        midground = midground,
        midgroundForeground = readableOn(midground),
        sidebarBackground = mix(Color(0xFFFAFAFA), accent, 0.05f),
        sidebarBorder = border,
        userBubble = soft,
        userBubbleBorder = border,
    )
}

/**
 * Desktop's `getBaseColors` (`apps/desktop/src/themes/context.tsx:120-129`):
 * dark falls back to the single palette, light is synthesised only when the
 * preset has no hand-tuned dark half.
 */
fun HermesThemePreset.paletteFor(dark: Boolean): HermesPalette =
    if (dark) darkColors ?: colors else if (darkColors != null) colors else synthLightColors(this)
