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

    @Test fun separateStoreInstancesRecoverAcceptedIdentity() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = sampleMessage().copy(conversation = sampleMessage().conversation.copy(endpoint = "durability-case"))
        val store = AndroidNotificationCheckpointStore(context)
        store.save(message.conversation.endpoint, NotificationCheckpoint())
        assertTrue(NotificationInbox(store).accept(message) {})
        assertFalse(NotificationInbox(AndroidNotificationCheckpointStore(context)).accept(message) { fail("replayed") })
    }
}
