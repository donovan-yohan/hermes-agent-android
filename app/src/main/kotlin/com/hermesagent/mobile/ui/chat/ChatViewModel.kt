package com.hermesagent.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.draft.SessionDraftStore
import com.hermesagent.mobile.data.draft.TransientSessionDraftStore
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    /** Another durable session owns the app-wide single submitted turn. */
    val runningOwner: SessionSummary? = null,
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
    private val draftStore: SessionDraftStore = TransientSessionDraftStore(),
    /** Process scope survives navigation long enough to flush the private draft. */
    private val applicationDraftScope: CoroutineScope? = null,
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
    private var draftSnapshot = linkedMapOf<String, String>()
    private val draftStoreReady = CompletableDeferred<Unit>()
    /** IDs changed locally in this ViewModel; stale DataStore emissions cannot replace them. */
    private val locallyTouchedDrafts = mutableSetOf<String>()
    private var draftRevision = 0L
    private var draftWrite: kotlinx.coroutines.Job? = null

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
        val blocking = cacheState.sessions.values.filter { it.status in PROMPT_BLOCKING_STATUSES }
        val running = blocking.size
        // SessionCache publishes this alias in the same atomic update that
        // moves a compressed parent to its canonical tip. Resolve it here so
        // the later navigation event cannot create a blank intermediate frame.
        val displayedActiveId = activeId?.let { cacheState.rehomes[it] ?: it }
        val active = displayedActiveId?.let(cacheState.sessions::get)
        val otherOwners = blocking.filter { it.id != active?.id }
        val runningOwner = otherOwners.maxWithOrNull(compareBy<SessionSummary> { it.activityStartedAtMillis }.thenBy { it.id })
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
            runningOwner = runningOwner,
            connection = navigation.connection,
            notice = navigation.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            draftStore.drafts.collect { restored ->
                val merged = LinkedHashMap(restored)
                locallyTouchedDrafts.forEach { id ->
                    merged.remove(id)
                    draftSnapshot[id]?.takeIf(String::isNotBlank)?.let { merged[id] = it }
                }
                draftSnapshot = merged
                if (!draftStoreReady.isCompleted) draftStoreReady.complete(Unit)
                activeSessionId.value?.let { id ->
                    if (id !in locallyTouchedDrafts) draft.value = merged[id].orEmpty()
                }
            }
        }
        viewModelScope.launch {
            val restoreGeneration = sidebarGroupingGeneration
            val restored = sidebarViewStore.sidebarGrouping.first()
            if (sidebarGroupingGeneration == restoreGeneration) sidebarGrouping.value = restored
        }
        viewModelScope.launch {
            repository.sessionRehomes.collect { rehome ->
                draftStoreReady.await()
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
                        openAndAdopt(initialId)
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
        val id = activeSessionId.value ?: return
        rememberDraft(id, value)
        val revision = invalidatePendingDraftWrite()
        draftWrite = viewModelScope.launch {
            kotlinx.coroutines.delay(DRAFT_DEBOUNCE_MILLIS)
            if (revision == draftRevision && id == activeSessionId.value) draftStore.replace(id, value)
        }
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
        flushDraft()
        rehome(id)
        viewModelScope.launch {
            openAndAdopt(id)
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
                    flushDraft()
                    rehome(id)
                }
                .onFailure { notice.value = "A new session could not be started. Check the Gateway and try again." }
        }
    }

    private fun rehome(id: String?) {
        activeSessionId.value = id
        invalidatePendingDraftWrite()
        draft.value = id?.let(draftSnapshot::get).orEmpty()
        notice.value = null
        id?.let(::markRead)
    }

    /** Adopt a compressed session tip without clearing a draft for the same logical session. */
    private suspend fun adoptCanonicalSession(requestedId: String, canonicalId: String) {
        createdProjectBySession.remove(requestedId)?.let { projectId ->
            createdProjectBySession[canonicalId] = projectId
        }
        if (canonicalId == requestedId) return
        draftStoreReady.await()
        val transitionRevision = invalidatePendingDraftWrite()
        val source = draftSnapshot[requestedId]
        val destination = draftSnapshot[canonicalId]
        val sourceWasTouched = requestedId in locallyTouchedDrafts
        var winner = draftStore.migrateIfDestinationEmpty(requestedId, canonicalId, source)
            ?: destination
            ?: source
        val editedDuringTransition = activeSessionId.value == requestedId && draftRevision != transitionRevision
        if (editedDuringTransition) {
            winner = draftSnapshot[requestedId]
            draftStore.replace(canonicalId, winner.orEmpty())
            draftStore.replace(requestedId, "")
        }
        if ((destination.isNullOrBlank() && !winner.isNullOrBlank()) || editedDuringTransition) {
            draftSnapshot.remove(requestedId)
            draftSnapshot.remove(canonicalId)
            winner?.takeIf(String::isNotBlank)?.let { draftSnapshot[canonicalId] = it }
            locallyTouchedDrafts += requestedId
            if (sourceWasTouched || editedDuringTransition) locallyTouchedDrafts += canonicalId
        }
        if (activeSessionId.value != requestedId) return
        activeSessionId.value = canonicalId
        draft.value = winner.orEmpty()
        markRead(canonicalId)
    }

    fun submit() {
        val sessionId = activeSessionId.value ?: return
        val prompt = draft.value.trim()
        if (prompt.isEmpty() || uiState.value.isStreaming || uiState.value.runningCount > 0 ||
            repository.connectionState.value.status != GatewayConnectionStatus.Connected
        ) return

        draft.value = ""
        invalidatePendingDraftWrite()
        rememberDraft(sessionId, "")
        viewModelScope.launch { draftStore.replace(sessionId, "") }
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
                if (draftSnapshot[sessionId].isNullOrBlank()) {
                    rememberDraft(sessionId, prompt)
                    if (activeSessionId.value == sessionId && draft.value.isEmpty()) draft.value = prompt
                    viewModelScope.launch { draftStore.replace(sessionId, prompt) }
                }
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

    internal fun flushDraft() {
        val id = activeSessionId.value ?: return
        val text = draft.value
        invalidatePendingDraftWrite()
        rememberDraft(id, text)
        viewModelScope.launch { draftStore.replace(id, text) }
    }

    override fun onCleared() {
        val id = activeSessionId.value
        val text = draft.value
        if (id != null) applicationDraftScope?.launch { draftStore.replace(id, text) }
        super.onCleared()
    }

    private fun rememberDraft(id: String, text: String) {
        draftSnapshot.remove(id)
        if (text.isNotBlank()) draftSnapshot[id] = text
        locallyTouchedDrafts += id
    }

    private fun invalidatePendingDraftWrite(): Long {
        val revision = ++draftRevision
        draftWrite?.cancel()
        return revision
    }

    private suspend fun openAndAdopt(id: String) {
        try {
            adoptCanonicalSession(id, repository.openSession(id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (activeSessionId.value == id) {
                notice.value = "This session could not be opened. Check the Gateway and try again."
            }
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
        private const val DRAFT_DEBOUNCE_MILLIS = 400L
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
            draftStore: SessionDraftStore,
            draftScope: CoroutineScope,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(cache, repository, sidebarViewStore, draftStore, draftScope) as T
            }
    }
}
