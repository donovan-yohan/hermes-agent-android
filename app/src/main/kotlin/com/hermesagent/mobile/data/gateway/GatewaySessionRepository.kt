package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.CompletionResult
import com.hermesagent.mobile.data.composer.ComposerControlState
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ControlMutationResult
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.ModelOption
import com.hermesagent.mobile.data.composer.ModelProvider
import com.hermesagent.mobile.data.composer.NewSessionComposerOverrides
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.composer.SessionComposerControls
import com.hermesagent.mobile.data.attachments.ImageRefLines
import com.hermesagent.mobile.data.attachments.OutgoingAttachment
import com.hermesagent.mobile.data.attachments.StagedAttachmentReference
import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ComposerBackgroundProcess
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerGatewayQueuedPrompt
import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerGoalStatus
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.data.session.ContextBreakdown
import com.hermesagent.mobile.data.session.ContextUsageCategory
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.SessionUsage
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.TurnTermination
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.graftRefreshedTailOntoBackfill
import com.hermesagent.mobile.data.session.mergeOlderTranscriptPage
import com.hermesagent.mobile.data.session.preservingRowIdOf
import com.hermesagent.mobile.data.session.retainingGatewayQueue
import com.hermesagent.mobile.data.session.transcriptPageState
import com.hermesagent.mobile.data.ssh.redact
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlin.coroutines.CoroutineContext

/**
 * How this connection's session RPCs are scoped to a Hermes profile.
 *
 * `session.create` (`tui_gateway/methods_session.py:42`), `session.list`
 * (`:163`) and `session.resume` (`:324`) all accept a `profile` parameter at
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`; a blank or absent one resolves to
 * the profile the Gateway launched with (`tui_gateway/server.py:1599-1613`).
 *
 * @param activeProfile what a new chat is created in; null keeps the Gateway's own.
 * @param listProfiles which `profile` values one refresh must cover. One entry
 *   for a single-profile scope, and the launch profile plus every named profile
 *   for the unified view — there is no server-side union on this transport.
 */
data class ProfileRouting(
    val activeProfile: String? = null,
    val listProfiles: List<String?> = listOf(null),
)

interface GatewaySessionRepository {
    val connectionState: StateFlow<GatewayConnectionState>
    /** Connection-owned loader for attached-image bytes; null while disconnected. */
    val imageLoader: StateFlow<GatewayImageLoader?> get() = NO_IMAGE_LOADER
    val sessionRehomes: Flow<SessionRehome> get() = emptyFlow()
    val composerControls: Flow<SessionComposerControls> get() = emptyFlow()
    /** Live required-action requests, keyed by generation/runtime/request/kind. */
    val pendingInputs: StateFlow<Map<PendingInputKey, PendingInputRequest>>
        get() = error("Pending inputs are not implemented by this repository.")

    /**
     * Terminal turn frames, for app-scoped followers that need to tell a
     * finished turn from a failed one. Both settle the session to
     * [SessionStatus.Idle], so the cache cannot answer that on its own.
     */
    val turnOutcomes: Flow<GatewayTurnOutcome> get() = emptyFlow()

    /** Active turns submitted or live on this client, keyed by durable session ID. */
    val activeTurns: StateFlow<Set<String>> get() = NO_ACTIVE_TURNS

    /**
     * How often this host asks before it acts, for the profile the app is
     * scoped to. [ApprovalModeState.mode] is null until [refreshApprovalMode]
     * has been answered; a repository with no approval leg stays null forever,
     * which is what hides the control rather than showing a guess.
     */
    val approvalMode: StateFlow<ApprovalModeState> get() = noApprovalMode()

    /**
     * Read `config.get {key: "approvals.mode"}` for the active profile. Call it
     * on connect and whenever the profile scope moves; failure is silent and
     * leaves the last known answer, because this is a status read.
     */
    suspend fun refreshApprovalMode() = Unit

    /** Write `config.set {key: "approvals.mode"}`; rolls back on refusal. */
    suspend fun setApprovalMode(mode: ApprovalMode): ApprovalModeOutcome =
        ApprovalModeOutcome.Rejected(APPROVAL_MODE_REJECTED)

    suspend fun respondToPendingInput(key: PendingInputKey, action: PendingInputAction): PendingInputResponse =
        error("Pending input responses are not implemented by this repository.")
    suspend fun refreshSessions()

    /** How far the session list has read, and whether there is more. */
    val sessionPaging: StateFlow<SessionListPaging> get() = NO_SESSION_PAGING

    /**
     * Read the next page of the session list, layering it over what is already
     * cached. A no-op when nothing more is known to exist — including on a
     * Gateway whose list contract has no offset at all.
     */
    suspend fun loadMoreSessions() = Unit
    /**
     * The profile scope new work and the session list belong to. UI-only
     * authority pushed down, never Gateway truth; the repository only turns it
     * into the `profile` parameter the session RPCs accept.
     */
    fun setProfileRouting(routing: ProfileRouting) = Unit
    suspend fun refreshProjects() = Unit
    suspend fun openProject(projectId: String) = Unit
    suspend fun createProject(name: String, folderPath: String): ProjectCreateOutcome =
        error("Project creation is not implemented by this repository.")
    suspend fun openSession(durableId: String): String
    suspend fun createSession(workspacePath: String? = null): String
    suspend fun createSession(
        workspacePath: String?,
        overrides: NewSessionComposerOverrides?,
    ): String = createSession(workspacePath)
    suspend fun loadModelOptions(durableId: String?): ModelCatalog =
        error("Model options are not implemented by this repository.")
    suspend fun loadComposerControls(durableId: String?): ModelControlsSnapshot =
        error("Composer controls are not implemented by this repository.")
    /**
     * Read the context breakdown for [durableId] via `session.context_breakdown`.
     * Fail-closed; returns null on unconfigured or unreachable backends, and the
     * last breakdown this session had when one call fails.
     *
     * A passive read: it never opens or activates a session to answer. Ask
     * [hasLiveRuntime] first — Desktop's own caller only ever holds a session
     * that is already active (`use-context-breakdown.ts:41` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     */
    suspend fun loadContextBreakdown(durableId: String): ContextBreakdown? = null

    /**
     * Whether [durableId] is already bound to a Gateway runtime.
     *
     * The breakdown read is an observer, so it must not be the thing that opens
     * a session: `openSession` holds the navigation mutex, and a read that
     * queued behind it would starve real navigation. Implementations that have
     * no runtime concept answer `true`.
     */
    fun hasLiveRuntime(durableId: String): Boolean = true

    suspend fun loadComposerState(durableId: String?): ComposerControlState {
        val catalog = loadModelOptions(durableId)
        val controls = loadComposerControls(durableId)
        return ComposerControlState(
            catalog = catalog,
            controls = controls.copy(selection = controls.selection ?: catalog.effectiveSelection),
        )
    }
    suspend fun setLiveModel(
        durableId: String,
        selection: ComposerModelSelection,
    ): ControlMutationResult = error("Live model controls are not implemented by this repository.")
    suspend fun setLiveReasoning(
        durableId: String,
        effort: ReasoningEffort,
    ): ControlMutationResult = error("Live reasoning controls are not implemented by this repository.")
    suspend fun setLiveFast(durableId: String, mode: FastMode): ControlMutationResult =
        error("Live fast controls are not implemented by this repository.")
    suspend fun completeSlash(query: String): CompletionResult =
        error("Slash completion is not implemented by this repository.")
    suspend fun completePath(durableId: String?, query: String, cwd: String): CompletionResult =
        error("Path completion is not implemented by this repository.")
    suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome
    /** A queue drain opts into the Gateway's non-interrupting busy behavior. */
    suspend fun submit(durableId: String, text: String, queued: Boolean): GatewaySubmitOutcome =
        submit(durableId, text)
    /**
     * Stage-then-submit transaction: every attachment must stage successfully
     * before prompt.submit; one failed or ambiguous stage refuses the whole
     * submit and returns which items need retry. The live repository overrides
     * this; the default keeps existing fakes compiling by dropping attachments
     * (fakes that care override it to capture).
     */
    suspend fun submit(
        durableId: String,
        text: String,
        queued: Boolean = false,
        attachments: List<OutgoingAttachment> = emptyList(),
    ): GatewaySubmitOutcome = submit(durableId, text, queued)
    suspend fun interrupt(durableId: String)
    /** New callers can distinguish an interruption from a protected input request. */
    suspend fun requestInterrupt(durableId: String): GatewayInterruptOutcome {
        interrupt(durableId)
        return GatewayInterruptOutcome.Interrupted
    }
    suspend fun redirect(durableId: String, text: String): GatewayRedirectOutcome =
        GatewayRedirectOutcome.Unsupported
    suspend fun steer(durableId: String, text: String): GatewaySteerOutcome = GatewaySteerOutcome.Unsupported
    suspend fun listProcesses(durableId: String): GatewayProcessListOutcome = GatewayProcessListOutcome.Unsupported
    suspend fun killProcess(durableId: String, processId: String): GatewayProcessKillOutcome =
        GatewayProcessKillOutcome.Unsupported
    suspend fun goalStatus(durableId: String): GatewayGoalStatusOutcome = GatewayGoalStatusOutcome.Unsupported
    /**
     * Durable session ids whose transcript is known to have older rows nobody
     * has asked for yet — exactly what the `Show earlier messages` control
     * renders on, and what it stops rendering on once a session is exhausted.
     */
    val sessionsWithEarlierMessages: StateFlow<Set<String>> get() = NO_EARLIER_MESSAGES

    /**
     * Fetch the page immediately older than the rows already held for
     * [durableId] and prepend it.
     *
     * A no-op when nothing older is known to exist, when a page for that
     * session is already in flight, or on a Gateway with no paged transcript
     * route. A failure is not raised: the control stays available and the next
     * press retries (`transcript-backfill.ts:143-148` @ `3ca096de`).
     */
    suspend fun loadEarlierMessages(durableId: String) = Unit

    suspend fun renameSession(durableId: String, title: String): String =
        error("Session renaming is not implemented by this repository.")
    suspend fun deleteSession(durableId: String): Unit =
        error("Session deletion is not implemented by this repository.")

    /**
     * Pin or unpin one conversation through `PATCH /api/sessions/{id}`
     * (`hermes_cli/web_routers/sessions.py:829-830` @ `3ca096de`). Optimistic:
     * the row is painted first and repainted if the write is refused.
     */
    suspend fun setSessionPinned(durableId: String, pinned: Boolean): Unit =
        error("Session pinning is not implemented by this repository.")

    /**
     * Archive or restore one conversation (`sessions.py:825-826` @ `3ca096de`).
     * Archiving evicts the row through the cache's explicit tombstone and puts
     * it back if the write is refused; restoring clears the flag in place.
     */
    suspend fun setSessionArchived(durableId: String, archived: Boolean): Unit =
        error("Session archiving is not implemented by this repository.")

    /**
     * Arm or retire the backend read watermark (`sessions.py:831-832`, which
     * writes `set_session_read(read = !unread)` @ `3ca096de`). Marking read
     * also clears this client's transient finished-turn dot, in Desktop's order
     * (`app/chat/sidebar/session-actions-menu.tsx:316-332` @ `3ca096de`), so a
     * later list page cannot repaint what was just dismissed.
     */
    suspend fun setSessionUnread(durableId: String, unread: Boolean): Unit =
        error("Session read state is not implemented by this repository.")

    /**
     * Read the archived set as its own pool.
     *
     * Archived rows are excluded from the session list itself, so the Archived
     * view has to fetch its own set rather than filter the live one — Desktop's
     * `loadArchivedSessions` (`apps/desktop/src/store/sidebar-archive.ts:7-30` @
     * `3ca096de`: "Archived rows are excluded from the sessions query, so the
     * Archived view has to fetch its own set. Capped: it's a lookup surface, not
     * a feed."). One `archived=only` request per profile leg, capped, layered
     * into the cache and never mixed into the live page's window.
     */
    suspend fun loadArchivedSessions() = Unit
}

sealed interface GatewaySubmitOutcome {
    data object Accepted : GatewaySubmitOutcome
    data object Ambiguous : GatewaySubmitOutcome
}

sealed interface GatewayRedirectOutcome {
    data object Redirected : GatewayRedirectOutcome
    data object QueuedByGateway : GatewayRedirectOutcome
    data object Rejected : GatewayRedirectOutcome
    data object Unsupported : GatewayRedirectOutcome
    /** The frame may have reached Hermes, so it must not be retried automatically. */
    data object Ambiguous : GatewayRedirectOutcome
    data object Failed : GatewayRedirectOutcome
}

sealed interface GatewaySteerOutcome {
    data object QueuedByGateway : GatewaySteerOutcome
    data object Rejected : GatewaySteerOutcome
    data object Unsupported : GatewaySteerOutcome
    data object Ambiguous : GatewaySteerOutcome
    data object Failed : GatewaySteerOutcome
}

sealed interface GatewayInterruptOutcome {
    data object Interrupted : GatewayInterruptOutcome
    /** A pending approval/sudo/secret response must remain answerable. */
    data object NeedsInput : GatewayInterruptOutcome
    data object NotActive : GatewayInterruptOutcome
    data object Rejected : GatewayInterruptOutcome
    data object Ambiguous : GatewayInterruptOutcome
    data object Failed : GatewayInterruptOutcome
}

sealed interface GatewayProcessListOutcome {
    data class Available(val processes: List<ComposerBackgroundProcess>) : GatewayProcessListOutcome
    data object Unsupported : GatewayProcessListOutcome
    data object Failed : GatewayProcessListOutcome
}

sealed interface GatewayProcessKillOutcome {
    data object Killed : GatewayProcessKillOutcome
    data object Rejected : GatewayProcessKillOutcome
    data object Unsupported : GatewayProcessKillOutcome
    data object Ambiguous : GatewayProcessKillOutcome
    data object Failed : GatewayProcessKillOutcome
}

sealed interface GatewayGoalStatusOutcome {
    data class Available(val goal: ComposerGoalStatus) : GatewayGoalStatusOutcome
    data object Unsupported : GatewayGoalStatusOutcome
    data object Failed : GatewayGoalStatusOutcome
}

data class ProjectCreateOutcome(
    val projectId: String,
    val catalogRefreshed: Boolean,
)

data class SessionRehome(
    val oldDurableId: String,
    val newDurableId: String,
)

/**
 * How far through the backend's session list this connection has read.
 *
 * Paging is explicit here for the same reason it is on Desktop: the list foot
 * carries a control the user presses (`load-more-row.tsx:15` @ `3ca096de`),
 * never a scroll that quietly asks for more. So this describes a button, and
 * the three things a button needs to know — whether pressing it would do
 * anything, whether a press is in flight, and how many rows are still out
 * there.
 *
 * @param total what the backend says exists in scope, summed across the profile
 *   legs of one refresh (`{"total": N}`, `sessions.py:159`). Null when any leg
 *   could not say — an older Gateway on the RPC fallback never does — because a
 *   partial sum presented as a total is a wrong number and the pager's label
 *   would render it.
 * @param remaining rows in scope that no page has asked for yet — what Desktop
 *   puts in `Load {n}` (`load-more-row.tsx:15`). Counted from the paging
 *   *windows* consumed, not from rows received, because a page can repeat rows:
 *   the route back-fills pinned conversations into every page that would
 *   otherwise drop them (`sessions.py:139`). Null exactly when [total] is.
 * @param canLoadMore whether any leg has rows past the pages already read.
 * @param loading whether a page request is in flight right now.
 */
data class SessionListPaging(
    val total: Long? = null,
    val remaining: Long? = null,
    val canLoadMore: Boolean = false,
    val loading: Boolean = false,
)

private enum class GatewayOptionalCapability {
    Redirect,
    Steer,
    Processes,
    Goals,
    Attachments,

    /**
     * `GET /api/sessions`. A 404 on a route with no path parameters can mean
     * nothing except that this backend lacks it, which is a capability rather
     * than a failure — Desktop remembers it the same way instead of re-probing
     * a known-dead endpoint once per refresh
     * (`apps/desktop/src/hermes.ts:609-616,639-642` @ `3ca096de`). Only a 404
     * sets it: a timeout, a 5xx or a refused connection is a blip, and letting
     * one blip permanently demote a Gateway to the older contract would cost
     * the user pin, archive and unread for the rest of the session.
     */
    SessionListRest,

    /**
     * `GET /api/sessions/{id}/messages`. The paged transcript route: the one
     * contract that can hydrate a session with its newest page instead of the
     * whole conversation, and the only one `Show earlier messages` can read
     * (`session.history` takes a session id and nothing else,
     * `tui_gateway/methods_session.py:2827-2856` @ `3ca096de`). A `404` here is
     * this backend saying it has no such route; every other refusal falls back
     * to the RPC for that one read without demoting the connection, because
     * opening a session must not become less reliable than it was before the
     * window existed.
     */
    SessionMessagesRest,
}

/** Why the session list is being read, and therefore what happens to its cursors. */
private enum class SessionPageRead {
    /** An explicit refresh or a new connection: page one, pager back to one page deep. */
    Refresh,

    /** A backend event moved the rows: page one, every leg keeps the depth it reached. */
    Rescan,

    /** `Load more`: the next page of every leg that has one. */
    More,
}

/**
 * Which set of rows a list pass reads.
 *
 * Two pools, because the Gateway keeps them apart: `archived=exclude` — the
 * route's own default — never mentions an archived row, so the Archived view
 * cannot be a filter over the live page. Desktop draws the same line, fetching
 * its archived set with a second `archived: 'only'` query into a store of its
 * own (`apps/desktop/src/store/sidebar-archive.ts:7-30` @ `3ca096de`).
 */
private enum class SessionPool {
    /** The live list: paged, and the only pool `Load more` walks. */
    Live,

    /**
     * The archived lookup: one capped request per leg, no paging, and no
     * cursors — Desktop calls it "a lookup surface, not a feed".
     */
    Archived,
}

/**
 * One optimistic row-flag write this client has made and the Gateway has not
 * echoed back yet. A null field is a flag this write said nothing about.
 */
private data class PendingFlagWrite(
    val pinned: Boolean? = null,
    val unread: Boolean? = null,
    val archived: Boolean? = null,
    val atMillis: Long,
) {
    /** Fold a second write for the same row into this one, newest wins per flag. */
    fun merge(other: PendingFlagWrite) = PendingFlagWrite(
        pinned = other.pinned ?: pinned,
        unread = other.unread ?: unread,
        archived = other.archived ?: archived,
        atMillis = other.atMillis,
    )

    /** Whether a listed row now agrees with everything this write claimed. */
    fun isConfirmedBy(row: SessionSummary): Boolean =
        (pinned == null || row.pinned == pinned) &&
            (unread == null || row.unread == unread) &&
            (archived == null || row.archived == archived)

    /**
     * Whether a *live* list page could ever bring this write's value back.
     *
     * A row filed as archived leaves the only pool the live list reads —
     * `archived=exclude` never mentions one ([readSessionLeg]) — so no page the
     * rescan could ask for will name the row again, however long it waits. The
     * archived pool's own read is what confirms that fence; scheduling a live
     * rescan for it would be one full list read per profile leg that cannot
     * change anything.
     */
    val confirmableByLivePage: Boolean get() = archived != true
}

/**
 * How long a fenced write outranks a list page, matching Desktop's
 * `UNREAD_WRITE_GUARD_MS` (`apps/desktop/src/store/session-unread-remote.ts:28`
 * @ `3ca096de`). The fence normally clears the moment a page confirms it; the
 * expiry is what stops a backend that never agrees from making this client
 * permanently disbelieve it.
 */
private const val FLAG_WRITE_GUARD_MILLIS = 10_000L

/**
 * Where one session's transcript window stands on this connection.
 *
 * @param pagingSessionId the id the route resolved the compression chain
 *   forward to (`hermes_cli/web_routers/sessions.py:663,707` @ `3ca096de`).
 *   Every later page addresses it, so a page can never be read off a parent.
 * @param profile the scope that served the tail, so an older page routes its
 *   read to the same backend store.
 * @param nextOffset where the next older page starts, measured back from the
 *   newest row.
 * @param possiblyTruncated whether older rows are believed to exist beyond it.
 * @param loading whether a page is on the wire for this session right now.
 * @param generation which hydration this window belongs to. A page in flight
 *   is measured back from the newest row *as this window saw it*; a re-hydrate
 *   re-reads the tail and rebases that origin, so the page must be discarded
 *   rather than prepended against a window it was not measured for — and the
 *   offset alone cannot say, because a rebase can land on the same number.
 */
private data class TranscriptWindow(
    val pagingSessionId: String,
    val profile: String?,
    val nextOffset: Int,
    val possiblyTruncated: Boolean,
    val generation: Long,
    val loading: Boolean = false,
)

/** One hydrated transcript window and the composer todos it ends on. */
private data class TranscriptHydration(
    val entries: List<TranscriptEntry>,
    val todos: List<ComposerTodoStatus>?,
)

/**
 * What one hydration needs to know before it touches the wire, read once under
 * the lock: which profile the read is scoped to, whether that scope is the store
 * that listed the row, and whether this conversation is a compression tip whose
 * ancestors the paged route will not read.
 */
private data class TranscriptHydrationPlan(
    val profile: String?,
    /**
     * Whether this read goes to the store that listed the row.
     *
     * A row that names a profile is read scoped to it, and that name is the
     * canonicalised one the leg that listed it asked for: the list route writes
     * `row_profile = profile_name or _cron_default_profile()` onto every row it
     * serves as `s["profile"]` (`hermes_cli/web_routers/sessions.py:182-189` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     *
     * A row that names NONE is read unscoped, and an unscoped read lands on
     * exactly the launch profile's own `state.db`. Naming none is the ordinary
     * case rather than the exotic one, because this file strips that stamp back
     * off the unscoped leg's rows on purpose — there it describes the Gateway
     * and not the row (`readSessionPages`, the `profile == null` branch). So an
     * unstamped row is owned when the unscoped leg is the leg that listed it
     * (`launchListedRowIds`), and a row this connection has never listed at all
     * is a row whose store nothing here can name.
     */
    val ownerKnown: Boolean,
    val compressed: Boolean,
)

/**
 * The rendering key for a paged row the backend stamped no identifier onto.
 *
 * Keyed off the page's own offset rather than a position in the whole
 * conversation. Within one hydration the pages cover disjoint offset ranges by
 * construction (`nextOffset = offset + returned`), so the same row keeps the
 * same key and two rows cannot claim one. It cannot collide with the RPC
 * history's `"<runtime>-history-<index>"` either.
 *
 * ACROSS hydrations the offsets are measured from a newest row that may have
 * moved, so a re-read can key a row at an offset an earlier read already used
 * for a different row. That is reachable only on a backend that stamps no
 * `messages.id`: at the pin every persisted row carries a positive one and
 * `messageId()` never reaches this fallback, so no key this function makes is
 * ever the thing a merge dedupes on. A backend that stopped stamping would need
 * the key to carry the window generation as well.
 */
private fun restRenderKey(pagingSessionId: String, offset: Int, index: Int): String =
    "$pagingSessionId-rest-${offset + index}"

/** Where one profile leg's paging stands. Meaningless across a connection change. */
private data class SessionPageCursor(
    /** Where this leg's next page starts: the window consumed, not the rows kept. */
    val nextOffset: Int,
    val total: Long?,
    val exhausted: Boolean,
)

/**
 * This freshly-read page-one cursor, carrying forward how deep [loaded] had
 * already read.
 *
 * A rescan re-reads page one for its rows; it is not the reader going back to
 * the top. The fresh page is the authority on the one thing that moves — the
 * scope's `total` — while how many rows have been paid for is the loaded
 * cursor's to say. Taking the deeper offset is also what keeps the arithmetic
 * honest when a row is created or retired between reads: `remaining` is
 * `total` minus what was consumed, and re-reading page one consumes nothing
 * new.
 *
 * `exhausted` follows the offset that survives. A fresh full page one proves
 * nothing about the tail; only the retained offset against the fresh total can
 * say, and when the contract reports no total at all (the `session.list`
 * fallback) the loaded answer stands.
 *
 * A leg with no [loaded] cursor has never answered before, so the fresh page is
 * all there is to know about it.
 */
private fun SessionPageCursor.keepingDepthOf(loaded: SessionPageCursor?): SessionPageCursor =
    if (loaded == null || loaded.nextOffset <= nextOffset) {
        this
    } else {
        copy(
            nextOffset = loaded.nextOffset,
            exhausted = total?.let { loaded.nextOffset >= it } ?: loaded.exhausted,
        )
    }

/** One leg's answer: the rows it returned and where that leaves its cursor. */
private data class SessionLegPage(
    val rows: List<SessionSummary>,
    val cursor: SessionPageCursor,
)

/** Result of staging one attachment payload to the Gateway. */
sealed interface GatewayStageOutcome {
    data class Staged(val reference: StagedAttachmentReference) : GatewayStageOutcome
    data class Rejected(val safeMessage: String) : GatewayStageOutcome
    data object Ambiguous : GatewayStageOutcome
    data object Unsupported : GatewayStageOutcome
}

/** Explicit, connection-scoped durable ↔ runtime identity. */
internal class SessionIdentityMap {
    private val durableToRuntime = mutableMapOf<String, String>()
    private val runtimeToDurable = mutableMapOf<String, String>()

    @Synchronized
    fun bind(durableId: String, runtimeId: String) {
        require(durableId.isNotBlank() && runtimeId.isNotBlank())
        durableToRuntime.put(durableId, runtimeId)?.let(runtimeToDurable::remove)
        runtimeToDurable.put(runtimeId, durableId)?.let(durableToRuntime::remove)
    }

    @Synchronized fun runtimeFor(durableId: String): String? = durableToRuntime[durableId]
    @Synchronized fun durableFor(runtimeId: String): String? = runtimeToDurable[runtimeId]

    @Synchronized
    fun unbindRuntime(runtimeId: String): String? = runtimeToDurable.remove(runtimeId)?.also {
        durableToRuntime.remove(it)
    }

    @Synchronized
    fun clear() {
        durableToRuntime.clear()
        runtimeToDurable.clear()
    }
}

internal class LiveGatewaySessionRepository(
    private val cache: SessionCache,
    private val connectionStateFlow: StateFlow<GatewayConnectionState>,
    private val clientFlow: StateFlow<GatewayRpcClient?>,
    private val scope: CoroutineScope,
    imageLoaderFlow: StateFlow<GatewayImageLoader?> = NO_IMAGE_LOADER,
    private val stopDispatchWaitMillis: Long = STOP_DISPATCH_WAIT_MILLIS,
    /**
     * The connection's authenticated REST transport, borrowed per call. Null
     * means this connection has no REST leg at all, which is not the same as a
     * Gateway that refuses the route: nothing is remembered about a backend
     * that was never asked.
     */
    private val http: () -> GatewayHttp? = { null },
    /**
     * Where the REST leg does its blocking work. See [GatewayRestClient]: the
     * default is a real thread pool, so a test that drives this repository on
     * virtual time injects its own scheduler instead of racing one.
     */
    restContext: CoroutineContext = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : GatewaySessionRepository {
    constructor(
        cache: SessionCache,
        connection: GatewayConnectionManager,
        scope: CoroutineScope,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        cache,
        connection.state,
        connection.client,
        scope,
        connection.imageLoader,
        http = { connection.gatewayHttp.value },
        clock = clock,
    )

    override val connectionState: StateFlow<GatewayConnectionState> = connectionStateFlow
    override val imageLoader: StateFlow<GatewayImageLoader?> = imageLoaderFlow
    private val rehomeEvents = MutableSharedFlow<SessionRehome>(extraBufferCapacity = 8)
    override val sessionRehomes: Flow<SessionRehome> = rehomeEvents
    private val composerControlEvents = MutableSharedFlow<SessionComposerControls>(extraBufferCapacity = 16)
    override val composerControls: Flow<SessionComposerControls> = composerControlEvents
    /**
     * Buffered and dropping: emitted from under `stateLock`, so it must never
     * suspend, and a follower slow enough to lose one has lost a notification
     * rather than a fact.
     */
    private val turnOutcomeEvents = MutableSharedFlow<GatewayTurnOutcome>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val turnOutcomes: Flow<GatewayTurnOutcome> = turnOutcomeEvents
    private val mutablePendingInputs =
        MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
    override val pendingInputs: StateFlow<Map<PendingInputKey, PendingInputRequest>> = mutablePendingInputs
    private val mutableActiveTurns = MutableStateFlow<Set<String>>(emptySet())
    override val activeTurns: StateFlow<Set<String>> = mutableActiveTurns
    override val approvalMode: StateFlow<ApprovalModeState> get() = approvalModeFlow

    /**
     * Requests *this* connection retired: answered, expired, superseded, or
     * died with their turn.
     *
     * It exists so that a key missing from [mutablePendingInputs] can be told
     * apart from a key that was never there. Both look identical to a lookup,
     * and they are opposite facts: the first is finished business, the second
     * is a request this client cannot answer at all.
     *
     * [PendingInputKey.connectionGeneration] cannot make that distinction on
     * its own, which is the subtle part. The generation is a per-process
     * counter that restarts at zero, so a notification posted by a process
     * that has since died carries a generation number a *fresh* process will
     * happily reach again — and on that fresh process the pending map is
     * empty because no session has been opened yet, not because anything was
     * answered. Membership here is process-scoped, so it cannot collide.
     *
     * Cleared with the pending map on every connection change, because
     * "retired" is a fact about one socket. Bounded, and evicting oldest-first
     * degrades an ancient key from "answered" to "cannot answer" — the safe
     * direction, since that shows the user a way to respond rather than
     * silently withdrawing a live request.
     */
    private val retiredKeys = object : LinkedHashMap<PendingInputKey, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PendingInputKey, Unit>): Boolean =
            size > MAX_RETIRED_KEYS
    }

    private val identities = SessionIdentityMap()
    private val sequence = AtomicLong()
    private val stateLock = Any()
    /** Serializes multi-RPC navigation sequences without blocking event routing. */
    private val navigationMutex = Mutex()
    private val refreshMutex = Mutex()
    /** Serializes catalog and detail snapshots so stale details cannot resurrect a removed project. */
    private val projectMutex = Mutex()
    /** One stage-plus-submit sequence per durable session; keyed, not global. */
    private val submitMutexes = KeyedMutex<String>()
    /** Orders only final prompt dispatch against Stop; staging never delays the emergency control. */
    private val turnDispatchMutexes = KeyedMutex<String>()
    /** Stop invalidates submit transactions that have not reached their final dispatch. */
    private val interruptEpochByDurableId = mutableMapOf<String, Long>()
    /** Confirmed Stop epochs suppress a queued row whose acknowledgement arrives afterward. */
    private val confirmedInterruptEpochByDurableId = mutableMapOf<String, Long>()
    /**
     * Live runtimes for which this connection has dispatched `session.interrupt`
     * and still awaits the terminal event. The terminal path consumes this
     * marker exactly once; its request-owner token means a later Stop cannot
     * remove it. A connection reset cannot transfer it to another socket or
     * runtime.
     */
    private val locallyRequestedInterruptRuntimeIds = mutableMapOf<String, Long>()
    /** Monotonic owner token so a later rejected Stop cannot revoke an earlier one. */
    private var nextLocalInterruptMarkerOwner = 0L
    private val assistantByRuntime = mutableMapOf<String, AssistantTurn>()
    private val reasoningByRuntime = mutableMapOf<String, ReasoningActivity>()
    private val toolsByRuntime = mutableMapOf<String, MutableMap<String, ToolActivity>>()
    /** `todo` tools are hoisted into the composer and never rendered as transcript rows. */
    private val todoToolIdsByRuntime = mutableMapOf<String, MutableSet<String>>()
    private val todoClearJobsByDurableId = mutableMapOf<String, Job>()
    private val optimisticUserByRuntime = mutableMapOf<String, UserTurn>()
    /** Accepted corrections remain visible until authoritative transcript reconciliation. */
    private val optimisticCorrectionsByRuntime = mutableMapOf<String, MutableList<UserTurn>>()
    private val progressRuntimeIds = mutableSetOf<String>()
    private val composerStatusRuntimeIds = mutableSetOf<String>()
    /** A completed turn consumes its accepted head envelope at the next message.start. */
    private val queuedPromptDrainReadyBatchIdsByRuntime = mutableMapOf<String, String>()

    /**
     * The profile scope the sidebar is in. App state, not connection state: it
     * survives a reconnect because the user's choice does, and it only ever
     * becomes a `profile` parameter on a session RPC.
     */
    private var profileRouting = ProfileRouting()

    /** Session REST routes over the connection-owned transport; holds no credential. */
    private val rest = GatewayRestClient(restContext, http)

    /** Per profile leg, how far this connection's list has read. Cleared with it. */
    private val sessionPageCursors = mutableMapOf<String?, SessionPageCursor>()

    /**
     * Row flags this client has written and the Gateway has not yet echoed.
     *
     * A list page already in flight when the PATCH lands answers with the OLD
     * value, and merging that page would visibly undo the write for a refresh
     * cycle — a pin that drops back out of the Pinned section, a dot that comes
     * back the moment it was dismissed. Desktop fences the same two flags the
     * same way (`apps/desktop/src/store/session-unread-remote.ts:28-31` and the
     * `unconfirmedPinWrites` fence honoured at
     * `app/chat/sidebar/session-index.ts:51-56,83-88` @ `3ca096de`).
     *
     * Keyed under the live id *and* the compression lineage root, because a
     * page can name the same conversation under either and a pin that only
     * knows the tip vanishes the moment the chat auto-compresses.
     */
    private val pendingFlagWrites = mutableMapOf<String, PendingFlagWrite>()

    /** The single pending [armFlagWriteReconcile] wake-up, if one is armed. */
    private var flagWriteReconcileJob: Job? = null

    /**
     * Rows the UNSCOPED list leg has answered with on this connection.
     *
     * An unscoped list and an unscoped read open the same store — the launch
     * profile's own — so a row this set holds is a row a later unscoped read is
     * addressing in the store that listed it. That is the only thing that makes
     * a `404` from the paged transcript route evidence about the ROUTE rather
     * than about the scope, for the rows the default single-profile topology is
     * made of: their stamp is deliberately stripped (`readSessionPages`), so
     * nothing else about them can say which store they came from.
     *
     * Ids accumulate across pages and refreshes and are cleared with the
     * connection. A row that has since left the backend cannot make this wrong:
     * a `404` for it is only ever read together with a `session.history` that
     * returned rows for the same session.
     */
    private val launchListedRowIds = mutableSetOf<String>()
    private val sessionPagingFlow = MutableStateFlow(SessionListPaging())
    override val sessionPaging: StateFlow<SessionListPaging> = sessionPagingFlow.asStateFlow()

    /**
     * Per hydrated session, where its transcript window stands. Facts about one
     * backend's rows, so cleared with that backend's capabilities.
     */
    private val transcriptWindows = mutableMapOf<String, TranscriptWindow>()
    private var transcriptWindowGeneration = 0L
    private val earlierMessagesFlow = MutableStateFlow<Set<String>>(emptySet())
    override val sessionsWithEarlierMessages: StateFlow<Set<String>> = earlierMessagesFlow.asStateFlow()

    /** Latest server-reported git branch per durable session, connection-scoped. */
    private val branchByDurableId = mutableMapOf<String, String>()
    /** Latest server-reported session cwd; never inferred from local state. */
    private val worktreeByDurableId = mutableMapOf<String, String>()
    /** Optional methods are feature-detected once per connection generation. */
    private val unsupportedCapabilities = mutableSetOf<GatewayOptionalCapability>()
    private val processRefreshesInFlight = mutableSetOf<String>()
    /** Per-connection ordering fences for live state and progress hydration. */
    private val runtimeEventRevisions = mutableMapOf<String, RuntimeEventRevision>()
    private val activeRuntimeIds = linkedSetOf<String>()
    private val reconnectDurableIds = mutableSetOf<String>()
    private val ephemeralSessions = mutableSetOf<String>()
    /** The active drill-in worth rehydrating after a catalog refresh or reconnect. */
    private var lastHydratedProjectId: String? = null
    private var eventJob: Job? = null
    private var bootstrapRefreshJob: Job? = null
    private var connectionGeneration = 0L
    private var metadataRefreshRunning = false
    private var metadataRefreshPending = false
    private var observedClient: GatewayRpcClient? = null
    /** Runtime selected to own identifier-less events; local submits stay pinned while active. */
    private var unscopedRuntimeId: String? = null
    private var localSubmitStartedAtMillis: Long? = null
    private var unscopedTurnIsLive = false
    /** Per-runtime submit timestamp and liveness, keyed by runtime session id. */
    private val localSubmitStartedAtByRuntime = mutableMapOf<String, Long>()
    private val liveTurnRuntimeIds = mutableSetOf<String>()
    private val contextBreakdownBySession = mutableMapOf<String, ContextBreakdown>()
    private val approvalModeFlow = MutableStateFlow(ApprovalModeState())

    /**
     * The last answer this connection was actually given, as opposed to the
     * optimistic one on screen. A refused write rolls back to this rather than
     * to a default, mirroring Desktop's `confirmedModes`
     * (`apps/desktop/src/store/approval-mode.ts:8,90-95` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     */
    private var confirmedApprovalMode: ApprovalMode? = null

    /**
     * Desktop's `revisions` fence (`store/approval-mode.ts:7,16-21`): a read or
     * a write that has been overtaken publishes nothing, so a slow `config.get`
     * cannot land on top of a newer `config.set`.
     */
    private var approvalModeRevision = 0L

    init {
        scope.launch {
            clientFlow.collect { next ->
                eventJob?.cancel()
                bootstrapRefreshJob?.cancel()
                val reset = synchronized(stateLock) {
                    val previous = observedClient
                    observedClient = next
                    connectionGeneration++
                    if (previous != null && previous !== next) {
                        connectionScopedRuntimeIds().forEach { runtimeId ->
                            identities.durableFor(runtimeId)?.let { durableId ->
                                settleConnectionLoss(durableId, runtimeId)
                                reconnectDurableIds += durableId
                            }
                        }
                    }
                    identities.clear()
                    assistantByRuntime.clear()
                    reasoningByRuntime.clear()
                    toolsByRuntime.clear()
                    todoToolIdsByRuntime.clear()
                    todoClearJobsByDurableId.values.forEach(Job::cancel)
                    todoClearJobsByDurableId.clear()
                    optimisticUserByRuntime.clear()
                    optimisticCorrectionsByRuntime.clear()
                    progressRuntimeIds.clear()
                    composerStatusRuntimeIds.clear()
                    queuedPromptDrainReadyBatchIdsByRuntime.clear()
                    contextBreakdownBySession.clear()
                    // The next backend is a different host with its own
                    // approvals config; nothing read from the previous one may
                    // survive, and nothing may be shown until it answers.
                    approvalModeRevision++
                    confirmedApprovalMode = null
                    approvalModeFlow.value = ApprovalModeState()
                    // Branch labels are connection-scoped server truth; the
                    // next session.info re-reports them after reconnect.
                    branchByDurableId.clear()
                    worktreeByDurableId.clear()
                    unsupportedCapabilities.clear()
                    // Offsets are facts about one backend's rows, cleared with
                    // that backend's capabilities just above. So is which rows
                    // the launch profile's own store answered with.
                    sessionPageCursors.clear()
                    launchListedRowIds.clear()
                    // A fence outranks a page from the backend it was written
                    // against. A new connection's pages are not that backend's,
                    // so the fence goes with the offsets — and so does the
                    // reconciliation that was waiting to retire it.
                    pendingFlagWrites.clear()
                    flagWriteReconcileJob?.cancel()
                    flagWriteReconcileJob = null
                    sessionPagingFlow.value = SessionListPaging()
                    transcriptWindows.clear()
                    publishEarlierMessagesLocked()
                    processRefreshesInFlight.clear()
                    runtimeEventRevisions.clear()
                    activeRuntimeIds.clear()
                    localSubmitStartedAtByRuntime.clear()
                    liveTurnRuntimeIds.clear()
                    locallyRequestedInterruptRuntimeIds.clear()
                    // Pending prompts are connection-scoped memory; a new
                    // client rehydrates only through fresh resume responses.
                    // These are stranded, not retired: the requests may still
                    // be parked on the Gateway, so nothing here may later read
                    // as "already answered".
                    mutablePendingInputs.value = emptyMap()
                    retiredKeys.clear()
                    clearUnscopedRuntime()
                    updateActiveTurnsLocked()
                    val ghosts = if (next == null) emptyList() else ephemeralSessions.toList()
                    if (next != null) ephemeralSessions.clear()
                    ConnectionReset(
                        generation = connectionGeneration,
                        ephemeralDurableIds = ghosts,
                        reconnectDurableIds = reconnectDurableIds.toList(),
                        clearProjects = previous !== next,
                    )
                }
                // A just-created session is persisted lazily on first submit.
                // Keep it useful while disconnected, then let the next
                // authoritative list decide whether it really exists.
                reset.ephemeralDurableIds.forEach(cache::removeSession)
                if (reset.clearProjects) {
                    cache.clearProjects()
                    cache.clearConnectionScopedFields(
                        preserveGatewayQueue = reset.reconnectDurableIds.isNotEmpty(),
                    )
                }
                if (next != null) {
                    eventJob = scope.launch {
                        next.events.collect { event ->
                            val refreshMetadata = synchronized(stateLock) {
                                if (reset.generation != connectionGeneration || clientFlow.value !== next) {
                                    false
                                } else {
                                    applyEvent(event)
                                }
                            }
                            if (refreshMetadata) scheduleMetadataRefresh()
                        }
                    }
                    bootstrapRefreshJob = scope.launch {
                        runCatching { refreshSessions() }
                        runCatching { refreshProjects() }
                        reset.reconnectDurableIds.forEach { durableId ->
                            runCatching { openSession(durableId) }
                                .onFailure { failure ->
                                    if (failure is CancellationException) throw failure
                                    synchronized(stateLock) {
                                        if (reset.generation == connectionGeneration && clientFlow.value === next) {
                                            settleReconciliationFailure(durableId)
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    /**
     * Install the profile scope every session RPC is routed under.
     *
     * A change of `activeProfile` is a change of subject for `approvals.mode`:
     * both handlers are `@_profile_scoped` (`tui_gateway/methods_config.py:181-182`,
     * `tui_gateway/server.py:14225-14226` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), so the answer this app is
     * holding belongs to the profile it just left. It is dropped here — flow,
     * confirmed value and revision fence together, the way the endpoint switch
     * drops them — so the chip shows nothing until the new scope's own
     * `config.get` answers, and keeps showing nothing if that read fails. A
     * control that names a security posture must never name another profile's.
     */
    override fun setProfileRouting(routing: ProfileRouting) {
        synchronized(stateLock) {
            val previous = profileRouting.activeProfile
            profileRouting = routing
            if (previous == routing.activeProfile) return
            approvalModeRevision++
            confirmedApprovalMode = null
            approvalModeFlow.value = ApprovalModeState()
        }
    }

    override suspend fun refreshSessions() = refreshMutex.withLock { readSessionPages(SessionPageRead.Refresh) }

    override suspend fun loadMoreSessions() = refreshMutex.withLock { readSessionPages(SessionPageRead.More) }

    /**
     * Re-read page one because something on the backend changed, without
     * telling the list it has only one page again.
     *
     * A turn finishing is news about rows, not about how far the reader has
     * scrolled. [SessionPageRead.Refresh] is what a person asking for a refresh
     * means; a terminal event is not that, and running one on every completed
     * turn would drop the offsets already paid for.
     */
    private suspend fun rescanSessions() = refreshMutex.withLock { readSessionPages(SessionPageRead.Rescan) }

    /**
     * One pass of the session list over every profile leg in scope.
     *
     * The three reads differ only in which offset each leg asks for and what
     * happens to the offsets already loaded:
     *
     * - [SessionPageRead.Refresh] — an explicit refresh, or a new connection.
     *   Page one of every leg, and the pager starts over: the rows already on
     *   screen stay (the cache layers), but the list is back to one page deep.
     * - [SessionPageRead.Rescan] — a backend event says the rows moved. Page
     *   one of every leg, and every cursor keeps the depth it had reached, so
     *   `remaining` and `Load more` still describe the list the reader is
     *   actually looking at.
     * - [SessionPageRead.More] — only the legs with somewhere further to go.
     *
     * All three layer; none replaces.
     */
    private suspend fun readSessionPages(mode: SessionPageRead, pool: SessionPool = SessionPool.Live) {
        val connection = connectionSnapshot()
        val profiles = synchronized(stateLock) {
            val inScope = profileRouting.listProfiles.distinct().ifEmpty { listOf(null) }
            // The archived pool has no pager and no cursors: it is one capped
            // lookup per leg, so it must not touch — or be reported by — the
            // live list's offsets.
            if (pool == SessionPool.Live) {
                when (mode) {
                    SessionPageRead.Refresh -> sessionPageCursors.clear()
                    // A rescan keeps its cursors instead of rebuilding them, so a
                    // leg that has left the scope has to be dropped by name — its
                    // offsets are no longer part of any total this list can show.
                    SessionPageRead.Rescan -> sessionPageCursors.keys.retainAll(inScope.toSet())
                    SessionPageRead.More -> Unit
                }
                sessionPagingFlow.value = sessionPagingFlow.value.copy(loading = true)
            }
            inScope
        }
        var firstFailure: Throwable? = null
        var answered = false
        // Ids the launch-profile leg answered with, in this refresh only, and
        // whether it answered at all.
        val launchRowIds = mutableSetOf<String>()
        val launchLegRequested = profiles.any { it == null }
        var launchLegAnswered = false
        try {
            for (profile in profiles) {
                // A leg with no cursor has never answered, and a leg past its end
                // has nothing to add; neither is a failure, so neither is asked.
                val offset = when {
                    pool == SessionPool.Archived -> 0
                    mode == SessionPageRead.More -> synchronized(stateLock) {
                        sessionPageCursors[profile]?.takeIf { !it.exhausted }?.nextOffset
                    }
                    else -> 0
                } ?: continue
                val leg = try {
                    readSessionLeg(connection, profile, offset, pool)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // The unified view is a fan-out. One profile refusing must not
                    // discard the profiles that answered, so the failure is only
                    // raised when nothing answered at all — which is exactly the
                    // single-profile scope's existing behaviour.
                    if (firstFailure == null) firstFailure = failure
                    continue
                }
                answered = true
                val parsed = leg.rows
                // `session.list`'s compact rows carry no owning profile at the pin
                // (`tui_gateway/methods_session.py:267-282`), so a row listed out of a named
                // profile's own state.db is stamped with the profile that was
                // asked for. The launch-profile leg is left unstamped, which is
                // the `default` bucket by the same rule Desktop filters with
                // (`app/chat/sidebar/profile-scope.ts:12`).
                //
                // A profile the Gateway cannot resolve is not an error there:
                // `_profile_home` answers None and `_profile_db` hands back the
                // launch handle (`tui_gateway/server.py:1556-1571,1599-1613`), so
                // the named leg can return the launch profile's own rows. Rows the
                // launch leg already answered with are therefore left alone — the
                // fan-out asks for it first (`sessionListProfiles`), and stamping
                // them would move them under an owner that does not exist and no
                // later refresh would take it back.
                val rows = if (profile == null) {
                    launchLegAnswered = true
                    parsed.mapTo(launchRowIds, SessionSummary::id)
                    // And the stamp the REST route put on them comes off.
                    //
                    // That route stamps *every* row with a profile even when
                    // the request named none: `row_profile = profile_name or
                    // _cron_default_profile()`, written onto each row as
                    // `s["profile"]` (`hermes_cli/web_routers/sessions.py:182-189`
                    // @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`). That
                    // fallback resolves the Gateway process's *own* active
                    // profile, and answers `"default"` only when that profile
                    // is literally `default` or `custom` — otherwise the
                    // launch profile's real name
                    // (`hermes_cli/web_server.py:12461-12478`).
                    //
                    // So on a Gateway launched under a named profile the
                    // unscoped leg's rows come back stamped with that name,
                    // and `filterSessionsByProfileScope` (`ProfileScope.kt:88`)
                    // would drop every one of them from the `default` scope a
                    // fresh install carries — an empty list on a backend with
                    // sessions. The RPC lane never had that: `session.list`
                    // reports no profile at all, and unstamped *is* the default
                    // bucket by the rule Desktop filters with
                    // (`app/chat/sidebar/profile-scope.ts:12`).
                    //
                    // Both contracts have to put the same rows in the same
                    // bucket, so the stamp is dropped rather than trusted. It
                    // is not a fact about the row: this leg asked for no
                    // profile, so what came back is the Gateway describing
                    // itself. A named leg is the opposite case — there the
                    // stamp is the canonicalised name that was *asked for*
                    // (`sessions.py:95-97`), which is the truth for that scope
                    // and is kept.
                    parsed.map { it.copy(remoteProfile = null) }
                } else if (launchLegRequested && !launchLegAnswered) {
                    // The leg that would have told us which rows are the launch
                    // profile's failed. Without it there is no way to tell a
                    // fallback answer from a real one, so nothing is stamped:
                    // an unstamped row reads as the launch profile's, which is
                    // recoverable, while a wrong owner is not.
                    parsed
                } else {
                    parsed.map { row ->
                        // A row that already names its own owner keeps it. On
                        // the REST route that is always the canonicalised name
                        // this leg asked for — `profile_name` is resolved from
                        // the query value and an unknown one is a `404`, never
                        // a fallback (`sessions.py:95-97,146` via
                        // `web_server.py:12487-12493`) — so the stamp and the
                        // parameter agree and this is a no-op there. It is the
                        // RPC lane that answers out of the launch handle when a
                        // profile will not resolve, and its compact rows carry
                        // no profile, which is what the `launchRowIds` guard is
                        // for.
                        if (row.id in launchRowIds || row.remoteProfile != null) {
                            row
                        } else {
                            row.copy(remoteProfile = profile)
                        }
                    }
                }
                synchronized(stateLock) {
                    ensureCurrent(connection)
                    // An unscoped leg reads the launch profile's own store, so
                    // the rows it answered with are the rows a later unscoped
                    // read addresses in the store that listed them. Both pools
                    // read that same store, so both say so.
                    if (profile == null) rows.mapTo(launchListedRowIds, SessionSummary::id)
                    if (pool == SessionPool.Live) {
                        sessionPageCursors[profile] = when (mode) {
                            // `More` replaces too: its own page is the deeper one,
                            // and a leg that fell back to `session.list` mid-read
                            // reports a terminal cursor that must not be overridden.
                            SessionPageRead.Refresh, SessionPageRead.More -> leg.cursor
                            SessionPageRead.Rescan -> leg.cursor.keepingDepthOf(sessionPageCursors[profile])
                        }
                    }
                    // Aliasing runs before the merge so the merge finds the row it
                    // is layering over under the id this page actually named,
                    // and the fence runs last so an unconfirmed write of ours
                    // outranks a page that was already in flight when it went.
                    val merged = rows.map { applyPendingFlagWrites(mergeListedSession(alignLineage(it))) }
                    cache.upsertSessions(merged)
                    retractWindowsForCompressionTipsLocked(merged)
                }
            }
            if (!answered) firstFailure?.let { throw it }
        } finally {
            if (pool == SessionPool.Live) publishSessionPaging()
        }
    }

    /**
     * One page from one profile leg, preferring the REST contract and falling
     * back to the `session.list` RPC for a Gateway that does not serve it.
     *
     * The fallback is reached one way only: a `404`, which on a route with no
     * path parameters can mean nothing except that this backend lacks it. Every
     * other refusal is raised — a 5xx or a dead connection is a condition to
     * report, not evidence about what the backend can do, and quietly serving
     * the older contract on a blip would hide a real outage behind a list that
     * silently lost its pin, archive and unread fields.
     */
    private suspend fun readSessionLeg(
        connection: ConnectionSnapshot,
        profile: String?,
        offset: Int,
        pool: SessionPool = SessionPool.Live,
    ): SessionLegPage {
        val restUsable = http() != null &&
            !isCapabilityUnsupported(GatewayOptionalCapability.SessionListRest, connection)
        if (restUsable) {
            val result = rest.listSessions(
                limit = if (pool == SessionPool.Archived) ARCHIVED_POOL_SIZE else SESSION_PAGE_SIZE,
                offset = offset,
                // The route's own default, not Desktop's `1`. Desktop asks for
                // 1 because its sidebar hard-replaces and a chat mid-first-
                // response would vanish (`store/session.ts:379-386` @ the pin);
                // this cache layers and never evicts, so the reason does not
                // transfer — and asking for 1 here would hide a just-created
                // session that the RPC contract shows today.
                minMessages = 0,
                // Desktop reads its archived view out of a second `only` query
                // into a store of its own (`store/sidebar-archive.ts:7-30` @
                // `3ca096de`), and so does this. The live page is what decides
                // whether an archived row is *present at all*: `exclude` never
                // mentions one, and folding them into the same LIMIT window
                // would make the Archived view empty for anyone whose newest
                // page is all live conversations.
                archived = when (pool) {
                    SessionPool.Live -> GatewaySessionArchivedFilter.Exclude
                    SessionPool.Archived -> GatewaySessionArchivedFilter.Only
                },
                order = GatewaySessionOrder.Recent,
                profile = profile,
            )
            when (result) {
                is GatewayRestResult.Success -> {
                    val page = result.value
                    val rows = page.rows.map { row -> parseRestSession(row, clock()) }
                    // Advance by the window the route used, never by the rows it
                    // returned. A page can carry *more* rows than its limit: the
                    // route back-fills pinned conversations that the LIMIT/OFFSET
                    // window left out (`include_pinned=True`, `sessions.py:139`,
                    // implemented at `hermes_state.py:9092-9099`). Counting those
                    // extras into the next offset would step past rows that were
                    // never read, and they would simply never appear.
                    val window = page.limit?.toInt()?.takeIf { it in 1..MAX_SESSION_PAGE }
                        ?: SESSION_PAGE_SIZE
                    val consumed = offset + window
                    return SessionLegPage(
                        rows = rows,
                        cursor = SessionPageCursor(
                            nextOffset = consumed,
                            total = page.total,
                            // The route always counts the scope it just paged
                            // (`session_count`, `sessions.py:141`), so the total
                            // is the authority on where the list ends. A short
                            // page is only the fallback answer for a backend
                            // that somehow did not say.
                            exhausted = page.total?.let { consumed >= it } ?: (rows.size < window),
                        ),
                    )
                }

                is GatewayRestResult.Failed -> if (result.statusCode == HTTP_NOT_FOUND) {
                    markCapabilityUnsupported(GatewayOptionalCapability.SessionListRest, connection)
                } else {
                    throw GatewayRpcException(result.safeMessage)
                }
            }
        }
        // The older `session.list` contract has no archived filter at all: it
        // reads `limit` and `include_hidden` and nothing else
        // (`tui_gateway/methods_session.py:246-266` @ `3ca096de`), and the rows
        // it emits carry `id/title/preview/started_at/message_count/source`
        // with no `archived` field to read back (`:267-282`). A backend that
        // only serves it cannot answer this question. An empty pool would
        // render `Nothing archived`, which is a claim about the account rather
        // than about the Gateway, so this says so instead.
        if (pool == SessionPool.Archived) throw GatewayRpcException(ARCHIVED_UNSUPPORTED)
        // The older contract has no offset and no total: one call returns what
        // it returns. Reporting that as exhausted is the honest answer — there
        // is no second page to ask for, so `Load more` must not offer one.
        val result = connection.client.request(
            "session.list",
            buildJsonObject {
                put("limit", JsonPrimitive(100))
                put("include_hidden", JsonPrimitive(false))
                profile?.let { put("profile", JsonPrimitive(it)) }
            },
        )
        val rows = parseSessionList(result, clock())
        return SessionLegPage(
            rows = rows,
            cursor = SessionPageCursor(nextOffset = rows.size, total = null, exhausted = true),
        )
    }

    /**
     * The newest page of a session's transcript, and the todo list it ends on.
     *
     * Desktop hydrates a chat from `getLatestSessionMessages` — 120 rows,
     * newest-first, compacted rows included — and keeps `session.history` for
     * the one thing that still needs the whole stamped conversation, rewind
     * (`apps/desktop/src/api/sessions.ts:408-438` and
     * `apps/desktop/src/app/session/hooks/use-prompt-actions/rewind.ts:200,226`
     * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`). This mirrors that split:
     * the paged route first, the RPC as the contract for a backend that has no
     * such route — and as the answer for one read the route refused, because a
     * blip must not make opening a session worse than it was.
     *
     * The window budget is enforced where the contract exists. The RPC has no
     * `limit` at all (`tui_gateway/methods_session.py:2827-2856`), so a Gateway
     * on that path still hydrates whole, and its window records nothing older
     * to ask for.
     */
    private suspend fun hydrateTranscript(
        connection: ConnectionSnapshot,
        durableId: String,
        canonicalId: String,
        runtimeId: String,
    ): TranscriptHydration {
        val plan = synchronized(stateLock) {
            ensureCurrent(connection)
            val owningProfile = owningProfileParam(canonicalId) ?: owningProfileParam(durableId)
            TranscriptHydrationPlan(
                profile = owningProfile,
                // The question is which STORE this read opens, not whether the
                // row happens to carry a string. A named scope opens the store
                // whose leg stamped that name; no scope at all opens the launch
                // profile's, which owns this row exactly when the unscoped leg
                // is the leg that listed it.
                ownerKnown = if (owningProfile != null) {
                    true
                } else {
                    sequenceOf(canonicalId, durableId).any { it in launchListedRowIds }
                },
                compressed = hasCompressionAncestorLocked(canonicalId, durableId),
            )
        }
        val profile = plan.profile
        val restUsable = http() != null &&
            !isCapabilityUnsupported(GatewayOptionalCapability.SessionMessagesRest, connection) &&
            !plan.compressed
        var routeAnswered404 = false
        if (restUsable) {
            val result = rest.sessionMessages(
                sessionId = canonicalId,
                limit = TRANSCRIPT_PAGE,
                offset = 0,
                order = GatewayMessageOrder.Latest,
                // Durable display history must include the rows in-place
                // compaction preserved; without them the transcript silently
                // ends at the compaction boundary and earlier turns are
                // unreachable (`api/sessions.ts:418-424`).
                includeCompacted = true,
                profile = profile,
            )
            when (result) {
                is GatewayRestResult.Success -> {
                    val page = result.value
                    // The route resolves the compression chain forward to the
                    // live tip before it reads, and reports the id it landed on
                    // (`sessions.py:660-663,707`). Every later page addresses
                    // that id; older rows never come from a parent.
                    val pagingId = page.sessionId.takeIf(String::isNotBlank) ?: canonicalId
                    val rows = projectRestTranscriptRows(page.messages)
                    val entries = parseMessages(rows, clock()) { index ->
                        restRenderKey(pagingId, page.offset?.toInt() ?: 0, index)
                    }
                    val window = transcriptPageState(
                        requestedOffset = 0,
                        echoedOffset = page.offset?.toInt(),
                        echoedLimit = page.limit?.toInt(),
                        returned = page.messages.size,
                    )
                    return synchronized(stateLock) {
                        ensureCurrent(connection)
                        // Re-opening a session re-reads only its newest page.
                        // Replacing the transcript with that page outright
                        // would silently drop everything `Show earlier
                        // messages` had already loaded, so the refreshed tail
                        // is grafted onto the prefix ahead of it and the next
                        // page starts past the rows that prefix holds.
                        val grafted = graftRefreshedTailOntoBackfill(
                            entries,
                            cache.transcript(canonicalId).ifEmpty { cache.transcript(durableId) },
                        )
                        // Read HERE, not at plan time. A `loadEarlierMessages`
                        // that completed while this page was on the wire has
                        // already deepened the window, and a snapshot taken
                        // before the read would step the next page back over
                        // rows the reader now holds. The id falls back the same
                        // way the transcript above does, so a row rehomed onto a
                        // lineage tip finds the window it was actually paged
                        // with.
                        val previousOffset =
                            (transcriptWindows[canonicalId] ?: transcriptWindows[durableId])?.nextOffset ?: 0
                        transcriptWindows[canonicalId] = TranscriptWindow(
                            pagingSessionId = pagingId,
                            profile = profile,
                            // Offsets are raw stored rows measured back from the
                            // newest one, so what the previous window had already
                            // consumed is already in that unit: the pages the
                            // reader loaded covered `[0, previousOffset)` and this
                            // refreshed tail covers `[0, nextOffset)`. The union
                            // ends at the further of the two, and re-deriving it
                            // from the kept prefix would count *entries* the
                            // projection may have dropped and step the next page
                            // onto rows the reader already holds. Rows persisted
                            // since the last read shift the origin, so the deeper
                            // offset can address a row already held — an overlap
                            // the prepend dedupes, never a row skipped.
                            nextOffset = if (grafted.keptPrefix) {
                                maxOf(window.nextOffset, previousOffset)
                            } else {
                                window.nextOffset
                            },
                            possiblyTruncated = window.possiblyTruncated,
                            generation = ++transcriptWindowGeneration,
                        )
                        publishEarlierMessagesLocked()
                        TranscriptHydration(grafted.entries, latestComposerTodosFromRows(rows))
                    }
                }

                // A 404 here is TWO different answers wearing one status. The
                // route raises it for a session id it could not resolve
                // (`sessions.py:660-662,683-684` @ `3ca096de`) as readily as a
                // backend with no such route does, and the read is scoped by
                // `owningProfileParam`, which answers null for a row whose
                // owning profile is not known yet — sending the read to a
                // different profile's `state.db`, where the session genuinely is
                // not found. Demoting on that would turn one unowned row into a
                // whole connection reverting to whole-history hydration. So the
                // status alone demotes nothing; it falls back for this read like
                // any other refusal, and the fallback itself is the evidence.
                is GatewayRestResult.Failed -> routeAnswered404 = result.statusCode == HTTP_NOT_FOUND
            }
        }
        val historyResult = connection.client.request("session.history", historyParams(runtimeId))
        val entries = parseHistory(historyResult, runtimeId, clock())
        // Two independent things have to be true before a 404 is evidence about
        // the ROUTE. The read has to have gone to the store that owns this row —
        // an unowned row's read goes out unscoped and can land on a different
        // profile's `state.db`, where a real session genuinely is not found. And
        // the RPC has to have come back with rows for the same session on the
        // same connection, so the session demonstrably exists and is readable.
        // Neither alone says anything; together they leave only "this backend
        // has no such route". An unowned row, an empty history or a failed one
        // demotes nothing and simply falls back for this read.
        if (routeAnswered404 && plan.ownerKnown && entries.isNotEmpty()) {
            markCapabilityUnsupported(GatewayOptionalCapability.SessionMessagesRest, connection)
        }
        synchronized(stateLock) {
            ensureCurrent(connection)
            // The whole conversation is loaded, so there is nothing earlier to
            // offer and the control must not appear.
            transcriptWindows.remove(canonicalId)
            publishEarlierMessagesLocked()
        }
        return TranscriptHydration(
            entries = entries,
            todos = latestComposerTodosFromHistory(historyResult),
        )
    }

    /**
     * Whether this conversation is the live tip of a compression chain whose
     * earlier sessions the paged route will not read.
     *
     * `session.history` merges the chain
     * (`get_messages_as_conversation(..., include_ancestors=True)`,
     * `tui_gateway/methods_session.py:2843-2847` @ `3ca096de`); the paged route
     * resolves the chain FORWARD to its tip and reads that session's rows alone
     * (`hermes_cli/web_routers/sessions.py:660-663,672-678`). Windowing such a
     * session would put turns Android used to show out of reach behind a control
     * that retires at the tip's first row, so those sessions keep whole-history
     * hydration and are offered no `Show earlier messages` at all.
     *
     * The signal is the list route's own, and this app already reads it:
     * `list_sessions_rich` projects a compression root forward to its tip and
     * stamps `_lineage_root_id` on the row it surfaces, and only on that row
     * (`hermes_state.py:11586-11605`), which [parseRestSession] carries as
     * [SessionSummary.lineageRootId]. It is a fact the REST list route states;
     * the `session.list` RPC does not, so a conversation this connection has
     * only ever seen over the RPC says nothing and takes the window. That
     * boundary is stated in `docs/parity/transcript-backfill.md`.
     *
     * Assumes [stateLock].
     */
    private fun hasCompressionAncestorLocked(canonicalId: String, durableId: String): Boolean =
        sequenceOf(canonicalId, durableId).any { id ->
            cache.session(id)?.lineageRootId?.let { it.isNotBlank() && it != id } == true
        }

    /**
     * Take the window back from a session the list has just revealed to be a
     * compression tip.
     *
     * [hasCompressionAncestorLocked] can only answer for a row the cache already
     * holds, and a session can be hydrated before its listed row arrives — a
     * reconnect resume, a restored active id, a session opened straight from a
     * notification. Such a session is windowed on the evidence available at the
     * time, and the list is the only contract that ever says otherwise
     * (`hermes_state.py:11586-11605` @ `3ca096de`). So the moment it does, the
     * window goes: the control stops being offered rather than paging to a first
     * row that is not the conversation's first row. The transcript already on
     * screen stays as it is, and the next open hydrates it whole.
     *
     * Assumes [stateLock].
     */
    private fun retractWindowsForCompressionTipsLocked(rows: List<SessionSummary>) {
        var retracted = false
        for (row in rows) {
            val root = row.lineageRootId ?: continue
            if (root.isBlank() || root == row.id) continue
            if (transcriptWindows.remove(row.id) != null) retracted = true
        }
        if (retracted) publishEarlierMessagesLocked()
    }

    override suspend fun loadEarlierMessages(durableId: String) {
        val connection = connectionSnapshot()
        val started = synchronized(stateLock) {
            ensureCurrent(connection)
            val id = cache.state.value.rehomes[durableId] ?: durableId
            val window = transcriptWindows[id] ?: return
            // Nothing older, or a page is already on the wire. Desktop leaves
            // its button clickable and shares the one in-flight promise
            // (`transcript-backfill.ts:111-133`); a press here is cheap to
            // ignore, and two overlapping pages would both advance the offset.
            if (!window.possiblyTruncated || window.loading) return
            transcriptWindows[id] = window.copy(loading = true)
            id to window
        }
        val (windowId, window) = started
        try {
            val result = rest.sessionMessages(
                sessionId = window.pagingSessionId,
                limit = TRANSCRIPT_PAGE,
                offset = window.nextOffset,
                order = GatewayMessageOrder.Latest,
                includeCompacted = true,
                profile = window.profile,
            )
            val page = (result as? GatewayRestResult.Success)?.value ?: return
            val rows = projectRestTranscriptRows(page.messages)
            val older = parseMessages(rows, clock()) { index ->
                restRenderKey(window.pagingSessionId, page.offset?.toInt() ?: window.nextOffset, index)
            }
            val advanced = transcriptPageState(
                requestedOffset = window.nextOffset,
                echoedOffset = page.offset?.toInt(),
                echoedLimit = page.limit?.toInt(),
                returned = page.messages.size,
            )
            synchronized(stateLock) {
                // The connection changed under the fetch: this page describes a
                // backend that is no longer the one on screen.
                if (connection.generation != connectionGeneration || clientFlow.value !== connection.client) return
                val current = transcriptWindows[windowId] ?: return
                // The window moved while the page was in flight — a re-hydrate
                // or a lineage change. Discard it rather than prepend a page
                // measured from an origin that no longer applies.
                if (current.generation != window.generation) return
                val existing = cache.transcript(windowId)
                val merged = mergeOlderTranscriptPage(existing, older)
                // Reconciliation is the tail's rule: it decides what the live
                // projection may add past persisted history
                // (`reconcileAuthoritativeTranscript`). [existing] has already
                // been through it and every prepended row is strictly older
                // than its boundary, so the merge layers under a reconciled
                // transcript rather than around one.
                if (merged !== existing) cache.setTranscript(windowId, merged)
                transcriptWindows[windowId] = current.copy(
                    nextOffset = advanced.nextOffset,
                    possiblyTruncated = advanced.possiblyTruncated,
                    loading = false,
                )
                publishEarlierMessagesLocked()
            }
        } finally {
            synchronized(stateLock) {
                transcriptWindows[windowId]?.takeIf(TranscriptWindow::loading)?.let { latest ->
                    transcriptWindows[windowId] = latest.copy(loading = false)
                }
                publishEarlierMessagesLocked()
            }
        }
    }

    /** Assumes [stateLock]. */
    private fun publishEarlierMessagesLocked() {
        earlierMessagesFlow.value = transcriptWindows
            .filterValues(TranscriptWindow::possiblyTruncated)
            .keys
            .toSet()
    }

    private fun publishSessionPaging() {
        sessionPagingFlow.value = synchronized(stateLock) {
            val cursors = sessionPageCursors.values.toList()
            // Only when every leg said. One leg's count presented as the whole
            // scope's total is a wrong number on a visible label.
            val total = cursors.map(SessionPageCursor::total)
                .takeIf { totals -> totals.isNotEmpty() && totals.all { it != null } }
                ?.filterNotNull()
                ?.sum()
            SessionListPaging(
                total = total,
                remaining = total?.let { (it - cursors.sumOf(SessionPageCursor::nextOffset)).coerceAtLeast(0) },
                canLoadMore = cursors.any { !it.exhausted },
                loading = false,
            )
        }
    }

    /**
     * Move a conversation the cache still files under its compression-lineage
     * root onto the live tip this page named.
     *
     * The list projects a compression chain forward to its latest continuation
     * and reports the original root separately (`hermes_state.py:9383-9392` @
     * `3ca096de`), so the same conversation can arrive under a different id than
     * the one an earlier refresh or resume filed it under. Two rows for one
     * conversation is the failure this prevents.
     *
     * Navigation identity does not change: [SessionCache.rehomeSession]
     * publishes the root → tip alias, and a screen already holding the root id
     * resolves through it (`ui/chat/ChatViewModel.kt:387`). Nothing is bound to
     * a runtime here — this is a list, not a resume, and no turn is running
     * under either id by virtue of having been listed.
     */
    private fun alignLineage(row: SessionSummary): SessionSummary {
        val rootId = row.lineageRootId?.takeIf { it.isNotBlank() && it != row.id } ?: return row
        if (cache.session(row.id) != null) return row
        val existing = cache.session(rootId) ?: return row
        cache.rehomeSession(rootId, existing.copy(id = row.id), cache.transcript(rootId))
        if (ephemeralSessions.remove(rootId)) ephemeralSessions += row.id
        branchByDurableId.remove(rootId)?.let { branchByDurableId[row.id] = it }
        worktreeByDurableId.remove(rootId)?.let { worktreeByDurableId[row.id] = it }
        rehomeEvents.tryEmit(SessionRehome(rootId, row.id))
        return row
    }

    /**
     * The `profile` parameter for acting on one known row: its own owner, or
     * null for the Gateway's own profile and for a row nothing is known about.
     */
    private fun owningProfileParam(durableId: String): String? =
        cache.session(durableId)?.remoteProfile?.trim()
            ?.takeIf { it.isNotEmpty() && it != DEFAULT_PROFILE }

    /** Layer one listed row over what the cache already knows about it. */
    private fun mergeListedSession(row: SessionSummary): SessionSummary =
        cache.session(row.id)?.let { existing ->
            row.copy(
                status = existing.status,
                progress = existing.progress,
                composerStatus = existing.composerStatus,
                activityStartedAtMillis = existing.activityStartedAtMillis,
                // A `session.info` event names a row's profile authoritatively;
                // a later list that cannot say must not take it away.
                remoteProfile = row.remoteProfile ?: existing.remoteProfile,
                gitBranch = branchByDurableId[row.id] ?: row.gitBranch ?: existing.gitBranch,
                worktreePath = worktreeByDurableId[row.id] ?: row.worktreePath ?: existing.worktreePath,
                // The same rule the profile follows, for the same reason: a
                // contract that cannot say must not take away what a contract
                // that could say already told us. A Gateway that *does* report
                // these sends a real `false`, which lands here as `false` and
                // overwrites — only a genuinely absent field preserves. That is
                // what keeps a mixed refresh (REST leg plus RPC fallback leg)
                // from silently unpinning half the list.
                archived = row.archived ?: existing.archived,
                pinned = row.pinned ?: existing.pinned,
                unread = row.unread ?: existing.unread,
                model = row.model ?: existing.model,
                toolCallCount = row.toolCallCount ?: existing.toolCallCount,
                inputTokens = row.inputTokens ?: existing.inputTokens,
                outputTokens = row.outputTokens ?: existing.outputTokens,
                actualCostUsd = row.actualCostUsd ?: existing.actualCostUsd,
                estimatedCostUsd = row.estimatedCostUsd ?: existing.estimatedCostUsd,
                lineageRootId = row.lineageRootId ?: existing.lineageRootId,
            )
        } ?: row.copy(
            gitBranch = branchByDurableId[row.id] ?: row.gitBranch,
            worktreePath = worktreeByDurableId[row.id] ?: row.worktreePath,
        )

    override suspend fun refreshProjects() {
        val rehydrate = projectMutex.withLock {
            val connection = connectionSnapshot()
            val payload = try {
                connection.client.request(
                    "projects.tree",
                    buildJsonObject { put("preview_limit", JsonPrimitive(PROJECT_PREVIEW_LIMIT)) },
                )
            } catch (failure: Throwable) {
                if (failure.isMissingProjectsMethod()) {
                    synchronized(stateLock) {
                        ensureCurrent(connection)
                        cache.markProjectsUnavailable()
                    }
                    return@withLock null
                }
                throw failure
            }
            val overview = parseProjectOverview(payload, clock())
            synchronized(stateLock) {
                ensureCurrent(connection)
                cache.replaceProjectOverview(overview.projects, overview.activeProjectId)
                lastHydratedProjectId?.takeIf { projectId ->
                    overview.projects.any { it.id == projectId }
                }.also { lastHydratedProjectId = it }
            }
        }
        rehydrate?.let { projectId ->
            runCatching { openProject(projectId) }
                .onFailure { failure -> if (failure is CancellationException) throw failure }
        }
    }

    override suspend fun openProject(projectId: String) = projectMutex.withLock {
        require(projectId.isNotBlank())
        val connection = connectionSnapshot()
        synchronized(stateLock) {
            ensureCurrent(connection)
            if (cache.state.value.projects.available == true &&
                projectId !in cache.state.value.projects.projects
            ) {
                throw GatewayRpcException("This project is no longer available.")
            }
        }
        val result = connection.client.request(
            "projects.project_sessions",
            buildJsonObject { put("project_id", JsonPrimitive(projectId)) },
        )
        val details = parseProjectDetails(result, clock())
        synchronized(stateLock) {
            ensureCurrent(connection)
            lastHydratedProjectId = projectId
            cache.replaceProjectDetails(details.project, details.sessions)
        }
    }

    override suspend fun createProject(name: String, folderPath: String): ProjectCreateOutcome {
        val cleanName = name.trim()
        val cleanPath = folderPath.trim()
        require(cleanName.isNotEmpty())
        require(cleanPath.isNotEmpty())
        val projectId = projectMutex.withLock {
            val connection = connectionSnapshot()
            val result = connection.client.request(
                "projects.create",
                buildJsonObject {
                    put("name", JsonPrimitive(cleanName))
                    put("folders", JsonArray(listOf(JsonPrimitive(cleanPath))))
                    put("primary_path", JsonPrimitive(cleanPath))
                    put("use", JsonPrimitive(true))
                },
            ).asObject("projects.create")
            synchronized(stateLock) { ensureCurrent(connection) }
            val project = result["project"] as? JsonObject
                ?: throw GatewayRpcException("Hermes did not return the created project.")
            project.string("id")?.takeIf(String::isNotBlank)
                ?: throw GatewayRpcException("Hermes did not return a project id.")
        }
        // Re-read backend truth instead of teaching this write path a second
        // project-tree parser. Creation has already succeeded at this point, so
        // a refresh failure must not tell callers to retry the write.
        val catalogRefreshed = try {
            refreshProjects()
            true
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            false
        }
        return ProjectCreateOutcome(projectId, catalogRefreshed)
    }

    override suspend fun openSession(durableId: String): String = navigationMutex.withLock {
        val connection = connectionSnapshot()
        val knownRuntime = synchronized(stateLock) { identities.runtimeFor(durableId) }
        val liveSnapshot: JsonObject
        val snapshotRevision: RuntimeEventRevision
        val runtimeId: String
        val canonicalId: String
        if (knownRuntime != null) {
            snapshotRevision = synchronized(stateLock) {
                ensureCurrent(connection)
                runtimeEventRevision(knownRuntime)
            }
            liveSnapshot = connection.client.request("session.activate", objectParams("session_id", knownRuntime))
                .asObject("session.activate")
            synchronized(stateLock) { ensureCurrent(connection) }
            runtimeId = knownRuntime
            canonicalId = synchronized(stateLock) { identities.durableFor(runtimeId) } ?: durableId
        } else {
            // Resume in the profile that owns the row, not the scope the
            // sidebar happens to be in — the unified view lists other
            // profiles' sessions and opening one must reach its state.db
            // (`methods_session.py:327-330`).
            val owningProfile = synchronized(stateLock) { owningProfileParam(durableId) }
            liveSnapshot = connection.client.request(
                "session.resume",
                buildJsonObject {
                    put("session_id", JsonPrimitive(durableId))
                    owningProfile?.let { put("profile", JsonPrimitive(it)) }
                },
            ).asObject("session.resume")
            runtimeId = liveSnapshot.string("session_id")
                ?: throw GatewayRpcException("Hermes did not return a runtime session id.")
            canonicalId = liveSnapshot.canonicalDurableId() ?: durableId
            snapshotRevision = synchronized(stateLock) {
                ensureCurrent(connection)
                identities.bind(canonicalId, runtimeId)
                runtimeEventRevision(runtimeId)
            }
        }

        val hydration = hydrateTranscript(connection, durableId, canonicalId, runtimeId)
        val history = hydration.entries
        val hydratedTodos = hydration.todos
        synchronized(stateLock) {
            ensureCurrent(connection)
            val currentRevision = runtimeEventRevision(runtimeId)
            val liveSnapshotIsCurrent = currentRevision.live == snapshotRevision.live
            val progressSnapshotIsCurrent = currentRevision.progress == snapshotRevision.progress
            val projection = if (liveSnapshotIsCurrent) {
                parseLiveSessionProjection(liveSnapshot, clock())
            } else {
                EMPTY_LIVE_SESSION_PROJECTION
            }
            val localLive = connectionScopedInflight(runtimeId)
            val reconciled = reconcileAuthoritativeTranscript(
                history,
                runtimeId,
                projection,
                localLive,
            )
            val priorStatus = cache.session(canonicalId)?.status ?: cache.session(durableId)?.status ?: SessionStatus.Idle
            val status = reconcileLiveState(runtimeId, projection, localLive.isNotEmpty(), reconciled, priorStatus)
            if (progressSnapshotIsCurrent) progressRuntimeIds.remove(runtimeId)
            val canonicalRow = canonicalSummary(
                durableId,
                canonicalId,
                liveSnapshot,
                status,
                liveSnapshotIsCurrent,
                preserveProgress = !progressSnapshotIsCurrent,
            )
            // Queue drains arrive as live events, so only a connection loss can
            // have hidden one. This id stays in reconnectDurableIds until the
            // post-reconnect reconciliation below clears it, and that window is
            // the only thing that licenses reading a local batch as drained.
            val mayHaveMissedDrain =
                durableId in reconnectDurableIds || canonicalId in reconnectDurableIds
            val row = if (liveSnapshotIsCurrent) {
                canonicalRow.withGatewayQueueProjection(projection, runtimeId, mayHaveMissedDrain)
            } else {
                canonicalRow
            }
            cache.rehomeSession(durableId, row, reconciled)
            hydratedTodos?.takeUnless(::todoListActive)?.let { todos ->
                setComposerTodos(canonicalId, runtimeId, todos)
            }
            reconnectDurableIds.remove(durableId)
            reconnectDurableIds.remove(canonicalId)
            if (canonicalId != durableId) {
                rehomeEvents.tryEmit(SessionRehome(durableId, canonicalId))
            }
        }
        canonicalId
    }

    override suspend fun createSession(workspacePath: String?): String = createSession(workspacePath, null)

    override suspend fun createSession(
        workspacePath: String?,
        overrides: NewSessionComposerOverrides?,
    ): String = navigationMutex.withLock {
        val connection = connectionSnapshot()
        val result = connection.client.request(
            "session.create",
            buildJsonObject {
                put("source", JsonPrimitive("desktop"))
                // The new chat belongs to the profile the rail is scoped to
                // (`methods_session.py:38-43`); omitted means the launch profile.
                synchronized(stateLock) { profileRouting }.activeProfile
                    ?.let { put("profile", JsonPrimitive(it)) }
                workspacePath?.trim()?.takeIf(String::isNotEmpty)?.let { put("cwd", JsonPrimitive(it)) }
                overrides?.selection?.takeIf { it.isSpecified }?.let { selection ->
                    put("model", JsonPrimitive(selection.model.trim()))
                    selection.provider.trim().takeIf(String::isNotEmpty)?.let { provider ->
                        put("provider", JsonPrimitive(provider))
                    }
                }
                overrides?.reasoning?.takeUnless { it is ReasoningEffort.Unknown }?.let {
                    put("reasoning_effort", JsonPrimitive(it.wireValue))
                }
                when (overrides?.fast) {
                    FastMode.Fast -> put("fast", JsonPrimitive(true))
                    FastMode.Normal -> put("fast", JsonPrimitive(false))
                    null, is FastMode.Unknown -> Unit
                }
            },
        ).asObject("session.create")
        val runtimeId = result.string("session_id")
            ?: throw GatewayRpcException("Hermes did not return a runtime session id.")
        val durableId = result.string("stored_session_id")
            ?: throw GatewayRpcException("Hermes did not return a durable session id.")
        val info = (result["session"] as? JsonObject) ?: (result["info"] as? JsonObject) ?: result
        synchronized(stateLock) {
            ensureCurrent(connection)
            identities.bind(durableId, runtimeId)
            ephemeralSessions += durableId
            cache.upsertSession(parseSession(info, clock(), durableId))
            val messages = result["messages"]
            if (messages is JsonArray) {
                cache.setTranscript(
                    durableId,
                    parseMessages(messages, clock()) { index -> "$runtimeId-history-$index" },
                )
            }
        }
        durableId
    }

    override suspend fun loadModelOptions(durableId: String?): ModelCatalog {
        val binding = durableId?.trim()?.takeIf(String::isNotEmpty)?.let { ensureRuntime(it) }
        val connection = connectionSnapshot()
        val result = connection.client.request(
            "model.options",
            buildJsonObject {
                binding?.runtimeId?.let { put("session_id", JsonPrimitive(it)) }
            },
        )
        synchronized(stateLock) { ensureCurrent(connection) }
        return parseModelCatalog(result)
    }

    override suspend fun loadComposerControls(durableId: String?): ModelControlsSnapshot {
        return loadComposerState(durableId).controls
    }

    override suspend fun loadComposerState(durableId: String?): ComposerControlState {
        val binding = durableId?.trim()?.takeIf(String::isNotEmpty)?.let { ensureRuntime(it) }
        val connection = connectionSnapshot()
        fun params(key: String): JsonObject = buildJsonObject {
            put("key", JsonPrimitive(key))
            binding?.runtimeId?.let { put("session_id", JsonPrimitive(it)) }
        }
        // config.get(provider) deliberately resolves the profile default, not
        // a live session override. model.options is the Gateway's effective
        // session-aware model/provider authority.
        val (catalogPayload, reasoning, fast) = coroutineScope {
            val catalogRequest = async {
                connection.client.request(
                    "model.options",
                    buildJsonObject { binding?.runtimeId?.let { put("session_id", JsonPrimitive(it)) } },
                )
            }
            val reasoningRequest = async {
                connection.client.request("config.get", params("reasoning")).asObject("config.get")
            }
            val fastRequest = async {
                connection.client.request("config.get", params("fast")).asObject("config.get")
            }
            Triple(catalogRequest.await(), reasoningRequest.await(), fastRequest.await())
        }
        synchronized(stateLock) { ensureCurrent(connection) }
        val catalog = parseModelCatalog(catalogPayload)
        return ComposerControlState(
            catalog = catalog,
            controls = ModelControlsSnapshot(
                selection = catalog.effectiveSelection,
                reasoning = ReasoningEffort.fromWire(reasoning.string("value")),
                fast = FastMode.fromWire(fast.string("value")),
            ),
        )
    }

    override fun hasLiveRuntime(durableId: String): Boolean {
        val trimmed = durableId.trim().takeIf(String::isNotEmpty) ?: return false
        return synchronized(stateLock) { identities.runtimeFor(trimmed) != null }
    }

    override suspend fun loadContextBreakdown(durableId: String): ContextBreakdown? {
        val trimmed = durableId.trim().takeIf(String::isNotEmpty) ?: return null
        // Never `ensureRuntime` from here. That falls through to `openSession`,
        // which takes the navigation mutex; a periodic read has no business
        // holding it, and a session with no runtime has nothing to estimate
        // from anyway. Desktop asks the same question by only ever passing a
        // session that is already active (`use-context-breakdown.ts:41` @
        // `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
        val runtimeId = synchronized(stateLock) { identities.runtimeFor(trimmed) }
            ?: return synchronized(stateLock) { contextBreakdownBySession[trimmed] }
        val connection = connectionSnapshot()
        val params = buildJsonObject {
            put("session_id", JsonPrimitive(runtimeId))
            // `_sess_nowait` (`tui_gateway/server.py:3564-3584` @ the same SHA)
            // reads only `session_id`, so this is defensive and unread: it keeps
            // the routing shape every other session RPC here sends.
            synchronized(stateLock) { profileRouting }.activeProfile
                ?.let { put("profile", JsonPrimitive(it)) }
        }
        // `runCatching` would swallow the CancellationException a session switch
        // throws, and the cancelled job would then clear "Loading breakdown…"
        // out from under the switch's own in-flight read.
        val response = try {
            connection.client.request("session.context_breakdown", params) as? JsonObject
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return synchronized(stateLock) { contextBreakdownBySession[trimmed] }
        synchronized(stateLock) { ensureCurrent(connection) }
        val parsed = parseContextBreakdown(response) ?: return synchronized(stateLock) {
            contextBreakdownBySession[trimmed]
        }
        synchronized(stateLock) {
            contextBreakdownBySession[trimmed] = parsed
        }
        return parsed
    }

    /**
     * `config.get {key: "approvals.mode", profile}`
     * (`tui_gateway/methods_config.py:290-294` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     *
     * Desktop sends no `profile` (`store/approval-mode.ts:56`) and therefore
     * always reads the launch profile's config. This app sends the profile the
     * rail is scoped to, because the handler is `@_profile_scoped`
     * (`methods_config.py:181-182`, `server.py:2463-2482`) and every other
     * session RPC here is already scoped that way — reading one profile's
     * approvals while writing another's would be worse than either.
     *
     * A status read: a failure keeps the last confirmed answer rather than
     * blanking a control the person may be looking at.
     */
    override suspend fun refreshApprovalMode() {
        val connection = connectionSnapshot()
        val revision = synchronized(stateLock) { ++approvalModeRevision }
        val mode = try {
            val response = connection.client.request("config.get", approvalModeParams()).asObject("config.get")
            // Inside the `try` so the whole read is silent: an endpoint switch
            // mid-flight makes [ensureCurrent] throw, and that is a stale
            // answer to drop rather than an error to raise.
            synchronized(stateLock) { ensureCurrent(connection) }
            ApprovalMode.fromWire(response.string("value"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        publishApprovalMode(mode, revision)
    }

    /**
     * `config.set {key: "approvals.mode", value, profile}`
     * (`tui_gateway/server.py:14584-14598` @ the same SHA).
     *
     * Optimistic, the way Desktop's `setApprovalModeForProfile` is
     * (`store/approval-mode.ts:67-97`): the chosen mode paints immediately, the
     * Gateway's echoed value confirms it, and a refusal — `4002` for a value
     * outside `manual|smart|off`, or a transport failure — rolls back to the
     * last confirmed mode. The Gateway also re-emits `session.info` for every
     * live session on success (`:14594-14597`), which reconciles the same value
     * through [applyStreamedApprovalMode] for any other client on this host.
     */
    override suspend fun setApprovalMode(mode: ApprovalMode): ApprovalModeOutcome {
        val connection = connectionSnapshot()
        // The paint happens inside the same critical section that produced the
        // fence it is guarded by, so a concurrent publish cannot be overwritten
        // by an optimistic value from between the bump and the assignment.
        val revision = synchronized(stateLock) {
            val fence = ++approvalModeRevision
            approvalModeFlow.value = approvalModeFlow.value.copy(mode = mode)
            fence
        }
        return try {
            val response = connection.client.request(
                "config.set",
                buildJsonObject {
                    put("key", JsonPrimitive(APPROVALS_MODE_KEY))
                    put("value", JsonPrimitive(mode.wireValue))
                    activeProfileParam()?.let { put("profile", JsonPrimitive(it)) }
                },
            ).asObject("config.set")
            synchronized(stateLock) { ensureCurrent(connection) }
            // The echo is `{"key": …, "value": raw}` (`server.py:14598`), and it
            // is what confirms the write. An echo that carries no `value` at all
            // confirms the mode the host just accepted rather than resolving a
            // null through `fromWire` to Manual, which would silently revert a
            // write that landed. Desktop normalises the same null to `manual`
            // (`store/approval-mode.ts:82`); on a phone this chip is the only
            // place the posture is named, so an unasked-for reversion is worse.
            publishApprovalMode(response.string("value")?.let(ApprovalMode::fromWire) ?: mode, revision)
            ApprovalModeOutcome.Applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            synchronized(stateLock) {
                if (approvalModeRevision == revision) {
                    approvalModeFlow.value = approvalModeFlow.value.copy(mode = confirmedApprovalMode)
                }
            }
            ApprovalModeOutcome.Rejected(APPROVAL_MODE_REJECTED)
        }
    }

    private fun approvalModeParams(): JsonObject = buildJsonObject {
        put("key", JsonPrimitive(APPROVALS_MODE_KEY))
        activeProfileParam()?.let { put("profile", JsonPrimitive(it)) }
    }

    private fun activeProfileParam(): String? =
        synchronized(stateLock) { profileRouting }.activeProfile

    private fun publishApprovalMode(mode: ApprovalMode, revision: Long) {
        synchronized(stateLock) {
            if (approvalModeRevision != revision) return
            confirmedApprovalMode = mode
            approvalModeFlow.value = approvalModeFlow.value.copy(mode = mode)
        }
    }

    /**
     * Reconcile the `approval_mode` / `yolo` pair a streamed `session.info`
     * carries (`tui_gateway/server.py:7659-7660` @ `3ca096de`).
     *
     * **Only while the app is scoped to the Gateway's launch profile.** Those
     * two fields come from `_load_approval_mode()`, which resolves under
     * whichever `HERMES_HOME` is bound when the event is emitted
     * (`server.py:5953-5971`). The `config.set` handler emits inside its own
     * `@_profile_scoped` binding, so that one reports the profile that was
     * written; every other emit — a turn start, a turn end, a resume — happens
     * outside any binding and reports the *launch* profile's config. Accepting
     * those while scoped elsewhere would flip the control to another profile's
     * posture, so a named scope takes its answer from the scoped `config.get`
     * and `config.set` echo alone.
     *
     * The scope test and the publish are **one** critical section. Read under a
     * separate acquisition, a [setProfileRouting] landing between the two would
     * let a launch-profile event repaint — and confirm — the mode the profile
     * switch had just dropped, which is exactly the answer that clear exists to
     * remove; it would then survive a failed scoped `config.get`, because that
     * read is silent.
     *
     * `internal` so the two-thread race can be driven directly: a single test
     * dispatcher cannot interleave two threads inside one function.
     */
    internal fun applyStreamedApprovalMode(payload: JsonObject) {
        if ("approval_mode" !in payload && "yolo" !in payload) return
        synchronized(stateLock) {
            if (profileRouting.activeProfile != null) return
            // This is an authoritative answer, so it also fences any read or
            // write still in flight: whatever they were told is older.
            approvalModeRevision++
            val next = if ("approval_mode" in payload) {
                confirmedApprovalMode = ApprovalMode.fromWire(payload.string("approval_mode"))
                approvalModeFlow.value.copy(mode = confirmedApprovalMode)
            } else {
                approvalModeFlow.value
            }
            approvalModeFlow.value = if ("yolo" in payload) {
                next.copy(bypassActive = payload.boolean("yolo") == true)
            } else {
                next
            }
        }
    }

    override suspend fun setLiveModel(
        durableId: String,
        selection: ComposerModelSelection,
    ): ControlMutationResult {
        if (!selection.isSpecified) return ControlMutationResult.Rejected("Choose a model, then try again.")
        val value = buildString {
            append(selection.model.trim())
            selection.provider.trim().takeIf(String::isNotEmpty)?.let { append(" --provider ").append(it) }
            append(" --session")
        }
        return mutateLiveControl(durableId, "model", value, modelSwitch = true)
    }

    override suspend fun setLiveReasoning(
        durableId: String,
        effort: ReasoningEffort,
    ): ControlMutationResult = mutateLiveControl(durableId, "reasoning", effort.wireValue)

    override suspend fun setLiveFast(durableId: String, mode: FastMode): ControlMutationResult =
        mutateLiveControl(durableId, "fast", mode.wireValue)

    override suspend fun completeSlash(query: String): CompletionResult {
        val connection = connectionSnapshot()
        val result = connection.client.request("complete.slash", objectParams("text", query))
        synchronized(stateLock) { ensureCurrent(connection) }
        return parseCompletionResult(result, "complete.slash")
    }

    override suspend fun completePath(durableId: String?, query: String, cwd: String): CompletionResult {
        val binding = durableId?.trim()?.takeIf(String::isNotEmpty)?.let { ensureRuntime(it) }
        val connection = connectionSnapshot()
        val result = connection.client.request(
            "complete.path",
            buildJsonObject {
                binding?.runtimeId?.let { put("session_id", JsonPrimitive(it)) }
                put("word", JsonPrimitive(query))
                cwd.trim().takeIf(String::isNotEmpty)?.let { put("cwd", JsonPrimitive(it)) }
            },
        )
        synchronized(stateLock) { ensureCurrent(connection) }
        return parseCompletionResult(result, "complete.path")
    }

    override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome =
        submit(durableId, text, queued = false)

    override suspend fun submit(
        durableId: String,
        text: String,
        queued: Boolean,
        attachments: List<OutgoingAttachment>,
    ): GatewaySubmitOutcome {
        if (attachments.isEmpty()) return submit(durableId, text, queued)
        val interruptEpoch = submitInterruptEpoch(durableId)
        // The per-session lock must cover staging AND submit: the Gateway's
        // attached_images slot is session-global, so a concurrent text drain
        // that submitted between our last stage and prompt.submit would claim
        // the staged images for the wrong prompt.
        return submitMutexes.withLock(durableId) {
            submitAttachmentsLocked(durableId, text, queued, attachments, interruptEpoch)
        }
    }

    private fun submitInterruptEpoch(durableId: String): Long = synchronized(stateLock) {
        interruptEpochByDurableId[durableId] ?: 0L
    }

    private fun requireUninterruptedSubmit(durableId: String, expectedEpoch: Long) {
        val interrupted = synchronized(stateLock) {
            (interruptEpochByDurableId[durableId] ?: 0L) != expectedEpoch
        }
        if (interrupted) throw GatewayRpcException("The message was not sent because Stop was requested.")
    }

    /** Called only while [submitMutexes] owns this durable session. */
    private suspend fun submitAttachmentsLocked(
        durableId: String,
        text: String,
        queued: Boolean,
        attachments: List<OutgoingAttachment>,
        interruptEpoch: Long,
    ): GatewaySubmitOutcome {
        val binding = try {
            ensureRuntime(durableId)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw GatewayRpcException("Reopen this session before attaching files.")
        }
        val connection = try {
            connectionSnapshot()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            throw GatewayRpcException("Reconnect to the Gateway and try again.")
        }
        // Stage everything first. A prompt must never cross after a failed or
        // ambiguous stage; the caller keeps its drafts for in-place retry.
        val fileRefs = StringBuilder()
        val imageRefs = mutableListOf<String>()
        val stagedImagePaths = mutableListOf<String>()
        var hasUndetachableImage = false

        suspend fun rejectAfterCleanup(failure: GatewayRpcException): Nothing {
            if (hasUndetachableImage || !detachStagedImages(connection, binding.runtimeId, stagedImagePaths)) {
                throw GatewayRpcException(
                    "The attachment upload could not be reconciled. Check this session before trying again.",
                    requestMayHaveBeenAccepted = true,
                )
            }
            throw failure
        }

        for (attachment in attachments) {
            requireUninterruptedSubmit(durableId, interruptEpoch)
            val result = stageOne(binding, connection, attachment)
            when (result) {
                is GatewayStageOutcome.Staged -> {
                    when (attachment) {
                        // Desktop parity: images contribute nothing to the
                        // submitted text. The gateway already holds the bytes
                        // via image.attach_bytes and persists the `@image:`
                        // refs itself at turn end (`_build_persist_message_with_image_refs`,
                        // tui_gateway/server.py @ 3ca096de). The attach response's
                        // `text` field is placeholder prose for the model and
                        // must never be echoed into the user-visible turn.
                        is OutgoingAttachment.Image -> {
                            val path = result.reference.gatewayPath
                            if (path == null) {
                                hasUndetachableImage = true
                            } else {
                                stagedImagePaths += path
                                imageRefs += ImageRefLines.formatRef(path)
                            }
                        }

                        is OutgoingAttachment.GenericFile -> {
                            if (result.reference.refText.isNotBlank()) {
                                if (fileRefs.isNotEmpty()) fileRefs.append('\n')
                                fileRefs.append(result.reference.refText)
                            }
                        }
                    }
                }

                is GatewayStageOutcome.Rejected ->
                    rejectAfterCleanup(GatewayRpcException(result.safeMessage))
                is GatewayStageOutcome.Ambiguous -> {
                    // Known earlier paths can be detached, but this lost
                    // acknowledgement may hide one more accepted path whose id
                    // never reached Android. The batch is review-required.
                    detachStagedImages(connection, binding.runtimeId, stagedImagePaths)
                    throw GatewayRpcException(
                        "The attachment may not have finished uploading. Check the session before retrying.",
                        requestMayHaveBeenAccepted = true,
                    )
                }
                GatewayStageOutcome.Unsupported -> rejectAfterCleanup(
                    GatewayRpcException("This Gateway does not accept that attachment. Update Hermes and try again."),
                )
            }
        }
        val typed = text.trim()
        // Desktop's buildContextText: file refs and typed text compose first,
        // and the image-only question is the fallback when nothing else is
        // there — never a branch that outranks a staged file ref.
        val wireText = listOf(fileRefs.toString(), typed)
            .filter(String::isNotBlank).joinToString("\n\n")
            .ifBlank { if (imageRefs.isNotEmpty()) IMAGE_ONLY_PROMPT else "" }
        require(wireText.isNotBlank())
        // The optimistic row carries the refs the gateway will persist, so the
        // live thumbnail renders immediately and the authoritative row that
        // replaces it is byte-identical.
        val optimisticText = listOf(wireText, imageRefs.joinToString("\n"))
            .filter(String::isNotBlank).joinToString("\n")
        return try {
            submitInternalLocked(
                binding,
                connection,
                wireText,
                optimisticText,
                queued,
                gatewayQueueMergeable = attachments.none { it is OutgoingAttachment.Image },
                interruptEpoch = interruptEpoch,
            )
        } catch (failure: Throwable) {
            if (failure is CancellationException || failure.isAmbiguousGatewayMutation()) throw failure
            rejectAfterCleanup(
                failure as? GatewayRpcException ?: GatewayRpcException("The message was not sent."),
            )
        }
    }

    private suspend fun stageOne(
        binding: SessionBinding,
        connection: ConnectionSnapshot,
        attachment: OutgoingAttachment,
    ): GatewayStageOutcome {
        return try {
            val result = when (attachment) {
                is OutgoingAttachment.Image -> connection.client.request(
                    "image.attach_bytes",
                    buildJsonObject {
                        // Attach RPCs resolve the session by id before staging;
                        // the desktop always sends its session's runtime id.
                        put("session_id", JsonPrimitive(binding.runtimeId))
                        put("content_base64", JsonPrimitive(attachment.contentBase64))
                        put("filename", JsonPrimitive(attachment.displayName))
                    },
                ).asObject("image.attach_bytes")

                is OutgoingAttachment.GenericFile -> connection.client.request(
                    "file.attach",
                    buildJsonObject {
                        put("session_id", JsonPrimitive(binding.runtimeId))
                        put("data_url", JsonPrimitive(attachment.dataUrl))
                        put("name", JsonPrimitive(attachment.displayName))
                    },
                ).asObject("file.attach")
            }
            synchronized(stateLock) { ensureCurrent(connection) }
            val refText = result.string("ref_text")
                ?: result.string("text")?.takeIf(String::isNotBlank)
                ?: "@file:${attachment.displayName}"
            GatewayStageOutcome.Staged(
                StagedAttachmentReference(refText = refText, gatewayPath = result.string("path")),
            )
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (failure.isUnsupportedGatewayCapability()) {
                markCapabilityUnsupported(GatewayOptionalCapability.Attachments, connection)
                GatewayStageOutcome.Unsupported
            } else if (failure.isAmbiguousGatewayMutation()) {
                GatewayStageOutcome.Ambiguous
            } else {
                GatewayStageOutcome.Rejected(safeGatewayTerminalError(failure.message ?: "The attachment was refused."))
            }
        }
    }

    /** Remove known image paths from the Gateway's shared pre-submit slot. */
    private suspend fun detachStagedImages(
        connection: ConnectionSnapshot,
        runtimeId: String,
        paths: List<String>,
    ): Boolean {
        if (paths.isEmpty()) return true
        var allDetached = true
        paths.asReversed().forEach { path ->
            try {
                val result = connection.client.request(
                    "image.detach",
                    buildJsonObject {
                        put("session_id", JsonPrimitive(runtimeId))
                        put("path", JsonPrimitive(path))
                    },
                ).asObject("image.detach")
                // `detached:false` means another same-session prompt already
                // consumed this path: cleanup did not reconcile our slot, so
                // the caller must not declare the batch retryable.
                if (result.boolean("detached") != true) allDetached = false
                synchronized(stateLock) { ensureCurrent(connection) }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                allDetached = false
            }
        }
        return allDetached
    }

    override suspend fun submit(
        durableId: String,
        text: String,
        queued: Boolean,
    ): GatewaySubmitOutcome {
        val prompt = text.trim()
        return submitInternal(durableId, prompt, prompt, queued)
    }

    private suspend fun submitInternal(
        durableId: String,
        wireText: String,
        optimisticText: String,
        queued: Boolean,
    ): GatewaySubmitOutcome {
        val interruptEpoch = submitInterruptEpoch(durableId)
        return submitMutexes.withLock(durableId) {
            require(wireText.isNotEmpty())
            val binding = ensureRuntime(durableId)
            val connection = connectionSnapshot()
            submitInternalLocked(
                binding,
                connection,
                wireText,
                optimisticText,
                queued,
                gatewayQueueMergeable = true,
                interruptEpoch = interruptEpoch,
            )
        }
    }

    private suspend fun submitInternalLocked(
        binding: SessionBinding,
        connection: ConnectionSnapshot,
        wireText: String,
        optimisticText: String,
        queued: Boolean,
        gatewayQueueMergeable: Boolean,
        interruptEpoch: Long,
    ): GatewaySubmitOutcome {
        // Null means the payload is queued behind this runtime's live turn: it
        // registers no optimistic state and so has nothing to roll back.
        val optimistic: OptimisticSubmit? = synchronized(stateLock) {
            ensureCurrent(connection)
            val currentRuntime = identities.runtimeFor(binding.durableId)
            if (currentRuntime != binding.runtimeId) {
                throw GatewayRpcException("Hermes did not activate this session.")
            }
            if (binding.runtimeId in activeRuntimeIds) {
                // An explicit queue payload belongs behind the live turn and
                // must not claim its local ownership.
                if (queued) return@synchronized null
                throw GatewayRpcException("Hermes is already working in this session.")
            }
            val now = clock()
            if (unscopedRuntimeId == null) {
                // Identifier-less events stay attributed to the most recent
                // submit only while no other live turn already owns the pin;
                // a second concurrent submit must not steal the attribution.
                unscopedRuntimeId = binding.runtimeId
                localSubmitStartedAtMillis = now
                unscopedTurnIsLive = false
            }
            activeRuntimeIds += binding.runtimeId
            localSubmitStartedAtByRuntime[binding.runtimeId] = now
            liveTurnRuntimeIds -= binding.runtimeId
            updateActiveTurnsLocked()
            val previousSession = cache.session(binding.durableId)
            val previousTranscript = cache.transcript(binding.durableId)
            val optimisticUser = UserTurn("local-user-${sequence.incrementAndGet()}", optimisticText, now)
            optimisticUserByRuntime[binding.runtimeId] = optimisticUser
            cache.appendEntry(binding.durableId, optimisticUser)
            clearProgress(binding.durableId, binding.runtimeId)
            previousSession?.let { session ->
                cache.upsertSession(
                    session.copy(
                        preview = wireText,
                        lastActiveAtMillis = now,
                        status = SessionStatus.Working,
                        activityStartedAtMillis = now,
                        messageCount = session.messageCount + 1,
                    ),
                )
            }
            OptimisticSubmit(previousSession, previousTranscript)
        }

        try {
            return turnDispatchMutexes.withLock(binding.durableId) {
                requireUninterruptedSubmit(binding.durableId, interruptEpoch)
                requestPromptSubmit(connection, binding.runtimeId, wireText, queued)
                synchronized(stateLock) {
                    ensureCurrent(connection)
                    val stoppedBeforeAcknowledgement =
                        (confirmedInterruptEpochByDurableId[binding.durableId] ?: 0L) > interruptEpoch
                    if (optimistic == null && !stoppedBeforeAcknowledgement) {
                        // Strip only @image: lines; a @file: ref is real prompt
                        // content and must stay readable in the queue row.
                        val displayText = optimisticText
                            .lineSequence()
                            .filterNot { line -> line.startsWith("@image:") }
                            .joinToString("\n")
                            .trim()
                        updateComposerStatus(binding.durableId, binding.runtimeId) { status ->
                            val occurrenceId = "gateway-queued-${sequence.incrementAndGet()}"
                            val head = status.gatewayQueuedPrompts.firstOrNull()
                            // Gateway merges text only while the queue is a single
                            // text envelope. Once another envelope exists, every
                            // later arrival remains separate.
                            val joinsOnlyEnvelope = gatewayQueueMergeable &&
                                head?.gatewayBatchMergeable == true &&
                                status.gatewayQueuedPrompts.all { it.gatewayBatchId == head.gatewayBatchId }
                            status.copy(
                                gatewayQueuedPrompts = status.gatewayQueuedPrompts + ComposerGatewayQueuedPrompt(
                                    id = occurrenceId,
                                    text = displayText,
                                    gatewayBatchId = if (joinsOnlyEnvelope) head.gatewayBatchId else occurrenceId,
                                    gatewayBatchMergeable = gatewayQueueMergeable,
                                ),
                            )
                        }
                    }
                    ephemeralSessions.remove(binding.durableId)
                }
                GatewaySubmitOutcome.Accepted
            }
        } catch (failure: Throwable) {
            val ambiguous = failure is CancellationException || failure.isAmbiguousGatewayMutation()
            if (optimistic != null) {
                synchronized(stateLock) {
                    // A definite, non-live rejection rolls its own submit back —
                    // not just the runtime that happens to own the event pin.
                    // Ambiguous acknowledgements still keep the optimistic row.
                    val canRollback = !ambiguous && binding.runtimeId !in liveTurnRuntimeIds
                    if (canRollback) {
                        releaseRuntimeGuard(binding.runtimeId)
                        localSubmitStartedAtByRuntime.remove(binding.runtimeId)
                        liveTurnRuntimeIds.remove(binding.runtimeId)
                        optimisticUserByRuntime.remove(binding.runtimeId)
                        cache.setTranscript(binding.durableId, optimistic.transcript)
                        optimistic.session?.let(cache::upsertSession)
                    }
                }
            }
            if (ambiguous) {
                // An RPC-local timeout/cancellation leaves the caller active and
                // is an ambiguous acknowledgement. Parent cancellation makes
                // this context inactive and must continue propagating.
                currentCoroutineContext().ensureActive()
                return GatewaySubmitOutcome.Ambiguous
            }
            throw failure
        }
    }

    private suspend fun requestPromptSubmit(
        connection: ConnectionSnapshot,
        runtimeId: String,
        prompt: String,
        queued: Boolean,
    ) {
        connection.client.request(
            "prompt.submit",
            buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("text", JsonPrimitive(prompt))
                if (queued) put("queued", JsonPrimitive(true))
            },
        )
    }

    override suspend fun interrupt(durableId: String) {
        when (requestInterrupt(durableId)) {
            GatewayInterruptOutcome.NotActive ->
                throw GatewayRpcException("Reopen this session before stopping Hermes.")
            else -> Unit
        }
    }

    /** One response in flight per pending request; a second tap is a no-op. */
    private val respondingKeys = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<PendingInputKey, Boolean>())

    override suspend fun respondToPendingInput(
        key: PendingInputKey,
        action: PendingInputAction,
    ): PendingInputResponse {
        // A miss is two different facts wearing the same face. The key carries
        // its own generation, so a key from a dead connection can never be in
        // this map — which is exactly why the generation fence below can never
        // see one, and why the miss has to be classified here instead.
        val request = mutablePendingInputs.value[key]
            ?: return synchronized(stateLock) {
                if (key in retiredKeys) PendingInputResponse.Resolved
                else PendingInputResponse.Unanswerable
            }
        // Generation fence: belt and braces for a key that is somehow in the
        // map under a generation this repository has already moved past.
        if (key.connectionGeneration != connectionGeneration) return PendingInputResponse.Unanswerable
        if (!respondingKeys.add(key)) return PendingInputResponse.Retryable
        try {
            val binding = try {
                ensureRuntime(request.durableSessionId)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                return PendingInputResponse.Retryable
            }
            val connection = connectionSnapshot()
            val result = try {
                when (action) {
                    is PendingInputAction.ClarifyAnswer -> {
                        if (action.cancelBatch) {
                            // Batch-wide cancel is exactly the empty no-qid answer.
                            connection.client.request(
                                "clarify.respond",
                                buildJsonObject {
                                    put("request_id", JsonPrimitive(key.requestId))
                                    put("answer", JsonPrimitive(""))
                                },
                            )
                        } else {
                            var last: JsonElement = JsonNull
                            for ((questionId, answer) in action.answers) {
                                last = connection.client.request(
                                    "clarify.respond",
                                    buildJsonObject {
                                        put("request_id", JsonPrimitive(key.requestId))
                                        // Singles carry no question_id at all; an
                                        // empty-key entry would read as a batch
                                        // answer for an unknown qid.
                                        if (questionId.isNotEmpty()) {
                                            put("question_id", JsonPrimitive(questionId))
                                        }
                                        put("answer", JsonPrimitive(answer))
                                    },
                                )
                            }
                            last
                        }
                    }

                    is PendingInputAction.ApprovalChoice -> {
                        if (action.choice !in (request as? ApprovalPending)?.choices.orEmpty()) {
                            return PendingInputResponse.Retryable
                        }
                        runCatching {
                            connection.client.request(
                                "approval.received",
                                buildJsonObject {
                                    put("session_id", JsonPrimitive(binding.runtimeId))
                                    put("request_id", JsonPrimitive(key.requestId))
                                },
                            )
                        }
                        connection.client.request(
                            "approval.respond",
                            buildJsonObject {
                                put("session_id", JsonPrimitive(binding.runtimeId))
                                put("request_id", JsonPrimitive(key.requestId))
                                put("choice", JsonPrimitive(action.choice))
                            },
                        )
                    }

                    is PendingInputAction.SudoPassword -> {
                        val password = action.password.concatToString()
                        try {
                            connection.client.request(
                                "sudo.respond",
                                buildJsonObject {
                                    put("request_id", JsonPrimitive(key.requestId))
                                    put("password", JsonPrimitive(password))
                                },
                            )
                        } finally {
                            action.password.fill(0.toChar())
                        }
                    }

                    is PendingInputAction.SecretValue -> {
                        val value = action.value.concatToString()
                        try {
                            connection.client.request(
                                "secret.respond",
                                buildJsonObject {
                                    put("request_id", JsonPrimitive(key.requestId))
                                    put("value", JsonPrimitive(value))
                                },
                            )
                        } finally {
                            action.value.fill(0.toChar())
                        }
                    }
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // Ambiguous transport: keep the request pending for explicit retry.
                return PendingInputResponse.Retryable
            }
            synchronized(stateLock) { ensureCurrent(connection) }
            val status = (result as? JsonObject)?.string("status")
            return when (status) {
                "expired" -> {
                    removePendingInput(key)
                    PendingInputResponse.Expired
                }
                null, "ok", "resolved" -> {
                    removePendingInput(key)
                    setStatus(request.durableSessionId, SessionStatus.Idle)
                    PendingInputResponse.Resolved
                }
                else -> PendingInputResponse.Retryable
            }
        } finally {
            respondingKeys.remove(key)
        }
    }

    private fun removePendingInput(key: PendingInputKey) {
        val next = mutablePendingInputs.value.minus(key)
        if (next.size != mutablePendingInputs.value.size) {
            mutablePendingInputs.value = next
            retire(listOf(key))
        }
    }

    /** Records requests this connection finished with. See [retiredKeys]. */
    private fun retire(keys: Collection<PendingInputKey>) {
        synchronized(stateLock) { keys.forEach { retiredKeys[it] = Unit } }
    }

    override suspend fun requestInterrupt(durableId: String): GatewayInterruptOutcome {
        val (binding, interruptEpoch) = synchronized(stateLock) {
            val runtimeId = identities.runtimeFor(durableId) ?: return GatewayInterruptOutcome.NotActive
            val session = cache.session(durableId)
            if (session?.status == SessionStatus.NeedsInput) return GatewayInterruptOutcome.NeedsInput
            // A live app-submitted or remotely reported turn is interruptible
            // regardless of which runtime owns the identifier-less event pin.
            if (runtimeId !in activeRuntimeIds) {
                return GatewayInterruptOutcome.NotActive
            }
            val nextEpoch = (interruptEpochByDurableId[durableId] ?: 0L) + 1L
            interruptEpochByDurableId[durableId] = nextEpoch
            SessionBinding(durableId, runtimeId) to nextEpoch
        }
        val connection = try {
            connectionSnapshot()
        } catch (failure: Throwable) {
            return interruptPreflightFailureOutcome(failure)
        }
        val ordered = turnDispatchMutexes.withLockWithin(durableId, stopDispatchWaitMillis) {
            requestInterruptNow(binding, connection, interruptEpoch)
        }
        // prompt.submit can wait 30 minutes for a lost acknowledgement. Once
        // its frame has been sent, waiting longer does not improve ordering:
        // the Gateway reads this later interrupt from the same ordered socket.
        // The reader loop awaits dispatch before its next receive_text, and
        // dispatch runs a non-pooled method inline to completion on that
        // thread, so the submit handler finishes before the interrupt frame is
        // read at all. Neither prompt.submit nor session.interrupt is in
        // _LONG_HANDLERS; this ordering does NOT hold for methods that are.
        // Source: NousResearch/hermes-agent @ 3ca096de5f8183cb2e0ec23673f294d5978656a3,
        // tui_gateway/ws.py:339 (loop), :341 (receive_text), :392 (dispatch);
        // tui_gateway/server.py:198-362 (_LONG_HANDLERS), :2110-2147 (dispatch).
        return ordered ?: requestInterruptNow(binding, connection, interruptEpoch)
    }

    private suspend fun requestInterruptNow(
        binding: SessionBinding,
        connection: ConnectionSnapshot,
        interruptEpoch: Long,
    ): GatewayInterruptOutcome {
        var confirmed = false
        var markerOwner: Long? = null
        return try {
            synchronized(stateLock) {
                ensureCurrent(connection)
                // Mark before the suspend boundary: a terminal event can arrive
                // while the RPC acknowledgement is still in flight, and it must
                // retain the fact that this app dispatched the Stop request. A
                // prior local Stop owns the marker until it is consumed, so a
                // second rejected request cannot revoke that attribution.
                markerOwner = installLocalInterruptMarker(binding.runtimeId)
            }
            val result = connection.client.request("session.interrupt", objectParams("session_id", binding.runtimeId))
                .asObject("session.interrupt")
            val interrupted = result.string("status") == "interrupted"
            synchronized(stateLock) {
                ensureCurrent(connection)
                if (interrupted) {
                    confirmedInterruptEpochByDurableId[binding.durableId] = maxOf(
                        interruptEpoch,
                        confirmedInterruptEpochByDurableId[binding.durableId] ?: 0L,
                    )
                    clearGatewayQueuedPrompts(binding.durableId, binding.runtimeId)
                    confirmed = true
                }
            }
            if (interrupted) GatewayInterruptOutcome.Interrupted else GatewayInterruptOutcome.Rejected
        } catch (failure: Throwable) {
            interruptFailureOutcome(failure)
        } finally {
            // A dropped, rejected, ambiguous, or cancelled acknowledgement is
            // not proof that this runtime was stopped by this client. Keep the
            // marker only after an explicit interrupted reply; an event that
            // arrived while the RPC was pending has already consumed it.
            if (!confirmed) {
                markerOwner?.let { owner ->
                    synchronized(stateLock) { removeLocalInterruptMarker(binding.runtimeId, owner) }
                }
            }
        }
    }

    override suspend fun redirect(durableId: String, text: String): GatewayRedirectOutcome {
        val correction = text.trim()
        require(correction.isNotEmpty())
        val binding = try {
            ensureRuntime(durableId)
        } catch (failure: Throwable) {
            return redirectPreflightFailureOutcome(failure)
        }
        val connection = try {
            connectionSnapshot()
        } catch (failure: Throwable) {
            return redirectPreflightFailureOutcome(failure)
        }
        if (isCapabilityUnsupported(GatewayOptionalCapability.Redirect, connection)) {
            return GatewayRedirectOutcome.Unsupported
        }
        if (!ownsActiveUnscopedTurn(binding, connection)) return GatewayRedirectOutcome.Rejected

        return try {
            val result = connection.client.request(
                "session.redirect",
                buildJsonObject {
                    put("session_id", JsonPrimitive(binding.runtimeId))
                    put("text", JsonPrimitive(correction))
                },
            ).asObject("session.redirect")
            synchronized(stateLock) { ensureCurrent(connection) }
            when (result.string("status")) {
                "redirected" -> {
                    recordOptimisticCorrection(binding, connection, correction)
                    GatewayRedirectOutcome.Redirected
                }

                "queued" -> {
                    recordOptimisticCorrection(binding, connection, correction)
                    GatewayRedirectOutcome.QueuedByGateway
                }

                "rejected" -> GatewayRedirectOutcome.Rejected
                else -> GatewayRedirectOutcome.Failed
            }
        } catch (failure: Throwable) {
            if (failure.isUnsupportedGatewayCapability()) {
                markCapabilityUnsupported(GatewayOptionalCapability.Redirect, connection)
                GatewayRedirectOutcome.Unsupported
            } else {
                redirectFailureOutcome(failure)
            }
        }
    }

    override suspend fun steer(durableId: String, text: String): GatewaySteerOutcome {
        val correction = text.trim()
        require(correction.isNotEmpty())
        val binding = try {
            ensureRuntime(durableId)
        } catch (failure: Throwable) {
            return steerPreflightFailureOutcome(failure)
        }
        val connection = try {
            connectionSnapshot()
        } catch (failure: Throwable) {
            return steerPreflightFailureOutcome(failure)
        }
        if (isCapabilityUnsupported(GatewayOptionalCapability.Steer, connection)) {
            return GatewaySteerOutcome.Unsupported
        }
        if (!ownsActiveUnscopedTurn(binding, connection)) return GatewaySteerOutcome.Rejected

        return try {
            val result = connection.client.request(
                "session.steer",
                buildJsonObject {
                    put("session_id", JsonPrimitive(binding.runtimeId))
                    put("text", JsonPrimitive(correction))
                },
            ).asObject("session.steer")
            synchronized(stateLock) { ensureCurrent(connection) }
            when (result.string("status")) {
                "queued" -> {
                    recordOptimisticCorrection(binding, connection, correction)
                    GatewaySteerOutcome.QueuedByGateway
                }

                "rejected" -> GatewaySteerOutcome.Rejected
                else -> GatewaySteerOutcome.Failed
            }
        } catch (failure: Throwable) {
            if (failure.isUnsupportedGatewayCapability()) {
                markCapabilityUnsupported(GatewayOptionalCapability.Steer, connection)
                GatewaySteerOutcome.Unsupported
            } else {
                steerFailureOutcome(failure)
            }
        }
    }

    override suspend fun listProcesses(durableId: String): GatewayProcessListOutcome {
        val binding = try {
            ensureRuntime(durableId)
        } catch (failure: Throwable) {
            return processListPreflightFailureOutcome(failure)
        }
        val connection = try {
            connectionSnapshot()
        } catch (failure: Throwable) {
            return processListPreflightFailureOutcome(failure)
        }
        if (isCapabilityUnsupported(GatewayOptionalCapability.Processes, connection)) {
            return GatewayProcessListOutcome.Unsupported
        }
        return try {
            val result = connection.client.request("process.list", objectParams("session_id", binding.runtimeId))
                .asObject("process.list")
            val processes = parseGatewayProcesses(result) ?: return GatewayProcessListOutcome.Failed
            synchronized(stateLock) {
                ensureCurrent(connection)
                if (identities.runtimeFor(binding.durableId) != binding.runtimeId) {
                    return GatewayProcessListOutcome.Failed
                }
                updateComposerStatus(binding.durableId, binding.runtimeId) { status ->
                    status.copy(backgroundProcesses = processes)
                }
            }
            GatewayProcessListOutcome.Available(processes)
        } catch (failure: Throwable) {
            if (failure.isUnsupportedGatewayCapability()) {
                markCapabilityUnsupported(GatewayOptionalCapability.Processes, connection)
                GatewayProcessListOutcome.Unsupported
            } else {
                GatewayProcessListOutcome.Failed
            }
        }
    }

    override suspend fun killProcess(durableId: String, processId: String): GatewayProcessKillOutcome {
        val cleanProcessId = processId.trim()
        require(cleanProcessId.isNotEmpty())
        val binding = try {
            ensureRuntime(durableId)
        } catch (failure: Throwable) {
            return processKillPreflightFailureOutcome(failure)
        }
        val connection = try {
            connectionSnapshot()
        } catch (failure: Throwable) {
            return processKillPreflightFailureOutcome(failure)
        }
        if (isCapabilityUnsupported(GatewayOptionalCapability.Processes, connection)) {
            return GatewayProcessKillOutcome.Unsupported
        }
        // A process action is scoped, but it must not mutate a session while an
        // identifier-less turn is owned by another runtime.
        if (!canMutateBoundSession(binding, connection)) return GatewayProcessKillOutcome.Rejected
        return try {
            connection.client.request(
                "process.kill",
                buildJsonObject {
                    put("process_id", JsonPrimitive(cleanProcessId))
                    put("session_id", JsonPrimitive(binding.runtimeId))
                },
            )
            synchronized(stateLock) { ensureCurrent(connection) }
            GatewayProcessKillOutcome.Killed
        } catch (failure: Throwable) {
            if (failure.isUnsupportedGatewayCapability()) {
                markCapabilityUnsupported(GatewayOptionalCapability.Processes, connection)
                GatewayProcessKillOutcome.Unsupported
            } else if (failure.isAmbiguousGatewayMutation()) {
                GatewayProcessKillOutcome.Ambiguous
            } else {
                GatewayProcessKillOutcome.Failed
            }
        }
    }

    override suspend fun goalStatus(durableId: String): GatewayGoalStatusOutcome {
        val binding = try {
            ensureRuntime(durableId)
        } catch (failure: Throwable) {
            return goalStatusPreflightFailureOutcome(failure)
        }
        val connection = try {
            connectionSnapshot()
        } catch (failure: Throwable) {
            return goalStatusPreflightFailureOutcome(failure)
        }
        if (isCapabilityUnsupported(GatewayOptionalCapability.Goals, connection)) {
            return GatewayGoalStatusOutcome.Unsupported
        }
        return try {
            val result = connection.client.request(
                "slash.exec",
                buildJsonObject {
                    put("command", JsonPrimitive("goal status"))
                    put("session_id", JsonPrimitive(binding.runtimeId))
                },
            ).asObject("slash.exec")
            val rawText = result.jsonString("output") ?: return GatewayGoalStatusOutcome.Failed
            val safeText = safeGatewayStatusText(rawText).takeIf(String::isNotEmpty)
                ?: return GatewayGoalStatusOutcome.Failed
            val goal = synchronized(stateLock) {
                ensureCurrent(connection)
                if (identities.runtimeFor(binding.durableId) != binding.runtimeId) {
                    return GatewayGoalStatusOutcome.Failed
                }
                val parsed = parseGatewayGoalStatus(safeText, cache.session(binding.durableId)?.composerStatus?.goal)
                updateComposerStatus(binding.durableId, binding.runtimeId) { status -> status.copy(goal = parsed) }
                parsed
            }
            GatewayGoalStatusOutcome.Available(goal)
        } catch (failure: Throwable) {
            if (failure.isUnsupportedGatewayCapability()) {
                markCapabilityUnsupported(GatewayOptionalCapability.Goals, connection)
                GatewayGoalStatusOutcome.Unsupported
            } else {
                GatewayGoalStatusOutcome.Failed
            }
        }
    }

    override suspend fun renameSession(durableId: String, title: String): String {
        require(durableId.isNotBlank()) { "Cannot rename a session without a durable id." }
        val trimmed = title.trim()
        val runtimeId = synchronized(stateLock) { identities.runtimeFor(durableId) }
        val client = clientFlow.value

        // Resolution rule:
        // Live runtime id -> session.title RPC
        // Persisted row (or empty title to clear) -> REST PATCH /api/sessions/{id}
        if (runtimeId != null && trimmed.isNotEmpty() && client != null) {
            try {
                val params = buildJsonObject {
                    put("session_id", JsonPrimitive(runtimeId))
                    put("title", JsonPrimitive(trimmed))
                }
                val result = client.request("session.title", params)
                val resolvedTitle = (result as? JsonObject)?.string("title") ?: trimmed
                val existing = cache.session(durableId)
                if (existing != null) {
                    cache.upsertSession(existing.copy(title = resolvedTitle))
                } else {
                    cache.upsertSession(
                        SessionSummary(
                            id = durableId,
                            title = resolvedTitle,
                            preview = "",
                            lastActiveAtMillis = clock(),
                        ),
                    )
                }
                return resolvedTitle
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // Fall through to the REST path rather than report the RPC's
                // refusal. Desktop does exactly this, and says why
                // (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:86-92`
                // @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`): the socket can
                // simply be mid-reconnect, and REST still renames any row that
                // already has a persisted one. The rename the person asked for
                // is what matters; which lane carried it is not. If REST cannot
                // do it either, what they are told is REST's answer — the last
                // thing that actually failed — mapped below.
            }
        }

        val profile = cache.session(durableId)?.remoteProfile ?: synchronized(stateLock) { profileRouting.activeProfile }
        val result = rest.updateSession(
            sessionId = durableId,
            title = trimmed,
            profile = profile,
        )
        return when (result) {
            is GatewayRestResult.Success -> {
                val resolvedTitle = result.value.title
                val existing = cache.session(durableId)
                if (existing != null) {
                    cache.upsertSession(existing.copy(title = resolvedTitle))
                } else {
                    cache.upsertSession(
                        SessionSummary(
                            id = durableId,
                            title = resolvedTitle,
                            preview = "",
                            lastActiveAtMillis = clock(),
                        ),
                    )
                }
                resolvedTitle
            }
            is GatewayRestResult.Failed -> {
                val safeMessage = when (result.statusCode) {
                    400 -> "Rename failed. Try a different title."
                    404 -> "Rename failed. That session is no longer available."
                    else -> "Rename failed. Check the Gateway and try again."
                }
                throw GatewayRpcException(safeMessage, statusCode = result.statusCode)
            }
        }
    }

    override suspend fun deleteSession(durableId: String) {
        require(durableId.isNotBlank()) { "Cannot delete a session without a durable id." }
        val runtimeId = synchronized(stateLock) { identities.runtimeFor(durableId) }
        val client = clientFlow.value
        val profile = cache.session(durableId)?.remoteProfile ?: synchronized(stateLock) { profileRouting.activeProfile }

        if (runtimeId != null && client != null) {
            val isRunning = synchronized(stateLock) {
                liveTurnRuntimeIds.contains(runtimeId) || activeRuntimeIds.contains(runtimeId)
            }
            if (isRunning) {
                throw GatewayRpcException("Cannot delete a running session. Stop the turn first and try again.")
            }
            try {
                val params = buildJsonObject {
                    put("session_id", JsonPrimitive(runtimeId))
                    if (profile != null) put("profile", JsonPrimitive(profile))
                }
                client.request("session.delete", params)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                if (failure is GatewayRpcError) {
                    if (failure.code == 4023) {
                        throw GatewayRpcException("Cannot delete a running session. Stop the turn first and try again.")
                    }
                    if (failure.code != 4007) {
                        throw GatewayRpcException("Delete failed. Check the Gateway and try again.")
                    }
                } else if (failure is GatewayRpcException) {
                    throw failure
                } else {
                    throw GatewayRpcException("Delete failed. Check the Gateway and try again.")
                }
            }
            synchronized(stateLock) {
                identities.unbindRuntime(runtimeId)
                assistantByRuntime.remove(runtimeId)
                reasoningByRuntime.remove(runtimeId)
                toolsByRuntime.remove(runtimeId)
                progressRuntimeIds.remove(runtimeId)
                activeRuntimeIds.remove(runtimeId)
                liveTurnRuntimeIds.remove(runtimeId)
                localSubmitStartedAtByRuntime.remove(runtimeId)
                ephemeralSessions.remove(durableId)
                branchByDurableId.remove(durableId)
                worktreeByDurableId.remove(durableId)
            }
            cache.removeSession(durableId)
            return
        }

        val result = rest.deleteSession(sessionId = durableId, profile = profile)
        when (result) {
            is GatewayRestResult.Success -> {
                cache.removeSession(durableId)
            }
            is GatewayRestResult.Failed -> {
                if (result.statusCode == 404) {
                    cache.removeSession(durableId)
                } else {
                    throw GatewayRpcException("Delete failed. Check the Gateway and try again.", statusCode = result.statusCode)
                }
            }
        }
    }

    /**
     * Pin or unpin, addressed by the id the row currently carries.
     *
     * Either id would do. `set_session_pinned` flips the whole compression
     * lineage as a unit — "the whole compression chain is flipped as a unit, so
     * pinning the surfaced tip protects the root (and vice-versa) no matter
     * which id the caller holds" (`hermes_state.py:10877-10888` @ `3ca096de`)
     * — and the list projects the *root's* `pinned` onto the tip it surfaces
     * (`:11596-11603`, where `pinned` is not among the fields the tip replaces).
     * Desktop happens to PATCH the root (`store/session.ts:352-356`); this
     * PATCHes whatever the row is filed under, which is the id the reader
     * pressed on. What actually has to know about the lineage is the fence,
     * and it is keyed under both.
     */
    override suspend fun setSessionPinned(durableId: String, pinned: Boolean) {
        require(durableId.isNotBlank()) { "Cannot pin a session without a durable id." }
        val previous = cache.session(durableId)
            ?: throw GatewayRpcException(PIN_FAILED)
        fenceFlagWrite(previous, PendingFlagWrite(pinned = pinned, atMillis = clock()))
        cache.upsertSession(previous.copy(pinned = pinned))
        val result = rest.updateSession(
            sessionId = durableId,
            pinned = pinned,
            profile = mutationProfile(durableId),
        )
        if (result is GatewayRestResult.Failed) {
            releaseFlagWrite(previous)
            cache.upsertSession(previous)
            throw GatewayRpcException(PIN_FAILED, statusCode = result.statusCode)
        }
    }

    /**
     * Archive or restore in place.
     *
     * The flag moves; the row does not. `SessionCache.removeSession` is the
     * cache's one tombstone and it means *gone* — it also drops the row's
     * rehome alias, its transcript and its project membership
     * (`data/session/SessionCache.kt:183-203`), none of which a reversible
     * verb may destroy and none of which a rollback could put back. The live
     * list stops showing an archived row because `buildSessionRows` filters
     * the pool it draws from (`data/session/SessionGrouping.kt:112`), which is
     * where Desktop draws the same line (`sidebar/index.tsx:488-495` @
     * `3ca096de`: "Archived is a view of its own set rather than a filter over
     * this one").
     */
    override suspend fun setSessionArchived(durableId: String, archived: Boolean) {
        require(durableId.isNotBlank()) { "Cannot archive a session without a durable id." }
        val previous = cache.session(durableId)
            ?: throw GatewayRpcException(if (archived) ARCHIVE_FAILED else UNARCHIVE_FAILED)
        fenceFlagWrite(previous, PendingFlagWrite(archived = archived, atMillis = clock()))
        cache.upsertSession(previous.copy(archived = archived))
        val result = rest.updateSession(
            sessionId = durableId,
            archived = archived,
            profile = mutationProfile(durableId),
        )
        if (result is GatewayRestResult.Failed) {
            releaseFlagWrite(previous)
            cache.upsertSession(previous)
            throw GatewayRpcException(
                if (archived) ARCHIVE_FAILED else UNARCHIVE_FAILED,
                statusCode = result.statusCode,
            )
        }
    }

    override suspend fun setSessionUnread(durableId: String, unread: Boolean) {
        require(durableId.isNotBlank()) { "Cannot mark a session without a durable id." }
        val previous = cache.session(durableId)
            ?: throw GatewayRpcException(UNREAD_FAILED)
        fenceFlagWrite(previous, PendingFlagWrite(unread = unread, atMillis = clock()))
        // Both unread sources move in one action, in Desktop's order: the
        // transient finished-turn dot is cleared with the watermark rather than
        // after it (`session-actions-menu.tsx:316-332` @ `3ca096de`), so no
        // refresh in between can repaint what was just dismissed.
        cache.upsertSession(
            previous.copy(
                unread = unread,
                status = when {
                    !unread && previous.status == SessionStatus.Unread -> SessionStatus.Idle
                    else -> previous.status
                },
            ),
        )
        val result = rest.updateSession(
            sessionId = durableId,
            unread = unread,
            profile = mutationProfile(durableId),
        )
        if (result is GatewayRestResult.Failed) {
            releaseFlagWrite(previous)
            cache.upsertSession(previous)
            throw GatewayRpcException(UNREAD_FAILED, statusCode = result.statusCode)
        }
    }

    override suspend fun loadArchivedSessions() =
        refreshMutex.withLock { readSessionPages(SessionPageRead.Refresh, SessionPool.Archived) }

    /**
     * The `profile` a row's own mutation rides: its owner, or the scope the
     * sidebar is standing in when nothing is known about the row. The same rule
     * rename and delete already apply.
     */
    private fun mutationProfile(durableId: String): String? =
        cache.session(durableId)?.remoteProfile ?: synchronized(stateLock) { profileRouting.activeProfile }

    /**
     * Record an optimistic flag write under every id a page can name this row
     * by, and arm the reconciliation that retires it.
     *
     * Expired entries are swept here as well as at merge time: a compression
     * re-home leaves the old tip's key behind — [applyPendingFlagWrites] only
     * ever sees the ids of a row a page actually named — and without a sweep
     * the map would grow by one entry per compression until the connection
     * changed.
     */
    private fun fenceFlagWrite(row: SessionSummary, write: PendingFlagWrite) {
        synchronized(stateLock) {
            pruneExpiredFlagWrites()
            row.flagWriteKeys().forEach { key ->
                pendingFlagWrites[key] = pendingFlagWrites[key]?.merge(write) ?: write
            }
        }
        armFlagWriteReconcile()
    }

    /** Drop a fence whose write was refused: the backend kept the old value. */
    private fun releaseFlagWrite(row: SessionSummary) {
        synchronized(stateLock) { row.flagWriteKeys().forEach(pendingFlagWrites::remove) }
    }

    private fun pruneExpiredFlagWrites() {
        val now = clock()
        pendingFlagWrites.entries.removeAll { now - it.value.atMillis >= FLAG_WRITE_GUARD_MILLIS }
    }

    /**
     * Ask the Gateway again once the guard lapses.
     *
     * Desktop keeps its guard as a read-side projection
     * (`store/session-dot-state.ts:142-156`, `sidebar/session-index.ts:83-88` @
     * `3ca096de`), so when the window closes the row it renders is the server's
     * again with nothing further to do. This fence writes through to
     * [SessionCache] instead, which is the backend-authoritative store — so a
     * write the Gateway acknowledged but has never echoed in a list page would
     * otherwise sit there as this client's own opinion with nothing scheduled
     * to correct it. One rescan when the guard expires is that schedule.
     *
     * Two rules keep that schedule honest:
     *
     * - **The job re-arms; it is not one-shot.** A second write made inside the
     *   first write's window used to get no wake-up of its own: the running job
     *   fired at the *first* fence's expiry, pruned that entry and rescanned,
     *   and the newer fence then lapsed with nothing scheduled — which is the
     *   hole this function exists to close. So it wakes at the earliest fence
     *   still outstanding, and re-arms from whatever is left when it is done.
     * - **Only for a write a live page can bring back.** See
     *   [PendingFlagWrite.confirmableByLivePage]: an archive can only expire,
     *   so arming for it would spend one list read per profile leg on an answer
     *   that cannot mention the row.
     */
    private fun armFlagWriteReconcile() {
        synchronized(stateLock) { armFlagWriteReconcileLocked() }
    }

    private fun armFlagWriteReconcileLocked() {
        if (flagWriteReconcileJob?.isActive == true) return
        val now = clock()
        val due = pendingFlagWrites.values
            .filter(PendingFlagWrite::confirmableByLivePage)
            .minOfOrNull { it.atMillis + FLAG_WRITE_GUARD_MILLIS - now }
        if (due == null) {
            flagWriteReconcileJob = null
            return
        }
        flagWriteReconcileJob = scope.launch {
            delay(due.coerceAtLeast(0L))
            // Only when something was still unconfirmed when the guard ran
            // out: a fence a page already agreed with retired itself, and a
            // refresh nobody asked for is a request nobody needed.
            val unconfirmed = synchronized(stateLock) {
                val outstanding = pendingFlagWrites.values.any(PendingFlagWrite::confirmableByLivePage)
                pruneExpiredFlagWrites()
                outstanding
            }
            if (unconfirmed) runCatching { rescanSessions() }
            // Only this job may retire this job. A connection change cancels and
            // nulls the field under the same lock, and a write made after that
            // arms a fresh one — clearing the field blind would orphan it and
            // schedule a second rescan for the same fence.
            val self = coroutineContext[Job]
            synchronized(stateLock) {
                if (flagWriteReconcileJob === self) {
                    flagWriteReconcileJob = null
                    armFlagWriteReconcileLocked()
                }
            }
        }
    }

    /**
     * Let an unconfirmed write of ours outrank a list page that predates it,
     * and retire the fence the moment a page agrees or the guard expires.
     */
    private fun applyPendingFlagWrites(row: SessionSummary): SessionSummary {
        val keys = row.flagWriteKeys()
        val write = keys.firstNotNullOfOrNull { pendingFlagWrites[it] } ?: return row
        if (write.isConfirmedBy(row) || clock() - write.atMillis >= FLAG_WRITE_GUARD_MILLIS) {
            keys.forEach(pendingFlagWrites::remove)
            return row
        }
        return row.copy(
            pinned = write.pinned ?: row.pinned,
            unread = write.unread ?: row.unread,
            archived = write.archived ?: row.archived,
        )
    }

    private fun isCapabilityUnsupported(
        capability: GatewayOptionalCapability,
        connection: ConnectionSnapshot,
    ): Boolean = synchronized(stateLock) {
        ensureCurrent(connection)
        capability in unsupportedCapabilities
    }

    private fun markCapabilityUnsupported(
        capability: GatewayOptionalCapability,
        connection: ConnectionSnapshot,
    ) {
        synchronized(stateLock) {
            if (connection.generation == connectionGeneration && clientFlow.value === connection.client) {
                unsupportedCapabilities += capability
            }
        }
    }

    private fun ownsActiveUnscopedTurn(binding: SessionBinding, connection: ConnectionSnapshot): Boolean =
        synchronized(stateLock) {
            ensureCurrent(connection)
            identities.runtimeFor(binding.durableId) == binding.runtimeId &&
                binding.runtimeId in activeRuntimeIds
        }

    private fun canMutateBoundSession(binding: SessionBinding, connection: ConnectionSnapshot): Boolean =
        synchronized(stateLock) {
            ensureCurrent(connection)
            identities.runtimeFor(binding.durableId) == binding.runtimeId
        }

    private fun recordOptimisticCorrection(
        binding: SessionBinding,
        connection: ConnectionSnapshot,
        text: String,
    ) {
        synchronized(stateLock) {
            ensureCurrent(connection)
            if (identities.runtimeFor(binding.durableId) != binding.runtimeId) return
            val correction = UserTurn(
                id = "local-correction-${sequence.incrementAndGet()}",
                text = text,
                atMillis = clock(),
            )
            optimisticCorrectionsByRuntime.getOrPut(binding.runtimeId, ::mutableListOf) += correction
            cache.appendEntry(binding.durableId, correction)
        }
    }

    private suspend fun redirectFailureOutcome(failure: Throwable): GatewayRedirectOutcome {
        if (failure is CancellationException) {
            currentCoroutineContext().ensureActive()
            return GatewayRedirectOutcome.Ambiguous
        }
        return if (failure.isAmbiguousGatewayMutation()) GatewayRedirectOutcome.Ambiguous else GatewayRedirectOutcome.Failed
    }

    private suspend fun steerFailureOutcome(failure: Throwable): GatewaySteerOutcome {
        if (failure is CancellationException) {
            currentCoroutineContext().ensureActive()
            return GatewaySteerOutcome.Ambiguous
        }
        return if (failure.isAmbiguousGatewayMutation()) GatewaySteerOutcome.Ambiguous else GatewaySteerOutcome.Failed
    }

    private suspend fun interruptFailureOutcome(failure: Throwable): GatewayInterruptOutcome {
        if (failure is CancellationException) {
            currentCoroutineContext().ensureActive()
            return GatewayInterruptOutcome.Ambiguous
        }
        return if (failure.isAmbiguousGatewayMutation()) GatewayInterruptOutcome.Ambiguous else GatewayInterruptOutcome.Failed
    }

    private fun redirectPreflightFailureOutcome(failure: Throwable): GatewayRedirectOutcome {
        if (failure is CancellationException) throw failure
        return GatewayRedirectOutcome.Failed
    }

    private fun steerPreflightFailureOutcome(failure: Throwable): GatewaySteerOutcome {
        if (failure is CancellationException) throw failure
        return GatewaySteerOutcome.Failed
    }

    private fun interruptPreflightFailureOutcome(failure: Throwable): GatewayInterruptOutcome {
        if (failure is CancellationException) throw failure
        return GatewayInterruptOutcome.Failed
    }

    private fun processListPreflightFailureOutcome(failure: Throwable): GatewayProcessListOutcome {
        if (failure is CancellationException) throw failure
        return GatewayProcessListOutcome.Failed
    }

    private fun processKillPreflightFailureOutcome(failure: Throwable): GatewayProcessKillOutcome {
        if (failure is CancellationException) throw failure
        return GatewayProcessKillOutcome.Failed
    }

    private fun goalStatusPreflightFailureOutcome(failure: Throwable): GatewayGoalStatusOutcome {
        if (failure is CancellationException) throw failure
        return GatewayGoalStatusOutcome.Failed
    }

    private suspend fun mutateLiveControl(
        durableId: String,
        key: String,
        value: String,
        modelSwitch: Boolean = false,
    ): ControlMutationResult {
        val binding = ensureRuntime(durableId)
        val connection = connectionSnapshot()
        return try {
            val result = connection.client.request(
                "config.set",
                buildJsonObject {
                    put("session_id", JsonPrimitive(binding.runtimeId))
                    put("key", JsonPrimitive(key))
                    put("value", JsonPrimitive(value))
                },
            ).asObject("config.set")
            synchronized(stateLock) { ensureCurrent(connection) }
            if (modelSwitch && result.boolean("deferred") == true) {
                ControlMutationResult.Deferred
            } else {
                ControlMutationResult.Applied
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (modelSwitch && failure.isLegacyBusyModelRefusal()) {
                ControlMutationResult.Deferred
            } else {
                ControlMutationResult.Rejected(controlFailureMessage(key))
            }
        }
    }

    /**
     * Merge a streamed `usage` object into the session's cached usage, the way
     * Desktop spreads it over the previous value
     * (`apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/
     * session-info.ts:403,439` and `.../message-stream.ts:383` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`): an absent key keeps the last
     * value rather than resetting it.
     */
    private fun applyStreamedUsage(durableId: String, payload: JsonObject) {
        val usageObj = payload.obj("usage") ?: return
        val nextUsage = parseSessionUsage(usageObj, cache.session(durableId)?.usage)
        cache.session(durableId)?.let { row ->
            val next = row.copy(usage = nextUsage)
            if (next != row) cache.upsertSession(next)
        }
    }

    private suspend fun ensureRuntime(durableId: String): SessionBinding {
        synchronized(stateLock) {
            identities.runtimeFor(durableId)?.let { return SessionBinding(durableId, it) }
        }
        val canonicalId = openSession(durableId)
        return synchronized(stateLock) {
            SessionBinding(
                canonicalId,
                identities.runtimeFor(canonicalId)
                    ?: throw GatewayRpcException("Hermes did not activate this session."),
            )
        }
    }

    /** Returns true when authoritative list metadata should be refreshed. */
    private fun applyEvent(event: GatewayEvent): Boolean {
        val payload = event.payload as? JsonObject ?: JsonObject(emptyMap())
        if (event.type == "session.reclaimed") {
            val reclaimedRuntime = payload.string("session_id")?.takeIf(String::isNotBlank) ?: return true
            val termination = reclaimedTurnTermination(payload.string("reason"))
            val mappedDurableId = identities.durableFor(reclaimedRuntime)
            if (mappedDurableId != null) {
                advanceLiveEventRevision(reclaimedRuntime)
                val durableId = payload.string("stored_session_id")
                    ?.takeIf(String::isNotBlank)
                    ?.let { rehomeDurableSession(mappedDurableId, it, reclaimedRuntime) }
                    ?: mappedDurableId
                settleStoppedRuntime(durableId, reclaimedRuntime, termination = termination)
                identities.unbindRuntime(reclaimedRuntime)
            }
            return true
        }

        val runtimeId = event.runtimeSessionId ?: unscopedRuntimeId ?: return false
        var durableId = identities.durableFor(runtimeId) ?: return false
        if (event.type in LIVE_RUNTIME_EVENT_TYPES) advanceLiveEventRevision(runtimeId)
        return when (event.type) {
            "gateway.ready" -> false
            "session.info" -> {
                val eventDurable = payload.string("stored_session_id")
                    ?: payload.string("session_key")
                    ?: payload.string("durable_id")
                val canonicalId = eventDurable?.takeIf(String::isNotBlank) ?: durableId
                val rehomed = canonicalId != durableId
                if (rehomed) durableId = rehomeDurableSession(durableId, canonicalId, runtimeId)
                val running = payload.boolean("running")
                if (running == true) {
                    markRuntimeLive(runtimeId)
                    ephemeralSessions.remove(durableId)
                }
                val branch = payload.sessionGitBranch()
                val worktreePath = payload.sessionWorktreePath()
                val reportsBranch = "branch" in payload || "git_branch" in payload
                val reportsWorktree = "cwd" in payload
                val usageObj = payload.obj("usage")
                val reportsUsage = usageObj != null
                val nextUsage = usageObj?.let { parseSessionUsage(it, cache.session(durableId)?.usage) }
                if (reportsBranch) {
                    if (branch == null) {
                        branchByDurableId.remove(durableId)
                    } else {
                        branchByDurableId[durableId] = branch
                    }
                }
                if (reportsWorktree) {
                    if (worktreePath == null) {
                        worktreeByDurableId.remove(durableId)
                    } else {
                        worktreeByDurableId[durableId] = worktreePath
                    }
                }
                if (reportsBranch || reportsWorktree || reportsUsage) {
                    cache.session(durableId)?.let { row ->
                        val next = row.copy(
                            gitBranch = if (reportsBranch) branch else row.gitBranch,
                            worktreePath = if (reportsWorktree) worktreePath else row.worktreePath,
                            usage = if (reportsUsage) nextUsage else row.usage,
                        )
                        if (next != row) cache.upsertSession(next)
                    }
                }
                reconcileSessionInfo(durableId, runtimeId, running, payload.status())
                applyStreamedApprovalMode(payload)
                projectComposerControls(durableId, payload)?.let(composerControlEvents::tryEmit)
                // The Gateway emits this settled snapshot after a turn ends but
                // before its queued next-turn prompt drains. Preserve the queue
                // while settling, then arm it only after settlement succeeds;
                // a pre-start running=false heartbeat is not a drain edge.
                val preserveGatewayQueue = running == false && hasGatewayQueuedPrompts(durableId)
                val settled = running == false && settleStoppedSessionInfo(
                    durableId,
                    runtimeId,
                    preserveGatewayQueue = preserveGatewayQueue,
                )
                if (settled && preserveGatewayQueue) armGatewayQueueDrain(durableId, runtimeId)
                if (running == false && !settled && !isLocallySubmitted(runtimeId)) {
                    releaseRuntimeGuard(runtimeId)
                }
                rehomed || settled
            }

            "session.usage" -> {
                applyStreamedUsage(durableId, payload)
                false
            }

            "message.start" -> {
                // A new turn on this runtime cannot inherit a previous turn's
                // locally requested Stop attribution.
                locallyRequestedInterruptRuntimeIds.remove(runtimeId)
                if ((payload.string("role") ?: "assistant") == "assistant") {
                    consumeGatewayQueuedPromptIfReady(durableId, runtimeId)
                    val turn = AssistantTurn(
                        id = payload.messageId() ?: "gateway-assistant-${sequence.incrementAndGet()}",
                        markdown = payload.contentText(),
                        atMillis = payload.timestamp(clock()),
                        streaming = true,
                    )
                    assistantByRuntime[runtimeId] = turn
                    cache.putEntry(durableId, turn)
                    clearProgress(durableId, runtimeId)
                    markRuntimeLive(runtimeId)
                    ephemeralSessions.remove(durableId)
                    setStatus(durableId, SessionStatus.Working)
                }
                false
            }

            "message.delta" -> {
                val current = assistantByRuntime[runtimeId] ?: AssistantTurn(
                    id = payload.messageId() ?: "gateway-assistant-${sequence.incrementAndGet()}",
                    markdown = "",
                    atMillis = clock(),
                    streaming = true,
                )
                val updated = current.copy(markdown = current.markdown + payload.deltaText(), streaming = true)
                assistantByRuntime[runtimeId] = updated
                cache.putEntry(durableId, updated)
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "message.complete" -> {
                clearPendingInputsForRuntime(runtimeId)
                // The authoritative end-of-turn figure. The Gateway stops and
                // joins its usage ticker *before* emitting this precisely so no
                // mid-turn `session.usage` tick can outlive it
                // (`tui_gateway/server.py:12820-12822,13431` @
                // `3ca096de5f8183cb2e0ec23673f294d5978656a3`); Desktop merges it
                // the same way (`apps/desktop/src/app/session/hooks/
                // use-message-stream/gateway-event/message-stream.ts:377-388`).
                applyStreamedUsage(durableId, payload)
                completeMessage(durableId, runtimeId, payload)
                true
            }

            "reasoning.delta", "reasoning.available" -> {
                applyReasoning(event.type, durableId, runtimeId, payload)
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "thinking.delta" -> {
                applyStatusUpdate(
                    durableId,
                    runtimeId,
                    buildJsonObject {
                        put("kind", JsonPrimitive("thinking"))
                        put("text", JsonPrimitive(payload.deltaText()))
                    },
                )
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "tool.start", "tool.progress", "tool.complete" -> {
                // A live tool must not repaint a session that is parked on a
                // required answer; NeedsInput survives tool progress.
                if (hasPendingInput(runtimeId)) return false
                sealReasoning(durableId, runtimeId, ToolState.Done)
                applyTool(event.type, durableId, runtimeId, payload)
                markRuntimeLive(runtimeId)
                ephemeralSessions.remove(durableId)
                setStatus(durableId, SessionStatus.Working)
                false
            }

            "status.update" -> {
                applyStatusUpdate(durableId, runtimeId, payload)
                if (payload.jsonString("kind")?.trim() == "process") {
                    scheduleProcessRefresh(durableId)
                }
                false
            }

            "error" -> {
                clearPendingInputsForRuntime(runtimeId)
                val current = assistantByRuntime.remove(runtimeId)
                val errorText = safeGatewayTerminalError(payload.string("error") ?: payload.string("message"))
                val failed = (current ?: AssistantTurn(
                    id = "gateway-error-${sequence.incrementAndGet()}",
                    markdown = "",
                    atMillis = payload.timestamp(clock()),
                )).copy(streaming = false, error = errorText)
                cache.putEntry(durableId, failed)
                sealReasoning(durableId, runtimeId, ToolState.Failed)
                sealTools(durableId, runtimeId, ToolState.Failed)
                optimisticUserByRuntime.remove(runtimeId)
                optimisticCorrectionsByRuntime.remove(runtimeId)
                clearConnectionScopedStatus(
                    durableId,
                    runtimeId,
                    preserveGatewayQueue = armGatewayQueueDrain(durableId, runtimeId),
                )
                setStatus(durableId, SessionStatus.Idle)
                ephemeralSessions.remove(durableId)
                releaseRuntimeGuard(runtimeId)
                turnOutcomeEvents.tryEmit(GatewayTurnOutcome(durableId, failed = true))
                true
            }

            "clarify.request", "approval.request", "sudo.request", "secret.request" -> {
                applyPendingInputEvent(event.type, durableId, runtimeId, payload)
                false
            }

            else -> false
        }
    }

    /** A parked answer must survive tool noise but dies with the turn. */
    private fun hasPendingInput(runtimeId: String): Boolean =
        mutablePendingInputs.value.keys.any { it.runtimeSessionId == runtimeId }

    private fun clearPendingInputsForRuntime(runtimeId: String) {
        val current = mutablePendingInputs.value
        val remaining = current.filterKeys { it.runtimeSessionId != runtimeId }
        if (remaining.size != current.size) {
            mutablePendingInputs.value = remaining
            // The turn these were blocking is over, so they are finished
            // business rather than requests anyone still owes an answer to.
            retire(current.keys - remaining.keys)
        }
    }

    private fun applyPendingInputEvent(
        type: String,
        durableId: String,
        runtimeId: String,
        payload: JsonObject,
) {
        val kind = when (type) {
            "clarify.request" -> PendingInputKind.Clarify
            "approval.request" -> PendingInputKind.Approval
            "sudo.request" -> PendingInputKind.Sudo
            else -> PendingInputKind.Secret
        }
        val requestId = payload.string("request_id")?.takeIf(String::isNotBlank) ?: return
        val key = PendingInputKey(connectionGeneration, runtimeId, requestId, kind)
        val request: PendingInputRequest = when (kind) {
            PendingInputKind.Clarify -> parseClarify(key, durableId, runtimeId, payload) ?: return
            PendingInputKind.Approval -> parseApproval(key, durableId, runtimeId, payload) ?: return
            PendingInputKind.Sudo -> SudoPending(key, durableId, runtimeId)
            PendingInputKind.Secret -> SecretPending(
                key = key,
                durableSessionId = durableId,
                runtimeSessionId = runtimeId,
                envVarLabel = payload.string("env_var").orEmpty().redactSafeBounded(),
                prompt = payload.string("prompt").orEmpty().redactSafeBounded(),
            )
        }
        // A newer same-kind request for this runtime supersedes the older one.
        val current = mutablePendingInputs.value
        val next = current
            .filterValues { existing ->
                !(existing.key.kind == kind && existing.key.runtimeSessionId == runtimeId)
            }
            .plus(key to request)
        mutablePendingInputs.value = next
        retire(current.keys - next.keys)
        setStatus(durableId, SessionStatus.NeedsInput)
    }

    private fun parseClarify(
        key: PendingInputKey,
        durableId: String,
        runtimeId: String,
        payload: JsonObject,
    ): ClarifyPending? {
        fun parseQuestion(obj: JsonObject): ClarifyQuestion? {
            val qid = obj.string("question_id")?.takeIf(String::isNotBlank) ?: return null
            val question = obj.string("question").orEmpty().redactSafeBounded()
            if (question.isBlank()) return null
            val choices = (obj["choices"] as? JsonArray)
                ?.mapNotNull { it as? JsonPrimitive }
                ?.mapNotNull { it.content }
                ?.map { it.normalizeChoice() }
                ?.filter(String::isNotEmpty)
                .orEmpty()
                .distinct()
                .take(MAX_PENDING_CHOICES)
            return ClarifyQuestion(qid, question, choices, obj.boolean("multi_select") == true)
        }
        val batch = (payload["questions"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull(::parseQuestion)
        if (!batch.isNullOrEmpty()) {
            // De-duplicate qids; a collision fails closed for the whole batch.
            val ids = batch.map { it.questionId }
            if (ids.size != ids.distinct().size || ids.size > MAX_PENDING_QUESTIONS) return null
            return ClarifyPending(key, durableId, runtimeId, questions = batch)
        }
        val question = payload.string("question").orEmpty().redactSafeBounded()
        if (question.isBlank()) return null
        val choices = (payload["choices"] as? JsonArray)
            ?.mapNotNull { it as? JsonPrimitive }
            ?.mapNotNull { it.content }
            ?.map { it.normalizeChoice() }
            ?.filter(String::isNotEmpty)
            .orEmpty()
            .distinct()
            .take(MAX_PENDING_CHOICES)
        return ClarifyPending(
            key = key,
            durableSessionId = durableId,
            runtimeSessionId = runtimeId,
            question = question,
            choices = choices,
            multiSelect = payload.boolean("multi_select") == true,
        )
    }

    private fun parseApproval(
        key: PendingInputKey,
        durableId: String,
        runtimeId: String,
        payload: JsonObject,
    ): ApprovalPending? {
        val command = listOfNotNull(
            payload.string("command"),
            payload.jsonString("description"),
        ).firstOrNull { it.isNotBlank() }?.redactSafeBounded() ?: return null
        val choices = (payload["choices"] as? JsonArray)
            ?.mapNotNull { it as? JsonPrimitive }
            ?.mapNotNull { it.content }
            ?.map { it.normalizeChoice() }
            ?.filter(String::isNotEmpty)
            .orEmpty()
            .distinct()
        // Without an offered choice list we cannot respond safely; fail closed.
        if (choices.isEmpty()) return null
        return ApprovalPending(
            key = key,
            durableSessionId = durableId,
            runtimeSessionId = runtimeId,
            command = command,
            description = payload.string("description").orEmpty().redactSafeBounded(),
            choices = choices.take(MAX_PENDING_CHOICES),
        )
    }

    private fun projectComposerControls(
        durableId: String,
        payload: JsonObject,
    ): SessionComposerControls? {
        val hasModel = "model" in payload
        val hasProvider = "provider" in payload
        val hasReasoning = "reasoning_effort" in payload
        val hasFast = "fast" in payload
        if (!hasModel && !hasProvider && !hasReasoning && !hasFast) return null

        val model = payload.string("model")?.trim().orEmpty()
        val provider = payload.string("provider")?.trim().orEmpty()
        return SessionComposerControls(
            durableId = durableId,
            selection = if ((hasModel || hasProvider) && model.isNotEmpty()) {
                ComposerModelSelection(model = model, provider = provider)
            } else {
                null
            },
            hasSelection = hasModel || hasProvider,
            reasoning = ReasoningEffort.fromWire(payload.string("reasoning_effort")),
            hasReasoning = hasReasoning,
            fast = payload.boolean("fast")?.let { if (it) FastMode.Fast else FastMode.Normal },
            hasFast = hasFast,
        )
    }

    /**
     * A pinned Desktop `session.info running=false` settles a turn when its
     * completion event was missed. An optimistic submit needs a bounded grace
     * first so the previous idle heartbeat cannot re-open the send guard before
     * the backend reports the new turn live.
     *
     * Source: NousResearch/hermes-agent @ 3ca096de5f8183cb2e0ec23673f294d5978656a3,
     * apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/session-info.ts:317-377.
     */
    private fun settleStoppedSessionInfo(
        durableId: String,
        runtimeId: String,
        preserveGatewayQueue: Boolean = false,
    ): Boolean {
        val locallySubmitted = isLocallySubmitted(runtimeId)
        val submittedAt = when {
            runtimeId in localSubmitStartedAtByRuntime -> localSubmitStartedAtByRuntime[runtimeId]
            unscopedRuntimeId == runtimeId -> localSubmitStartedAtMillis
            else -> null
        }
        val remainingGrace = submittedAt?.let {
            (PRE_START_FALSE_SETTLE_GRACE_MILLIS - (clock() - it)).coerceAtLeast(0)
        } ?: 0
        // The grace protects a submit whose turn has not reported live yet;
        // once events proved the turn live (or it was resumed live), an
        // authoritative running=false settles immediately.
        if (locallySubmitted && runtimeId !in liveTurnRuntimeIds && remainingGrace > 0) {
            return false
        }
        settleStoppedRuntime(
            durableId,
            runtimeId,
            preserveGatewayQueue = preserveGatewayQueue,
            termination = TurnTermination.SessionNoLongerRunning,
        )
        return true
    }

    /** A local submit or resume owns this runtime's pre-start settle edge. */
    private fun isLocallySubmitted(runtimeId: String): Boolean =
        runtimeId in localSubmitStartedAtByRuntime ||
            (unscopedRuntimeId == runtimeId && localSubmitStartedAtMillis != null)

    private fun markRuntimeLive(runtimeId: String) {
        activeRuntimeIds += runtimeId
        liveTurnRuntimeIds += runtimeId
        if (unscopedRuntimeId == runtimeId) {
            unscopedTurnIsLive = true
        }
        updateActiveTurnsLocked()
    }

    /** Session-info heartbeats contain state, not a complete session row. */
    private fun reconcileSessionInfo(
        durableId: String,
        runtimeId: String,
        running: Boolean?,
        reportedStatus: SessionStatus?,
    ) {
        val existing = cache.session(durableId) ?: return
        // A parked answer outranks a stale heartbeat: never repaint NeedsInput
        // as Working while its request is still pending.
        if (hasPendingInput(runtimeId)) {
            setStatus(durableId, SessionStatus.NeedsInput)
            return
        }
        val status = when (running) {
            true -> reportedStatus?.takeIf { it != SessionStatus.Idle } ?: SessionStatus.Working
            // A pre-start heartbeat must not wipe an optimistic local submit
            // for this runtime; settleStoppedSessionInfo owns that edge.
            false ->
                if (isLocallySubmitted(runtimeId)) {
                    existing.status
                } else {
                    SessionStatus.Idle
                }
            null -> reportedStatus ?: existing.status
        }
        if (status != existing.status) {
            cache.upsertSession(
                existing.copy(
                    status = status,
                    activityStartedAtMillis = if (status == SessionStatus.Working) {
                        existing.activityStartedAtMillis ?: clock()
                    } else {
                        null
                    },
                ),
            )
        }
    }

    private fun settleStoppedRuntime(
        durableId: String,
        runtimeId: String,
        preserveGatewayQueue: Boolean = false,
        termination: TurnTermination,
    ) {
        val settledTermination = if (consumeLocalInterruptMarker(runtimeId)) {
            TurnTermination.UserRequested
        } else {
            termination
        }
        clearPendingInputsForRuntime(runtimeId)
        assistantByRuntime.remove(runtimeId)?.let { partial ->
            cache.putEntry(durableId, partial.copy(streaming = false, termination = settledTermination))
        }
        sealReasoning(durableId, runtimeId, ToolState.Stopped)
        sealTools(durableId, runtimeId, ToolState.Stopped)
        optimisticUserByRuntime.remove(runtimeId)
        optimisticCorrectionsByRuntime.remove(runtimeId)
        clearConnectionScopedStatus(
            durableId,
            runtimeId,
            preserveGatewayQueue = preserveGatewayQueue,
        )
        setStatus(durableId, SessionStatus.Idle)
        releaseRuntimeGuard(runtimeId)
    }

    /** Convert Gateway-only lifecycle strings into an exhaustive UI-safe model. */
    private fun reclaimedTurnTermination(reason: String?): TurnTermination = when (reason) {
        "ws_orphan_reap" -> TurnTermination.WsOrphanReap
        "idle_timeout" -> TurnTermination.IdleTimeout
        "lru_evict" -> TurnTermination.LruEvict
        else -> TurnTermination.Reclaimed
    }

    private fun clearUnscopedRuntime() {
        unscopedRuntimeId = null
        localSubmitStartedAtMillis = null
        unscopedTurnIsLive = false
    }

    private fun releaseRuntimeGuard(runtimeId: String) {
        todoToolIdsByRuntime.remove(runtimeId)
        activeRuntimeIds.remove(runtimeId)
        localSubmitStartedAtByRuntime.remove(runtimeId)
        liveTurnRuntimeIds.remove(runtimeId)
        locallyRequestedInterruptRuntimeIds.remove(runtimeId)
        if (unscopedRuntimeId == runtimeId || unscopedRuntimeId == null) {
            // Exactly one remaining locally submitted runtime inherits the
            // identifier-less event pin, so its stream keeps flowing after the
            // previous owner settles (or after ambiguity left the pin
            // unowned). With zero or multiple candidates there is no safe
            // owner and identifier-less events stay unattributed.
            val inheriting = activeRuntimeIds.filter { it in localSubmitStartedAtByRuntime }.singleOrNull()
            if (inheriting != null && inheriting != runtimeId) {
                unscopedRuntimeId = inheriting
                localSubmitStartedAtMillis = localSubmitStartedAtByRuntime[inheriting]
                unscopedTurnIsLive = inheriting in liveTurnRuntimeIds
            } else if (unscopedRuntimeId == runtimeId) {
                clearUnscopedRuntime()
            }
        }
        updateActiveTurnsLocked()
    }

    private fun updateActiveTurnsLocked() {
        val active = buildSet {
            unscopedRuntimeId?.let { runtimeId ->
                if (localSubmitStartedAtMillis != null || unscopedTurnIsLive) {
                    identities.durableFor(runtimeId)?.let(::add)
                }
            }
            for (runtimeId in localSubmitStartedAtByRuntime.keys) {
                identities.durableFor(runtimeId)?.let(::add)
            }
            for (runtimeId in liveTurnRuntimeIds) {
                identities.durableFor(runtimeId)?.let(::add)
            }
        }
        mutableActiveTurns.value = active
    }

    private fun completeMessage(durableId: String, runtimeId: String, payload: JsonObject) {
        val current = assistantByRuntime.remove(runtimeId)
        val finalText = payload.contentText()
        val status = payload.string("status")?.lowercase()
        val interrupted = status == "interrupted" || payload.boolean("interrupted") == true
        val termination = if (interrupted) {
            if (consumeLocalInterruptMarker(runtimeId)) {
                TurnTermination.UserRequested
            } else {
                TurnTermination.InterruptedExternally
            }
        } else {
            null
        }
        val errorText = if (status == "error") {
            safeGatewayTerminalError(
                payload.string("error") ?: payload.string("message") ?: finalText,
            )
        } else {
            null
        }
        val keepFailedPartial = errorText != null && payload.boolean("partial") == true && current != null
        val completed = (current ?: AssistantTurn(
            id = payload.messageId() ?: "gateway-assistant-${sequence.incrementAndGet()}",
            markdown = finalText,
            atMillis = payload.timestamp(clock()),
        )).copy(
            markdown = when {
                errorText != null -> if (keepFailedPartial) current.markdown else ""
                finalText.isNotBlank() -> finalText
                else -> current?.markdown.orEmpty()
            },
            streaming = false,
            error = errorText,
            termination = termination,
        )
        cache.putEntry(durableId, completed)
        sealReasoning(durableId, runtimeId, if (errorText != null) ToolState.Failed else ToolState.Done)
        sealTools(
            durableId,
            runtimeId,
            when {
                errorText != null -> ToolState.Failed
                interrupted -> ToolState.Stopped
                else -> ToolState.Done
            },
        )
        optimisticUserByRuntime.remove(runtimeId)
        optimisticCorrectionsByRuntime.remove(runtimeId)
        clearConnectionScopedStatus(
            durableId,
            runtimeId,
            preserveGatewayQueue = !interrupted && armGatewayQueueDrain(durableId, runtimeId),
        )
        setStatus(durableId, SessionStatus.Idle)
        ephemeralSessions.remove(durableId)
        releaseRuntimeGuard(runtimeId)
        turnOutcomeEvents.tryEmit(GatewayTurnOutcome(durableId, failed = errorText != null))
    }

    private fun applyTool(type: String, durableId: String, runtimeId: String, payload: JsonObject) {
        val explicitId = payload.string("tool_id") ?: payload.string("tool_call_id") ?: payload.string("id")
        val todoIds = todoToolIdsByRuntime.getOrPut(runtimeId, ::mutableSetOf)
        val incomingToolName = payload.string("name")
        val knownTodoId = when {
            explicitId != null && explicitId in todoIds -> explicitId
            explicitId == null && (incomingToolName == null || incomingToolName == "todo") -> todoIds.singleOrNull()
            else -> null
        }
        // A named, identifier-less non-todo tool must never inherit the sole
        // live todo id. Correlation fallback is safe only when the name is
        // absent or explicitly `todo`.
        val isTodo = incomingToolName == "todo" || knownTodoId != null
        if (isTodo) {
            val todoId = knownTodoId ?: explicitId ?: "gateway-todo-${sequence.incrementAndGet()}"
            if (type == "tool.complete") todoIds.remove(todoId) else todoIds += todoId
            parseComposerTodosFromTool(payload)?.let { todos ->
                setComposerTodos(durableId, runtimeId, todos)
            }
            if (todoIds.isEmpty()) todoToolIdsByRuntime.remove(runtimeId)
            return
        }
        if (todoIds.isEmpty()) todoToolIdsByRuntime.remove(runtimeId)
        val tools = toolsByRuntime.getOrPut(runtimeId, ::mutableMapOf)
        val id = explicitId ?: tools.keys.singleOrNull() ?: "gateway-tool-${sequence.incrementAndGet()}"
        val previous = tools[id]
        val startedAt = previous?.startedAtMillis ?: clock()
        val elapsed = payload.primitive("duration_s")?.toDoubleOrNull()
            ?: payload.primitive("elapsed_seconds")?.toDoubleOrNull()
            ?: if (type == "tool.complete") (clock() - startedAt).coerceAtLeast(0) / 1_000.0 else previous?.elapsedSeconds
            ?: 0.0
        val toolName = payload.string("name").safeToolLabel(previous?.toolName ?: "Tool")
        val label = (payload.string("label") ?: payload.string("name"))
            .safeToolLabel(previous?.label ?: "Tool")
        val activity = ToolActivity(
            id = id,
            label = label,
            detail = payload.toolDetail(type).ifBlank { previous?.detail.orEmpty() },
            state = when (type) {
                "tool.complete" -> if (payload.toolFailed()) ToolState.Failed else ToolState.Done
                else -> ToolState.Running
            },
            elapsedSeconds = elapsed,
            toolName = toolName,
            argsText = payload.toolInputText() ?: previous?.argsText,
            resultText = payload["result"].safePayloadText() ?: previous?.resultText,
            inlineDiff = payload.jsonString("inline_diff")?.safePayloadText() ?: previous?.inlineDiff,
            startedAtMillis = startedAt,
        )
        cache.putEntry(durableId, activity)
        // Running snapshots are text-only, so completed structure stays here
        // until terminal history or connection settlement replaces it.
        tools[id] = activity
    }

    private fun applyReasoning(type: String, durableId: String, runtimeId: String, payload: JsonObject) {
        val previous = reasoningByRuntime[runtimeId]
        val now = clock()
        val startedAt = previous?.startedAtMillis ?: now
        val complete = type == "reasoning.available"
        val incoming = when (type) {
            "reasoning.delta", "thinking.delta" -> payload.deltaText()
            else -> payload.string("text") ?: payload.contentText()
        }.safePayloadText().orEmpty()
        if (incoming.isBlank() && previous == null) return
        val activity = ReasoningActivity(
            id = previous?.id ?: "gateway-reasoning-${sequence.incrementAndGet()}",
            text = when {
                complete && incoming.isNotBlank() -> incoming
                else -> previous?.text.orEmpty() + incoming
            },
            state = if (complete) ToolState.Done else ToolState.Running,
            startedAtMillis = startedAt,
            elapsedSeconds = if (complete) (now - startedAt).coerceAtLeast(0) / 1_000.0 else 0.0,
        )
        cache.putEntry(durableId, activity)
        if (complete) reasoningByRuntime.remove(runtimeId) else reasoningByRuntime[runtimeId] = activity
    }

    private fun sealReasoning(durableId: String, runtimeId: String, state: ToolState) {
        reasoningByRuntime.remove(runtimeId)?.let { activity ->
            cache.putEntry(
                durableId,
                activity.copy(
                    state = state,
                    elapsedSeconds = (clock() - (activity.startedAtMillis ?: clock())).coerceAtLeast(0) / 1_000.0,
                ),
            )
        }
    }

    private fun sealTools(durableId: String, runtimeId: String, state: ToolState) {
        toolsByRuntime.remove(runtimeId).orEmpty().values.forEach { activity ->
            cache.putEntry(
                durableId,
                if (activity.state == ToolState.Running) {
                    activity.copy(
                        state = state,
                        elapsedSeconds = (clock() - (activity.startedAtMillis ?: clock())).coerceAtLeast(0) / 1_000.0,
                    )
                } else {
                    activity
                },
            )
        }
    }

    private fun setStatus(durableId: String, status: SessionStatus) {
        cache.session(durableId)?.let { existing ->
            val now = clock()
            cache.upsertSession(
                existing.copy(
                    status = status,
                    lastActiveAtMillis = now,
                    activityStartedAtMillis = when (status) {
                        SessionStatus.Working -> existing.activityStartedAtMillis ?: now
                        else -> null
                    },
                ),
            )
        }
    }

    private fun applyStatusUpdate(durableId: String, runtimeId: String, payload: JsonObject) {
        val kind = payload.jsonString("kind")?.trim()?.takeIf(KNOWN_STATUS_UPDATE_KINDS::contains) ?: return
        val text = payload.jsonString("text")
            ?.let(::safeGatewayStatusText)
            ?.takeIf(String::isNotEmpty)
            ?: return
        cache.session(durableId)?.let { existing ->
            val progress = SessionProgress(kind, text)
            val previous = existing.composerStatus
            val status = when (kind) {
                "compacting" -> (previous ?: ComposerStatusState()).copy(isCompacting = true)
                "compacted" -> previous?.copy(isCompacting = false)
                "goal" -> (previous ?: ComposerStatusState()).copy(
                    goal = parseGatewayGoalStatus(text, previous?.goal),
                )
                else -> previous
            }
            cache.upsertSession(existing.copy(progress = progress, composerStatus = status))
            progressRuntimeIds += runtimeId
            if (status != null) composerStatusRuntimeIds += runtimeId
            advanceProgressEventRevision(runtimeId)
        }
    }

    private fun clearProgress(durableId: String, runtimeId: String) {
        progressRuntimeIds.remove(runtimeId)
        cache.session(durableId)?.let { existing ->
            val status = existing.composerStatus?.copy(isCompacting = false)
            if (existing.progress != null || status != existing.composerStatus) {
                cache.upsertSession(existing.copy(progress = null, composerStatus = status))
            }
        }
    }

    /** A terminal turn invalidates live rows; a completed todo may briefly land. */
    private fun clearConnectionScopedStatus(
        durableId: String,
        runtimeId: String,
        preserveFinishedTodos: Boolean = true,
        preserveGatewayQueue: Boolean = false,
    ) {
        progressRuntimeIds.remove(runtimeId)
        composerStatusRuntimeIds.remove(runtimeId)
        cache.session(durableId)?.let { existing ->
            val finishedTodos = if (preserveFinishedTodos) {
                existing.composerStatus?.todos
                    ?.takeIf(List<ComposerTodoStatus>::isNotEmpty)
                    ?.takeUnless(::todoListActive)
            } else {
                null
            }
            val gatewayQueue = if (preserveGatewayQueue) {
                existing.composerStatus?.gatewayQueuedPrompts.orEmpty()
            } else {
                emptyList()
            }
            val landed = ComposerStatusState(
                todos = finishedTodos.orEmpty(),
                gatewayQueuedPrompts = gatewayQueue,
            ).takeIf(ComposerStatusState::hasVisibleRows)
            if (existing.progress != null || existing.composerStatus != landed) {
                cache.upsertSession(existing.copy(progress = null, composerStatus = landed))
            }
        }
    }

    /**
     * A visible Gateway queue survives this terminal turn and consumes its head
     * server envelope at the next message.start. Reports whether a head exists.
     */
    private fun armGatewayQueueDrain(durableId: String, runtimeId: String): Boolean {
        val headBatchId = cache.session(durableId)
            ?.composerStatus
            ?.gatewayQueuedPrompts
            ?.firstOrNull()
            ?.gatewayBatchId
            ?: return false
        queuedPromptDrainReadyBatchIdsByRuntime[runtimeId] = headBatchId
        return true
    }

    private fun hasGatewayQueuedPrompts(durableId: String): Boolean =
        cache.session(durableId)?.composerStatus?.gatewayQueuedPrompts?.isNotEmpty() == true

    private fun consumeGatewayQueuedPromptIfReady(durableId: String, runtimeId: String) {
        val headBatchId = queuedPromptDrainReadyBatchIdsByRuntime.remove(runtimeId) ?: return
        mutateGatewayQueuedPrompts(durableId) { prompts ->
            if (prompts.firstOrNull()?.gatewayBatchId == headBatchId) {
                prompts.dropWhile { it.gatewayBatchId == headBatchId }
            } else {
                prompts
            }
        }
    }

    private fun clearGatewayQueuedPrompts(durableId: String, runtimeId: String) {
        queuedPromptDrainReadyBatchIdsByRuntime.remove(runtimeId)
        mutateGatewayQueuedPrompts(durableId) { emptyList() }
    }

    /** Rewrite an existing session's Gateway queue rows; an emptied stack drops the group. */
    private fun mutateGatewayQueuedPrompts(
        durableId: String,
        update: (List<ComposerGatewayQueuedPrompt>) -> List<ComposerGatewayQueuedPrompt>,
    ) {
        val existing = cache.session(durableId) ?: return
        val status = existing.composerStatus ?: return
        val next = status.copy(gatewayQueuedPrompts = update(status.gatewayQueuedPrompts))
            .takeIf(ComposerStatusState::hasVisibleRows)
        if (next != status) cache.upsertSession(existing.copy(composerStatus = next))
    }

    private fun setComposerTodos(
        durableId: String,
        runtimeId: String,
        todos: List<ComposerTodoStatus>,
    ) {
        todoClearJobsByDurableId.remove(durableId)?.cancel()
        updateComposerStatus(durableId, runtimeId) { current -> current.copy(todos = todos) }
        if (todos.isNotEmpty() && !todoListActive(todos)) {
            todoClearJobsByDurableId[durableId] = scope.launch {
                delay(FINISHED_TODO_LINGER_MILLIS)
                synchronized(stateLock) {
                    todoClearJobsByDurableId.remove(durableId)
                    cache.session(durableId)?.let { existing ->
                        val status = existing.composerStatus ?: return@let
                        if (status.todos == todos) {
                            // Drop only the todos; a Gateway queue row must not
                            // be deleted by this cleanup timer.
                            val cleared = status.copy(todos = emptyList()).takeIf(ComposerStatusState::hasVisibleRows)
                            cache.upsertSession(existing.copy(composerStatus = cleared))
                        }
                    }
                }
            }
        }
    }

    private fun updateComposerStatus(
        durableId: String,
        runtimeId: String,
        update: (ComposerStatusState) -> ComposerStatusState,
    ) {
        cache.session(durableId)?.let { existing ->
            val next = update(existing.composerStatus ?: ComposerStatusState())
            if (next != existing.composerStatus) {
                cache.upsertSession(existing.copy(composerStatus = next))
            }
            composerStatusRuntimeIds += runtimeId
        }
    }

    private fun connectionScopedRuntimeIds(): Set<String> = buildSet {
        unscopedRuntimeId?.let(::add)
        addAll(activeRuntimeIds)
        addAll(liveTurnRuntimeIds)
        addAll(localSubmitStartedAtByRuntime.keys)
        addAll(assistantByRuntime.keys)
        addAll(reasoningByRuntime.keys)
        addAll(toolsByRuntime.keys)
        addAll(optimisticUserByRuntime.keys)
        addAll(optimisticCorrectionsByRuntime.keys)
        addAll(progressRuntimeIds)
        addAll(composerStatusRuntimeIds)
        addAll(queuedPromptDrainReadyBatchIdsByRuntime.keys)
        addAll(locallyRequestedInterruptRuntimeIds.keys)
    }

    /**
     * Installs one attribution owner for a live runtime. A second Stop may
     * still reach the Gateway, but it must not own or remove the first marker.
     */
    private fun installLocalInterruptMarker(runtimeId: String): Long? {
        if (runtimeId in locallyRequestedInterruptRuntimeIds) return null
        return (++nextLocalInterruptMarkerOwner).also { owner ->
            locallyRequestedInterruptRuntimeIds[runtimeId] = owner
        }
    }

    /** Removes only the marker installed by this request, never another Stop's. */
    private fun removeLocalInterruptMarker(runtimeId: String, owner: Long) {
        if (locallyRequestedInterruptRuntimeIds[runtimeId] == owner) {
            locallyRequestedInterruptRuntimeIds.remove(runtimeId)
        }
    }

    /** Terminal and settle paths consume attribution exactly once. */
    private fun consumeLocalInterruptMarker(runtimeId: String): Boolean =
        locallyRequestedInterruptRuntimeIds.remove(runtimeId) != null

    private fun settleConnectionLoss(durableId: String, runtimeId: String) {
        assistantByRuntime[runtimeId]?.let { partial ->
            cache.putEntry(durableId, partial.copy(streaming = false))
        }
        sealReasoning(durableId, runtimeId, ToolState.Stopped)
        sealTools(durableId, runtimeId, ToolState.Stopped)
        optimisticCorrectionsByRuntime.remove(runtimeId)
        // A new connection cannot inherit a previous connection's landing
        // timer or completed task list.
        clearConnectionScopedStatus(
            durableId,
            runtimeId,
            preserveFinishedTodos = false,
            // Locally projected rows exist only after a successful queued
            // prompt.submit acknowledgement. Keep those accepted occurrences
            // for resume reconciliation: the Gateway exposes only its FIFO
            // head, so dropping the local tail here would be unrecoverable.
            preserveGatewayQueue = true,
        )
        cache.session(durableId)?.let { existing ->
            cache.upsertSession(
                existing.copy(
                    status = SessionStatus.Stalled,
                    lastActiveAtMillis = clock(),
                    activityStartedAtMillis = null,
                ),
            )
        }
    }

    private fun settleReconciliationFailure(durableId: String) {
        identities.runtimeFor(durableId)?.let { runtimeId ->
            releaseRuntimeGuard(runtimeId)
            identities.unbindRuntime(runtimeId)
        }
        cache.session(durableId)?.let { existing ->
            cache.upsertSession(
                existing.copy(
                    status = SessionStatus.Idle,
                    progress = SessionProgress(RECONCILIATION_FAILED_KIND, RECONCILIATION_FAILED_TEXT),
                    composerStatus = null,
                ),
            )
        }
    }

    private fun connectionScopedInflight(runtimeId: String): List<TranscriptEntry> = buildList {
        optimisticUserByRuntime[runtimeId]?.let(::add)
        addAll(optimisticCorrectionsByRuntime[runtimeId].orEmpty())
        assistantByRuntime[runtimeId]?.let(::add)
        reasoningByRuntime[runtimeId]?.let(::add)
        addAll(toolsByRuntime[runtimeId].orEmpty().values)
    }

    /**
     * Persisted history replaces every completed optimistic/live row. Only the
     * Gateway's `inflight` projection, plus events that raced ahead of that
     * snapshot on this same connection, may extend it.
     *
     * Source: NousResearch/hermes-agent @ 3ca096de5f8183cb2e0ec23673f294d5978656a3,
     * apps/desktop/src/app/session/hooks/use-session-actions/utils.ts:699-924 and
     * tui_gateway/server.py:8925-8986.
     */
    private fun reconcileAuthoritativeTranscript(
        history: List<TranscriptEntry>,
        runtimeId: String,
        projection: LiveSessionProjection,
        localLive: List<TranscriptEntry>,
    ): List<TranscriptEntry> {
        var reconciled = appendInflightProjection(history, runtimeId, projection, clock())
        if (projection.retainedFailure) return reconciled
        // `session.activate`/`session.resume` snapshots `running` and inflight
        // under the same upstream history lock. A reported terminal turn is
        // authoritative over any local stream that raced with the request.
        if (projection.running == false) return reconciled

        val localIsLive = runtimeId in activeRuntimeIds || localLive.any {
            (it is AssistantTurn && it.streaming) ||
                (it is ReasoningActivity && it.state == ToolState.Running) ||
                (it is ToolActivity && it.state == ToolState.Running)
        }
        if (!localIsLive) return reconciled

        localLive.filterIsInstance<UserTurn>().forEach { user ->
            if (!reconciled.openUserRunContains(user.text)) reconciled = reconciled + user
        }

        val localAssistant = localLive.filterIsInstance<AssistantTurn>().lastOrNull()
        val projectedAssistant = projection.inflight?.assistant.orEmpty()
        val localSupersedesProjection = projection.inflight?.corrections.isNullOrEmpty() &&
            localAssistant != null &&
            (projectedAssistant.isBlank() ||
                (localAssistant.markdown.startsWith(projectedAssistant) &&
                    localAssistant.markdown.length > projectedAssistant.length))
        if (localAssistant != null && (projection.inflight == null || localSupersedesProjection)) {
            if (localSupersedesProjection) {
                reconciled = reconciled.filterNot { it.id.startsWith("inflight-assistant-") }
            }
            reconciled = reconciled.replaceOrAppend(localAssistant)
        }

        localLive.filterIsInstance<ToolActivity>().forEach { tool ->
            reconciled = reconciled.replaceOrAppend(tool)
        }
        localLive.filterIsInstance<ReasoningActivity>().forEach { reasoning ->
            reconciled = reconciled.replaceOrAppend(reasoning)
        }
        return reconciled
    }

    private fun reconcileLiveState(
        runtimeId: String,
        projection: LiveSessionProjection,
        hasLocalLiveEntries: Boolean,
        reconciled: List<TranscriptEntry>,
        priorStatus: SessionStatus,
    ): SessionStatus {
        val localBusy = projection.running != false && (
            runtimeId in activeRuntimeIds || hasLocalLiveEntries && reconciled.any {
                (it is AssistantTurn && it.streaming) ||
                    (it is ReasoningActivity && it.state == ToolState.Running) ||
                    (it is ToolActivity && it.state == ToolState.Running)
            }
        )
        val busy = !projection.retainedFailure && (projection.busy || localBusy)

        if (busy) {
            if (unscopedRuntimeId == null && activeRuntimeIds.isEmpty()) {
                // A locally requested resume/activate snapshot is the only
                // non-submit path allowed to claim identifier-less events.
                // Scoped events alone must never retarget that pin after a
                // different turn has completed.
                unscopedRuntimeId = runtimeId
                localSubmitStartedAtMillis = null
                unscopedTurnIsLive = true
            }
            markRuntimeLive(runtimeId)
            projection.inflight?.user?.takeIf(String::isNotBlank)?.let { user ->
                optimisticUserByRuntime[runtimeId] = UserTurn(
                    id = "inflight-user-$runtimeId",
                    text = user,
                    atMillis = projection.inflight.atMillis,
                )
            }
            reconciled.filterIsInstance<AssistantTurn>().lastOrNull { it.streaming }?.let { assistant ->
                assistantByRuntime[runtimeId] = assistant
            }
            reconciled.filterIsInstance<ReasoningActivity>().lastOrNull { it.state == ToolState.Running }
                ?.let { reasoning -> reasoningByRuntime[runtimeId] = reasoning }
            return projection.status?.takeIf { it != SessionStatus.Idle } ?: SessionStatus.Working
        }

        if (projection.hasAuthoritativeState) {
            assistantByRuntime.remove(runtimeId)
            reasoningByRuntime.remove(runtimeId)
            toolsByRuntime.remove(runtimeId)
            optimisticUserByRuntime.remove(runtimeId)
            optimisticCorrectionsByRuntime.remove(runtimeId)
            releaseRuntimeGuard(runtimeId)
            return projection.status ?: SessionStatus.Idle
        }
        return priorStatus
    }

    private fun rehomeDurableSession(fromId: String, targetId: String, runtimeId: String): String {
        if (fromId == targetId) return fromId
        val existing = cache.session(targetId) ?: cache.session(fromId)
        val entries = mergeHistoryWithLiveEntries(
            cache.transcript(fromId),
            cache.transcript(targetId),
        )
        val row = existing?.copy(id = targetId)
            ?: SessionSummary(targetId, "New session", "", clock())
        cache.rehomeSession(fromId, row, entries)
        if (ephemeralSessions.remove(fromId)) ephemeralSessions += targetId
        branchByDurableId.remove(fromId)?.let { branchByDurableId[targetId] = it }
        worktreeByDurableId.remove(fromId)?.let { worktreeByDurableId[targetId] = it }
        identities.bind(targetId, runtimeId)
        rehomeEvents.tryEmit(SessionRehome(fromId, targetId))
        return targetId
    }

    private fun connectionSnapshot(): ConnectionSnapshot {
        val client = clientFlow.value ?: throw GatewayRpcException("Connect to a Gateway first.")
        return synchronized(stateLock) {
            if (clientFlow.value !== client) throw GatewayRpcException("The gateway connection changed.")
            ConnectionSnapshot(client, connectionGeneration)
        }
    }

    private fun ensureCurrent(connection: ConnectionSnapshot) {
        if (connection.generation != connectionGeneration || clientFlow.value !== connection.client) {
            throw GatewayRpcException("The gateway connection changed.")
        }
    }

    private fun canonicalSummary(
        requestedId: String,
        canonicalId: String,
        snapshot: JsonObject,
        status: SessionStatus,
        snapshotIsCurrent: Boolean,
        preserveProgress: Boolean,
    ): SessionSummary {
        val existing = cache.session(canonicalId) ?: cache.session(requestedId)
        val activityStartedAtMillis = if (status == SessionStatus.Working) {
            snapshot.primitive("turn_started_at")?.epochMillisOrNull()
                ?: existing?.activityStartedAtMillis
                ?: clock()
        } else {
            null
        }
        if (!snapshotIsCurrent && existing != null) {
            return existing.copy(id = canonicalId, status = status, activityStartedAtMillis = activityStartedAtMillis)
        }
        val parsed = parseSession(snapshot, clock(), canonicalId)
        return existing?.copy(
            id = canonicalId,
            title = snapshot.string("title")?.ifBlank { existing.title } ?: existing.title,
            preview = snapshot.string("preview") ?: existing.preview,
            lastActiveAtMillis = if (snapshot.hasTimestamp()) parsed.lastActiveAtMillis else existing.lastActiveAtMillis,
            messageCount = snapshot.primitive("message_count")?.toIntOrNull() ?: existing.messageCount,
            source = snapshot.string("source") ?: existing.source,
            remoteProfile = snapshot.string("profile") ?: snapshot.string("profile_name") ?: existing.remoteProfile,
            gitBranch = snapshot.sessionGitBranch() ?: existing.gitBranch,
            worktreePath = snapshot.sessionWorktreePath() ?: existing.worktreePath,
            status = status,
            progress = if (preserveProgress) existing.progress else null,
            composerStatus = if (preserveProgress) {
                existing.composerStatus
            } else {
                existing.composerStatus.retainingGatewayQueue()
            },
            activityStartedAtMillis = activityStartedAtMillis,
        ) ?: parsed.copy(status = status, activityStartedAtMillis = activityStartedAtMillis)
    }

    private fun SessionSummary.withGatewayQueueProjection(
        projection: LiveSessionProjection,
        runtimeId: String,
        mayHaveMissedDrain: Boolean,
    ): SessionSummary {
        val local = composerStatus?.gatewayQueuedPrompts.orEmpty()
        val prompts = when {
            // Authoritative absence: the Gateway holds nothing queued.
            projection.hasAuthoritativeQueueState && projection.queuedUser == null -> emptyList()
            // The Gateway snapshot exposes only the FIFO head. Consecutive
            // text-only submissions may share that server envelope, so compare
            // local batch suffixes rather than deduplicating occurrence text.
            // Gateway may remove a queued self-copy of the live user prompt
            // from the front of an otherwise merged text envelope.
            else -> {
                val headText = projection.queuedUser
                if (headText != null) {
                    val match = local.matchGatewayQueueHead(headText, mayHaveMissedDrain)
                    if (match != null) {
                        val retained = local.drop(match.localStart)
                        val batchId = retained.first().gatewayBatchId
                        fun foreignOccurrence(text: String): ComposerGatewayQueuedPrompt {
                            val occurrenceId = "gateway-queued-${sequence.incrementAndGet()}"
                            return ComposerGatewayQueuedPrompt(
                                id = occurrenceId,
                                text = text,
                                gatewayBatchId = batchId,
                                gatewayBatchMergeable = true,
                            )
                        }
                        buildList {
                            match.foreignPrefix?.let { add(foreignOccurrence(it)) }
                            addAll(retained.take(match.batchSize))
                            match.foreignSuffix?.let { add(foreignOccurrence(it)) }
                            addAll(retained.drop(match.batchSize))
                        }
                    } else {
                        val occurrenceId = "gateway-queued-${sequence.incrementAndGet()}"
                        listOf(
                            ComposerGatewayQueuedPrompt(
                                id = occurrenceId,
                                text = headText,
                                gatewayBatchId = occurrenceId,
                            ),
                        ) + local
                    }
                } else {
                    local
                }
            }
        }
        val status = (composerStatus ?: ComposerStatusState())
            .copy(gatewayQueuedPrompts = prompts)
            .takeIf(ComposerStatusState::hasVisibleRows)
        if (status == composerStatus) return this
        if (status != null) composerStatusRuntimeIds += runtimeId
        return copy(composerStatus = status)
    }

    private fun runtimeEventRevision(runtimeId: String): RuntimeEventRevision =
        runtimeEventRevisions[runtimeId] ?: RuntimeEventRevision()

    private fun advanceLiveEventRevision(runtimeId: String) {
        val current = runtimeEventRevision(runtimeId)
        runtimeEventRevisions[runtimeId] = current.copy(live = current.live + 1)
    }

    private fun advanceProgressEventRevision(runtimeId: String) {
        val current = runtimeEventRevision(runtimeId)
        runtimeEventRevisions[runtimeId] = current.copy(progress = current.progress + 1)
    }

    /** `status.update/process` coalesces onto the repository event path, never a UI collector. */
    private fun scheduleProcessRefresh(durableId: String) {
        val launch = synchronized(stateLock) {
            if (durableId in processRefreshesInFlight) false else {
                processRefreshesInFlight += durableId
                true
            }
        }
        if (!launch) return
        scope.launch {
            try {
                listProcesses(durableId)
            } finally {
                synchronized(stateLock) { processRefreshesInFlight.remove(durableId) }
            }
        }
    }

    /** Coalesce terminal pushes, but rerun once if another terminal edge lands mid-refresh. */
    private fun scheduleMetadataRefresh() {
        val launch = synchronized(stateLock) {
            metadataRefreshPending = true
            if (metadataRefreshRunning) false else {
                metadataRefreshRunning = true
                true
            }
        }
        if (!launch) return
        scope.launch {
            while (true) {
                val shouldRun = synchronized(stateLock) {
                    if (metadataRefreshPending) {
                        metadataRefreshPending = false
                        true
                    } else {
                        metadataRefreshRunning = false
                        false
                    }
                }
                if (!shouldRun) return@launch
                // A rescan, not a refresh: a turn finishing is news about the
                // rows, and re-reading page one must not tell a reader who has
                // paged down that the list is one page long again.
                runCatching { rescanSessions() }
                if (cache.state.value.projects.available != false) runCatching { refreshProjects() }
            }
        }
    }

    private data class ConnectionSnapshot(val client: GatewayRpcClient, val generation: Long)
    private data class RuntimeEventRevision(val live: Long = 0, val progress: Long = 0)
    private data class ConnectionReset(
        val generation: Long,
        val ephemeralDurableIds: List<String>,
        val reconnectDurableIds: List<String>,
        val clearProjects: Boolean,
    )
    private data class SessionBinding(val durableId: String, val runtimeId: String)
    private data class OptimisticSubmit(
        val session: SessionSummary?,
        val transcript: List<TranscriptEntry>,
    )
}

internal fun safeGatewayTerminalError(raw: String?): String {
    val classified = redact(raw).take(MAX_GATEWAY_ERROR_CLASSIFICATION_CHARS).lowercase()
    return if (REMOTE_STORAGE_ERROR_MARKERS.any(classified::contains)) {
        "The remote host is out of storage. Free space there, then try again."
    } else {
        "Hermes ended this turn unexpectedly. Check the Gateway, then try again."
    }
}

internal fun safeGatewayStatusText(raw: String): String =
    redact(raw).replace(STATUS_WHITESPACE, " ").trim().take(MAX_STATUS_TEXT)

/** Parse only documented `process.list` rows and keep every display field safe and bounded. */
internal fun parseGatewayProcesses(root: JsonObject): List<ComposerBackgroundProcess>? {
    val rawProcesses = root["processes"] as? JsonArray ?: return null
    return rawProcesses.map { element ->
        val process = element as? JsonObject ?: return null
        val id = process.jsonString("session_id")?.trim()?.takeIf(String::isNotEmpty)
            ?: process.jsonString("process_id")?.trim()?.takeIf(String::isNotEmpty)
            ?: return null
        val exitCode = process.primitive("exit_code")?.toIntOrNull()
        val rawStatus = process.jsonString("status")?.lowercase()
        val state = when {
            rawStatus in PROCESS_FAILURE_STATUSES -> ComposerBackgroundProcessState.Failed
            rawStatus == "exited" && exitCode != null && exitCode != 0 -> ComposerBackgroundProcessState.Failed
            rawStatus == "exited" || rawStatus in PROCESS_DONE_STATUSES -> ComposerBackgroundProcessState.Done
            else -> ComposerBackgroundProcessState.Running
        }
        ComposerBackgroundProcess(
            id = id,
            title = safeGatewayStatusText(process.jsonString("command").orEmpty().lineSequence().firstOrNull().orEmpty())
                .ifBlank { "background process" },
            state = state,
            exitCode = exitCode,
            output = process.jsonString("output_tail")?.let(::safeGatewayStatusText),
        )
    }
}

/**
 * The Gateway returns goal status as text. Preserve the redacted text even
 * when a newer server format cannot be recognized; `Unknown` is intentionally
 * neutral rather than a fabricated active goal.
 */
internal fun parseGatewayGoalStatus(
    rawText: String,
    previous: ComposerGoalStatus?,
): ComposerGoalStatus {
    val text = safeGatewayStatusText(rawText)
    val line = text.substringBefore('\n').trim()
    fun match(pattern: Regex): String? = pattern.matchEntire(line)?.groupValues?.getOrNull(1)?.trim()
        ?.takeIf(String::isNotEmpty)
    if (line.matches(NO_GOAL_STATUS)) {
        return ComposerGoalStatus(text, ComposerGoalState.None)
    }
    match(GOAL_SET_STATUS)?.let { return ComposerGoalStatus(text, ComposerGoalState.Active, title = it) }
    match(GOAL_ACTIVE_STATUS)?.let { return ComposerGoalStatus(text, ComposerGoalState.Active, title = it) }
    match(GOAL_RESUMED_STATUS)?.let { return ComposerGoalStatus(text, ComposerGoalState.Active, title = it) }
    match(GOAL_WAITING_STATUS)?.let { return ComposerGoalStatus(text, ComposerGoalState.Waiting, title = it) }
    match(GOAL_PAUSED_STATUS)?.let { return ComposerGoalStatus(text, ComposerGoalState.Paused, title = it) }
    match(GOAL_DONE_STATUS)?.let { return ComposerGoalStatus(text, ComposerGoalState.Done, title = it) }

    val priorTitle = previous?.title ?: "Standing goal"
    return when {
        CONTINUING_GOAL_STATUS.containsMatchIn(line) -> ComposerGoalStatus(
            text,
            ComposerGoalState.Active,
            title = priorTitle,
            detail = line.removePrefix("↻").trim(),
        )

        PARKED_GOAL_STATUS.containsMatchIn(line) -> ComposerGoalStatus(
            text,
            ComposerGoalState.Waiting,
            title = priorTitle,
            detail = line.removePrefix("⏳").trim(),
        )

        PAUSED_GOAL_NOTICE.containsMatchIn(line) -> ComposerGoalStatus(
            text,
            ComposerGoalState.Paused,
            title = priorTitle,
            detail = line.removePrefix("⏸").trim(),
        )

        ACHIEVED_GOAL_STATUS.containsMatchIn(line) -> ComposerGoalStatus(
            text,
            ComposerGoalState.Done,
            title = priorTitle,
            detail = line.removePrefix("✓").trim(),
        )

        else -> ComposerGoalStatus(text, ComposerGoalState.Unknown)
    }
}

internal fun parseSessionList(result: JsonElement, nowMillis: Long): List<SessionSummary> {
    val root = result.asObject("session.list")
    val sessions = root["sessions"] as? JsonArray
        ?: throw GatewayRpcException("Hermes returned a malformed session list.")
    return sessions.map { element ->
        val session = element as? JsonObject
            ?: throw GatewayRpcException("Hermes returned a malformed session row.")
        parseSession(session, nowMillis)
    }
}

/** Parses the documented model.options payload without inventing catalog rows. */
internal fun parseModelCatalog(result: JsonElement): ModelCatalog {
    val root = result.asObject("model.options")
    val selectedModel = root.string("model")?.trim().orEmpty()
    val selectedProvider = root.string("provider")?.trim().orEmpty()
    val providers = (root["providers"] as? JsonArray).orEmpty().mapNotNull { providerElement ->
        val provider = providerElement as? JsonObject ?: return@mapNotNull null
        val id = provider.string("slug")?.trim()?.takeIf(String::isNotEmpty)
            ?: provider.string("id")?.trim()?.takeIf(String::isNotEmpty)
            ?: return@mapNotNull null
        val capabilities = provider["capabilities"] as? JsonObject
        val models = (provider["models"] as? JsonArray).orEmpty().mapNotNull { modelElement ->
            val model = when (modelElement) {
                is JsonPrimitive -> if (modelElement is JsonNull) "" else modelElement.content.trim()
                is JsonObject -> (modelElement.string("id") ?: modelElement.string("model")).orEmpty().trim()
                else -> ""
            }
            if (model.isBlank()) return@mapNotNull null
            val capability = capabilities?.get(model) as? JsonObject
            ModelOption(
                id = model,
                label = (modelElement as? JsonObject)?.string("label")?.takeIf(String::isNotBlank) ?: model,
                supportsReasoning = capability?.boolean("reasoning") ?: true,
                supportsFast = capability?.boolean("fast") ?: false,
            )
        }
        ModelProvider(
            id = id,
            label = provider.string("name")?.trim()?.takeIf(String::isNotEmpty) ?: id,
            models = models,
            // The backend's own shortlist, when it ships one
            // (`hermes_cli/inventory.py:513-568` @ `3ca096de`). It decides what
            // the Models sheet shows before anyone customises it, so an
            // aggregator's hundred rows are not the default view.
            // Strings only, kept verbatim: the field is typed `string[]`
            // (`apps/desktop/src/types/hermes.ts:391-395`) and is matched with
            // `featured.includes(family.id)` (`store/model-visibility.ts:127`),
            // which neither coerces a number nor trims whitespace.
            featured = (provider["featured_models"] as? JsonArray).orEmpty().mapNotNull { featured ->
                (featured as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)
            },
        )
    }
    return ModelCatalog(
        providers = providers,
        effectiveSelection = selectedModel.takeIf(String::isNotEmpty)?.let {
            ComposerModelSelection(it, selectedProvider)
        },
    )
}

internal fun parseCompletionResult(result: JsonElement, method: String): CompletionResult {
    val root = result.asObject(method)
    val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val text = item.string("text")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        CompletionItem(
            text = text,
            display = item.string("display")?.takeIf(String::isNotEmpty) ?: text,
            detail = item.string("meta").orEmpty(),
            kind = item.string("kind").orEmpty(),
        )
    }
    return CompletionResult(
        items = items,
        replaceFrom = root.primitive("replace_from")?.toIntOrNull(),
    )
}

private fun Throwable.isLegacyBusyModelRefusal(): Boolean =
    this is GatewayRpcError && code == 4009

private fun controlFailureMessage(key: String): String = when (key) {
    "model" -> "Hermes could not switch the model. Try again."
    "reasoning" -> "Hermes could not change reasoning. Try again."
    "fast" -> "Fast mode is not available for this model. Choose another mode or model."
    else -> "Hermes could not update this control. Try again."
}

internal data class ProjectOverviewPayload(
    val projects: List<ProjectSummary>,
    val activeProjectId: String?,
)

internal data class ProjectDetailsPayload(
    val project: ProjectSummary,
    val sessions: List<SessionSummary>,
)

/** Parse only the backend-authored project tree; Android never infers membership from paths. */
internal fun parseProjectOverview(result: JsonElement, nowMillis: Long): ProjectOverviewPayload {
    val root = result.asObject("projects.tree")
    val projects = root["projects"] as? JsonArray
        ?: throw GatewayRpcException("Hermes returned a malformed project list.")
    return ProjectOverviewPayload(
        projects = projects.map { element ->
            parseProject(element as? JsonObject
                ?: throw GatewayRpcException("Hermes returned a malformed project row."), nowMillis)
        },
        activeProjectId = root.string("active_id"),
    )
}

internal fun parseProjectDetails(result: JsonElement, nowMillis: Long): ProjectDetailsPayload {
    val root = result.asObject("projects.project_sessions")
    val projectRoot = root["project"] as? JsonObject
        ?: throw GatewayRpcException("This project is no longer available.")
    val sessions = linkedMapOf<String, SessionSummary>()
    (projectRoot["repos"] as? JsonArray).orEmpty().forEach { repoElement ->
        val repo = repoElement as? JsonObject
            ?: throw GatewayRpcException("Hermes returned a malformed project repository.")
        (repo["groups"] as? JsonArray).orEmpty().forEach { groupElement ->
            val group = groupElement as? JsonObject
                ?: throw GatewayRpcException("Hermes returned a malformed project lane.")
            (group["sessions"] as? JsonArray).orEmpty().forEach { sessionElement ->
                val session = parseSession(
                    sessionElement as? JsonObject
                        ?: throw GatewayRpcException("Hermes returned a malformed project session."),
                    nowMillis,
                )
                sessions.putIfAbsent(session.id, session)
            }
        }
    }
    return ProjectDetailsPayload(parseProject(projectRoot, nowMillis), sessions.values.toList())
}

private fun parseProject(root: JsonObject, nowMillis: Long): ProjectSummary {
    val id = root.string("id")?.takeIf(String::isNotBlank)
        ?: throw GatewayRpcException("Hermes returned a project without an id.")
    val previews = (root["previewSessions"] as? JsonArray).orEmpty().map { element ->
        parseSession(
            element as? JsonObject
                ?: throw GatewayRpcException("Hermes returned a malformed project preview."),
            nowMillis,
        )
    }
    return ProjectSummary(
        id = id,
        label = root.string("label")?.ifBlank { id } ?: id,
        path = root.string("path")?.takeIf(String::isNotBlank),
        isAuto = root.boolean("isAuto") == true,
        isHome = root.boolean("isNoProject") == true,
        sessionCount = root.primitive("sessionCount")?.toIntOrNull() ?: previews.size,
        lastActiveAtMillis = root.primitive("lastActive")?.epochMillisOrNull() ?: 0,
        previewSessions = previews,
    )
}

internal fun parseHistory(result: JsonElement, runtimeId: String, nowMillis: Long): List<TranscriptEntry> {
    val root = result.asObject("session.history")
    val messages = root["messages"] as? JsonArray
        ?: throw GatewayRpcException("Hermes returned malformed session history.")
    return parseMessages(messages, nowMillis) { index -> "$runtimeId-history-$index" }
}

/** The todo list a projected REST page ends on, read the same way history's is. */
internal fun latestComposerTodosFromRows(messages: List<JsonObject>): List<ComposerTodoStatus>? =
    latestComposerTodosFromHistory(buildJsonObject { put("messages", JsonArray(messages)) })

/** Latest parseable todo call wins; active historical lists are filtered by the caller. */
internal fun latestComposerTodosFromHistory(result: JsonElement): List<ComposerTodoStatus>? {
    val root = result as? JsonObject ?: return null
    val messages = root["messages"] as? JsonArray ?: return null
    var latest: List<ComposerTodoStatus>? = null
    messages.forEach messageLoop@ { element ->
        val message = element as? JsonObject ?: return@messageLoop
        if (message.string("role") == "tool" && message.todoToolName() == "todo") {
            parseComposerTodosFromTool(message)?.let { latest = it }
        }
        (message["content"] as? JsonArray).orEmpty().forEach partLoop@ { partElement ->
            val part = partElement as? JsonObject ?: return@partLoop
            val toolName = part.todoToolName()
            if (toolName == "todo") parseComposerTodosFromTool(part)?.let { latest = it }
        }
    }
    return latest
}

/**
 * Projected rows to transcript entries.
 *
 * [fallbackId] mints the rendering key for a row the backend gave no
 * identifier for. It is the caller's because the two contracts number their
 * rows differently: `session.history` ships the whole conversation, so an index
 * into it is a position; a REST page ships a window, so only its offset places
 * a row. Two schemes that could collide would merge two different rows into one
 * on a prepend.
 */
private fun parseMessages(
    messages: List<JsonElement>,
    nowMillis: Long,
    fallbackId: (Int) -> String,
): List<TranscriptEntry> = buildList {
    messages.forEachIndexed { index, element ->
        val message = element as? JsonObject ?: return@forEachIndexed
        val id = message.messageId() ?: fallbackId(index)
        val rowId = message.durableRowId()
        val time = message.timestamp(nowMillis)
        when (message.string("role")) {
            "user" -> add(UserTurn(id, message.answerText(), time, rowId = rowId))
            "assistant" -> {
                val reasoning = message.reasoningText()
                reasoning.takeIf(String::isNotBlank)?.let {
                    add(
                        ReasoningActivity(
                            id = "$id-reasoning",
                            text = it.safePayloadText().orEmpty(),
                            state = ToolState.Done,
                            elapsedSeconds = message.durationSeconds(),
                            rowId = rowId,
                        ),
                    )
                }
                val answer = message.answerText()
                if (answer.isNotBlank()) {
                    add(AssistantTurn(id, answer, time, rowId = rowId))
                }
            }

            "tool" -> {
                if (message.todoToolName() == "todo") return@forEachIndexed
                val name = message.string("name").safeToolLabel("Tool")
                add(
                    ToolActivity(
                        id = id,
                        label = name,
                        detail = (message.string("context") ?: message.contentText())
                            .safeDisplayText(MAX_TOOL_DETAIL)
                            .orEmpty(),
                        state = if (message.toolFailed()) ToolState.Failed else ToolState.Done,
                        elapsedSeconds = message.durationSeconds(),
                        toolName = name,
                        argsText = message.toolInputText(),
                        resultText = message["result"].safePayloadText() ?: message["content"].safePayloadText(),
                        inlineDiff = message.jsonString("inline_diff")?.safePayloadText(),
                        // Upstream's tool projection returns at server.py:7601-7615,
                        // before the row_id stamp at :7645, so this is null today.
                        // Read rather than hardcoded: a Gateway that starts
                        // stamping tool rows must not have that address dropped.
                        rowId = rowId,
                    ),
                )
            }
        }
    }
}

internal fun parseSession(root: JsonObject, nowMillis: Long, authoritativeId: String? = null): SessionSummary {
    val id = authoritativeId ?: root.string("id")
        ?: throw GatewayRpcException("Hermes returned a session without a durable id.")
    val usageObj = root.obj("usage")
    return SessionSummary(
        id = id,
        title = root.string("title")?.ifBlank { "New session" } ?: "New session",
        preview = root.string("preview").orEmpty(),
        lastActiveAtMillis = root.timestamp(nowMillis),
        messageCount = root.primitive("message_count")?.toIntOrNull() ?: 0,
        source = root.string("source"),
        remoteProfile = root.string("profile") ?: root.string("profile_name"),
        gitBranch = root.sessionGitBranch(),
        worktreePath = root.sessionWorktreePath(),
        usage = if (usageObj != null) parseSessionUsage(usageObj) else null,
    )
}

/**
 * One row of `GET /api/sessions` (hermes-agent @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 *
 * The keys are the `sessions` table's own columns as
 * `SessionDB.list_sessions_rich` projects them
 * (`hermes_state_portability.py:33-43`, chain projection at
 * `hermes_state.py:9383-9401`), plus what the route stamps on top:
 * `profile`/`is_default_profile`, and `archived`/`pinned` coerced from SQLite's
 * 0/1 into real JSON booleans (`hermes_cli/web_routers/sessions.py:145-156`).
 * `unread` is derived per surfaced conversation from the read watermark
 * (`hermes_state.py:9400-9401`).
 *
 * Every field this contract adds is optional here. A Gateway predating a column
 * omits it, and the answer to "was this archived?" on such a backend is *not
 * known*, not "no" — writing a `false` or a `0` in that gap would put a control
 * on screen that cannot work, which is precisely what the pin/archive/unread
 * affordances must avoid until a backend says they are real.
 */
internal fun parseRestSession(root: JsonObject, nowMillis: Long): SessionSummary {
    // The id is read strictly here and tolerantly in [parseSession]: this one
    // becomes a cache key and can move a whole conversation under
    // `alignLineage`, so a number wearing an id is refused rather than coerced.
    // Loosening the RPC path to match is a separate contract's decision.
    val id = root.jsonString("id")
        ?: throw GatewayRpcException("Hermes returned a session without a durable id.")
    // The columns both contracts share are one parser's job, not two. Title,
    // preview, activity, count, source, profile, branch and cwd read identically
    // off either shape — the profile included, which this route stamps on every
    // row even when the request named none (`sessions.py:182-189`). Whether that
    // stamp is a fact about the *row* is not this parser's call to make: it is
    // the row's owner on a named leg and the Gateway describing itself on an
    // unscoped one, and `readSessionPages` is where that is decided. What
    // follows is only what this contract adds.
    return parseSession(root, nowMillis, authoritativeId = id).copy(
        archived = root.boolean("archived"),
        pinned = root.boolean("pinned"),
        unread = root.boolean("unread"),
        // Strict, like the id above and for the same reason — the surrounding
        // columns are read leniently by design: `model` is a label this app
        // shows verbatim, so a number wearing a model name is refused rather
        // than coerced into `"42"`.
        model = root.jsonString("model")?.trim()?.takeIf(String::isNotEmpty),
        toolCallCount = root.primitive("tool_call_count")?.toIntOrNull(),
        inputTokens = root.primitive("input_tokens")?.toLongOrNull(),
        outputTokens = root.primitive("output_tokens")?.toLongOrNull(),
        // Both are real `0.0` on subscription auth that never quotes a price
        // (`apps/desktop/src/types/hermes.ts:493-498` @ the pin), so a zero
        // here is data and only an absent key is unknown.
        actualCostUsd = root.primitive("actual_cost_usd")?.toDoubleOrNull(),
        estimatedCostUsd = root.primitive("estimated_cost_usd")?.toDoubleOrNull(),
        lineageRootId = root.jsonString("_lineage_root_id")?.takeIf(String::isNotBlank),
    )
}

internal fun parseSessionUsage(
    json: JsonObject,
    previous: SessionUsage? = null,
): SessionUsage {
    val contextUsed = json.long("context_used")
        ?: json.int("context_used")?.toLong()
        ?: previous?.contextUsed
    val contextMax = json.long("context_max")
        ?: json.int("context_max")?.toLong()
        ?: previous?.contextMax
    val contextPercent = json.int("context_percent")
        ?: json.double("context_percent")?.let { Math.round(it).toInt() }
        ?: previous?.contextPercent
    val total = json.long("total")
        ?: json.int("total")?.toLong()
        ?: previous?.total
        ?: 0L
    val input = json.long("input")
        ?: json.int("input")?.toLong()
        ?: previous?.input
        ?: 0L
    val output = json.long("output")
        ?: json.int("output")?.toLong()
        ?: previous?.output
        ?: 0L
    val calls = json.int("calls")
        ?: previous?.calls
        ?: 0
    val model = json.string("model")
        ?.let { redact(it).take(40) }
        ?: previous?.model
        ?: ""
    return SessionUsage(
        contextUsed = contextUsed?.takeIf { it >= 0 },
        contextMax = contextMax?.takeIf { it > 0 },
        contextPercent = contextPercent?.coerceIn(0, 100),
        total = maxOf(0L, total),
        input = maxOf(0L, input),
        output = maxOf(0L, output),
        calls = maxOf(0, calls),
        model = model,
    )
}

internal fun parseContextBreakdown(json: JsonObject): ContextBreakdown? {
    val categories = json.array("categories").orEmpty().take(16).mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id")?.trim()?.take(40) ?: ""
        val rawLabel = obj.string("label")?.trim() ?: id
        val label = redact(rawLabel).take(40)
        val tokens = maxOf(0L, obj.long("tokens") ?: obj.int("tokens")?.toLong() ?: 0L)
        val color = obj.string("color")?.trim() ?: "var(--ui-text-tertiary)"
        ContextUsageCategory(
            id = id,
            label = label,
            tokens = tokens,
            color = color,
        )
    }
    val contextMax = maxOf(0L, json.long("context_max") ?: json.int("context_max")?.toLong() ?: 0L)
    val contextPercent = (json.int("context_percent")
        ?: json.double("context_percent")?.let { Math.round(it).toInt() }
        ?: 0).coerceIn(0, 100)
    val contextUsed = maxOf(0L, json.long("context_used") ?: json.int("context_used")?.toLong() ?: 0L)
    val estimatedTotal = maxOf(0L, json.long("estimated_total") ?: json.int("estimated_total")?.toLong() ?: 0L)
    val model = redact(json.string("model")?.trim() ?: "").take(40)
    return ContextBreakdown(
        categories = categories,
        contextMax = contextMax,
        contextPercent = contextPercent,
        contextUsed = contextUsed,
        estimatedTotal = estimatedTotal,
        model = model,
    )
}

private fun JsonElement.asObject(method: String): JsonObject = this as? JsonObject
    ?: throw GatewayRpcException("Hermes returned malformed data for $method.")

private fun Throwable.isMissingProjectsMethod(): Boolean =
    this is GatewayRpcError && (
        code == MISSING_RPC_METHOD_CODE ||
            message.contains("unknown method", ignoreCase = true) ||
            message.contains("method not found", ignoreCase = true)
        )

private fun Throwable.isUnsupportedGatewayCapability(): Boolean =
    this is GatewayRpcError && (
        code == MISSING_RPC_METHOD_CODE ||
            code == UNSUPPORTED_CAPABILITY_CODE ||
            message.contains("unknown method", ignoreCase = true) ||
            message.contains("method not found", ignoreCase = true) ||
            message.contains("does not support", ignoreCase = true) ||
            message.contains("unsupported", ignoreCase = true)
        )

private fun Throwable.isAmbiguousGatewayMutation(): Boolean =
    this is GatewayRpcException && requestMayHaveBeenAccepted

private fun objectParams(name: String, value: String): JsonObject =
    buildJsonObject { put(name, JsonPrimitive(value)) }

/**
 * Params for `session.history`, including a forward-compatible
 * `include_row_ids` hedge.
 *
 * Be honest about what this flag does today: nothing. At
 * NousResearch/hermes-agent @ `3ca096de5f8183cb2e0ec23673f294d5978656a3` the
 * handler hardcodes `include_row_ids=True` on its own read and never looks at
 * request params (`tui_gateway/methods_session.py:2611-2620`), so the pinned
 * Gateway stamps every persisted row with its `messages.id` whether or not we
 * ask. The flag is sent so that a Gateway which one day makes the stamped read
 * opt-in still answers a stamped transcript, because that stamp is the only
 * durable address a client has for one turn — the ids this app mints are
 * rendering keys and differ between a live, an optimistic and a rehydrated row.
 *
 * Sending it is safe on the pinned Gateway for a narrow, method-specific
 * reason, not a protocol guarantee: dispatch only type-checks that `params` is
 * an object (`tui_gateway/server.py:2144-2161`) and this handler then reads
 * `session_id` alone (`_sess_nowait`, `server.py:2518-2520`), so an extra key
 * is inert here. Handlers validate their own params and do refuse requests —
 * `message.react` answers 4023 when its row address is missing
 * (`methods_session.py:1266-1274`) — so this tolerance must be re-checked per
 * method, never assumed.
 *
 * Either way [durableRowId] reports no durable identity when a response
 * carries no `row_id`, rather than inventing one.
 */
private fun historyParams(sessionId: String): JsonObject = buildJsonObject {
    put("session_id", JsonPrimitive(sessionId))
    put("include_row_ids", JsonPrimitive(true))
}

/**
 * The rendering key for a projected row: whatever identifier the row carries,
 * `row_id` first because a persisted row's is the most stable of them. Unlike
 * [durableRowId] this tolerates any shape — a key only has to be unique down
 * the rendered list, never addressable back to the backend.
 */
private fun JsonObject.messageId(): String? = string("row_id") ?: string("message_id") ?: string("id")

/**
 * The durable `messages.id` of a persisted row, or null.
 *
 * Null covers every case where the backend has not given us an address: an
 * older Gateway that ships no `row_id`, a live row it has not written down
 * yet, or a value that is not a positive integer row id. Nothing is fabricated
 * from the local rendering key — a made-up address would later rewind or react
 * to the wrong turn.
 */
private fun JsonObject.durableRowId(): TranscriptRowId? =
    primitive("row_id")?.toLongOrNull()?.takeIf { it > 0 }?.let(::TranscriptRowId)

private fun JsonObject.todoToolName(): String? =
    string("toolName") ?: string("tool_name") ?: string("name")

private fun JsonObject.sessionGitBranch(): String? =
    (string("branch") ?: string("git_branch"))
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_SESSION_BRANCH }

/** Preserve the server path byte-for-byte; truncation could target another path. */
private fun JsonObject.sessionWorktreePath(): String? =
    string("cwd")?.takeIf { it.isNotBlank() && it.length <= MAX_SESSION_CWD }

internal fun JsonObject.jsonString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.canonicalDurableId(): String? =
    string("session_key") ?: string("resumed") ?: string("stored_session_id")

private fun JsonObject.deltaText(): String = string("delta") ?: string("text") ?: contentText()

private fun JsonObject.contentText(): String = (this["content"] ?: this["text"]).coerceMessageText()

private fun JsonObject.reasoningText(): String {
    string("reasoning")?.let { return it }
    string("reasoning_content")?.let { return it }
    string("reasoning_details")?.let { return it }
    val content = this["content"] as? JsonArray ?: return ""
    return content.mapNotNull { item ->
        val part = item as? JsonObject ?: return@mapNotNull null
        val type = part.string("type")?.lowercase()
        if (type !in REASONING_CONTENT_TYPES) return@mapNotNull null
        part.string("text") ?: part.string("content")
    }.joinToString("")
}

private fun JsonObject.answerText(): String {
    val content = this["content"] as? JsonArray ?: return contentText()
    return content.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.content
            is JsonObject -> {
                val type = item.string("type")?.lowercase()
                if (type in REASONING_CONTENT_TYPES) null else item.string("text") ?: item.string("content")
            }

            else -> null
        }
    }.joinToString("")
}

private fun JsonObject.durationSeconds(): Double =
    primitive("duration_s")?.toDoubleOrNull()
        ?: primitive("elapsed_seconds")?.toDoubleOrNull()
        ?: 0.0

private fun JsonElement?.safePayloadText(): String? =
    displayText().safeDisplayText(MAX_TOOL_PAYLOAD)

private fun String?.safeDisplayText(limit: Int): String? = this
    ?.let(::redact)
    ?.take(limit)
    ?.takeIf(String::isNotBlank)

private fun String?.safePayloadText(): String? = safeDisplayText(MAX_TOOL_PAYLOAD)

private fun String?.safeToolLabel(fallback: String): String =
    safeDisplayText(MAX_TOOL_LABEL) ?: fallback

/** Redacted, bounded, single-line text for pending-input display fields. */
private fun String.redactSafeBounded(limit: Int = MAX_PENDING_TEXT): String =
    redact(this).replace(STATUS_WHITESPACE, " ").trim().take(limit)

private fun String.normalizeChoice(): String = redactSafeBounded(MAX_PENDING_CHOICE)

private fun JsonObject.toolInputText(): String? =
    this["args"].safePayloadText()
        ?: this["arguments"].safePayloadText()
        ?: this["input"].safePayloadText()
        ?: string("args_text")?.safePayloadText()

private fun JsonObject.toolDetail(type: String): String {
    val result = this["result"].displayText()
    val detail = if (type == "tool.complete") {
        string("summary") ?: result ?: string("context") ?: string("message") ?: ""
    } else {
        string("context") ?: string("summary") ?: string("preview") ?: string("message") ?: result.orEmpty()
    }
    return detail.safeDisplayText(MAX_TOOL_DETAIL).orEmpty()
}

private fun JsonObject.toolFailed(): Boolean {
    val status = string("status")?.lowercase()
    if (status in TOOL_FAILURE_STATUSES || this["error"].isTruthySignal()) return true
    if (boolean("success") == false || boolean("is_error") == true) return true
    val result = this["result"] as? JsonObject ?: return false
    return result.string("status")?.lowercase() in TOOL_FAILURE_STATUSES ||
        result["error"].isTruthySignal() ||
        result.boolean("success") == false ||
        result.boolean("is_error") == true
}

private fun JsonElement?.displayText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    is JsonObject, is JsonArray -> toString()
}

private fun JsonElement?.isTruthySignal(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        !isString -> booleanOrNull ?: (content.toDoubleOrNull()?.let { it != 0.0 } ?: content.isNotBlank())
        else -> content.isNotBlank() && content.lowercase() !in FALSEY_SIGNAL_STRINGS
    }
    is JsonObject -> isNotEmpty()
    is JsonArray -> isNotEmpty()
}

private fun mergeHistoryWithLiveEntries(
    authoritative: List<TranscriptEntry>,
    live: List<TranscriptEntry>,
): List<TranscriptEntry> {
    val merged = authoritative.toMutableList()
    live.forEach { entry ->
        val index = merged.indexOfFirst { it.id == entry.id }
        if (index >= 0) merged[index] = entry else merged += entry
    }
    return merged
}

private fun JsonObject.status(): SessionStatus? = when (string("status")?.lowercase()) {
    "running", "starting", "working", "streaming" -> SessionStatus.Working
    "waiting", "needs_input", "needs-input" -> SessionStatus.NeedsInput
    "background" -> SessionStatus.Background
    "stalled" -> SessionStatus.Stalled
    "idle", "complete", "completed", "done" -> SessionStatus.Idle
    else -> null
}

private data class InflightProjection(
    val user: String,
    val assistant: String,
    val corrections: List<String>,
    val correctionOffsets: List<Int>?,
    val streaming: Boolean,
    val error: String,
    val status: String?,
    val atMillis: Long,
)

private data class LiveSessionProjection(
    val running: Boolean?,
    val status: SessionStatus?,
    val inflight: InflightProjection?,
    val queuedUser: String?,
    val hasAuthoritativeState: Boolean,
    val hasAuthoritativeQueueState: Boolean,
) {
    val retainedFailure: Boolean
        get() = inflight?.error?.isNotBlank() == true || inflight?.status.equals("error", ignoreCase = true)
    val busy: Boolean
        get() = running == true || inflight?.streaming == true || status in RESUMED_BUSY_STATUSES
}

private data class GatewayQueueHeadMatch(
    val localStart: Int,
    val batchSize: Int,
    val foreignPrefix: String? = null,
    val foreignSuffix: String? = null,
)

/**
 * Map the Gateway's authoritative head-envelope text back onto the local queue
 * rows. The snapshot exposes only the FIFO head, so a local batch can be wider
 * than the head (its tail sits in later envelopes) and the head can carry
 * another client's occurrences around ours.
 *
 * [mayHaveMissedDrain] is the only evidence that an EARLIER local batch could
 * already have drained. While the app is connected it observes every drain, so
 * the earliest plausible batch is the head; preferring a later look-alike there
 * silently drops every row in front of it, queued image turns included.
 */
private fun List<ComposerGatewayQueuedPrompt>.matchGatewayQueueHead(
    headText: String,
    mayHaveMissedDrain: Boolean,
): GatewayQueueHeadMatch? {
    fun batchFrom(index: Int): List<ComposerGatewayQueuedPrompt> {
        val candidate = this[index]
        return drop(index).takeWhile { it.gatewayBatchId == candidate.gatewayBatchId }
    }

    fun textOf(rows: List<ComposerGatewayQueuedPrompt>): String =
        rows.joinToString("\n\n", transform = ComposerGatewayQueuedPrompt::text)

    // An exact head is unambiguous, so it outranks any loose look-alike; the
    // earliest such batch keeps the most local occurrence ids.
    fun exactPass(sizesOf: (List<ComposerGatewayQueuedPrompt>) -> List<Int>): GatewayQueueHeadMatch? =
        indices.firstNotNullOfOrNull { index ->
            val batch = batchFrom(index)
            sizesOf(batch).firstNotNullOfOrNull { size ->
                if (textOf(batch.take(size)) == headText) {
                    GatewayQueueHeadMatch(index, size)
                } else {
                    null
                }
            }
        }

    fun loosePass(sizesOf: (List<ComposerGatewayQueuedPrompt>) -> List<Int>): GatewayQueueHeadMatch? {
        val looseMatchesByBatch = linkedMapOf<String, GatewayQueueHeadMatch>()
        indices.forEach { index ->
            val batchId = this[index].gatewayBatchId
            if (batchId in looseMatchesByBatch) return@forEach
            val batch = batchFrom(index)
            val match = sizesOf(batch).firstNotNullOfOrNull { size ->
                val part = batch.take(size)
                if (!part.all(ComposerGatewayQueuedPrompt::gatewayBatchMergeable)) {
                    return@firstNotNullOfOrNull null
                }
                val batchText = textOf(part)
                val surroundedNeedle = "\n\n$batchText\n\n"
                val surroundedAt = headText.indexOf(surroundedNeedle)
                when {
                    headText.startsWith("$batchText\n\n") -> GatewayQueueHeadMatch(
                        localStart = index,
                        batchSize = size,
                        foreignSuffix = headText.removePrefix("$batchText\n\n"),
                    )
                    headText.endsWith("\n\n$batchText") -> GatewayQueueHeadMatch(
                        localStart = index,
                        batchSize = size,
                        foreignPrefix = headText.removeSuffix("\n\n$batchText"),
                    )
                    surroundedAt >= 0 -> GatewayQueueHeadMatch(
                        localStart = index,
                        batchSize = size,
                        foreignPrefix = headText.substring(0, surroundedAt),
                        foreignSuffix = headText.substring(surroundedAt + surroundedNeedle.length),
                    )
                    else -> null
                }
            }
            if (match != null) looseMatchesByBatch[batchId] = match
        }
        return if (mayHaveMissedDrain) {
            looseMatchesByBatch.values.maxByOrNull(GatewayQueueHeadMatch::localStart)
        } else {
            looseMatchesByBatch.values.minByOrNull(GatewayQueueHeadMatch::localStart)
        }
    }

    val wholeBatch = { batch: List<ComposerGatewayQueuedPrompt> -> listOf(batch.size) }
    exactPass(wholeBatch)?.let { return it }
    loosePass(wholeBatch)?.let { return it }

    // Nothing matched a whole batch. The head may still cover only the front of
    // one local batch, its tail having landed in a later envelope; prepending
    // the head as a foreign row there would show the user their own text twice.
    // Retry over proper prefixes of two or more rows: a one-row prefix is the
    // ambiguous single-occurrence case, where reading the head as somebody
    // else's text keeps every local row visible instead of hiding one.
    val batchPrefix = { batch: List<ComposerGatewayQueuedPrompt> -> (batch.size - 1 downTo 2).toList() }
    exactPass(batchPrefix)?.let { return it }
    return loosePass(batchPrefix)
}

private val EMPTY_LIVE_SESSION_PROJECTION = LiveSessionProjection(
    running = null,
    status = null,
    inflight = null,
    queuedUser = null,
    hasAuthoritativeState = false,
    hasAuthoritativeQueueState = false,
)

private fun parseLiveSessionProjection(root: JsonObject, fallbackTime: Long): LiveSessionProjection {
    val inflightRoot = root["inflight"] as? JsonObject
    val queuedUser = (root["queued"] as? JsonObject)
        ?.string("user")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    val corrections = (inflightRoot?.get("corrections") as? JsonArray).orEmpty().mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
    }
    val offsetsRoot = inflightRoot?.get("correction_offsets") as? JsonArray
    val offsets = offsetsRoot?.mapNotNull { item ->
        (item as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()
    }?.takeIf { it.size == corrections.size }
    val atMillis = root.primitive("turn_started_at")?.epochMillisOrNull() ?: root.timestamp(fallbackTime)
    val inflight = inflightRoot?.let {
        InflightProjection(
            user = it.string("user").orEmpty().trim(),
            assistant = it.string("assistant").orEmpty(),
            corrections = corrections,
            correctionOffsets = offsets,
            streaming = it.boolean("streaming") == true,
            error = it.string("error").orEmpty().trim(),
            status = it.string("status"),
            atMillis = atMillis,
        )
    }?.takeIf { projection ->
        projection.user.isNotBlank() || projection.assistant.isNotBlank() || projection.corrections.isNotEmpty() ||
            projection.streaming || projection.error.isNotBlank() || !projection.status.isNullOrBlank()
    }
    val hasAuthoritativeState = "running" in root || "status" in root || "inflight" in root || "queued" in root
    return LiveSessionProjection(
        running = root.boolean("running"),
        status = root.status(),
        inflight = inflight,
        queuedUser = queuedUser,
        hasAuthoritativeState = hasAuthoritativeState,
        // session.resume/session.activate are complete live snapshots and name
        // their runtime. Partial responses must not erase locally accepted rows
        // merely because they omit the optional `queued` object.
        hasAuthoritativeQueueState = "session_id" in root && hasAuthoritativeState,
    )
}

private fun appendInflightProjection(
    history: List<TranscriptEntry>,
    runtimeId: String,
    projection: LiveSessionProjection,
    fallbackTime: Long,
): List<TranscriptEntry> {
    val inflight = projection.inflight
    if (inflight == null && !projection.busy) return history
    val restored = history.toMutableList()
    val atMillis = inflight?.atMillis ?: fallbackTime
    val user = inflight?.user.orEmpty()
    if (user.isNotBlank() && !restored.openUserRunContains(user)) {
        restored += UserTurn("inflight-user-$runtimeId", user, atMillis)
    }

    val assistant = inflight?.assistant.orEmpty()
    val error = inflight?.error.orEmpty().takeIf(String::isNotBlank)?.let(::safeGatewayTerminalError)
    val corrections = inflight?.corrections.orEmpty()
    val offsets = inflight?.correctionOffsets
    val usableOffsets = error == null && assistant.isNotEmpty() && offsets != null && offsets.size == corrections.size

    if (usableOffsets) {
        var cursor = 0
        corrections.forEachIndexed { index, correction ->
            val boundary = offsets[index].coerceIn(cursor, assistant.length)
            val segment = assistant.substring(cursor, boundary)
            if (segment.isNotBlank()) {
                restored += AssistantTurn("inflight-assistant-segment-$index-$runtimeId", segment, atMillis)
            }
            cursor = boundary
            if (!restored.openUserRunContains(correction)) {
                restored += UserTurn("inflight-correction-$index-$runtimeId", correction, atMillis)
            }
        }
        restored += AssistantTurn(
            id = "inflight-assistant-$runtimeId",
            markdown = assistant.substring(cursor),
            atMillis = atMillis,
            streaming = projection.busy,
        )
    } else {
        val wantsAssistant = assistant.isNotBlank() || projection.busy || error != null
        if (wantsAssistant) {
            restored += AssistantTurn(
                id = "inflight-assistant-$runtimeId",
                markdown = assistant,
                atMillis = atMillis,
                streaming = projection.busy,
                error = error,
            )
        }
        corrections.forEachIndexed { index, correction ->
            if (!restored.openUserRunContains(correction)) {
                restored += UserTurn("inflight-correction-$index-$runtimeId", correction, atMillis)
            }
        }
    }
    return restored
}

private fun List<TranscriptEntry>.openUserRunContains(text: String): Boolean {
    val normalized = text.normalizedTranscriptText()
    if (normalized.isEmpty()) return false
    for (entry in asReversed()) {
        when (entry) {
            is UserTurn -> if (entry.text.normalizedTranscriptText() == normalized) return true
            is AssistantTurn -> if (!entry.streaming) return false
            is ReasoningActivity -> Unit
            is ToolActivity -> Unit
        }
    }
    return false
}

private fun String.normalizedTranscriptText(): String = replace(STATUS_WHITESPACE, " ").trim()

private fun todoListActive(todos: List<ComposerTodoStatus>): Boolean =
    todos.any { it.state == ComposerTodoState.Pending || it.state == ComposerTodoState.InProgress }

private fun ComposerStatusState.hasVisibleRows(): Boolean =
    goal != null || todos.isNotEmpty() || subagents.isNotEmpty() || backgroundProcesses.isNotEmpty() ||
        previewArtifacts.isNotEmpty() || gatewayQueuedPrompts.isNotEmpty() || isCompacting

/**
 * Replace by rendering key, or append.
 *
 * Wholesale in everything but the durable address: a live entry landing on the
 * id of a hydrated one keeps that row's [TranscriptEntry.rowId], because null
 * on the incoming entry means the backend has not written it down yet rather
 * than that the row has no identity — and the `Show earlier` merge dedupes an
 * overlapping page on exactly that address (#68 S25).
 */
private fun List<TranscriptEntry>.replaceOrAppend(entry: TranscriptEntry): List<TranscriptEntry> {
    val index = indexOfFirst { it.id == entry.id }
    if (index < 0) return this + entry
    val merged = entry.preservingRowIdOf(this[index])
    return toMutableList().apply { this[index] = merged }
}

private fun JsonObject.primitive(name: String): String? = (this[name] as? JsonPrimitive)?.content
private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray
private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    ?: primitive(name)?.toLongOrNull()
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
    ?: primitive(name)?.toIntOrNull()
private fun JsonObject.double(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull
    ?: primitive(name)?.toDoubleOrNull()

/**
 * A JSON *boolean* field. A quoted `"true"` is not one — the wire says what it
 * means, and coercing a string here would let a backend's typo become a flag.
 */
internal fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)
    ?.takeUnless { it.isString }
    ?.booleanOrNull

private fun JsonObject.timestamp(fallback: Long): Long {
    // Desktop sorts by last activity and falls back to creation:
    // NousResearch/hermes-agent @ 3ca096de5f8183cb2e0ec23673f294d5978656a3,
    // apps/desktop/src/app/chat/sidebar/projects/workspace-groups.ts:134-135.
    val value = this["last_active"] ?: this["started_at"] ?: this["created_at"] ?: this["timestamp"] ?: return fallback
    val text = (value as? JsonPrimitive)?.content ?: return fallback
    text.epochMillisOrNull()?.let { return it }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(fallback)
}

private fun JsonObject.hasTimestamp(): Boolean =
    "last_active" in this || "started_at" in this || "created_at" in this || "timestamp" in this

private fun String.epochMillisOrNull(): Long? {
    val number = toBigDecimalOrNull() ?: return null
    val millis = if (number < EPOCH_SECONDS_CUTOFF) number.movePointRight(3) else number
    return runCatching {
        millis.setScale(0, RoundingMode.DOWN).longValueExact()
    }.getOrNull()
}

private const val MAX_TOOL_DETAIL = 4_096
private const val MAX_TOOL_PAYLOAD = 32_768
private const val MAX_TOOL_LABEL = 256
/** Retired keys held per connection. Tens is the realistic count; this is headroom. */
private const val MAX_RETIRED_KEYS = 256

private const val MAX_PENDING_TEXT = 1_024
private const val MAX_PENDING_CHOICE = 240
private const val MAX_PENDING_CHOICES = 12
private const val MAX_PENDING_QUESTIONS = 20
private const val MAX_SESSION_BRANCH = 512
private const val MAX_SESSION_CWD = 4_096
private const val FINISHED_TODO_LINGER_MILLIS = 4_000L
private const val PROJECT_PREVIEW_LIMIT = 3
private const val MISSING_RPC_METHOD_CODE = -32601
private const val UNSUPPORTED_CAPABILITY_CODE = 4010
private const val MAX_GATEWAY_ERROR_CLASSIFICATION_CHARS = 4_096
private const val MAX_STATUS_TEXT = 240
private const val RECONCILIATION_FAILED_KIND = "reconcile_failed"
private const val RECONCILIATION_FAILED_TEXT =
    "This turn could not be checked. Reconnect to the Gateway, then reopen the session."
private const val PRE_START_FALSE_SETTLE_GRACE_MILLIS = 15_000L
private const val STOP_DISPATCH_WAIT_MILLIS = 2_000L
private const val IMAGE_ONLY_PROMPT = "What do you see in this image?"
private val NO_IMAGE_LOADER: MutableStateFlow<GatewayImageLoader?> = MutableStateFlow(null)
internal val NO_SESSION_PAGING: StateFlow<SessionListPaging> = MutableStateFlow(SessionListPaging())
internal val NO_EARLIER_MESSAGES: StateFlow<Set<String>> = MutableStateFlow(emptySet())
internal val NO_ACTIVE_TURNS: StateFlow<Set<String>> = MutableStateFlow(emptySet())

/**
 * One page of the session list, matching Desktop's own sidebar page
 * (`SIDEBAR_SESSIONS_PAGE_SIZE = 50`, `apps/desktop/src/store/layout.ts:25` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) and well inside the route's
 * `le=100` cap. The `session.list` fallback keeps its own historical `limit`
 * of 100 — that call has no second page, so shrinking it would lose rows.
 */
/**
 * What a refused flag write says.
 *
 * Desktop has one such string, `unreadFailed` (`apps/desktop/src/i18n/en.ts:2307`
 * @ `3ca096de`), and it is used verbatim. It has no counterpart for pin or
 * archive — a failed pin there raises the generic action-failed notice — so
 * those two follow this app's own error rule instead: what did not happen, and
 * a safe next step, never the transport's own words.
 */
private const val UNREAD_FAILED = "Could not update unread state"

/**
 * What a Gateway too old to be asked says. The `session.list` RPC has no
 * archived filter, so the honest answer is about this Gateway rather than an
 * empty `Nothing archived` about the account.
 *
 * Shared rather than private: the Archived view has to tell this refusal apart
 * from an ordinary read failure, because the two say different things on
 * screen, and one sentence in one place is what keeps them from drifting.
 */
internal const val ARCHIVED_UNSUPPORTED = "Archived chats need a newer Hermes on this Gateway."
private const val PIN_FAILED = "Could not update pin. Check the Gateway and try again."
private const val ARCHIVE_FAILED = "Could not archive that chat. Check the Gateway and try again."
private const val UNARCHIVE_FAILED = "Could not restore that chat. Check the Gateway and try again."

/**
 * Every id a list page can name this conversation by: the live tip and the
 * compression lineage root it was filed under before.
 */
private fun SessionSummary.flagWriteKeys(): List<String> =
    listOfNotNull(id, lineageRootId?.takeIf { it.isNotBlank() && it != id })

private const val SESSION_PAGE_SIZE = 50

/**
 * How many archived rows one lookup asks for.
 *
 * Desktop asks for 200 (`store/sidebar-archive.ts:9`), which its route allows:
 * `/api/profiles/sessions` caps at 500 precisely because "real desktop callers
 * use limit=200" (`hermes_cli/web_routers/profiles.py:222-228` @ `3ca096de`).
 * This app reads one profile leg at a time through `/api/sessions`, which caps
 * at 100 (`hermes_cli/web_routers/sessions.py:91-94`), so 100 is the whole
 * window that route will give — and the same cap this client already enforces
 * (`MAX_SESSION_PAGE`).
 */
private const val ARCHIVED_POOL_SIZE = MAX_SESSION_PAGE

/**
 * One transcript page — the tail a session first paints, and the size of every
 * older page `Show earlier messages` asks for.
 *
 * Desktop's own hydration page (`LATEST_SESSION_MESSAGES_LIMIT = 120`,
 * `apps/desktop/src/api/sessions.ts:415` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`): "enough tail to fill the
 * transcript window a few times over, small enough that opening a long session
 * doesn't ship hundreds of rows nobody has scrolled to". Well inside the
 * route's own 500-row ceiling (`hermes_cli/web_routers/sessions.py:671`).
 */
private const val TRANSCRIPT_PAGE = 120

/** The one status that means "this backend does not have that route". */
private const val HTTP_NOT_FOUND = 404
private val STATUS_WHITESPACE = Regex("\\s+")
private val KNOWN_STATUS_UPDATE_KINDS = setOf("compacting", "compacted", "process", "goal", "progress", "thinking")
private val NO_GOAL_STATUS = Regex("^(?:No active goal|No goal (?:set|to resume)|✓ Goal cleared)\\b.*", RegexOption.IGNORE_CASE)
private val GOAL_SET_STATUS = Regex("^⊙ Goal set(?:\\s*\\([^)]*\\))?:\\s*(.+)$")
private val GOAL_ACTIVE_STATUS = Regex("^⊙ Goal\\s*\\([^)]*active[^)]*\\):\\s*(.+)$", RegexOption.IGNORE_CASE)
private val GOAL_RESUMED_STATUS = Regex("^▶ Goal resumed:\\s*(.+)$")
private val GOAL_WAITING_STATUS = Regex("^⏳ Goal\\s*\\([^)]*(?:parked|active)[^)]*\\):\\s*(.+)$", RegexOption.IGNORE_CASE)
private val GOAL_PAUSED_STATUS = Regex("^⏸ Goal(?:\\s*\\([^)]*\\)| paused)?:\\s*(.+)$", RegexOption.IGNORE_CASE)
private val GOAL_DONE_STATUS = Regex("^✓ Goal done\\s*\\([^)]*\\):\\s*(.+)$", RegexOption.IGNORE_CASE)
private val CONTINUING_GOAL_STATUS = Regex("^↻ Continuing toward goal\\b", RegexOption.IGNORE_CASE)
private val PARKED_GOAL_STATUS = Regex("^⏳ Goal parked\\b", RegexOption.IGNORE_CASE)
private val PAUSED_GOAL_NOTICE = Regex("^⏸ Goal paused\\b", RegexOption.IGNORE_CASE)
private val ACHIEVED_GOAL_STATUS = Regex("^✓ Goal achieved\\b", RegexOption.IGNORE_CASE)
internal val RESUMED_BUSY_STATUSES = setOf(
    SessionStatus.Working,
    SessionStatus.Stalled,
    SessionStatus.NeedsInput,
    SessionStatus.Background,
)
private val LIVE_RUNTIME_EVENT_TYPES = setOf(
    "session.info",
    "session.usage",
    "message.start",
    "message.delta",
    "message.complete",
    "reasoning.delta",
    "reasoning.available",
    "thinking.delta",
    "tool.start",
    "tool.progress",
    "tool.complete",
    "error",
)
private val EPOCH_SECONDS_CUTOFF = BigDecimal("10000000000")
private val TOOL_FAILURE_STATUSES = setOf("timeout", "error", "failed", "failure")
private val PROCESS_FAILURE_STATUSES = setOf("failed", "failure", "error")
private val PROCESS_DONE_STATUSES = setOf("done", "complete", "completed", "killed", "stopped")
private val REASONING_CONTENT_TYPES = setOf("reasoning", "reasoning_text", "thinking")
private val FALSEY_SIGNAL_STRINGS = setOf("", "0", "false", "none", "null")
private val REMOTE_STORAGE_ERROR_MARKERS = setOf("disk full", "no space left on device", "errno 28")
