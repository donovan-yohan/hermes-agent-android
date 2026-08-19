package com.hermesagent.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.buildSessionRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessionRows: List<SessionListRow> = emptyList(),
    val activeSession: SessionSummary? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val query: String = "",
    val draft: String = "",
    val isStreaming: Boolean = false,
    val runningCount: Int = 0,
    val connection: GatewayConnectionState = GatewayConnectionState(),
    val notice: String? = null,
) {
    val canCreateSession: Boolean
        get() = connection.status == GatewayConnectionStatus.Connected
    val canSend: Boolean
        get() = canCreateSession &&
            activeSession != null && draft.isNotBlank() && !isStreaming && runningCount == 0
    val transcriptIsEmpty: Boolean get() = transcript.isEmpty()
    val liveStatusText: String? get() = activeSession?.progress?.text
}

/** UI-only chat state over the process-scoped live Gateway repository/cache. */
internal class ChatViewModel(
    private val cache: SessionCache,
    private val repository: GatewaySessionRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val draft = MutableStateFlow("")
    private val activeSessionId = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private var choseInitialSession = false
    private var previousStatuses = emptyMap<String, SessionStatus>()

    val uiState: StateFlow<ChatUiState> = combine(
        cache.state,
        query,
        draft,
        activeSessionId,
        combine(repository.connectionState, notice) { connection, message -> connection to message },
    ) { cacheState, queryText, draftText, activeId, (connection, message) ->
        val running = cacheState.sessions.values.count { it.status in PROMPT_BLOCKING_STATUSES }
        // SessionCache publishes this alias in the same atomic update that
        // moves a compressed parent to its canonical tip. Resolve it here so
        // the later navigation event cannot create a blank intermediate frame.
        val displayedActiveId = activeId?.let { cacheState.rehomes[it] ?: it }
        val active = displayedActiveId?.let(cacheState.sessions::get)
        ChatUiState(
            sessionRows = buildSessionRows(
                sessions = cacheState.sessions.values,
                nowMillis = clock(),
                query = queryText,
            ),
            activeSession = active,
            transcript = displayedActiveId?.let(cacheState.transcripts::get).orEmpty(),
            query = queryText,
            draft = draftText,
            isStreaming = active?.status in STREAMING_STATUSES,
            runningCount = running,
            connection = connection,
            notice = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            repository.sessionRehomes.collect { rehome ->
                adoptCanonicalSession(rehome.oldDurableId, rehome.newDurableId)
            }
        }
        viewModelScope.launch {
            cache.state.collect { state ->
                if (!choseInitialSession && activeSessionId.value == null && state.sessions.isNotEmpty()) {
                    choseInitialSession = true
                    state.sessions.values.maxByOrNull { it.lastActiveAtMillis }?.id?.let { initialId ->
                        rehome(initialId)
                        runCatching { repository.openSession(initialId) }
                            .onSuccess { canonicalId -> adoptCanonicalSession(initialId, canonicalId) }
                            .onFailure {
                                if (activeSessionId.value == initialId) {
                                    notice.value = "This session could not be opened. Check the Gateway and try again."
                                }
                            }
                    }
                }
                for ((id, session) in state.sessions) {
                    if (previousStatuses[id] in PROMPT_BLOCKING_STATUSES &&
                        session.status == SessionStatus.Idle && activeSessionId.value != id
                    ) {
                        cache.upsertSession(session.copy(status = SessionStatus.Unread))
                    }
                }
                previousStatuses = state.sessions.mapValues { it.value.status }
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setDraft(value: String) {
        draft.value = value
    }

    fun selectSession(id: String) {
        if (activeSessionId.value == id) return
        rehome(id)
        viewModelScope.launch {
            runCatching { repository.openSession(id) }
                .onSuccess { canonicalId -> adoptCanonicalSession(id, canonicalId) }
                .onFailure {
                    if (activeSessionId.value == id) {
                        notice.value = "This session could not be opened. Check the Gateway and try again."
                    }
                }
        }
    }

    fun createSession() {
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
            notice.value = "Connect to a Gateway before starting a session."
            return
        }
        viewModelScope.launch {
            runCatching { repository.createSession() }
                .onSuccess(::rehome)
                .onFailure { notice.value = "A new session could not be started. Check the Gateway and try again." }
        }
    }

    private fun rehome(id: String?) {
        activeSessionId.value = id
        draft.value = ""
        notice.value = null
        id?.let(::markRead)
    }

    /** Adopt a compressed session tip without clearing a draft for the same logical session. */
    private fun adoptCanonicalSession(requestedId: String, canonicalId: String) {
        if (activeSessionId.value != requestedId || canonicalId == requestedId) return
        activeSessionId.value = canonicalId
        markRead(canonicalId)
    }

    fun submit() {
        val sessionId = activeSessionId.value ?: return
        val prompt = draft.value.trim()
        if (prompt.isEmpty() || uiState.value.isStreaming || uiState.value.runningCount > 0 ||
            repository.connectionState.value.status != GatewayConnectionStatus.Connected
        ) return

        draft.value = ""
        notice.value = null
        viewModelScope.launch {
            try {
                when (repository.submit(sessionId, prompt)) {
                    GatewaySubmitOutcome.Accepted -> Unit
                    GatewaySubmitOutcome.Ambiguous -> {
                        notice.value = "This message may have been sent. Check this session and wait for Hermes before trying again."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                notice.value = "The message was not sent. Reconnect to the Gateway and try again."
                if (activeSessionId.value == sessionId && draft.value.isEmpty()) draft.value = prompt
            }
        }
    }

    fun stop() {
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            runCatching { repository.interrupt(sessionId) }
                .onFailure { notice.value = "Hermes could not be stopped. Check the Gateway connection." }
        }
    }

    private fun markRead(id: String) {
        val session = cache.session(id) ?: return
        if (session.status == SessionStatus.Unread) cache.upsertSession(session.copy(status = SessionStatus.Idle))
    }

    companion object {
        private val STREAMING_STATUSES = setOf(
            SessionStatus.Working,
            SessionStatus.Stalled,
        )
        private val PROMPT_BLOCKING_STATUSES = STREAMING_STATUSES + setOf(
            SessionStatus.Background,
            SessionStatus.NeedsInput,
        )

        fun factory(cache: SessionCache, repository: GatewaySessionRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(cache, repository) as T
            }
    }
}
