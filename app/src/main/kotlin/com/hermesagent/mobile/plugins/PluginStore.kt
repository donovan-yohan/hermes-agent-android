package com.hermesagent.mobile.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Underlying persistent storage for user enable/disable plugin decisions.
 * Stored in DataStore under `hermes.plugin.decisions.v1`.
 */
interface PluginDecisionStore {
    val pluginDecisions: Flow<Map<String, Boolean>>
    suspend fun savePluginDecision(id: String, enabled: Boolean)
}

/**
 * Loader-owned lifecycle controls for a plugin (activate/deactivate).
 */
interface PluginHandle {
    suspend fun activate()
    fun deactivate()
}

/**
 * JSON serialization for plugin decisions (`id -> Boolean`).
 */
object PluginDecisionsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(decisions: Map<String, Boolean>): String = json.encodeToString(
        JsonObject(decisions.mapValues { JsonPrimitive(it.value) })
    )

    fun decode(raw: String?): Map<String, Boolean> = runCatching {
        if (raw.isNullOrBlank()) return emptyMap()
        val root = json.parseToJsonElement(raw).jsonObject
        root.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.booleanOrNull?.let { k to it }
        }.toMap()
    }.getOrDefault(emptyMap())
}

/**
 * Reactive inventory and lifecycle controller for plugins.
 *
 * Direct Kotlin port of Desktop's `plugins-store.ts`
 * (`apps/desktop/src/contrib/plugins-store.ts:1-118` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
class PluginStore(
    private val scope: CoroutineScope,
    private val decisionStore: PluginDecisionStore,
    initialDecisions: Map<String, Boolean> = emptyMap(),
) {
    private val _records = MutableStateFlow<Map<String, PluginRecord>>(emptyMap())
    val records: StateFlow<Map<String, PluginRecord>> = _records.asStateFlow()

    private val _decisions = MutableStateFlow(initialDecisions)
    val decisions: StateFlow<Map<String, Boolean>> = _decisions.asStateFlow()

    private val handles = ConcurrentHashMap<String, PluginHandle>()

    init {
        scope.launch {
            decisionStore.pluginDecisions.collect { next ->
                _decisions.value = next
            }
        }
    }

    /**
     * Whether a plugin should register: the user's explicit choice if any,
     * else the plugin's own default.
     */
    fun pluginActive(id: String, defaultEnabled: Boolean = true): Boolean {
        val current = _decisions.value
        return current[id] ?: defaultEnabled
    }

    /**
     * Publish or refresh a plugin's inventory record and optional lifecycle handle.
     */
    fun publishPlugin(record: PluginRecord, handle: PluginHandle? = null) {
        _records.update { it + (record.id to record) }
        if (handle != null) {
            handles[record.id] = handle
        }
    }

    /**
     * Mutate an existing plugin record in place.
     */
    fun patchPlugin(id: String, patch: (PluginRecord) -> PluginRecord) {
        _records.update { current ->
            val rec = current[id] ?: return@update current
            current + (id to patch(rec))
        }
    }

    /**
     * Drop a plugin from the active inventory.
     */
    fun dropPlugin(id: String) {
        _records.update { it - id }
        handles.remove(id)
    }

    /**
     * Live toggle: deactivate + remember choice, or activate + remember choice.
     */
    suspend fun setPluginEnabled(id: String, enabled: Boolean) {
        _decisions.update { it + (id to enabled) }
        decisionStore.savePluginDecision(id, enabled)

        val handle = handles[id] ?: return

        if (enabled) {
            try {
                handle.activate()
                patchPlugin(id) {
                    if (it.status == PluginStatus.Disabled) it.copy(status = PluginStatus.Loaded, error = null) else it
                }
            } catch (t: Throwable) {
                patchPlugin(id) {
                    it.copy(
                        status = PluginStatus.Error,
                        error = t.message ?: t.toString(),
                    )
                }
            }
        } else {
            try {
                handle.deactivate()
            } finally {
                patchPlugin(id) { it.copy(status = PluginStatus.Disabled) }
            }
        }
    }
}
