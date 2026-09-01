package com.hermesagent.mobile.data.gateway

/**
 * One turn reached a terminal frame.
 *
 * This exists because a completed turn and a failed turn both settle the
 * session to [com.hermesagent.mobile.data.session.SessionStatus.Idle], so the
 * cache alone cannot tell an app-scoped follower which of the two happened —
 * and Desktop raises a different notification for each (`turnDone` at
 * `apps/desktop/src/app/session/hooks/use-message-stream/index.ts:772`,
 * `turnError` at `.../gateway-event.ts:1661`, both @
 * `936b970e281d5d28e930c5698f36bc4ebb54c7ba`).
 *
 * It is a signal, not state: nothing renders from it, nothing persists it, and
 * a follower that misses one has missed a notification, not a fact.
 */
data class GatewayTurnOutcome(
    val durableSessionId: String,
    /** True for a terminal `error` frame or a `message.complete` carrying one. */
    val failed: Boolean,
)
