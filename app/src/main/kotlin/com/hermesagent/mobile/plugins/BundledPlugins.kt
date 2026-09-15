package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.plugins.bots.BotsPlugin
import com.hermesagent.mobile.plugins.kanban.KanbanPlugin

/**
 * Roster of compiled-in plugins available on Android.
 *
 * Mirror of Desktop's `import.meta.glob` over `plugins/<id>/plugin.{js,ts,tsx}`
 * (`apps/desktop/src/contrib/plugins.ts:18` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`).
 */
object BundledPlugins {
    val ALL: List<HermesPlugin> = listOf(
        BotsPlugin(),
        KanbanPlugin(),
    )
}
