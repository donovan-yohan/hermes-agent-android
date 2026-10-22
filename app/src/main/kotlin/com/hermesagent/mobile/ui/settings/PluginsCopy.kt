package com.hermesagent.mobile.ui.settings

/**
 * Copy for this app's Plugins surface.
 *
 * Desktop source: `apps/desktop/src/app/skills/plugins-tab.tsx` and the two copy
 * blocks it reads — `skills.plugins.*` (`apps/desktop/src/i18n/en.ts:1587-1630`)
 * and `settings.plugins.*` (`:445-457`) — @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`.
 *
 * Upstream `61afcde8f9` folded Desktop's two half-pages into one: Capabilities ▸
 * Plugins now owns agent plugins, desktop plugins, install and the catalog, and
 * Settings ▸ Plugins was deleted along with the `settings.nav.plugins` key this
 * file used to quote. The historical naming port is retained. The agent list
 * and toggles are now backed separately; their current source pin is documented
 * beside AGENT_BLURB and in docs/parity/settings-plugins.md. Install and catalog
 * remain outside this bounded capability.
 */
object PluginsCopy {
    /**
     * Verbatim `skills.tabPlugins` (`i18n/en.ts:1587` @ the pin).
     *
     * The old stamp quoted `settings.sectionEntries.plugins` at `en.ts:408` @
     * `3ca096de`, which named the wrong key (that line was `settings.nav.plugins`)
     * and no longer exists at all: `61afcde8f9` dropped it when Settings ▸
     * Plugins was removed. The word Desktop draws on the tab is unchanged, so
     * only the citation moves.
     */
    const val SETTINGS_ROW_TITLE: String = "Plugins"

    /**
     * Mobile adaptation of `settings.plugins.blurb` (`en.ts:447-448` @ the pin),
     * shortened to the one clause a destination row has space for.
     *
     * Desktop reaches this surface from a Capabilities pane and a command
     * palette, neither of which a phone has; this app's only destination list is
     * Settings, so the row says what the surface is for rather than where it sits.
     */
    const val SETTINGS_ROW_DETAIL: String = "Extend this app. Enable or disable bundled plugins."

    /**
     * Verbatim `skills.tabPlugins` (`en.ts:1587` @ the pin) — the page's own name.
     *
     * It used to read as an adaptation of `settings.plugins.title`
     * ("Desktop plugins"), which scoped the surface to Desktop's disk door. Since
     * `61afcde8f9` the page Desktop calls "Plugins" is the whole plugins surface,
     * so this app's title is the same word for the same thing.
     */
    const val TITLE: String = "Plugins"

    /**
     * Mobile adaptation of `settings.plugins.blurb` (`en.ts:447-448` @ the pin).
     *
     * Desktop's first clause — "Extend this app, not an agent — installed once
     * for the whole app, whichever profile, gateway, or machine you connect to"
     * — is true here and is the fact that separates this list from the agent half
     * below it, so it is kept. Its second clause names the `desktop-plugins`
     * folder, a non-goal on Android (`docs/adr/0003-bundled-plugin-sdk.md`), so
     * the sentence states the only delivery mode this app has.
     */
    const val BLURB: String =
        "Extend this app, not an agent — the same for every profile, gateway or machine you " +
            "connect to. Bundled with the app; toggles apply live."

    /** Verbatim `settings.plugins.count` (`en.ts:449` @ the pin). */
    fun count(n: Int): String = "$n installed"

    /** Verbatim `settings.plugins.enable` / `disable` (`en.ts:453-454` @ the pin). */
    const val ENABLE: String = "Enable"
    const val DISABLE: String = "Disable"

    /** Verbatim `settings.plugins.failed` (`en.ts:455` @ the pin). */
    const val FAILED: String = "failed"

    /**
     * Verbatim `skills.plugins.emptyAll` (`en.ts:1609` @ the pin).
     *
     * The page-level empty, not `settings.plugins.empty` ("No desktop plugins
     * installed yet.", `:456`): that one is scoped to Desktop's desktop-half
     * list, and this app's list is the whole page.
     */
    const val EMPTY: String = "No plugins yet."

    /** Verbatim `settings.plugins.kinds.*` (`en.ts:457` @ the pin). */
    const val KIND_BUNDLED: String = "bundled"
    const val KIND_DISK: String = "on disk"
    const val KIND_RUNTIME: String = "runtime"

    /** Verbatim `skills.plugins.agentTitle` (`en.ts:1589` @ the pin). */
    const val AGENT_TITLE: String = "Agent plugins"

    /** Verbatim skills.plugins.agentBlurb, apps/desktop/src/i18n/en.ts:2105-2106
     * @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa. */
    const val AGENT_BLURB: String = "Extend the agent for the selected profile — tools, hooks, providers. " +
        "Take effect after a gateway restart."
}
