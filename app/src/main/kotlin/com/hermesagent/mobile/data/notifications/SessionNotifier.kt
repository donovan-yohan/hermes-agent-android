package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.ApprovalPending
import com.hermesagent.mobile.data.gateway.GatewayTurnOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.session.SessionCacheState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`) with its four guards in the same
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
    private sealed interface Signal {
        data object SocketOpen : Signal
        data class Pending(val requests: Map<PendingInputKey, PendingInputRequest>) : Signal
        data class Turn(val outcome: GatewayTurnOutcome) : Signal
        data class Present(val foregrounded: Boolean, val visibleSessionId: String?) : Signal
        data class Settings(val settings: NotificationSettings) : Signal
    }

    private var quietUntil = 0L

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
     * What is currently parked, and — for an approval — which request the
     * shade's buttons would answer. Keyed by (session, kind) because that is
     * the notification's identity: a superseding request updates the same
     * notification in place rather than stacking a second one.
     */
    private var shown = mapOf<Pair<String, NotificationKind>, ApprovalTarget?>()

    /**
     * Every signal through one collector, so the quiet window, the throttle map
     * and [shown] are only ever touched from one coroutine. Merging is not a
     * style choice: three independent collectors mutating this state would make
     * the ordering of a reconnect against its own prompt replay a race.
     */
    fun start(scope: CoroutineScope): Job {
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
            ).collect { signal ->
                when (signal) {
                    Signal.SocketOpen -> markBaseline()
                    is Signal.Pending -> applyPending(signal.requests)
                    is Signal.Turn -> applyTurn(signal.outcome)
                    is Signal.Present -> applyPresence(signal.foregrounded, signal.visibleSessionId)
                    is Signal.Settings -> settings = signal.settings
                }
            }
        }
    }

    /**
     * `store/notify-baseline.ts:14-26` @ the pin. A socket opening replays state
     * that already existed — a session parked on an approval re-emits its
     * request — and those are not things that just happened. Mobile needs this
     * more than Desktop does, not less: there is no stream resume, so every
     * reconnect is a full replay.
     */
    private fun markBaseline() {
        quietUntil = clock() + SEED_QUIET_MS
    }

    private fun applyPending(requests: Map<PendingInputKey, PendingInputRequest>) {
        val desired = mutableMapOf<Pair<String, NotificationKind>, ApprovalTarget?>()
        for ((key, request) in requests) {
            val kind = key.kind.notificationKind()
            val identity = request.durableSessionId to kind
            // First writer wins for a session with two runtimes parked on the
            // same kind: one notification per (session, kind), and answering it
            // resolves one of them rather than an arbitrary merge of both.
            if (identity in desired) continue
            desired[identity] = (request as? ApprovalPending)
                ?.let { ApprovalTarget(key, it.durableSessionId) }
        }

        for ((identity, _) in shown) {
            if (identity !in desired) surface.clear(identity.second, identity.first)
        }
        for ((identity, target) in desired) {
            // A superseding request changes the target without changing the
            // identity; re-dispatching keeps the buttons pointed at the live one.
            if (identity in shown && shown[identity] == target) continue
            dispatch(identity.second, identity.first, target)
        }
        shown = desired
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
        if (foregrounded && visibleSessionId != null) surface.clearSession(visibleSessionId)
    }

    /** Desktop's `dispatchNativeNotification`, guard for guard (`:190-223`). */
    private fun dispatch(kind: NotificationKind, durableSessionId: String, approval: ApprovalTarget?) {
        if (!settings.allows(kind)) return
        if (clock() < quietUntil) return
        if (!shouldFire(kind, durableSessionId)) return
        if (throttled("${kind.key}:$durableSessionId", clock())) return

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
    }

    /**
     * `native-notifications.ts:131-148` @ the pin, with `isBackgrounded()` read
     * as "no resumed Activity" and `$activeSessionId` as the conversation the
     * chat surface has open.
     *
     * Desktop's `global` branch has no mobile equivalent: it exists for the
     * command center's session-less background runs, which this client cannot
     * start.
     */
    private fun shouldFire(kind: NotificationKind, durableSessionId: String): Boolean {
        val backgrounded = !presence.appForegrounded.value
        val visible = presence.visibleSessionId.value

        // Attention kinds break through for an off-screen session even while focused.
        if (kind in ATTENTION_KINDS) return backgrounded || durableSessionId != visible

        // Completion kinds: only the active session, only while away — so a busy
        // gateway can't raise one notification per background session.
        return backgrounded && durableSessionId == visible
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
 * Desktop files clarify, sudo and secret prompts under one `input` kind
 * (`.../gateway-event.ts:1228`, `:1279`, `:1366`, `:1393` @ the pin), and so
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
