package com.hermesagent.mobile.ui.theme

/**
 * The Desktop theme registry as it stands at the pinned upstream SHA.
 *
 * **Provenance:** `NousResearch/hermes-agent` @
 * `36922ad064d65dcf25f8f48df81e1ccf9a55de67`. Identity, registry and typography
 * come from `apps/desktop/src/themes/presets.ts`; the palette literals moved to
 * `apps/shared/src/theme-presets.ts` (upstream's September 2026 shared-package
 * extraction). Classic is the exception: presets.ts:342-384 converts explicit
 * light/dark skin seeds through skin.ts, not a shared palette. Re-verified
 * 2026-10-02 from a disposable checkout. The other eleven palettes, converter
 * and shared color maths are byte-identical to e05b16348b1d06a3311237423b0a4fc30d9c5aa1.
 * Entry sourceLines below identify this target's preset declarations; historical
 * production palette citations and visual reports retain their inspected pins.
 * This ledger certifies registry identity, not fresh rendered parity.
 *
 * This exists so the parity test is **offline and deterministic**: CI has no
 * upstream checkout, and a test that silently skips when a path is missing is
 * not a gate. The live diff against a real checkout is
 * `.chalk/skills/sync-hermes-desktop-themes/scripts/check-theme-parity.py`, which the `sync-hermes-desktop-themes`
 * skill drives; the two are complementary, not redundant — this one catches
 * "someone deleted a preset from Android", the script catches "Desktop moved
 * and nobody noticed".
 *
 * Update this file **only** as part of a deliberate theme sync, and record the
 * new SHA above when you do.
 */
object DesktopThemeLedger {

    const val PINNED_SHA = "36922ad064d65dcf25f8f48df81e1ccf9a55de67"
    const val SOURCE_PATH = "apps/desktop/src/themes/presets.ts"

    /** Where the palettes live since upstream's shared-package extraction. */
    const val PALETTE_SOURCE_PATH = "apps/shared/src/theme-presets.ts"

    /** `presets.ts:462` — `DEFAULT_SKIN_NAME`. */
    const val DEFAULT_SKIN = "nous"

    data class Entry(
        val name: String,
        val label: String,
        val description: String,
        /** True when the preset ships a hand-tuned `darkColors` block. */
        val hasHandTunedDark: Boolean,
        /** presets.ts line range of the preset literal. */
        val sourceLines: String,
    )

    /** `presets.ts:444-456` — `BUILTIN_THEMES`, in declaration order. */
    val ENTRIES: List<Entry> = listOf(
        Entry("nous", "Nous", "GitHub chrome, Nous blue accent", true, "130-178"),
        Entry("github", "GitHub", "GitHub Light Default and Dark Default", true, "67-115"),
        Entry("catppuccin", "Catppuccin", "Soothing pastels — Latte and Mocha", true, "181-228"),
        Entry("everforest", "Everforest", "Warm, low-contrast forest greens", true, "231-276"),
        Entry("solarized", "Solarized", "Fixed-contrast light and dark", true, "279-324"),
        Entry("nous-alt", "Nous Alt", "Glass neutrals, cream on mission-blue", true, "330-340"),
        Entry("classic", "Classic Hermes", "Gold on navy, the CLI's original look", true, "379-385"),
        Entry("midnight", "Midnight", "Deep blue-violet with cool accents", false, "391-400"),
        Entry("ember", "Ember", "Warm crimson and bronze — forge vibes", false, "402-411"),
        Entry("mono", "Mono", "Clean grayscale — minimal and focused", false, "414-419"),
        Entry("slate", "Slate", "Cool slate blue — focused developer theme", false, "434-442"),
        Entry("cyberpunk", "Cyberpunk", "Neon green on black — matrix terminal", false, "422-431"),
    )

    /**
     * The required half of `DesktopThemeColors`
     * (`apps/desktop/src/themes/types.ts:13-48`) — every field without a `?`.
     * Android models each of these as a non-null [HermesPalette] field, so
     * "required" is enforced by the type system and re-asserted here in case
     * someone makes one nullable.
     */
    val REQUIRED_COLOR_KEYS: List<String> = listOf(
        "background", "foreground", "card", "cardForeground",
        "muted", "mutedForeground", "popover", "popoverForeground",
        "primary", "primaryForeground", "secondary", "secondaryForeground",
        "accent", "accentForeground", "border", "input", "ring",
        "destructive", "destructiveForeground",
    )

    /** The optional half, all of which Android resolves rather than drops. */
    val OPTIONAL_COLOR_KEYS: List<String> = listOf(
        "midground", "midgroundForeground", "composerRing",
        "sidebarBackground", "sidebarBorder", "userBubble", "userBubbleBorder",
    )
}
