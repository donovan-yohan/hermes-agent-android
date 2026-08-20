package com.hermesagent.mobile.ui.theme

/**
 * The Desktop theme registry as it stands at the pinned upstream SHA.
 *
 * **Provenance:** `NousResearch/hermes-agent` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`, file
 * `apps/desktop/src/themes/presets.ts`, transcribed 2026-08-19 from the
 * read-only checkout at `~/.hermes/hermes-agent`.
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

    const val PINNED_SHA = "f82f2dbabd9e66b714f2b4f8a40447fe0c13e732"
    const val SOURCE_PATH = "apps/desktop/src/themes/presets.ts"

    /** presets.ts:291 — `DEFAULT_SKIN_NAME`. */
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

    /** presets.ts:279-286 — `BUILTIN_THEMES`, in declaration order. */
    val ENTRIES: List<Entry> = listOf(
        Entry("nous", "Nous", "Glass neutrals with Nous blue accents", true, "34-97"),
        Entry("midnight", "Midnight", "Deep blue-violet with cool accents", false, "100-134"),
        Entry("ember", "Ember", "Warm crimson and bronze — forge vibes", false, "137-171"),
        Entry("mono", "Mono", "Clean grayscale — minimal and focused", false, "174-204"),
        Entry("cyberpunk", "Cyberpunk", "Neon green on black — matrix terminal", false, "207-241"),
        Entry("slate", "Slate", "Cool slate blue — focused developer theme", false, "244-277"),
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
