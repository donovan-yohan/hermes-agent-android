package com.hermesagent.mobile.data.composer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerQueueControllerTest {
    @Test
    fun `accepted queued submission removes only its acknowledged FIFO head`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Accepted)
        val controller = ComposerQueueController(store, gateway, clock = { 10 }, entryId = ids("one", "two"))
        controller.enqueue("durable", "first")
        controller.enqueue("durable", "second")

        assertEquals(ComposerQueueDrainResult.Accepted, controller.drainIfIdle("durable", isIdle = true))
        assertEquals(listOf("first"), gateway.texts)
        assertEquals(listOf("two"), controller.queue("durable").map(QueuedPrompt::id))
    }

    @Test
    fun `rejected send stays exact then auto drain is bounded while manual send may retry`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Rejected)
        val controller = ComposerQueueController(
            store,
            gateway,
            entryId = ids("one"),
            maxAutoFailuresPerEntry = 2,
        )
        controller.enqueue("durable", "keep me")

        assertEquals(ComposerQueueDrainResult.Rejected, controller.drainIfIdle("durable", true))
        assertEquals(ComposerQueueDrainResult.Rejected, controller.drainIfIdle("durable", true))
        assertEquals(ComposerQueueDrainResult.RetryLimitReached, controller.drainIfIdle("durable", true))
        assertEquals(ComposerQueueDrainResult.Rejected, controller.sendNextWhenIdle("durable", "one", true))
        assertEquals(listOf("one"), controller.queue("durable").map(QueuedPrompt::id))
        assertEquals(3, gateway.texts.size)
    }

    @Test
    fun `ambiguous acknowledgement marks review and blocks every automatic retry`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Ambiguous)
        val controller = ComposerQueueController(store, gateway, entryId = ids("one"))
        controller.enqueue("durable", "do not duplicate")

        assertEquals(ComposerQueueDrainResult.Ambiguous, controller.drainIfIdle("durable", true))
        assertEquals(QueuedPromptDelivery.Ambiguous, controller.queue("durable").single().delivery)
        assertEquals(ComposerQueueDrainResult.ReviewRequired, controller.drainIfIdle("durable", true))
        assertEquals(1, gateway.texts.size)

        gateway.outcome = QueueSubmissionOutcome.Accepted
        assertEquals(ComposerQueueMutation.Applied, controller.markReadyAfterReview("durable", "one"))
        assertEquals(ComposerQueueDrainResult.Accepted, controller.drainIfIdle("durable", true))
        assertTrue(controller.queue("durable").isEmpty())
    }

    @Test
    fun `redirect ambiguity can persist a review-required queue record without submitting it`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Accepted)
        val controller = ComposerQueueController(store, gateway, entryId = ids("redirect"))

        controller.enqueue("durable", "fallback text", delivery = QueuedPromptDelivery.Ambiguous)

        assertEquals(ComposerQueueDrainResult.ReviewRequired, controller.drainIfIdle("durable", true))
        assertEquals(0, gateway.texts.size)
        assertEquals(QueuedPromptDelivery.Ambiguous, controller.queue("durable").single().delivery)
    }

    @Test
    fun `single drain mutex prevents concurrent duplicate submits`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = BlockingSubmitter()
        val controller = ComposerQueueController(store, gateway, entryId = ids("one"))
        controller.enqueue("durable", "only once")

        val first = async { controller.drainIfIdle("durable", true) }
        runCurrent()
        val second = async { controller.drainIfIdle("durable", true) }
        runCurrent()
        assertEquals(1, gateway.calls)

        gateway.reply.complete(QueueSubmissionOutcome.Accepted)
        assertEquals(ComposerQueueDrainResult.Accepted, first.await())
        assertEquals(ComposerQueueDrainResult.NoEntry, second.await())
        assertEquals(1, gateway.calls)
    }

    @Test
    fun `park moves during rehome but cannot survive a fresh controller`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Accepted)
        val controller = ComposerQueueController(store, gateway, entryId = ids("one"))
        controller.enqueue("old", "resume later")
        controller.park("old")

        assertEquals(ComposerQueueDrainResult.Parked, controller.drainIfIdle("old", true))
        assertEquals(ComposerQueueMutation.Applied, controller.migrate("old", "new"))
        assertTrue(controller.isParked("new"))
        assertEquals(ComposerQueueDrainResult.Parked, controller.drainIfIdle("new", true))

        val afterProcessRecreation = ComposerQueueController(store, gateway)
        assertTrue(!afterProcessRecreation.isParked("new"))
        assertEquals(ComposerQueueDrainResult.Accepted, afterProcessRecreation.drainIfIdle("new", true))
    }

    @Test
    fun `profile scope reset clears parked edit and retry state before durable ids can overlap`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Rejected)
        val controller = ComposerQueueController(
            store,
            gateway,
            entryId = ids("same-id"),
            maxAutoFailuresPerEntry = 2,
        )
        controller.enqueue("same-durable-id", "profile scoped")
        assertEquals(ComposerQueueDrainResult.Rejected, controller.drainIfIdle("same-durable-id", true))
        assertEquals(ComposerQueueDrainResult.Rejected, controller.drainIfIdle("same-durable-id", true))
        controller.park("same-durable-id")
        requireNotNull(controller.beginEdit("same-durable-id", "same-id", "old profile draft"))

        controller.resetTransientScopeState()

        assertTrue(controller.parkedDurableIds.value.isEmpty())
        assertTrue(!controller.isParked("same-durable-id"))
        gateway.outcome = QueueSubmissionOutcome.Accepted
        assertEquals(ComposerQueueDrainResult.Accepted, controller.drainIfIdle("same-durable-id", true))
    }

    @Test
    fun `edit blocks drain and cancel restores only original text draft`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Accepted)
        val controller = ComposerQueueController(store, gateway, entryId = ids("one"))
        controller.enqueue("durable", "queued")
        val snapshot = requireNotNull(controller.beginEdit("durable", "one", "draft before edit"))

        assertEquals(ComposerQueueDrainResult.Editing, controller.drainIfIdle("durable", true))
        assertEquals("draft before edit", controller.cancelEdit(snapshot))
        assertEquals(ComposerQueueDrainResult.Accepted, controller.drainIfIdle("durable", true))
    }

    @Test
    fun `send next promotes selected FIFO entry before its queued submit`() = runTest {
        val store = TransientComposerQueueStore()
        val gateway = RecordingSubmitter(QueueSubmissionOutcome.Accepted)
        val controller = ComposerQueueController(store, gateway, entryId = ids("one", "two"))
        controller.enqueue("durable", "first")
        controller.enqueue("durable", "second")

        assertEquals(ComposerQueueDrainResult.Accepted, controller.sendNextWhenIdle("durable", "two", true))
        assertEquals(listOf("second"), gateway.texts)
        assertEquals(listOf("one"), controller.queue("durable").map(QueuedPrompt::id))
    }

    private fun ids(vararg ids: String): () -> String {
        val values = ids.iterator()
        return { values.next() }
    }

    private class RecordingSubmitter(var outcome: QueueSubmissionOutcome) : ComposerQueueSubmitter {
        val texts = mutableListOf<String>()
        override suspend fun submitQueued(durableSessionId: String, text: String): QueueSubmissionOutcome {
            texts += text
            return outcome
        }
    }

    private class BlockingSubmitter : ComposerQueueSubmitter {
        val reply = CompletableDeferred<QueueSubmissionOutcome>()
        var calls = 0
        override suspend fun submitQueued(durableSessionId: String, text: String): QueueSubmissionOutcome {
            calls += 1
            return reply.await()
        }
    }
}
