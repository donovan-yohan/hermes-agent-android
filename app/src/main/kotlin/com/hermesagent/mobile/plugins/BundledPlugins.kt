package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.plugins.bots.BotsPlugin
import com.hermesagent.mobile.plugins.kanban.KanbanPlugin

/**
 * Roster of compiled-in plugins available on Android.
 *
 * Mirror of Desktop's `import.meta.glob` over `plugins/<id>/plugin.{js,ts,tsx}`
 * (`apps/desktop/src/contrib/plugins.ts:18` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`).
 */
object BundledPlugins {
    val ALL: List<HermesPlugin> = create()

    /** App-owned dependencies enter through the Bots constructor, not a global host. */
    fun create(bots: BotsPlugin = BotsPlugin()): List<HermesPlugin> = listOf(
        bots,
        com.hermesagent.mobile.plugins.groups.GroupsPlugin(),
        KanbanPlugin(),
    )
}
