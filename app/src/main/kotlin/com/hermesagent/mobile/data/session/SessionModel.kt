package com.hermesagent.mobile.data.session

/**
 * The shapes the chat surface renders.
 *
 * These are deliberately modelled on what the Hermes gateway is authoritative
 * for (`apps/desktop/AGENTS.md`, "Decide state by authority" @ `f82f2dba`),
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
    val isCompacting: Boolean = false,
)

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

/** One block in a transcript. */
sealed interface TranscriptEntry {
    val id: String
}

data class UserTurn(
    override val id: String,
    val text: String,
    val atMillis: Long,
) : TranscriptEntry

data class AssistantTurn(
    override val id: String,
    val markdown: String,
    val atMillis: Long,
    /** True while deltas are still arriving — drives the streaming cursor. */
    val streaming: Boolean = false,
    /** Set when the turn ended badly. Rendered as an honest error, not a retry spinner. */
    val error: String? = null,
    /** Set when the user stopped generation. Desktop keeps the partial text. */
    val stopped: Boolean = false,
) : TranscriptEntry

/** Provider reasoning, kept separate from answer prose like Desktop's reasoning disclosure. */
data class ReasoningActivity(
    override val id: String,
    val text: String,
    val state: ToolState,
    val startedAtMillis: Long? = null,
    val elapsedSeconds: Double = 0.0,
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
) : TranscriptEntry

enum class ToolState { Running, Done, Failed, Stopped }
