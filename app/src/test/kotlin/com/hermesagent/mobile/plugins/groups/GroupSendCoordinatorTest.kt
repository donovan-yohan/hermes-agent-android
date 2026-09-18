package com.hermesagent.mobile.plugins.groups

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSendCoordinatorTest {
    private val testIdentity = GroupSendIdentity("saved-1", "binding-1", "gateway-1")
    private val testTarget = GroupSendTarget(testIdentity, "room-1")

    private class FakeConnection(
        override val identity: GroupSendIdentity,
        var sendHandler: (GroupSendOperation) -> JsonElement = { op ->
            buildJsonObject {
                put("client_event_id", op.rawId)
                put("accepted", true)
                put("driver_started", true)
                put("event", buildJsonObject {
                    put("event_id", storedUserEventId(op.rawId))
                    put("room_id", op.target.room)
                    put("kind", "message.user")
                    put("actor", buildJsonObject { put("kind", "user"); put("id", "desktop") })
                    put("payload", op.payload.wire())
                    put("authority_epoch", 1)
                    put("created_at", 10.0)
                    put("idempotent", false)
                    put("seq", 1)
                })
            }
        },
        var refreshHandler: (String) -> GroupSendRefresh = { _ ->
            GroupSendRefresh(emptyList(), writable = true, worker = true)
        }
    ) : GroupSendConnection {
        val sendCalls = mutableListOf<GroupSendOperation>()
        override suspend fun refresh(room: String): GroupSendRefresh = refreshHandler(room)
        override suspend fun send(operation: GroupSendOperation): JsonElement {
            sendCalls += operation
            return sendHandler(operation)
        }
    }

    @Test
    fun `successful submit persists before wire and confirms`() = runTest {
        val store = TransientGroupSendStore()
        val connection = FakeConnection(testIdentity)
        val coordinator = GroupSendCoordinator(store, connection) { 1000L }

        val res = coordinator.submit(testTarget, "raw-1", "Hello world", "thread-1", 1L)
        assertTrue(res.isSuccess)
        val receipt = res.getOrThrow()
        assertEquals(storedUserEventId("raw-1"), receipt.storedId)
        assertEquals(1L, receipt.sequence)
        assertEquals(true, receipt.driverStarted)

        // Check store record is Confirmed
        val snapshot = store.snapshot()
        val rec = snapshot.records.values.single()
        assertEquals(GroupSendRecordState.Confirmed, rec.state)
        assertEquals(1L, rec.receiptSeq)
    }

    @Test
    fun `cancelled send completes contended uncertainty persistence before rethrowing original`() = runTest {
        val gate = Mutex(locked = true)
        val store = object : TransientGroupSendStore() {
            override suspend fun markUncertain(recordKey: String): GroupSendStoreMutation =
                gate.withLock { super.markUncertain(recordKey) }
        }
        val original = CancellationException("Synthetic send cancellation")
        lateinit var job: Job
        var observed: CancellationException? = null
        val connection = FakeConnection(testIdentity, sendHandler = {
            job.cancel(original)
            throw original
        })
        val coordinator = GroupSendCoordinator(store, connection)
        job = launch(start = CoroutineStart.LAZY) {
            try {
                coordinator.submit(testTarget, "cancelled", "Retain me", "thread-1", 1L)
            } catch (failure: CancellationException) {
                observed = failure
            }
        }
        job.start()
        runCurrent()
        gate.unlock()
        runCurrent()
        job.join()
        assertEquals(GroupSendRecordState.Uncertain, store.snapshot().records.values.single().state)
        assertEquals(GroupSendCoordinatorState.Uncertain, coordinator.executionState.first().status)
        // Coroutine stacktrace recovery may copy the exception while retaining its cause.
        assertTrue(generateSequence(observed as Throwable?) { it.cause }.any { it === original })
        assertEquals(1, connection.sendCalls.size)
    }

    @Test
    fun `expiry persistence failure stays uncertain and cannot reopen wire after storage recovery`() = runTest {
        for (failTombstone in listOf(true, false)) {
            var failing = true
            val store = object : TransientGroupSendStore() {
                override suspend fun tombstoneRoom(scopedRoomKey: String): GroupSendStoreMutation =
                    if (failing && failTombstone) GroupSendStoreMutation.StorageUnavailable
                    else super.tombstoneRoom(scopedRoomKey)

                override suspend fun markBlocked(recordKey: String): GroupSendStoreMutation =
                    if (failing && !failTombstone) GroupSendStoreMutation.StorageUnavailable
                    else super.markBlocked(recordKey)
            }
            val connection = FakeConnection(testIdentity, sendHandler = {
                throw GroupSendFailure(GroupSendProblem.Expired)
            })
            val coordinator = GroupSendCoordinator(store, connection)
            assertTrue(coordinator.submit(testTarget, "expired", "Old room", "thread-1", 1L).isFailure)
            assertEquals(GroupSendCoordinatorState.Uncertain, coordinator.executionState.first().status)
            assertEquals(GroupSendProblem.Expired, coordinator.executionState.first().problem)
            failing = false
            assertTrue(coordinator.submit(testTarget, "new-key", "Do not send", "thread-1", 2L).isFailure)
            assertEquals(1, connection.sendCalls.size)
            assertEquals(GroupSendCoordinatorState.Blocked, coordinator.executionState.first().status)
            val snapshot = store.snapshot()
            assertTrue(GroupSendScope(testIdentity, testTarget.room).roomKey in snapshot.tombstones)
            assertEquals(GroupSendRecordState.Blocked, snapshot.records.values.single().state)
        }
    }

    @Test
    fun `write failure blocks wire dispatch entirely`() = runTest {
        val store = TransientGroupSendStore()
        store.failWrites = true
        val connection = FakeConnection(testIdentity)
        val coordinator = GroupSendCoordinator(store, connection)

        val res = coordinator.submit(testTarget, "raw-1", "Hello world", "thread-1", 1L)
        assertTrue(res.isFailure)
        assertTrue(connection.sendCalls.isEmpty())
        assertTrue(store.snapshot().records.isEmpty())
    }

    @Test
    fun `confirmation persistence failure returns uncertainty and retains immutable record`() = runTest {
        val store = object : TransientGroupSendStore() {
            override suspend fun markConfirmed(recordKey: String, receipt: GroupSendReceipt): GroupSendStoreMutation =
                GroupSendStoreMutation.StorageUnavailable
        }
        val connection = FakeConnection(testIdentity)
        val coordinator = GroupSendCoordinator(store, connection)

        val res = coordinator.submit(testTarget, "raw-confirm-fail", "Accepted remotely", "thread-1", 1L)
        assertTrue(res.isFailure)
        assertEquals(GroupSendProblem.TransportUncertain, res.exceptionOrNull()?.let { (it as GroupSendFailure).problem })
        assertEquals(GroupSendCoordinatorState.Uncertain, coordinator.executionState.first().status)
        assertEquals("raw-confirm-fail", store.snapshot().records.values.single().operation.rawId)
    }

    @Test
    fun `transport uncertainty marks record uncertain and retains rawId without dropping`() = runTest {
        val store = TransientGroupSendStore()
        val connection = FakeConnection(testIdentity, sendHandler = {
            throw GroupSendFailure(GroupSendProblem.TransportUncertain)
        })
        val coordinator = GroupSendCoordinator(store, connection)

        val res = coordinator.submit(testTarget, "raw-1", "Hello uncertain", "thread-1", 1L)
        assertTrue(res.isFailure)
        assertEquals(1, connection.sendCalls.size)

        // Record must be preserved as Uncertain, not deleted
        val rec = store.snapshot().records.values.single()
        assertEquals(GroupSendRecordState.Uncertain, rec.state)
        assertEquals("raw-1", rec.operation.rawId)
    }

    @Test
    fun `reconcile dedupes uncertain record against server log and marks confirmed`() = runTest {
        val store = TransientGroupSendStore()
        val op = GroupSendOperation(testTarget, "raw-1", GroupSendPayload.normalize("Reconcile me", "thread-1"))
        val record = GroupSendRecord(op, draftRevision = 1L, state = GroupSendRecordState.Uncertain, createdAtMillis = 1000L)
        store.prepare(record)
        store.markUncertain(record.recordKey)

        val matchingLogEvent = buildJsonObject {
            put("event_id", storedUserEventId("raw-1"))
            put("room_id", "room-1")
            put("kind", "message.user")
            put("actor", buildJsonObject { put("kind", "user"); put("id", "desktop") })
            put("payload", op.payload.wire())
            put("authority_epoch", 1)
            put("created_at", 15.0)
            put("idempotent", false)
            put("seq", 77)
        }

        val connection = FakeConnection(
            identity = testIdentity,
            refreshHandler = { room ->
                GroupSendRefresh(listOf(matchingLogEvent), writable = true, worker = true)
            }
        )

        val coordinator = GroupSendCoordinator(store, connection)
        val resolved = coordinator.reconcile("room-1")

        assertEquals(1, resolved.size)
        assertEquals(GroupSendRecordState.Confirmed, resolved.single().state)
        assertEquals(77L, resolved.single().receiptSeq)

        val stored = store.snapshot().records[record.recordKey]
        assertEquals(GroupSendRecordState.Confirmed, stored?.state)
        assertEquals(77L, stored?.receiptSeq)
        assertNull("Worker availability cannot prove historical driver startup", stored?.receiptDriver)
    }

    @Test
    fun `history expired marks tombstone and blocks further writes to room`() = runTest {
        val store = TransientGroupSendStore()
        val connection = FakeConnection(testIdentity, sendHandler = {
            throw GroupSendFailure(GroupSendProblem.Expired)
        })
        val coordinator = GroupSendCoordinator(store, connection)

        val res = coordinator.submit(testTarget, "raw-1", "Will expire", "thread-1", 1L)
        assertTrue(res.isFailure)

        val scopedRoom = GroupSendScope(testIdentity, testTarget.room).roomKey
        assertTrue(store.snapshot().tombstones.contains(scopedRoom))

        // Next send into same room fails immediately at prepare
        val res2 = coordinator.submit(testTarget, "raw-2", "Another try", "thread-1", 2L)
        assertTrue(res2.isFailure)
        assertEquals(1, connection.sendCalls.size) // No second wire call
    }

    @Test
    fun `worker unavailable preserves record as blocked`() = runTest {
        val store = TransientGroupSendStore()
        val connection = FakeConnection(testIdentity, sendHandler = {
            throw GroupSendFailure(GroupSendProblem.WorkerUnavailable)
        })
        val coordinator = GroupSendCoordinator(store, connection)

        val res = coordinator.submit(testTarget, "raw-1", "Worker down", "thread-1", 1L)
        assertTrue(res.isFailure)

        val stored = store.snapshot().records.values.single()
        assertEquals(GroupSendRecordState.Blocked, stored.state)
    }
}
