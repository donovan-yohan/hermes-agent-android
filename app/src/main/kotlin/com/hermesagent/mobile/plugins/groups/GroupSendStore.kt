package com.hermesagent.mobile.plugins.groups

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

private const val STORE_VERSION = "1"
private const val MAX_STORE_RECORDS = 50
private const val MAX_STORE_SERIALIZED_BYTES = 128 * 1024
internal enum class GroupSendRecordState {
    Prepared,
    Uncertain,
    Confirmed,
    Blocked,
}

internal data class GroupSendScope(
    val identity: GroupSendIdentity,
    val room: String,
) {
    init { sendIdentifier(room) }

    /** Length-prefixed components make the full durable key injective without changing wire values. */
    fun durableKey(vararg suffixes: String): String = buildString {
        val parts = listOf(identity.savedRow, identity.endpointBinding, identity.gateway, room) + suffixes
        append(parts.size)
        parts.forEach { append(':').append(it.toByteArray(Charsets.UTF_8).size).append(':').append(it) }
    }

    val roomKey: String get() = durableKey()
}

internal data class GroupSendEditorDraft(
    val target: GroupSendTarget,
    val text: String,
    val thread: String,
    val revision: Long,
) {
    init {
        require(revision >= 0)
        require(text.isNotEmpty() && text == text.trim(::pythonSpace))
        sendIdentifier(thread)
    }

    val scope: GroupSendScope get() = GroupSendScope(target.identity, target.room)
    val draftKey: String get() = scope.roomKey

    fun matches(record: GroupSendRecord): Boolean =
        target == record.operation.target &&
            text == record.operation.payload.text &&
            thread == record.operation.payload.thread &&
            revision == record.draftRevision
}

internal data class GroupSendRecord(
    val operation: GroupSendOperation,
    val draftRevision: Long,
    val state: GroupSendRecordState,
    val createdAtMillis: Long,
    val receiptSeq: Long? = null,
    val receiptDriver: Boolean? = null,
) {
    init {
        require(draftRevision >= 0)
        require(createdAtMillis >= 0)
    }

    val recordKey: String get() = GroupSendScope(operation.target.identity, operation.target.room)
        .durableKey(operation.rawId)
}

internal sealed interface GroupSendStoreMutation {
    data object Applied : GroupSendStoreMutation
    data object StorageUnavailable : GroupSendStoreMutation
    data object CapacityReached : GroupSendStoreMutation
    data object ConflictingRecord : GroupSendStoreMutation
    data object NotFound : GroupSendStoreMutation
}

internal data class GroupSendStoreSnapshot(
    val drafts: Map<String, GroupSendEditorDraft> = emptyMap(),
    val records: Map<String, GroupSendRecord> = emptyMap(),
    val tombstones: Set<String> = emptySet(), // Scoped room tombstones (e.g. "savedRow:endpointBinding:room")
    val valid: Boolean = true,
)

internal interface GroupSendStore {
    val snapshotFlow: Flow<GroupSendStoreSnapshot>
    suspend fun snapshot(): GroupSendStoreSnapshot
    suspend fun persistDraftAndPrepare(draft: GroupSendEditorDraft, record: GroupSendRecord): GroupSendStoreMutation
    suspend fun prepare(record: GroupSendRecord): GroupSendStoreMutation
    suspend fun markUncertain(recordKey: String): GroupSendStoreMutation
    suspend fun markConfirmed(recordKey: String, receipt: GroupSendReceipt): GroupSendStoreMutation
    suspend fun markBlocked(recordKey: String): GroupSendStoreMutation
    suspend fun tombstoneRoom(scopedRoomKey: String): GroupSendStoreMutation
    suspend fun remove(recordKey: String): GroupSendStoreMutation
}

internal open class TransientGroupSendStore(
    initialSnapshot: GroupSendStoreSnapshot = GroupSendStoreSnapshot()
) : GroupSendStore {
    private val mutex = Mutex()
    private val flow = kotlinx.coroutines.flow.MutableStateFlow(initialSnapshot)
    var failWrites = false

    override val snapshotFlow: Flow<GroupSendStoreSnapshot> = flow

    override suspend fun snapshot(): GroupSendStoreSnapshot = flow.value

    override suspend fun persistDraftAndPrepare(draft: GroupSendEditorDraft, record: GroupSendRecord): GroupSendStoreMutation = mutex.withLock {
        if (failWrites || !flow.value.valid) return GroupSendStoreMutation.StorageUnavailable
        if (!draft.matches(record)) return GroupSendStoreMutation.ConflictingRecord
        val scopedRoom = draft.draftKey
        if (scopedRoom in flow.value.tombstones) return GroupSendStoreMutation.ConflictingRecord
        val existing = flow.value.records[record.recordKey]
        if (existing != null && existing.operation.payload != record.operation.payload) return GroupSendStoreMutation.ConflictingRecord
        if (existing == null && flow.value.records.size >= MAX_STORE_RECORDS) return GroupSendStoreMutation.CapacityReached
        val existingDraft = flow.value.drafts[draft.draftKey]
        // A replay keeps its accepted identity; a newly minted stale intent is not a replay.
        if (existing == null && existingDraft != null && existingDraft.revision > draft.revision) {
            return GroupSendStoreMutation.ConflictingRecord
        }
        val retainedDraft = if (existingDraft != null && existingDraft.revision > draft.revision) existingDraft else draft
        flow.value = flow.value.copy(
            drafts = flow.value.drafts + (draft.draftKey to retainedDraft),
            records = flow.value.records + (record.recordKey to (existing ?: record)),
        )
        GroupSendStoreMutation.Applied
    }

    override suspend fun prepare(record: GroupSendRecord): GroupSendStoreMutation = mutex.withLock {
        if (failWrites || !flow.value.valid) return GroupSendStoreMutation.StorageUnavailable
        val scopedRoom = GroupSendScope(record.operation.target.identity, record.operation.target.room).roomKey
        if (scopedRoom in flow.value.tombstones) return GroupSendStoreMutation.ConflictingRecord
        val existing = flow.value.records[record.recordKey]
        if (existing != null) {
            if (existing.operation.payload != record.operation.payload) return GroupSendStoreMutation.ConflictingRecord
            // Idempotent retry of identical payload with same rawId is allowed
            return GroupSendStoreMutation.Applied
        }
        if (flow.value.records.size >= MAX_STORE_RECORDS) return GroupSendStoreMutation.CapacityReached
        flow.value = flow.value.copy(records = flow.value.records + (record.recordKey to record))
        GroupSendStoreMutation.Applied
    }

    override suspend fun markUncertain(recordKey: String): GroupSendStoreMutation = mutex.withLock {
        if (failWrites || !flow.value.valid) return GroupSendStoreMutation.StorageUnavailable
        val existing = flow.value.records[recordKey] ?: return GroupSendStoreMutation.NotFound
        flow.value = flow.value.copy(records = flow.value.records + (recordKey to existing.copy(state = GroupSendRecordState.Uncertain)))
        GroupSendStoreMutation.Applied
    }

    override suspend fun markConfirmed(recordKey: String, receipt: GroupSendReceipt): GroupSendStoreMutation = mutex.withLock {
        if (failWrites || !flow.value.valid) return GroupSendStoreMutation.StorageUnavailable
        val existing = flow.value.records[recordKey] ?: return GroupSendStoreMutation.NotFound
        flow.value = flow.value.copy(records = flow.value.records + (recordKey to existing.copy(
            state = GroupSendRecordState.Confirmed,
            receiptSeq = receipt.sequence,
            receiptDriver = receipt.driverStarted
        )))
        GroupSendStoreMutation.Applied
    }

    override suspend fun markBlocked(recordKey: String): GroupSendStoreMutation = mutex.withLock {
        if (failWrites || !flow.value.valid) return GroupSendStoreMutation.StorageUnavailable
        val existing = flow.value.records[recordKey] ?: return GroupSendStoreMutation.NotFound
        flow.value = flow.value.copy(records = flow.value.records + (recordKey to existing.copy(state = GroupSendRecordState.Blocked)))
        GroupSendStoreMutation.Applied
    }

    override suspend fun tombstoneRoom(scopedRoomKey: String): GroupSendStoreMutation = mutex.withLock {
        if (failWrites || !flow.value.valid) return GroupSendStoreMutation.StorageUnavailable
        flow.value = flow.value.copy(tombstones = flow.value.tombstones + scopedRoomKey)
        GroupSendStoreMutation.Applied
    }

    override suspend fun remove(recordKey: String): GroupSendStoreMutation = mutex.withLock {
        if (failWrites || !flow.value.valid) return GroupSendStoreMutation.StorageUnavailable
        if (recordKey !in flow.value.records) return GroupSendStoreMutation.NotFound
        flow.value = flow.value.copy(records = flow.value.records - recordKey)
        GroupSendStoreMutation.Applied
    }
}

internal object GroupSendStoreCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun decode(raw: String?): GroupSendStoreSnapshot {
        if (raw.isNullOrBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_STORE_SERIALIZED_BYTES) {
            return GroupSendStoreSnapshot(valid = false)
        }
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if (root["version"]?.jsonPrimitive?.content != STORE_VERSION) return GroupSendStoreSnapshot(valid = false)
            val tombstones = root["tombstones"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
                ?: return GroupSendStoreSnapshot(valid = false)
            val draftsJson = root["drafts"]?.jsonArray ?: return GroupSendStoreSnapshot(valid = false)
            val drafts = mutableMapOf<String, GroupSendEditorDraft>()
            for (elem in draftsJson) {
                val draft = decodeDraft(elem.jsonObject) ?: return GroupSendStoreSnapshot(valid = false)
                if (drafts.put(draft.draftKey, draft) != null) return GroupSendStoreSnapshot(valid = false)
            }
            val recordsJson = root["records"]?.jsonArray ?: return GroupSendStoreSnapshot(valid = false)
            val recordsMap = mutableMapOf<String, GroupSendRecord>()
            for (elem in recordsJson) {
                val r = decodeRecord(elem.jsonObject) ?: return GroupSendStoreSnapshot(valid = false)
                if (recordsMap.put(r.recordKey, r) != null) return GroupSendStoreSnapshot(valid = false)
            }
            if (recordsMap.size > MAX_STORE_RECORDS) return GroupSendStoreSnapshot(valid = false)
            GroupSendStoreSnapshot(drafts = drafts, records = recordsMap, tombstones = tombstones, valid = true)
        }.getOrElse { GroupSendStoreSnapshot(valid = false) }
    }

    fun encodeOrNull(snapshot: GroupSendStoreSnapshot): String? {
        if (snapshot.records.size > MAX_STORE_RECORDS) return null
        val root = buildJsonObject {
            put("version", STORE_VERSION)
            put("tombstones", JsonArray(snapshot.tombstones.map { JsonPrimitive(it) }))
            put("drafts", JsonArray(snapshot.drafts.values.map(::encodeDraft)))
            put("records", JsonArray(snapshot.records.values.map(::encodeRecord)))
        }
        val encoded = json.encodeToString(root)
        return encoded.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_STORE_SERIALIZED_BYTES }
    }

    private fun decodeDraft(obj: JsonObject): GroupSendEditorDraft? = runCatching {
        GroupSendEditorDraft(
            target = GroupSendTarget(
                identity = GroupSendIdentity(obj.str("saved_row"), obj.str("endpoint_binding"), obj.str("gateway")),
                room = obj.str("room"),
            ),
            text = obj.str("text"),
            thread = obj.str("thread"),
            revision = obj.num("revision"),
        )
    }.getOrNull()

    private fun encodeDraft(draft: GroupSendEditorDraft): JsonObject = buildJsonObject {
        put("saved_row", draft.target.identity.savedRow)
        put("endpoint_binding", draft.target.identity.endpointBinding)
        put("gateway", draft.target.identity.gateway)
        put("room", draft.target.room)
        put("text", draft.text)
        put("thread", draft.thread)
        put("revision", draft.revision)
    }

    private fun decodeRecord(obj: JsonObject): GroupSendRecord? = runCatching {
        val savedRow = obj.str("saved_row")
        val endpointBinding = obj.str("endpoint_binding")
        val gateway = obj.str("gateway")
        val room = obj.str("room")
        val rawId = obj.str("raw_id")
        val text = obj.str("text")
        val thread = obj.str("thread")
        val draftRev = obj.num("draft_rev")
        val state = GroupSendRecordState.valueOf(obj.str("state"))
        val createdAt = obj.num("created_at")
        val receiptSeq = (obj["receipt_seq"] as? JsonPrimitive)?.longOrNull
        val receiptDriver = (obj["receipt_driver"] as? JsonPrimitive)?.booleanOrNull
        GroupSendRecord(
            operation = GroupSendOperation(
                target = GroupSendTarget(
                    identity = GroupSendIdentity(savedRow, endpointBinding, gateway),
                    room = room
                ),
                rawId = rawId,
                payload = GroupSendPayload(text, thread)
            ),
            draftRevision = draftRev,
            state = state,
            createdAtMillis = createdAt,
            receiptSeq = receiptSeq,
            receiptDriver = receiptDriver
        )
    }.getOrNull()

    private fun encodeRecord(r: GroupSendRecord): JsonObject = buildJsonObject {
        put("saved_row", r.operation.target.identity.savedRow)
        put("endpoint_binding", r.operation.target.identity.endpointBinding)
        put("gateway", r.operation.target.identity.gateway)
        put("room", r.operation.target.room)
        put("raw_id", r.operation.rawId)
        put("text", r.operation.payload.text)
        put("thread", r.operation.payload.thread)
        put("draft_rev", r.draftRevision)
        put("state", r.state.name)
        put("created_at", r.createdAtMillis)
        r.receiptSeq?.let { put("receipt_seq", it) }
        r.receiptDriver?.let { put("receipt_driver", it) }
    }
}

internal class AndroidGroupSendStore(
    context: Context,
    storageFileName: String = "group_send_store.json",
) : GroupSendStore {
    private val file = File(context.noBackupFilesDir, storageFileName)
    private val atomicFile = AtomicFile(file)
    private val mutationMutex = Mutex()
    private val state = MutableStateFlow(readSnapshot())

    override val snapshotFlow: Flow<GroupSendStoreSnapshot> = state.asStateFlow()

    override suspend fun snapshot(): GroupSendStoreSnapshot = mutationMutex.withLock { readSnapshot().also { state.value = it } }

    override suspend fun persistDraftAndPrepare(draft: GroupSendEditorDraft, record: GroupSendRecord): GroupSendStoreMutation = mutate { current ->
        if (!draft.matches(record) || draft.draftKey in current.tombstones) {
            return@mutate GroupSendStoreMutation.ConflictingRecord to current
        }
        val existing = current.records[record.recordKey]
        if (existing != null && existing.operation.payload != record.operation.payload) {
            return@mutate GroupSendStoreMutation.ConflictingRecord to current
        }
        if (existing == null && current.records.size >= MAX_STORE_RECORDS) return@mutate GroupSendStoreMutation.CapacityReached to current
        val existingDraft = current.drafts[draft.draftKey]
        if (existing == null && existingDraft != null && existingDraft.revision > draft.revision) {
            return@mutate GroupSendStoreMutation.ConflictingRecord to current
        }
        val retainedDraft = if (existingDraft != null && existingDraft.revision > draft.revision) existingDraft else draft
        GroupSendStoreMutation.Applied to current.copy(
            drafts = current.drafts + (draft.draftKey to retainedDraft),
            records = current.records + (record.recordKey to (existing ?: record)),
        )
    }

    override suspend fun prepare(record: GroupSendRecord): GroupSendStoreMutation = mutate { current ->
        val scopedRoom = GroupSendScope(record.operation.target.identity, record.operation.target.room).roomKey
        if (scopedRoom in current.tombstones) return@mutate GroupSendStoreMutation.ConflictingRecord to current
        val existing = current.records[record.recordKey]
        if (existing != null) {
            if (existing.operation.payload != record.operation.payload) return@mutate GroupSendStoreMutation.ConflictingRecord to current
            return@mutate GroupSendStoreMutation.Applied to current
        }
        if (current.records.size >= MAX_STORE_RECORDS) return@mutate GroupSendStoreMutation.CapacityReached to current
        GroupSendStoreMutation.Applied to current.copy(records = current.records + (record.recordKey to record))
    }

    override suspend fun markUncertain(recordKey: String): GroupSendStoreMutation = mutate { current ->
        val existing = current.records[recordKey] ?: return@mutate GroupSendStoreMutation.NotFound to current
        GroupSendStoreMutation.Applied to current.copy(records = current.records + (recordKey to existing.copy(state = GroupSendRecordState.Uncertain)))
    }

    override suspend fun markConfirmed(recordKey: String, receipt: GroupSendReceipt): GroupSendStoreMutation = mutate { current ->
        val existing = current.records[recordKey] ?: return@mutate GroupSendStoreMutation.NotFound to current
        GroupSendStoreMutation.Applied to current.copy(records = current.records + (recordKey to existing.copy(
            state = GroupSendRecordState.Confirmed,
            receiptSeq = receipt.sequence,
            receiptDriver = receipt.driverStarted
        )))
    }

    override suspend fun markBlocked(recordKey: String): GroupSendStoreMutation = mutate { current ->
        val existing = current.records[recordKey] ?: return@mutate GroupSendStoreMutation.NotFound to current
        GroupSendStoreMutation.Applied to current.copy(records = current.records + (recordKey to existing.copy(state = GroupSendRecordState.Blocked)))
    }

    override suspend fun tombstoneRoom(scopedRoomKey: String): GroupSendStoreMutation = mutate { current ->
        GroupSendStoreMutation.Applied to current.copy(tombstones = current.tombstones + scopedRoomKey)
    }

    override suspend fun remove(recordKey: String): GroupSendStoreMutation = mutate { current ->
        if (recordKey !in current.records) return@mutate GroupSendStoreMutation.NotFound to current
        GroupSendStoreMutation.Applied to current.copy(records = current.records - recordKey)
    }

    private suspend fun mutate(
        op: (GroupSendStoreSnapshot) -> Pair<GroupSendStoreMutation, GroupSendStoreSnapshot>
    ): GroupSendStoreMutation = mutationMutex.withLock {
        val current = readSnapshot()
        if (!current.valid) return GroupSendStoreMutation.StorageUnavailable
        val (result, next) = op(current)
        if (result != GroupSendStoreMutation.Applied || next == current) return result
        val encoded = GroupSendStoreCodec.encodeOrNull(next) ?: return GroupSendStoreMutation.CapacityReached
        return try {
            val output = atomicFile.startWrite()
            try {
                output.write(encoded.toByteArray(Charsets.UTF_8))
                output.fd.sync()
                atomicFile.finishWrite(output)
            } catch (failure: Throwable) {
                atomicFile.failWrite(output)
                throw failure
            }
            state.value = next
            GroupSendStoreMutation.Applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GroupSendStoreMutation.StorageUnavailable
        }
    }

    private fun readSnapshot(): GroupSendStoreSnapshot {
        return try {
            // AtomicFile must recover a legacy backup before testing for an absent store.
            atomicFile.openRead().use { input ->
                val bytes = ByteArray(MAX_STORE_SERIALIZED_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > MAX_STORE_SERIALIZED_BYTES) GroupSendStoreSnapshot(valid = false)
                else GroupSendStoreCodec.decode(String(bytes, 0, count, Charsets.UTF_8))
            }
        } catch (_: FileNotFoundException) {
            GroupSendStoreSnapshot(valid = !file.exists() && !File(file.path + ".bak").exists())
        } catch (_: IOException) {
            GroupSendStoreSnapshot(valid = false)
        }
    }
}
