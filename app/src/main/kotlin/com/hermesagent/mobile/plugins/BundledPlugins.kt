package com.hermesagent.mobile.plugins

/**
 * Roster of compiled-in plugins available on Android.
 *
 * Mirror of Desktop's `import.meta.glob` over `plugins/<id>/plugin.{js,ts,tsx}`
 * (`apps/desktop/src/contrib/plugins.ts:18` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
object BundledPlugins {
    val ALL: List<HermesPlugin> = emptyList()
}
