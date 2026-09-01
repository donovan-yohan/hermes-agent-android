package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.ApprovalPending
import com.hermesagent.mobile.data.gateway.GatewayTurnOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.session.SessionCacheState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Raises OS notifications for the one live Gateway connection.
 *
 * A port of Desktop's `dispatchNativeNotification`
 * (`apps/desktop/src/store/native-notifications.ts:190-223` @
 * `29112bef099274229cadff79cdff7bf7b99c4b77`) with its four guards in the same
 * order: preferences (`:193`), the post-connect quiet window (`:197`), the
 * foreground/active-session rule (`:201`, implemented at `:131-148`), then the
 * one-second throttle (`:205`).
 *
 * It follows the repository rather than any transport, so Remote, Managed SSH
 * and Local behave identically — all three deliver the same events over the
 * same socket, and this class has never heard of any of them.
 *
 * Deliberately Android-free. The gating rules are the part that is easy to get
 * subtly wrong and expensive to debug on a device, so they are testable on
 * virtual time with a recording [NotificationSurface] and nothing else.
 */
class SessionNotifier(
    private val pendingInputs: StateFlow<Map<PendingInputKey, PendingInputRequest>>,
    private val turnOutcomes: Flow<GatewayTurnOutcome>,
    private val sessions: StateFlow<SessionCacheState>,
    /** One emission per socket open, including the first. Opens the quiet window. */
    private val socketOpens: Flow<Unit>,
    private val presence: NotificationPresence,
    private val settingsFlow: Flow<NotificationSettings>,
    private val surface: NotificationSurface,
    private val clock: () -> Long,
) {
    /** One parked request as the shade needs it: what it is, and how to answer it. */
    private data class Prompt(val key: PendingInputKey, val approval: ApprovalTarget?)

    private sealed interface Signal {
        data object SocketOpen : Signal
        data class Pending(val requests: Map<PendingInputKey, PendingInputRequest>) : Signal
        data class Turn(val outcome: GatewayTurnOutcome) : Signal
        data class Present(val foregrounded: Boolean, val visibleSessionId: String?) : Signal
        data class Settings(val settings: NotificationSettings) : Signal
        data class QuietWindowExpired(val generation: Long) : Signal
    }

    private var quietUntil = 0L
    private var quietGeneration = 0L

    /**
     * Desktop reads its preferences synchronously and falls back to the
     * documented defaults when it cannot (`native-notifications.ts:51-79`).
     * A disk-backed store cannot be read synchronously, so the same defaults
     * stand until its first emission — and they are all-on, so the failure
     * mode is a notification the user could have turned off, never a silent
     * one they wanted.
     */
    private var settings = NotificationSettings()

    /**
     * Self-evicting throttle map, Desktop's `lastFiredAt` (`:98-114`). Entries
     * older than the window are pruned on every dispatch, so it cannot grow.
     */
    private val lastFiredAt = mutableMapOf<String, Long>()

    /**
     * The prompt notifications actually in the shade, and — for an approval —
     * which request their buttons would answer. Keyed by (session, kind)
     * because that is the notification's identity: a superseding request
     * updates the same notification in place rather than stacking a second one.
     *
     * Only notifications that were really posted belong here. Recording
     * suppressed ones too would wedge them: an approval that arrived while the
     * user was looking at its conversation would be remembered as shown, and
     * could never be raised once they left.
     *
     * Prompts only. A completion is not derived from the pending map, so
     * filing one here would make the next pending update withdraw it for the
     * sole reason that no pending request corresponds to it.
     */
    private var shown = mapOf<Pair<String, NotificationKind>, Prompt>()

    /**
     * Prompts that have successfully posted a notification to the shade.
     * Used to deduplicate across reconnects so a prompt already notified
     * before disconnect is not re-announced when replayed on the new socket.
     */
    private val notified = mutableSetOf<PromptIdentity>()

    /** Latest pending inputs map received from the repository. */
    private var latestPending = mapOf<PendingInputKey, PendingInputRequest>()

    private val quietExpiries = MutableSharedFlow<Signal.QuietWindowExpired>(extraBufferCapacity = 16)
    private var quietJob: Job? = null
    private var runningScope: CoroutineScope? = null
    private var collectorJob: Job? = null

    /**
     * Every signal through one collector, so the quiet window, the throttle map
     * and [shown] are only ever touched from one coroutine. Merging is not a
     * style choice: three independent collectors mutating this state would make
     * the ordering of a reconnect against its own prompt replay a race.
     */
    fun start(scope: CoroutineScope): Job {
        collectorJob?.let { return it }
        runningScope = scope
        // Process start is itself a baseline: whatever the socket replays in
        // the next few seconds is state that already existed.
        markBaseline()
        return scope.launch {
            merge(
                socketOpens.map { Signal.SocketOpen },
                pendingInputs.map(Signal::Pending),
                turnOutcomes.map(Signal::Turn),
                combine(presence.appForegrounded, presence.visibleSessionId, Signal::Present)
                    .distinctUntilChanged(),
                settingsFlow.map(Signal::Settings),
                quietExpiries,
            ).collect { signal ->
                when (signal) {
                    Signal.SocketOpen -> markBaseline()
                    is Signal.Pending -> {
                        val previousPending = latestPending
                        latestPending = signal.requests
                        applyPending(signal.requests, previousPending)
                    }
                    is Signal.Turn -> applyTurn(signal.outcome)
                    is Signal.Present -> {
                        applyPresence(signal.foregrounded, signal.visibleSessionId)
                        applyPending(latestPending, latestPending)
                    }
                    is Signal.Settings -> {
                        settings = signal.settings
                        applyPending(latestPending, latestPending)
                    }
                    is Signal.QuietWindowExpired -> {
                        // Ignore stale expiry from a cancelled quiet window that was already buffered.
                        if (signal.generation == quietGeneration) {
                            quietUntil = 0L
                            applyPending(latestPending, latestPending)
                        }
                    }
                }
            }
        }.also { collectorJob = it }
    }

    /**
     * `store/notify-baseline.ts:14-26` @ the pin. A socket opening replays state
     * that already existed — a session parked on an approval re-emits its
     * request — and those are not things that just happened. Mobile needs this
     * more than Desktop does, not less: there is no stream resume, so every
     * reconnect is a full replay.
     */
    private fun markBaseline() {
        val scope = runningScope ?: return
        val generation = ++quietGeneration
        quietUntil = clock() + SEED_QUIET_MS
        quietJob?.cancel()
        quietJob = scope.launch {
            delay(SEED_QUIET_MS)
            quietExpiries.emit(Signal.QuietWindowExpired(generation))
        }
    }

    private fun applyPending(
        requests: Map<PendingInputKey, PendingInputRequest>,
        previousPending: Map<PendingInputKey, PendingInputRequest> = latestPending,
    ) {
        val desired = mutableMapOf<Pair<String, NotificationKind>, Pair<Prompt, PromptIdentity>>()
        for ((key, request) in requests) {
            val kind = key.kind.notificationKind()
            val identity = request.durableSessionId to kind
            // First writer wins for a session with two runtimes parked on the
            // same kind: one notification per (session, kind), and answering it
            // resolves one of them rather than an arbitrary merge of both.
            if (identity in desired) continue
            desired[identity] = Prompt(
                key = key,
                approval = (request as? ApprovalPending)?.let { ApprovalTarget(key, it.durableSessionId) },
            ) to request.promptIdentity()
        }

        for (identity in shown.keys) {
            if (identity !in desired) surface.clear(identity.second, identity.first)
        }

        val next = shown.filterKeys { it in desired }.toMutableMap()
        for ((identity, pair) in desired) {
            val (prompt, promptIdentity) = pair
            // A different request id under the same identity is the Gateway
            // replacing the question, not repeating it. None of the "already
            // dealt with" rules below apply to a request nobody has seen.
            val supersedes = shown[identity]?.key?.let { it != prompt.key } == true
            if (!supersedes) {
                if (identity in shown) continue
                // Deduplicate across reconnects: if already notified pre-disconnect,
                // do not re-notify.
                if (promptIdentity in notified) continue
                // Suppress immediate firing during quiet window; defer until window closes.
                if (clock() < quietUntil) {
                    continue
                }
            }
            // The throttle is bypassed for a supersession: the shade's buttons
            // would otherwise keep pointing at a request id the Gateway has
            // already replaced, and pressing one would answer nothing.
            val posted = dispatch(identity.second, identity.first, prompt.approval, bypassThrottle = supersedes)
            if (posted) {
                next[identity] = prompt
                notified += promptIdentity
            } else {
                next.remove(identity)
            }
        }
        shown = next

        // Prune resolved prompts on observed resolution (present in previous pending, absent from current)
        // rather than set difference against current requests, so incremental replays on reconnect
        // ({A} -> {A, B}) do not evict un-replayed identities before they arrive.
        // During disconnects, requests is empty, so we preserve deduplication across reconnects.
        if (requests.isNotEmpty()) {
            // Only a disappearance *within one connection generation* is a
            // resolution. `pendingInputs` is a conflating StateFlow, so a
            // reconnect's empty-map wipe can be swallowed between collections,
            // leaving the old generation's keys diffed against the new
            // generation's replay — where every identity looks resolved, the
            // whole dedupe set is pruned, and every parked prompt is announced
            // a second time.
            val generations = requests.keys.mapTo(mutableSetOf()) { it.connectionGeneration }
            for ((prevKey, prevReq) in previousPending) {
                if (prevKey.connectionGeneration !in generations) continue
                if (prevKey !in requests) {
                    notified.remove(prevReq.promptIdentity())
                }
            }
        }
    }

    private fun applyTurn(outcome: GatewayTurnOutcome) {
        // A failed turn is `turnError`, a separate kind with separate copy and
        // its own preference. Until that row ships, a failure notifies nothing
        // rather than claiming Hermes finished.
        if (outcome.failed) return
        dispatch(NotificationKind.TurnDone, outcome.durableSessionId, approval = null)
    }

    private fun applyPresence(foregrounded: Boolean, visibleSessionId: String?) {
        // Opening a conversation is reading it. Nothing about it is still news.
        if (!foregrounded || visibleSessionId == null) return
        surface.clearSession(visibleSessionId)
        // Those notifications are gone from the shade, so they are gone from
        // the record of what is in it. Keeping them would mean a prompt still
        // parked when the user leaves again could never be raised a second
        // time, because it would look like it was already showing.
        shown = shown.filterKeys { it.first != visibleSessionId }
        notified.removeAll { it.durableSessionId == visibleSessionId }
    }

    /**
     * Desktop's `dispatchNativeNotification`, guard for guard (`:190-223`).
     * Returns whether the notification reached the surface, exactly as
     * Desktop's does (`:187-190`) and for the same reason: the caller records
     * per-notification state that a suppressed one must never acquire.
     */
    private fun dispatch(
        kind: NotificationKind,
        durableSessionId: String,
        approval: ApprovalTarget?,
        bypassThrottle: Boolean = false,
    ): Boolean {
        if (!settings.allows(kind)) return false
        if (clock() < quietUntil) return false
        if (!shouldFire(kind, durableSessionId)) return false
        if (!allowedByThrottle("${kind.key}:$durableSessionId", clock(), bypassThrottle)) return false

        val title = sessions.value.sessions[durableSessionId]?.title.orEmpty().notificationSafeTitle()
        surface.post(
            NotificationPost(
                kind = kind,
                durableSessionId = durableSessionId,
                title = NotificationCopy.title(kind),
                body = title.ifBlank { NotificationCopy.fallbackBody(kind) },
                approval = approval,
            ),
        )
        return true
    }

    /**
     * `native-notifications.ts:131-148` @ the pin, with `isBackgrounded()` read
     * as "no resumed Activity" and `$activeSessionId` as the conversation the
     * chat surface has open.
     *
     * Attention kinds break through for an off-screen session even while focused.
     * Completion kinds notify for any session when the app is backgrounded,
     * allowing finishing turns to alert even if the user backgrounded from the
     * session list or a non-chat surface where no session was visible.
     */
    private fun shouldFire(kind: NotificationKind, durableSessionId: String): Boolean {
        val backgrounded = !presence.appForegrounded.value
        val visible = presence.visibleSessionId.value

        // Attention kinds break through for an off-screen session even while focused.
        if (kind in ATTENTION_KINDS) return backgrounded || durableSessionId != visible

        // Completion kinds notify for any session when backgrounded; suppressed while foregrounded.
        return backgrounded
    }

    /**
     * The throttle, plus the one exemption mobile needs.
     *
     * A bypass still restarts the window: the notification did fire, so the
     * next ordinary one for this pair should be measured from now rather than
     * from the post it replaced.
     */
    private fun allowedByThrottle(key: String, now: Long, bypass: Boolean): Boolean {
        if (!throttled(key, now)) return true
        if (!bypass) return false
        lastFiredAt[key] = now
        return true
    }

    /** `native-notifications.ts:100-114` @ the pin. */
    private fun throttled(key: String, now: Long): Boolean {
        lastFiredAt.entries.removeAll { now - it.value >= THROTTLE_MS }
        if (key in lastFiredAt) return true
        lastFiredAt[key] = now
        return false
    }
}

/**
 * Deduplication identity for a pending prompt across reconnects.
 *
 * `runtimeSessionId` is excluded because a reconnect replay can carry a fresh
 * runtime ID for the same underlying prompt request. That deliberately widens
 * the identity — two prompts differing only by runtime read as one — which is
 * the trade this dedupe exists to make, and it holds only as far as identities
 * are pruned when their request is observed to resolve in [applyPending].
 *
 * One case is never pruned there. When the *last* outstanding request resolves,
 * the repository reports an empty map, and an empty map is skipped, because a
 * disconnect empties it the same way and must not wipe the dedupe set. Nothing
 * on the pending path removes that identity afterwards: it is cleared only when
 * the user opens that conversation with the app foregrounded, where
 * [applyPresence] drops every identity for the visible session. So the leak is
 * one entry per identity that was the last one outstanding, bounded by the
 * distinct prompts a connection saw, and its cost is that re-parking that exact
 * request stays silent until the conversation is read.
 */
private data class PromptIdentity(
    val durableSessionId: String,
    val requestId: String,
    val kind: PendingInputKind,
)

private fun PendingInputRequest.promptIdentity(): PromptIdentity =
    PromptIdentity(durableSessionId, key.requestId, key.kind)

/**
 * Desktop files clarify, sudo and secret prompts under one `input` kind
 * (`.../gateway-event/input-requests.ts:101-106`, `:149-154`, `:282-287`,
 * `:313-318` @ the pin), and so
 * does this. They are the same question to the user: something is blocked
 * until you answer it in the app.
 */
private fun PendingInputKind.notificationKind(): NotificationKind = when (this) {
    PendingInputKind.Approval -> NotificationKind.Approval
    PendingInputKind.Clarify, PendingInputKind.Sudo, PendingInputKind.Secret -> NotificationKind.Input
}

/** `native-notifications.ts:97` @ the pin. */
private const val THROTTLE_MS = 1_000L

/** `store/notify-baseline.ts:14` @ the pin. */
private const val SEED_QUIET_MS = 4_000L
