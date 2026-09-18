package com.hermesagent.mobile.plugins.groups

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val testIdentity = GroupSendIdentity("saved-1", "binding-1", "gateway-1")
    private val testTarget = GroupSendTarget(testIdentity, "room-1")

    private fun storeFile(): File = File(context.noBackupFilesDir, "group-send-test-${System.nanoTime()}.json")

    private fun sampleRecord(rawId: String = "client-1", text: String = "Hello", rev: Long = 1L): GroupSendRecord {
        return GroupSendRecord(
            operation = GroupSendOperation(
                target = testTarget,
                rawId = rawId,
                payload = GroupSendPayload.normalize(text, "thread-1")
            ),
            draftRevision = rev,
            state = GroupSendRecordState.Prepared,
            createdAtMillis = 1000L
        )
    }

    @Test
    fun `codec encodes and decodes records and tombstones accurately`() {
        val r1 = sampleRecord("id-1", "Text 1", 1)
        val r2 = sampleRecord("id-2", "Text 2", 2).copy(state = GroupSendRecordState.Uncertain)
        val snapshot = GroupSendStoreSnapshot(
            records = mapOf(r1.recordKey to r1, r2.recordKey to r2),
            tombstones = setOf("saved-1:binding-1:room-1")
        )

        val encoded = GroupSendStoreCodec.encodeOrNull(snapshot)
        assertNotNull(encoded)
        val decoded = GroupSendStoreCodec.decode(encoded)

        assertEquals(snapshot.tombstones, decoded.tombstones)
        assertEquals(2, decoded.records.size)
        assertEquals(r1, decoded.records[r1.recordKey])
        assertEquals(r2, decoded.records[r2.recordKey])
    }

    @Test
    fun `codec fails closed on corrupt or unknown version string`() {
        val r1 = sampleRecord("id-1", "Text 1", 1)
        val snapshot = GroupSendStoreSnapshot(records = mapOf(r1.recordKey to r1))
        val encoded = requireNotNull(GroupSendStoreCodec.encodeOrNull(snapshot))

        val badVersion = encoded.replace("\"version\":\"1\"", "\"version\":\"99\"")
        val decodedBadVersion = GroupSendStoreCodec.decode(badVersion)
        assertFalse(decodedBadVersion.valid)
        assertTrue(decodedBadVersion.records.isEmpty())
        assertTrue(decodedBadVersion.tombstones.isEmpty())

        val corrupt = encoded.substring(0, encoded.length / 2)
        val decodedCorrupt = GroupSendStoreCodec.decode(corrupt)
        assertFalse(decodedCorrupt.valid)
        assertTrue(decodedCorrupt.records.isEmpty())
    }

    @Test
    fun `codec rejects noncanonical immutable text rather than rewriting accepted payload`() {
        val record = sampleRecord(text = "Immutable text")
        val snapshot = GroupSendStoreSnapshot(records = mapOf(record.recordKey to record))
        val encoded = requireNotNull(GroupSendStoreCodec.encodeOrNull(snapshot))
        assertEquals(record, GroupSendStoreCodec.decode(encoded).records[record.recordKey])
        for (text in listOf(" Immutable text", "Immutable text ", "\u001cImmutable text")) {
            val tampered = encoded.replace(
                "\"text\":\"Immutable text\"",
                "\"text\":${kotlinx.serialization.json.JsonPrimitive(text)}",
            )
            assertNotEquals(encoded, tampered)
            val decoded = GroupSendStoreCodec.decode(tampered)
            assertFalse("Noncanonical persisted payload must fail closed", decoded.valid)
            assertTrue(decoded.records.isEmpty())
        }
    }

    @Test
    fun `prepare handles identical retry and rejects conflicting payload on same rawId`() = runTest {
        val store = TransientGroupSendStore()
        val r1 = sampleRecord("id-1", "Same text")
        val rConflict = sampleRecord("id-1", "Changed text")

        assertEquals(GroupSendStoreMutation.Applied, store.prepare(r1))
        // Same payload re-prepared: Applied
        assertEquals(GroupSendStoreMutation.Applied, store.prepare(r1))
        // Changed payload on same rawId: ConflictingRecord
        assertEquals(GroupSendStoreMutation.ConflictingRecord, store.prepare(rConflict))
    }

    @Test
    fun `tombstone rejects prepare into tombstoned room`() = runTest {
        val store = TransientGroupSendStore()
        val scopedRoom = GroupSendScope(testIdentity, testTarget.room).roomKey
        assertEquals(GroupSendStoreMutation.Applied, store.tombstoneRoom(scopedRoom))

        val r = sampleRecord("id-1", "Text")
        assertEquals(GroupSendStoreMutation.ConflictingRecord, store.prepare(r))
    }

    @Test
    fun `same raw id stays isolated across identity room and gateway replacement`() = runTest {
        val firstTarget = GroupSendTarget(GroupSendIdentity("row-1", "binding-1", "gateway-a"), "room-a")
        val secondTarget = GroupSendTarget(GroupSendIdentity("row-2", "binding:2", "gateway-b"), "room:b")
        val replacementTarget = GroupSendTarget(GroupSendIdentity("row-1", "binding-1", "gateway-replaced"), "room-a")
        val first = sampleRecord("same-raw", "first").copy(operation = GroupSendOperation(firstTarget, "same-raw", GroupSendPayload.normalize("first", "thread-1")))
        val second = first.copy(operation = GroupSendOperation(secondTarget, "same-raw", GroupSendPayload.normalize("second", "thread-1")))
        val replacement = first.copy(operation = GroupSendOperation(replacementTarget, "same-raw", GroupSendPayload.normalize("replacement", "thread-1")))

        assertNotEquals(first.recordKey, second.recordKey)
        assertNotEquals(first.recordKey, replacement.recordKey)
        assertNotEquals(GroupSendScope(firstTarget.identity, firstTarget.room).roomKey, GroupSendScope(replacementTarget.identity, replacementTarget.room).roomKey)

        val store = TransientGroupSendStore()
        val firstDraft = GroupSendEditorDraft(firstTarget, "first", "thread-1", 1L)
        val secondDraft = GroupSendEditorDraft(secondTarget, "second", "thread-1", 1L)
        val replacementDraft = GroupSendEditorDraft(replacementTarget, "replacement", "thread-1", 1L)
        val roomVariantTarget = GroupSendTarget(GroupSendIdentity("row-1", "binding-1", "gateway-a"), "room-b")
        val roomVariant = first.copy(operation = GroupSendOperation(roomVariantTarget, "same-raw", GroupSendPayload.normalize("room variant", "thread-1")))
        val roomVariantDraft = GroupSendEditorDraft(roomVariantTarget, "room variant", "thread-1", 1L)
        assertEquals(GroupSendStoreMutation.Applied, store.persistDraftAndPrepare(firstDraft, first))
        assertEquals(GroupSendStoreMutation.Applied, store.persistDraftAndPrepare(roomVariantDraft, roomVariant))
        assertNotEquals(first.recordKey, roomVariant.recordKey)
        assertEquals(GroupSendStoreMutation.Applied, store.persistDraftAndPrepare(secondDraft, second))
        assertEquals(GroupSendStoreMutation.Applied, store.tombstoneRoom(firstDraft.draftKey))
        assertEquals(GroupSendStoreMutation.Applied, store.persistDraftAndPrepare(replacementDraft, replacement))
        assertEquals(4, store.snapshot().records.size)
        assertEquals("replacement", store.snapshot().drafts[replacementDraft.draftKey]?.text)
    }

    @Test
    fun `older replay cannot overwrite newer editor revision`() = runTest {
        val store = TransientGroupSendStore()
        val target = testTarget
        val newer = GroupSendEditorDraft(target, "newer editor", "thread-1", 2L)
        val older = GroupSendEditorDraft(target, "older editor", "thread-1", 1L)
        val olderRecord = sampleRecord("old-send", "older editor", 1L)
        val newerRecord = sampleRecord("new-send", "newer editor", 2L)
        assertEquals(GroupSendStoreMutation.Applied, store.persistDraftAndPrepare(newer, newerRecord))
        assertEquals(GroupSendStoreMutation.Applied, store.persistDraftAndPrepare(older, olderRecord))
        assertEquals("newer editor", store.snapshot().drafts[newer.draftKey]?.text)
        assertEquals(2L, store.snapshot().drafts[newer.draftKey]?.revision)
    }

    @Test
    fun `state transitions from prepared to uncertain, confirmed, and removal`() = runTest {
        val store = TransientGroupSendStore()
        val r = sampleRecord("id-1", "Text")

        assertEquals(GroupSendStoreMutation.Applied, store.prepare(r))
        assertEquals(GroupSendRecordState.Prepared, store.snapshot().records[r.recordKey]?.state)

        assertEquals(GroupSendStoreMutation.Applied, store.markUncertain(r.recordKey))
        assertEquals(GroupSendRecordState.Uncertain, store.snapshot().records[r.recordKey]?.state)

        val receipt = GroupSendReceipt("user:fixture", 12L, true)
        assertEquals(GroupSendStoreMutation.Applied, store.markConfirmed(r.recordKey, receipt))
        val confirmed = store.snapshot().records[r.recordKey]
        assertEquals(GroupSendRecordState.Confirmed, confirmed?.state)
        assertEquals(12L, confirmed?.receiptSeq)
        assertTrue(confirmed?.receiptDriver == true)

        assertEquals(GroupSendStoreMutation.Applied, store.remove(r.recordKey))
        assertNull(store.snapshot().records[r.recordKey])
    }

    @Test
    fun `storage failure hook fails closed`() = runTest {
        val store = TransientGroupSendStore()
        store.failWrites = true
        val r = sampleRecord("id-1", "Text")
        assertEquals(GroupSendStoreMutation.StorageUnavailable, store.prepare(r))
        assertTrue(store.snapshot().records.isEmpty())
    }

    @Test
    fun `atomic file survives close and reopen with editor draft and send snapshot`() = runTest {
        val file = storeFile()
        try {
            val first = AndroidGroupSendStore(context, file.name)
            val record = sampleRecord("id-persist", "Immutable text", 7L)
            val draft = GroupSendEditorDraft(testTarget, "Immutable text", "thread-1", 7L)
            assertEquals(GroupSendStoreMutation.Applied, first.persistDraftAndPrepare(draft, record))
            assertTrue(file.exists())

            val reopened = AndroidGroupSendStore(context, file.name)
            val restored = reopened.snapshot()
            assertTrue(restored.valid)
            assertEquals(draft, restored.drafts[draft.draftKey])
            assertEquals(record, restored.records[record.recordKey])
            assertEquals(GroupSendRecordState.Prepared, restored.records[record.recordKey]?.state)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `corrupt atomic file refuses writes without minting replacement state`() = runTest {
        val file = storeFile()
        try {
            file.parentFile?.mkdirs()
            file.writeText("{not-json")
            val store = AndroidGroupSendStore(context, file.name)
            val record = sampleRecord("id-corrupt", "Must not send")
            val draft = GroupSendEditorDraft(testTarget, "Must not send", "thread-1", 1L)
            assertFalse(store.snapshot().valid)
            assertEquals(GroupSendStoreMutation.StorageUnavailable, store.persistDraftAndPrepare(draft, record))
            assertEquals("{not-json", file.readText())
        } finally {
            file.delete()
        }
    }
}
