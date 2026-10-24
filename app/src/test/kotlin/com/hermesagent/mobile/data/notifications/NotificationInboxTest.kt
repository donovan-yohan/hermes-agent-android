package com.hermesagent.mobile.data.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NotificationInboxTest {
    @Test fun preCancelledAdmissionCannotPostBeforeCancellationIsObserved() = runTest {
        val context = kotlinx.coroutines.Job().apply { cancel() }
        var deliveries = 0
        try {
            kotlinx.coroutines.withContext(context) { NotificationInbox(MemoryNotificationCheckpoints()).accept(sampleMessage()) { deliveries++ } }
        } catch (_: CancellationException) { }
        assertEquals(0, deliveries)
    }

    @Test fun concurrentLiveAndRecoveryShareOneAdmissionAndNoOpCountsAsSeen() = runTest {
        val store = MemoryNotificationCheckpoints()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = async { NotificationInbox(store).accept(sampleMessage()) { entered.complete(Unit); release.await() } }
        entered.await()
        val second = async { NotificationInbox(store).accept(sampleMessage()) { fail("duplicate") } }
        release.complete(Unit)
        assertTrue(first.await()); assertFalse(second.await())
        assertTrue(NotificationInbox(store).accept(sampleMessage("quiet")) { })
        assertFalse(NotificationInbox(store).accept(sampleMessage("quiet")) { fail("quiet replay") })
    }
    @Test fun identityRetentionIsBounded() = runTest {
        val store = MemoryNotificationCheckpoints()
        val inbox = NotificationInbox(store)
        repeat(4100) { inbox.accept(sampleMessage(it.toString())) {} }
        assertEquals(4096, store.value.accepted.size)
    }

    @Test fun restartDeduplicatesLiveAndRecoveredIdentityWithoutSavingText() = runTest {
        val store = MemoryNotificationCheckpoints()
        val message = sampleMessage()
        var deliveries = 0
        assertTrue(NotificationInbox(store).accept(message) { deliveries++ })
        assertFalse(NotificationInbox(store).accept(message.copy(text = "edited")) { deliveries++ })
        assertEquals(1, deliveries)
        assertFalse(store.value.toString().contains(message.text))
        assertTrue(NotificationInbox(store).accept(message.copy(senderId = "other")) { deliveries++ })
        assertTrue(NotificationInbox(store).accept(message.copy(conversation = message.conversation.copy(endpoint = "other"))) { deliveries++ })
    }

    @Test fun deliveryFailureDoesNotAcknowledge() = runTest {
        val store = MemoryNotificationCheckpoints()
        val inbox = NotificationInbox(store)
        try { inbox.accept(sampleMessage()) { throw CancellationException("stopped") }; fail() }
        catch (_: CancellationException) { }
        assertTrue(inbox.accept(sampleMessage()) {})
    }
}

internal class MemoryNotificationCheckpoints : NotificationCheckpointStore {
    var value = NotificationCheckpoint()
    private val values = mutableMapOf<String, NotificationCheckpoint>()
    override suspend fun load(endpoint: String) = values[endpoint] ?: NotificationCheckpoint()
    override suspend fun save(endpoint: String, checkpoint: NotificationCheckpoint) {
        value = checkpoint
        values[endpoint] = checkpoint
    }
}

internal fun sampleMessage(id: String = "42") = BotMessage(
    MessageConversation("saved-endpoint", MessageConversationKind.Session, "qualified-session", "bot"),
    id, "owner", "Bot", "bot", "Conversation", "private reply body", 1000,
)
