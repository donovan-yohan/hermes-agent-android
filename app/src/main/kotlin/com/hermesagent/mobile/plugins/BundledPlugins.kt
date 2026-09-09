package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.plugins.relay.RelayPlugin

/**
 * Roster of compiled-in plugins available on Android.
 *
 * Mirror of Desktop's `import.meta.glob` over `plugins/<id>/plugin.{js,ts,tsx}`
 * (`apps/desktop/src/contrib/plugins.ts:18` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
 */
object BundledPlugins {
    val ALL: List<HermesPlugin> = listOf(
        RelayPlugin(),
    )
}
