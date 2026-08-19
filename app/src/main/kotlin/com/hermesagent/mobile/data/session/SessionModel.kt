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
)

/** A transient backend progress notice; one value per session, never a transcript row. */
data class SessionProgress(
    val kind: String,
    val text: String,
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

/** A tool run, rendered as scaffolding rather than as a message. */
data class ToolActivity(
    override val id: String,
    val label: String,
    val detail: String,
    val state: ToolState,
    val elapsedSeconds: Double = 0.0,
) : TranscriptEntry

enum class ToolState { Running, Done, Failed, Stopped }
