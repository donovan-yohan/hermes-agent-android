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
 * The frames the gateway broadcasts as session-less globals.
 *
 * Two of them are lifecycle, not change hints: `gateway.ready` opens every
 * socket with `skin`, `change_events`, `heartbeat` and `replay_epoch`
 * (`tui_gateway/ws.py:272-277` @ the pin SHA), and `session.reclaimed` is
 * *broadcast* rather than session-targeted because the reap paths run on timer
 * threads with no live transport (`tui_gateway/session_lifecycle.py:275-286`).
 * Both bypass `write_json`, which is why neither carries a `seq`.
 *
 * The four change hints are polled and broadcast by the change watcher
 * (`tui_gateway/change_watcher.py:177-184` @ the pin SHA). The pin also
 * broadcasts `skin.changed`, `platforms.changed` and `pairing.changed`; this
 * client has no surface for them yet, and they stay out of the allow-list
 * rather than being admitted unhandled.
 */
internal val GATEWAY_GLOBAL_EVENT_TYPES: Set<String> = setOf(
    "gateway.ready",
    "session.reclaimed",
    "cron.changed",
    "pet.changed",
    "sessions.changed",
    "bot_relay.outbox.pending",
)

internal fun gatewayEventLane(type: String): GatewayEventLane =
    if (type in GATEWAY_GLOBAL_EVENT_TYPES) GatewayEventLane.Global else GatewayEventLane.Session

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
     * A type this lane does not know is dropped: the allow-list already refused
     * it upstream of here, and a future broadcast must not throw on a client
     * that cannot use it
     * (`tui_gateway/change_watcher.py:177-184` @ the pin SHA — the pin's set is
     * wider than this client's).
     */
    fun accept(event: GatewayEvent): Boolean = when (event.type) {
        "gateway.ready" -> {
            adoptEpoch(event.payload as? JsonObject)
            false
        }

        "cron.changed" -> {
            publish(GatewayChangeHintKind.Cron, event.payload)
            false
        }

        "pet.changed" -> {
            publish(GatewayChangeHintKind.Pet, event.payload)
            false
        }

        "bot_relay.outbox.pending" -> {
            publish(GatewayChangeHintKind.BotRelayOutbox, event.payload)
            false
        }

        // The backend's session rows moved; the repository rescans its own list.
        "sessions.changed" -> {
            publish(GatewayChangeHintKind.Sessions, event.payload)
            true
        }

        else -> false
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
