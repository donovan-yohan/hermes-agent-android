package com.hermesagent.mobile.data.composer

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Narrow queued-submit boundary. The adapter owns durable-to-runtime resolution and `queued:true`. */
interface ComposerQueueSubmitter {
    suspend fun submitQueued(durableSessionId: String, text: String): QueueSubmissionOutcome
}

/** A rejected request is safe to retry later; an ambiguous acknowledgement is not. */
sealed interface QueueSubmissionOutcome {
    data object Accepted : QueueSubmissionOutcome
    data object Rejected : QueueSubmissionOutcome
    data object Ambiguous : QueueSubmissionOutcome
}

sealed interface ComposerQueueDrainResult {
    data object NoEntry : ComposerQueueDrainResult
    data object NotIdle : ComposerQueueDrainResult
    data object Parked : ComposerQueueDrainResult
    data object Editing : ComposerQueueDrainResult
    data object ReviewRequired : ComposerQueueDrainResult
    data object RetryLimitReached : ComposerQueueDrainResult
    data object Accepted : ComposerQueueDrainResult
    data object Rejected : ComposerQueueDrainResult
    data object Ambiguous : ComposerQueueDrainResult
    data object StoreUnavailable : ComposerQueueDrainResult
}

/**
 * Text-only edit snapshot. Attachments intentionally have no counterpart here:
 * they cannot enter a durable queue before a Gateway upload identity exists.
 */
data class QueueEditSnapshot(
    val originalDraft: String,
    val entryId: String,
    val durableSessionId: String,
)

/**
 * Coordinates persisted FIFO entries without owning Gateway runtime identity.
 * One [drainMutex] covers every possible queued submit, so idle/reconnect/turn
 * completion notifications cannot duplicate a drain. Park and edit state are
 * intentionally memory-only and therefore vanish across process recreation.
 */
class ComposerQueueController(
    private val store: ComposerQueueStore,
    private val submitter: ComposerQueueSubmitter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val entryId: () -> String = { UUID.randomUUID().toString() },
    private val maxAutoFailuresPerEntry: Int = 2,
) {
    init {
        require(maxAutoFailuresPerEntry > 0)
    }

    private val drainMutex = Mutex()
    private val localStateMutex = Mutex()
    private val parkedIds = mutableSetOf<String>()
    private val editingIds = mutableSetOf<QueueEntryKey>()
    private val failureCounts = mutableMapOf<QueueEntryKey, Int>()
    /** A positive submit whose local removal/mark could not be persisted must never auto-retry in this process. */
    private val reviewRequiredIds = mutableSetOf<QueueEntryKey>()
    private val _parkedDurableIds = MutableStateFlow<Set<String>>(emptySet())
    val parkedDurableIds: StateFlow<Set<String>> = _parkedDurableIds.asStateFlow()

    val state = store.state

    suspend fun queue(durableSessionId: String): List<QueuedPrompt> = store.snapshot().entriesFor(durableSessionId)

    suspend fun enqueue(
        durableSessionId: String,
        text: String,
        displayText: String? = null,
        delivery: QueuedPromptDelivery = QueuedPromptDelivery.Ready,
    ): ComposerQueueMutation {
        val entry = runCatching {
            QueuedPrompt(
                id = entryId(),
                text = text,
                displayText = displayText,
                queuedAtMillis = clock(),
                delivery = delivery,
            )
        }.getOrNull() ?: return ComposerQueueMutation.Rejected
        return store.enqueue(durableSessionId, entry)
    }

    suspend fun remove(durableSessionId: String, entryId: String): ComposerQueueMutation {
        val result = store.remove(durableSessionId, entryId)
        if (result == ComposerQueueMutation.Applied) {
            localStateMutex.withLock {
                failureCounts.remove(QueueEntryKey(durableSessionId, entryId))
                editingIds.remove(QueueEntryKey(durableSessionId, entryId))
                reviewRequiredIds.remove(QueueEntryKey(durableSessionId, entryId))
            }
        }
        return result
    }

    suspend fun moveToHead(durableSessionId: String, entryId: String): ComposerQueueMutation =
        store.moveToHead(durableSessionId, entryId)

    suspend fun beginEdit(
        durableSessionId: String,
        entryId: String,
        originalDraft: String,
    ): QueueEditSnapshot? {
        if (store.snapshot().entriesFor(durableSessionId).none { it.id == entryId }) return null
        localStateMutex.withLock { editingIds += QueueEntryKey(durableSessionId, entryId) }
        return QueueEditSnapshot(originalDraft, entryId, durableSessionId)
    }

    /** Saving stays parked at the queue layer; integration decides when it may next drain. */
    suspend fun saveEdit(
        snapshot: QueueEditSnapshot,
        text: String,
        displayText: String? = null,
    ): ComposerQueueMutation {
        val result = store.update(snapshot.durableSessionId, snapshot.entryId, text, displayText)
        if (result == ComposerQueueMutation.Applied) {
            localStateMutex.withLock { editingIds.remove(snapshot.key()) }
        }
        return result
    }

    /** Does not mutate the queue. Caller restores [QueueEditSnapshot.originalDraft] into its editor. */
    suspend fun cancelEdit(snapshot: QueueEditSnapshot): String {
        localStateMutex.withLock { editingIds.remove(snapshot.key()) }
        return snapshot.originalDraft
    }

    suspend fun park(durableSessionId: String) {
        localStateMutex.withLock {
            parkedIds += durableSessionId
            _parkedDurableIds.value = parkedIds.toSet()
        }
    }

    suspend fun resume(durableSessionId: String) {
        localStateMutex.withLock {
            parkedIds -= durableSessionId
            _parkedDurableIds.value = parkedIds.toSet()
        }
    }

    suspend fun isParked(durableSessionId: String): Boolean = localStateMutex.withLock {
        durableSessionId in parkedIds
    }

    /**
     * Profile/connection changes retain no transient state, even if two
     * profiles happen to use the same durable session id. Call before routing
     * the backing store to its next profile scope. The drain lock fences an
     * in-flight queued submit while this local state is discarded.
     */
    suspend fun resetTransientScopeState() {
        drainMutex.withLock {
            localStateMutex.withLock {
                parkedIds.clear()
                editingIds.clear()
                failureCounts.clear()
                reviewRequiredIds.clear()
                _parkedDurableIds.value = emptySet()
            }
        }
    }

    /** Redirect acknowledgement loss or a drain timeout freezes this entry for review. */
    suspend fun markAmbiguous(durableSessionId: String, entryId: String): ComposerQueueMutation {
        val result = store.markAmbiguous(durableSessionId, entryId)
        if (result == ComposerQueueMutation.Applied) {
            localStateMutex.withLock {
                failureCounts.remove(QueueEntryKey(durableSessionId, entryId))
                reviewRequiredIds.remove(QueueEntryKey(durableSessionId, entryId))
            }
        }
        return result
    }

    /** Explicit review is the only way an ambiguous entry becomes eligible for another submit. */
    suspend fun markReadyAfterReview(durableSessionId: String, entryId: String): ComposerQueueMutation {
        val result = store.markReadyAfterReview(durableSessionId, entryId)
        if (result == ComposerQueueMutation.Applied) {
            localStateMutex.withLock {
                failureCounts.remove(QueueEntryKey(durableSessionId, entryId))
                reviewRequiredIds.remove(QueueEntryKey(durableSessionId, entryId))
            }
        }
        return result
    }

    /**
     * Preserve FIFO during durable-session rehome. Park state moves, but is not
     * persisted; an app relaunch recreates this controller with no parked ids.
     */
    suspend fun migrate(fromDurableId: String, toDurableId: String): ComposerQueueMutation {
        val result = store.migrate(fromDurableId, toDurableId)
        if (result == ComposerQueueMutation.Applied) {
            localStateMutex.withLock {
                if (parkedIds.remove(fromDurableId)) parkedIds += toDurableId
                remapEntryLocalState(fromDurableId, toDurableId, failureCounts)
                remapEntryLocalState(fromDurableId, toDurableId, editingIds)
                remapEntryLocalState(fromDurableId, toDurableId, reviewRequiredIds)
                _parkedDurableIds.value = parkedIds.toSet()
            }
        }
        return result
    }

    /**
     * One automatic FIFO drain. Call only after this durable session is known
     * idle. `Ambiguous` entries remain visible and block all automatic retries.
     */
    suspend fun drainIfIdle(durableSessionId: String, isIdle: Boolean): ComposerQueueDrainResult =
        drain(durableSessionId, isIdle)

    /**
     * Manual Send next promotes one entry, then makes the same exact drain. It
     * may retry a bounded-auto-failure entry, but never an ambiguous one until
     * [markReadyAfterReview] records deliberate review.
     */
    suspend fun sendNextWhenIdle(
        durableSessionId: String,
        entryId: String,
        isIdle: Boolean,
    ): ComposerQueueDrainResult = drain(durableSessionId, isIdle, promoteEntryId = entryId)

    private suspend fun drain(
        durableSessionId: String,
        isIdle: Boolean,
        promoteEntryId: String? = null,
    ): ComposerQueueDrainResult {
        if (!isIdle) return ComposerQueueDrainResult.NotIdle
        return drainMutex.withLock {
            if (isParked(durableSessionId)) return@withLock ComposerQueueDrainResult.Parked
            if (promoteEntryId != null) {
                when (store.moveToHead(durableSessionId, promoteEntryId)) {
                    ComposerQueueMutation.Applied -> Unit
                    ComposerQueueMutation.StorageUnavailable -> return@withLock ComposerQueueDrainResult.StoreUnavailable
                    else -> return@withLock ComposerQueueDrainResult.NoEntry
                }
            }
            val entry = store.snapshot().entriesFor(durableSessionId).firstOrNull()
                ?: return@withLock ComposerQueueDrainResult.NoEntry
            if (isReviewRequired(durableSessionId, entry.id)) {
                return@withLock ComposerQueueDrainResult.ReviewRequired
            }
            if (entry.delivery == QueuedPromptDelivery.Ambiguous) {
                return@withLock ComposerQueueDrainResult.ReviewRequired
            }
            if (isEditing(durableSessionId, entry.id)) return@withLock ComposerQueueDrainResult.Editing
            if (promoteEntryId == null && failureCount(durableSessionId, entry.id) >= maxAutoFailuresPerEntry) {
                return@withLock ComposerQueueDrainResult.RetryLimitReached
            }
            when (submitter.submitQueued(durableSessionId, entry.text)) {
                QueueSubmissionOutcome.Accepted -> when (store.remove(durableSessionId, entry.id)) {
                    ComposerQueueMutation.Applied -> {
                        clearFailure(durableSessionId, entry.id)
                        ComposerQueueDrainResult.Accepted
                    }
                    else -> {
                        requireReview(durableSessionId, entry.id)
                        ComposerQueueDrainResult.ReviewRequired
                    }
                }
                QueueSubmissionOutcome.Rejected -> {
                    incrementFailure(durableSessionId, entry.id)
                    ComposerQueueDrainResult.Rejected
                }
                QueueSubmissionOutcome.Ambiguous -> when (store.markAmbiguous(durableSessionId, entry.id)) {
                    ComposerQueueMutation.Applied -> {
                        clearFailure(durableSessionId, entry.id)
                        ComposerQueueDrainResult.Ambiguous
                    }
                    else -> {
                        requireReview(durableSessionId, entry.id)
                        ComposerQueueDrainResult.ReviewRequired
                    }
                }
            }
        }
    }

    private suspend fun isEditing(durableSessionId: String, entryId: String): Boolean =
        localStateMutex.withLock { QueueEntryKey(durableSessionId, entryId) in editingIds }

    private suspend fun failureCount(durableSessionId: String, entryId: String): Int = localStateMutex.withLock {
        failureCounts[QueueEntryKey(durableSessionId, entryId)] ?: 0
    }

    private suspend fun incrementFailure(durableSessionId: String, entryId: String) {
        val key = QueueEntryKey(durableSessionId, entryId)
        localStateMutex.withLock { failureCounts[key] = (failureCounts[key] ?: 0) + 1 }
    }

    private suspend fun clearFailure(durableSessionId: String, entryId: String) {
        localStateMutex.withLock { failureCounts.remove(QueueEntryKey(durableSessionId, entryId)) }
    }

    private suspend fun isReviewRequired(durableSessionId: String, entryId: String): Boolean = localStateMutex.withLock {
        QueueEntryKey(durableSessionId, entryId) in reviewRequiredIds
    }

    private suspend fun requireReview(durableSessionId: String, entryId: String) {
        localStateMutex.withLock { reviewRequiredIds += QueueEntryKey(durableSessionId, entryId) }
    }

    private data class QueueEntryKey(val durableSessionId: String, val entryId: String)

    private fun QueueEditSnapshot.key(): QueueEntryKey = QueueEntryKey(durableSessionId, entryId)

    private fun remapEntryLocalState(
        fromDurableId: String,
        toDurableId: String,
        values: MutableMap<QueueEntryKey, Int>,
    ) {
        values.entries.filter { it.key.durableSessionId == fromDurableId }.toList().forEach { (key, count) ->
            values.remove(key)
            values[QueueEntryKey(toDurableId, key.entryId)] = count
        }
    }

    private fun remapEntryLocalState(
        fromDurableId: String,
        toDurableId: String,
        values: MutableSet<QueueEntryKey>,
    ) {
        values.filter { it.durableSessionId == fromDurableId }.forEach { key ->
            values.remove(key)
            values += QueueEntryKey(toDurableId, key.entryId)
        }
    }

}
