package com.hermesagent.mobile.ui.theme

/**
 * The Desktop theme registry as it stands at the pinned upstream SHA.
 *
 * **Provenance:** `NousResearch/hermes-agent` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`. Identity, registry and typography
 * come from `apps/desktop/src/themes/presets.ts`; the palette literals moved to
 * `apps/shared/src/theme-presets.ts` (upstream's September 2026 shared-package
 * extraction), so `hasHandTunedDark` is read from there. Transcribed
 * 2026-08-31 and re-verified 2026-09-15 from the read-only checkout at
 * `~/.hermes/hermes-agent`.
 *
 * This exists so the parity test is **offline and deterministic**: CI has no
 * upstream checkout, and a test that silently skips when a path is missing is
 * not a gate. The live diff against a real checkout is
 * `scripts/check-theme-parity.py`, which the `sync-hermes-desktop-themes`
 * skill drives; the two are complementary, not redundant — this one catches
 * "someone deleted a preset from Android", the script catches "Desktop moved
 * and nobody noticed".
 *
 * Update this file **only** as part of a deliberate theme sync, and record the
 * new SHA above when you do.
 */
object DesktopThemeLedger {

    const val PINNED_SHA = "437116f9497c80d242ce034ff7f5d81dc277a337"
    const val SOURCE_PATH = "apps/desktop/src/themes/presets.ts"

    /** Where the palettes live since upstream's shared-package extraction. */
    const val PALETTE_SOURCE_PATH = "apps/shared/src/theme-presets.ts"

    /** `presets.ts:407` — `DEFAULT_SKIN_NAME`. */
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

    /** `presets.ts:390-402` — `BUILTIN_THEMES`, in declaration order. */
    val ENTRIES: List<Entry> = listOf(
        Entry("nous", "Nous", "GitHub chrome, Nous blue accent", true, "121-169"),
        Entry("github", "GitHub", "GitHub Light Default and Dark Default", true, "58-106"),
        Entry("catppuccin", "Catppuccin", "Soothing pastels — Latte and Mocha", true, "172-219"),
        Entry("everforest", "Everforest", "Warm, low-contrast forest greens", true, "222-267"),
        Entry("solarized", "Solarized", "Fixed-contrast light and dark", true, "270-315"),
        Entry("nous-alt", "Nous Alt", "Glass neutrals, cream on mission-blue", true, "321-331"),
        Entry("midnight", "Midnight", "Deep blue-violet with cool accents", false, "337-346"),
        Entry("ember", "Ember", "Warm crimson and bronze — forge vibes", false, "348-357"),
        Entry("mono", "Mono", "Clean grayscale — minimal and focused", false, "360-365"),
        Entry("slate", "Slate", "Cool slate blue — focused developer theme", false, "380-388"),
        Entry("cyberpunk", "Cyberpunk", "Neon green on black — matrix terminal", false, "368-377"),
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
