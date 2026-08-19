package com.hermesagent.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Built-in Hermes themes, ported from Desktop's registry at
 * `apps/desktop/src/themes/presets.ts` (upstream `NousResearch/hermes-agent`
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`).
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

    // presets.ts:22-24
    private val NousBlue = Color(0xFF0053FD)
    private val PsycheBlue = Color(0xFF1540B1)
    private val PsycheWarm = Color(0xFFFFE6CB)

    // presets.ts:26-27
    private fun nousTint(pct: Float) = mixPremultiplied(NousBlue, pct, Color.White)
    private fun nousTintTransparent(pct: Float) = mixPremultiplied(NousBlue, pct, Color.Transparent)

    /**
     * presets.ts:34-97. The one preset upstream ships with a hand-tuned dark
     * half, so both halves are transcribed rather than synthesised.
     */
    val Nous = HermesThemePreset(
        name = "nous",
        label = "Nous",
        description = "Glass neutrals with Nous blue accents",
        colors = HermesPalette(
            background = Color(0xFFF8FAFF),
            foreground = Color(0xFF17171A),
            card = Color(0xFFFFFFFF),
            cardForeground = Color(0xFF17171A),
            muted = nousTint(5f),
            mutedForeground = Color(0xFF666678),
            popover = Color(0xFFFFFFFF),
            popoverForeground = Color(0xFF17171A),
            primary = NousBlue,
            primaryForeground = Color(0xFFFCFCFC),
            secondary = nousTint(7f),
            secondaryForeground = Color(0xFF242432),
            accent = nousTint(10f),
            accentForeground = Color(0xFF202030),
            border = nousTintTransparent(22f),
            input = nousTintTransparent(30f),
            ring = NousBlue,
            destructive = Color(0xFFC72E4D),
            destructiveForeground = Color(0xFFFFFFFF),
            midground = NousBlue,
            composerRing = NousBlue,
            sidebarBackground = Color(0xFFF3F7FF),
            sidebarBorder = nousTintTransparent(18f),
            userBubble = nousTint(6f),
            userBubbleBorder = nousTintTransparent(24f),
        ),
        darkColors = HermesPalette(
            background = Color(0xFF0D2F86),
            foreground = PsycheWarm,
            card = Color(0xFF12378F),
            cardForeground = PsycheWarm,
            muted = Color(0xFF183F9A),
            mutedForeground = Color(0xFFB5C7F3),
            popover = Color(0xFF123A96),
            popoverForeground = PsycheWarm,
            primary = PsycheWarm,
            primaryForeground = Color(0xFF0D2F86),
            secondary = Color(0xFF1B45A4),
            secondaryForeground = Color(0xFFE0E8FF),
            accent = PsycheBlue,
            accentForeground = Color(0xFFF0F4FF),
            border = Color(0xFF3158AD),
            input = Color(0xFF0B2566),
            ring = PsycheWarm,
            destructive = Color(0xFFC0473A),
            destructiveForeground = Color(0xFFFEF2F2),
            midground = NousBlue,
            composerRing = PsycheWarm,
            sidebarBackground = Color(0xFF09286F),
            sidebarBorder = Color(0xFF234A9C),
            userBubble = Color(0xFF143B91),
            userBubbleBorder = Color(0xFF3A63BD),
        ),
        // presets.ts:92-96 asks for Courier Prime over a Google Fonts URL for
        // mono; Android substitutes the platform monospace (no runtime fetch).
        fonts = HermesFontChoice(),
    )

    /** presets.ts:100-134. Dark-first; light comes from [synthLightColors]. */
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

    /** presets.ts:137-171. Dark-first. */
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

    /** presets.ts:174-204. Dark-first, no typography block upstream. */
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
     * presets.ts:207-241. Dark-first. The one preset whose typography changes
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

    /** presets.ts:244-277. Dark-first. */
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

    /** Registry order matches `BUILTIN_THEMES` at presets.ts:279-286. */
    val ALL: List<HermesThemePreset> = listOf(Nous, Midnight, Ember, Mono, Cyberpunk, Slate)

    /** presets.ts:291 — the skin used when nothing is persisted. */
    const val DEFAULT_NAME: String = "nous"

    private val byName: Map<String, HermesThemePreset> = ALL.associateBy { it.name }

    /** Unknown or retired names fall back to the default, as Desktop does. */
    fun resolve(name: String?): HermesThemePreset = byName[name] ?: Nous
}
