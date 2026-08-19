package com.hermesagent.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.demo.DemoTurnEngine
import com.hermesagent.mobile.data.demo.TurnEvent
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.buildSessionRows
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the chat surface renders. Everything here is derived, never edited in place. */
data class ChatUiState(
    val sessionRows: List<SessionListRow> = emptyList(),
    val activeSession: SessionSummary? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val query: String = "",
    val draft: String = "",
    /** True when the *foreground* session is producing. Background ones are not. */
    val isStreaming: Boolean = false,
    /** How many sessions are producing right now, foreground or not. */
    val runningCount: Int = 0,
    val showArchived: Boolean = false,
) {
    /** A visible send control always has a foreground session [submit] can use. */
    val canSend: Boolean get() = activeSession != null && draft.isNotBlank() && !isStreaming
    val transcriptIsEmpty: Boolean get() = transcript.isEmpty()
}

/**
 * Owns chat interaction state and drives the demo turn engine.
 *
 * Two Desktop invariants are load-bearing here and are what the tests pin:
 *
 * - **Guard against the past** (`apps/desktop/AGENTS.md` @ `f82f2dba`). Each
 *   submit takes a generation; a delta from an older generation is dropped
 *   rather than allowed to overwrite newer intent. Cancelling the job is not
 *   enough on its own — an in-flight emission can still be mid-delivery.
 * - **Isolate the foreground.** A turn writes to the session that started it.
 *   Switching sessions never cancels it and never lets it paint into the
 *   session the user is now looking at; it lands as an unread dot instead.
 */
class ChatViewModel(
    private val cache: SessionCache,
    private val turnEngine: DemoTurnEngine = DemoTurnEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val draft = MutableStateFlow("")
    private val activeSessionId = MutableStateFlow<String?>(null)
    private val runningSessions = MutableStateFlow<Set<String>>(emptySet())
    private val showArchived = MutableStateFlow(false)

    private val jobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Int>()
    private var createdSessionCount = 0

    val uiState: StateFlow<ChatUiState> = combine(
        cache.state,
        query,
        draft,
        activeSessionId,
        combine(runningSessions, showArchived) { running, archived -> running to archived },
    ) { cacheState, queryText, draftText, activeId, (running, archived) ->
        ChatUiState(
            sessionRows = buildSessionRows(
                sessions = cacheState.sessions.values.map { it.withLiveStatus(running) },
                nowMillis = clock(),
                query = queryText,
                includeArchived = archived,
            ),
            activeSession = activeId?.let { cacheState.sessions[it]?.withLiveStatus(running) },
            transcript = activeId?.let { cacheState.transcripts[it] }.orEmpty(),
            query = queryText,
            draft = draftText,
            isStreaming = activeId != null && activeId in running,
            runningCount = running.size,
            showArchived = archived,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** A running turn beats whatever status the cache last recorded. */
    private fun SessionSummary.withLiveStatus(running: Set<String>): SessionSummary =
        if (id in running) copy(status = SessionStatus.Working) else this

    fun setQuery(value: String) {
        query.value = value
    }

    fun setDraft(value: String) {
        draft.value = value
    }

    fun setShowArchived(value: Boolean) {
        showArchived.value = value
    }

    /**
     * Switching sessions is a re-home, not a reboot
     * (`apps/desktop/AGENTS.md`): the draft for the session being left is
     * dropped rather than carried into the next one, and no running turn is
     * touched.
     */
    fun selectSession(id: String) {
        if (activeSessionId.value == id) return
        rehome(id)
    }

    fun createSession(): String {
        val id = nextLocalSessionId()
        cache.upsertSession(
            SessionSummary(
                id = id,
                title = "New session",
                preview = "",
                lastActiveAtMillis = clock(),
                status = SessionStatus.Idle,
            ),
        )
        rehome(id)
        return id
    }

    /**
     * Point the foreground at [id] and drop the draft that belonged to the
     * session being left.
     *
     * The draft is session-scoped state the cache deliberately does not hold,
     * so every path that moves the foreground has to go through here — a
     * half-written prompt appearing under, and sendable to, a session the user
     * never typed it in is the bug this exists to prevent. `null` is a real
     * outcome: archiving the last live session leaves nothing to land on.
     */
    private fun rehome(id: String?) {
        activeSessionId.value = id
        draft.value = ""
        id?.let(::markRead)
    }

    fun renameSession(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        cache.session(id)?.let { cache.upsertSession(it.copy(title = trimmed)) }
    }

    fun setArchived(id: String, archived: Boolean) {
        val session = cache.session(id) ?: return
        cache.upsertSession(session.copy(archived = archived))
        // Archiving what the user is looking at picks a replacement, which is a
        // re-home like any other — not a quiet id swap under a live draft.
        if (archived && activeSessionId.value == id) {
            rehome(
                cache.state.value.sessions.values
                    .filterNot { it.archived }
                    .maxByOrNull { it.lastActiveAtMillis }
                    ?.id,
            )
        } else if (!archived && activeSessionId.value == null) {
            // Restoring the final archived session gives the user somewhere to
            // continue. Do not steal focus from an existing live foreground.
            rehome(id)
        }
    }

    /**
     * Local ids must not restart with a ViewModel. [SessionCache] is process
     * scoped, so a recreated screen has to avoid both session rows and any
     * already-cached transcript keyed by an earlier local id.
     */
    private fun nextLocalSessionId(): String {
        var id: String
        do {
            createdSessionCount += 1
            id = "s-new-$createdSessionCount"
        } while (
            cache.state.value.sessions.containsKey(id) ||
                cache.state.value.transcripts.containsKey(id)
        )
        return id
    }

    fun submit() {
        val sessionId = activeSessionId.value ?: return
        val prompt = draft.value.trim()
        if (prompt.isEmpty() || sessionId in runningSessions.value) return

        draft.value = ""
        val now = clock()
        val generation = (generations[sessionId] ?: 0) + 1
        generations[sessionId] = generation

        cache.appendEntry(sessionId, UserTurn(id = "$sessionId-u$generation", text = prompt, atMillis = now))
        touchSession(sessionId, preview = prompt, status = SessionStatus.Working, at = now)
        runningSessions.update { it + sessionId }

        jobs[sessionId] = viewModelScope.launch {
            var assistantIndex = 0
            var assistantId: String? = null
            var assistantText = StringBuilder()

            fun currentAssistant(streaming: Boolean, stopped: Boolean = false): AssistantTurn? {
                val id = assistantId ?: return null
                return AssistantTurn(
                    id = id,
                    markdown = assistantText.toString(),
                    atMillis = now,
                    streaming = streaming,
                    stopped = stopped,
                )
            }

            try {
                turnEngine.run(prompt).collect { event ->
                    // Guard against the past: a delta from a superseded turn
                    // must never land on the transcript.
                    if (generations[sessionId] != generation) return@collect

                    when (event) {
                        is TurnEvent.Delta -> {
                            if (assistantId == null) {
                                assistantIndex += 1
                                assistantId = "$sessionId-a$generation-$assistantIndex"
                                assistantText = StringBuilder()
                            }
                            assistantText.append(event.text)
                            currentAssistant(streaming = true)?.let { cache.putEntry(sessionId, it) }
                        }

                        is TurnEvent.ToolStarted -> {
                            // Seal the prose block so the tool row reads as a
                            // separate scaffold line, the way the transcript
                            // interleaves them on Desktop.
                            currentAssistant(streaming = false)?.let { cache.putEntry(sessionId, it) }
                            assistantId = null
                            cache.putEntry(
                                sessionId,
                                ToolActivity(
                                    id = "$sessionId-$generation-${event.id}",
                                    label = event.label,
                                    detail = event.detail,
                                    state = ToolState.Running,
                                ),
                            )
                        }

                        is TurnEvent.ToolFinished -> {
                            cache.putEntry(
                                sessionId,
                                ToolActivity(
                                    id = "$sessionId-$generation-${event.id}",
                                    label = event.label(sessionId, generation),
                                    detail = event.detail,
                                    state = if (event.failed) ToolState.Failed else ToolState.Done,
                                    elapsedSeconds = 1,
                                ),
                            )
                        }

                        TurnEvent.Completed -> {
                            currentAssistant(streaming = false)?.let { cache.putEntry(sessionId, it) }
                            finish(sessionId, generation, foregroundAware = true)
                        }
                    }
                }
            } finally {
                if (generations[sessionId] == generation && sessionId in runningSessions.value) {
                    currentAssistant(streaming = false, stopped = true)?.let { cache.putEntry(sessionId, it) }
                    finish(sessionId, generation, foregroundAware = true)
                }
            }
        }
    }

    /** Cancellation is synchronous in the UI even though cleanup is async. */
    fun stop() {
        val sessionId = activeSessionId.value ?: return
        jobs.remove(sessionId)?.cancel()
    }

    private fun finish(sessionId: String, generation: Int, foregroundAware: Boolean) {
        if (generations[sessionId] != generation) return
        runningSessions.update { it - sessionId }
        jobs.remove(sessionId)
        val looking = foregroundAware && activeSessionId.value == sessionId
        val session = cache.session(sessionId) ?: return
        cache.upsertSession(
            session.copy(
                status = if (looking) SessionStatus.Idle else SessionStatus.Unread,
                lastActiveAtMillis = clock(),
            ),
        )
    }

    private fun markRead(id: String) {
        val session = cache.session(id) ?: return
        if (session.status == SessionStatus.Unread) {
            cache.upsertSession(session.copy(status = SessionStatus.Idle))
        }
    }

    private fun touchSession(id: String, preview: String, status: SessionStatus, at: Long) {
        val session = cache.session(id) ?: return
        cache.upsertSession(
            session.copy(
                preview = preview,
                status = status,
                lastActiveAtMillis = at,
                title = if (session.title == "New session") preview.take(48) else session.title,
            ),
        )
    }

    /** The finished row keeps the label the running row showed. */
    private fun TurnEvent.ToolFinished.label(sessionId: String, generation: Int): String =
        cache.transcript(sessionId)
            .filterIsInstance<ToolActivity>()
            .firstOrNull { it.id == "$sessionId-$generation-$id" }
            ?.label
            ?: id

    companion object {
        /**
         * The app's single factory. Phase 1 has one graph and no DI framework:
         * a container would be indirection over a single wiring site.
         */
        fun factory(cache: SessionCache, initialSessionId: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(cache).also { vm ->
                        initialSessionId?.let(vm::selectSession)
                    } as T
            }
    }
}
