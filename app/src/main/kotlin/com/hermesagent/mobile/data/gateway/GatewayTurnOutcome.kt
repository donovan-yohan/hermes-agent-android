package com.hermesagent.mobile.data.gateway

/**
 * One turn reached a terminal frame.
 *
 * This exists because a completed turn and a failed turn both settle the
 * session to [com.hermesagent.mobile.data.session.SessionStatus.Idle], so the
 * cache alone cannot tell an app-scoped follower which of the two happened —
 * and Desktop raises a different notification for each (`turnDone` at
 * `apps/desktop/src/app/session/hooks/use-message-stream/index.ts:772`,
 * `turnError` at `.../gateway-event/status.ts:140-145`, both @
 * `29112bef099274229cadff79cdff7bf7b99c4b77`).
 *
 * It is a signal, not state: nothing renders from it, nothing persists it, and
 * a follower that misses one has missed a notification, not a fact.
 */
data class GatewayTurnOutcome(
    val durableSessionId: String,
    /** True for a terminal `error` frame or a `message.complete` carrying one. */
    val failed: Boolean,
)
