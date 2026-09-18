package com.hermesagent.mobile.plugins.groups

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class GroupSendBackupRecoveryTest {
    @Test fun `oversized disk state fails closed and is not replaced`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "group-send-oversize-recovery.json")
        file.parentFile!!.mkdirs()
        val content = ByteArray(128 * 1024 + 1) { ' '.code.toByte() }
        try {
            file.writeBytes(content)
            val store = AndroidGroupSendStore(context, file.name)
            assertFalse(store.snapshot().valid)
            assertEquals(GroupSendStoreMutation.StorageUnavailable, store.tombstoneRoom("synthetic-room"))
            assertTrue(content.contentEquals(file.readBytes()))
        } finally {
            file.delete()
        }
    }

    @Test fun `absent store is a valid empty snapshot`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "group-send-absent-recovery.json")
        assertFalse(file.exists())
        val state = AndroidGroupSendStore(context, file.name).snapshot()
        assertTrue(state.valid)
        assertTrue(state.records.isEmpty())
    }

    @Test fun `backup-only committed outbox survives recovery`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.noBackupFilesDir, "group-send-backup-recovery.json")
        val backup = File(file.path + ".bak")
        val target = GroupSendTarget(GroupSendIdentity("saved", "binding", "gateway"), "room")
        val record = GroupSendRecord(
            GroupSendOperation(target, "event", GroupSendPayload.normalize("Keep this send", "thread")),
            draftRevision = 1L,
            state = GroupSendRecordState.Prepared,
            createdAtMillis = 1L,
        )
        try {
            val store = AndroidGroupSendStore(context, file.name)
            assertEquals(GroupSendStoreMutation.Applied, store.prepare(record))
            // Models the recoverable backup-only state after an interrupted legacy AtomicFile write.
            assertTrue(file.renameTo(backup))
            assertFalse(file.exists())
            val recovered = AndroidGroupSendStore(context, file.name).snapshot()
            assertTrue(recovered.valid)
            assertEquals(record, recovered.records[record.recordKey])
        } finally {
            file.delete()
            backup.delete()
            File(file.path + ".new").delete()
        }
    }
}
