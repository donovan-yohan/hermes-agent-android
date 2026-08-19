package com.hermesagent.mobile.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Everything the cache holds. Sessions and transcripts move together. */
data class SessionCacheState(
    val sessions: Map<String, SessionSummary> = emptyMap(),
    val transcripts: Map<String, List<TranscriptEntry>> = emptyMap(),
)

/**
 * The cache of backend truth.
 *
 * Phase 1 has no gateway, so the only writer is the demo source — but the
 * *rules* are the ones the gateway will need, taken from
 * `apps/desktop/AGENTS.md` ("Server truth is cached, not owned" @ `f82f2dba`):
 *
 * - **Merge, don't clobber.** [upsertSessions] layers new information over
 *   what is already known; it never drops a row the refresh did not mention.
 *   A row disappears only through [removeSession], the explicit tombstone.
 * - **Preserve reference identity on no-ops.** An upsert that changes nothing
 *   returns the same state instance, so Compose does not recompose the list.
 *
 * UI-only state (search text, drawer open, draft) is deliberately *not* here.
 * It belongs to the ViewModel and dies with the screen.
 */
class SessionCache {

    private val _state = MutableStateFlow(SessionCacheState())
    val state: StateFlow<SessionCacheState> = _state.asStateFlow()

    fun upsertSessions(rows: List<SessionSummary>) {
        if (rows.isEmpty()) return
        _state.update { current ->
            val merged = current.sessions.toMutableMap()
            var changed = false
            for (row in rows) {
                if (merged[row.id] != row) {
                    merged[row.id] = row
                    changed = true
                }
            }
            if (changed) current.copy(sessions = merged) else current
        }
    }

    fun upsertSession(row: SessionSummary) = upsertSessions(listOf(row))

    /** Explicit tombstone. The only way a session leaves the cache. */
    fun removeSession(id: String) {
        _state.update { current ->
            if (!current.sessions.containsKey(id)) {
                current
            } else {
                current.copy(
                    sessions = current.sessions - id,
                    transcripts = current.transcripts - id,
                )
            }
        }
    }

    fun appendEntry(sessionId: String, entry: TranscriptEntry) {
        _state.update { current ->
            val existing = current.transcripts[sessionId].orEmpty()
            current.copy(transcripts = current.transcripts + (sessionId to existing + entry))
        }
    }

    /**
     * Replace an entry by id, or append it when it is new. This is how a
     * streaming turn grows: one entry rewritten in place rather than a new
     * message per delta.
     */
    fun putEntry(sessionId: String, entry: TranscriptEntry) {
        _state.update { current ->
            val existing = current.transcripts[sessionId].orEmpty()
            val index = existing.indexOfFirst { it.id == entry.id }
            when {
                index < 0 -> current.copy(transcripts = current.transcripts + (sessionId to existing + entry))
                existing[index] == entry -> current
                else -> {
                    val updated = existing.toMutableList().apply { this[index] = entry }
                    current.copy(transcripts = current.transcripts + (sessionId to updated))
                }
            }
        }
    }

    fun setTranscript(sessionId: String, entries: List<TranscriptEntry>) {
        _state.update { current ->
            if (current.transcripts[sessionId] == entries) {
                current
            } else {
                current.copy(transcripts = current.transcripts + (sessionId to entries))
            }
        }
    }

    fun session(id: String): SessionSummary? = _state.value.sessions[id]

    fun transcript(id: String): List<TranscriptEntry> = _state.value.transcripts[id].orEmpty()
}
