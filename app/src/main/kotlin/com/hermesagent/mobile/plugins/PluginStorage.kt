package com.hermesagent.mobile.plugins

/**
 * Namespaced persistence for plugins (the VS Code `globalState` analog).
 * Keys live under `hermes.plugin.<id>.<key>` — plugins cannot read or clobber
 * each other.
 *
 * Direct Kotlin port of Desktop's `PluginStorage`
 * (`apps/desktop/src/contrib/plugin.ts:9-13` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
interface PluginStorage {
    suspend fun get(key: String, fallback: String? = null): String?
    suspend fun set(key: String, value: String)
    suspend fun remove(key: String)
}

/**
 * Storage backend seam for reading and writing plugin preferences keys.
 */
interface PluginKeyValueStore {
    suspend fun read(scopedKey: String): String?
    suspend fun write(scopedKey: String, value: String?)
}

/**
 * Derives the namespaced DataStore/Preferences key for a plugin and key.
 */
fun pluginStorageKey(pluginId: String, key: String): String = "hermes.plugin.$pluginId.$key"

/**
 * Scoped implementation of [PluginStorage] bound to a single plugin id.
 */
class ScopedPluginStorage(
    private val pluginId: String,
    private val store: PluginKeyValueStore,
) : PluginStorage {
    override suspend fun get(key: String, fallback: String?): String? {
        return store.read(pluginStorageKey(pluginId, key)) ?: fallback
    }

    override suspend fun set(key: String, value: String) {
        store.write(pluginStorageKey(pluginId, key), value)
    }

    override suspend fun remove(key: String) {
        store.write(pluginStorageKey(pluginId, key), null)
    }
}
