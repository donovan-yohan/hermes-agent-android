package com.hermesagent.mobile.ui.theme

/** Resolve without rewriting the stored pick: an unknown custom skin may arrive after boot. */
internal fun resolveAppearancePreset(name: String?, customThemes: List<HermesThemePreset>): HermesThemePreset {
    // context.tsx:51-63 @ e05b16348b1d06a3311237423b0a4fc30d9c5aa1.
    // These are retired picks, not all slash aliases (hermes/ares remain valid custom names).
    if (name in BuiltinThemes.RETIRED_NAMES) return BuiltinThemes.Nous
    return BuiltinThemes.ALL.firstOrNull { it.name == name }
        ?: customThemes.firstOrNull { it.name == name }
        ?: BuiltinThemes.resolve(name)
}
