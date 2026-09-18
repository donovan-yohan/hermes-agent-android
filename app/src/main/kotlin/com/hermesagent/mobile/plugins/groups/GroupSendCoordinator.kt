package com.hermesagent.mobile.plugins.groups

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

internal enum class GroupSendCoordinatorState {
    Idle,
    Persisting,
    Sending,
    Confirmed,
    Uncertain,
    Failed,
    Blocked,
}

internal data class GroupSendExecutionState(
    val status: GroupSendCoordinatorState = GroupSendCoordinatorState.Idle,
    val pendingRecord: GroupSendRecord? = null,
    val receipt: GroupSendReceipt? = null,
    val problem: GroupSendProblem? = null,
    val activeRevision: Long = 0L,
)

internal class GroupSendCoordinator(
    private val store: GroupSendStore,
    private val connection: GroupSendConnection,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val stateFlow = MutableStateFlow(GroupSendExecutionState())
    val executionState: Flow<GroupSendExecutionState> = stateFlow.asStateFlow()

    /**
     * Submit a draft to be durably persisted before sending.
     * [draftRevision] tracks editor revisions.
     */
    suspend fun submit(
        target: GroupSendTarget,
        rawId: String,
        text: String,
        thread: String,
        draftRevision: Long,
    ): Result<GroupSendReceipt> = mutex.withLock {
        // Enforce identity alignment
        if (target.identity != connection.identity) {
            stateFlow.update { it.copy(status = GroupSendCoordinatorState.Blocked, problem = GroupSendProblem.AuthorityBlocked) }
            return Result.failure(GroupSendFailure(GroupSendProblem.AuthorityBlocked))
        }

        val payload = GroupSendPayload.normalize(text, thread)
        val operation = GroupSendOperation(target, rawId, payload)
        val editorDraft = GroupSendEditorDraft(target, payload.text, payload.thread, draftRevision)
        val record = GroupSendRecord(
            operation = operation,
            draftRevision = draftRevision,
            state = GroupSendRecordState.Prepared,
            createdAtMillis = clock()
        )

        stateFlow.update { it.copy(status = GroupSendCoordinatorState.Persisting, pendingRecord = record, activeRevision = draftRevision) }

        // Step 1: atomically persist the editable draft and immutable send snapshot before wire.
        val mutation = store.persistDraftAndPrepare(editorDraft, record)
        if (mutation !is GroupSendStoreMutation.Applied) {
            val failure = when (mutation) {
                GroupSendStoreMutation.CapacityReached -> GroupSendFailure(GroupSendProblem.ServerRefused)
                GroupSendStoreMutation.ConflictingRecord -> GroupSendFailure(GroupSendProblem.AuthorityBlocked)
                else -> GroupSendFailure(GroupSendProblem.TransportUncertain)
            }
            stateFlow.update { it.copy(status = GroupSendCoordinatorState.Failed, problem = failure.problem) }
            return Result.failure(failure)
        }

        // Step 2: WIRE DISPATCH
        stateFlow.update { it.copy(status = GroupSendCoordinatorState.Sending) }
        try {
            val response = connection.send(operation)
            val receipt = parseSendReceipt(response, operation)

            // Step 3: durable confirmation. A failed write leaves the immutable key uncertain.
            if (store.markConfirmed(record.recordKey, receipt) != GroupSendStoreMutation.Applied) {
                stateFlow.update { it.copy(status = GroupSendCoordinatorState.Uncertain, problem = GroupSendProblem.TransportUncertain) }
                return Result.failure(GroupSendFailure(GroupSendProblem.TransportUncertain))
            }
            stateFlow.update {
                it.copy(
                    status = GroupSendCoordinatorState.Confirmed,
                    receipt = receipt,
                    problem = null
                )
            }
            Result.success(receipt)
        } catch (failure: GroupSendFailure) {
            when (failure.problem) {
                GroupSendProblem.TransportUncertain -> {
                    // Mark uncertain in store, do NOT drop record or rawId
                    store.markUncertain(record.recordKey)
                    stateFlow.update { it.copy(status = GroupSendCoordinatorState.Uncertain, problem = failure.problem) }
                }
                GroupSendProblem.WorkerUnavailable, GroupSendProblem.AuthorityBlocked -> {
                    store.markBlocked(record.recordKey)
                    stateFlow.update { it.copy(status = GroupSendCoordinatorState.Blocked, problem = failure.problem) }
                }
                GroupSendProblem.Expired -> {
                    val scopedRoom = GroupSendScope(target.identity, target.room).roomKey
                    store.tombstoneRoom(scopedRoom)
                    store.markBlocked(record.recordKey)
                    stateFlow.update { it.copy(status = GroupSendCoordinatorState.Blocked, problem = failure.problem) }
                }
                else -> {
                    stateFlow.update { it.copy(status = GroupSendCoordinatorState.Failed, problem = failure.problem) }
                }
            }
            Result.failure(failure)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    store.markUncertain(record.recordKey)
                }
            } catch (_: Exception) {
                // Prepared remains recoverable if local uncertainty persistence fails.
            } finally {
                stateFlow.update { it.copy(status = GroupSendCoordinatorState.Uncertain, problem = GroupSendProblem.TransportUncertain) }
            }
            throw cancelled
        } catch (other: Throwable) {
            store.markUncertain(record.recordKey)
            stateFlow.update { it.copy(status = GroupSendCoordinatorState.Uncertain, problem = GroupSendProblem.TransportUncertain) }
            Result.failure(GroupSendFailure(GroupSendProblem.TransportUncertain))
        }
    }

    /**
     * Reconciles uncertain records after reconnection or process crash by inspecting room log events.
     */
    suspend fun reconcile(room: String): List<GroupSendRecord> = mutex.withLock {
        val snapshot = store.snapshot()
        val uncertainRecords = snapshot.records.values.filter {
            it.operation.target.identity == connection.identity &&
                it.operation.target.room == room &&
                (it.state == GroupSendRecordState.Uncertain || it.state == GroupSendRecordState.Prepared)
        }
        if (uncertainRecords.isEmpty()) return emptyList()

        val refresh = try {
            connection.refresh(room)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return emptyList()
        }

        val resolved = mutableListOf<GroupSendRecord>()
        for (record in uncertainRecords) {
            val matchingSeq = refresh.events.firstNotNullOfOrNull { eventObj ->
                matchingSendEvent(eventObj, record.operation)
            }
            if (matchingSeq != null) {
                val receipt = GroupSendReceipt(
                    storedId = storedUserEventId(record.operation.rawId),
                    sequence = matchingSeq,
                    driverStarted = null
                )
                if (store.markConfirmed(record.recordKey, receipt) == GroupSendStoreMutation.Applied) {
                    resolved += record.copy(
                        state = GroupSendRecordState.Confirmed,
                        receiptSeq = receipt.sequence,
                        receiptDriver = receipt.driverStarted
                    )
                }
            }
        }
        resolved
    }
}
