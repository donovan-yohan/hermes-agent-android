package com.hermesagent.mobile.data.session

/**
 * The shapes the chat surface renders.
 *
 * These are deliberately modelled on what the Hermes gateway is authoritative
 * for (`apps/desktop/AGENTS.md`, "Decide state by authority" @ `3ca096de`),
 * not on UI-local convenience. The live Gateway repository maps protocol data
 * into these types while [SessionCache] preserves backend authority.
 */

/**
 * Session dot states, one for one with Desktop's
 * `apps/desktop/src/app/chat/session-status-dot.tsx:29-77`. Desktop resolves a
 * single winner before rendering, so this is a state, not a set of flags.
 */
enum class SessionStatus {
    /** Nothing has ever run here. */
    Idle,

    /** The turn is running. */
    Working,

    /** Authoritatively running, but nothing has arrived for the watchdog window. */
    Stalled,

    /** A clarify/approval is blocking the turn — the one "act now" state. */
    NeedsInput,

    /** A background process outlived the turn. */
    Background,

    /** The turn finished while the user was looking elsewhere. */
    Unread,
}

/**
 * The one dot a row paints, resolved from every source that claims it.
 *
 * Unread has two sources and Desktop resolves them in one place rather than at
 * each call site, so the sidebar, the tabs and the switcher cannot disagree
 * (`apps/desktop/src/store/session-dot-state.ts:19-23,131-158` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`): this client's transient
 * finished-turn marker ([SessionStatus.Unread]) and the backend's durable read
 * watermark ([SessionSummary.unread]). Both claim the same tier — "there is
 * something here you haven't opened" — and everything louder (a background
 * process, a live turn, a blocking prompt) claims over both, which is why the
 * watermark only speaks for a row that is otherwise idle.
 *
 * A row whose payload omits `unread` is read. Null is "this Gateway never
 * said", never "unread".
 */
fun SessionSummary.displayStatus(): SessionStatus =
    if (status == SessionStatus.Idle && unread == true) SessionStatus.Unread else status

/**
 * Whether either unread source claims this row — the question the read-state
 * menu item asks, which is **not** the question the dot asks.
 *
 * Desktop reads the two sources raw for the item: `unread || isUnread`, where
 * `unread` is the row's own flag and `isUnread` is membership of
 * `$unreadFinishedSessionIds`
 * (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:314-315,319` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). [displayStatus] is the dot's
 * question, where a louder state outranks unread; using it here would leave a
 * row that is working, backgrounded or waiting on input offering `Mark as
 * unread` while its watermark already says unread — and no way to clear it.
 */
fun SessionSummary?.isUnread(): Boolean =
    this != null && (unread == true || status == SessionStatus.Unread)

/**
 * A row in the session list.
 *
 * @param id the durable identity — what navigation, persistence and the
 *   transcript map key off. Desktop keeps runtime and durable ids separate
 *   (`apps/desktop/AGENTS.md`, "Identity is not incidental"). Runtime ids stay
 *   connection-scoped in the Gateway repository and never become navigation
 *   identity here.
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val lastActiveAtMillis: Long,
    val messageCount: Int = 0,
    val source: String? = null,
    val remoteProfile: String? = null,
    val status: SessionStatus = SessionStatus.Idle,
    /** Latest coalesced Gateway `status.update` (`{kind,text}`), if useful. */
    val progress: SessionProgress? = null,
    /**
     * Server-reported git branch for this session's working directory,
     * carried by authoritative `session.info` events. Null when the Gateway
     * did not report one (older servers, detached worktrees, no repository).
     */
    val gitBranch: String? = null,
    /**
     * Exact session cwd reported by `session.info`. It remains connection-scoped
     * and is the only path Android may submit to authenticated git status APIs.
     */
    val worktreePath: String? = null,
    /**
     * Connection-scoped composer material projected from the live Gateway.
     * It is deliberately separate from transcript truth and is cleared when
     * the live turn or Gateway connection settles.
     */
    val composerStatus: ComposerStatusState? = null,
    /** Client-observed start of the current live turn, used only for its visible timer. */
    val activityStartedAtMillis: Long? = null,
    /**
     * Durable server-side soft-archive flag (`sessions.archived`, exposed as a
     * real JSON boolean at hermes-agent @ `3ca096de`,
     * `hermes_cli/web_routers/sessions.py:154`).
     *
     * Null is not `false`: it means this Gateway's list contract never said.
     * The `session.list` RPC an older Gateway answers carries no such column
     * (`tui_gateway/methods_session.py:267-282`), and a surface that read a
     * silent contract as "not archived" would draw an affordance that does
     * nothing. Absent, so the affordance can stay absent.
     */
    val archived: Boolean? = null,
    /**
     * Durable server-side pin (`sessions.pinned`, `sessions.py:155`). The list
     * route back-fills pinned rows past its own LIMIT
     * (`hermes_state.py:9092-9099`), so on a Gateway that reports it, a pinned
     * conversation is present in every page. Null means "no opinion" — see
     * [archived].
     */
    val pinned: Boolean? = null,
    /**
     * Backend read watermark: `last_read_at` against `last_active`, derived per
     * surfaced conversation (`hermes_state.py:9400-9401`, `session_unread` at
     * `:8455`).
     *
     * Deliberately not [SessionStatus.Unread]. That status is this client's
     * foreground-isolation dot — a turn *this app* started finished while the
     * user was looking at another session — and it is connection-scoped. This
     * is durable, cross-client server truth about whether the conversation has
     * been read anywhere. Null means the Gateway does not report it.
     */
    val unread: Boolean? = null,
    /** Model the row's live tip last ran on (`sessions.model`); null when unreported. */
    val model: String? = null,
    /**
     * Tool calls counted on the conversation. Null rather than `0` when the
     * contract does not carry the column, because Desktop's metadata line drops
     * an absent field and renders `0` as nothing either way
     * (`apps/desktop/src/app/chat/sidebar/session-row-details.ts:24-31` @ the
     * pin) — a zero invented here would be indistinguishable from a real one.
     */
    val toolCallCount: Int? = null,
    /** Prompt tokens billed to the conversation; null when unreported. */
    val inputTokens: Long? = null,
    /** Completion tokens billed to the conversation; null when unreported. */
    val outputTokens: Long? = null,
    /**
     * Spend straight off the `sessions` row: `actual` when the provider quoted
     * a price, `estimated` from Hermes' own pricing table. Both are genuinely
     * `0.0` on subscription auth that never quotes one, which is why they are
     * carried separately and why null (unreported) is not folded into zero.
     */
    val actualCostUsd: Double? = null,
    val estimatedCostUsd: Double? = null,
    /**
     * Original root id of a compression chain when [id] is a projected
     * continuation tip (`hermes_state.py:9392`). Stable across compressions, so
     * it is the durable id a pin is stored against — and the id an earlier
     * refresh may have filed this conversation under. Never navigation
     * identity: [id] is.
     */
    val lineageRootId: String? = null,
    /** Latest coalesced Gateway `usage` from `session.info` or `session.usage`. */
    val usage: SessionUsage? = null,
)

/** A transient backend notice rendered at the live turn tail, never stored as transcript content. */
data class SessionProgress(
    val kind: String,
    val text: String,
)

/**
 * Live, session-scoped status material for the composer stack. Gateway status
 * is not transcript content and none of these rows are persisted as local
 * user state.
 */
data class ComposerStatusState(
    val goal: ComposerGoalStatus? = null,
    val todos: List<ComposerTodoStatus> = emptyList(),
    val subagents: List<ComposerSubagentStatus> = emptyList(),
    val backgroundProcesses: List<ComposerBackgroundProcess> = emptyList(),
    val previewArtifacts: List<ComposerPreviewArtifact> = emptyList(),
    /** Accepted for a later turn; retained across reconnect, but never persisted locally. */
    val gatewayQueuedPrompts: List<ComposerGatewayQueuedPrompt> = emptyList(),
    val isCompacting: Boolean = false,
)

data class ComposerGatewayQueuedPrompt(
    val id: String,
    val text: String,
    /** Local identity for the inferred server envelope containing this occurrence. */
    val gatewayBatchId: String = id,
    /** Whether another text-only occurrence may still merge into this envelope. */
    val gatewayBatchMergeable: Boolean = false,
)

/** Keep only accepted Gateway queue rows; null when no queue remains visible. */
internal fun ComposerStatusState?.retainingGatewayQueue(): ComposerStatusState? =
    this?.gatewayQueuedPrompts
        ?.takeIf(List<ComposerGatewayQueuedPrompt>::isNotEmpty)
        ?.let { ComposerStatusState(gatewayQueuedPrompts = it) }

enum class ComposerGoalState { Active, Waiting, Paused, Done, None, Unknown }

/** Safe, bounded raw Gateway text plus the deliberately conservative parser result. */
data class ComposerGoalStatus(
    val rawText: String,
    val state: ComposerGoalState,
    val title: String? = null,
    val detail: String? = null,
)

enum class ComposerTodoState { Pending, InProgress, Completed, Cancelled, Unknown }

data class ComposerTodoStatus(
    val id: String,
    val title: String,
    val state: ComposerTodoState,
)

data class ComposerSubagentStatus(
    val id: String,
    val title: String,
    val currentTool: String? = null,
)

enum class ComposerBackgroundProcessState { Running, Done, Failed }

data class ComposerBackgroundProcess(
    val id: String,
    val title: String,
    val state: ComposerBackgroundProcessState,
    val exitCode: Int? = null,
    val output: String? = null,
)

data class ComposerPreviewArtifact(
    val id: String,
    val title: String,
    val detail: String? = null,
)

/**
 * The Gateway's durable address for one persisted message — the `messages.id`
 * it stamps onto a history row when the transcript is read with row ids
 * (NousResearch/hermes-agent @ `3ca096de`,
 * `tui_gateway/methods_session.py:2611-2620`: "the durable row id is how
 * clients address a specific persisted turn"; the wire value is projected at
 * `tui_gateway/server.py:7752-7758`).
 *
 * A separate type because it is not interchangeable with [TranscriptEntry.id]:
 * that id is a rendering key this client mints, and it exists for every row —
 * live, optimistic or rehydrated. This one exists only for a row the backend
 * has actually written down, which is what an addressed action (rewind,
 * reactions, backfill dedupe, read-aloud) needs.
 */
@JvmInline
value class TranscriptRowId(val value: Long)

/** One block in a transcript. */
sealed interface TranscriptEntry {
    /**
     * The rendering key. Locally minted — stable within a session's transcript
     * but meaningless to the backend, and never an address to send back.
     */
    val id: String

    /**
     * The durable row this entry was projected from, or null when the backend
     * has not written it down (a live or optimistic row) or the Gateway does
     * not stamp row ids. Null is the honest answer; nothing here is ever
     * invented from a local id.
     *
     * One persisted row can project to more than one entry — an assistant row
     * carrying reasoning yields both a [ReasoningActivity] and an
     * [AssistantTurn] — so this addresses the row, not the entry, and is not
     * unique across a transcript. [id] remains the per-entry key.
     */
    val rowId: TranscriptRowId?
}

data class UserTurn(
    override val id: String,
    val text: String,
    val atMillis: Long,
    override val rowId: TranscriptRowId? = null,
) : TranscriptEntry

data class AssistantTurn(
    override val id: String,
    val markdown: String,
    val atMillis: Long,
    /** True while deltas are still arriving — drives the streaming cursor. */
    val streaming: Boolean = false,
    /** Set when the turn ended badly. Rendered as an honest error, not a retry spinner. */
    val error: String? = null,
    /**
     * Why this turn stopped before ordinary completion, if the Gateway made
     * that fact available. Only [TurnTermination.UserRequested] means this
     * client sent the stop request; the other values are external endings.
     */
    val termination: TurnTermination? = null,
    override val rowId: TranscriptRowId? = null,
) : TranscriptEntry

/** A terminal turn cause whose attribution is safe to show in the transcript. */
enum class TurnTermination {
    /** This client sent `session.interrupt` for the live runtime. */
    UserRequested,
    /** The Gateway reaped the WebSocket-owned runtime after its orphan grace. */
    WsOrphanReap,
    /** The Gateway ended an idle runtime. */
    IdleTimeout,
    /** The Gateway evicted the runtime under its live-session limit. */
    LruEvict,
    /** The Gateway reclaimed the runtime but supplied no recognized reason. */
    Reclaimed,
    /** A Gateway state snapshot settled the turn without a completion event. */
    SessionNoLongerRunning,
    /** The Gateway reported an interrupted turn this client did not stop. */
    InterruptedExternally,
}

/** Provider reasoning, kept separate from answer prose like Desktop's reasoning disclosure. */
data class ReasoningActivity(
    override val id: String,
    val text: String,
    val state: ToolState,
    val startedAtMillis: Long? = null,
    val elapsedSeconds: Double = 0.0,
    override val rowId: TranscriptRowId? = null,
) : TranscriptEntry

/** A tool run, rendered as scaffolding rather than as a message. */
data class ToolActivity(
    override val id: String,
    val label: String,
    val detail: String,
    val state: ToolState,
    val elapsedSeconds: Double = 0.0,
    val toolName: String = label,
    val argsText: String? = null,
    val resultText: String? = null,
    val inlineDiff: String? = null,
    val startedAtMillis: Long? = null,
    override val rowId: TranscriptRowId? = null,
) : TranscriptEntry

enum class ToolState { Running, Done, Failed, Stopped }

/**
 * The same entry addressed at [rowId]. A `when` over the sealed variants rather
 * than a member, because the durable address belongs to the row a variant was
 * projected from, not to the variant's own shape.
 */
internal fun TranscriptEntry.withRowId(rowId: TranscriptRowId?): TranscriptEntry = when (this) {
    is UserTurn -> copy(rowId = rowId)
    is AssistantTurn -> copy(rowId = rowId)
    is ReasoningActivity -> copy(rowId = rowId)
    is ToolActivity -> copy(rowId = rowId)
}

/**
 * This entry, keeping the durable address [existing] already holds when this
 * one has none.
 *
 * A replace keyed on the rendering id is wholesale, so an unstamped entry
 * landing on a stamped one would erase the only address the backend gave us for
 * that row (`transcript-backfill.ts:44-57` @ `3ca096de` dedupes on exactly that
 * address). Null on the incoming entry means "the backend has not written this
 * down yet", never "this row has no durable identity" — so it may not overwrite
 * one that was.
 */
internal fun TranscriptEntry.preservingRowIdOf(existing: TranscriptEntry): TranscriptEntry =
    if (rowId != null || existing.rowId == null) this else withRowId(existing.rowId)
