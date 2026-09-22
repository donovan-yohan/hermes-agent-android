package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.voice.ReplySpeaker
import com.hermesagent.mobile.data.voice.TranscriptionResult
import com.hermesagent.mobile.data.voice.VoiceSessionKey
import com.hermesagent.mobile.data.voice.VoiceTransportException
import com.hermesagent.mobile.data.voice.VoiceUiState
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
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.setProviderVisibility
import com.hermesagent.mobile.data.composer.toggleModelVisibility
import com.hermesagent.mobile.data.composer.NewSessionComposerOverrides
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.composer.SessionComposerControls
import com.hermesagent.mobile.data.draft.SessionDraftStore
import com.hermesagent.mobile.data.draft.TransientSessionDraftStore
import com.hermesagent.mobile.data.attachments.AttachmentEncoding
import com.hermesagent.mobile.data.attachments.AttachmentKind
import com.hermesagent.mobile.data.attachments.AttachmentPickScope
import com.hermesagent.mobile.data.attachments.AttachmentPolicy
import com.hermesagent.mobile.data.attachments.AttachmentReader
import com.hermesagent.mobile.data.attachments.AttachmentReadResult
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.ComposerAttachmentDraft
import com.hermesagent.mobile.data.attachments.OutgoingAttachment
import com.hermesagent.mobile.data.attachments.RecentImage
import com.hermesagent.mobile.data.attachments.RecentImageAccess
import com.hermesagent.mobile.data.attachments.RecentImagesPolicy
import com.hermesagent.mobile.data.attachments.RecentImagesSource
import com.hermesagent.mobile.data.attachments.readRecentImages
import com.hermesagent.mobile.ui.chat.composer.RecentImagesUiState
import com.hermesagent.mobile.ui.common.AttachmentThumbnails
import com.hermesagent.mobile.data.gateway.safeGatewayStatusText
import com.hermesagent.mobile.data.gateway.APPROVAL_MODE_REJECTED
import com.hermesagent.mobile.data.gateway.isSessionNotOwned
import com.hermesagent.mobile.data.gateway.ARCHIVED_UNSUPPORTED
import com.hermesagent.mobile.data.gateway.ApprovalMode
import com.hermesagent.mobile.data.gateway.ApprovalModeOutcome
import com.hermesagent.mobile.data.gateway.ApprovalModeState
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayImageLoader
import com.hermesagent.mobile.data.gateway.SessionListPaging
import com.hermesagent.mobile.data.gateway.ProfileRouting
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayGoalStatusOutcome
import com.hermesagent.mobile.data.gateway.GatewayProcessKillOutcome
import com.hermesagent.mobile.data.gateway.GatewayProcessListOutcome
import com.hermesagent.mobile.data.gateway.BranchPlan
import com.hermesagent.mobile.data.gateway.deriveBranchCount
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome
import com.hermesagent.mobile.data.gateway.RegeneratePlan
import com.hermesagent.mobile.data.gateway.planRegenerate
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.gateway.GatewayRedirectOutcome
import com.hermesagent.mobile.data.session.ContextBreakdown
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ContextMeterState
import com.hermesagent.mobile.data.session.SessionUsage
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.data.prefs.ComposerControlsStore
import com.hermesagent.mobile.data.prefs.NewDraftComposerPreference
import com.hermesagent.mobile.data.prefs.ProfileScopeStore
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.prefs.TransientProfileScopeStore
import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.profiles.GatewayProfileConnectionState
import com.hermesagent.mobile.data.profiles.NoProfileRepository
import com.hermesagent.mobile.data.profiles.ProfileRepository
import com.hermesagent.mobile.data.profiles.ProfileRosterState
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.profiles.filterSessionsByProfileScope
import com.hermesagent.mobile.data.profiles.normalizeProfileKey
import com.hermesagent.mobile.data.profiles.sessionListProfiles
import com.hermesagent.mobile.ui.profiles.ProfilesUiState
import com.hermesagent.mobile.ui.sessions.ProfileRailState
import com.hermesagent.mobile.data.prefs.SidebarViewStore
import com.hermesagent.mobile.data.prefs.TransientSidebarViewStore
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionBucketLabel
import com.hermesagent.mobile.data.session.IcuSessionBucketLabel
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.buildSessionRows
import com.hermesagent.mobile.data.session.displayStatus
import com.hermesagent.mobile.data.session.matchesProjectQuery
import com.hermesagent.mobile.data.session.sortProjectsForOverview
import com.hermesagent.mobile.data.composer.maskComposerReferences
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val codingContext: CodingContext = CodingContext.Unavailable,
    val codingReview: CodingReviewUiState = CodingReviewUiState.Closed,
    /**
     * The person's saved `provider::model` shortlist, or null while they have
     * never customised it — in which case the curated default applies
     * (`apps/desktop/src/store/model-visibility.ts:87-89` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
     */
    val visibleModels: Set<String>? = null,
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
    /** Occurrence-keyed preview bitmaps for image drafts; UI-only, never persisted. */
    val attachmentThumbnails: Map<String, ImageBitmap> = emptyMap(),
    /** The add sheet's device-image rail, fenced to this composer scope. */
    val recentImages: RecentImagesUiState = RecentImagesUiState(),
) {
    /** An attachment whose bytes are in hand, so a message can leave with no text. */
    val hasReadyAttachment: Boolean get() = attachments.any { it.stage is AttachmentStage.Ready }
}

/** A pending action owned by a session other than the one on screen. */
data class BackgroundPendingInput(
    val durableSessionId: String,
    val sessionTitle: String,
    val kind: PendingInputKind,
)

/**
 * How the project catalog relates to the profile scope on screen.
 *
 * Project RPCs are profile-scoped (`tui_gateway/methods_config.py:85-113` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`), so a named sidebar scope can
 * show that profile's own backend catalog.
 */
enum class ProjectProfileScope {
    /** The sidebar is in the profile the catalog came from. Nothing to say. */
    Own,

    /** Every profile is in view, but only one profile's projects exist. */
    Unified,

    ;

    /** Whether the catalog may be browsed at all in this scope. */
    val showsCatalog: Boolean get() = true
}

/**
 * Which [ProjectProfileScope] a sidebar profile scope puts the catalog in.
 *
 * One function rather than two readings: the rail's rendering and the search
 * key both have to agree on whether a scope draws projects or sessions, and a
 * second copy of the mapping is how they stop agreeing.
 */
fun projectProfileScopeOf(scope: ProfileScope): ProjectProfileScope = when {
    scope.isAll -> ProjectProfileScope.Unified
    else -> ProjectProfileScope.Own
}

/**
 * What the `archived=only` pool has said, for the endpoint and profile scope
 * the sidebar is standing in.
 *
 * Desktop keeps `$archivedSessionsLoading` beside `$archivedSessions`
 * (`apps/desktop/src/store/sidebar-archive.ts:12,19,28` @ `72a3277cd7`) but only
 * to refuse a second concurrent fetch; nothing renders it, and its `catch`
 * publishes an empty set (`:25-27`). Here the marker is rendered, because
 * `Nothing archived` is a statement about the *account* — and a read in
 * flight, a read that failed, and a Gateway that cannot be asked at all are
 * none of them that.
 */
enum class ArchivedPoolState {
    /** Nothing has been asked yet on this endpoint, in this scope. */
    Idle,

    /** Asked; the Gateway has not answered. */
    Loading,

    /** Answered. An empty pool now really does mean nothing is archived. */
    Loaded,

    /** The read failed. What is on screen is not "nothing archived". */
    Failed,

    /**
     * This Gateway serves only the `session.list` RPC, which has no archived
     * filter — so the question cannot be asked here at all.
     */
    Unsupported,

    ;

    /** Whether a re-read would tell us anything we do not already know. */
    val needsRead: Boolean get() = this == Idle || this == Failed
}

sealed interface ReadAloudUiState {
    data object Idle : ReadAloudUiState
    data class Preparing(val entryId: String) : ReadAloudUiState
    data class Speaking(val entryId: String) : ReadAloudUiState
}

enum class ReadAloudControl { Idle, Preparing, Speaking, Blocked }

/**
 * The one thing that gets a notice's state unstuck, when there is one.
 *
 * An enum rather than a lambda because the state a screen reads is compared
 * for equality and re-emitted on every combine; and because the ViewModel
 * knows *which* escape a refusal has, while the screen knows what it is called
 * and where it goes — the same split [com.hermesagent.mobile.ui.ChatActions]
 * already makes.
 */
enum class ChatNoticeAction {
    /**
     * A live-owner refusal. Another surface holds this session's lease
     * (JSON-RPC 4090, `error.data.reason = SESSION_NOT_OWNED`), so sending
     * again fails identically for as long as it does; the way out is a fresh
     * session on this surface. Desktop reaches the same conclusion from the
     * same reason code and offers the same escape
     * (`apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:548-551,559-563`
     * @ `564aef2946c436500a5e80ee117b66b789b3f99a`).
     */
    StartNewSession,
}

/**
 * A one-line status this surface is reporting, and — where the state that
 * produced it has a way out — the escape that ends it.
 *
 * A type rather than a `String?` because the alternative is the UI deciding
 * "is this the refusal one?" by comparing user-visible prose, which is exactly
 * the sniffer upstream deleted when it keyed the same escape off the reason
 * code instead (`c80003ff57`; the app's own
 * `com.hermesagent.mobile.data.gateway.isSessionNotOwned` and its test hold
 * that line on the classification side). The action travels with the sentence
 * because the two are one fact: a notice that is replaced or cleared takes its
 * escape with it, and no code path can leave one behind.
 */
data class ChatNotice(
    val text: String,
    val action: ChatNoticeAction? = null,
)

/**
 * What the open of a durable session is doing, as a fact the chat pane can act
 * on instead of a sentence it has to recognize.
 *
 * The chat pane used to identify a failed open by comparing the notice text
 * against `SESSION_OPEN_FAILED_COPY` — the only signal the state carried. That
 * coupling is what this type deletes: the failure now says *which* session
 * could not be opened and carries a safe internal cause, so the pane renders a
 * retry from the typed field and never parses prose.
 *
 * [Failed.sessionId] is the id the caller asked for, not the canonical one: a
 * failure can happen before compression moved the key, and re-selecting the id
 * the reader was navigating to is the path that re-runs the open without
 * rehoming.
 */
sealed interface SessionOpenState {
    /** Nothing is being opened, or the open finished without an outcome. */
    data object Idle : SessionOpenState

    /** The named session's open is on the wire. */
    data class Opening(val sessionId: String) : SessionOpenState

    /**
     * The open of [sessionId] failed. [detail] is a bounded, redacted summary of
     * the internal cause — safe to show and safe to attach to a report, and
     * deliberately not a transcript.
     */
    data class Failed(
        val sessionId: String,
        val message: String,
        val detail: String? = null,
    ) : SessionOpenState
}

data class ChatUiState(
    val diagnostics: SendDiagnosticsState? = null,
    val gatewayLogs: GatewayLogsState? = null,
    val diagnosticsEndpointGeneration: Long = 0L,
    val voice: VoiceUiState = VoiceUiState.Idle,
    val readAloud: ReadAloudUiState = ReadAloudUiState.Idle,
    val sessionRows: List<SessionListRow> = emptyList(),
    /**
     * The one clock read this state's session list was built against.
     *
     * It ages every row's relative metadata *and* chooses the rows' date
     * buckets, and it has to be the same number for both: two reads could
     * straddle midnight, grouping a row under `Today` while it shows an age of
     * `1d`. Carried on the state rather than re-read in the list so a
     * screenshot, a journey and production all age the same rows the same way.
     */
    val nowMillis: Long = System.currentTimeMillis(),
    val projects: List<ProjectSummary> = emptyList(),
    val projectsAvailable: Boolean? = null,
    val sidebarGrouping: SidebarGrouping = SidebarGrouping.Date,
    /** Desktop's `Archived` filter: the list is a view of the archived set instead. */
    val archivedVisible: Boolean = false,
    /** What the archived pool has said; the empty state waits on it. */
    val archivedPool: ArchivedPoolState = ArchivedPoolState.Idle,
    /** Loaded, non-archived rows that are still unread, by either source. */
    val unreadCount: Int = 0,
    /**
     * A live-pool page is on the wire and this scope has no rows yet — Desktop's
     * `showSessionSkeletons` (`apps/desktop/src/app/chat/sidebar/index.tsx:1423`
     * @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
     *
     * Keyed off the UNFILTERED scoped set, exactly as Desktop's is and for the
     * same reason it records: keyed off the filtered one, a filter that matches
     * nothing would show skeletons on every background refresh instead of the
     * state that says the filter is why the list is bare.
     *
     * It exists so an empty list during the first fetch, and after every
     * reconnect, is not mistaken for an empty account.
     */
    val sessionsLoading: Boolean = false,
    /** The foot rail: this Gateway's profiles and the scope the sidebar is in. */
    val profileRail: ProfileRailState = ProfileRailState(),
    /** How the project catalog relates to the profile scope the sidebar is in. */
    val projectScope: ProjectProfileScope = ProjectProfileScope.Own,
    /** The read-only roster behind "Manage profiles…". */
    val profiles: ProfilesUiState = ProfilesUiState(),
    val selectedProject: ProjectSummary? = null,
    val projectLoading: Boolean = false,
    val activeSession: SessionSummary? = null,
    /**
     * The session the composer is homed on, whether or not its row has reached
     * the cache yet.
     *
     * Not the same question as [activeSession] being non-null: `session.create`
     * homes the composer on an id before `session.info` publishes the row, so
     * for a frame or two there is an active session with no summary. Anything
     * that must distinguish "no session at all" from "a session whose row has
     * not landed" — the intro splash does — has to read this, not that.
     */
    val activeSessionId: String? = null,
    /**
     * The project the homed session belongs to, when the catalog has been
     * hydrated far enough to say.
     *
     * Not [selectedProject], which is the sidebar's own browsing state: a
     * session opened from search or from a rehome belongs to whatever project
     * claims it, and the sidebar may be looking at a different one or at none.
     * Null whenever the membership is simply not known — the intro splash, its
     * only reader, then says nothing rather than guessing.
     */
    val activeSessionProject: ProjectSummary? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val query: String = "",
    val draft: String = "",
    val isStreaming: Boolean = false,
    val runningCount: Int = 0,
    /** A required action parked in a non-visible session. */
    val backgroundPendingInput: BackgroundPendingInput? = null,
    val connection: GatewayConnectionState = GatewayConnectionState(),
    val notice: ChatNotice? = null,
    /**
     * Whether the active session is still opening, and if it failed. The chat
     * pane renders its own failure surface from this — never from [notice]'s
     * prose. See [SessionOpenState].
     */
    val sessionOpen: SessionOpenState = SessionOpenState.Idle,
    val composer: ComposerUiState = ComposerUiState(),
    /** Connection-owned attached-image loader; null while disconnected. */
    val imageLoader: GatewayImageLoader? = null,
    val contextMeter: ContextMeterState? = null,
    /**
     * Whether this session's transcript has older rows the Gateway has not been
     * asked for — the one thing `Show earlier messages` renders on. There is no
     * in-flight or exhausted variant: Desktop's control carries neither, and an
     * exhausted session simply stops offering it
     * (`apps/desktop/src/components/assistant-ui/thread/list.tsx:834` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
     */
    val canShowEarlierMessages: Boolean = false,
    /**
     * How often this host asks before it acts, or null while that is not known
     * or the Gateway is not connected — Desktop hides its own statusbar item on
     * exactly the second condition (`use-statusbar-items.tsx:610-613` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
     */
    val approvalMode: ApprovalMode? = null,
    /**
     * True while the foreground session is a canonical Bot Chat this screen
     * opened and still owns on this endpoint.
     *
     * It hides the session-menu controls, which Phase B leaves unwired inside a
     * Bot Chat; the composer itself is open (sending is the one mutation the
     * chat accepts here).
     */
    val botChat: Boolean = false,
) {
    val canCreateSession: Boolean
        get() = connection.status == GatewayConnectionStatus.Connected
    val canSend: Boolean
        get() = canCreateSession &&
            activeSession?.status == SessionStatus.Idle &&
            (draft.isNotBlank() || composer.runtime.hasReadyAttachment)
    val transcriptIsEmpty: Boolean get() = transcript.isEmpty()
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
    private val codingContextProvider: CodingContextProvider = CodingContextProvider.Unavailable,
    /** Saved profile scope. Declared late so existing positional callers keep working. */
    private val profileScopeStore: ProfileScopeStore = TransientProfileScopeStore(),
    private val profileRepository: ProfileRepository = NoProfileRepository,
    private val replySpeaker: ReplySpeaker? = null,
    private val connectionGeneration: () -> Long = { 0L },
    /** Reads happen off Main; tests inject the virtual scheduler. */
    var attachmentReadDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gatewayHttp: () -> com.hermesagent.mobile.data.gateway.GatewayHttp? = { null },

    /**
     * How a month divider is worded. Null means the shipped ICU formatter,
     * which is resolved on first use — see [productionBucketLabel]. A plain JVM
     * suite has no ICU at all, so it injects its own and this state machine
     * stays drivable there; `IcuSessionBucketLabelTest` pins the real one under
     * Robolectric.
     */
    private val bucketLabel: SessionBucketLabel? = null,
) : ViewModel() {
    private val query = MutableStateFlow("")
    /** UI routing state, never backend/session-cache authority. */
    private var botChatSessionId: String? = null
    /** Endpoint generations that minted the transient Bot Chat capability. */
    private var botChatEndpoint: BotChatEndpoint? = null

    /**
     * The shipped divider wording, resolved once and only when a list is
     * actually built. It is ICU, which a plain JVM unit-test classpath does not
     * carry: building it at construction made *every* plain JVM test that
     * collects this state die on `ULocale.forLocale`, for a question most of
     * them never asked. Building it here keeps it lazy per ViewModel rather than
     * per emission, and only a month divider ever asks for it.
     */
    private val productionBucketLabel: SessionBucketLabel by lazy { IcuSessionBucketLabel() }

    private data class BotChatEndpoint(
        val cacheGeneration: Long,
        val connectionGeneration: Long,
    )

    /**
     * Whether the debounced backend search is still in flight. It is UI state,
     * never cache truth, and it only ever chooses between two empty states —
     * see [buildSessionRows].
     */
    private val searchPendingState = MutableStateFlow(false)

    /**
     * What the Gateway's own index last answered for the live query, or null
     * when it was never asked, could not be asked, or refused. Null and empty
     * are different facts: only the second one means "nothing matched".
     *
     * Search stubs never reach [SessionCache]. A stub carries an id, a lineage
     * root and a snippet and nothing else the row contract wants
     * (`apps/desktop/src/app/chat/sidebar/index.tsx:272-293` @ `72a3277cd7`), so
     * filing one under backend authority would make an invented row
     * indistinguishable from a listed one — and it would outlive the query.
     */
    private val searchResults = MutableStateFlow<List<SessionSummary>?>(null)

    private val draft = MutableStateFlow("")
    /** Locally acquired attachment drafts, occurrence-scoped and memory-only. */
    private val attachments = MutableStateFlow<List<ComposerAttachmentDraft>>(emptyList())
    /** Occurrence-keyed preview bitmaps for image drafts; UI-only, wiped with drafts. */
    private val attachmentThumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    /**
     * Device images the add sheet may offer, published with the composer scope
     * that owns them. Ownership travels inside the value rather than in a
     * sibling field, so a stale `combine` delivery cannot borrow a newer
     * scope's session.
     */
    private val recentImages = MutableStateFlow(ScopedRecentImages())
    private var recentImagesLoad: Job? = null
    /** Whether the add sheet is on screen, which is the only reason to read the library. */
    private var recentImagesOpen = false
    /** Occurrence id to device image id, so removing a chip clears its rail mark. */
    private val recentImageAdds = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Activity-owned reader; it never persists a media grant. */
    var recentImagesSource: RecentImagesSource? = null
    private val activeSessionId = MutableStateFlow<String?>(null)
    private val activeContextBreakdown = MutableStateFlow<ContextBreakdown?>(null)
    /** The saved model shortlist for the bound scope; null = never customised. */
    private val visibleModels = MutableStateFlow<Set<String>?>(null)
    private var visibleModelsLoad: Job? = null
    private val contextBreakdownLoading = MutableStateFlow(false)
    private var contextBreakdownJob: Job? = null

    /**
     * Which read owns [contextBreakdownLoading]. A cancelled job still runs its
     * `finally`, and it may run *after* its successor set the flag — without
     * this, a session switch clears "Loading breakdown…" out from under the new
     * session's own in-flight read.
     */
    private var contextBreakdownGeneration = 0L

    /**
     * Which sessions this connection has already asked for a breakdown. A
     * per-session marker, not "do we have one yet": a backend that answers null
     * — or fails — must not turn every subsequent signal into another RPC.
     */
    private val contextBreakdownAttempted = mutableSetOf<String>()
    private val readAloudState = MutableStateFlow<ReadAloudUiState>(ReadAloudUiState.Idle)
    private var readAloudJob: Job? = null
    private val notice = MutableStateFlow<ChatNotice?>(null)

    /**
     * The open's own state, alongside — never inside — [notice].
     *
     * Two readers with two lifetimes: the composer's status line reports what
     * the reader just did and is replaced by the next thing, while the chat
     * pane's failure surface has to survive until the reader retries or leaves.
     * Keeping them in one slot meant a failure could only be recognized by its
     * sentence.
     */
    private val sessionOpen = MutableStateFlow<SessionOpenState>(SessionOpenState.Idle)

    /**
     * The status line, for the notices that are only a sentence — which is all
     * of them but one.
     *
     * Writing through here is what keeps an escape from outliving the state
     * that earned it: every other notice in this ViewModel clears the action
     * simply by being written, so a refusal's `Start new session` cannot
     * survive the next project failure, the next rehome or the next send. The
     * one notice that carries an action writes [notice] directly, in the place
     * that has just classified the refusal.
     */
    private var noticeLine: String?
        get() = notice.value?.text
        set(value) {
            notice.value = value?.let(::ChatNotice)
        }
    private val selectedProjectId = MutableStateFlow<String?>(null)
    private val projectLoadingId = MutableStateFlow<String?>(null)
    private var pendingProfileUnavailableNotice = false
    private val sidebarGrouping = MutableStateFlow(SidebarGrouping.Date)

    /**
     * Desktop's `Archived` filter. A view choice, so it lives here and never in
     * [SessionCache]: the cache holds what the Gateway said, not what the
     * reader is currently looking at.
     */
    private val archivedVisible = MutableStateFlow(false)

    /**
     * What the archived pool has answered on the endpoint and scope it was read
     * under. Endpoint- and scope-scoped, so it is reset — never assumed — when
     * either moves; see [invalidateArchivedPool].
     */
    private val archivedPool = MutableStateFlow(ArchivedPoolState.Idle)
    private var archivedPoolJob: Job? = null
    /** UI-only authority, restored from and saved to a preference. */
    private val profileScope = MutableStateFlow(ProfileScope())
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
    @Volatile private var navigationGeneration = 0L
        set(value) { field = value; gatewayLogsController.dismiss() }
    /**
     * The newest session-open request. `repository.openSession` waits on the
     * Gateway, so a tap can outlive the chat it named; the completion carries
     * this number and proves the screen still belongs to it before adopting or
     * repainting anything.
     */
    private var sessionOpenGeneration = 0L
    private var sidebarGroupingGeneration = 0L
    @Volatile private var profileScopeGeneration = 0L
        set(value) { field = value; gatewayLogsController.dismiss() }
    private var choseInitialSession = false
    private var previousStatuses = emptyMap<String, SessionStatus>()
    private var draftSnapshot = linkedMapOf<String, String>()
    private val draftStoreReady = CompletableDeferred<Unit>()
    /** IDs changed locally in this ViewModel; stale DataStore emissions cannot replace them. */
    private val locallyTouchedDrafts = mutableSetOf<String>()
    private var draftRevision = 0L
    private var draftWrite: Job? = null
    private var composerScope: ComposerControlsScope? = null
    /** The Hermes profile half of the queue scope; null while on the Gateway's own. */
    private var composerQueueProfile: String? = null
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
    private var codingLoad: Job? = null
    private var codingReviewLoad: Job? = null
    private var codingGeneration = 0L
    private var codingReviewGeneration = 0L
    private var observedConnectionStatus: GatewayConnectionStatus? = null
    /** Connection callbacks are the observable seam for a reconnect generation. */
    private var observedRecentImagesGeneration = connectionGeneration()
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
        combine(attachments, attachmentThumbnails, recentImages, recentImageAdds) { drafts, thumbnails, rail, marks ->
            AttachmentBundle(drafts, thumbnails, rail, marks)
        },
    ) { queue, revision, scopeReady, pending, draftsWithThumbnails ->
        LocalComposerState(
            queue = queue,
            historyRevision = revision,
            scopeReady = scopeReady,
            pendingInputs = pending,
            attachments = draftsWithThumbnails.attachments,
            attachmentThumbnails = draftsWithThumbnails.thumbnails,
            recentImages = draftsWithThumbnails.recentImages,
            recentImageAdds = draftsWithThumbnails.recentImageAdds,
        )
    }

    /**
     * The two facts the chat chrome reads that are neither cache truth nor
     * composer state: the host's approval posture, and the saved model
     * shortlist. Bundled because the state assembly below is already at
     * `combine`'s arity.
     */
    private val chromeState = combine(repository.approvalMode, visibleModels, ::ChromeBundle)

    private val gatewayLogsController = GatewayLogsController(
        scope = viewModelScope,
        identity = { GatewayLogsIdentity(cache.endpointGeneration.value, connectionGeneration(),
            activeSessionId.value, profileScope.value.key, navigationGeneration, profileScopeGeneration) },
        connected = { repository.connectionState.value.status == GatewayConnectionStatus.Connected },
        read = { current ->
            // Resolve only after confirmation, never retain the transport from VM creation.
            // The consent identity and this exact transport must both survive until IO/retention.
            val http = if (current()) gatewayHttp() else null
            if (http == null) com.hermesagent.mobile.data.gateway.GatewayLogsResult.Failed
            else com.hermesagent.mobile.data.gateway.readGatewayLogs(http) {
                current() && gatewayHttp() === http
            }
        },
    )

    fun requestGatewayLogs(entryId: String, endpoint: Long) {
        if (endpoint != cache.endpointGeneration.value) return
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
            noticeLine = "Connect to a Gateway before viewing logs."
            return
        }
        val session = activeSessionId.value ?: return
        if (cache.state.value.transcripts[session].orEmpty().filterIsInstance<AssistantTurn>()
                .none { it.id == entryId && it.error != null }) return
        gatewayLogsController.open()
    }

    fun confirmGatewayLogs(generation: Long) = gatewayLogsController.confirm(generation)
    fun dismissGatewayLogs() = gatewayLogsController.dismiss()

    private val diagnosticsController = SendDiagnosticsController(
        scope = viewModelScope,
        endpoint = { cache.endpointGeneration.value },
        connection = connectionGeneration,
        connected = { repository.connectionState.value.status == GatewayConnectionStatus.Connected },
        upload = repository::shareDiagnostics,
    )

    fun requestSendDiagnostics(entryId: String, endpoint: Long) {
        if (endpoint != cache.endpointGeneration.value ||
            repository.connectionState.value.status != GatewayConnectionStatus.Connected) return
        val sessionId = activeSessionId.value ?: return
        val turn = cache.state.value.transcripts[sessionId].orEmpty()
            .filterIsInstance<AssistantTurn>().firstOrNull { it.id == entryId && it.error != null } ?: return
        val details = turn.errorDetails ?: com.hermesagent.mobile.data.session.TurnErrorDetails(details = turn.error.orEmpty())
        diagnosticsController.open(details.copyText(turn.error.orEmpty()))
    }

    fun confirmSendDiagnostics(generation: Long) = diagnosticsController.confirm(generation)
    fun dismissSendDiagnostics() = diagnosticsController.dismiss()

    val uiState: StateFlow<ChatUiState> = combine(
        combine(cache.state, cache.endpointGeneration) { state, endpoint -> state to endpoint },
        combine(query, searchPendingState, searchResults, ::SearchStateBundle),
        draft,
        activeSessionId,
        combine(
            combine(
                repository.imageLoader,
                repository.sessionsWithEarlierMessages,
                repository.sessionPaging,
                ::TranscriptWindowBundle,
            ),
            combine(
                composer,
                voice,
                combine(
                    combine(
                        repository.connectionState,
                        notice,
                        selectedProjectId,
                        projectLoadingId,
                        combine(
                            combine(
                                sidebarGrouping,
                                profileScope,
                                profileRepository.roster,
                                archivedVisible,
                                archivedPool,
                                ::SidebarViewState,
                            ),
                            // Its own flow, not a `notice` field: the pane
                            // renders a failed open from this typed fact and
                            // never from prose, and a notice the reader
                            // dismisses must not take the failure surface
                            // with it.
                            sessionOpen,
                        ) { sidebarView, open -> sidebarView to open },
                    ) { connection, message, projectId, loadingId, sidebarAndOpen ->
                        NavigationState(
                            connection,
                            message,
                            projectId,
                            loadingId,
                            sidebarAndOpen.first,
                            sessionOpen = sidebarAndOpen.second,
                        )
                    },
                    localComposerState,
                ) { navigation, local -> navigation.copy(localComposer = local) },
                combine(activeContextBreakdown, contextBreakdownLoading, ::ContextMeterBundle),
                chromeState,
            ) { composerState, voiceState, navigation, meterBundle, chrome ->
                ComposerBundle(composerState, voiceState, navigation, meterBundle, chrome)
            },
            readAloudState,
        ) { windowBundle, composerBundle, readAloud -> Triple(composerBundle, windowBundle, readAloud) },
    ) { cacheAndEndpoint, searchState, draftText, activeId, bundle ->
        val cacheState = cacheAndEndpoint.first
        // One clock read for this whole state emission: it buckets the rows and
        // ages their metadata, and both must describe the same instant.
        val now = clock()
        val composerBundle = bundle.first
        val imageLoader = bundle.second.imageLoader
        val readAloud = bundle.third
        val navigation = composerBundle.navigation
        val voiceState = composerBundle.voice
        val meterBundle = composerBundle.contextMeter
        val running = cacheState.sessions.values.count { it.status in PROMPT_BLOCKING_STATUSES }
        // SessionCache publishes this alias in the same atomic update that
        // moves a compressed parent to its canonical tip. Resolve it here so
        // the later navigation event cannot create a blank intermediate frame.
        val displayedActiveId = activeId?.let { cacheState.rehomes[it] ?: it }
        val active = displayedActiveId?.let(cacheState.sessions::get)
        // Project handlers at `564aef2946c436500a5e80ee117b66b789b3f99a` are
        // profile-scoped and reject unknown profiles. Catalog state therefore
        // belongs only to the profile it was read from.
        val profileScopeState = navigation.sidebarView.profileScope
        val projectScope = projectProfileScopeOf(profileScopeState)
        val selectedProject = navigation.projectId
            ?.takeIf { projectScope.showsCatalog }
            ?.let(cacheState.projects.projects::get)
        // The sidebar shows one profile at a time; the unified view shows every
        // profile's rows (`apps/desktop/src/app/chat/sidebar/profile-scope.ts:5-13`).
        // The cache keeps every row it has ever been told about either way.
        val scopedSessions = filterSessionsByProfileScope(
            selectedProject?.id
                ?.let { cacheState.projects.memberships[it].orEmpty() }
                ?.mapNotNull(cacheState.sessions::get)
                ?: cacheState.sessions.values.toList(),
            navigation.sidebarView.profileScope.key,
        )
        val projects = if (selectedProject == null && projectScope.showsCatalog) {
            sortProjectsForOverview(
                cacheState.projects.projects.values,
                cacheState.projects.activeProjectId,
            ).map { project ->
                project.copy(
                    previewSessions = filterSessionsByProfileScope(
                        project.previewSessions
                            .map { preview -> cacheState.sessions[preview.id] ?: preview }
                            .filter { it.hidden != true },
                        profileScopeState.key,
                    ),
                )
            }.filter { it.matchesProjectQuery(searchState.query) }
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
            attachmentThumbnails = navigation.localComposer.attachmentThumbnails,
            recentImages = navigation.localComposer.recentImages.forDisplayedScope(
                RecentImagesScope(connectionGeneration(), displayedActiveId),
                navigation.localComposer.attachments,
                navigation.localComposer.recentImageAdds,
            ),
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
        val breakdown = if (displayedActiveId != null) meterBundle.breakdown else null
        val breakdownLoading = meterBundle.loading
        val streamedUsage = active?.usage ?: SessionUsage()
        // `gaugeUsage`: the breakdown overrides its context fields and
        // provenance, so the meter and the panel can never disagree —
        //   `contextBreakdown ? { ...currentUsage, context_estimated,
        //    context_source, context_max, context_percent, context_used } : currentUsage`
        // (`apps/desktop/src/app/shell/hooks/use-statusbar-items.tsx:281-294` @
        // `437116f9497c80d242ce034ff7f5d81dc277a337`). `total` and `model` stay
        // the streamed ones: a resumed session whose breakdown reports
        // `context_max: 0` (no compressor, `agent/context_breakdown.py:144-145` @
        // `437116f9497c80d242ce034ff7f5d81dc277a337`)
        // has no measured usage either, and Desktop hides the item rather than
        // painting the estimate under it.
        val gaugeUsage = if (breakdown != null) {
            streamedUsage.copy(
                contextUsed = breakdown.contextUsed,
                contextMax = breakdown.contextMax.takeIf { it > 0 },
                contextPercent = breakdown.contextPercent,
                contextEstimated = breakdown.contextEstimated,
                contextSource = breakdown.contextSource,
            )
        } else {
            streamedUsage
        }

        val contextLabel = usageContextLabel(
            contextUsed = gaugeUsage.contextUsed,
            contextMax = gaugeUsage.contextMax,
            total = gaugeUsage.total,
            contextEstimated = gaugeUsage.contextEstimated == true,
        )
        val contextDetail = contextBarLabel(
            contextPercent = gaugeUsage.contextPercent?.toDouble(),
            contextMax = gaugeUsage.contextMax,
            contextEstimated = gaugeUsage.contextEstimated == true,
        )
        val contextMeter = if (contextLabel.isNotEmpty()) {
            ContextMeterState(
                label = contextLabel,
                detail = contextDetail,
                usage = gaugeUsage,
                breakdown = breakdown,
                loading = breakdownLoading,
            )
        } else {
            null
        }

        ChatUiState(
            voice = voiceState,
            readAloud = readAloud,
            nowMillis = now,
            sessionRows = buildSessionRows(
                sessions = scopedSessions,
                nowMillis = now,
                query = searchState.query,
                searchPending = searchState.pending,
                serverMatches = searchState.results,
                archivedView = navigation.sidebarView.archivedVisible,
                bucketLabel = bucketLabel ?: productionBucketLabel,
            ),
            archivedVisible = navigation.sidebarView.archivedVisible,
            archivedPool = navigation.sidebarView.archivedPool,
            // Desktop counts listed, non-archived rows whose resolved dot is
            // unread (`store/session-dot-state.ts:186-200` @ `72a3277cd7`) and
            // hides the mark-all action at zero.
            unreadCount = scopedSessions.count {
                it.archived != true && it.displayStatus() == SessionStatus.Unread
            },
            // A genuinely empty account therefore alternates between the
            // placeholder bars and the blank state on every background refresh,
            // because both clauses stay true. That is Desktop's behaviour too —
            // `showSessionSkeletons` has the same two clauses and no latch — and
            // matching it is the point.
            sessionsLoading = bundle.second.sessionPaging.loading && scopedSessions.isEmpty(),
            projects = projects,
            projectsAvailable = cacheState.projects.available,
            sidebarGrouping = navigation.sidebarView.grouping,
            profileRail = ProfileRailState(
                profiles = navigation.sidebarView.roster.profiles,
                scope = navigation.sidebarView.profileScope,
                loaded = navigation.sidebarView.roster.loaded,
            ),
            projectScope = projectScope,
            profiles = ProfilesUiState(
                profiles = navigation.sidebarView.roster.profiles,
                loaded = navigation.sidebarView.roster.loaded,
                connected = navigation.connection.status == GatewayConnectionStatus.Connected,
            ),
            selectedProject = selectedProject,
            projectLoading = selectedProject != null && navigation.loadingProjectId == selectedProject.id,
            activeSession = active,
            activeSessionId = displayedActiveId,
            // `memberships` is projectId -> sessionIds and only hydrated for
            // projects that have been read, so this resolves for a session
            // opened from its project and is null otherwise. That is the
            // honest answer: the catalog has not been asked.
            activeSessionProject = displayedActiveId?.let { id ->
                cacheState.projects.projects.values.firstOrNull { project ->
                    id in cacheState.projects.memberships[project.id].orEmpty()
                }
            },
            diagnosticsEndpointGeneration = cacheAndEndpoint.second,
            transcript = displayedActiveId?.let(cacheState.transcripts::get).orEmpty(),
            query = searchState.query,
            draft = draftText,
            isStreaming = active?.status in STREAMING_STATUSES,
            runningCount = running,
            backgroundPendingInput = backgroundPending,
            connection = navigation.connection,
            notice = navigation.notice,
            // Read from its own flow, not from `notice`: the pane classifies a
            // failed open from this typed fact and never from prose.
            sessionOpen = navigation.sessionOpen,
            composer = composerBundle.composer.copy(
                runtime = runtime,
                visibleModels = composerBundle.chrome.visibleModels,
            ),
            imageLoader = imageLoader,
            contextMeter = contextMeter,
            canShowEarlierMessages = displayedActiveId != null &&
                displayedActiveId in bundle.second.sessionsWithEarlierMessages,
            // Desktop hides the item unless the gateway socket is open
            // (`use-statusbar-items.tsx:569`); this app hides it until the mode
            // is also *known*, because it shows no optimistic default.
            approvalMode = composerBundle.chrome.approval.mode
                ?.takeIf { navigation.connection.status == GatewayConnectionStatus.Connected },
            botChat = displayedActiveId != null &&
                displayedActiveId == botChatSessionId &&
                botChatEndpoint?.cacheGeneration == cacheAndEndpoint.second &&
                botChatEndpoint?.connectionGeneration == connectionGeneration(),
        )
    }.combine(diagnosticsController.state) { state, diagnostics ->
        state.copy(diagnostics = diagnostics)
    }.combine(gatewayLogsController.state) { state, logs ->
        state.copy(gatewayLogs = logs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    init {
        viewModelScope.launch {
            combine(cache.endpointGeneration, repository.connectionState, activeSessionId, profileScope) { _, _, _, _ -> Unit }
                .collect { gatewayLogsController.invalidateIfChanged() }
        }
        // Desktop's sidebar search, and its reason: "Full-text search across
        // *all* sessions (not just the loaded page) so 699 sessions stay
        // findable. Debounced; loaded sessions are matched instantly
        // client-side and merged ahead of the server hits."
        // (`apps/desktop/src/app/chat/sidebar/index.tsx:643-677` @ `72a3277cd7`).
        //
        // Three things scope the answer that Desktop's effect does not have to
        // think about, and all three belong in the key rather than in the body:
        // the profile the rail is standing in, which backend this is, and
        // whether the rail is showing sessions at all. See [SessionSearchKey].
        viewModelScope.launch {
            combine(
                query,
                profileScope,
                cache.endpointGeneration,
                sidebarGrouping,
                selectedProjectId,
            ) { raw, scope, endpoint, grouping, projectId ->
                SessionSearchKey(
                    query = raw.trim(),
                    scope = SessionSearchScope(
                        // The unified view has no union to ask for: the route
                        // opens exactly one profile's database
                        // (`hermes_cli/web_routers/sessions.py:268` @
                        // `72a3277cd7`), so sending the *active* profile there
                        // would merge one profile's server hits into an
                        // all-profile list. Send none, which is the launch
                        // profile — the same leg `sessionListProfiles` asks
                        // first — and say so in the ledger.
                        profile = if (scope.isAll) null else scope.sessionProfileParam,
                        endpoint = endpoint,
                    ),
                    // The project overview reuses this same field as a filter
                    // over projects and renders no session rows at all
                    // (`ui/sessions/SessionList.kt`), so a backend session
                    // search there costs a request per settled keystroke for an
                    // answer nothing draws. A scope that cannot list the catalog
                    // draws session rows instead, so its field is a session
                    // search however the grouping control is set.
                    sessionsView = grouping != SidebarGrouping.Project || projectId != null ||
                        !projectProfileScopeOf(scope).showsCatalog,
                )
            }
                .distinctUntilChanged()
                // A result belongs to the complete search key: query, scope,
                // endpoint and view. Retire it before the debounce whenever
                // that key changes, while local matches still answer instantly.
                // collectLatest cancels the previous key's in-flight request.
                .onEach { searchResults.value = null }
                .collectLatest { key ->
                    if (key.query.isEmpty() || !key.sessionsView) {
                        searchResults.value = null
                        searchPendingState.value = false
                        return@collectLatest
                    }
                    searchPendingState.value = true
                    // `collectLatest` cancels this wait when the next keystroke
                    // arrives, which is the whole debounce: the request is only
                    // ever issued for a query that stood still.
                    delay(SESSION_SEARCH_DEBOUNCE_MILLIS)
                    // Null on refusal, on an absent route and on failure — the
                    // list falls back to its client-side matches with no error
                    // banner, exactly as Desktop swallows its own (`:641`).
                    searchResults.value = repository.searchSessions(key.query, key.scope.profile)
                    searchPendingState.value = false
                }
        }
        // The breakdown is a *transition* reader, not a poller. Desktop's effect
        // re-runs only when the focused session or `busy` changes
        // (`apps/desktop/src/app/shell/hooks/use-context-breakdown.ts:31-57` @
        // `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`). `cache.state` here is
        // not that: it republishes on every transcript append of *any* session
        // (`SessionCache.kt:207-242`), so a background turn's `message.delta`
        // stream would re-enter this body dozens of times a second. The guard
        // that actually bounds the RPC is `contextBreakdownAttempted`: a
        // session is asked once per (session change, turn end) transition and
        // "we still have no breakdown" is never a reason to ask again. The
        // derived signal and `distinctUntilChanged` only thin the emissions
        // this body sees; removing them changes work, not behaviour.
        viewModelScope.launch {
            var observed: ContextFetchSignal? = null

            combine(
                activeSessionId,
                cache.state,
                repository.activeTurns,
                repository.connectionState,
            ) { activeId, cacheState, turns, connection ->
                val activeSession = activeId?.let { cacheState.rehomes[it] ?: it }?.let(cacheState.sessions::get)
                val busy = activeSession?.status in STREAMING_STATUSES ||
                    (activeId != null && activeId in turns) ||
                    activeSession?.status == SessionStatus.Working
                ContextFetchSignal(
                    sessionId = activeId,
                    busy = busy,
                    connected = connection.status == GatewayConnectionStatus.Connected,
                    // Asked, never forced: `loadContextBreakdown` refuses to open
                    // a session, so the read waits for navigation to bind one.
                    runtimeReady = activeId != null && repository.hasLiveRuntime(activeId),
                )
            }.distinctUntilChanged().collect { signal ->
                val previous = observed
                observed = signal
                val sessionChanged = previous?.sessionId != signal.sessionId
                val turnEnded = previous?.busy == true && !signal.busy && !sessionChanged

                if (sessionChanged) {
                    contextBreakdownJob?.cancel()
                    contextBreakdownJob = null
                    activeContextBreakdown.value = null
                    contextBreakdownLoading.value = false
                    // Re-arm: coming back to a session re-reads it, exactly as
                    // Desktop's `sessionId` dependency does.
                    signal.sessionId?.let(contextBreakdownAttempted::remove)
                }
                if (!signal.connected) {
                    // The next backend is a different machine. Nothing that was
                    // read on this one counts as read on it.
                    contextBreakdownAttempted.clear()
                }
                if (signal.busy) {
                    // Mid-turn the transcript changes on every delta and the
                    // Gateway already streams measured usage, so an estimate
                    // would be both stale and wasteful (`use-context-breakdown
                    // .ts:32-36`).
                    contextBreakdownJob?.cancel()
                    contextBreakdownJob = null
                    contextBreakdownLoading.value = false
                    return@collect
                }

                val sessionId = signal.sessionId ?: return@collect
                if (!signal.connected || !signal.runtimeReady) return@collect
                val firstAttempt = contextBreakdownAttempted.add(sessionId)
                if (!firstAttempt && !turnEnded) return@collect

                val generation = ++contextBreakdownGeneration
                contextBreakdownJob?.cancel()
                contextBreakdownLoading.value = true
                contextBreakdownJob = viewModelScope.launch {
                    try {
                        val breakdown = repository.loadContextBreakdown(sessionId)
                        // A resolved-null answer never clears a good breakdown:
                        // `use-context-breakdown.ts:43` @ 72a3277cd7 only sets
                        // fetched when the payload is truthy.
                        if (activeSessionId.value == sessionId && breakdown != null) {
                            activeContextBreakdown.value = breakdown
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A failure keeps the last breakdown for that session
                    } finally {
                        if (generation == contextBreakdownGeneration) {
                            contextBreakdownLoading.value = false
                        }
                    }
                }
            }
        }
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
            val restoreGeneration = profileScopeGeneration
            val restored = profileScopeStore.profileScope.first()
            if (profileScopeGeneration == restoreGeneration) profileScope.value = restored
        }
        // A persisted scope can name a profile this Gateway does not have — it
        // was deleted or renamed on the host, or the scope came from another
        // Gateway entirely. At `564aef2946c436500a5e80ee117b66b789b3f99a`,
        // profile-scoped project handlers reject that request rather than
        // falling back to the launch profile. Once the roster has answered, a
        // scope it does not contain goes back to the Gateway's own profile.
        viewModelScope.launch {
            combine(profileScope, profileRepository.roster) { scope, roster -> scope to roster }
                .collect { (scope, roster) ->
                    if (!roster.loaded) return@collect
                    val active = normalizeProfileKey(scope.activeProfile)
                    if (active == DEFAULT_PROFILE) return@collect
                    if (roster.profiles.any { it.key == active }) return@collect
                    // The unified view is kept: only the profile new work
                    // targets is stale, not the choice to browse everything.
                    pendingProfileUnavailableNotice = true
                    applyProfileScope(scope.copy(activeProfile = DEFAULT_PROFILE))
                }
        }
        // The repository only ever learns the scope as the `profile` parameter
        // its session RPCs carry. Re-listing after a scope change is what makes
        // a newly visible profile's rows arrive; the cache keeps the rest.
        viewModelScope.launch {
            var seenRouting = false
            var projectSubject: String? = null
            combine(profileScope, profileRepository.roster) { scope, roster ->
                ProfileRouting(scope.sessionProfileParam, sessionListProfiles(scope, roster.profiles))
            }
                .distinctUntilChanged()
                .collect { routing ->
                    val first = !seenRouting
                    seenRouting = true
                    val projectSubjectChanged = projectSubject != routing.activeProfile
                    projectSubject = routing.activeProfile
                    if (!first && projectSubjectChanged) navigationGeneration += 1
                    repository.setProfileRouting(routing)
                    if (!first && projectSubjectChanged) {
                        // A project detail, its loading marker and a failure are
                        // UI state about the scope just left. The repository
                        // clears backend snapshots above; this clears the rest
                        // before a new profile can paint.
                        selectedProjectId.value = null
                        projectLoadingId.value = null
                        noticeLine = if (pendingProfileUnavailableNotice) {
                            pendingProfileUnavailableNotice = false
                            "That profile is no longer available."
                        } else {
                            null
                        }
                    }
                    // The archived pool is one profile scope's set exactly as
                    // the live list is, and only the live list is re-listed
                    // below. Invalidated *after* the routing is installed, so
                    // the re-read carries the scope the reader just chose.
                    if (!first) invalidateArchivedPool()
                    // `approvals.mode` is read and written through a
                    // `@_profile_scoped` handler (`tui_gateway/methods_config.py:228-229`,
                    // `tui_gateway/methods_config_set.py:458-459` @ `72a3277cd7`), so a
                    // scope change is a change of subject: re-read it. The
                    // first routing is the connection collector's to read.
                    if (!first &&
                        repository.connectionState.value.status == GatewayConnectionStatus.Connected
                    ) {
                        launch { runCatching { repository.refreshApprovalMode() } }
                    }
                    // A scope that has not moved off the Gateway's own profile
                    // is what the connection's own bootstrap already listed;
                    // asking again at every launch would be a second
                    // session.list for the same rows.
                    if (first && routing == ProfileRouting()) return@collect
                    if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) return@collect
                    runCatching { repository.refreshSessions() }
                    runCatching { repository.refreshProjects() }
                }
        }
        // The other half of the archived pool's scope: which backend it came
        // from. `SessionCache.resetForEndpointSwitch()` is this app's one
        // wholesale clear and the switch already calls it
        // (`ConnectionSwitchController.kt:223`), so the generation it bumps is
        // the same seam telling this reader that the set it holds belongs to a
        // machine it is no longer talking to. The connection status is folded
        // in because the new endpoint is not up at the moment of the switch:
        // the read is issued when there is finally a Gateway to ask.
        viewModelScope.launch {
            var generation = cache.endpointGeneration.value
            combine(cache.endpointGeneration, repository.connectionState) { endpoint, connection ->
                endpoint to (connection.status == GatewayConnectionStatus.Connected)
            }
                .distinctUntilChanged()
                .collect { (endpoint, _) ->
                    diagnosticsController.invalidateIfChanged()
                    if (endpoint != generation) {
                        generation = endpoint
                        // A durable id is only meaningful on the endpoint that
                        // produced it. Drop the Bot Chat capability before any
                        // later cache/rehome event can reuse that id here.
                        clearBotChatCapability(rehomeActive = true)
                        clearRecentImages()
                        clearAttachmentDrafts()
                        invalidateArchivedPool()
                    } else {
                        reloadArchivedPoolWhenReady()
                    }
                }
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
                diagnosticsController.invalidateIfChanged()
                if (connectionGeneration() != observedRecentImagesGeneration) {
                    observedRecentImagesGeneration = connectionGeneration()
                    clearRecentImages()
                }
                if (connection.status == observedConnectionStatus) return@collect
                observedConnectionStatus = connection.status
                invalidateComposerRuntimeState()
                val connected = connection.status == GatewayConnectionStatus.Connected
                profileRepository.connectionChanged(
                    if (connection.status == GatewayConnectionStatus.Disconnected) {
                        GatewayProfileConnectionState.Gone
                    } else {
                        GatewayProfileConnectionState.Changed
                    },
                )
                if (connected) {
                    refreshComposer(activeSessionId.value)
                    activeSessionId.value?.let(::drainQueueIfIdle)
                    // profiles.list is a slow-lane call with its own budget, so
                    // it rides its own job and never delays the composer.
                    launch { runCatching { profileRepository.refreshProfiles() } }
                    // The approval control shows nothing until this answers, so
                    // it must not wait behind the composer's own reads.
                    launch { runCatching { repository.refreshApprovalMode() } }
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
                    noticeLine = "That project is no longer available."
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
        // The queue is private per endpoint *and* per Hermes profile: text
        // parked while scoped to one profile must never be presented while
        // scoped to another.
        combine(composerControlsStore.activeScope, profileScope) { scope, profiles ->
            scope to normalizeProfileKey(profiles.activeProfile).takeIf { it != DEFAULT_PROFILE }
        }
            .distinctUntilChanged()
            .collect { (scope, hermesProfile) -> bindComposerScope(scope, hermesProfile) }
    }

    private fun isComposerScopeBound(scope: ComposerControlsScope, hermesProfile: String?): Boolean =
        composerScope == scope && composerQueueProfile == hermesProfile

    private fun bindComposerScope(scope: ComposerControlsScope, hermesProfile: String?) {
        if (isComposerScopeBound(scope, hermesProfile)) return
        // Any change to the *endpoint* scope is a change of session set, not
        // just a change of address. The scope's second half is the SSH *remote
        // Hermes profile*, and two profiles on one host are two different
        // Hermes homes with two different session histories — comparing only
        // the address would leave one install's sessions painted under the
        // other's. A provider change on a Remote row costs a reload it did not
        // strictly need; showing another install's conversations costs more.
        //
        // The first bind is not a change: there was no endpoint to leave. Nor
        // is a Hermes-profile switch, which reaches this function with the same
        // endpoint: those sessions are the same connection's, the rail's own
        // scope decides which of them are shown, and `selectProfile` already
        // starts fresh where Desktop does — leaving the endpoint here would
        // also close the open session on a mere All-profiles toggle, which
        // Desktop deliberately leaves alone.
        val endpointChanged = composerScope != null && composerScope != scope
        composerScope = scope
        composerQueueProfile = hermesProfile
        if (endpointChanged) leaveEndpoint()
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
                ComposerQueueScope.forConnectionProfile(
                    scope.connectionIdentity,
                    scope.profileIdentity,
                    hermesProfile,
                ),
            )
            if (isComposerScopeBound(scope, hermesProfile)) {
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
                if (!isComposerScopeBound(scope, hermesProfile)) return@collect
                if (!newDraftPreferenceTouched) {
                    newDraftPreference = preference
                    if (activeSessionId.value == null) publishFreshDraftControls()
                }
            }
        }
        // The shortlist is this endpoint's: another Gateway is another catalog,
        // and its keys would name models this one does not serve. Cleared
        // before the new scope's own document arrives.
        visibleModels.value = null
        visibleModelsLoad?.cancel()
        visibleModelsLoad = viewModelScope.launch {
            composerControlsStore.visibleModels(scope).collect { keys ->
                if (!isComposerScopeBound(scope, hermesProfile)) return@collect
                visibleModels.value = keys
            }
        }
        refreshComposer(activeSessionId.value)
    }

    /**
     * Forget the endpoint-scoped view state this surface owns.
     *
     * Only UI-only state lives here: which session is open, the search text,
     * a project drill-in, and a stale notice. Cached sessions and transcripts
     * are the cache's to clear, and the connection switch clears them; the
     * unread markers go with them because they are a field on those rows.
     */
    private fun leaveEndpoint() {
        navigationGeneration += 1
        invalidateCompletionState()
        choseInitialSession = false
        activeSessionId.value = null
        selectedProjectId.value = null
        projectLoadingId.value = null
        query.value = ""
        noticeLine = null
        previousStatuses = emptyMap()
    }

    private fun invalidateComposerRuntimeState() {
        composerGeneration += 1
        liveMutationGeneration += 1
        inputGeneration += 1
        liveComposerControls = null
        composerLoad?.cancel()
        completionLoad?.cancel()
        codingLoad?.cancel()
        codingReviewLoad?.cancel()
        codingGeneration += 1
        codingReviewGeneration += 1
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

    /**
     * Write the host's approval mode. Optimism, confirmation and rollback all
     * belong to the repository, which owns the value; this only reports a
     * refusal, because a control that silently springs back explains nothing.
     */
    fun selectApprovalMode(mode: ApprovalMode) {
        // Approval mode is profile-global, not Bot-session state. Phase A is
        // transcript-only nevertheless, so this surface cannot change it.
        if (refuseBotChatMutation() != null) return
        if (repository.approvalMode.value.mode == mode) return
        viewModelScope.launch {
            val outcome = runCatching { repository.setApprovalMode(mode) }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                ApprovalModeOutcome.Rejected(APPROVAL_MODE_REJECTED)
            }
            if (outcome is ApprovalModeOutcome.Rejected) noticeLine = outcome.safeMessage
        }
    }

    /**
     * Show or hide one model in the picker. The arithmetic — including the
     * hide-all sentinel a provider's last model leaves behind — is
     * `data/composer/ModelVisibility.kt`, ported from Desktop's own store.
     */
    fun toggleModelVisible(providerId: String, model: String) {
        val providers = catalogProviders()
        persistVisibleModels(toggleModelVisibility(visibleModels.value, providers, providerId, model))
    }

    /** Flip one provider's master switch, enabling or hiding every family it has. */
    fun setProviderModelsVisible(providerId: String, visible: Boolean) {
        val providers = catalogProviders()
        persistVisibleModels(setProviderVisibility(visibleModels.value, providers, providerId, visible))
    }

    private fun catalogProviders(): List<ModelProvider> =
        (composer.value.catalog as? ComposerCatalogUiState.Ready)?.catalog?.providers.orEmpty()

    /**
     * Publish first, then persist. The sheet is a run of rapid toggles and the
     * DataStore write is asynchronous; waiting for disk would make every switch
     * lag behind the finger, and the store's own emission re-publishes the same
     * value when it lands.
     *
     * With no bound scope there is no disk slot, so the choice would be lost at
     * the next bind with nothing on screen to say so. That is the same outcome
     * as a failed write and it says the same sentence, and nothing is painted:
     * a switch that springs back is worse than one that never moved.
     */
    private fun persistVisibleModels(keys: Set<String>) {
        val scope = composerScope
        if (scope == null) {
            noticeLine = MODEL_VISIBILITY_NOT_SAVED
            return
        }
        visibleModels.value = keys
        viewModelScope.launch {
            runCatching { composerControlsStore.saveVisibleModels(scope, keys) }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                noticeLine = MODEL_VISIBILITY_NOT_SAVED
            }
        }
    }

    fun selectModel(selection: ComposerModelSelection) {
        if (refuseBotChatMutation() != null) return
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
        if (refuseBotChatMutation() != null) return
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
        if (refuseBotChatMutation() != null) return
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
                        noticeLine = "This new-chat choice will not be remembered after you leave this Gateway."
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
                        noticeLine = "This sidebar view could not be saved. Try changing it again."
                    }
                }
        }
    }

    /**
     * Switch the sidebar to `name`: leave the unified view, point new chats at
     * it, and — when this is a real switch — start fresh there rather than
     * leaving the reader inside another profile's session
     * (`apps/desktop/src/store/profile.ts:453-466`).
     *
     * Nothing here interrupts anything. A turn running in the profile being
     * left keeps running and lands as an unread row, exactly as it does when
     * the reader simply opens another session.
     */
    fun selectProfile(name: String) {
        val target = normalizeProfileKey(name)
        val current = profileScope.value
        val switching = current.showAllProfiles || target != normalizeProfileKey(current.activeProfile)
        applyProfileScope(ProfileScope(activeProfile = target, showAllProfiles = false))
        if (switching) startFreshSessionInScope()
    }

    /**
     * Desktop's opt-in unified browse view (`store/profile.ts:481-483`). It
     * deliberately leaves the concrete profile alone, so leaving it returns to
     * the profile you were in — and it does not start a fresh session.
     */
    fun showAllProfiles() {
        val current = profileScope.value
        if (current.showAllProfiles) return
        applyProfileScope(current.copy(showAllProfiles = true))
    }

    private fun applyProfileScope(scope: ProfileScope) {
        profileScopeGeneration += 1
        if (profileScope.value == scope) return
        profileScope.value = scope
        viewModelScope.launch {
            runCatching { profileScopeStore.saveProfileScope(scope) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (profileScope.value == scope) {
                        noticeLine = "This profile could not be saved. Try switching again."
                    }
                }
        }
    }

    /**
     * Desktop's `requestFreshSession()`: land in a new chat in the profile just
     * picked. The project drill-in is left too — a project catalog belongs to
     * the profile it was read from.
     */
    private fun startFreshSessionInScope() {
        navigationGeneration += 1
        invalidateCompletionState()
        selectedProjectId.value = null
        projectLoadingId.value = null
        flushDraft()
        rehome(null)
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
            noticeLine = "Connect to a Gateway before creating a project."
            return
        }
        val createGeneration = ++navigationGeneration
        viewModelScope.launch {
            runCatching { repository.createProject(name, folderPath) }
                .onSuccess { outcome ->
                    if (navigationGeneration != createGeneration) return@onSuccess
                    if (!outcome.scopeCurrent) return@onSuccess
                    if (!outcome.catalogRefreshed) {
                        noticeLine = "The project was created, but Projects could not be refreshed. Reopen Sessions to refresh."
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
                        noticeLine = "The project could not be created. Check the name and remote folder, then try again."
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
                    noticeLine = "Projects could not be refreshed. Try opening Sessions again."
                }
        }
    }

    private fun loadProject(id: String) {
        projectLoadingId.value = id
        noticeLine = null
        viewModelScope.launch {
            runCatching { repository.openProject(id) }
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (selectedProjectId.value == id) {
                        noticeLine = "This project could not be opened. Check the Gateway and try again."
                    }
                }
            if (projectLoadingId.value == id) projectLoadingId.value = null
        }
    }

    fun selectSession(id: String) {
        if (activeSessionId.value != id) {
            navigationGeneration += 1
            flushDraft()
            clearBotChatCapability()
            rehome(id)
        }
        // A notification tap names a session that may already be the one on
        // screen, and the open is what makes its history authoritative. Only
        // navigation runs when the selection actually changes: rehoming an
        // unchanged id would disturb its draft and composer state instead.
        viewModelScope.launch {
            openAndAdopt(id)
        }
    }

    /**
     * Phase B handoff: explicit roster profile, writable canonical Bot Chat.
     *
     * The capability recorded here is what licenses the composer's send path in
     * this one chat; every other mutation door stays refused by
     * [refuseBotChatMutation]. It is endpoint-scoped, so a switch retires it
     * before a durable id from the machine this device left can be reused.
     *
     * Opening stays inert: this resumes or opens the chat that the roster
     * resolved (or that Phase B just created and titled), and sends nothing. The
     * person's first prompt is what arms live delivery.
     */
    fun openBotChat(profile: String, durableId: String, onFinished: (Boolean) -> Unit) {
        val generation = ++navigationGeneration
        val endpoint = connectionGeneration()
        val previousActiveId = activeSessionId.value
        flushDraft()
        botChatSessionId = durableId
        val endpointBinding = BotChatEndpoint(cache.endpointGeneration.value, endpoint)
        botChatEndpoint = endpointBinding
        rehome(durableId, applyOpenSideEffects = false)
        viewModelScope.launch {
            var finished = false
            fun finish(opened: Boolean) {
                if (!finished) {
                    finished = true
                    onFinished(opened)
                }
            }

            fun stillOwnsRequest(): Boolean =
                generation == navigationGeneration &&
                    endpoint == connectionGeneration() &&
                    botChatEndpoint == BotChatEndpoint(cache.endpointGeneration.value, endpoint) &&
                    activeSessionId.value == durableId

            try {
                // The resume is bound to the endpoint the roster resolved this
                // id on. This call waits on the repository's navigation mutex,
                // and the app can leave the endpoint while it waits — the
                // replacement never minted the id, so the repository refuses
                // rather than resuming it there.
                val canonicalId = repository.openSessionAtEndpoint(
                    durableId,
                    profile,
                    endpointBinding.cacheGeneration,
                )
                if (!stillOwnsRequest()) {
                    // The current request still completes, but an intervening
                    // navigation or endpoint owns the screen now. If its
                    // provisional Bot Chat is still foreground, remove it
                    // rather than carrying an endpoint-local id forward.
                    if (endpoint != connectionGeneration() && activeSessionId.value == durableId) rehome(null)
                    finish(false)
                    return@launch
                }
                adoptCanonicalSession(durableId, canonicalId, applyOpenSideEffects = false)
                finish(true)
            } catch (cancelled: CancellationException) {
                if (stillOwnsRequest()) {
                    clearBotChatCapability()
                    rehome(previousActiveId)
                }
                finish(false)
                throw cancelled
            } catch (_: Throwable) {
                if (stillOwnsRequest()) {
                    clearBotChatCapability()
                    // The Bot Chat has not become the active chat unless its
                    // resume succeeded. Restore the regular chat it replaced.
                    rehome(previousActiveId)
                }
                finish(false)
            }
        }
    }

    fun createSession() {
        // This is the person's explicit escape from a Bot Chat, not a Bot-open
        // side effect. Clear first so New Chat is usable.
        clearBotChatCapability()
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
            noticeLine = "Connect to a Gateway before starting a session."
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
                noticeLine = "A new session could not be started. Check the Gateway and try again."
            }
        }
    }

    fun branchFromReply(entryId: String) {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value
        if (sessionId == null || repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
            noticeLine = "Nothing to branch. Start or resume a chat before branching."
            return
        }
        if (cache.session(sessionId)?.status in PROMPT_BLOCKING_STATUSES) {
            noticeLine = "Session busy. Stop the current turn before branching this chat."
            return
        }

        viewModelScope.launch {
            try {
                val authoritativeHistory = repository.fetchSessionHistory(sessionId)
                if (cache.session(sessionId)?.status in PROMPT_BLOCKING_STATUSES) {
                    noticeLine = "Session busy. Stop the current turn before branching this chat."
                    return@launch
                }
                if (activeSessionId.value != sessionId) {
                    // Desktop aborts if session ownership drifted while the RPC
                    // ran, so don't paint anything until this call returns.
                    return@launch
                }
                val localTranscript = cache.transcript(sessionId)
                val count = when (val plan = deriveBranchCount(localTranscript, authoritativeHistory, entryId)) {
                    BranchPlan.Whole -> null
                    is BranchPlan.Keep -> plan.count
                    BranchPlan.Unlocatable -> {
                        noticeLine = "Nothing to branch. This message has no text to branch from."
                        return@launch
                    }
                }

                val createdControls = composer.value.controls
                val createdCatalog = composer.value.catalog
                val newId = repository.branchSession(sessionId, count)
                if (activeSessionId.value != sessionId) {
                    // Desktop aborts on drift after branching, so don't swap
                    // composer state and don't switch to the result session.
                    return@launch
                }
                flushDraft()
                rehome(newId)
                composer.value = ComposerUiState(
                    catalog = createdCatalog,
                    controls = createdControls,
                    isLiveSession = true,
                )
                if (createdCatalog !is ComposerCatalogUiState.Ready) {
                    refreshComposer(newId, retainControlsUntilSessionInfo = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                noticeLine = "Branch failed. Check the Gateway and try again."
            }
        }
    }

    fun regenerateReply(entryId: String) {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        val endpoint = cache.endpointGeneration.value
        fun stillOwnsReply() = activeSessionId.value == sessionId && cache.endpointGeneration.value == endpoint

        viewModelScope.launch {
            fun setInterruptNotice(interrupt: GatewayInterruptOutcome) {
                when (interrupt) {
                    GatewayInterruptOutcome.Interrupted -> Unit
                    GatewayInterruptOutcome.NeedsInput -> noticeLine = "Hermes needs a response. Answer the request above."
                    else -> noticeLine = "Hermes could not be stopped. Check the Gateway connection."
                }
            }

            suspend fun interruptForRefresh(): Boolean {
                if (!stillOwnsReply()) return false
                val outcome = try {
                    repository.requestInterrupt(sessionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (stillOwnsReply()) {
                        noticeLine = "Hermes could not be stopped. Check the Gateway connection."
                    }
                    return false
                }
                if (outcome == GatewayInterruptOutcome.Interrupted) return true
                if (stillOwnsReply()) setInterruptNotice(outcome)
                return false
            }

            try {
                if (!stillOwnsReply()) return@launch
                val transcript = cache.transcript(sessionId)
                val plan = planRegenerate(transcript, entryId)
                if (plan !is RegeneratePlan.Ready) return@launch

                if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) {
                    if (stillOwnsReply()) {
                        noticeLine = "Connect to a Gateway before refreshing this reply."
                    }
                    return@launch
                }

                var targetRowId = plan.sourceRowId
                val failedTurn = transcript.firstOrNull { it.id == entryId } as? AssistantTurn
                val startupFailure = failedTurn?.errorDetails?.let {
                    it.layer == "runtime" && it.code == "agent_init_failed"
                } == true
                // A retained startup failure is a new occurrence, never an older identical history row.
                if (targetRowId == null && !startupFailure) {
                    val authoritativeHistory = repository.fetchSessionHistory(sessionId)
                    if (!stillOwnsReply()) return@launch
                    val matchingUsers = authoritativeHistory.filterIsInstance<UserTurn>().filter { it.rowId != null && it.text.trim() == plan.sourceText.trim() }
                    if (matchingUsers.size == 1) {
                        targetRowId = matchingUsers.first().rowId
                    } else if (matchingUsers.size > 1) {
                        val lastDurableUserTurn = authoritativeHistory.findLast { it is UserTurn && it.rowId != null }
                        if (plan.sourceIsLastUserTurn && matchingUsers.last() == lastDurableUserTurn) {
                            targetRowId = matchingUsers.last().rowId
                        }
                    }
                }

                if (targetRowId == null) {
                    if (plan.sourceIsLastUserTurn && repository.retryRetainedFailure(sessionId, plan.sourceText, endpoint)) return@launch
                    if (stillOwnsReply()) noticeLine =
                        "Refresh could not find this turn in the session history. Reopen the session and try again."
                    return@launch
                }

                val sessionStatus = cache.session(sessionId)?.status
                if (sessionStatus == SessionStatus.NeedsInput) {
                    if (stillOwnsReply()) {
                        noticeLine = "Hermes needs a response. Answer the request above."
                    }
                    return@launch
                }
                if (sessionStatus == SessionStatus.Working || sessionStatus == SessionStatus.Stalled) {
                    if (!interruptForRefresh()) return@launch
                }

                var retries = 1
                while (true) {
                    try {
                        if (!stillOwnsReply()) return@launch
                        repository.regenerate(sessionId, plan.sourceText, targetRowId, plan.sourceEntryId, endpoint)
                        break
                    } catch (e: GatewayRpcException) {
                        // Match the repository's own refusal ("Hermes is already working in this session.") and the Gateway's 4090 busy refusal.
                        val isBusy = e.message?.contains("already working", ignoreCase = true) == true ||
                            e.message?.contains("busy", ignoreCase = true) == true
                        if (retries > 0 && isBusy) {
                            retries--
                            if (!interruptForRefresh()) return@launch
                        } else {
                            if (stillOwnsReply()) noticeLine = "Regenerate failed. Check the Gateway and try again."
                            break
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (stillOwnsReply()) noticeLine = "Regenerate failed. Check the Gateway and try again."
            }
        }
    }

    fun renameSession(id: String, newTitle: String) {
        viewModelScope.launch {
            try {
                renameSessionAsync(id, newTitle)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                noticeLine = e.message ?: "Rename failed. Check the Gateway and try again."
            }
        }
    }

    suspend fun renameSessionAsync(id: String, newTitle: String): String {
        refuseBotChatMutation(id)?.let { throw it }
        return repository.renameSession(id, newTitle)
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            try {
                deleteSessionAsync(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                noticeLine = e.message ?: "Delete failed. Check the Gateway and try again."
            }
        }
    }

    suspend fun deleteSessionAsync(id: String) {
        refuseBotChatMutation(id)?.let { throw it }
        repository.deleteSession(id)
        if (activeSessionId.value == id) {
            startFreshSessionInScope()
        }
        noticeLine = "Session deleted"
    }

    /**
     * Pin, archive and read-state all go the same way: the repository paints
     * the row, writes `PATCH /api/sessions/{id}` and repaints on refusal, and
     * what the person is told is the message it raised. The notice slot is this
     * app's one outcome surface, so the failure is reported here rather than
     * twice.
     *
     * All three run on [viewModelScope] and none of them suspends its caller.
     * The press comes from a row's own menu, and every one of these verbs takes
     * that row off the list it was pressed on — an archive leaves the live
     * pool, a pin moves into the Pinned section. A coroutine owned by the
     * composable that is being removed would be cancelled mid-flight: the
     * Gateway never told, and neither the success nor the rollback branch run.
     */
    fun setSessionPinnedAsync(id: String, pinned: Boolean) {
        if (refuseBotChatMutation(id) != null) return
        viewModelScope.launch {
            reportingFailure("Could not update pin. Check the Gateway and try again.") {
                repository.setSessionPinned(id, pinned)
            }
        }
    }

    fun setSessionArchivedAsync(id: String, archived: Boolean) {
        if (refuseBotChatMutation(id) != null) return
        viewModelScope.launch {
            reportingFailure("Could not update that chat. Check the Gateway and try again.") {
                repository.setSessionArchived(id, archived)
                if (archived && activeSessionId.value == id) startFreshSessionInScope()
            }
        }
    }

    fun setSessionUnreadAsync(id: String, unread: Boolean) {
        if (refuseBotChatMutation(id) != null) return
        viewModelScope.launch {
            reportingFailure(UNREAD_FAILED) { repository.setSessionUnread(id, unread) }
        }
    }

    /**
     * Desktop's `Archived` toggle: swap the pool the list draws from, and read
     * the archived set every time it is asked for.
     *
     * The read happens on the way *in* only, exactly as Desktop's
     * `useEffect(… if (showArchived) void loadArchivedSessions(), [showArchived])`
     * does (`app/chat/sidebar/index.tsx:1325-1331` @ `72a3277cd7`): turning the
     * view off changes nothing about what the Gateway was asked, because the
     * live list was never the thing carrying these rows.
     */
    fun setArchivedVisible(visible: Boolean) {
        if (archivedVisible.value == visible) return
        archivedVisible.value = visible
        if (visible) loadArchivedPool()
    }

    /**
     * Read the `archived=only` pool, and say so while it is being read.
     *
     * The pool has no paging flow of its own to publish `loading` through
     * (`GatewaySessionRepository.readSessionPages` deliberately leaves the live
     * list's pager alone for it), so the marker lives here. Until it says
     * [ArchivedPoolState.Loaded] the list must not paint `Nothing archived`:
     * that sentence is a claim about the account, and a read in flight, a read
     * that failed, and a Gateway that cannot answer at all are three different
     * things — none of them "nothing is archived".
     *
     * Desktop keeps the same marker (`$archivedSessionsLoading`,
     * `store/sidebar-archive.ts:12,19,28` @ `72a3277cd7`) but spends it on
     * re-entry alone, and its `catch` sets the archived set to `[]`
     * (`:25-27`) — so there a failed read renders as an empty account. That is
     * the divergence this ships, ledgered in
     * `docs/parity/session-list-sections.md`.
     */
    private fun loadArchivedPool() {
        archivedPoolJob?.cancel()
        archivedPool.value = ArchivedPoolState.Loading
        archivedPoolJob = viewModelScope.launch {
            try {
                repository.loadArchivedSessions()
                archivedPool.value = ArchivedPoolState.Loaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                archivedPool.value = when {
                    e is GatewayRpcException && e.message == ARCHIVED_UNSUPPORTED ->
                        ArchivedPoolState.Unsupported
                    else -> ArchivedPoolState.Failed
                }
                // Only a Gateway refusal carries product copy: what a
                // `GatewayRpcException` raises here is either this app's own
                // sentence or a `safeMessage`, which by contract never carries a
                // byte the Gateway wrote (`GatewayRestClient.kt:103-110`). A
                // parse or state failure would put an implementation sentence in
                // a slot that is product-facing, so it gets the fallback.
                noticeLine = (e as? GatewayRpcException)?.message?.takeIf(String::isNotBlank)
                    ?: ARCHIVED_LOAD_FAILED
            }
        }
    }

    /**
     * Forget what the archived pool said, and read it again if it is on screen.
     *
     * Two things scope that pool and neither of them is in it: the endpoint it
     * was read from, and the profile routing it was read under. A connection
     * switch clears every row through [SessionCache.resetForEndpointSwitch]
     * (`ConnectionSwitchController.kt:223`) and a profile change re-lists the
     * live pool only — so without this the Archived view sits on the previous
     * backend's answer, or on `Nothing archived`, which is then false about
     * both. The reader's *choice* to be in the Archived view survives; only
     * what this app believes about the set does not.
     */
    private fun invalidateArchivedPool() {
        archivedPoolJob?.cancel()
        archivedPoolJob = null
        archivedPool.value = ArchivedPoolState.Idle
        reloadArchivedPoolWhenReady()
    }

    /** Re-issue the read only where there is something to ask and someone looking. */
    private fun reloadArchivedPoolWhenReady() {
        if (!archivedVisible.value) return
        if (!archivedPool.value.needsRead) return
        if (repository.connectionState.value.status != GatewayConnectionStatus.Connected) return
        loadArchivedPool()
    }

    /**
     * Mark every loaded unread row read: one PATCH each, and an honest count
     * when some of them refuse.
     *
     * Desktop's own control does the same fan-out
     * (`app/chat/sidebar/index.tsx:1728-1734` @ `72a3277cd7`) and has no string
     * for a partial failure, so the outcome is this app's own: the number that
     * did not move, never a blanket success.
     */
    fun markAllSessionsRead() {
        // This bulk action remains useful for ordinary rows, but it must never
        // smuggle a write to the active transcript-only Bot session.
        val ids = unreadSessionsInScope().map(SessionSummary::id).filter { refuseBotChatMutation(it) == null }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            for (id in ids) {
                try {
                    repository.setSessionUnread(id, false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed++
                }
            }
            if (failed > 0) noticeLine = "$UNREAD_FAILED for $failed of ${ids.size} chats."
        }
    }

    /**
     * The loaded, in-scope, non-archived rows the sidebar is currently counting
     * as unread. Read from the cache rather than from `uiState`, which is
     * `WhileSubscribed` and answers with its initial value when nothing is
     * collecting — the count in the menu and the rows this acts on have to be
     * the same set.
     */
    private fun unreadSessionsInScope(): List<SessionSummary> =
        filterSessionsByProfileScope(
            cache.state.value.sessions.values.toList(),
            profileScope.value.key,
        ).filter { it.archived != true && it.displayStatus() == SessionStatus.Unread }

    private suspend fun reportingFailure(fallback: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            noticeLine = e.message ?: fallback
        }
    }

    private fun rehome(id: String?, applyOpenSideEffects: Boolean = true) {
        invalidateRecentImagesScope()
        activeSessionId.value?.let(composerHistoryController::reset)
        id?.let(composerHistoryController::reset)
        invalidateHistory()
        activeSessionId.value = id
        invalidateComposerRuntimeState()
        invalidatePendingDraftWrite()
        draft.value = id?.let(draftSnapshot::get).orEmpty()
        noticeLine = null
        // Leaving this chat retires its open state with it: a failure surface
        // for a session the reader has navigated away from is a stale claim.
        sessionOpen.value = SessionOpenState.Idle
        if (applyOpenSideEffects) {
            id?.let(::markRead)
            id?.let(::drainQueueIfIdle)
        }
        if (id == null) refreshComposer(null)
    }

    /** Adopt a compressed session tip without clearing a draft for the same logical session. */
    private suspend fun adoptCanonicalSession(
        requestedId: String,
        canonicalId: String,
        applyOpenSideEffects: Boolean = true,
    ) {
        if (botChatSessionId == requestedId) botChatSessionId = canonicalId
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
        if (applyOpenSideEffects) markRead(canonicalId)
        refreshComposer(canonicalId)
        if (applyOpenSideEffects) drainQueueIfIdle(canonicalId)
    }

    /** The explicit idle action; a busy action is resolved by [performComposerPrimaryAction]. */
    fun submit() = submitToGateway(queued = false)

    /**
     * One hard gate for every route which could reach a Gateway mutation while
     * this screen holds a canonical Bot Chat.
     *
     * Phase B opens exactly one door inside that chat: [allowPromptSend], the
     * person's own typed message through `prompt.submit`. That first real prompt
     * is what makes this Gateway runtime the profile's live delivery consumer
     * (there is no client-side advertisement RPC — see
     * `docs/spikes/bot-mode-gateway-contracts-2026-09-12.md`), so it stays an
     * explicit, narrow exception rather than an open composer: queue, session
     * management, approvals, attachments, voice and live-control all remain
     * refused here until a later slice ports them with their consent story.
     *
     * Composer *editing* is not gated at all — the draft, the history and the
     * undo stack are this app's own state, and the send button has to be
     * reachable for the exception above to mean anything.
     */
    private fun refuseBotChatMutation(
        targetId: String? = activeSessionId.value,
        allowPromptSend: Boolean = false,
    ): BotChatMutationException? {
        val botId = botChatSessionId ?: return null
        val activeId = activeSessionId.value
        val endpoint = botChatEndpoint
        if (
            endpoint == null ||
            endpoint.cacheGeneration != cache.endpointGeneration.value ||
            endpoint.connectionGeneration != connectionGeneration()
        ) {
            val staleBotIsActive = activeId == botId
            clearBotChatCapability(rehomeActive = staleBotIsActive)
            if (!staleBotIsActive || targetId != botId) return null
        } else if (targetId != botId) {
            return null
        } else if (allowPromptSend) {
            return null
        }
        val refusal = BotChatMutationException()
        noticeLine = refusal.message
        return refusal
    }

    /** Retiring this endpoint-local capability must never revive an old active id. */
    private fun clearBotChatCapability(rehomeActive: Boolean = false) {
        val botId = botChatSessionId
        botChatSessionId = null
        botChatEndpoint = null
        if (rehomeActive && activeSessionId.value == botId) rehome(null)
    }

    /** Attachments can use the Gateway's busy queue; the durable local queue remains text-only. */
    private fun submitToGateway(queued: Boolean) {
        if (refuseBotChatMutation(allowPromptSend = !queued) != null) return
        val sessionId = activeSessionId.value ?: return
        val prompt = draft.value.trim()
        val pending = attachments.value.filter { it.durableSessionId == sessionId }
        val ready = pending.filter { it.stage is AttachmentStage.Ready }
        // Desktop parity (isTargetSessionBusy): the selected session's own
        // authoritative status gates its send — other sessions' turns never do.
        val activeIsIdle = cache.session(sessionId)?.status == SessionStatus.Idle
        if (pending.any { it.stage is AttachmentStage.Reading || it.stage is AttachmentStage.Staging }) {
            noticeLine = "Still reading an attachment — try again in a moment."
            return
        }
        pending.firstNotNullOfOrNull { (it.stage as? AttachmentStage.ReviewRequired)?.safeMessage }?.let { warning ->
            noticeLine = warning
            return
        }
        val refusalWarning = pending.firstNotNullOfOrNull { (it.stage as? AttachmentStage.Refused)?.safeMessage }
        refusalWarning?.let { refusal ->
            noticeLine = refusal
        }
        // A locally refused chip is skipped, not fatal: the rest of the
        // payload (typed text, healthy chips) still sends. A review-required
        // chip above does hard-stop, because its mutation may have landed.
        if ((prompt.isEmpty() && ready.isEmpty()) || (!queued && !activeIsIdle) ||
            repository.connectionState.value.status != GatewayConnectionStatus.Connected
        ) return
        // Resolve every payload once: a chip whose bytes are already gone
        // fails the whole send before the draft is cleared.
        val readyPayloads = ready.map { draft ->
            val payload = attachmentPayloads[draft.occurrenceId] ?: run {
                noticeLine = "That attachment is no longer available. Remove it and attach it again."
                return
            }
            draft to payload
        }

        // Payload bytes are claimed for the wire but chips stay visible until
        // the Gateway answers, so a failed or timed-out stage keeps its
        // retryable draft instead of evaporating.
        data class Claimed(val draft: ComposerAttachmentDraft, val outgoing: OutgoingAttachment)
        val outgoing = readyPayloads.map { (draft, payload) ->
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
        val claimedIds = outgoing.mapTo(mutableSetOf()) { it.draft.occurrenceId }
        // Claim before launching the first RPC. This single-owner fence turns
        // a second Queue tap into a status notice instead of a duplicate
        // Gateway mutation.
        attachments.value = attachments.value.map { attachment ->
            if (attachment.occurrenceId in claimedIds) {
                attachment.copy(stage = AttachmentStage.Staging("Uploading"))
            } else {
                attachment
            }
        }
        // The Bot Chat's one allowed write is bound to the endpoint that minted
        // the chat. The capability check above is the last synchronous moment
        // it is known to be current, and this submit is about to move onto
        // viewModelScope, where a switch can land first; the repository refuses
        // then instead of submitting a foreign durable id to the replacement.
        val botPromptEndpoint = botChatEndpoint
            ?.takeIf { botChatSessionId == sessionId }
            ?.cacheGeneration
        clearDraftAfterDelivery(sessionId)
        noticeLine = refusalWarning
        viewModelScope.launch {
            try {
                val result = if (botPromptEndpoint != null) {
                    repository.submitAtEndpoint(sessionId, submittedPrompt, queued, botPromptEndpoint)
                } else {
                    repository.submit(
                        sessionId,
                        submittedPrompt,
                        queued = queued,
                        attachments = outgoing.map { it.outgoing },
                    )
                }
                when (result) {
                    GatewaySubmitOutcome.Accepted -> {
                        claimedIds.forEach { occurrenceId ->
                            attachmentPayloads.remove(occurrenceId)?.fill(0)
                            attachmentMimes.remove(occurrenceId)
                        }
                        recentImageAdds.value = recentImageAdds.value - claimedIds.toSet()
                        attachmentThumbnails.value = attachmentThumbnails.value - claimedIds
                        attachments.value = attachments.value.filterNot { it.occurrenceId in claimedIds }
                        composerHistoryController.reset(sessionId)
                        invalidateHistory()
                        if (composer.value.mutation is ComposerMutationUiState.Deferred) {
                            composer.value = composer.value.copy(mutation = ComposerMutationUiState.Idle)
                        }
                        val projectId = createdProjectBySession.remove(sessionId) ?: selectedProjectId.value
                        if (projectId != null) runCatching { repository.refreshProjects() }
                    }
                    GatewaySubmitOutcome.Ambiguous -> {
                        markAttachmentsReviewRequired(sessionId, submittedPrompt)
                        noticeLine = "This message may have been sent. Check this session and wait for Hermes before trying again."
                    }
                }
            } catch (cancelled: CancellationException) {
                markAttachmentsReviewRequired(sessionId, submittedPrompt)
                throw cancelled
            } catch (failure: Throwable) {
                val rpcFailure = failure as? GatewayRpcException
                val ambiguous = rpcFailure?.requestMayHaveBeenAccepted == true
                if (ambiguous) {
                    markAttachmentsReviewRequired(sessionId, submittedPrompt)
                } else {
                    markAttachmentsUnsent(sessionId)
                }
                // Stage refusals arrive as GatewayRpcException whose message is
                // already sanitized for people; anything else stays generic.
                val safe = rpcFailure?.message?.takeIf(String::isNotBlank)
                // A live-owner refusal is the one failure where the generic
                // sentence is actively wrong: the Gateway is fine, reconnecting
                // changes nothing, and trying again fails the same way for as
                // long as the other surface holds the lease. So it is also the
                // one notice that carries an action — upstream's answer is an
                // explicit escape rather than a retry (`6efe3a45c1`), and this
                // is the last place that knows *why* the send bounced, so the
                // classification and the escape are attached in the same write.
                // Every other write here goes through `noticeLine`, which means
                // the next notice of any kind takes this escape away with it.
                //
                // The escape is also fenced on the composer still being homed
                // where the refusal happened. A switch already clears the
                // notice through `rehome`, but this reply can land *after* that
                // — and an escape that appears in a session nobody asked about
                // would start a chat out of nowhere. Where the sentence itself
                // belongs in that race is the same foreground-isolation
                // question the two branches below have always had, and is not
                // this change's to answer.
                val stillHomed = activeSessionId.value == sessionId
                notice.value = when {
                    failure.isSessionNotOwned() ->
                        ChatNotice(NOT_OWNED_NOTICE, ChatNoticeAction.StartNewSession.takeIf { stillHomed })
                    ambiguous ->
                        ChatNotice(safe ?: "This message may have been sent. Check this session before trying again.")
                    else -> ChatNotice(safe ?: "The message was not sent. Reconnect to the Gateway and try again.")
                }
                if (!ambiguous) restoreSubmittedDraft(sessionId, submittedPrompt)
            }
        }
    }

    private fun restoreSubmittedDraft(sessionId: String, submittedPrompt: String) {
        // A newer edit wins everywhere. An ambiguous attachment's exact caption
        // lives on its review-required chip instead of overwriting user work.
        if (submittedPrompt.isBlank() || !draftSnapshot[sessionId].isNullOrBlank()) return
        rememberDraft(sessionId, submittedPrompt)
        if (activeSessionId.value == sessionId && draft.value.isEmpty()) draft.value = submittedPrompt
        viewModelScope.launch { persistDraft(sessionId, submittedPrompt) }
    }

    /** A failed or timed-out submit returns its drafts to Ready for retry. */
    private fun markAttachmentsUnsent(sessionId: String) =
        resettleInFlightAttachments(sessionId, AttachmentStage::Ready)

    /** A possibly accepted mutation stays visible but is never silently retryable. */
    private fun markAttachmentsReviewRequired(sessionId: String, submittedText: String = "") =
        resettleInFlightAttachments(sessionId) { bytes ->
            AttachmentStage.ReviewRequired(
                byteCount = bytes,
                safeMessage = "May have been sent — check the session, then remove and attach again if needed.",
                submittedText = submittedText,
            )
        }

    /**
     * Move this session's claimed chips out of the in-flight stages. The true
     * size comes back from the retained payload: future reservations read it,
     * and a zeroed count would let the aggregate bound be exceeded on retry.
     */
    private fun resettleInFlightAttachments(sessionId: String, settled: (Int) -> AttachmentStage) {
        attachments.value = attachments.value.map { draft ->
            if (draft.durableSessionId != sessionId) return@map draft
            when (draft.stage) {
                is AttachmentStage.Staging, is AttachmentStage.Staged ->
                    draft.copy(stage = settled(attachmentPayloads[draft.occurrenceId]?.size ?: 0))
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

    /**
     * Read one locally granted source into an in-memory draft. [expectedScope]
     * is the world the pick was launched in: a grant that outlived a reconnect,
     * an endpoint switch or a session change is refused rather than attached to
     * whichever composer happens to be on screen when it lands. The rail's own
     * tap passes its scope for the same reason.
     */
    fun addAttachmentFromGrant(
        uriString: String,
        displayName: String,
        claimedMime: String?,
        expectedScope: AttachmentPickScope? = null,
    ): String? {
        if (refuseBotChatMutation() != null) return null
        val sessionId = activeSessionId.value ?: return null
        if (expectedScope != null && expectedScope != attachmentPickScope()) {
            reportExpiredAttachmentPick()
            return null
        }
        if (attachments.value.count { it.durableSessionId == sessionId } >= AttachmentPolicy.MAX_ATTACHMENTS_PER_MESSAGE) {
            noticeLine = "That is more attachments than one message can carry."
            return null
        }
        // Reserve the per-item cap for every in-flight read so N simultaneous
        // picks cannot slip past the aggregate bound before their payloads land.
        val reservedBytes = attachments.value
            .filter { it.durableSessionId == sessionId }
            .sumOf { draft ->
                val stage = draft.stage
                when (stage) {
                    is AttachmentStage.Ready -> stage.byteCount.toLong()
                    is AttachmentStage.ReviewRequired -> stage.byteCount.toLong()
                    is AttachmentStage.Reading, is AttachmentStage.Staging ->
                        AttachmentPolicy.MAX_BYTES_PER_ATTACHMENT.toLong()
                    else -> 0L
                }
            }
        val occurrenceId = "attach-${java.util.UUID.randomUUID()}"
        val attachmentGeneration = connectionGeneration()
        attachments.value = attachments.value + ComposerAttachmentDraft(
            occurrenceId = occurrenceId,
            durableSessionId = sessionId,
            displayName = AttachmentPolicy.sanitizeDisplayName(displayName),
            kind = AttachmentKind.File,
            stage = AttachmentStage.Reading,
        )
        viewModelScope.launch {
            val read = withContext(attachmentReadDispatcher) {
                val result = AttachmentReader.read(
                    openStream = openAttachmentStream?.let { opener -> { opener(uriString) } },
                    rawDisplayName = displayName,
                    claimedMime = claimedMime,
                )
                // Preview decoding is decorative. A decoder failure must not
                // turn a valid, bounded attachment into a failed read.
                val preview = (result as? AttachmentReadResult.Read)
                    ?.takeIf { it.kind == AttachmentKind.Image }
                    ?.let { runCatching { AttachmentThumbnails.decodeComposer(it.bytes) }.getOrNull() }
                result to preview
            }
            val result = read.first
            val preview = read.second
            when (result) {
                is AttachmentReadResult.Read -> {
                    // A grant read can finish after an endpoint switch or a
                    // removal. Its bytes belong to neither later composer.
                    if (
                        attachmentGeneration != connectionGeneration() ||
                        attachments.value.none { it.occurrenceId == occurrenceId && it.durableSessionId == sessionId }
                    ) {
                        result.bytes.fill(0)
                        return@launch
                    }
                    // The optimistic reservation bounds concurrent picks; the
                    // exact check keeps a single honest pick from refusing.
                    if (reservedBytes + result.bytes.size > AttachmentPolicy.MAX_TOTAL_BYTES) {
                        result.bytes.fill(0)
                        noticeLine = "Attachments for one message can total at most 16 MB."
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
                        // The composer preview is a UI-only projection decoded
                        // from the same bounded in-memory bytes; a refusal
                        // needs no bitmap and a non-image keeps its glyph.
                        if (result.kind == AttachmentKind.Image) {
                            if (preview != null) {
                                attachmentThumbnails.value =
                                    attachmentThumbnails.value + (occurrenceId to preview)
                            }
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
        return occurrenceId
    }

    fun removeAttachment(occurrenceId: String) {
        if (refuseBotChatMutation() != null) return
        attachmentPayloads.remove(occurrenceId)?.fill(0)
        attachmentMimes.remove(occurrenceId)
        recentImageAdds.value = recentImageAdds.value - occurrenceId
        attachmentThumbnails.value = attachmentThumbnails.value - occurrenceId
        attachments.value = attachments.value.filterNot { it.occurrenceId == occurrenceId }
    }

    /** Read the bounded device rail only while this session and endpoint still own it. */
    fun requestRecentImages() {
        recentImagesLoad?.cancel()
        val scope = RecentImagesScope(connectionGeneration(), activeSessionId.value)
        recentImagesOpen = true
        val access = recentImages.value.access
        val source = recentImagesSource
        if (scope.durableSessionId == null || source == null || !access.readsLibrary) {
            recentImages.value = recentImages.value.cleared()
            return
        }
        recentImages.value = ScopedRecentImages(scope = scope, access = access, loading = true)
        recentImagesLoad = viewModelScope.launch {
            val read = withContext(attachmentReadDispatcher) { source.readRecentImages() }
            if (recentImages.value.scope != scope || currentRecentImagesScope() != scope) return@launch
            recentImages.value = ScopedRecentImages(
                scope = scope,
                access = access,
                images = read.images,
                thumbnails = read.thumbnails,
                // "This device has no images" and "this device could not be
                // read" are different statements: the rail says which one it is.
                failed = read.failed,
            )
        }
    }

    /**
     * The add sheet closed. Its rows and previews are device material held for
     * one look, so they go with the sheet; the marks that say which images this
     * message already holds stay, because the chips that carry them do.
     */
    fun closeRecentImages() {
        recentImagesOpen = false
        invalidateRecentImagesScope()
    }

    /** Add only an image that is still in this scope's published device rail. */
    fun addRecentImage(imageId: Long) {
        val scope = currentRecentImagesScope()
        val published = recentImages.value
        if (published.scope != scope) return
        val image = published.images.firstOrNull { it.id == imageId } ?: return
        val activeAttachments = attachments.value.filter { it.durableSessionId == scope.durableSessionId }
        if (activeAttachments.size >= AttachmentPolicy.MAX_ATTACHMENTS_PER_MESSAGE) return
        val activeOccurrences = activeAttachments.mapTo(mutableSetOf()) { it.occurrenceId }
        val marks = recentImageAdds.value
        if (marks.any { (occurrence, id) -> occurrence in activeOccurrences && id == imageId }) return
        val source = recentImagesSource ?: return
        val sourceUri = runCatching { source.sourceFor(image) }.getOrNull() ?: return
        val expected = AttachmentPickScope(scope.connectionGeneration, scope.durableSessionId)
        addAttachmentFromGrant(sourceUri, image.displayName, image.mimeType, expected)?.let { occurrence ->
            // The mark must follow the same scope, otherwise a reconnect could
            // paint an old selection onto a newer rail.
            if (currentRecentImagesScope() == scope) {
                recentImageAdds.value = recentImageAdds.value + (occurrence to imageId)
            }
        }
    }

    /** The Activity reports the current runtime grant; denied access revokes rail rows. */
    fun onRecentImageAccessChanged(access: RecentImageAccess) {
        if (access == RecentImageAccess.Denied) {
            recentImagesLoad?.cancel()
            recentImagesLoad = null
            recentImages.value = ScopedRecentImages(access = access)
        } else {
            recentImages.value = recentImages.value.copy(access = access)
            // Only a rail that is on screen follows the grant it was just given:
            // a resume on its own must not read the library nobody is looking at.
            if (
                recentImagesOpen &&
                access.readsLibrary &&
                activeSessionId.value != null &&
                recentImagesSource != null
            ) requestRecentImages()
        }
    }

    /** A late picker result belongs to the world that opened it, not to this one. */
    fun reportExpiredAttachmentPick() {
        noticeLine = "Those attachments arrived after the session changed. Pick them again."
    }

    /** The world a launched picker belongs to; the Activity fences its results to it. */
    fun attachmentPickScope() = AttachmentPickScope(connectionGeneration(), activeSessionId.value)

    private fun currentRecentImagesScope() = RecentImagesScope(connectionGeneration(), activeSessionId.value)

    private fun clearRecentImages() {
        recentImagesLoad?.cancel()
        recentImagesLoad = null
        recentImagesOpen = false
        recentImageAdds.value = emptyMap()
        recentImages.value = recentImages.value.cleared()
    }

    private fun invalidateRecentImagesScope() {
        recentImagesLoad?.cancel()
        recentImagesLoad = null
        recentImages.value = recentImages.value.cleared()
    }

    private fun clearAttachmentDrafts() {
        attachmentPayloads.values.forEach { it.fill(0) }
        attachmentPayloads.clear()
        attachmentMimes.clear()
        attachmentThumbnails.value = emptyMap()
        attachments.value = emptyList()
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
        // Do not even open the Android permission flow for a start we will
        // refuse. A running capture still reaches toggleDictation to stop.
        if (voice.value !is VoiceUiState.DictationRecording &&
            voice.value !is VoiceUiState.DictationTranscribing &&
            refuseBotChatMutation() != null
        ) return
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
                if (refuseBotChatMutation() != null) return
                voice.value = VoiceUiState.DictationRecording(elapsedMillis = 0L, level = 0f)
                dictationStop = onDictationCapture?.invoke(sessionId) { result ->
                    viewModelScope.launch {
                        voice.value = VoiceUiState.Idle
                        if (result is TranscriptionResult.Transcript && activeSessionId.value == sessionId) {
                            insertTextAtCursor(result.text)
                        } else if (result is TranscriptionResult.Silence && activeSessionId.value == sessionId) {
                            noticeLine = "No speech detected. Try again."
                        }
                    }
                }
                if (dictationStop == null) voice.value = VoiceUiState.Idle
            }
        }
    }

    fun toggleReadAloud(entryId: String) {
        // Read-aloud is a Gateway voice mutation (`POST api/audio/speak`), so it
        // takes the same Bot Chat gate as every other one: the composer's one
        // allowed write is the plain prompt. The gate covers the stop half too —
        // one refusal sentence, one door.
        if (refuseBotChatMutation() != null) return
        val current = readAloudState.value
        if (current is ReadAloudUiState.Speaking && current.entryId == entryId) {
            replySpeaker?.stop()
            readAloudJob?.cancel()
            readAloudState.value = ReadAloudUiState.Idle
            return
        }
        if (current is ReadAloudUiState.Preparing || current is ReadAloudUiState.Speaking) {
            return
        }

        val sessionId = activeSessionId.value ?: return
        val transcript = cache.state.value.transcripts[sessionId] ?: return
        val entry = transcript.find { it.id == entryId } as? AssistantTurn ?: return
        if (entry.markdown.isBlank()) return

        readAloudState.value = ReadAloudUiState.Preparing(entryId)
        readAloudJob = viewModelScope.launch {
            val key = VoiceSessionKey(
                connectionGeneration = connectionGeneration(),
                durableSessionId = sessionId
            )
            try {
                replySpeaker?.speak(key, entry.markdown) {
                    if (readAloudState.value is ReadAloudUiState.Preparing && readAloudOwns(entryId)) {
                        readAloudState.value = ReadAloudUiState.Speaking(entryId)
                    }
                }
                if (readAloudOwns(entryId)) {
                    readAloudState.value = ReadAloudUiState.Idle
                }
            } catch (e: VoiceTransportException) {
                if (readAloudOwns(entryId)) {
                    readAloudState.value = ReadAloudUiState.Idle
                    noticeLine = "Read aloud failed. ${e.safeMessage}"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (readAloudOwns(entryId)) {
                    readAloudState.value = ReadAloudUiState.Idle
                    noticeLine = "Read aloud failed. Check the Gateway and try again."
                }
            }
        }
    }

    private fun readAloudOwns(entryId: String): Boolean = when (val state = readAloudState.value) {
        is ReadAloudUiState.Preparing -> state.entryId == entryId
        is ReadAloudUiState.Speaking -> state.entryId == entryId
        ReadAloudUiState.Idle -> false
    }

    /** Engine hook: surface a failed transcription as state, never as silence. */
    fun reportDictationFailure(message: String) {
        if (voice.value is VoiceUiState.DictationTranscribing || voice.value is VoiceUiState.DictationRecording) {
            voice.value = VoiceUiState.Idle
        }
        noticeLine = message
    }

    /** Engine hook: publish the live capture meter for the recording state. */
    fun reportDictationLevel(level: Float) {
        val current = voice.value
        if (current is VoiceUiState.DictationRecording) {
            voice.value = current.copy(level = level.coerceIn(0f, 1f))
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
            noticeLine = "Connect to a Gateway before starting a voice conversation."
            return
        }
        if (refuseBotChatMutation() != null) return
        voice.value = VoiceUiState.Conversation(VoiceUiState.ConversationPhase.Listening, muted = false)
        voiceConversationStart?.invoke(sessionId)
    }

    fun toggleVoiceMute() {
        val current = voice.value
        if (current is VoiceUiState.Conversation) {
            if (refuseBotChatMutation() != null) return
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
            hasAttachments = state.composer.runtime.attachments.isNotEmpty(),
            canSend = state.canSend,
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

    /**
     * The composer's one Queue entry point, for both the primary tap and the
     * secondary queue action. Choosing between the Gateway's busy queue and
     * the text-only durable queue happens here and nowhere else, so the two
     * routes cannot drift apart.
     */
    fun queueDraft() {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        if (attachments.value.any { it.durableSessionId == sessionId }) {
            submitToGateway(queued = true)
        } else {
            queueTextDraft(sessionId)
        }
    }

    private fun queueTextDraft(sessionId: String) {
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
                ComposerQueueMutation.CapacityReached -> noticeLine = "The queue is full. Send, edit, or remove a queued message."
                ComposerQueueMutation.StorageUnavailable -> noticeLine = "This message could not be queued. Keep it in the editor and try again."
                else -> noticeLine = "This message could not be queued. Keep it in the editor and try again."
            }
        }
    }

    fun redirectDraftFromUi() {
        if (refuseBotChatMutation() != null) return
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
                noticeLine = if (ambiguous) {
                    "This correction may have reached Hermes. Review the queued copy before sending it."
                } else {
                    "Hermes did not accept that correction, so it was added to this session's queue."
                }
            }
            else -> noticeLine = "Hermes did not accept that correction. It remains in the editor."
        }
    }

    fun stop() {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        // Authoritative cache truth, not the possibly stale projected kind:
        // an explicit Stop must never cancel a required-input turn.
        if (cache.session(sessionId)?.status == SessionStatus.NeedsInput) {
            noticeLine = "Hermes needs a response. Answer the request above."
            return
        }
        val hadGatewayQueue = cache.session(sessionId)
            ?.composerStatus
            ?.gatewayQueuedPrompts
            .orEmpty()
            .isNotEmpty()
        viewModelScope.launch {
            composerQueueController.park(sessionId)
            try {
                when (repository.requestInterrupt(sessionId)) {
                    com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome.Interrupted -> {
                        if (hadGatewayQueue) {
                            noticeLine = "Stopped. Any queued next-turn messages were discarded with the turn."
                        }
                    }
                    com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome.NeedsInput ->
                        noticeLine = "Hermes needs a response. Your queue remains parked."
                    else -> noticeLine = "Hermes could not be stopped. Check the Gateway connection."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                noticeLine = "Hermes could not be stopped. Check the Gateway connection."
            }
        }
    }

    fun resumeQueue() {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            composerQueueController.resume(sessionId)
            drainQueueIfIdle(sessionId)
        }
    }

    fun sendNext(entryId: String) {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            val state = uiState.value
            when (state.composer.runtime.busyKind) {
                ComposerBusyKind.NeedsInput -> {
                    noticeLine = "Hermes needs a response before queued messages can continue."
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
                        noticeLine = "Hermes could not switch to that queued message. Try again."
                        return
                    }
                    if (isSessionIdle(sessionId)) {
                        when (composerQueueController.sendNextWhenIdle(sessionId, entryId, isIdle = true)) {
                            ComposerQueueDrainResult.Ambiguous,
                            ComposerQueueDrainResult.ReviewRequired,
                            -> noticeLine = "Review that queued message before sending it again."
                            ComposerQueueDrainResult.StoreUnavailable -> noticeLine = "The queue could not be updated. Try again."
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
                                    -> noticeLine = "Review that queued message before sending it again."
                                    ComposerQueueDrainResult.StoreUnavailable -> noticeLine = "The queue could not be updated. Try again."
                                    else -> Unit
                                }
                            }
                            else -> noticeLine = "Hermes could not switch to that queued message. Try again."
                        }
                        else -> noticeLine = "That queued message is no longer available."
                    }
                }
            ComposerBusyKind.Background ->
                noticeLine = "Hermes is still working. This queued message will be ready when it is idle."
            else -> when (composerQueueController.sendNextWhenIdle(sessionId, entryId, isSessionIdle(sessionId))) {
                ComposerQueueDrainResult.Ambiguous,
                ComposerQueueDrainResult.ReviewRequired,
                -> noticeLine = "Review that queued message before sending it again."
                ComposerQueueDrainResult.StoreUnavailable -> noticeLine = "The queue could not be updated. Try again."
                else -> Unit
            }
        }
    }

    fun redirectQueuedEntry(entryId: String) {
        if (refuseBotChatMutation() != null) return
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
                        noticeLine = "This correction may have reached Hermes. Review it before sending again."
                    }
                    GatewayRedirectOutcome.Rejected,
                    GatewayRedirectOutcome.Unsupported,
                    GatewayRedirectOutcome.Failed,
                    -> noticeLine = "Hermes did not accept that correction. It remains in this queue."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                noticeLine = "Hermes did not accept that correction. It remains in this queue."
            }
        }
    }

    fun deleteQueuedEntry(entryId: String) {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            composerQueueController.remove(sessionId, entryId)
            if (queueEdit.value?.entryId == entryId) finishQueueEdit(resetDraft = null)
        }
    }

    fun beginQueueEdit(entryId: String) {
        if (refuseBotChatMutation() != null) return
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
        if (refuseBotChatMutation() != null) return
        if (queueEdit.value != null) queueEditText.value = text
    }

    fun saveQueueEdit() {
        if (refuseBotChatMutation() != null) return
        val snapshot = queueEdit.value ?: return
        val text = queueEditText.value.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            when (composerQueueController.saveEdit(snapshot, text)) {
                ComposerQueueMutation.Applied -> finishQueueEdit(resetDraft = null)
                else -> noticeLine = "That queued message could not be saved. Try again."
            }
        }
    }

    fun cancelQueueEdit() {
        if (refuseBotChatMutation() != null) return
        val snapshot = queueEdit.value ?: return
        viewModelScope.launch {
            finishQueueEdit(resetDraft = composerQueueController.cancelEdit(snapshot))
        }
    }

    fun markQueuedEntryReadyAfterReview(entryId: String) {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            if (composerQueueController.markReadyAfterReview(sessionId, entryId) == ComposerQueueMutation.Applied) {
                noticeLine = "That queued message is ready when you choose Send next."
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
        if (refuseBotChatMutation() != null) return
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
        // A Bot Chat never auto-drains a stored queue. Its first prompt is the
        // one the person types here — a drain on open would arm live delivery
        // from nothing but an open, which is the exact consequence Phase B
        // keeps honest (`docs/parity/bot-chat.md`).
        if (sessionId == botChatSessionId) return
        if (!queueScopeReady.value || !isSessionIdle(sessionId)) return
        if (!scheduledQueueDrains.add(sessionId)) return
        viewModelScope.launch {
            try {
                // Recheck inside the scheduled owner. A settle/reconnect pair
                // can both observe idle before either coroutine runs.
                if (!queueScopeReady.value || !isSessionIdle(sessionId)) return@launch
                when (composerQueueController.drainIfIdle(sessionId, isIdle = true)) {
                    ComposerQueueDrainResult.StoreUnavailable -> if (activeSessionId.value == sessionId) {
                        noticeLine = "The queue could not be updated. Try again."
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
                    noticeLine = "Goal status could not be refreshed. Try again."
                }
                else -> Unit
            }
        }
    }

    fun refreshCodingContext() {
        activeSessionId.value?.let(::refreshCodingContext)
    }

    private fun refreshCodingContext(sessionId: String) {
        val session = cache.session(sessionId)
        val path = session?.worktreePath?.takeIf(String::isNotBlank)
        if (path == null) {
            codingLoad?.cancel()
            composer.value = composer.value.copy(
                codingContext = CodingContext.Unavailable,
                codingReview = CodingReviewUiState.Closed,
            )
            return
        }
        val generation = ++codingGeneration
        codingLoad?.cancel()
        val current = composer.value.codingContext
        composer.value = composer.value.copy(
            // Desktop refreshes an already-painted rail silently. Keep verified
            // truth visible until the replacement request resolves.
            codingContext = current.takeIf {
                it is CodingContext.Available && it.worktreePath == path
            } ?: CodingContext.Loading(path, session.gitBranch),
            codingReview = CodingReviewUiState.Closed,
        )
        codingLoad = viewModelScope.launch {
            val result = try {
                codingContextProvider.contextFor(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                CodingContext.Unavailable
            }
            val isCurrent = {
                generation == codingGeneration &&
                    activeSessionId.value == sessionId &&
                    cache.session(sessionId)?.worktreePath == path
            }
            if (!isCurrent()) return@launch
            val previous = composer.value.codingContext as? CodingContext.Available
            val paintedResult = if (
                result is CodingContext.Available &&
                previous?.worktreePath == result.worktreePath && previous.branch == result.branch
            ) {
                result.copy(pullRequest = previous.pullRequest)
            } else {
                result
            }
            composer.value = composer.value.copy(codingContext = paintedResult)

            val available = paintedResult as? CodingContext.Available ?: return@launch
            if (available.detached) return@launch
            val pullRequest = try {
                codingContextProvider.pullRequestFor(path, available.branch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (!isCurrent()) return@launch
            val painted = composer.value.codingContext as? CodingContext.Available ?: return@launch
            if (painted.worktreePath == path && painted.branch == available.branch) {
                composer.value = composer.value.copy(codingContext = painted.copy(pullRequest = pullRequest))
            }
        }
    }

    fun openCodingReview() {
        val context = composer.value.codingContext as? CodingContext.Available ?: return
        val sessionId = activeSessionId.value ?: return
        val generation = ++codingReviewGeneration
        codingReviewLoad?.cancel()
        composer.value = composer.value.copy(codingReview = CodingReviewUiState.Loading(context.worktreePath))
        codingReviewLoad = viewModelScope.launch {
            val result = try {
                codingContextProvider.reviewFor(context.worktreePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                CodingReviewResult.Unavailable
            }
            if (
                generation != codingReviewGeneration ||
                activeSessionId.value != sessionId ||
                (composer.value.codingContext as? CodingContext.Available)?.worktreePath != context.worktreePath
            ) {
                return@launch
            }
            composer.value = composer.value.copy(
                codingReview = when (result) {
                    is CodingReviewResult.Available -> CodingReviewUiState.Ready(context.worktreePath, result.files)
                    CodingReviewResult.Unavailable -> CodingReviewUiState.Failed(context.worktreePath)
                },
            )
        }
    }

    fun dismissCodingReview() {
        codingReviewGeneration += 1
        codingReviewLoad?.cancel()
        composer.value = composer.value.copy(codingReview = CodingReviewUiState.Closed)
    }

    fun refreshProcesses() {
        activeSessionId.value?.let { refreshProcesses(it, showFailure = true) }
    }

    /**
     * The bounded background-process ladder owns this request. Keeping the call
     * suspendable lets Compose cancel an in-flight read when its session or
     * foreground lifecycle leaves instead of orphaning work in viewModelScope.
     */
    suspend fun reconcileProcesses() {
        val sessionId = activeSessionId.value ?: return
        repository.listProcesses(sessionId)
    }

    /**
     * `Show earlier messages`: fetch the page before the one on screen and
     * prepend it to the session that asked.
     *
     * Addressed to the session the press came from, never to whatever is active
     * when the page lands — the repository writes the page into that
     * transcript, so a switch mid-fetch cannot paint it into the wrong one. A
     * failure is silent by design: the control simply stays, and the next press
     * retries (`transcript-backfill.ts:143-148` @ `72a3277cd7`).
     */
    fun showEarlierMessages() {
        val activeId = activeSessionId.value ?: return
        val durableId = cache.state.value.rehomes[activeId] ?: activeId
        viewModelScope.launch { runCatching { repository.loadEarlierMessages(durableId) } }
    }

    private fun refreshProcesses(sessionId: String, showFailure: Boolean) {
        viewModelScope.launch {
            when (repository.listProcesses(sessionId)) {
                GatewayProcessListOutcome.Failed -> if (showFailure && activeSessionId.value == sessionId) {
                    noticeLine = "Background work could not be refreshed. Try again."
                }
                else -> Unit
            }
        }
    }

    fun killProcess(processId: String) {
        if (refuseBotChatMutation() != null) return
        val sessionId = activeSessionId.value ?: return
        viewModelScope.launch {
            when (repository.killProcess(sessionId, processId)) {
                GatewayProcessKillOutcome.Killed -> refreshProcesses(sessionId, showFailure = false)
                GatewayProcessKillOutcome.Rejected,
                GatewayProcessKillOutcome.Failed,
                GatewayProcessKillOutcome.Ambiguous,
                -> if (activeSessionId.value == sessionId) {
                    noticeLine = "Background work could not be stopped. Try again."
                }
                GatewayProcessKillOutcome.Unsupported -> if (activeSessionId.value == sessionId) {
                    noticeLine = "This Gateway cannot stop background work."
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
        gatewayLogsController.dismiss()
        replySpeaker?.stop()
        // Attachment bytes are memory-only by contract: nothing survives the
        // ViewModel, and process recreation shows "unavailable" rather than a
        // stale grant.
        clearRecentImages()
        clearAttachmentDrafts()
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
        val generation = ++sessionOpenGeneration
        // The open waits on the Gateway and adoption waits on the draft store,
        // so the reader can leave this chat between either await and the work
        // it licenses. Only the newest request for the session still on screen
        // may adopt or repaint: a stale completion that ran on would republish
        // `Loading` over the live chat's composer, after cancelling the only
        // read that could have finished it. The id is re-read each time, since
        // compression can move an adopted session to its canonical key.
        fun stillOwns(target: String): Boolean =
            sessionOpenGeneration == generation && activeSessionId.value == target
        // Publishing `Opening` is what lets the pane distinguish "still on the
        // wire" from "failed" without a timer; only the newest owner may set it.
        if (sessionOpenGeneration == generation && activeSessionId.value == id) {
            sessionOpen.value = SessionOpenState.Opening(id)
        }
        try {
            val canonicalId = repository.openSession(id)
            if (!stillOwns(id)) return
            adoptCanonicalSession(id, canonicalId)
            // This fence is the half adoption cannot cover: for an equal id it
            // returns before any of its own guards run.
            if (!stillOwns(canonicalId)) return
            // The open succeeded; the pane's surface goes back to Idle before the
            // composer read starts, so a slow catalog cannot look like a failure.
            if (sessionOpenGeneration == generation && activeSessionId.value == canonicalId) {
                sessionOpen.value = SessionOpenState.Idle
            }
            refreshComposer(canonicalId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!stillOwns(id)) return
            // The internal cause is captured, not discarded. It is redacted and
            // bounded (never a transcript, never a secret) and travels with the
            // failure so a report can name what actually happened; the reader
            // still gets the one product sentence.
            val detail = safeSessionOpenFailureDetail(failure)
            sessionOpen.value = SessionOpenState.Failed(
                sessionId = id,
                message = SESSION_OPEN_FAILED_COPY,
                detail = detail,
            )
        }
    }

    /**
     * Re-run the open for the session the pane says failed.
     *
     * Re-selecting the id already homed is exactly the path that re-opens it
     * without rehoming, so the draft, attachments and composer scope stay where
     * they are. A no-op when nothing failed or when the reader has since moved
     * to another session.
     */
    fun retrySessionOpen() {
        val failed = sessionOpen.value as? SessionOpenState.Failed ?: return
        val target = failed.sessionId
        if (target != activeSessionId.value) return
        sessionOpen.value = SessionOpenState.Idle
        selectSession(target)
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
        val before = maskComposerReferences(text).substring(0, start)
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
        // The cache now holds rows from several profiles at once, so a row
        // that names no profile is a launch-profile row — never a reason to
        // borrow a sibling's profile and emit a reference to the wrong one.
        val sessions = cache.state.value.sessions.values
            .sortedByDescending(SessionSummary::lastActiveAtMillis)
            .asSequence()
            .map { session ->
                val profile = session.remoteProfile?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_PROFILE
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

    /**
     * Opening a session clears both unread sources, in Desktop's order: the
     * transient finished-turn dot here and now, and the durable watermark
     * best-effort behind it (`store/session-unread-remote.ts:65-79` @
     * `72a3277cd7` — "a failed PATCH is healed by the next honest refresh", and
     * it is not worth a notice for something the reader did not ask for).
     */
    private fun markRead(id: String) {
        val session = cache.session(id) ?: return
        if (session.status == SessionStatus.Unread) cache.upsertSession(session.copy(status = SessionStatus.Idle))
        if (session.unread == true) {
            viewModelScope.launch { runCatching { repository.setSessionUnread(id, false) } }
        }
    }

    /** The sidebar's saved view choices plus the roster the rail paints from. */
    private data class SidebarViewState(
        val grouping: SidebarGrouping = SidebarGrouping.Date,
        val profileScope: ProfileScope = ProfileScope(),
        val roster: ProfileRosterState = ProfileRosterState(),
        /** Whether the list is showing the archived set instead of the live one. */
        val archivedVisible: Boolean = false,
        /** What that set's own read has said, if anything. */
        val archivedPool: ArchivedPoolState = ArchivedPoolState.Idle,
    )

    private data class NavigationState(
        val connection: GatewayConnectionState,
        val notice: ChatNotice?,
        val projectId: String?,
        val loadingProjectId: String?,
        val sidebarView: SidebarViewState,
        /** The active session's open state; see [SessionOpenState]. */
        val sessionOpen: SessionOpenState = SessionOpenState.Idle,
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
        /** Occurrence-keyed preview bitmaps for image drafts; UI-only. */
        val attachmentThumbnails: Map<String, ImageBitmap> = emptyMap(),
        /** The device rail is read separately from attachment payload bytes. */
        val recentImages: ScopedRecentImages = ScopedRecentImages(),
        /** Occurrence id to device image id for the rail marks the chips carry. */
        val recentImageAdds: Map<String, Long> = emptyMap(),
    )

    private data class AttachmentBundle(
        val attachments: List<ComposerAttachmentDraft>,
        val thumbnails: Map<String, ImageBitmap>,
        val recentImages: ScopedRecentImages,
        val recentImageAdds: Map<String, Long>,
    )

    private data class RecentImagesScope(
        val connectionGeneration: Long,
        val durableSessionId: String?,
    )

    /**
     * The published device rail together with the scope that owns it. A null
     * scope means nothing is published: the rows, previews and marks were
     * cleared by a close, a navigation or a reconnect.
     */
    private data class ScopedRecentImages(
        val scope: RecentImagesScope? = null,
        val access: RecentImageAccess = RecentImageAccess.Unknown,
        val loading: Boolean = false,
        val images: List<RecentImage> = emptyList(),
        val thumbnails: Map<Long, ImageBitmap> = emptyMap(),
        /** The read reached the device but the library refused it. */
        val failed: Boolean = false,
    ) {
        /** The same grant with everything the library supplied taken away. */
        fun cleared() = ScopedRecentImages(access = access)
    }

    /**
     * What the composer may show for the scope it is displaying. A read that
     * landed for another session, endpoint or sheet opening contributes its
     * grant but none of its rows, and the attachment cap is judged against the
     * drafts the displayed session actually holds.
     */
    private fun ScopedRecentImages.forDisplayedScope(
        displayedScope: RecentImagesScope,
        drafts: List<ComposerAttachmentDraft>,
        marks: Map<String, Long>,
    ): RecentImagesUiState {
        val activeDrafts = drafts.filter { it.durableSessionId == displayedScope.durableSessionId }
        val full = activeDrafts.size >= AttachmentPolicy.MAX_ATTACHMENTS_PER_MESSAGE
        if (scope != displayedScope) {
            return RecentImagesUiState(access = access, full = full)
        }
        val present = activeDrafts.mapTo(mutableSetOf()) { it.occurrenceId }
        return RecentImagesUiState(
            access = access,
            loading = loading,
            images = images,
            thumbnails = thumbnails,
            // A mark outlives the chip that carried it only by a frame; the
            // drafts on screen are what decide whether it still counts.
            addedIds = marks.filterKeys(present::contains).values.toSet(),
            failed = failed,
            full = full,
        )
    }

    /**
     * The only four things that make a breakdown worth re-reading. Everything
     * else `cache.state` publishes collapses in `distinctUntilChanged`.
     */
    private data class ContextFetchSignal(
        val sessionId: String?,
        val busy: Boolean,
        val connected: Boolean,
        val runtimeReady: Boolean,
    )

    private data class ContextMeterBundle(
        val breakdown: ContextBreakdown? = null,
        val loading: Boolean = false,
    )

    private data class ChromeBundle(
        val approval: ApprovalModeState,
        val visibleModels: Set<String>?,
    )

    private data class ComposerBundle(
        val composer: ComposerUiState,
        val voice: VoiceUiState,
        val navigation: NavigationState,
        val contextMeter: ContextMeterBundle,
        val chrome: ChromeBundle,
    )

    /**
     * Connection-owned projections: the image loader, the transcript window, and
     * whether a live-pool page is on the wire. The third rides along because
     * `combine` takes a fixed arity and this assembly is already at it.
     */
    private data class TranscriptWindowBundle(
        val imageLoader: GatewayImageLoader?,
        val sessionsWithEarlierMessages: Set<String>,
        val sessionPaging: SessionListPaging,
    )

    /**
     * The three facts about search that the row builder reads together: what
     * was typed, whether the backend is still answering it, and what it said.
     * Bundled because `combine` takes a fixed arity and this state moves as one.
     */
    private data class SearchStateBundle(
        val query: String,
        val pending: Boolean,
        val results: List<SessionSummary>?,
    )

    /**
     * Which conversations a search answer would be *about*.
     *
     * Kept apart from the query because it is the half that invalidates an
     * answer already on screen: a stub carries a durable id, and the next
     * backend can recycle one (`SessionCache.resetForEndpointSwitch`), so a hit
     * from the previous Gateway is not a narrower answer here — it is a row
     * that may open a different conversation entirely.
     */
    private data class SessionSearchScope(val profile: String?, val endpoint: Long)

    /**
     * Everything the debounced search effect re-keys on: what was typed, whose
     * conversations it is about, and whether the rail is showing sessions at
     * all rather than the project overview, where the same field filters
     * projects and no session row is drawn.
     */
    private data class SessionSearchKey(
        val query: String,
        val scope: SessionSearchScope,
        val sessionsView: Boolean,
    )

    companion object {
        /**
         * Desktop's own sidebar-search debounce, `setTimeout(…, 200)`
         * (`apps/desktop/src/app/chat/sidebar/index.tsx:671` @ `72a3277cd7`).
         * There is no minimum query length: one character is a legitimate
         * search, and the wait is what keeps it from being a request per
         * keystroke.
         */
        internal const val SESSION_SEARCH_DEBOUNCE_MILLIS = 200L
        private const val DRAFT_DEBOUNCE_MILLIS = 400L
        private const val COMPLETION_DEBOUNCE_MILLIS = 120L
        /** A slash directive may include arguments; @ and : stay one token. */
        private val SLASH_COMPLETION = Regex("(?:^|[\\s\\uFFFC])(/[^\\n\\uFFFC]*)$")
        private val AT_COMPLETION = Regex("(?:^|[\\s\\uFFFC])(@[^\\s\\uFFFC]*)$")
        private val EMOJI_COMPLETION = Regex("(?:^|[\\s\\uFFFC])(:[^\\s:\\uFFFC]*)$")
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
            codingContextProvider: CodingContextProvider,
            sidebarViewStore: SidebarViewStore,
            profileScopeStore: ProfileScopeStore,
            profileRepository: ProfileRepository,
            composerControlsStore: ComposerControlsStore,
            draftStore: SessionDraftStore,
            draftScope: CoroutineScope,
            composerQueueController: ComposerQueueController = transientQueueController(),
            switchComposerQueueScope: suspend (ComposerQueueScope) -> Unit = {},
            replySpeaker: ReplySpeaker? = null,
            connectionGeneration: () -> Long = { 0L },
            gatewayHttp: () -> com.hermesagent.mobile.data.gateway.GatewayHttp? = { null },
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
                        codingContextProvider = codingContextProvider,
                        sidebarViewStore = sidebarViewStore,
                        profileScopeStore = profileScopeStore,
                        profileRepository = profileRepository,
                        composerControlsStore = composerControlsStore,
                        draftStore = draftStore,
                        applicationDraftScope = draftScope,
                        composerQueueController = composerQueueController,
                        switchComposerQueueScope = switchComposerQueueScope,
                        replySpeaker = replySpeaker,
                        connectionGeneration = connectionGeneration,
                        gatewayHttp = gatewayHttp,
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

/** `Could not update unread state` (`apps/desktop/src/i18n/en.ts:2501` @ `72a3277cd7`). */
private const val UNREAD_FAILED = "Could not update unread state"

/** What the Archived view says when its own read did not come back. */
private const val ARCHIVED_LOAD_FAILED = "Could not load archived chats. Check the Gateway and try again."

/**
 * What a shortlist that never reached disk says. Desktop's own store writes to
 * `localStorage` and reports nothing (`store/model-visibility.ts:91-99`); this
 * app says which action did not stick and what to do next.
 */
private const val MODEL_VISIBILITY_NOT_SAVED = "That model list could not be saved. Try again."

/** A rejected session-menu or live-control request must not look like a successful no-op to its suspend caller. */
private class BotChatMutationException : IllegalStateException(BOT_CHAT_MUTATION_NOTICE)

/**
 * What a refused action says inside a canonical Bot Chat.
 *
 * Phase B keeps the composer's send path as the only open door in a Bot Chat
 * (see [ChatViewModel.refuseBotChatMutation]); Desktop permits the rest, so
 * this sentence is a mobile adaptation and is ledgered as one in
 * `docs/parity/bot-chat.md`. It states the limitation and claims nothing about
 * delivery, which only starts when the person actually sends.
 */
private const val BOT_CHAT_MUTATION_NOTICE = "Only messages can be sent from a Bot Chat on mobile."

/**
 * What a live-owner refusal says.
 *
 * Deliberately not "reconnect and try again", which is what this used to say
 * and is wrong twice over: the Gateway is answering, and retrying fails
 * identically for as long as the other surface holds the lease. The line names
 * the escape because on this surface the line *is* the escape — the status
 * action beside it has no label of its own to read.
 *
 * Internal rather than private so the screen's own test and the ViewModel's
 * can name the same sentence instead of copying it; nothing decides anything
 * by comparing against it (see [ChatNotice]).
 */
internal const val NOT_OWNED_NOTICE = "Another Hermes has this session open. Start a new session to send here."
/**
 * A bounded, redacted summary of why an open failed — safe to show, safe to
 * attach to a report, and never the transcript.
 *
 * This exists because the old handler discarded the cause entirely: the
 * observable failure was the product sentence and nothing else, so a real
 * defect (a lock the UI thread was waiting on, a malformed row, a timeout)
 * looked exactly like a Gateway that was down. The class name and message of an
 * exception are not user content, and both are pushed through the same
 * redaction the rest of the app uses before they are bounded.
 */
internal fun safeSessionOpenFailureDetail(failure: Throwable): String {
    val type = failure::class.java.simpleName.takeIf(String::isNotBlank) ?: "Throwable"
    val raw = failure.message.orEmpty()
    val message = safeGatewayStatusText(raw).take(MAX_SESSION_OPEN_DETAIL)
    return if (message.isBlank()) type else "$type: $message"
}

/** Long enough to name a cause, short enough for one line of a surface. */
private const val MAX_SESSION_OPEN_DETAIL = 240
