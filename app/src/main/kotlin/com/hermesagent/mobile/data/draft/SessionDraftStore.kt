package com.hermesagent.mobile.data.draft

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
private val Context.composerDraftDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "composer_drafts",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Private, no-backup local text drafts keyed only by canonical durable session id. */
interface SessionDraftStore {
    val drafts: Flow<LinkedHashMap<String, String>>

    suspend fun replace(durableSessionId: String, text: String)

    /** Atomically migrates when safe and returns the destination text after the decision. */
    suspend fun migrateIfDestinationEmpty(
        fromDurableId: String,
        toDurableId: String,
        sourceText: String? = null,
    ): String?

    /**
     * Drop every draft, because the endpoint they were typed against is gone.
     *
     * A draft is keyed by durable session id and nothing else, and two gateways
     * can hand out the same one — so text typed against gateway A's `s-123`
     * would otherwise prefill gateway B's `s-123`. Only a connection switch
     * calls this; leaving text in one composer that was written in another is
     * worse than losing it.
     */
    suspend fun clear()
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
        try {
            mutationMutex.withLock {
                dataStore.edit { prefs ->
                    val next = SessionDraftCodec.decode(prefs[DRAFTS_KEY])
                    next.applyReplacement(durableSessionId, text)
                    val encoded = next.trimToStorageLimitAndEncode()
                    if (next.isEmpty()) prefs.remove(DRAFTS_KEY) else prefs[DRAFTS_KEY] = encoded
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CorruptionException) {
            // The corruption handler repairs reads; a racing failed mutation remains non-fatal.
        } catch (_: IOException) {
            // Draft persistence is best-effort; the ViewModel retains the in-memory source.
        }
    }

    override suspend fun clear() {
        try {
            mutationMutex.withLock {
                dataStore.edit { prefs -> prefs.remove(DRAFTS_KEY) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CorruptionException) {
            // Reads are repaired by the corruption handler; a racing failed
            // mutation is non-fatal, and the next read is empty either way.
        } catch (_: IOException) {
            // Best effort, like every other mutation here.
        }
    }

    override suspend fun migrateIfDestinationEmpty(
        fromDurableId: String,
        toDurableId: String,
        sourceText: String?,
    ): String? {
        if (!fromDurableId.isDurableId() || !toDurableId.isDurableId() || fromDurableId == toDurableId) return null
        return try {
            mutationMutex.withLock {
                var destination: String? = null
                dataStore.edit { prefs ->
                    val next = SessionDraftCodec.decode(prefs[DRAFTS_KEY])
                    next.applyMigration(fromDurableId, toDurableId, sourceText)
                    val encoded = next.trimToStorageLimitAndEncode()
                    destination = next[toDurableId]
                    if (next.isEmpty()) prefs.remove(DRAFTS_KEY) else prefs[DRAFTS_KEY] = encoded
                }
                destination
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CorruptionException) {
            null
        } catch (_: IOException) {
            null
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
                applyReplacement(durableSessionId, text)
                trimToStorageLimitAndEncode()
            }
        }
    }

    override suspend fun clear() {
        mutationMutex.withLock { state.value = linkedMapOf() }
    }

    override suspend fun migrateIfDestinationEmpty(
        fromDurableId: String,
        toDurableId: String,
        sourceText: String?,
    ): String? {
        if (!fromDurableId.isDurableId() || !toDurableId.isDurableId() || fromDurableId == toDurableId) return null
        return mutationMutex.withLock {
            state.value = LinkedHashMap(state.value).apply {
                applyMigration(fromDurableId, toDurableId, sourceText)
                trimToStorageLimitAndEncode()
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

private fun LinkedHashMap<String, String>.applyReplacement(durableSessionId: String, text: String) {
    remove(durableSessionId)
    if (text.isNotBlank()) {
        put(durableSessionId, text)
        while (size > MAX_DRAFTS) remove(entries.first().key)
    }
}

private fun LinkedHashMap<String, String>.applyMigration(
    fromDurableId: String,
    toDurableId: String,
    sourceText: String?,
) {
    if (sourceText != null && sourceText.isBlank()) {
        remove(fromDurableId)
        return
    }
    val source = sourceText ?: this[fromDurableId]
    if (this[toDurableId].isNullOrBlank() && !source.isNullOrBlank()) {
        remove(fromDurableId)
        put(toDurableId, source)
    }
}

private fun LinkedHashMap<String, String>.trimToStorageLimitAndEncode(): String {
    var encoded = SessionDraftCodec.encode(this)
    while (isNotEmpty() && encoded.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES) {
        remove(entries.first().key)
        encoded = SessionDraftCodec.encode(this)
    }
    return encoded
}
