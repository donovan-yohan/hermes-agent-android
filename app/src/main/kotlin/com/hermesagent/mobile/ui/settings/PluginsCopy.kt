package com.hermesagent.mobile.ui.settings

/**
 * Copy for Settings ▸ Plugins.
 *
 * Desktop source: `apps/desktop/src/i18n/en.ts:408-421` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * This app ships **bundled** plugins only (no disk door), so copy that would
 * claim folder-backed installs is adapted and ledgered in
 * `docs/parity/settings-plugins.md`.
 */
object PluginsCopy {
    /** Verbatim `settings.sectionEntries.plugins` (`en.ts:408` @ the pin). */
    const val SETTINGS_ROW_TITLE: String = "Plugins"
    const val SETTINGS_ROW_DETAIL: String = "Enable or disable bundled plugins."

    /**
     * Mobile adaptation of `settings.plugins.title` (`en.ts:411` @ the pin).
     *
     * Desktop calls this section “Desktop plugins” because it supports disk
     * installs. Android ships bundled-only, so “Desktop” would be untrue here.
     */
    const val TITLE: String = "Plugins"

    /**
     * Mobile adaptation of `settings.plugins.blurb` (`en.ts:412` @ the pin).
     *
     * Desktop mentions the `desktop-plugins` folder and a Rescan control, both
     * non-goals on Android (no disk door).
     */
    const val BLURB: String = "Bundled plugins. Disable to unload live."

    /** Verbatim `settings.plugins.count` (`en.ts:413` @ the pin). */
    fun count(n: Int): String = "$n installed"

    /** Verbatim `settings.plugins.enable` / `disable` (`en.ts:417-418` @ the pin). */
    const val ENABLE: String = "Enable"
    const val DISABLE: String = "Disable"

    /** Verbatim `settings.plugins.failed` (`en.ts:419` @ the pin). */
    const val FAILED: String = "failed"

    /**
     * Mobile adaptation of `settings.plugins.empty` (`en.ts:420` @ the pin).
     *
     * Desktop calls them “desktop plugins”; Android shows “plugins”.
     */
    const val EMPTY: String = "No plugins installed yet."

    /** Verbatim `settings.plugins.kinds.*` (`en.ts:421` @ the pin). */
    const val KIND_BUNDLED: String = "bundled"
    const val KIND_DISK: String = "on disk"
    const val KIND_RUNTIME: String = "runtime"
}

