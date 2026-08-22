package com.hermesagent.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.CompletionResult
import com.hermesagent.mobile.data.composer.CompletionTrigger
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ComposerReference
import com.hermesagent.mobile.data.composer.ControlMutationResult
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.NewSessionComposerOverrides
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.composer.SessionComposerControls
import com.hermesagent.mobile.data.draft.SessionDraftStore
import com.hermesagent.mobile.data.draft.TransientSessionDraftStore
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.data.prefs.ComposerControlsStore
import com.hermesagent.mobile.data.prefs.NewDraftComposerPreference
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Gateway catalog state, kept distinct from an empty-but-resolved catalog. */
sealed interface ComposerCatalogUiState {
    data object Loading : ComposerCatalogUiState
    data class Ready(val catalog: ModelCatalog) : ComposerCatalogUiState
    data class Error(val safeMessage: String) : ComposerCatalogUiState
}

/** One live control mutation at a time. Deferred means the next turn owns it. */
sealed interface ComposerMutationUiState {
    data object Idle : ComposerMutationUiState
    data object Saving : ComposerMutationUiState
    data object Deferred : ComposerMutationUiState
    data class Error(val safeMessage: String) : ComposerMutationUiState
}

/**
 * A completion answer is valid only for this editor generation and active
 * durable session. The editor owns the actual [replaceStart]/[replaceEnd]
 * replacement so its IME composition and selection never cross the ViewModel.
 */
data class CompletionUiState(
    val trigger: CompletionTrigger? = null,
    val query: String = "",
    val items: List<CompletionItem> = emptyList(),
    val replaceStart: Int = 0,
    val replaceEnd: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Nested composer state: persisted fresh-draft choices, live Gateway truth,
 * and transient completion/mutation work remain visibly separate.
 */
data class ComposerUiState(
    val catalog: ComposerCatalogUiState = ComposerCatalogUiState.Loading,
    val controls: ModelControlsSnapshot = ModelControlsSnapshot(),
    val isLiveSession: Boolean = false,
    val isManualNewDraft: Boolean = false,
    val mutation: ComposerMutationUiState = ComposerMutationUiState.Idle,
    val completion: CompletionUiState = CompletionUiState(),
)

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
    val composer: ComposerUiState = ComposerUiState(),
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
    private val composerControlsStore: ComposerControlsStore =
        com.hermesagent.mobile.data.prefs.TransientComposerControlsStore(),
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
    private val composer = MutableStateFlow(ComposerUiState())
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
    private var draftWrite: Job? = null
    private var composerScope: ComposerControlsScope? = null
    private var newDraftPreference: NewDraftComposerPreference? = null
    /** A delayed store snapshot cannot replace a choice made in this scope. */
    private var newDraftPreferenceTouched = false
    /** Gateway defaults are transient and never inherit a just-viewed live session. */
    private var newDraftDefaults = ModelControlsSnapshot()
    private var composerGeneration = 0L
    /** Fences live mutation replies independently of editor completion work. */
    private var liveMutationGeneration = 0L
    /** Latest authoritative partial session.info controls for the active durable session. */
    private var liveComposerControls: SessionComposerControls? = null
    private var inputGeneration = 0L
    private var composerLoad: Job? = null
    private var completionLoad: Job? = null
    private var preferenceLoad: Job? = null
    private var observedConnectionStatus: GatewayConnectionStatus? = null

    val uiState: StateFlow<ChatUiState> = combine(
        cache.state,
        query,
        draft,
        activeSessionId,
        combine(
            composer,
            combine(
                repository.connectionState,
                notice,
                selectedProjectId,
                projectLoadingId,
                sidebarGrouping,
            ) { connection, message, projectId, loadingId, grouping ->
                NavigationState(connection, message, projectId, loadingId, grouping)
            },
        ) { composerState, navigation ->
            navigation.copy(composer = composerState)
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
            composer = navigation.composer,
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
            repository.composerControls.collect(::applyComposerControls)
        }
        viewModelScope.launch {
            observeComposerScope()
        }
        viewModelScope.launch {
            repository.connectionState.collect { connection ->
                if (connection.status == observedConnectionStatus) return@collect
                observedConnectionStatus = connection.status
                invalidateComposerRuntimeState()
                if (connection.status == GatewayConnectionStatus.Connected) {
                    refreshComposer(activeSessionId.value)
                }
            }
        }
        viewModelScope.launch {
            cache.state.collect { state ->
                val projectId = selectedProjectId.value
                if (state.projects.available == true && projectId != null && projectId !in state.projects.projects) {
                    navigationGeneration += 1
                    invalidateCompletionState()
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

    /**
     * A manual draft pick must never cross remote endpoint/profile or SSH host
     * boundaries. ComposerControlsStore owns that normalized scope in
     * production; test-only stores use an isolated in-memory scope.
     */
    private suspend fun observeComposerScope() {
        composerControlsStore.activeScope.collect(::bindComposerScope)
    }

    private fun bindComposerScope(scope: ComposerControlsScope) {
        if (composerScope == scope) return
        composerScope = scope
        newDraftPreference = null
        newDraftPreferenceTouched = false
        newDraftDefaults = ModelControlsSnapshot()
        invalidateComposerRuntimeState()
        preferenceLoad?.cancel()
        preferenceLoad = viewModelScope.launch {
            composerControlsStore.preference(scope).collect { preference ->
                if (composerScope != scope) return@collect
                if (!newDraftPreferenceTouched) {
                    newDraftPreference = preference
                    if (activeSessionId.value == null) publishFreshDraftControls()
                }
            }
        }
        refreshComposer(activeSessionId.value)
    }

    private fun invalidateComposerRuntimeState() {
        composerGeneration += 1
        liveMutationGeneration += 1
        inputGeneration += 1
        liveComposerControls = null
        composerLoad?.cancel()
        completionLoad?.cancel()
        composer.value = ComposerUiState(
            catalog = ComposerCatalogUiState.Loading,
            controls = if (activeSessionId.value == null) freshDraftControls() else ModelControlsSnapshot(),
            isLiveSession = activeSessionId.value != null,
            isManualNewDraft = activeSessionId.value == null && hasManualNewDraftChoice(),
        )
    }

    private fun invalidateCompletionState() {
        inputGeneration += 1
        completionLoad?.cancel()
        composer.value = composer.value.copy(completion = CompletionUiState())
    }

    fun setQuery(value: String) {
        query.value = value
    }

    /**
     * The editor reports plain text plus offsets, never a Compose TextFieldValue.
     * That keeps the ViewModel UI-neutral while allowing the editor to retain
     * IME composition during an inline completion replacement.
     */
    fun onEditorSelectionChange(text: String, selectionStart: Int, selectionEnd: Int) {
        val safeStart = selectionStart.coerceIn(0, text.length)
        val safeEnd = selectionEnd.coerceIn(0, text.length)
        val request = completionRequest(text, safeStart, safeEnd)
        val generation = ++inputGeneration
        completionLoad?.cancel()
        if (request == null) {
            composer.value = composer.value.copy(completion = CompletionUiState())
            return
        }
        if (request.trigger == CompletionTrigger.Emoji) {
            // EmojiIndex is bundled with the Compose surface. The VM still
            // supplies the exact trigger/range and fences stale editor state.
            composer.value = composer.value.copy(
                completion = CompletionUiState(
                    trigger = request.trigger,
                    query = request.query,
                    replaceStart = request.start,
                    replaceEnd = request.end,
                ),
            )
            return
        }
        val durableId = activeSessionId.value
        val runtimeGeneration = composerGeneration
        composer.value = composer.value.copy(
            completion = CompletionUiState(
                trigger = request.trigger,
                query = request.query,
                replaceStart = request.start,
                replaceEnd = request.end,
                loading = true,
            ),
        )
        completionLoad = viewModelScope.launch {
            delay(COMPLETION_DEBOUNCE_MILLIS)
            val result: Result<CompletionResult> = try {
                Result.success(loadCompletion(request, durableId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            if (!isCurrentCompletion(generation, runtimeGeneration, durableId)) return@launch
            composer.value = composer.value.copy(
                completion = result.fold(
                    onSuccess = { answer ->
                        val slashOffset = (answer.replaceFrom ?: 0)
                            .coerceIn(0, request.end - request.start)
                        val replaceStart = if (
                            request.trigger == CompletionTrigger.Slash && slashOffset > 1
                        ) {
                            request.start + slashOffset
                        } else {
                            request.start
                        }
                        CompletionUiState(
                            trigger = request.trigger,
                            query = request.query,
                            items = answer.items,
                            replaceStart = replaceStart,
                            replaceEnd = request.end,
                        )
                    },
                    onFailure = {
                        CompletionUiState(
                            trigger = request.trigger,
                            query = request.query,
                            replaceStart = request.start,
                            replaceEnd = request.end,
                            error = "Suggestions could not be loaded. Keep typing or try again.",
                        )
                    },
                ),
            )
        }
    }

    /** The editor has already applied the canonical text replacement locally. */
    fun onCompletionSelected(@Suppress("UNUSED_PARAMETER") item: CompletionItem) {
        // Item is intentionally not re-serialized here: the editor's selected
        // range is the only authority for its text and IME composition.
        inputGeneration += 1
        completionLoad?.cancel()
        composer.value = composer.value.copy(completion = CompletionUiState())
    }

    /** URL/snippet insertion is performed at the editor caret, then echoed through setDraft. */
    fun onInsertText(text: String) {
        if (text.isBlank()) return
        inputGeneration += 1
        completionLoad?.cancel()
        composer.value = composer.value.copy(completion = CompletionUiState())
    }

    fun setDraft(value: String) {
        draft.value = value
        val id = activeSessionId.value ?: return
        rememberDraft(id, value)
        val revision = invalidatePendingDraftWrite()
        draftWrite = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MILLIS)
            if (revision == draftRevision && id == activeSessionId.value) persistDraft(id, value)
        }
    }

    fun selectModel(selection: ComposerModelSelection) {
        val liveId = activeSessionId.value
        if (liveId == null) {
            saveNewDraftPreference { current ->
                current.copy(selection = selection.copy(source = ComposerModelSelection.Source.Manual))
            }
            return
        }
        if (composer.value.mutation is ComposerMutationUiState.Saving) return
        mutateLiveControls(liveId, { snapshot -> snapshot.copy(selection = selection) }) {
            repository.setLiveModel(liveId, selection)
        }
    }

    fun selectReasoning(effort: ReasoningEffort) {
        val liveId = activeSessionId.value
        if (liveId == null) {
            saveNewDraftPreference { current -> current.copy(reasoning = effort) }
            return
        }
        if (composer.value.mutation is ComposerMutationUiState.Saving ||
            composer.value.mutation is ComposerMutationUiState.Deferred
        ) return
        mutateLiveControls(liveId, { snapshot -> snapshot.copy(reasoning = effort) }) {
            repository.setLiveReasoning(liveId, effort)
        }
    }

    fun selectFast(mode: FastMode) {
        val liveId = activeSessionId.value
        if (liveId == null) {
            saveNewDraftPreference { current -> current.copy(fast = mode) }
            return
        }
        if (composer.value.mutation is ComposerMutationUiState.Saving ||
            composer.value.mutation is ComposerMutationUiState.Deferred
        ) return
        mutateLiveControls(liveId, { snapshot -> snapshot.copy(fast = mode) }) {
            repository.setLiveFast(liveId, mode)
        }
    }

    private fun saveNewDraftPreference(
        transform: (NewDraftComposerPreference) -> NewDraftComposerPreference,
    ) {
        val next = transform(newDraftPreference ?: NewDraftComposerPreference())
        newDraftPreference = next
        newDraftPreferenceTouched = true
        publishFreshDraftControls()
        val scope = composerScope ?: return
        viewModelScope.launch {
            runCatching { composerControlsStore.saveManual(scope, next) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (composerScope == scope && activeSessionId.value == null) {
                        notice.value = "This new-chat choice will not be remembered after you leave this Gateway."
                    }
                }
        }
    }

    private fun mutateLiveControls(
        durableId: String,
        update: (ModelControlsSnapshot) -> ModelControlsSnapshot,
        request: suspend () -> ControlMutationResult,
    ) {
        if (composer.value.mutation is ComposerMutationUiState.Saving) return
        val before = composer.value
        val generation = composerGeneration
        val mutationGeneration = ++liveMutationGeneration
        composerLoad?.cancel()
        composer.value = before.copy(
            controls = update(before.controls),
            isLiveSession = true,
            isManualNewDraft = false,
            mutation = ComposerMutationUiState.Saving,
        )
        viewModelScope.launch {
            val result = try {
                request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                ControlMutationResult.Rejected("Hermes could not update this control. Check the Gateway and try again.")
            }
            if (
                composerGeneration != generation ||
                liveMutationGeneration != mutationGeneration ||
                activeSessionId.value != durableId
            ) return@launch
            when (result) {
                ControlMutationResult.Applied -> {
                    composer.value = composer.value.copy(mutation = ComposerMutationUiState.Idle)
                }
                ControlMutationResult.Deferred -> {
                    composer.value = composer.value.copy(mutation = ComposerMutationUiState.Deferred)
                }
                is ControlMutationResult.Rejected -> {
                    composer.value = before.copy(
                        mutation = ComposerMutationUiState.Error(result.safeMessage.ifBlank {
                            "Hermes could not update this control. Try again."
                        }),
                    )
                }
            }
        }
    }

    fun setSidebarGrouping(grouping: SidebarGrouping) {
        sidebarGroupingGeneration += 1
        if (sidebarGrouping.value == grouping) return
        navigationGeneration += 1
        invalidateCompletionState()
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
        invalidateCompletionState()
        selectedProjectId.value = id
        query.value = ""
        loadProject(id)
    }

    fun exitProject() {
        navigationGeneration += 1
        invalidateCompletionState()
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
                    invalidateCompletionState()
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
        // Session creation can suspend while the Gateway establishes the
        // runtime. Capture every visible fresh-draft control, including
        // Gateway-seeded defaults, before that await so a later picker change
        // cannot alter this request.
        val overrides = newSessionOverrides()
        val createdControls = composer.value.controls
        val createdCatalog = composer.value.catalog
        viewModelScope.launch {
            val projectId = selectedProjectId.value
            val workspacePath = projectId?.let { cache.state.value.projects.projects[it]?.path }
            try {
                val id = repository.createSession(workspacePath, overrides)
                if (projectId != null) createdProjectBySession[id] = projectId
                flushDraft()
                rehome(id)
                // session.create accepted this exact snapshot. A pre-build
                // model.options response still reports the profile default,
                // so keep the accepted create controls until session.info
                // publishes the session's effective runtime state.
                composer.value = ComposerUiState(
                    catalog = createdCatalog,
                    controls = createdControls,
                    isLiveSession = true,
                )
                if (createdCatalog !is ComposerCatalogUiState.Ready) {
                    refreshComposer(id, retainControlsUntilSessionInfo = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                notice.value = "A new session could not be started. Check the Gateway and try again."
            }
        }
    }

    private fun rehome(id: String?) {
        activeSessionId.value = id
        invalidateComposerRuntimeState()
        invalidatePendingDraftWrite()
        draft.value = id?.let(draftSnapshot::get).orEmpty()
        notice.value = null
        id?.let(::markRead)
        if (id == null) refreshComposer(null)
    }

    /** Adopt a compressed session tip without clearing a draft for the same logical session. */
    private suspend fun adoptCanonicalSession(requestedId: String, canonicalId: String) {
        createdProjectBySession.remove(requestedId)?.let { projectId ->
            createdProjectBySession[canonicalId] = projectId
        }
        if (canonicalId == requestedId) return
        draftStoreReady.await()
        val transitionRevision = invalidatePendingDraftWrite()
        val sourceWasTouched = requestedId in locallyTouchedDrafts
        val source = if (sourceWasTouched) draftSnapshot[requestedId].orEmpty() else draftSnapshot[requestedId]
        val destination = draftSnapshot[canonicalId]
        var winner = migrateDraft(requestedId, canonicalId, source)
            ?: destination
            ?: source
        val editedDuringTransition = activeSessionId.value == requestedId && draftRevision != transitionRevision
        if (editedDuringTransition) {
            winner = draftSnapshot[requestedId]
            persistDraft(canonicalId, winner.orEmpty())
            persistDraft(requestedId, "")
        }
        if ((destination.isNullOrBlank() && !winner.isNullOrBlank()) || editedDuringTransition) {
            draftSnapshot.remove(requestedId)
            draftSnapshot.remove(canonicalId)
            winner?.takeIf(String::isNotBlank)?.let { draftSnapshot[canonicalId] = it }
            locallyTouchedDrafts += requestedId
            if (sourceWasTouched || editedDuringTransition) locallyTouchedDrafts += canonicalId
        }
        if (activeSessionId.value != requestedId) return
        // Compression changes only the durable key. Keep the accumulated
        // session.info authority attached to the same logical session so a
        // canonical-id event cannot collide with the old presence patch.
        liveComposerControls = liveComposerControls?.copy(durableId = canonicalId)
        activeSessionId.value = canonicalId
        draft.value = winner.orEmpty()
        markRead(canonicalId)
        refreshComposer(canonicalId)
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
        viewModelScope.launch { persistDraft(sessionId, "") }
        notice.value = null
        viewModelScope.launch {
            try {
                when (repository.submit(sessionId, prompt)) {
                    GatewaySubmitOutcome.Accepted -> {
                        if (composer.value.mutation is ComposerMutationUiState.Deferred) {
                            composer.value = composer.value.copy(mutation = ComposerMutationUiState.Idle)
                        }
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
                    viewModelScope.launch { persistDraft(sessionId, prompt) }
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
        viewModelScope.launch { persistDraft(id, text) }
    }

    override fun onCleared() {
        val id = activeSessionId.value
        val text = draft.value
        if (id != null) applicationDraftScope?.launch { persistDraft(id, text) }
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
            val canonicalId = repository.openSession(id)
            adoptCanonicalSession(id, canonicalId)
            refreshComposer(canonicalId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (activeSessionId.value == id) {
                notice.value = "This session could not be opened. Check the Gateway and try again."
            }
        }
    }

    private fun refreshComposer(
        durableId: String?,
        retainControlsUntilSessionInfo: Boolean = false,
    ) {
        val live = durableId != null
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
            if (!live) publishFreshDraftControls()
            return
        }
        val generation = ++composerGeneration
        liveMutationGeneration += 1
        composerLoad?.cancel()
        composer.value = composer.value.copy(
            catalog = ComposerCatalogUiState.Loading,
            controls = if (live && retainControlsUntilSessionInfo) composer.value.controls
            else if (live) liveComposerControls?.applyTo(ModelControlsSnapshot()) ?: ModelControlsSnapshot()
            else freshDraftControls(),
            isLiveSession = live,
            isManualNewDraft = !live && hasManualNewDraftChoice(),
            mutation = ComposerMutationUiState.Idle,
            completion = CompletionUiState(),
        )
        composerLoad = viewModelScope.launch {
            val loaded = try {
                repository.loadComposerState(durableId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (composerGeneration != generation || activeSessionId.value != durableId) return@launch
            if (loaded == null) {
                composer.value = composer.value.copy(
                    catalog = ComposerCatalogUiState.Error("Model controls could not be loaded. Reopen them to try again."),
                    controls = if (live) composer.value.controls else freshDraftControls(),
                )
                return@launch
            }
            val catalog = loaded.catalog
            val defaults = loaded.controls
            if (!live) newDraftDefaults = defaults
            val liveBaseline = if (retainControlsUntilSessionInfo) composer.value.controls else defaults
            composer.value = composer.value.copy(
                catalog = ComposerCatalogUiState.Ready(catalog),
                controls = if (live) liveComposerControls?.applyTo(liveBaseline) ?: liveBaseline
                else freshDraftControls(defaults),
                isLiveSession = live,
                isManualNewDraft = !live && hasManualNewDraftChoice(),
            )
        }
    }

    private fun publishFreshDraftControls() {
        if (activeSessionId.value != null) return
        composer.value = composer.value.copy(
            controls = freshDraftControls(),
            isLiveSession = false,
            isManualNewDraft = hasManualNewDraftChoice(),
        )
    }

    private fun freshDraftControls(defaults: ModelControlsSnapshot = newDraftDefaults): ModelControlsSnapshot {
        val manual = newDraftPreference
        return ModelControlsSnapshot(
            selection = manual?.selection ?: defaults.selection,
            reasoning = manual?.reasoning ?: defaults.reasoning,
            fast = manual?.fast ?: defaults.fast,
        )
    }

    private fun hasManualNewDraftChoice(): Boolean = newDraftPreference?.let {
        it.selection != null || it.reasoning != null || it.fast != null
    } == true

    private fun newSessionOverrides(): NewSessionComposerOverrides? {
        val visible = if (activeSessionId.value == null) composer.value.controls else freshDraftControls()
        if (visible.selection == null && visible.reasoning == null && visible.fast == null) return null
        return NewSessionComposerOverrides(
            selection = visible.selection,
            reasoning = visible.reasoning,
            fast = visible.fast,
        )
    }

    private fun applyComposerControls(event: SessionComposerControls) {
        if (activeSessionId.value != event.durableId) return
        // `session.info` is authoritative even if it overtakes the matching
        // mutation RPC response. Keep an in-flight catalog hydration alive:
        // it still owns provider/capability metadata, and its controls are
        // overlaid with this authoritative partial event before publication.
        liveMutationGeneration += 1
        liveComposerControls = liveComposerControls?.overlay(event) ?: event
        val current = composer.value
        val keepDeferred = current.mutation is ComposerMutationUiState.Deferred
        val authoritative = event.applyTo(current.controls)
        composer.value = composer.value.copy(
            // Modern Gateways report the pending model in session.info; older
            // busy-refusal Gateways report the still-running model. In both
            // cases the local deferred pick remains the next-turn contract
            // until an accepted submit crosses that boundary.
            controls = if (keepDeferred) authoritative.copy(selection = current.controls.selection)
            else authoritative,
            isLiveSession = true,
            isManualNewDraft = false,
            mutation = if (keepDeferred) ComposerMutationUiState.Deferred else ComposerMutationUiState.Idle,
        )
    }

    private fun completionRequest(text: String, start: Int, end: Int): CompletionRequest? {
        if (start != end) return null
        val before = text.substring(0, start)
        val slash = SLASH_COMPLETION.find(before)
        if (slash != null) {
            val tokenGroup = requireNotNull(slash.groups[1])
            val token = tokenGroup.value
            return CompletionRequest(
                trigger = CompletionTrigger.Slash,
                query = token.drop(1),
                requestText = token,
                start = tokenGroup.range.first,
                end = start,
            )
        }
        val at = AT_COMPLETION.find(before)
        if (at != null) {
            val tokenGroup = requireNotNull(at.groups[1])
            val token = tokenGroup.value
            return CompletionRequest(
                trigger = CompletionTrigger.At,
                query = token.drop(1),
                requestText = token,
                start = tokenGroup.range.first,
                end = start,
            )
        }
        val emoji = EMOJI_COMPLETION.find(before)
        if (emoji != null) {
            val tokenGroup = requireNotNull(emoji.groups[1])
            val token = tokenGroup.value
            return CompletionRequest(
                trigger = CompletionTrigger.Emoji,
                query = token.drop(1),
                requestText = token,
                start = tokenGroup.range.first,
                end = start,
            )
        }
        return null
    }

    private suspend fun loadCompletion(request: CompletionRequest, durableId: String?): CompletionResult = when (request.trigger) {
        CompletionTrigger.Slash -> repository.completeSlash(request.requestText)
        CompletionTrigger.At -> {
            val static = staticAtCompletions(request.query)
            if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
                CompletionResult(static)
            }
            else {
                // A live runtime owns its cwd; an empty explicit cwd lets the
                // Gateway resolve that session-scoped workspace. Only a fresh
                // project draft supplies the selected project's path.
                val cwd = if (durableId == null) {
                    selectedProjectId.value
                        ?.let { cache.state.value.projects.projects[it]?.path }
                        .orEmpty()
                } else {
                    ""
                }
                val remote = repository.completePath(durableId, request.requestText, cwd)
                CompletionResult(
                    items = (static + remote.items).distinctBy(CompletionItem::text),
                    replaceFrom = remote.replaceFrom,
                )
            }
        }
        CompletionTrigger.Emoji -> CompletionResult()
    }

    private fun staticAtCompletions(query: String): List<CompletionItem> {
        val lower = query.lowercase()
        val starters = listOf(
            CompletionItem("@file:", "@file:", "Attach a remote file reference", "file"),
            CompletionItem("@folder:", "@folder:", "Attach a remote folder reference", "folder"),
            CompletionItem("@url:", "@url:", "Attach a URL reference", "url"),
            CompletionItem("@git:", "@git:", "Attach git context", "git"),
            CompletionItem("@session:", "@session:", "Reference a session", "session"),
        ).filter { it.text.removePrefix("@").startsWith(lower) }
        // session.list is scoped to one Gateway profile, but its compact rows
        // omit profile_name. Reuse the profile reported by any opened sibling
        // instead of silently rewriting every reference to `default`.
        val scopedProfile = cache.state.value.sessions.values
            .asSequence()
            .mapNotNull { it.remoteProfile?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            ?: "default"
        val sessions = cache.state.value.sessions.values
            .sortedByDescending(SessionSummary::lastActiveAtMillis)
            .asSequence()
            .map { session ->
                val profile = session.remoteProfile?.trim()?.takeIf(String::isNotEmpty) ?: scopedProfile
                CompletionItem(
                    text = ComposerReference.Session("$profile/${session.id}").wireText,
                    display = session.title.ifBlank { "Session ${session.id.take(8)}" },
                    detail = "Session reference",
                    kind = "session",
                )
            }
            .filter { item -> !query.isNotBlank() || item.text.contains(lower, ignoreCase = true) || item.display.contains(lower, ignoreCase = true) }
            .take(7)
            .toList()
        return starters + sessions
    }

    private fun isCurrentCompletion(
        input: Long,
        runtime: Long,
        durableId: String?,
    ): Boolean = inputGeneration == input && composerGeneration == runtime && activeSessionId.value == durableId

    private data class CompletionRequest(
        val trigger: CompletionTrigger,
        val query: String,
        val requestText: String,
        val start: Int,
        val end: Int,
    )

    private suspend fun persistDraft(id: String, text: String) {
        try {
            draftStore.replace(id, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Draft persistence is best-effort; draftSnapshot remains authoritative for this process.
        }
    }

    private suspend fun migrateDraft(fromId: String, toId: String, sourceText: String?): String? = try {
        draftStore.migrateIfDestinationEmpty(fromId, toId, sourceText)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
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
        val composer: ComposerUiState = ComposerUiState(),
    )

    companion object {
        private const val DRAFT_DEBOUNCE_MILLIS = 400L
        private const val COMPLETION_DEBOUNCE_MILLIS = 120L
        /** A slash directive may include arguments; @ and : stay one token. */
        private val SLASH_COMPLETION = Regex("(?:^|\\s)(/[^\\n]*)$")
        private val AT_COMPLETION = Regex("(?:^|\\s)(@[^\\s]*)$")
        private val EMOJI_COMPLETION = Regex("(?:^|\\s)(:[^\\s:]*)$")
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
            composerControlsStore: ComposerControlsStore,
            draftStore: SessionDraftStore,
            draftScope: CoroutineScope,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(
                        cache = cache,
                        repository = repository,
                        sidebarViewStore = sidebarViewStore,
                        composerControlsStore = composerControlsStore,
                        draftStore = draftStore,
                        applicationDraftScope = draftScope,
                    ) as T
            }
    }
}
