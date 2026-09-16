package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a session-less broadcast is *about*. That is the whole of what it says:
 * upstream deliberately neither stamps nor buffers these frames
 * (`tui_gateway/event_replay.py:46-49` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`),
 * and expects the client to refetch through the surface's own RPC. A hint is
 * an edge, never a payload to render.
 */
enum class GatewayChangeHintKind {
    Cron,
    Pet,
    Sessions,
    BotRelayOutbox,
}

/** One session-less broadcast, as a refetch trigger for its own surface. */
data class GatewayChangeHint(
    val kind: GatewayChangeHintKind,
    val payload: JsonElement,
)

/**
 * Which dispatch lane a frame belongs to.
 *
 * The split is by **type**, not by the envelope's `session_id`, and the
 * difference is load-bearing. An empty `session_id` on a session-scoped type is
 * an identifier-less *session* event — the Local route's older frames — and
 * those are attributed to the pinned unscoped runtime inside `applyEvent`
 * (`GatewaySessionRepository.kt`, "identifier-less events stay attributed to
 * the most recent submit"). Steering those to the global lane would silently
 * drop live transcript deltas.
 */
internal enum class GatewayEventLane {
    /** Routed to `applyEvent`, where a runtime session id is resolved. */
    Session,

    /** Broadcast to every client; routed to [GatewayGlobalEventLane]. */
    Global,
}

/**
 * Which module settles one admitted global frame.
 *
 * Every entry in [GatewayGlobalEventType] names exactly one of these, and both
 * the repository's dispatch and this lane's router read it — so a type cannot
 * be admitted without a destination.
 */
internal enum class GatewayGlobalEventOwner {
    /** Settled by [GatewayGlobalEventLane.accept]: the replay epoch, or a change hint. */
    Lane,

    /**
     * Settled by the repository's reclaim path before the lane is reached: a
     * reclaim has to settle and unbind the runtime it names, which needs the
     * identity map the lane does not hold.
     */
    Reclaim,
}

/**
 * The frames the gateway broadcasts as session-less globals, as one table.
 *
 * The subscription allow-list ([GATEWAY_GLOBAL_EVENT_TYPES]), the lane question
 * ([gatewayEventLane]) and the routing on both sides all read this enum, so
 * membership and handling cannot drift: `accept`'s `when` is exhaustive over
 * these entries, and the next type added here fails compilation until a branch
 * names its handler and an owner names who settles it.
 *
 * Two of them are lifecycle, not change hints: `gateway.ready` opens every
 * socket with `skin`, `change_events`, `heartbeat` and `replay_epoch`
 * (`tui_gateway/ws.py:296-303` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`), and `session.reclaimed` is
 * *broadcast* rather than session-targeted because the reap paths run on timer
 * threads with no live transport
 * (`tui_gateway/session_lifecycle.py:286-298` @ the same SHA). Both bypass
 * `write_json`, which is why neither carries a `seq`.
 *
 * The four change hints are polled and broadcast by the change watcher
 * (`tui_gateway/change_watcher.py:177-184` @ the same SHA). The pin also
 * broadcasts `skin.changed`, `platforms.changed` and `pairing.changed`; this
 * client has no surface for them yet, and they stay out of this table rather
 * than being admitted unhandled.
 */
internal enum class GatewayGlobalEventType(val wire: String, val owner: GatewayGlobalEventOwner) {
    GatewayReady("gateway.ready", GatewayGlobalEventOwner.Lane),
    SessionReclaimed("session.reclaimed", GatewayGlobalEventOwner.Reclaim),
    CronChanged("cron.changed", GatewayGlobalEventOwner.Lane),
    PetChanged("pet.changed", GatewayGlobalEventOwner.Lane),
    SessionsChanged("sessions.changed", GatewayGlobalEventOwner.Lane),
    BotRelayOutbox("bot_relay.outbox.pending", GatewayGlobalEventOwner.Lane),
    ;

    companion object {
        /** The table entry for one wire type, or null for a frame this client does not admit. */
        fun fromWire(type: String): GatewayGlobalEventType? =
            entries.firstOrNull { it.wire == type }
    }
}

/**
 * What the socket subscribes to: the wire types of [GatewayGlobalEventType],
 * derived rather than restated so the allow-list cannot drift from the table.
 */
internal val GATEWAY_GLOBAL_EVENT_TYPES: Set<String> =
    GatewayGlobalEventType.entries.map { it.wire }.toSet()

internal fun gatewayEventLane(type: String): GatewayEventLane =
    if (GatewayGlobalEventType.fromWire(type) != null) GatewayEventLane.Global else GatewayEventLane.Session

/**
 * The session-less lane: the dispatch path parallel to `applyEvent`, for frames
 * that carry no runtime to route by.
 *
 * It owns two things and narrates neither.
 *
 * **Replay watermarks.** A session event is stamped with a per-session
 * monotonic `seq` and kept in a bounded ring; `session.events.since` resumes
 * from a client's last seen `seq` (`tui_gateway/event_replay.py:39-60,74-79` @
 * the pin SHA). Those counters are in-process, so a restart resets them to 1
 * while this client still holds high watermarks — replay would answer "nothing
 * newer" forever. `replay_epoch` is the token that makes the restart visible:
 * it is regenerated per process and carried both on `gateway.ready` and on
 * every `session.events.since` answer
 * (`tui_gateway/methods_session.py:2131-2144` @ the pin SHA). A new epoch
 * therefore drops the cached watermarks; a backend that advertises none drops
 * them too, since undetectable is not the same as unchanged.
 *
 * **Change hints.** One [GatewayChangeHint] per broadcast, for surfaces that
 * refetch on resume. `change_events` is deliberately not modelled yet: this
 * client has no legacy poll for it to demote to a backstop, and an unused
 * capability flag would be a claim nothing reads.
 */
internal class GatewayGlobalEventLane {
    private val lock = Any()
    private var epoch: String? = null
    private val lastSeenSeqByRuntime = mutableMapOf<String, Long>()

    /**
     * Buffered and dropping: emitted from under the repository's state lock, so
     * it must never suspend, and a follower slow enough to lose a hint has lost
     * a *reason to refetch* rather than a fact — the next hint, or the surface's
     * own resume, refetches anyway.
     */
    private val hintFlow = MutableSharedFlow<GatewayChangeHint>(
        extraBufferCapacity = HINT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changeHints: Flow<GatewayChangeHint> = hintFlow

    /** The gateway process's current seq numbering, once `gateway.ready` announced one. */
    fun replayEpoch(): String? = synchronized(lock) { epoch }

    /** Highest `seq` seen per runtime session: what a replay would resume from. */
    fun watermarks(): Map<String, Long> = synchronized(lock) { lastSeenSeqByRuntime.toMap() }

    /** Record one session event's `seq`, never lowering a runtime's watermark. */
    fun noteSessionSeq(runtimeSessionId: String?, seq: Long?) {
        if (runtimeSessionId.isNullOrBlank() || seq == null) return
        synchronized(lock) {
            val previous = lastSeenSeqByRuntime[runtimeSessionId]
            if (previous == null || seq > previous) lastSeenSeqByRuntime[runtimeSessionId] = seq
        }
    }

    /**
     * Watermarks and the epoch describe one gateway *process*, so neither
     * survives a change of connection. Fail closed: the next `gateway.ready`
     * re-announces the epoch and a reconnect refetches rather than resuming
     * from a number that may name a different run.
     */
    fun clearConnectionState() {
        synchronized(lock) {
            epoch = null
            lastSeenSeqByRuntime.clear()
        }
    }

    /**
     * Handle one session-less frame. Returns true when the session list's rows
     * moved, which is the one global frame whose refetch this repository owns.
     *
     * The `when` is exhaustive over [GatewayGlobalEventType] with no `else`:
     * adding a type to the table is a compile error until a branch here names
     * how the lane settles it, so a subscribed type can never fall through
     * unhandled. Only the entries the lane owns can arrive — the repository
     * settles [GatewayGlobalEventOwner.Reclaim] before dispatching — and a
     * type from a newer backend never reaches this method at all, because the
     * allow-list refused it upstream
     * (`tui_gateway/change_watcher.py:177-184` @
     * `437116f9497c80d242ce034ff7f5d81dc277a337` — the pin's set is wider than
     * this client's). Null cannot happen either, but naming it keeps the
     * refusal explicit rather than silent.
     */
    fun accept(event: GatewayEvent): Boolean = when (GatewayGlobalEventType.fromWire(event.type)) {
        GatewayGlobalEventType.GatewayReady -> {
            adoptEpoch(event.payload as? JsonObject)
            false
        }

        GatewayGlobalEventType.CronChanged -> {
            publish(GatewayChangeHintKind.Cron, event.payload)
            false
        }

        GatewayGlobalEventType.PetChanged -> {
            publish(GatewayChangeHintKind.Pet, event.payload)
            false
        }

        GatewayGlobalEventType.BotRelayOutbox -> {
            publish(GatewayChangeHintKind.BotRelayOutbox, event.payload)
            false
        }

        // The backend's session rows moved; the repository rescans its own list.
        GatewayGlobalEventType.SessionsChanged -> {
            publish(GatewayChangeHintKind.Sessions, event.payload)
            true
        }

        // The repository's own reclaim path, never this lane's: it settles and
        // unbinds the runtime, which is what the identity map is for. If one
        // ever arrives here the routing above is wrong, and claiming to have
        // handled it would be worse than dropping it.
        GatewayGlobalEventType.SessionReclaimed,
        null,
        -> false
    }

    private fun publish(kind: GatewayChangeHintKind, payload: JsonElement) {
        hintFlow.tryEmit(GatewayChangeHint(kind, payload))
    }

    private fun adoptEpoch(payload: JsonObject?) {
        val advertised = (payload?.get("replay_epoch") as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
            ?.takeIf(String::isNotBlank)
        synchronized(lock) {
            if (advertised == epoch) return
            epoch = advertised
            lastSeenSeqByRuntime.clear()
        }
    }

    private companion object {
        const val HINT_BUFFER = 16
    }
}
