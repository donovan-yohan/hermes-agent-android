package com.hermesagent.mobile.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.voice.VoiceUiState
import com.hermesagent.mobile.data.voice.TranscriptionResult
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.CompletionResult
import com.hermesagent.mobile.data.composer.CompletionTrigger
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ComposerDraftChange
import com.hermesagent.mobile.data.composer.ComposerHistoryBrowseState
import com.hermesagent.mobile.data.composer.ComposerHistoryController
import com.hermesagent.mobile.data.composer.ComposerQueueController
import com.hermesagent.mobile.data.composer.ComposerQueueDrainResult
import com.hermesagent.mobile.data.composer.ComposerQueueMutation
import com.hermesagent.mobile.data.composer.ComposerQueueScope
import com.hermesagent.mobile.data.composer.ComposerQueueState
import com.hermesagent.mobile.data.composer.ComposerQueueSubmitter
import com.hermesagent.mobile.data.composer.ComposerUndoRedoState
import com.hermesagent.mobile.data.composer.QueueEditSnapshot
import com.hermesagent.mobile.data.composer.QueueSubmissionOutcome
import com.hermesagent.mobile.data.composer.QueuedPrompt
import com.hermesagent.mobile.data.composer.QueuedPromptDelivery
import com.hermesagent.mobile.data.composer.SavedStateComposerHistoryBrowseStore
import com.hermesagent.mobile.data.composer.TransientComposerHistoryBrowseStore
import com.hermesagent.mobile.data.composer.TransientComposerQueueStore
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
import com.hermesagent.mobile.data.attachments.AttachmentEncoding
import com.hermesagent.mobile.data.attachments.AttachmentKind
import com.hermesagent.mobile.data.attachments.AttachmentPolicy
import com.hermesagent.mobile.data.attachments.AttachmentReader
import com.hermesagent.mobile.data.attachments.AttachmentReadResult
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.data.attachments.OutgoingAttachment
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayGoalStatusOutcome
import com.hermesagent.mobile.data.gateway.GatewayProcessKillOutcome
import com.hermesagent.mobile.data.gateway.GatewayProcessListOutcome
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.GatewayRedirectOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val runtime: ComposerRuntimeUiState = ComposerRuntimeUiState(),
)

/** Local, durable-queue-aware composer behavior projected alongside Gateway truth. */
data class ComposerRuntimeUiState(
    val activeDurableId: String? = null,
    val busyKind: ComposerBusyKind = ComposerBusyKind.Idle,
    val queueEntries: List<QueuedPrompt> = emptyList(),
    val queueParked: Boolean = false,
    val queueEditingEntryId: String? = null,
    val queueEditingText: String = "",
    val canRedirect: Boolean = false,
    val canQueue: Boolean = false,
    val historyBrowse: ComposerHistoryBrowseState? = null,
    val undoRedo: ComposerUndoRedoState = ComposerUndoRedoState(),
    /** The required action parked in this session, if any. Repository memory only. */
    val pendingInput: PendingInputRequest? = null,
    /** Locally acquired attachment drafts for this session; memory-only. */
    val attachments: List<ComposerAttachmentDraft> = emptyList(),
)

/** A pending action owned by a session other than the one on screen. */
data class BackgroundPendingInput(
    val durableSessionId: String,
    val sessionTitle: String,
    val kind: PendingInputKind,
)

data class ChatUiState(
    val voice: VoiceUiState = VoiceUiState.Idle,
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
    /** A required action parked in a non-visible session. */
    val backgroundPendingInput: BackgroundPendingInput? = null,
    val connection: GatewayConnectionState = GatewayConnectionState(),
    val notice: String? = null,
    val composer: ComposerUiState = ComposerUiState(),
) {
    val canCreateSession: Boolean
        get() = connection.status == GatewayConnectionStatus.Connected
    val canSend: Boolean
        get() = canCreateSession &&
            activeSession?.status == SessionStatus.Idle && draft.isNotBlank()
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
    private val composerQueueController: ComposerQueueController = transientQueueController(),
    /** Switches the backing store before a new endpoint/profile can see or mutate its queue. */
    private val switchComposerQueueScope: suspend (ComposerQueueScope) -> Unit = {},
    private val composerHistoryController: ComposerHistoryController =
        ComposerHistoryController(cache, TransientComposerHistoryBrowseStore()),
    /** Reads happen off Main; tests inject the virtual scheduler. */
    var attachmentReadDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val draft = MutableStateFlow("")
    /** Locally acquired attachment drafts, occurrence-scoped and memory-only. */
    private val attachments = MutableStateFlow<List<ComposerAttachmentDraft>>(emptyList())
    private val activeSessionId = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val selectedProjectId = MutableStateFlow<String?>(null)
    private val projectLoadingId = MutableStateFlow<String?>(null)
    private val sidebarGrouping = MutableStateFlow(SidebarGrouping.Date)
    private val composer = MutableStateFlow(ComposerUiState())
    /** Engine-owned voice state; contains no media bytes. */
    private val voice = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    private val queueState = MutableStateFlow(ComposerQueueState())
    private val parkedQueueIds = MutableStateFlow<Set<String>>(emptySet())
    private val queueEdit = MutableStateFlow<QueueEditSnapshot?>(null)
    private val queueEditText = MutableStateFlow("")
    /** Browser/undo buffers are deliberately local, so this just invalidates the projection. */
    private val historyRevision = MutableStateFlow(0L)
    private val queueScopeReady = MutableStateFlow(false)
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
    private var queueScopeSwitch: Job? = null
    /** Activity-provided opener for content grants; set by MainActivity. */
    var openAttachmentStream: ((String) -> java.io.InputStream?)? = null
    private var redirectInFlight = false
    /** One automatic drain may be in flight per durable session. */
    private val scheduledQueueDrains = mutableSetOf<String>()

    private val localComposerState = combine(
        combine(queueState, parkedQueueIds, queueEdit, queueEditText) { state, parked, edit, editText ->
            LocalQueueState(state, parked, edit, editText)
        },
        historyRevision,
        queueScopeReady,
        repository.pendingInputs,
        attachments,
    ) { queue, revision, scopeReady, pending, drafts ->
        LocalComposerState(queue, revision, scopeReady, pending, drafts)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        cache.state,
        query,
        draft,
        activeSessionId,
        combine(
            composer,
            voice,
            combine(
                combine(
                    repository.connectionState,
                    notice,
                    selectedProjectId,
                    projectLoadingId,
                    sidebarGrouping,
                ) { connection, message, projectId, loadingId, grouping ->
                    NavigationState(connection, message, projectId, loadingId, grouping)
                },
                localComposerState,
            ) { navigation, local -> navigation.copy(localComposer = local) },
        ) { composerState, voiceState, navigation ->
            Triple(composerState, voiceState, navigation)
        },
    ) { cacheState, queryText, draftText, activeId, composerBundle ->
        val navigation = composerBundle.third
        val voiceState = composerBundle.second
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
        val busyKind = when {
            active?.status == SessionStatus.NeedsInput -> ComposerBusyKind.NeedsInput
            active?.status in STREAMING_STATUSES -> ComposerBusyKind.Streaming
            active?.status == SessionStatus.Background -> ComposerBusyKind.Background
            else -> ComposerBusyKind.Idle
        }
        val queueEntries = displayedActiveId
            ?.takeIf { navigation.localComposer.scopeReady }
            ?.let(navigation.localComposer.queue.state::entriesFor)
            .orEmpty()
        val runtime = ComposerRuntimeUiState(
            activeDurableId = displayedActiveId,
            busyKind = busyKind,
            queueEntries = queueEntries,
            queueParked = displayedActiveId != null && displayedActiveId in navigation.localComposer.queue.parkedIds,
            queueEditingEntryId = navigation.localComposer.queue.edit?.takeIf {
                it.durableSessionId == displayedActiveId
            }?.entryId,
            queueEditingText = navigation.localComposer.queue.editText,
            canRedirect = navigation.connection.status == GatewayConnectionStatus.Connected &&
                busyKind == ComposerBusyKind.Streaming && draftText.isRedirectEligible(),
            canQueue = navigation.connection.status == GatewayConnectionStatus.Connected &&
                displayedActiveId != null && navigation.localComposer.scopeReady &&
                busyKind == ComposerBusyKind.Idle,
            historyBrowse = displayedActiveId?.let(composerHistoryController::browseState),
            undoRedo = displayedActiveId?.let(composerHistoryController::undoRedoState) ?: ComposerUndoRedoState(),
            pendingInput = navigation.localComposer.pendingInputs.entries
                .firstOrNull { it.value.durableSessionId == displayedActiveId }?.value,
            attachments = navigation.localComposer.attachments.filter { it.durableSessionId == displayedActiveId },
        )
        val backgroundPending = navigation.localComposer.pendingInputs.values
            .firstOrNull { it.durableSessionId != displayedActiveId }
            ?.let { pending ->
                val row = cacheState.sessions[pending.durableSessionId]
                BackgroundPendingInput(
                    durableSessionId = pending.durableSessionId,
                    sessionTitle = row?.title.orEmpty().ifBlank { "Another session" },
                    kind = pending.key.kind,
                )
            }
        ChatUiState(
            voice = voiceState,
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
            backgroundPendingInput = backgroundPending,
            connection = navigation.connection,
            notice = navigation.notice,
            composer = composerBundle.first.copy(runtime = runtime),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            composerQueueController.state.collect { queueState.value = it }
        }
        viewModelScope.launch {
            composerQueueController.parkedDurableIds.collect { parkedQueueIds.value = it }
        }
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
                    activeSessionId.value?.let(::drainQueueIfIdle)
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
                    if (previousStatuses[id] in PROMPT_BLOCKING_STATUSES && session.status == SessionStatus.Idle) {
                        // The exact settled durable session drains (or remains
                        // parked) before an off-screen unread marker replaces
                        // the visible idle status.
                        drainQueueIfIdle(id)
                        if (activeSessionId.value != id) {
                            cache.upsertSession(session.copy(status = SessionStatus.Unread))
                        }
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
        queueScopeReady.value = false
        queueEdit.value = null
        queueEditText.value = ""
        queueScopeSwitch?.cancel()
        queueScopeSwitch = viewModelScope.launch {
            // Park/edit/review state is intentionally local to the active
            // connection/profile. Clear it before the backing store switches
            // so equal durable IDs cannot inherit transient UI state.
            composerQueueController.resetTransientScopeState()
            switchComposerQueueScope(
                ComposerQueueScope.forConnectionProfile(scope.connectionIdentity, scope.profileIdentity),
            )
            if (composerScope == scope) {
                queueScopeReady.value = true
                activeSessionId.value?.let(::drainQueueIfIdle)
            }
        }
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
        val activeId = activeSessionId.value
        if (activeId != null) {
            composerHistoryController.recordOrdinaryEdit(activeId, draft.value, value)
            invalidateHistory()
        }
        setDraftWithoutHistory(value)
    }

    private fun setDraftWithoutHistory(value: String) {
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
        activeSessionId.value?.let(composerHistoryController::reset)
        id?.let(composerHistoryController::reset)
        invalidateHistory()
        activeSessionId.value = id
        invalidateComposerRuntimeState()
        invalidatePendingDraftWrite()
        draft.value = id?.let(draftSnapshot::get).orEmpty()
        notice.value = null
        id?.let(::markRead)
        id?.let(::drainQueueIfIdle)
        if (id == null) refreshComposer(null)
    }

    /** Adopt a compressed session tip without clearing a draft for the same logical session. */
    private suspend fun adoptCanonicalSession(requestedId: String, canonicalId: String) {
        createdProjectBySession.remove(requestedId)?.let { projectId ->
            createdProjectBySession[canonicalId] = projectId
        }
        if (canonicalId == requestedId) return
        composerHistoryController.rehome(requestedId, canonicalId)
        composerQueueController.migrate(requestedId, canonicalId)
        invalidateHistory()
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
        // Compression changes only the durable key. Attachment drafts are
        // occurrence-scoped, so rekey them instead of orphaning their bytes.
        attachments.value = attachments.value.map { draft ->
            if (draft.durableSessionId == requestedId) draft.copy(durableSessionId = canonicalId) else draft
        }
        // Compression changes only the durable key. Keep the accumulated
        // session.info authority attached to the same logical session so a
        // canonical-id event cannot collide with the old presence patch.
        liveComposerControls = liveComposerControls?.copy(durableId = canonicalId)
        activeSessionId.value = canonicalId
        draft.value = winner.orEmpty()
        markRead(canonicalId)
        refreshComposer(canonicalId)
        drainQueueIfIdle(canonicalId)
    }

    /** The explicit idle action; a busy action is resolved by [performComposerPrimaryAction]. */
    fun submit() {
        val sessionId = activeSessionId.value ?: return
        val prompt = draft.value.trim()
        val pending = attachments.value.filter { it.durableSessionId == sessionId }
        val ready = pending.filter { it.stage is AttachmentStage.Ready }
        // Desktop parity (isTargetSessionBusy): the selected session's own
        // authoritative status gates its send — other sessions' turns never do.
        val activeIsIdle = cache.session(sessionId)?.status == SessionStatus.Idle
        if ((prompt.isEmpty() && ready.isEmpty()) || !activeIsIdle ||
            repository.connectionState.value.status != GatewayConnectionStatus.Connected
        ) return
        if (pending.any { it.stage is AttachmentStage.Reading || it.stage is AttachmentStage.Staging }) {
            notice.value = "Still reading an attachment — try again in a moment."
            return
        }

        clearDraftAfterDelivery(sessionId)
        // Payload bytes are claimed for the wire but chips stay visible until
        // the Gateway answers, so a failed or timed-out stage keeps its
        // retryable draft instead of evaporating.
        data class Claimed(val draft: ComposerAttachmentDraft, val outgoing: OutgoingAttachment)
        val outgoing = ready.mapNotNull { draft ->
            val payload = attachmentPayloads[draft.occurrenceId] ?: return@mapNotNull null
            val mime = attachmentMimes[draft.occurrenceId]
            val encoded = when (draft.kind) {
                AttachmentKind.Image -> AttachmentEncoding.base64(payload)
                AttachmentKind.File -> AttachmentEncoding.dataUrl(mime, payload)
            }
            Claimed(
                draft,
                when (draft.kind) {
                    AttachmentKind.Image -> OutgoingAttachment.Image(draft.displayName, encoded)
                    AttachmentKind.File -> OutgoingAttachment.GenericFile(draft.displayName, encoded)
                },
            )
        }
        val submittedPrompt = prompt
        notice.value = null
        viewModelScope.launch {
            try {
                val result = repository.submit(
                    sessionId,
                    submittedPrompt,
                    queued = false,
                    attachments = outgoing.map { it.outgoing },
                )
                if (result == GatewaySubmitOutcome.Accepted) {
                    outgoing.forEach { claimed ->
                        attachmentPayloads.remove(claimed.draft.occurrenceId)?.fill(0)
                        attachmentMimes.remove(claimed.draft.occurrenceId)
                    }
                    attachments.value = attachments.value.filterNot { it.durableSessionId == sessionId }
                } else {
                    markAttachmentsUnsent(sessionId)
                }
                when (result) {
                    GatewaySubmitOutcome.Accepted -> {
                        composerHistoryController.reset(sessionId)
                        invalidateHistory()
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
                markAttachmentsUnsent(sessionId)
                throw cancelled
            } catch (failure: Throwable) {
                markAttachmentsUnsent(sessionId)
                // Stage refusals arrive as GatewayRpcException whose message is
                // already sanitized for people; anything else stays generic.
                val safe = (failure as? GatewayRpcException)?.message
                    ?.takeIf(String::isNotBlank)
                notice.value = safe ?: "The message was not sent. Reconnect to the Gateway and try again."
                if (draftSnapshot[sessionId].isNullOrBlank()) {
                    rememberDraft(sessionId, submittedPrompt)
                    if (activeSessionId.value == sessionId && draft.value.isEmpty()) draft.value = submittedPrompt
                    viewModelScope.launch { persistDraft(sessionId, submittedPrompt) }
                }
            }
        }
    }

    /** A failed or timed-out submit returns its drafts to Ready for retry. */
    private fun markAttachmentsUnsent(sessionId: String) {
        attachments.value = attachments.value.map { draft ->
            if (draft.durableSessionId != sessionId) return@map draft
            when (draft.stage) {
                // Restore the true size from the retained payload: future
                // reservations read it, and a zeroed count would let the
                // aggregate bound be exceeded on the retry path.
                is AttachmentStage.Staging, is AttachmentStage.Staged -> {
                    val bytes = attachmentPayloads[draft.occurrenceId]?.size ?: 0
                    draft.copy(stage = AttachmentStage.Ready(bytes))
                }
                else -> draft
            }
        }
    }

    /**
     * Payload bytes for in-flight drafts, keyed by occurrence. Memory-only and
     * wiped on send, refusal, or session switch; never persisted anywhere.
     */
    private val attachmentPayloads = mutableMapOf<String, ByteArray>()
    private val attachmentMimes = mutableMapOf<String, String?>()

    /** Read one locally granted source into an in-memory draft. */
    fun addAttachmentFromGrant(uriString: String, displayName: String, claimedMime: String?) {
        val sessionId = activeSessionId.value ?: return
        if (attachments.value.count { it.durableSessionId == sessionId } >= AttachmentPolicy.MAX_ATTACHMENTS_PER_MESSAGE) {
            notice.value = "That is more attachments than one message can carry."
            return
        }
        // Reserve the per-item cap for every in-flight read so N simultaneous
        // picks cannot slip past the aggregate bound before their payloads land.
        val reservedBytes = attachments.value
            .filter { it.durableSessionId == sessionId }
            .sumOf { draft ->
                val stage = draft.stage
                when (stage) {
                    is AttachmentStage.Ready -> stage.byteCount.toLong()
                    is AttachmentStage.Reading, is AttachmentStage.Staging ->
                        AttachmentPolicy.MAX_BYTES_PER_ATTACHMENT.toLong()
                    else -> 0L
                }
            }
        val occurrenceId = "attach-${java.util.UUID.randomUUID()}"
        attachments.value = attachments.value + ComposerAttachmentDraft(
            occurrenceId = occurrenceId,
            durableSessionId = sessionId,
            displayName = AttachmentPolicy.sanitizeDisplayName(displayName),
            kind = AttachmentKind.File,
            stage = AttachmentStage.Reading,
        )
        viewModelScope.launch(attachmentReadDispatcher) {
            val result = AttachmentReader.read(
                openStream = openAttachmentStream?.let { opener -> { opener(uriString) } },
                rawDisplayName = displayName,
                claimedMime = claimedMime,
            )
            when (result) {
                is AttachmentReadResult.Read -> {
                    // The optimistic reservation bounds concurrent picks; the
                    // exact check keeps a single honest pick from refusing.
                    if (reservedBytes + result.bytes.size > AttachmentPolicy.MAX_TOTAL_BYTES) {
                        notice.value = "Attachments for one message can total at most 16 MB."
                        updateAttachment(occurrenceId) {
                            it.copy(stage = AttachmentStage.Refused(
                                "Adding this file would pass 16 MB. Remove one first.",
                            ))
                        }
                    } else {
                        attachmentPayloads[occurrenceId] = result.bytes
                        attachmentMimes[occurrenceId] = result.claimedMime
                        updateAttachment(occurrenceId) {
                            it.copy(kind = result.kind, stage = AttachmentStage.Ready(result.bytes.size))
                        }
                    }
                }
                is AttachmentReadResult.Refused -> {
                    updateAttachment(occurrenceId) {
                        it.copy(stage = AttachmentStage.Refused(result.safeMessage))
                    }
                }
            }
        }
    }

    fun removeAttachment(occurrenceId: String) {
        attachmentPayloads.remove(occurrenceId)?.fill(0)
        attachmentMimes.remove(occurrenceId)
        attachments.value = attachments.value.filterNot { it.occurrenceId == occurrenceId }
    }

    private fun updateAttachment(occurrenceId: String, transform: (ComposerAttachmentDraft) -> ComposerAttachmentDraft) {
        attachments.value = attachments.value.map { if (it.occurrenceId == occurrenceId) transform(it) else it }
    }
    /**
     * Dictation toggle. Capture is engine-owned and fenced to the active
     * durable session; the transcript inserts into the draft only when the
     * same session is still on screen, and it never auto-submits.
     */
    /** Activity sets this to run its permission gate before capture starts. */
    var onToggleDictationRequested: (() -> Unit)? = null

    /** Permission-denied projection; safe copy only, no raw system detail. */
    fun reportDictationPermissionDenied() {
        voice.value = com.hermesagent.mobile.data.voice.VoiceUiState.Error(
            VoiceUiState.VoiceErrorKind.PermissionDenied,
            "Allow microphone access to dictate. Tap the mic to try again.",
        )
    }

    /** UI entry point: the activity's permission gate runs before capture. */
    fun requestToggleDictation() {
        onToggleDictationRequested?.invoke() ?: toggleDictation()
    }

    fun toggleDictation() {
        val sessionId = activeSessionId.value ?: return
        val current = voice.value
        when (current) {
            is VoiceUiState.DictationRecording -> {
                voice.value = VoiceUiState.DictationTranscribing
                dictationStop?.invoke()
            }
            is VoiceUiState.DictationTranscribing -> Unit
            else -> {
                voice.value = VoiceUiState.DictationRecording(elapsedMillis = 0L, level = 0f)
                dictationStop = onDictationCapture?.invoke(sessionId) { result ->
                    viewModelScope.launch {
                        voice.value = VoiceUiState.Idle
                        if (result is TranscriptionResult.Transcript && activeSessionId.value == sessionId) {
                            insertTextAtCursor(result.text)
                        } else if (result is TranscriptionResult.Silence && activeSessionId.value == sessionId) {
                            notice.value = "No speech detected. Try again."
                        }
                    }
                }
            }
        }
    }

    /**
     * Starts or ends a voice conversation for the active session. The engine
     * hook (injected by MainActivity) owns capture and playback; state
     * transitions project through [voice]. No-op while disconnected.
     */
    fun toggleVoiceConversation() {
        val sessionId = activeSessionId.value ?: return
        val current = voice.value
        if (current is VoiceUiState.Conversation && current.phase != VoiceUiState.ConversationPhase.Ended) {
            voiceConversationEnd?.invoke()
            voice.value = VoiceUiState.Conversation(VoiceUiState.ConversationPhase.Ended, muted = false)
            return
        }
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
            notice.value = "Connect to a Gateway before starting a voice conversation."
            return
        }
        voice.value = VoiceUiState.Conversation(VoiceUiState.ConversationPhase.Listening, muted = false)
        voiceConversationStart?.invoke(sessionId)
    }

    fun toggleVoiceMute() {
        val current = voice.value
        if (current is VoiceUiState.Conversation) {
            voice.value = current.copy(muted = !current.muted)
            voiceConversationMute?.invoke(!current.muted)
        }
    }

    /** Engine hooks: start/end a conversation session and mute its mic. */
    var voiceConversationStart: ((durableSessionId: String) -> Unit)? = null
    var voiceConversationEnd: (() -> Unit)? = null
    var voiceConversationMute: ((muted: Boolean) -> Unit)? = null

    /** Engine-provided capture stop for the live dictation; null when idle. */
    var dictationStop: (() -> Unit)? = null

    /** Activity/engine hook that starts bounded capture for one session. */
    var onDictationCapture: ((durableSessionId: String, onDone: (TranscriptionResult) -> Unit) -> (() -> Unit)?)? = null

    private fun insertTextAtCursor(value: String) {
        val existing = draft.value
        val updated = if (existing.isBlank()) value else "$existing $value"
        setDraft(updated)
    }

    /** Dispatches the reducer's primary action without ever substituting steer for redirect. */
    fun performComposerPrimaryAction() {
        val state = uiState.value
        val action = composerActionState(
            connected = state.connection.status == GatewayConnectionStatus.Connected,
            busyKind = state.composer.runtime.busyKind,
            hasText = state.draft.isNotBlank(),
            redirectEligible = state.composer.runtime.canRedirect,
            queueCount = state.composer.runtime.queueEntries.size,
        ).primary
        when (action) {
            ComposerPrimaryAction.Send -> submit()
            ComposerPrimaryAction.Redirect -> redirectDraftFromUi()
            ComposerPrimaryAction.Stop -> stop()
            ComposerPrimaryAction.SendNext -> state.composer.runtime.queueEntries.firstOrNull()?.id?.let(::sendNext)
            ComposerPrimaryAction.Queue -> queueDraft()
            ComposerPrimaryAction.None -> Unit
        }
    }

    fun queueDraft() {
        val sessionId = activeSessionId.value ?: return
        val prompt = draft.value.trim()
        if (prompt.isEmpty() || !queueScopeReady.value) return
        viewModelScope.launch {
            when (composerQueueController.enqueue(sessionId, prompt)) {
                ComposerQueueMutation.Applied -> {
                    clearDraftAfterDelivery(sessionId)
                    composerHistoryController.reset(sessionId)
                    invalidateHistory()
                    drainQueueIfIdle(sessionId)
                }
                ComposerQueueMutation.CapacityReached -> notice.value = "The queue is full. Send, edit, or remove a queued message."
                ComposerQueueMutation.StorageUnavailable -> notice.value = "This message could not be queued. Keep it in the editor and try again."
                else -> notice.value = "This message could not be queued. Keep it in the editor and try again."
            }
        }
    }

    fun redirectDraftFromUi() {
        val sessionId = activeSessionId.value ?: return
        val prompt = draft.value.trim()
        if (prompt.isEmpty() || redirectInFlight) return
        redirectInFlight = true
        viewModelScope.launch {
            try {
                when (repository.redirect(sessionId, prompt)) {
                    GatewayRedirectOutcome.Redirected,
                    GatewayRedirectOutcome.QueuedByGateway,
                    -> {
                        clearDraftAfterDelivery(sessionId)
                        composerHistoryController.reset(sessionId)
                        invalidateHistory()
                    }
                    GatewayRedirectOutcome.Ambiguous -> queueRedirectFallback(sessionId, prompt, ambiguous = true)
                    GatewayRedirectOutcome.Rejected,
                    GatewayRedirectOutcome.Unsupported,
                    GatewayRedirectOutcome.Failed,
                    -> queueRedirectFallback(sessionId, prompt, ambiguous = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                queueRedirectFallback(sessionId, prompt, ambiguous = false)
            } finally {
                redirectInFlight = false
            }
        }
    }

    /** A redirect can fail safely, but it must leave one local durable copy—not retry a different RPC. */
    private suspend fun queueRedirectFallback(sessionId: String, prompt: String, ambiguous: Boolean) {
        when (
            composerQueueController.enqueue(
                sessionId,
                prompt,
                delivery = if (ambiguous) QueuedPromptDelivery.Ambiguous else QueuedPromptDelivery.Ready,
            )
        ) {
            ComposerQueueMutation.Applied -> {
                clearDraftAfterDelivery(sessionId)
                composerHistoryController.reset(sessionId)
                invalidateHistory()
                notice.value = if (ambiguous) {
                    "This correction may have reached Hermes. Review the queued copy before sending it."
                } else {
                    "Hermes did not accept that correction, so it was added to this session's queue."
                }
            }
            else -> notice.value = "Hermes did not accept that correction. It remains in the editor."
        }
    }

    fun stop() {
        val sessionId = activeSessionId.value ?: return
        // Authoritative cache truth, not the possibly stale projected kind:
        // an explicit Stop must never cancel a required-input turn.
        if (cache.session(sessionId)?.status == SessionStatus.NeedsInput) {
            notice.value = "Hermes needs a response. Answer the request above."
            return
        }
        viewModelScope.launch {
            composerQueueController.park(sessionId)
            try {
                when (repository.requestInterrupt(sessionId)) {
                    com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome.Interrupted -> Unit
                    com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome.NeedsInput ->
                        notice.value = "Hermes needs a response. Your queue remains parked."
                    else -> notice.value = "Hermes could not be stopped. Check the Gateway connection."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                notice.value = "Hermes could not be stopped. Check the Gateway connection."
            }
        }
    }

    fun resumeQueue() {
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            composerQueueController.resume(sessionId)
            drainQueueIfIdle(sessionId)
        }
    }

    fun sendNext(entryId: String) {
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            val state = uiState.value
            when (state.composer.runtime.busyKind) {
                ComposerBusyKind.NeedsInput -> {
                    notice.value = "Hermes needs a response before queued messages can continue."
                }
                else -> {
                    composerQueueController.resume(sessionId)
                    sendNextAfterResume(sessionId, entryId, state.composer.runtime.busyKind)
                }
            }
        }
    }

    private suspend fun sendNextAfterResume(
        sessionId: String,
        entryId: String,
        busyKind: ComposerBusyKind,
    ) {
        when (busyKind) {
            ComposerBusyKind.Streaming -> {
                    // A busy turn can be a stale post-reconnect row whose
                    // runtime is not yet known locally. Rehydrate before
                    // declaring the switch impossible: openSession resumes the
                    // live runtime and rebinds identity so the guarded
                    // interrupt can succeed against real Gateway truth.
                    val refreshed = runCatching { repository.openSession(sessionId) }
                        .onFailure { if (it is CancellationException) throw it }
                        .isSuccess
                    if (!refreshed) {
                        notice.value = "Hermes could not switch to that queued message. Try again."
                        return
                    }
                    if (isSessionIdle(sessionId)) {
                        when (composerQueueController.sendNextWhenIdle(sessionId, entryId, isIdle = true)) {
                            ComposerQueueDrainResult.Ambiguous,
                            ComposerQueueDrainResult.ReviewRequired,
                            -> notice.value = "Review that queued message before sending it again."
                            ComposerQueueDrainResult.StoreUnavailable -> notice.value = "The queue could not be updated. Try again."
                            else -> Unit
                        }
                        return
                    }
                    when (composerQueueController.moveToHead(sessionId, entryId)) {
                        ComposerQueueMutation.Applied -> when (repository.requestInterrupt(sessionId)) {
                            com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome.Interrupted -> Unit
                            com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome.NotActive -> {
                                // The runtime ended between rehydrate and the
                                // interrupt RPC; drain the head entry now.
                                when (composerQueueController.sendNextWhenIdle(sessionId, entryId, isSessionIdle(sessionId))) {
                                    ComposerQueueDrainResult.Ambiguous,
                                    ComposerQueueDrainResult.ReviewRequired,
                                    -> notice.value = "Review that queued message before sending it again."
                                    ComposerQueueDrainResult.StoreUnavailable -> notice.value = "The queue could not be updated. Try again."
                                    else -> Unit
                                }
                            }
                            else -> notice.value = "Hermes could not switch to that queued message. Try again."
                        }
                        else -> notice.value = "That queued message is no longer available."
                    }
                }
            ComposerBusyKind.Background ->
                notice.value = "Hermes is still working. This queued message will be ready when it is idle."
            else -> when (composerQueueController.sendNextWhenIdle(sessionId, entryId, isSessionIdle(sessionId))) {
                ComposerQueueDrainResult.Ambiguous,
                ComposerQueueDrainResult.ReviewRequired,
                -> notice.value = "Review that queued message before sending it again."
                ComposerQueueDrainResult.StoreUnavailable -> notice.value = "The queue could not be updated. Try again."
                else -> Unit
            }
        }
    }

    fun redirectQueuedEntry(entryId: String) {
        val sessionId = activeSessionId.value ?: return
        val entry = uiState.value.composer.runtime.queueEntries.firstOrNull { it.id == entryId } ?: return
        if (!uiState.value.composer.runtime.canRedirect || entry.delivery == QueuedPromptDelivery.Ambiguous) return
        viewModelScope.launch {
            try {
                when (repository.redirect(sessionId, entry.text)) {
                    GatewayRedirectOutcome.Redirected,
                    GatewayRedirectOutcome.QueuedByGateway,
                    -> composerQueueController.remove(sessionId, entryId)
                    GatewayRedirectOutcome.Ambiguous -> {
                        composerQueueController.markAmbiguous(sessionId, entryId)
                        notice.value = "This correction may have reached Hermes. Review it before sending again."
                    }
                    GatewayRedirectOutcome.Rejected,
                    GatewayRedirectOutcome.Unsupported,
                    GatewayRedirectOutcome.Failed,
                    -> notice.value = "Hermes did not accept that correction. It remains in this queue."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                notice.value = "Hermes did not accept that correction. It remains in this queue."
            }
        }
    }

    fun deleteQueuedEntry(entryId: String) {
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            composerQueueController.remove(sessionId, entryId)
            if (queueEdit.value?.entryId == entryId) finishQueueEdit(resetDraft = null)
        }
    }

    fun beginQueueEdit(entryId: String) {
        val sessionId = activeSessionId.value ?: return
        val text = uiState.value.composer.runtime.queueEntries.firstOrNull { it.id == entryId }?.text ?: return
        viewModelScope.launch {
            val snapshot = composerQueueController.beginEdit(sessionId, entryId, draft.value) ?: return@launch
            queueEdit.value = snapshot
            queueEditText.value = text
            composerHistoryController.reset(sessionId)
            invalidateHistory()
        }
    }

    fun setQueueEditText(text: String) {
        if (queueEdit.value != null) queueEditText.value = text
    }

    fun saveQueueEdit() {
        val snapshot = queueEdit.value ?: return
        val text = queueEditText.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            when (composerQueueController.saveEdit(snapshot, text)) {
                ComposerQueueMutation.Applied -> finishQueueEdit(resetDraft = null)
                else -> notice.value = "That queued message could not be saved. Try again."
            }
        }
    }

    fun cancelQueueEdit() {
        val snapshot = queueEdit.value ?: return
        viewModelScope.launch {
            finishQueueEdit(resetDraft = composerQueueController.cancelEdit(snapshot))
        }
    }

    fun markQueuedEntryReadyAfterReview(entryId: String) {
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            if (composerQueueController.markReadyAfterReview(sessionId, entryId) == ComposerQueueMutation.Applied) {
                notice.value = "That queued message is ready when you choose Send next."
            }
        }
    }

    /** Keyboard history only starts at an empty ordinary draft; queue edit owns its own field. */
    fun historyOlder(): Boolean {
        val sessionId = activeSessionId.value ?: return false
        if (queueEdit.value != null) return false
        return applyHistoryChange(composerHistoryController.browseOlder(sessionId, draft.value))
    }

    fun historyNewer(): Boolean {
        val sessionId = activeSessionId.value ?: return false
        if (queueEdit.value != null) return false
        return applyHistoryChange(composerHistoryController.browseNewer(sessionId))
    }

    fun undoDraft(): Boolean {
        val sessionId = activeSessionId.value ?: return false
        if (queueEdit.value != null) return false
        return applyHistoryChange(composerHistoryController.undo(sessionId, draft.value))
    }

    fun redoDraft(): Boolean {
        val sessionId = activeSessionId.value ?: return false
        if (queueEdit.value != null) return false
        return applyHistoryChange(composerHistoryController.redo(sessionId, draft.value))
    }

    /** One deliberate answer for a parked request; the repository owns fencing. */
    fun respondToPendingInput(action: com.hermesagent.mobile.data.gateway.PendingInputAction) {
        val key = composer.value.runtime.pendingInput?.key ?: return
        viewModelScope.launch {
            runCatching { repository.respondToPendingInput(key, action) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                }
        }
    }

    /**
     * System back / scrim dismissed a secure prompt without an explicit choice.
     * The dialog already sent the safe empty refusal; this only clears local UI.
     */
    fun dismissSecurePending() = Unit

    private fun applyHistoryChange(change: ComposerDraftChange): Boolean = when (change) {
        ComposerDraftChange.Unchanged -> false
        is ComposerDraftChange.Changed -> {
            setDraftWithoutHistory(change.draft)
            invalidateHistory()
            true
        }
    }

    private fun finishQueueEdit(resetDraft: String?) {
        val sessionId = queueEdit.value?.durableSessionId
        queueEdit.value = null
        queueEditText.value = ""
        sessionId?.let(composerHistoryController::reset)
        resetDraft?.let(::setDraftWithoutHistory)
        invalidateHistory()
    }

    private fun clearDraftAfterDelivery(sessionId: String) {
        if (activeSessionId.value != sessionId) return
        draft.value = ""
        invalidatePendingDraftWrite()
        rememberDraft(sessionId, "")
        viewModelScope.launch { persistDraft(sessionId, "") }
    }

    private fun drainQueueIfIdle(sessionId: String) {
        if (!queueScopeReady.value || !isSessionIdle(sessionId)) return
        if (!scheduledQueueDrains.add(sessionId)) return
        viewModelScope.launch {
            try {
                // Recheck inside the scheduled owner. A settle/reconnect pair
                // can both observe idle before either coroutine runs.
                if (!queueScopeReady.value || !isSessionIdle(sessionId)) return@launch
                when (composerQueueController.drainIfIdle(sessionId, isIdle = true)) {
                    ComposerQueueDrainResult.StoreUnavailable -> if (activeSessionId.value == sessionId) {
                        notice.value = "The queue could not be updated. Try again."
                    }
                    else -> Unit
                }
            } finally {
                scheduledQueueDrains.remove(sessionId)
            }
        }
    }

    private fun isSessionIdle(sessionId: String): Boolean = cache.session(sessionId)?.status == SessionStatus.Idle

    private fun invalidateHistory() {
        historyRevision.value += 1
    }

    /** Optional status capabilities remain explicitly unavailable on older Gateways. */
    fun composerStatusOpened() {
        val sessionId = activeSessionId.value ?: return
        refreshProcesses(sessionId, showFailure = false)
        viewModelScope.launch {
            when (repository.goalStatus(sessionId)) {
                GatewayGoalStatusOutcome.Failed -> if (activeSessionId.value == sessionId) {
                    notice.value = "Goal status could not be refreshed. Try again."
                }
                else -> Unit
            }
        }
    }

    fun refreshProcesses() {
        activeSessionId.value?.let { refreshProcesses(it, showFailure = true) }
    }

    private fun refreshProcesses(sessionId: String, showFailure: Boolean) {
        viewModelScope.launch {
            when (repository.listProcesses(sessionId)) {
                GatewayProcessListOutcome.Failed -> if (showFailure && activeSessionId.value == sessionId) {
                    notice.value = "Background work could not be refreshed. Try again."
                }
                else -> Unit
            }
        }
    }

    fun killProcess(processId: String) {
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            when (repository.killProcess(sessionId, processId)) {
                GatewayProcessKillOutcome.Killed -> refreshProcesses(sessionId, showFailure = false)
                GatewayProcessKillOutcome.Rejected,
                GatewayProcessKillOutcome.Failed,
                GatewayProcessKillOutcome.Ambiguous,
                -> if (activeSessionId.value == sessionId) {
                    notice.value = "Background work could not be stopped. Try again."
                }
                GatewayProcessKillOutcome.Unsupported -> if (activeSessionId.value == sessionId) {
                    notice.value = "This Gateway cannot stop background work."
                }
            }
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
        // Attachment bytes are memory-only by contract: nothing survives the
        // ViewModel, and process recreation shows "unavailable" rather than a
        // stale grant.
        for (payload in attachmentPayloads.values) java.util.Arrays.fill(payload, 0)
        attachmentPayloads.clear()
        attachmentMimes.clear()
        attachments.value = emptyList()
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
        val localComposer: LocalComposerState = LocalComposerState(),
    )

    private data class LocalQueueState(
        val state: ComposerQueueState = ComposerQueueState(),
        val parkedIds: Set<String> = emptySet(),
        val edit: QueueEditSnapshot? = null,
        val editText: String = "",
    )

    private data class LocalComposerState(
        val queue: LocalQueueState = LocalQueueState(),
        @Suppress("unused") val historyRevision: Long = 0L,
        val scopeReady: Boolean = false,
        val pendingInputs: Map<PendingInputKey, PendingInputRequest> = emptyMap(),
        /** In-memory attachment drafts; UI projects them per active session. */
        val attachments: List<ComposerAttachmentDraft> = emptyList(),
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
            composerQueueController: ComposerQueueController = transientQueueController(),
            switchComposerQueueScope: suspend (ComposerQueueScope) -> Unit = {},
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: androidx.lifecycle.viewmodel.CreationExtras,
                ): T =
                    ChatViewModel(
                        cache = cache,
                        repository = repository,
                        sidebarViewStore = sidebarViewStore,
                        composerControlsStore = composerControlsStore,
                        draftStore = draftStore,
                        applicationDraftScope = draftScope,
                        composerQueueController = composerQueueController,
                        switchComposerQueueScope = switchComposerQueueScope,
                        composerHistoryController = ComposerHistoryController(
                            cache,
                            SavedStateComposerHistoryBrowseStore(extras.createSavedStateHandle()),
                        ),
                    ) as T
            }
    }
}

private fun transientQueueController(): ComposerQueueController = ComposerQueueController(
    store = TransientComposerQueueStore(),
    submitter = object : ComposerQueueSubmitter {
        override suspend fun submitQueued(durableSessionId: String, text: String): QueueSubmissionOutcome =
            QueueSubmissionOutcome.Rejected
    },
)

/** Slash commands follow their own capability path; only ordinary text can redirect a live turn. */
private fun String.isRedirectEligible(): Boolean = trim().isNotEmpty() && !trimStart().startsWith('/')
