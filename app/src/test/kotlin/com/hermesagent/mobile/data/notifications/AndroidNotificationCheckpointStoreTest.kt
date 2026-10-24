package com.hermesagent.mobile.data.notifications

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidNotificationCheckpointStoreTest {
    @Test fun pollerContinuationAndInboxIdsRoundTripInOneAtomicRecord() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = NotificationCheckpoint(accepted = listOf("digest"), enrolledAt = 1000,
            sessionSweepCompleted = true, roomSweepCompleted = false,
            sessions = mapOf("app-id" to NotificationSessionCursor(10, 40, 90, 1000)),
            rooms = mapOf("room" to 2), roomCutoffs = mapOf("room" to 1000),
            sessionOffset = 80, sessionIndex = 3, roomOffset = 40, roomIndex = 2)
        AndroidNotificationCheckpointStore(context).save("cursor-case", expected)
        assertEquals(expected, AndroidNotificationCheckpointStore(context).load("cursor-case"))
    }

    @Test
    @Config(sdk = [26])
    fun backupOnlyInterruptedWriteRecoversDurableCheckpoint() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val endpoint = "backup-only"
        val expected = NotificationCheckpoint(accepted = listOf("already-delivered"), enrolledAt = 123)
        val store = AndroidNotificationCheckpointStore(context)
        store.save(endpoint, expected)
        val atomic = android.util.AtomicFile(checkpointFile(context, endpoint))
        // API 26 startWrite moves the committed base to .bak. Model process
        // death after the uncommitted base has disappeared, without failWrite.
        atomic.startWrite().use { it.write("unfinished".toByteArray()) }
        assertTrue(atomic.baseFile.delete())
        assertTrue(java.io.File(atomic.baseFile.path + ".bak").isFile)
        assertEquals(expected, AndroidNotificationCheckpointStore(context).load(endpoint))
        assertTrue(atomic.baseFile.isFile)
        assertFalse(java.io.File(atomic.baseFile.path + ".bak").exists())
    }

    @Test
    @Config(sdk = [26])
    fun genuinelyMissingCheckpointIsEmpty() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(NotificationCheckpoint(), AndroidNotificationCheckpointStore(context).load("never-saved"))
    }

    @Test
    @Config(sdk = [26])
    fun corruptCheckpointIsRejectedWithoutOverwritingItsBytes() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = checkpointFile(context, "corrupt")
        assertTrue(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val rejected = "not a checkpoint".toByteArray()
        file.writeBytes(rejected)
        assertTrue(runCatching { AndroidNotificationCheckpointStore(context).load("corrupt") }.isFailure)
        assertArrayEquals(rejected, file.readBytes())
    }

    @Test
    @Config(sdk = [26])
    fun inaccessibleExistingCheckpointIsNotTreatedAsMissing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = checkpointFile(context, "inaccessible")
        assertTrue(file.mkdirs()) // Opening a directory fails with FileNotFoundException too.
        val sentinel = java.io.File(file, "preserve").apply { writeText("rejected source") }
        assertTrue(runCatching { AndroidNotificationCheckpointStore(context).load("inaccessible") }.isFailure)
        assertEquals("rejected source", sentinel.readText())
    }

    private fun checkpointFile(context: Context, endpoint: String): java.io.File {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(context.noBackupFilesDir, "notification-checkpoints-v1/$key.json")
    }

    @Test fun separateStoreInstancesRecoverAcceptedIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = sampleMessage().copy(conversation = sampleMessage().conversation.copy(endpoint = "durability-case"))
        val store = AndroidNotificationCheckpointStore(context)
        store.save(message.conversation.endpoint, NotificationCheckpoint())
        assertTrue(NotificationInbox(store).accept(message) {})
        assertFalse(NotificationInbox(AndroidNotificationCheckpointStore(context)).accept(message) { fail("replayed") })
    }
}
