package com.hermesagent.mobile.data.composer

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val QUEUE_VERSION = "1"
private const val MAX_QUEUE_ENTRIES = 100
private const val MAX_QUEUE_ENTRIES_PER_SESSION = MAX_QUEUE_ENTRIES
private const val MAX_QUEUE_TEXT_BYTES = 16 * 1024
private const val MAX_QUEUE_DISPLAY_BYTES = 512
private const val MAX_QUEUE_SERIALIZED_BYTES = 256 * 1024
private val QUEUE_STATE_KEY = stringPreferencesKey("composer.queue.v1")

/**
 * Stable profile scope for private queue storage. Only its SHA-256 digest is
 * used as a filename, so a profile identifier is never copied into a path.
 */
data class ComposerQueueScope(val stableProfileId: String) {
    init {
        require(stableProfileId.isNotBlank())
    }

    internal fun storageFileName(): String = "composer_queue_${stableProfileId.sha256()}.preferences_pb"

    companion object {
        /**
         * Connection, endpoint profile and Hermes profile are all stable
         * persisted identities, never runtime session IDs.
         *
         * [hermesProfile] is the profile scope the rail is in. It is appended
         * only when a named profile is active, so an install that has never
         * used the rail keeps the queue it already has, and text parked under
         * one Hermes profile can never be presented under another.
         */
        fun forConnectionProfile(
            connectionIdentity: String,
            profileIdentity: String,
            hermesProfile: String? = null,
        ): ComposerQueueScope {
            val base = "$connectionIdentity\u0000$profileIdentity"
            val hermes = hermesProfile?.trim().orEmpty()
            return ComposerQueueScope(if (hermes.isEmpty()) base else "$base\u0000hermes:$hermes")
        }
    }
}

/** Delivery state deliberately has no retry transition; review must be explicit. */
enum class QueuedPromptDelivery { Ready, Ambiguous }

/**
 * Text-only private queue record. It contains no runtime Gateway ID, Android
 * URI, attachment reference, credential, or secret field. Busy attachments
 * bypass this store: their in-memory bytes stage directly into the Gateway's
 * connection-scoped next-turn queue, whose accepted text is projected from
 * live Gateway state rather than copied into this private durable store.
 */
data class QueuedPrompt(
    val id: String,
    val text: String,
    val displayText: String? = null,
    val queuedAtMillis: Long,
    val delivery: QueuedPromptDelivery = QueuedPromptDelivery.Ready,
) {
    init {
        require(id.isQueueEntryId())
        require(text.isPersistableQueueText())
        require(displayText == null || displayText.isPersistableQueueDisplay())
        require(queuedAtMillis >= 0)
    }
}

/** Immutable durable-session-keyed queue state. Lists retain FIFO order. */
data class ComposerQueueState(
    val entriesByDurableId: Map<String, List<QueuedPrompt>> = emptyMap(),
) {
    fun entriesFor(durableSessionId: String): List<QueuedPrompt> =
        entriesByDurableId[durableSessionId].orEmpty()

    val entryCount: Int get() = entriesByDurableId.values.sumOf { it.size }
}

/** Never turn a failed persistence operation into a pretend-successful enqueue. */
sealed interface ComposerQueueMutation {
    data object Applied : ComposerQueueMutation
    data object NotFound : ComposerQueueMutation
    data object Rejected : ComposerQueueMutation
    data object CapacityReached : ComposerQueueMutation
    data object StorageUnavailable : ComposerQueueMutation
}

/**
 * Atomic durable queue store. Implementations must not evict an existing entry
 * to make room for a new one: callers receive [ComposerQueueMutation.CapacityReached].
 */
interface ComposerQueueStore {
    val state: Flow<ComposerQueueState>

    suspend fun snapshot(): ComposerQueueState
    suspend fun enqueue(durableSessionId: String, entry: QueuedPrompt): ComposerQueueMutation
    suspend fun update(
        durableSessionId: String,
        entryId: String,
        text: String,
        displayText: String? = null,
    ): ComposerQueueMutation

    suspend fun remove(durableSessionId: String, entryId: String): ComposerQueueMutation
    suspend fun moveToHead(durableSessionId: String, entryId: String): ComposerQueueMutation
    suspend fun migrate(fromDurableId: String, toDurableId: String): ComposerQueueMutation
    suspend fun markAmbiguous(durableSessionId: String, entryId: String): ComposerQueueMutation
    suspend fun markReadyAfterReview(durableSessionId: String, entryId: String): ComposerQueueMutation
}

/**
 * Production private store. Its file lives below `noBackupFilesDir`, instead
 * of the shared connection-preferences store, so queue text never transfers to
 * another device. DataStore serializes the read-modify-write transaction; this
 * mutex also gives callers one local mutation order.
 */
class AndroidComposerQueueStore(
    context: Context,
    scope: ComposerQueueScope,
) : ComposerQueueStore {
    private val dataStore = ComposerQueueDataStores.get(context.applicationContext, scope)
    private val mutationMutex = Mutex()

    override val state: Flow<ComposerQueueState> = dataStore.data.map { preferences ->
        ComposerQueueCodec.decode(preferences[QUEUE_STATE_KEY])
    }

    override suspend fun snapshot(): ComposerQueueState = state.first()

    override suspend fun enqueue(durableSessionId: String, entry: QueuedPrompt): ComposerQueueMutation =
        mutate { current -> current.enqueue(durableSessionId, entry) }

    override suspend fun update(
        durableSessionId: String,
        entryId: String,
        text: String,
        displayText: String?,
    ): ComposerQueueMutation = mutate { current ->
        current.update(durableSessionId, entryId, text, displayText)
    }

    override suspend fun remove(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.remove(durableSessionId, entryId) }

    override suspend fun moveToHead(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.moveToHead(durableSessionId, entryId) }

    override suspend fun migrate(fromDurableId: String, toDurableId: String): ComposerQueueMutation =
        mutate { current -> current.migrate(fromDurableId, toDurableId) }

    override suspend fun markAmbiguous(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.transition(durableSessionId, entryId, QueuedPromptDelivery.Ambiguous) }

    override suspend fun markReadyAfterReview(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.transition(durableSessionId, entryId, QueuedPromptDelivery.Ready) }

    private suspend fun mutate(
        operation: (ComposerQueueState) -> QueueStateMutation,
    ): ComposerQueueMutation = try {
        mutationMutex.withLock {
            var result: ComposerQueueMutation = ComposerQueueMutation.StorageUnavailable
            dataStore.edit { preferences ->
                val current = ComposerQueueCodec.decode(preferences[QUEUE_STATE_KEY])
                val mutation = operation(current)
                result = mutation.result
                if (mutation.result == ComposerQueueMutation.Applied && mutation.state != current) {
                    val encoded = ComposerQueueCodec.encodeOrNull(mutation.state)
                    if (encoded == null) {
                        result = ComposerQueueMutation.CapacityReached
                    } else if (mutation.state.entryCount == 0) {
                        preferences.remove(QUEUE_STATE_KEY)
                    } else {
                        preferences[QUEUE_STATE_KEY] = encoded
                    }
                }
            }
            result
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: CorruptionException) {
        ComposerQueueMutation.StorageUnavailable
    } catch (_: IOException) {
        ComposerQueueMutation.StorageUnavailable
    }
}

/** Opens an isolated no-backup store for the currently selected stable profile. */
class AndroidComposerQueueStoreFactory(private val context: Context) {
    fun open(scope: ComposerQueueScope): AndroidComposerQueueStore = AndroidComposerQueueStore(context, scope)
}

/**
 * One controller can survive profile changes without merging profile queues.
 * Call [switchScope] before handling new scope actions; it serializes the
 * switch against all mutations, while [state] immediately follows only the
 * selected profile's durable queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSwitchingComposerQueueStore(
    private val factory: AndroidComposerQueueStoreFactory,
    initialScope: ComposerQueueScope,
) : ComposerQueueStore {
    private val scopeMutex = Mutex()
    private var selectedScope = initialScope
    private val selectedStore = MutableStateFlow<ComposerQueueStore>(factory.open(initialScope))
    override val state: Flow<ComposerQueueState> = selectedStore.flatMapLatest { it.state }

    suspend fun switchScope(scope: ComposerQueueScope) {
        scopeMutex.withLock {
            if (scope == selectedScope) return
            selectedScope = scope
            selectedStore.value = factory.open(scope)
        }
    }

    override suspend fun snapshot(): ComposerQueueState = withSelectedStore(ComposerQueueStore::snapshot)

    override suspend fun enqueue(durableSessionId: String, entry: QueuedPrompt): ComposerQueueMutation =
        withSelectedStore { it.enqueue(durableSessionId, entry) }

    override suspend fun update(
        durableSessionId: String,
        entryId: String,
        text: String,
        displayText: String?,
    ): ComposerQueueMutation = withSelectedStore { it.update(durableSessionId, entryId, text, displayText) }

    override suspend fun remove(durableSessionId: String, entryId: String): ComposerQueueMutation =
        withSelectedStore { it.remove(durableSessionId, entryId) }

    override suspend fun moveToHead(durableSessionId: String, entryId: String): ComposerQueueMutation =
        withSelectedStore { it.moveToHead(durableSessionId, entryId) }

    override suspend fun migrate(fromDurableId: String, toDurableId: String): ComposerQueueMutation =
        withSelectedStore { it.migrate(fromDurableId, toDurableId) }

    override suspend fun markAmbiguous(durableSessionId: String, entryId: String): ComposerQueueMutation =
        withSelectedStore { it.markAmbiguous(durableSessionId, entryId) }

    override suspend fun markReadyAfterReview(durableSessionId: String, entryId: String): ComposerQueueMutation =
        withSelectedStore { it.markReadyAfterReview(durableSessionId, entryId) }

    private suspend fun <T> withSelectedStore(block: suspend (ComposerQueueStore) -> T): T =
        scopeMutex.withLock { block(selectedStore.value) }
}

/** Test-friendly atomic store. It has the same bound and transition rules. */
class TransientComposerQueueStore(
    initialState: ComposerQueueState = ComposerQueueState(),
) : ComposerQueueStore {
    private val stateFlow = MutableStateFlow(ComposerQueueCodec.normalize(initialState))
    private val mutationMutex = Mutex()
    override val state: Flow<ComposerQueueState> = stateFlow

    override suspend fun snapshot(): ComposerQueueState = stateFlow.value

    override suspend fun enqueue(durableSessionId: String, entry: QueuedPrompt): ComposerQueueMutation =
        mutate { current -> current.enqueue(durableSessionId, entry) }

    override suspend fun update(
        durableSessionId: String,
        entryId: String,
        text: String,
        displayText: String?,
    ): ComposerQueueMutation = mutate { current ->
        current.update(durableSessionId, entryId, text, displayText)
    }

    override suspend fun remove(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.remove(durableSessionId, entryId) }

    override suspend fun moveToHead(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.moveToHead(durableSessionId, entryId) }

    override suspend fun migrate(fromDurableId: String, toDurableId: String): ComposerQueueMutation =
        mutate { current -> current.migrate(fromDurableId, toDurableId) }

    override suspend fun markAmbiguous(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.transition(durableSessionId, entryId, QueuedPromptDelivery.Ambiguous) }

    override suspend fun markReadyAfterReview(durableSessionId: String, entryId: String): ComposerQueueMutation =
        mutate { current -> current.transition(durableSessionId, entryId, QueuedPromptDelivery.Ready) }

    private suspend fun mutate(operation: (ComposerQueueState) -> QueueStateMutation): ComposerQueueMutation =
        mutationMutex.withLock {
            val mutation = operation(stateFlow.value)
            if (mutation.result == ComposerQueueMutation.Applied && mutation.state != stateFlow.value) {
                if (ComposerQueueCodec.encodeOrNull(mutation.state) == null) {
                    ComposerQueueMutation.CapacityReached
                } else {
                    stateFlow.value = mutation.state
                    ComposerQueueMutation.Applied
                }
            } else {
                mutation.result
            }
        }
}

private data class QueueStateMutation(
    val state: ComposerQueueState,
    val result: ComposerQueueMutation,
)

private fun ComposerQueueState.enqueue(
    durableSessionId: String,
    entry: QueuedPrompt,
): QueueStateMutation {
    if (!durableSessionId.isDurableQueueId()) return QueueStateMutation(this, ComposerQueueMutation.Rejected)
    val existing = entriesFor(durableSessionId)
    if (entryCount >= MAX_QUEUE_ENTRIES || existing.size >= MAX_QUEUE_ENTRIES_PER_SESSION ||
        existing.any { it.id == entry.id }
    ) {
        return QueueStateMutation(this, ComposerQueueMutation.CapacityReached)
    }
    return QueueStateMutation(
        copy(entriesByDurableId = entriesByDurableId + (durableSessionId to (existing + entry))),
        ComposerQueueMutation.Applied,
    )
}

private fun ComposerQueueState.update(
    durableSessionId: String,
    entryId: String,
    text: String,
    displayText: String?,
): QueueStateMutation {
    if (!durableSessionId.isDurableQueueId() || !entryId.isQueueEntryId() ||
        !text.isPersistableQueueText() || (displayText != null && !displayText.isPersistableQueueDisplay())
    ) {
        return QueueStateMutation(this, ComposerQueueMutation.Rejected)
    }
    val entries = entriesFor(durableSessionId)
    val index = entries.indexOfFirst { it.id == entryId }
    if (index < 0) return QueueStateMutation(this, ComposerQueueMutation.NotFound)
    val updated = entries.toMutableList().apply {
        this[index] = this[index].copy(text = text, displayText = displayText)
    }
    return QueueStateMutation(
        copy(entriesByDurableId = entriesByDurableId + (durableSessionId to updated)),
        ComposerQueueMutation.Applied,
    )
}

private fun ComposerQueueState.remove(durableSessionId: String, entryId: String): QueueStateMutation {
    if (!durableSessionId.isDurableQueueId() || !entryId.isQueueEntryId()) {
        return QueueStateMutation(this, ComposerQueueMutation.Rejected)
    }
    val entries = entriesFor(durableSessionId)
    val index = entries.indexOfFirst { it.id == entryId }
    if (index < 0) return QueueStateMutation(this, ComposerQueueMutation.NotFound)
    val updated = entries.toMutableList().apply { removeAt(index) }
    val map = entriesByDurableId.toMutableMap().apply {
        if (updated.isEmpty()) remove(durableSessionId) else put(durableSessionId, updated)
    }
    return QueueStateMutation(copy(entriesByDurableId = map), ComposerQueueMutation.Applied)
}

private fun ComposerQueueState.moveToHead(durableSessionId: String, entryId: String): QueueStateMutation {
    if (!durableSessionId.isDurableQueueId() || !entryId.isQueueEntryId()) {
        return QueueStateMutation(this, ComposerQueueMutation.Rejected)
    }
    val entries = entriesFor(durableSessionId)
    val index = entries.indexOfFirst { it.id == entryId }
    if (index < 0) return QueueStateMutation(this, ComposerQueueMutation.NotFound)
    if (index == 0) return QueueStateMutation(this, ComposerQueueMutation.Applied)
    val updated = entries.toMutableList().apply { add(0, removeAt(index)) }
    return QueueStateMutation(
        copy(entriesByDurableId = entriesByDurableId + (durableSessionId to updated)),
        ComposerQueueMutation.Applied,
    )
}

private fun ComposerQueueState.migrate(fromDurableId: String, toDurableId: String): QueueStateMutation {
    if (!fromDurableId.isDurableQueueId() || !toDurableId.isDurableQueueId()) {
        return QueueStateMutation(this, ComposerQueueMutation.Rejected)
    }
    if (fromDurableId == toDurableId) return QueueStateMutation(this, ComposerQueueMutation.Applied)
    val source = entriesFor(fromDurableId)
    if (source.isEmpty()) return QueueStateMutation(this, ComposerQueueMutation.NotFound)
    val destination = entriesFor(toDurableId)
    val combined = (source + destination)
        .sortedWith(compareBy<QueuedPrompt> { it.queuedAtMillis }.thenBy { it.id })
    if (combined.size > MAX_QUEUE_ENTRIES_PER_SESSION || combined.size > MAX_QUEUE_ENTRIES) {
        return QueueStateMutation(this, ComposerQueueMutation.CapacityReached)
    }
    val map = entriesByDurableId.toMutableMap().apply {
        remove(fromDurableId)
        put(toDurableId, combined)
    }
    return QueueStateMutation(copy(entriesByDurableId = map), ComposerQueueMutation.Applied)
}

private fun ComposerQueueState.transition(
    durableSessionId: String,
    entryId: String,
    target: QueuedPromptDelivery,
): QueueStateMutation {
    if (!durableSessionId.isDurableQueueId() || !entryId.isQueueEntryId()) {
        return QueueStateMutation(this, ComposerQueueMutation.Rejected)
    }
    val entries = entriesFor(durableSessionId)
    val index = entries.indexOfFirst { it.id == entryId }
    if (index < 0) return QueueStateMutation(this, ComposerQueueMutation.NotFound)
    val current = entries[index]
    if (current.delivery == target) return QueueStateMutation(this, ComposerQueueMutation.Applied)
    // Auto-drain may only make Ready -> Ambiguous. Returning to Ready needs the
    // explicit review method above, so no reconnect can silently retry it.
    val updated = entries.toMutableList().apply { this[index] = current.copy(delivery = target) }
    return QueueStateMutation(
        copy(entriesByDurableId = entriesByDurableId + (durableSessionId to updated)),
        ComposerQueueMutation.Applied,
    )
}

internal object ComposerQueueCodec {
    private val json = Json { ignoreUnknownKeys = false }
    private val entryKeys = setOf("id", "text", "displayText", "queuedAtMillis", "delivery")

    fun decode(raw: String?): ComposerQueueState {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_QUEUE_SERIALIZED_BYTES) {
            return ComposerQueueState()
        }
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["version"]?.jsonPrimitive?.content != QUEUE_VERSION) return ComposerQueueState()
            val sessions = root["sessions"]?.jsonArray ?: return ComposerQueueState()
            if (sessions.size > MAX_QUEUE_ENTRIES) return ComposerQueueState()
            val restored = linkedMapOf<String, List<QueuedPrompt>>()
            var count = 0
            for (session in sessions) {
                val objectValue = session.jsonObject
                if (objectValue.keys != setOf("durableId", "entries")) return ComposerQueueState()
                val durableId = objectValue["durableId"]?.jsonPrimitive?.content.orEmpty()
                val entries = objectValue["entries"]?.jsonArray ?: return ComposerQueueState()
                if (!durableId.isDurableQueueId() || entries.isEmpty() ||
                    entries.size > MAX_QUEUE_ENTRIES_PER_SESSION || durableId in restored
                ) {
                    return ComposerQueueState()
                }
                val decodedEntries = entries.mapNotNull(::decodeEntry)
                if (decodedEntries.size != entries.size || decodedEntries.map(QueuedPrompt::id).distinct().size != entries.size) {
                    return ComposerQueueState()
                }
                count += decodedEntries.size
                if (count > MAX_QUEUE_ENTRIES) return ComposerQueueState()
                restored[durableId] = decodedEntries
            }
            ComposerQueueState(restored)
        }.getOrElse { ComposerQueueState() }
    }

    fun encodeOrNull(state: ComposerQueueState): String? {
        val normalized = normalize(state)
        if (normalized != state || normalized.entryCount > MAX_QUEUE_ENTRIES) return null
        val root = JsonObject(
            mapOf(
                "version" to JsonPrimitive(QUEUE_VERSION),
                "sessions" to JsonArray(normalized.entriesByDurableId.map { (durableId, entries) ->
                    JsonObject(
                        mapOf(
                            "durableId" to JsonPrimitive(durableId),
                            "entries" to JsonArray(entries.map(::encodeEntry)),
                        ),
                    )
                }),
            ),
        )
        val encoded = json.encodeToString(root)
        return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_QUEUE_SERIALIZED_BYTES }
    }

    fun normalize(state: ComposerQueueState): ComposerQueueState {
        if (state.entryCount > MAX_QUEUE_ENTRIES) return ComposerQueueState()
        val entries = linkedMapOf<String, List<QueuedPrompt>>()
        state.entriesByDurableId.forEach { (durableId, values) ->
            if (!durableId.isDurableQueueId() || values.isEmpty() || values.size > MAX_QUEUE_ENTRIES_PER_SESSION ||
                values.any { !it.id.isQueueEntryId() || !it.text.isPersistableQueueText() ||
                    (it.displayText != null && !it.displayText.isPersistableQueueDisplay()) || it.queuedAtMillis < 0
                } || values.map(QueuedPrompt::id).distinct().size != values.size
            ) {
                return ComposerQueueState()
            }
            entries[durableId] = values.toList()
        }
        return ComposerQueueState(entries)
    }

    private fun decodeEntry(element: kotlinx.serialization.json.JsonElement): QueuedPrompt? = runCatching {
        val value = element.jsonObject
        if (!value.keys.all(entryKeys::contains)) return null
        val id = value["id"]?.jsonPrimitive?.content.orEmpty()
        val text = value["text"]?.jsonPrimitive?.content.orEmpty()
        val display = value["displayText"]?.jsonPrimitive?.contentOrNull
        val queuedAt = value["queuedAtMillis"]?.jsonPrimitive?.longOrNull ?: return null
        val delivery = when (value["delivery"]?.jsonPrimitive?.content) {
            "ready" -> QueuedPromptDelivery.Ready
            "ambiguous" -> QueuedPromptDelivery.Ambiguous
            else -> return null
        }
        QueuedPrompt(id, text, display, queuedAt, delivery)
    }.getOrNull()

    private fun encodeEntry(entry: QueuedPrompt): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(entry.id))
            put("text", JsonPrimitive(entry.text))
            entry.displayText?.let { put("displayText", JsonPrimitive(it)) }
            put("queuedAtMillis", JsonPrimitive(entry.queuedAtMillis))
            put("delivery", JsonPrimitive(if (entry.delivery == QueuedPromptDelivery.Ready) "ready" else "ambiguous"))
        },
    )
}

private object ComposerQueueDataStores {
    private val stores = mutableMapOf<String, DataStore<Preferences>>()

    fun get(context: Context, scope: ComposerQueueScope): DataStore<Preferences> = synchronized(this) {
        val file = File(context.noBackupFilesDir, scope.storageFileName())
        stores.getOrPut(file.absolutePath) {
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { file },
            )
        }
    }
}

private fun String.isDurableQueueId(): Boolean = isNotBlank() && trim() == this && length <= 512
private fun String.isQueueEntryId(): Boolean = isNotBlank() && trim() == this && length <= 128
private fun String.isPersistableQueueText(): Boolean = isNotBlank() && toByteArray(Charsets.UTF_8).size <= MAX_QUEUE_TEXT_BYTES
private fun String.isPersistableQueueDisplay(): Boolean = isNotBlank() && toByteArray(Charsets.UTF_8).size <= MAX_QUEUE_DISPLAY_BYTES

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
