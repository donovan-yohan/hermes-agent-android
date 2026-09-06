package com.hermesagent.mobile.plugins

/**
 * Canonical contribution area identifiers, matching Desktop's area constants
 * (`apps/desktop/src/sdk/index.ts` and `contrib.ts` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
object PluginAreas {
    const val ROUTES_AREA = "routes"
    const val SIDEBAR_NAV_AREA = "sidebarNav"
    const val TRANSCRIPT_DIRECTIVE_AREA = "transcript.directives"
    const val THEMES_AREA = "themes"
    const val PANES_AREA = "panes"
    const val CHAT_EMPTY_AREA = "chatEmpty"
    const val PALETTE_AREA = "palette"
    const val KEYBINDS_AREA = "keybinds"

    object Composer {
        const val TOP = "composer.top"
        const val BOTTOM = "composer.bottom"
        const val UNDERSIDE = "composer.underside"
        const val LEADING = "composer.leading"
        const val ACTIONS = "composer.actions"
        const val MIDDLEWARE = "composer.middleware"
        const val ATTACHMENTS = "composer.attachments"
        const val MICRO_ACTIONS = "composer.microActions"
        const val AT_COMPLETIONS = "composer.atCompletions"
    }

    object StatusBar {
        const val LEFT = "statusBar.left"
        const val RIGHT = "statusBar.right"
    }

    object TitleBar {
        const val CENTER = "titleBar.center"
        const val LEFT = "titleBar.left"
        const val RIGHT = "titleBar.right"
    }
}
