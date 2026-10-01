package com.hermesagent.mobile.ui.theme

/**
 * The Desktop theme registry as it stands at the pinned upstream SHA.
 *
 * **Provenance:** `NousResearch/hermes-agent` @
 * `587e673e2a2fae0616d8b750bb189217080f621a`. Identity, registry and typography
 * come from `apps/desktop/src/themes/presets.ts`; the palette literals moved to
 * `apps/shared/src/theme-presets.ts` (upstream's September 2026 shared-package
 * extraction), so `hasHandTunedDark` is read from there. Transcribed
 * 2026-08-31 and re-verified 2026-10-01 from a disposable upstream checkout.
 * Registry identity, shared palettes, colour keys and typography are unchanged
 * from e27448b231498e79ade668d68c0b6c6206951206. Backend Classic Hermes selection
 * changed separately; this built-in ledger does not certify backend skin parity.
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

    const val PINNED_SHA = "587e673e2a2fae0616d8b750bb189217080f621a"
    const val SOURCE_PATH = "apps/desktop/src/themes/presets.ts"

    /** Where the palettes live since upstream's shared-package extraction. */
    const val PALETTE_SOURCE_PATH = "apps/shared/src/theme-presets.ts"

    /** `presets.ts:415` — `DEFAULT_SKIN_NAME`. */
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

    /** `presets.ts:398-410` — `BUILTIN_THEMES`, in declaration order. */
    val ENTRIES: List<Entry> = listOf(
        Entry("nous", "Nous", "GitHub chrome, Nous blue accent", true, "129-177"),
        Entry("github", "GitHub", "GitHub Light Default and Dark Default", true, "66-114"),
        Entry("catppuccin", "Catppuccin", "Soothing pastels — Latte and Mocha", true, "180-227"),
        Entry("everforest", "Everforest", "Warm, low-contrast forest greens", true, "230-275"),
        Entry("solarized", "Solarized", "Fixed-contrast light and dark", true, "278-323"),
        Entry("nous-alt", "Nous Alt", "Glass neutrals, cream on mission-blue", true, "329-339"),
        Entry("midnight", "Midnight", "Deep blue-violet with cool accents", false, "345-354"),
        Entry("ember", "Ember", "Warm crimson and bronze — forge vibes", false, "356-365"),
        Entry("mono", "Mono", "Clean grayscale — minimal and focused", false, "368-373"),
        Entry("slate", "Slate", "Cool slate blue — focused developer theme", false, "388-396"),
        Entry("cyberpunk", "Cyberpunk", "Neon green on black — matrix terminal", false, "376-385"),
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
