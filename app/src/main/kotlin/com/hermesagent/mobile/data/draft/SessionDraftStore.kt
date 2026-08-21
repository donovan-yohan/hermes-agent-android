package com.hermesagent.mobile.data.draft

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_DRAFTS = 50
private const val MAX_SERIALIZED_BYTES = 64 * 1024
private val DRAFTS_KEY = stringPreferencesKey("composer.drafts.v1")
private val Context.composerDraftDataStore: DataStore<Preferences> by preferencesDataStore(name = "composer_drafts")

/** Private, no-backup local text drafts keyed only by canonical durable session id. */
interface SessionDraftStore {
    val drafts: Flow<LinkedHashMap<String, String>>

    suspend fun replace(durableSessionId: String, text: String)
    /** Atomically migrates when safe and returns the destination text after the decision. */
    suspend fun migrateIfDestinationEmpty(fromDurableId: String, toDurableId: String): String?
}

/**
 * A separate DataStore keeps private draft text out of connection preferences and
 * SessionCache. DataStore mutations serialize read-modify-write atomically.
 */
class AndroidSessionDraftStore(context: Context) : SessionDraftStore {
    private val dataStore = context.composerDraftDataStore
    private val mutationMutex = Mutex()

    override val drafts: Flow<LinkedHashMap<String, String>> = dataStore.data
        .map { prefs -> SessionDraftCodec.decode(prefs[DRAFTS_KEY]) }
        .catch { emit(linkedMapOf()) }

    override suspend fun replace(durableSessionId: String, text: String) {
        if (!durableSessionId.isDurableId()) return
        mutationMutex.withLock {
            dataStore.edit { prefs ->
                val next = SessionDraftCodec.decode(prefs[DRAFTS_KEY])
                next.remove(durableSessionId)
                if (text.isNotBlank()) {
                    next[durableSessionId] = text
                    while (next.size > MAX_DRAFTS) next.remove(next.entries.first().key)
                }
                next.trimToStorageLimit()
                if (next.isEmpty()) prefs.remove(DRAFTS_KEY) else prefs[DRAFTS_KEY] = SessionDraftCodec.encode(next)
            }
        }
    }

    override suspend fun migrateIfDestinationEmpty(fromDurableId: String, toDurableId: String): String? {
        if (!fromDurableId.isDurableId() || !toDurableId.isDurableId() || fromDurableId == toDurableId) return null
        return mutationMutex.withLock {
            var destination: String? = null
            dataStore.edit { prefs ->
                val next = SessionDraftCodec.decode(prefs[DRAFTS_KEY])
                val source = next[fromDurableId]
                if (next[toDurableId].isNullOrBlank() && !source.isNullOrBlank()) {
                    next.remove(fromDurableId)
                    next[toDurableId] = source
                }
                next.trimToStorageLimit()
                destination = next[toDurableId]
                if (next.isEmpty()) prefs.remove(DRAFTS_KEY) else prefs[DRAFTS_KEY] = SessionDraftCodec.encode(next)
            }
            destination
        }
    }
}

/** Test-friendly non-persistent implementation; production always injects AndroidSessionDraftStore. */
internal class TransientSessionDraftStore : SessionDraftStore {
    private val state = MutableStateFlow(linkedMapOf<String, String>())
    private val mutationMutex = Mutex()
    override val drafts: Flow<LinkedHashMap<String, String>> = state

    override suspend fun replace(durableSessionId: String, text: String) {
        if (!durableSessionId.isDurableId()) return
        mutationMutex.withLock {
            state.value = LinkedHashMap(state.value).apply {
                remove(durableSessionId)
                if (text.isNotBlank()) {
                    put(durableSessionId, text)
                    while (size > MAX_DRAFTS) remove(entries.first().key)
                }
                trimToStorageLimit()
            }
        }
    }

    override suspend fun migrateIfDestinationEmpty(fromDurableId: String, toDurableId: String): String? {
        if (!fromDurableId.isDurableId() || !toDurableId.isDurableId() || fromDurableId == toDurableId) return null
        return mutationMutex.withLock {
            state.value = LinkedHashMap(state.value).apply {
                val source = this[fromDurableId]
                if (this[toDurableId].isNullOrBlank() && !source.isNullOrBlank()) {
                    remove(fromDurableId)
                    put(toDurableId, source)
                }
                trimToStorageLimit()
            }
            state.value[toDurableId]
        }
    }
}

internal object SessionDraftCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String?): LinkedHashMap<String, String> {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES) return linkedMapOf()
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["version"]?.jsonPrimitive?.content != "1") return linkedMapOf()
            val values = root["drafts"]?.jsonObject ?: return linkedMapOf()
            LinkedHashMap<String, String>().apply {
                for ((id, value) in values) {
                    val text = value.jsonPrimitive.content
                    if (id.isDurableId() && text.isNotBlank()) put(id, text)
                }
                while (size > MAX_DRAFTS) remove(entries.first().key)
            }
        }.getOrElse { linkedMapOf() }
    }

    fun encode(drafts: LinkedHashMap<String, String>): String = json.encodeToString(
        JsonObject(mapOf("version" to JsonPrimitive("1"), "drafts" to JsonObject(drafts.mapValues { JsonPrimitive(it.value) }))),
    )
}

private fun String.isDurableId(): Boolean = isNotBlank() && length <= 512

private fun LinkedHashMap<String, String>.trimToStorageLimit() {
    while (isNotEmpty() && SessionDraftCodec.encode(this).toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES) {
        remove(entries.first().key)
    }
}
