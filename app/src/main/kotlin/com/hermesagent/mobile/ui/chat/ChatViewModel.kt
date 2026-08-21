package com.hermesagent.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.prefs.SidebarViewStore
import com.hermesagent.mobile.data.prefs.TransientSidebarViewStore
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.data.session.matchesProjectQuery
import com.hermesagent.mobile.data.session.sortProjectsForOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val sessionRows: List<SessionListRow> = emptyList(),
    val projects: List<ProjectSummary> = emptyList(),
    val projectsAvailable: Boolean? = null,
    val sidebarGrouping: SidebarGrouping = SidebarGrouping.Date,
    val selectedProject: ProjectSummary? = null,
    val projectLoading: Boolean = false,
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
    private val sidebarViewStore: SidebarViewStore = TransientSidebarViewStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val draft = MutableStateFlow("")
    private val activeSessionId = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val selectedProjectId = MutableStateFlow<String?>(null)
    private val projectLoadingId = MutableStateFlow<String?>(null)
    private val sidebarGrouping = MutableStateFlow(SidebarGrouping.Date)
    private val createdProjectBySession = mutableMapOf<String, String>()
    private var navigationGeneration = 0L
    private var sidebarGroupingGeneration = 0L
    private var choseInitialSession = false
    private var previousStatuses = emptyMap<String, SessionStatus>()

    val uiState: StateFlow<ChatUiState> = combine(
        cache.state,
        query,
        draft,
        activeSessionId,
        combine(
            repository.connectionState,
            notice,
            selectedProjectId,
            projectLoadingId,
            sidebarGrouping,
        ) { connection, message, projectId, loadingId, grouping ->
            NavigationState(connection, message, projectId, loadingId, grouping)
        },
    ) { cacheState, queryText, draftText, activeId, navigation ->
        val running = cacheState.sessions.values.count { it.status in PROMPT_BLOCKING_STATUSES }
        // SessionCache publishes this alias in the same atomic update that
        // moves a compressed parent to its canonical tip. Resolve it here so
        // the later navigation event cannot create a blank intermediate frame.
        val displayedActiveId = activeId?.let { cacheState.rehomes[it] ?: it }
        val active = displayedActiveId?.let(cacheState.sessions::get)
        val selectedProject = navigation.projectId?.let(cacheState.projects.projects::get)
        val scopedSessions = selectedProject?.id
            ?.let { cacheState.projects.memberships[it].orEmpty() }
            ?.mapNotNull(cacheState.sessions::get)
            ?: cacheState.sessions.values.toList()
        val projects = if (selectedProject == null) {
            sortProjectsForOverview(
                cacheState.projects.projects.values,
                cacheState.projects.activeProjectId,
            ).map { project ->
                project.copy(
                    previewSessions = project.previewSessions.map { preview ->
                        cacheState.sessions[preview.id] ?: preview
                    },
                )
            }.filter { it.matchesProjectQuery(queryText) }
        } else {
            emptyList()
        }
        ChatUiState(
            sessionRows = buildSessionRows(
                sessions = scopedSessions,
                nowMillis = clock(),
                query = queryText,
            ),
            projects = projects,
            projectsAvailable = cacheState.projects.available,
            sidebarGrouping = navigation.grouping,
            selectedProject = selectedProject,
            projectLoading = selectedProject != null && navigation.loadingProjectId == selectedProject.id,
            activeSession = active,
            transcript = displayedActiveId?.let(cacheState.transcripts::get).orEmpty(),
            query = queryText,
            draft = draftText,
            isStreaming = active?.status in STREAMING_STATUSES,
            runningCount = running,
            connection = navigation.connection,
            notice = navigation.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            val restoreGeneration = sidebarGroupingGeneration
            val restored = sidebarViewStore.sidebarGrouping.first()
            if (sidebarGroupingGeneration == restoreGeneration) sidebarGrouping.value = restored
        }
        viewModelScope.launch {
            repository.sessionRehomes.collect { rehome ->
                adoptCanonicalSession(rehome.oldDurableId, rehome.newDurableId)
            }
        }
        viewModelScope.launch {
            cache.state.collect { state ->
                val projectId = selectedProjectId.value
                if (state.projects.available == true && projectId != null && projectId !in state.projects.projects) {
                    navigationGeneration += 1
                    selectedProjectId.value = null
                    query.value = ""
                    notice.value = "That project is no longer available."
                }
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

    fun setSidebarGrouping(grouping: SidebarGrouping) {
        sidebarGroupingGeneration += 1
        if (sidebarGrouping.value == grouping) return
        navigationGeneration += 1
        sidebarGrouping.value = grouping
        selectedProjectId.value = null
        projectLoadingId.value = null
        query.value = ""
        viewModelScope.launch {
            runCatching { sidebarViewStore.saveSidebarGrouping(grouping) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (sidebarGrouping.value == grouping) {
                        notice.value = "This sidebar view could not be saved. Try changing it again."
                    }
                }
        }
    }

    fun selectProject(id: String) {
        if (cache.state.value.projects.projects[id] == null) return
        navigationGeneration += 1
        selectedProjectId.value = id
        query.value = ""
        loadProject(id)
    }

    fun exitProject() {
        navigationGeneration += 1
        selectedProjectId.value = null
        projectLoadingId.value = null
        query.value = ""
    }

    fun createProject(name: String, folderPath: String) {
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
            notice.value = "Connect to a Gateway before creating a project."
            return
        }
        val createGeneration = ++navigationGeneration
        viewModelScope.launch {
            runCatching { repository.createProject(name, folderPath) }
                .onSuccess { outcome ->
                    if (navigationGeneration != createGeneration) return@onSuccess
                    if (!outcome.catalogRefreshed) {
                        notice.value = "The project was created, but Projects could not be refreshed. Reopen Sessions to refresh."
                        return@onSuccess
                    }
                    selectedProjectId.value = outcome.projectId
                    query.value = ""
                    loadProject(outcome.projectId)
                }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (navigationGeneration == createGeneration) {
                        notice.value = "The project could not be created. Check the name and remote folder, then try again."
                    }
                }
        }
    }

    /** Refresh when the compact drawer opens; the wide rail stays live through Gateway events. */
    fun refreshSessionNavigation() {
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) return
        viewModelScope.launch {
            runCatching { repository.refreshProjects() }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    notice.value = "Projects could not be refreshed. Try opening Sessions again."
                }
        }
    }

    private fun loadProject(id: String) {
        projectLoadingId.value = id
        notice.value = null
        viewModelScope.launch {
            runCatching { repository.openProject(id) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (selectedProjectId.value == id) {
                        notice.value = "This project could not be opened. Check the Gateway and try again."
                    }
                }
            if (projectLoadingId.value == id) projectLoadingId.value = null
        }
    }

    fun selectSession(id: String) {
        if (activeSessionId.value == id) return
        navigationGeneration += 1
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
            val projectId = selectedProjectId.value
            val workspacePath = projectId?.let { cache.state.value.projects.projects[it]?.path }
            runCatching { repository.createSession(workspacePath) }
                .onSuccess { id ->
                    if (projectId != null) createdProjectBySession[id] = projectId
                    rehome(id)
                }
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
        createdProjectBySession.remove(requestedId)?.let { projectId ->
            createdProjectBySession[canonicalId] = projectId
        }
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
                    GatewaySubmitOutcome.Accepted -> {
                        val projectId = createdProjectBySession.remove(sessionId) ?: selectedProjectId.value
                        if (projectId != null) runCatching { repository.refreshProjects() }
                    }
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

    private data class NavigationState(
        val connection: GatewayConnectionState,
        val notice: String?,
        val projectId: String?,
        val loadingProjectId: String?,
        val grouping: SidebarGrouping,
    )

    companion object {
        private val STREAMING_STATUSES = setOf(
            SessionStatus.Working,
            SessionStatus.Stalled,
        )
        private val PROMPT_BLOCKING_STATUSES = STREAMING_STATUSES + setOf(
            SessionStatus.Background,
            SessionStatus.NeedsInput,
        )

        fun factory(
            cache: SessionCache,
            repository: GatewaySessionRepository,
            sidebarViewStore: SidebarViewStore,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(cache, repository, sidebarViewStore) as T
            }
    }
}
