package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Built-in Hermes themes, ported from Desktop's registry at
 * `apps/desktop/src/themes/presets.ts` (upstream `NousResearch/hermes-agent`
 * @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`).
 *
 * **Adding a theme is a data edit.** Append to [ALL] and nothing else changes:
 * the picker enumerates this list, components read semantic tokens, and
 * `ThemeParityTest` re-checks the required-token contract. If a new theme ever
 * needs a component to know its name, that is the bug — add a token.
 *
 * Per-preset provenance is on each `val` as `presets.ts:<lines>`. Colour
 * *expressions* (`color-mix`) are kept as expressions rather than pre-resolved
 * hex so a Desktop tweak to `NOUS_BLUE` is a one-line follow-up here too.
 */
object BuiltinThemes {

    // presets.ts:187,195-198 @ 45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8
    private val NousBlue = Color(0xFF0053FD)

    /** `presets.ts:174-277` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. */
    val Nous = HermesThemePreset(
        name = "nous",
        label = "Nous",
        description = "GitHub chrome, Nous blue accent",
        colors = HermesPalette(
            background = Color(0xFFFFFFFF),
            foreground = Color(0xFF1F2328),
            card = Color(0xFFF6F8FA),
            cardForeground = Color(0xFF1F2328),
            muted = Color(0xFFF6F6F6),
            mutedForeground = Color(0xFF656D76),
            popover = Color(0xFFFFFFFF),
            popoverForeground = Color(0xFF1F2328),
            primary = NousBlue,
            primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFFDEEAFF),
            secondaryForeground = Color(0xFF1F2328),
            accent = Color(0xFFE3EDFF),
            accentForeground = Color(0xFF1F2328),
            border = Color(0xFFD0D7DE),
            input = Color(0xFFFFFFFF),
            ring = NousBlue,
            destructive = Color(0xFFCF222E),
            destructiveForeground = Color(0xFFFFFFFF),
            midground = NousBlue,
            midgroundForeground = Color(0xFFFFFFFF),
            composerRing = NousBlue,
            sidebarBackground = Color(0xFFF6F8FA),
            sidebarBorder = Color(0xFFD0D7DE),
            userBubble = Color(0xFFDAE7FD),
            userBubbleBorder = Color(0xFFD0D7DE),
        ),
        darkColors = HermesPalette(
            background = Color(0xFF0D1117),
            foreground = Color(0xFFE6EDF3),
            card = Color(0xFF010409),
            cardForeground = Color(0xFFE6EDF3),
            muted = Color(0xFF1A1E24),
            mutedForeground = Color(0xFF7D8590),
            popover = Color(0xFF161B22),
            popoverForeground = Color(0xFFE6EDF3),
            primary = Color(0xFF4A84FE),
            primaryForeground = Color(0xFF161616),
            secondary = Color(0xFF1D2E4F),
            secondaryForeground = Color(0xFFE6EDF3),
            accent = Color(0xFF17243A),
            accentForeground = Color(0xFFE6EDF3),
            border = Color(0xFF30363D),
            input = Color(0xFF0D1117),
            ring = Color(0xFF4A84FE),
            destructive = Color(0xFFF85149),
            destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFF4A84FE),
            midgroundForeground = Color(0xFF161616),
            composerRing = Color(0xFF4A84FE),
            sidebarBackground = Color(0xFF010409),
            sidebarBorder = Color(0xFF30363D),
            userBubble = Color(0xFF07162C),
            userBubbleBorder = Color(0xFF30363D),
        ),
        // presets.ts:234-237 asks for Courier Prime over a Google Fonts URL for
        // mono; Android substitutes the platform monospace (no runtime fetch).
        fonts = HermesFontChoice(),
    )

    /** `presets.ts:56-159` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. */
    val Github = HermesThemePreset(
        name = "github",
        label = "GitHub",
        description = "GitHub Light Default and Dark Default",
        colors = HermesPalette(
            background = Color(0xFFFFFFFF), foreground = Color(0xFF1F2328),
            card = Color(0xFFF6F8FA), cardForeground = Color(0xFF1F2328),
            muted = Color(0xFFF6F6F6), mutedForeground = Color(0xFF656D76),
            popover = Color(0xFFFFFFFF), popoverForeground = Color(0xFF1F2328),
            primary = Color(0xFF196D31), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFFDFEBE2), secondaryForeground = Color(0xFF1F2328),
            accent = Color(0xFFE3EDE6), accentForeground = Color(0xFF1F2328),
            border = Color(0xFFD0D7DE), input = Color(0xFFFFFFFF), ring = Color(0xFF196D31),
            destructive = Color(0xFFCF222E), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFF196D31), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFF196D31), sidebarBackground = Color(0xFFF6F8FA),
            sidebarBorder = Color(0xFFD0D7DE), userBubble = Color(0xFFDBE7E2),
            userBubbleBorder = Color(0xFFD0D7DE),
        ),
        darkColors = HermesPalette(
            background = Color(0xFF0D1117), foreground = Color(0xFFE6EDF3),
            card = Color(0xFF010409), cardForeground = Color(0xFFE6EDF3),
            muted = Color(0xFF1A1E24), mutedForeground = Color(0xFF7D8590),
            popover = Color(0xFF161B22), popoverForeground = Color(0xFFE6EDF3),
            primary = Color(0xFF4F9E5E), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFF1F382B), secondaryForeground = Color(0xFFE6EDF3),
            accent = Color(0xFF192A24), accentForeground = Color(0xFFE6EDF3),
            border = Color(0xFF30363D), input = Color(0xFF0D1117), ring = Color(0xFF4F9E5E),
            destructive = Color(0xFFF85149), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFF4F9E5E), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFF4F9E5E), sidebarBackground = Color(0xFF010409),
            sidebarBorder = Color(0xFF30363D), userBubble = Color(0xFF0F2018),
            userBubbleBorder = Color(0xFF30363D),
        ),
        // `presets.ts:116-119` requests Courier Prime for mono; Android uses platform mono.
        fonts = HermesFontChoice(),
    )

    /** `presets.ts:280-382` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. */
    val Catppuccin = HermesThemePreset(
        name = "catppuccin",
        label = "Catppuccin",
        description = "Soothing pastels — Latte and Mocha",
        colors = HermesPalette(
            background = Color(0xFFEFF1F5), foreground = Color(0xFF4C4F69),
            card = Color(0xFFE6E9EF), cardForeground = Color(0xFF4C4F69),
            muted = Color(0xFFE8EBEF), mutedForeground = Color(0xFF4C4F69),
            popover = Color(0xFFE6E9EF), popoverForeground = Color(0xFF4C4F69),
            primary = Color(0xFF6D2EBF), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFFDDD6ED), secondaryForeground = Color(0xFF4C4F69),
            accent = Color(0xFFDFDAEF), accentForeground = Color(0xFF4C4F69),
            border = Color(0xFFACB0BE), input = Color(0xFFCCD0DA), ring = Color(0xFF6D2EBF),
            destructive = Color(0xFFD20F39), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFF6D2EBF), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFF6D2EBF), sidebarBackground = Color(0xFFE6E9EF),
            sidebarBorder = Color(0xFFACB0BE), userBubble = Color(0xFFD7D3E9),
            userBubbleBorder = Color(0xFFACB0BE),
        ),
        darkColors = HermesPalette(
            background = Color(0xFF1E1E2E), foreground = Color(0xFFCDD6F4),
            card = Color(0xFF181825), cardForeground = Color(0xFFCDD6F4),
            muted = Color(0xFF29293A), mutedForeground = Color(0xFFCDD6F4),
            popover = Color(0xFF181825), popoverForeground = Color(0xFFCDD6F4),
            primary = Color(0xFFCBA6F7), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFF4E4466), secondaryForeground = Color(0xFFCDD6F4),
            accent = Color(0xFF3D3652), accentForeground = Color(0xFFCDD6F4),
            border = Color(0xFF585B70), input = Color(0xFF313244), ring = Color(0xFFCBA6F7),
            destructive = Color(0xFFF38BA8), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFFCBA6F7), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFFCBA6F7), sidebarBackground = Color(0xFF181825),
            sidebarBorder = Color(0xFF585B70), userBubble = Color(0xFF38324B),
            userBubbleBorder = Color(0xFF585B70),
        ),
    )

    /** `presets.ts:385-485` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. */
    val Everforest = HermesThemePreset(
        name = "everforest",
        label = "Everforest",
        description = "Warm, low-contrast forest greens",
        colors = HermesPalette(
            background = Color(0xFFFDF6E3), foreground = Color(0xFF5C6A72),
            card = Color(0xFFFDF6E3), cardForeground = Color(0xFF5C6A72),
            muted = Color(0xFFF7F0DE), mutedForeground = Color(0xFF939F91),
            popover = Color(0xFFFDF6E3), popoverForeground = Color(0xFF5C6A72),
            primary = Color(0xFF586B35), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFFE6E3CB), secondaryForeground = Color(0xFF5C6A72),
            accent = Color(0xFFE9E5CE), accentForeground = Color(0xFF5C6A72),
            border = Color(0xFFFDF6E3), input = Color(0xFFFDF6E3), ring = Color(0xFF586B35),
            destructive = Color(0xFFF1706F), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFF586B35), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFF586B35), sidebarBackground = Color(0xFFFDF6E3),
            sidebarBorder = Color(0xFFFDF6E3), userBubble = Color(0xFFE9E5CE),
            userBubbleBorder = Color(0xFFFDF6E3),
        ),
        darkColors = HermesPalette(
            background = Color(0xFF2D353B), foreground = Color(0xFFD3C6AA),
            card = Color(0xFF2D353B), cardForeground = Color(0xFFD3C6AA),
            muted = Color(0xFF373E42), mutedForeground = Color(0xFF859289),
            popover = Color(0xFF2D353B), popoverForeground = Color(0xFFD3C6AA),
            primary = Color(0xFFA7C080), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFF4F5C4E), secondaryForeground = Color(0xFFD3C6AA),
            accent = Color(0xFF434E47), accentForeground = Color(0xFFD3C6AA),
            border = Color(0xFF2D353B), input = Color(0xFF2D353B), ring = Color(0xFFA7C080),
            destructive = Color(0xFFDA6362), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFFA7C080), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFFA7C080), sidebarBackground = Color(0xFF2D353B),
            sidebarBorder = Color(0xFF2D353B), userBubble = Color(0xFF434E47),
            userBubbleBorder = Color(0xFF2D353B),
        ),
    )

    /** `presets.ts:488-588` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. */
    val Solarized = HermesThemePreset(
        name = "solarized",
        label = "Solarized",
        description = "Fixed-contrast light and dark",
        colors = HermesPalette(
            background = Color(0xFFFDF6E3), foreground = Color(0xFF1F1F1F),
            card = Color(0xFFD3CBB7), cardForeground = Color(0xFF1F1F1F),
            muted = Color(0xFFF4EDDB), mutedForeground = Color(0xFF9CA8A6),
            popover = Color(0xFFEEE8D5), popoverForeground = Color(0xFF1F1F1F),
            primary = Color(0xFF675E34), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFFE8E1CB), secondaryForeground = Color(0xFF1F1F1F),
            accent = Color(0xFFEBE4CE), accentForeground = Color(0xFF1F1F1F),
            border = Color(0xFFDDD6C1), input = Color(0xFFDDD6C1), ring = Color(0xFF675E34),
            destructive = Color(0xFFE25563), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFF675E34), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFF675E34), sidebarBackground = Color(0xFFEEE8D5),
            sidebarBorder = Color(0xFFDDD6C1), userBubble = Color(0xFFC6BEA7),
            userBubbleBorder = Color(0xFFDDD6C1),
        ),
        darkColors = HermesPalette(
            background = Color(0xFF002B36), foreground = Color(0xFF839496),
            card = Color(0xFF002B36), cardForeground = Color(0xFF839496),
            muted = Color(0xFF08313C), mutedForeground = Color(0xFF586E75),
            popover = Color(0xFF001F26), popoverForeground = Color(0xFF839496),
            primary = Color(0xFF6EA1C4), primaryForeground = Color(0xFFFFFFFF),
            secondary = Color(0xFF1F4C5E), secondaryForeground = Color(0xFF839496),
            accent = Color(0xFF144050), accentForeground = Color(0xFF839496),
            border = Color(0xFF234751), input = Color(0xFF073642), ring = Color(0xFF6EA1C4),
            destructive = Color(0xFFE35957), destructiveForeground = Color(0xFFFFFFFF),
            midground = Color(0xFF6EA1C4), midgroundForeground = Color(0xFFFFFFFF),
            composerRing = Color(0xFF6EA1C4), sidebarBackground = Color(0xFF001F26),
            sidebarBorder = Color(0xFF234751), userBubble = Color(0xFF144050),
            userBubbleBorder = Color(0xFF234751),
        ),
    )

    /** `presets.ts:601-664` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. */
    val NousAlt = HermesThemePreset(
        name = "nous-alt",
        label = "Nous Alt",
        description = "Glass neutrals, cream on mission-blue",
        colors = HermesPalette(
            background = Color(0xFFF8FAFF), foreground = Color(0xFF17171A),
            card = Color(0xFFFFFFFF), cardForeground = Color(0xFF17171A),
            muted = mixPremultiplied(NousBlue, 5f, Color.White), mutedForeground = Color(0xFF666678),
            popover = Color(0xFFFFFFFF), popoverForeground = Color(0xFF17171A),
            primary = NousBlue, primaryForeground = Color(0xFFFCFCFC),
            secondary = mixPremultiplied(NousBlue, 7f, Color.White), secondaryForeground = Color(0xFF242432),
            accent = mixPremultiplied(NousBlue, 10f, Color.White), accentForeground = Color(0xFF202030),
            border = mixPremultiplied(Color(0xFF0053FD), 22f, Color.Transparent),
            input = mixPremultiplied(Color(0xFF0053FD), 30f, Color.Transparent), ring = NousBlue,
            destructive = Color(0xFFC72E4D), destructiveForeground = Color(0xFFFFFFFF),
            midground = NousBlue, composerRing = NousBlue, sidebarBackground = Color(0xFFF3F7FF),
            sidebarBorder = mixPremultiplied(Color(0xFF0053FD), 18f, Color.Transparent),
            userBubble = mixPremultiplied(NousBlue, 6f, Color.White),
            userBubbleBorder = mixPremultiplied(Color(0xFF0053FD), 24f, Color.Transparent),
        ),
        darkColors = HermesPalette(
            background = Color(0xFF0D2F86), foreground = Color(0xFFFFE6CB),
            card = Color(0xFF12378F), cardForeground = Color(0xFFFFE6CB),
            muted = Color(0xFF183F9A), mutedForeground = Color(0xFFB5C7F3),
            popover = Color(0xFF123A96), popoverForeground = Color(0xFFFFE6CB),
            primary = Color(0xFFFFE6CB), primaryForeground = Color(0xFF0D2F86),
            secondary = Color(0xFF1B45A4), secondaryForeground = Color(0xFFE0E8FF),
            accent = Color(0xFF1540B1), accentForeground = Color(0xFFF0F4FF),
            border = Color(0xFF3158AD), input = Color(0xFF0B2566), ring = Color(0xFFFFE6CB),
            destructive = Color(0xFFC0473A), destructiveForeground = Color(0xFFFEF2F2),
            midground = NousBlue, composerRing = Color(0xFFFFE6CB), sidebarBackground = Color(0xFF09286F),
            sidebarBorder = Color(0xFF234A9C), userBubble = Color(0xFF143B91),
            userBubbleBorder = Color(0xFF3A63BD),
        ),
        // `presets.ts:659-662` requests Courier Prime for mono; Android uses platform mono.
        fonts = HermesFontChoice(),
    )

    /** `presets.ts:670-704` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. Dark-first. */
    val Midnight = HermesThemePreset(
        name = "midnight",
        label = "Midnight",
        description = "Deep blue-violet with cool accents",
        colors = HermesPalette(
            background = Color(0xFF08081C),
            foreground = Color(0xFFDDD6FF),
            card = Color(0xFF0D0D28),
            cardForeground = Color(0xFFDDD6FF),
            muted = Color(0xFF13133A),
            mutedForeground = Color(0xFF7C7AB0),
            popover = Color(0xFF0F0F2E),
            popoverForeground = Color(0xFFDDD6FF),
            primary = Color(0xFFDDD6FF),
            primaryForeground = Color(0xFF08081C),
            secondary = Color(0xFF1A1A4A),
            secondaryForeground = Color(0xFFC4BFF0),
            accent = Color(0xFF1A1A44),
            accentForeground = Color(0xFFD0C8FF),
            border = Color(0xFF1E1E52),
            input = Color(0xFF1E1E52),
            ring = Color(0xFF8B80E8),
            destructive = Color(0xFFB03060),
            destructiveForeground = Color(0xFFFEF2F2),
            midground = Color(0xFF8B80E8),
            sidebarBackground = Color(0xFF06061A),
            sidebarBorder = Color(0xFF12123A),
            userBubble = Color(0xFF14143A),
            userBubbleBorder = Color(0xFF242466),
        ),
        // presets.ts:130-133 — "JetBrains Mono" via Google Fonts; substituted.
        fonts = HermesFontChoice(),
    )

    /** `presets.ts:706-740` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. Dark-first. */
    val Ember = HermesThemePreset(
        name = "ember",
        label = "Ember",
        description = "Warm crimson and bronze — forge vibes",
        colors = HermesPalette(
            background = Color(0xFF160800),
            foreground = Color(0xFFFFD8B0),
            card = Color(0xFF1E0E04),
            cardForeground = Color(0xFFFFD8B0),
            muted = Color(0xFF2A1408),
            mutedForeground = Color(0xFFAA7A56),
            popover = Color(0xFF221008),
            popoverForeground = Color(0xFFFFD8B0),
            primary = Color(0xFFFFD8B0),
            primaryForeground = Color(0xFF160800),
            secondary = Color(0xFF341800),
            secondaryForeground = Color(0xFFF0C090),
            accent = Color(0xFF301600),
            accentForeground = Color(0xFFE8C080),
            border = Color(0xFF3A1C08),
            input = Color(0xFF3A1C08),
            ring = Color(0xFFD97316),
            destructive = Color(0xFFC43010),
            destructiveForeground = Color(0xFFFEF2F2),
            midground = Color(0xFFD97316),
            sidebarBackground = Color(0xFF100600),
            sidebarBorder = Color(0xFF2A1004),
            userBubble = Color(0xFF2A1000),
            userBubbleBorder = Color(0xFF4A2010),
        ),
        // presets.ts:167-170 — "IBM Plex Mono" via Google Fonts; substituted.
        fonts = HermesFontChoice(),
    )

    /** `presets.ts:743-773` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. Dark-first. */
    val Mono = HermesThemePreset(
        name = "mono",
        label = "Mono",
        description = "Clean grayscale — minimal and focused",
        colors = HermesPalette(
            background = Color(0xFF0E0E0E),
            foreground = Color(0xFFEAEAEA),
            card = Color(0xFF141414),
            cardForeground = Color(0xFFEAEAEA),
            muted = Color(0xFF1E1E1E),
            mutedForeground = Color(0xFF808080),
            popover = Color(0xFF181818),
            popoverForeground = Color(0xFFEAEAEA),
            primary = Color(0xFFEAEAEA),
            primaryForeground = Color(0xFF0E0E0E),
            secondary = Color(0xFF262626),
            secondaryForeground = Color(0xFFC8C8C8),
            accent = Color(0xFF222222),
            accentForeground = Color(0xFFD8D8D8),
            border = Color(0xFF2A2A2A),
            input = Color(0xFF2A2A2A),
            ring = Color(0xFF9A9A9A),
            destructive = Color(0xFFA84040),
            destructiveForeground = Color(0xFFFEF2F2),
            midground = Color(0xFF9A9A9A),
            sidebarBackground = Color(0xFF0A0A0A),
            sidebarBorder = Color(0xFF202020),
            userBubble = Color(0xFF1A1A1A),
            userBubbleBorder = Color(0xFF363636),
        ),
    )

    /**
     * `presets.ts:776-810` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. Dark-first. The one preset whose typography changes
     * the whole UI: upstream sets `fontSans` *and* `fontMono` to Courier, so
     * body text goes monospace. That behaviour survives the substitution.
     */
    val Cyberpunk = HermesThemePreset(
        name = "cyberpunk",
        label = "Cyberpunk",
        description = "Neon green on black — matrix terminal",
        colors = HermesPalette(
            background = Color(0xFF000A00),
            foreground = Color(0xFF00FF41),
            card = Color(0xFF001200),
            cardForeground = Color(0xFF00FF41),
            muted = Color(0xFF001A00),
            mutedForeground = Color(0xFF1A8A30),
            popover = Color(0xFF001000),
            popoverForeground = Color(0xFF00FF41),
            primary = Color(0xFF00FF41),
            primaryForeground = Color(0xFF000A00),
            secondary = Color(0xFF002800),
            secondaryForeground = Color(0xFF00CC34),
            accent = Color(0xFF002000),
            accentForeground = Color(0xFF00E038),
            border = Color(0xFF003000),
            input = Color(0xFF003000),
            ring = Color(0xFF00FF41),
            destructive = Color(0xFFFF003C),
            destructiveForeground = Color(0xFF000A00),
            midground = Color(0xFF00FF41),
            sidebarBackground = Color(0xFF000600),
            sidebarBorder = Color(0xFF001800),
            userBubble = Color(0xFF001400),
            userBubbleBorder = Color(0xFF004800),
        ),
        fonts = HermesFontChoice(sans = HermesFontFamily.Mono, mono = HermesFontFamily.Mono),
    )

    /** `presets.ts:813-846` @ `45fcaaa54aae2d03ab816fb61c6ba312d3ac67b8`. Dark-first. */
    val Slate = HermesThemePreset(
        name = "slate",
        label = "Slate",
        description = "Cool slate blue — focused developer theme",
        colors = HermesPalette(
            background = Color(0xFF0D1117),
            foreground = Color(0xFFC9D1D9),
            card = Color(0xFF161B22),
            cardForeground = Color(0xFFC9D1D9),
            muted = Color(0xFF21262D),
            mutedForeground = Color(0xFF8B949E),
            popover = Color(0xFF1C2128),
            popoverForeground = Color(0xFFC9D1D9),
            primary = Color(0xFFC9D1D9),
            primaryForeground = Color(0xFF0D1117),
            secondary = Color(0xFF2A3038),
            secondaryForeground = Color(0xFFADB5BF),
            accent = Color(0xFF1E2530),
            accentForeground = Color(0xFFC0C8D0),
            border = Color(0xFF30363D),
            input = Color(0xFF30363D),
            ring = Color(0xFF58A6FF),
            destructive = Color(0xFFCF4848),
            destructiveForeground = Color(0xFFFEF2F2),
            midground = Color(0xFF58A6FF),
            sidebarBackground = Color(0xFF090D13),
            sidebarBorder = Color(0xFF1C2228),
            userBubble = Color(0xFF1E2A38),
            userBubbleBorder = Color(0xFF2E4060),
        ),
        // presets.ts:274-276 — "JetBrains Mono" without a font URL; substituted.
        fonts = HermesFontChoice(),
    )

    /** Registry order matches `BUILTIN_THEMES` at `presets.ts:848-860`. */
    val ALL: List<HermesThemePreset> = listOf(
        Nous, Github, Catppuccin, Everforest, Solarized, NousAlt,
        Midnight, Ember, Mono, Slate, Cyberpunk,
    )

    /** `presets.ts:864-865` — the skin used when nothing is persisted. */
    const val DEFAULT_NAME: String = "nous"

    private val byName: Map<String, HermesThemePreset> = ALL.associateBy { it.name }

    /** Unknown or retired names fall back to the default, as Desktop does. */
    fun resolve(name: String?): HermesThemePreset = byName[name] ?: Nous
}
