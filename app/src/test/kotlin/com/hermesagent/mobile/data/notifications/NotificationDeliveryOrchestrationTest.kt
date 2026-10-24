package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.GatewayTurnOutcome
import com.hermesagent.mobile.plugins.bots.BotStorageEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationDeliveryOrchestrationTest {
    private val row = SavedConnection("captured", "Synthetic", ConnectionKind.Remote)
    private fun outcome(id: String? = "1", failed: Boolean = false) = GatewayTurnOutcome(
        "qualified-chat", failed, "Exact reply", completedMessageId = id,
        completedMessageOwner = "bot", completedAtMillis = 123, endpointGeneration = 7)

    @Test fun endpointAbortDoesNotRetireCollectorBeforeNextOutcome() = runTest {
        val posts = mutableListOf<String>()
        val store = object : NotificationCheckpointStore {
            var checkpoint = NotificationCheckpoint()
            override suspend fun load(endpoint: String) = checkpoint
            override suspend fun save(endpoint: String, checkpoint: NotificationCheckpoint) { this.checkpoint = checkpoint }
        }
        var first = true
        val notifier = MessageNotifications(NotificationInbox(store)::accept, { "endpoint" },
            TransientNotificationPreferences(), NotificationPresence()) { message, _ ->
            if (first) { first = false; throw CancellationException("Notification endpoint changed") }
            posts += message.messageId
        }
        var failure: Throwable? = null
        val collector = backgroundScope.launch {
            try {
                collectBotMessageOutcomes(flowOf(outcome("1"), outcome("2")), { value ->
                    notifier.acceptOutcome(value, BotStorageEndpoint("endpoint", 7), "Title", "Bot")
                }, {})
            } catch (caught: Throwable) { failure = caught }
        }
        runCurrent()
        assertEquals("later persisted reply must still reach the real inbox and presenter", listOf("2"), posts)
        assertNull(failure)
        assertEquals(1, store.checkpoint.accepted.size)
        assertTrue(collector.isCompleted)
    }

    @Test fun storeFailureDoesNotRetireCollectorBeforeNextOutcome() = runTest {
        val posts = mutableListOf<String>()
        val store = object : NotificationCheckpointStore {
            var loads = 0
            var checkpoint = NotificationCheckpoint()
            override suspend fun load(endpoint: String): NotificationCheckpoint {
                if (++loads == 1) throw java.io.IOException("checkpoint temporarily unreadable")
                return checkpoint
            }
            override suspend fun save(endpoint: String, checkpoint: NotificationCheckpoint) { this.checkpoint = checkpoint }
        }
        val notifier = MessageNotifications(NotificationInbox(store)::accept, { "endpoint" },
            TransientNotificationPreferences(), NotificationPresence()) { message, _ -> posts += message.messageId }
        var failure: Throwable? = null
        backgroundScope.launch {
            try {
                collectBotMessageOutcomes(flowOf(outcome("1"), outcome("2")), { value ->
                    notifier.acceptOutcome(value, BotStorageEndpoint("endpoint", 7), "Title", "Bot")
                }, {})
            } catch (caught: Throwable) { failure = caught }
        }
        runCurrent()
        assertEquals(listOf("2"), posts)
        assertNull(failure)
        assertEquals(2, store.loads)
        assertEquals(1, store.checkpoint.accepted.size)
    }

    @Test fun collectorPreservesActualJobCancellation() = runTest {
        val reached = CompletableDeferred<Unit>()
        val attempted = mutableListOf<String?>()
        var scans = 0
        val collector = backgroundScope.launch {
            collectBotMessageOutcomes(flowOf(outcome("1"), outcome("2")), { value ->
                attempted += value.completedMessageId
                reached.complete(Unit)
                awaitCancellation()
            }, { scans++ })
        }
        runCurrent()
        assertTrue(reached.isCompleted)
        collector.cancelAndJoin()
        assertTrue(collector.isCancelled)
        assertEquals(listOf("1"), attempted)
        assertEquals(0, scans)
    }

    @Test fun missingIdentityStillReconcilesAfterScopeAbort() = runTest {
        val attempted = mutableListOf<String?>()
        var scans = 0
        var failure: Throwable? = null
        backgroundScope.launch {
            try {
                collectBotMessageOutcomes(flowOf(outcome("1"), outcome(null)), { value ->
                    attempted += value.completedMessageId
                    if (value.completedMessageId != null) throw CancellationException("scope replaced")
                }, { scans++ })
            } catch (caught: Throwable) { failure = caught }
        }
        runCurrent()
        assertEquals(listOf("1", null), attempted)
        assertNull(failure)
        assertEquals(1, scans)
    }

    @Test fun onlySuccessfulOutcomeWithoutPersistedIdentityRequestsReconciliation() = runTest {
        val attempted = mutableListOf<GatewayTurnOutcome>()
        var scans = 0
        collectBotMessageOutcomes(flowOf(
            outcome("1"), outcome(null), outcome("2", failed = true), outcome(null, failed = true)),
            { attempted += it }, { scans++ })
        assertEquals(4, attempted.size)
        assertEquals("persisted replies and errors must not launch catch-up scans", 1, scans)
    }

    @Test fun replacedRouteReturnsWithoutEverObservingCapturedRow() = runTest {
        val routes = MutableStateFlow<SavedConnection?>(null)
        val awaiting = backgroundScope.async { awaitNotificationRoute(routes, row) }
        runCurrent()
        routes.value = row.copy(id = "replacement")
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("obsolete route wait must finish within five seconds", awaiting.isCompleted)
        assertFalse(awaiting.await())
    }

    @Test fun routeThatNeverInitializesAlsoTimesOut() = runTest {
        val awaiting = backgroundScope.async { awaitNotificationRoute(MutableStateFlow(null), row) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("uninitialized route must be bounded", awaiting.isCompleted)
        assertFalse(awaiting.await())
    }

    @Test fun capturedRouteCanArriveBeforeDeadline() = runTest {
        val routes = MutableStateFlow<SavedConnection?>(null)
        val awaiting = backgroundScope.async { awaitNotificationRoute(routes, row) }
        runCurrent()
        advanceTimeBy(1_000)
        routes.value = row
        runCurrent()
        assertTrue(awaiting.await())
        assertEquals(1_000, testScheduler.currentTime)
    }

    @Test fun routeWaitPreservesCallerCancellation() = runTest {
        val awaiting = backgroundScope.async { awaitNotificationRoute(MutableStateFlow(null), row) }
        runCurrent()
        awaiting.cancelAndJoin()
        assertTrue(awaiting.isCancelled)
        assertEquals(0, testScheduler.currentTime)
    }
}
