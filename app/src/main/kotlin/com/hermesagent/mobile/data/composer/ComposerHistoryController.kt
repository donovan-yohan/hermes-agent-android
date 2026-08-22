package com.hermesagent.mobile.data.composer

import androidx.lifecycle.SavedStateHandle
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.UserTurn

/** Cursor is an index into newest-first user-turn history. */
data class ComposerHistoryBrowseState(
    val cursor: Int,
    val draftSnapshot: String,
)

/** Explicit result keeps an empty restored draft distinct from no navigation. */
sealed interface ComposerDraftChange {
    data object Unchanged : ComposerDraftChange
    data class Changed(val draft: String) : ComposerDraftChange
}

/**
 * Small persistence boundary for browse cursor and pre-browse draft only. It
 * intentionally stores neither transcript history nor undo stacks.
 */
interface ComposerHistoryBrowseStore {
    fun get(durableSessionId: String): ComposerHistoryBrowseState?
    fun put(durableSessionId: String, state: ComposerHistoryBrowseState)
    fun remove(durableSessionId: String)
}

/** SavedStateHandle-backed browser state; it is never written to SessionCache or DataStore. */
class SavedStateComposerHistoryBrowseStore(
    private val handle: SavedStateHandle,
) : ComposerHistoryBrowseStore {
    override fun get(durableSessionId: String): ComposerHistoryBrowseState? =
        decode(handle[HISTORY_BROWSE_KEY])[durableSessionId]

    override fun put(durableSessionId: String, state: ComposerHistoryBrowseState) {
        if (!durableSessionId.isValidHistoryDurableId() || state.cursor < 0) return
        val next = decode(handle[HISTORY_BROWSE_KEY]).toMutableMap().apply { put(durableSessionId, state) }
        handle[HISTORY_BROWSE_KEY] = encode(next)
    }

    override fun remove(durableSessionId: String) {
        val next = decode(handle[HISTORY_BROWSE_KEY]).toMutableMap().apply { remove(durableSessionId) }
        if (next.isEmpty()) handle.remove<List<String>>(HISTORY_BROWSE_KEY) else handle[HISTORY_BROWSE_KEY] = encode(next)
    }

    private fun decode(raw: List<String>?): Map<String, ComposerHistoryBrowseState> {
        if (raw == null || raw.size % 3 != 0) return emptyMap()
        return buildMap {
            raw.chunked(3).forEach { (durableId, cursor, snapshot) ->
                val index = cursor.toIntOrNull()
                if (durableId.isValidHistoryDurableId() && index != null && index >= 0) {
                    put(durableId, ComposerHistoryBrowseState(index, snapshot))
                }
            }
        }
    }

    private fun encode(states: Map<String, ComposerHistoryBrowseState>): ArrayList<String> =
        ArrayList<String>(states.size * 3).also { target ->
            states.forEach { (durableId, state) ->
                target += durableId
                target += state.cursor.toString()
                target += state.draftSnapshot
            }
        }

    private companion object {
        const val HISTORY_BROWSE_KEY = "composer.history.browse.v1"
    }
}

/** Test-only in-memory equivalent of [SavedStateComposerHistoryBrowseStore]. */
class TransientComposerHistoryBrowseStore : ComposerHistoryBrowseStore {
    private val values = mutableMapOf<String, ComposerHistoryBrowseState>()
    override fun get(durableSessionId: String): ComposerHistoryBrowseState? = values[durableSessionId]
    override fun put(durableSessionId: String, state: ComposerHistoryBrowseState) {
        if (durableSessionId.isValidHistoryDurableId() && state.cursor >= 0) values[durableSessionId] = state
    }
    override fun remove(durableSessionId: String) {
        values.remove(durableSessionId)
    }
}

data class ComposerUndoRedoState(
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/**
 * Local editor history. Browse candidates are read from the current session's
 * authoritative transcript for every action; this controller never writes to
 * [SessionCache]. Undo/redo is bounded, session-local and deliberately dies
 * on reset, session switch, rehome, submit, or queue-edit exit.
 */
class ComposerHistoryController(
    private val cache: SessionCache,
    private val browseStore: ComposerHistoryBrowseStore,
    private val maxUndoDepth: Int = 50,
) {
    init {
        require(maxUndoDepth > 0)
    }

    private val undoByDurableId = mutableMapOf<String, UndoRedoBuffers>()

    fun browseState(durableSessionId: String): ComposerHistoryBrowseState? = browseStore.get(durableSessionId)

    /** Up: only starts from an empty draft, then follows newest-first UserTurn text. */
    fun browseOlder(durableSessionId: String, currentDraft: String): ComposerDraftChange {
        val history = userHistory(durableSessionId)
        if (history.isEmpty()) return ComposerDraftChange.Unchanged
        val current = browseStore.get(durableSessionId)
        if (current == null) {
            if (currentDraft.isNotEmpty()) return ComposerDraftChange.Unchanged
            browseStore.put(durableSessionId, ComposerHistoryBrowseState(cursor = 0, draftSnapshot = currentDraft))
            return ComposerDraftChange.Changed(history.first())
        }
        val next = (current.cursor + 1).coerceAtMost(history.lastIndex)
        browseStore.put(durableSessionId, current.copy(cursor = next))
        return ComposerDraftChange.Changed(history[next])
    }

    /** Down: walks toward newer history; Down from newest restores pre-browse text. */
    fun browseNewer(durableSessionId: String): ComposerDraftChange {
        val current = browseStore.get(durableSessionId) ?: return ComposerDraftChange.Unchanged
        val history = userHistory(durableSessionId)
        if (current.cursor <= 0 || history.isEmpty()) {
            browseStore.remove(durableSessionId)
            return ComposerDraftChange.Changed(current.draftSnapshot)
        }
        val next = (current.cursor - 1).coerceAtMost(history.lastIndex)
        browseStore.put(durableSessionId, current.copy(cursor = next))
        return ComposerDraftChange.Changed(history[next])
    }

    /** Record one ordinary text edit. Browser navigation itself must not call this. */
    fun recordOrdinaryEdit(durableSessionId: String, previousDraft: String, newDraft: String) {
        browseStore.remove(durableSessionId)
        if (previousDraft == newDraft || !durableSessionId.isValidHistoryDurableId()) return
        val buffers = undoByDurableId.getOrPut(durableSessionId, ::UndoRedoBuffers)
        buffers.undo += previousDraft
        if (buffers.undo.size > maxUndoDepth) buffers.undo.removeAt(0)
        buffers.redo.clear()
    }

    fun undoRedoState(durableSessionId: String): ComposerUndoRedoState {
        val buffers = undoByDurableId[durableSessionId] ?: return ComposerUndoRedoState()
        return ComposerUndoRedoState(buffers.undo.isNotEmpty(), buffers.redo.isNotEmpty())
    }

    fun undo(durableSessionId: String, currentDraft: String): ComposerDraftChange {
        val buffers = undoByDurableId[durableSessionId] ?: return ComposerDraftChange.Unchanged
        val restored = buffers.undo.removeLastOrNull() ?: return ComposerDraftChange.Unchanged
        buffers.redo += currentDraft
        if (buffers.redo.size > maxUndoDepth) buffers.redo.removeAt(0)
        browseStore.remove(durableSessionId)
        return ComposerDraftChange.Changed(restored)
    }

    fun redo(durableSessionId: String, currentDraft: String): ComposerDraftChange {
        val buffers = undoByDurableId[durableSessionId] ?: return ComposerDraftChange.Unchanged
        val restored = buffers.redo.removeLastOrNull() ?: return ComposerDraftChange.Unchanged
        buffers.undo += currentDraft
        if (buffers.undo.size > maxUndoDepth) buffers.undo.removeAt(0)
        browseStore.remove(durableSessionId)
        return ComposerDraftChange.Changed(restored)
    }

    /** Reset at every local scope boundary: submit, session switch/rehome, or queued-edit exit. */
    fun reset(durableSessionId: String) {
        browseStore.remove(durableSessionId)
        undoByDurableId.remove(durableSessionId)
    }

    fun rehome(fromDurableId: String, toDurableId: String) {
        reset(fromDurableId)
        reset(toDurableId)
    }

    private fun userHistory(durableSessionId: String): List<String> = cache.transcript(durableSessionId)
        .filterIsInstance<UserTurn>()
        .map(UserTurn::text)
        .filter(String::isNotBlank)
        .asReversed()

    private class UndoRedoBuffers {
        val undo = mutableListOf<String>()
        val redo = mutableListOf<String>()
    }
}

private fun String.isValidHistoryDurableId(): Boolean = isNotBlank() && trim() == this && length <= 512
