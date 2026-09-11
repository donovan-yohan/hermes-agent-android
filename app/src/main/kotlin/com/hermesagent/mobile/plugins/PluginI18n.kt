package com.hermesagent.mobile.plugins

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Plugin-scoped i18n: a plugin ships its own locale bundles and registers them
 * under its id, resolved against the app's active locale. No core `en.ts`
 * equivalent is edited to add a plugin's strings.
 *
 * Direct Kotlin port of Desktop's `PluginI18n`
 * (`apps/desktop/src/i18n/plugin-i18n.ts:39-47,96-101` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
 */
interface PluginI18n {
    /**
     * Merge locale bundles for this plugin — call once from `register`.
     * Returns a disposer that drops the plugin's bundles on unload/disable,
     * the same lifecycle as every other registration.
     */
    fun register(bundles: Map<String, Map<String, String>>): () -> Unit

    /**
     * Resolve [key] against the app's active locale, then the plugin's own
     * `en` bundle, then the key itself — Desktop's resolution order.
     */
    fun t(key: String): String
}

/**
 * The process-wide plugin locale registry.
 *
 * Keyed by plugin id and then by locale, so two plugins can define the same
 * key and neither can read the other's bundle. A class rather than a global
 * object so a test drives its own registry and never shares state with another
 * test's plugin.
 */
class PluginLocaleRegistry(
    private val activeLocale: () -> String = { Locale.getDefault().language },
) {
    private val bundles =
        ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, String>>>()

    /** Merge [locales] for [pluginId]; returns a disposer dropping them all. */
    fun register(pluginId: String, locales: Map<String, Map<String, String>>): () -> Unit {
        // computeIfAbsent, not getOrPut: two registrations for one plugin must
        // not race into two inner maps and lose one of them.
        val byLocale = bundles.computeIfAbsent(pluginId) { ConcurrentHashMap() }
        locales.forEach { (locale, messages) ->
            byLocale.merge(locale, messages) { existing, incoming -> existing + incoming }
        }
        return { bundles.remove(pluginId) }
    }

    /** Active locale → the plugin's `en` bundle → the key itself. */
    fun translate(pluginId: String, key: String): String {
        val byLocale = bundles[pluginId] ?: return key
        return byLocale[activeLocale()]?.get(key)
            ?: byLocale[FALLBACK_LOCALE]?.get(key)
            ?: key
    }

    /**
     * Build the scoped [PluginI18n] door for one plugin. [track] records the
     * bundle disposer alongside the plugin's other disposers, so unloading the
     * plugin drops its strings with it.
     */
    fun scoped(pluginId: String, track: (() -> Unit) -> () -> Unit): PluginI18n = object : PluginI18n {
        override fun register(bundles: Map<String, Map<String, String>>): () -> Unit =
            track(this@PluginLocaleRegistry.register(pluginId, bundles))

        override fun t(key: String): String = translate(pluginId, key)
    }

    companion object {
        /** The bundle a plugin that ships one language ships: `en`. */
        const val FALLBACK_LOCALE = "en"

        /** The registry the running app's plugin contexts share. */
        val shared = PluginLocaleRegistry()
    }
}
