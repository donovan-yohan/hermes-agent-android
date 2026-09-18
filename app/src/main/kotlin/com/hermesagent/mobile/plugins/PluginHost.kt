package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcError
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.data.gateway.RECONNECT_MESSAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What a door that tracks no endpoint reports: the generation never moves.
 *
 * The interface's default, and `GatewayPluginHost`'s, so a door built without a
 * switch behind it — [UnavailablePluginHost], a test's fake, a test that is not
 * about a switch — never claims a move that did not happen. Production states
 * the app's own generation instead: a door wired without an endpoint holds a
 * plugin's stale rows through every switch, which is the defect this member
 * exists to prevent.
 *
 * File scope, beside the interface rather than inside its companion: both the
 * interface default and `GatewayPluginHost`'s constructor default read it, and
 * a companion `private` is not visible to a sibling top-level class.
 */
private val ENDPOINT_NEVER_MOVES: StateFlow<Long> = MutableStateFlow(0L)
private val NO_READY_LEG: StateFlow<PluginConnectionToken?> = MutableStateFlow(null)

/** An opaque identity, with no transport, credentials, or operations attached. */
class PluginConnectionToken internal constructor()

/**
 * The plugin-facing gateway door: JSON-RPC to the live connection, plus a tap
 * on the gateway's event stream.
 *
 * Desktop surfaces the same two capabilities as a module-global `host`
 * (`apps/desktop/src/sdk/index.ts:582,1267,1407-1414` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`). Android has no module-global
 * host: this app's plugin contract is a scoped [PluginContext], so the door
 * hangs off `ctx.host` instead and resolves the *live* connection per call,
 * exactly as Desktop's lazy `host.request` does.
 */
interface PluginHost {
    /**
     * One gateway JSON-RPC call (`session.list`, `bot_relay.deliver`, …).
     *
     * [method] is validated before anything is sent; see
     * [normalizePluginHostMethod]. The result is a [PluginHostResult] rather
     * than a thrown exception so a plugin branches on the value, the same
     * shape as [PluginRest] — including a call the Gateway never answered:
     * an exchange that times out is [PluginHostResult.Refused], never a
     * cancellation of the plugin's own coroutine.
     */
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): PluginHostResult

    /**
     * One request bound to the endpoint generation the caller observed.
     *
     * The ordinary [request] deliberately follows the live client slot. A
     * multi-call mutation must not: after it has read one Gateway, a switch
     * must not let its next call mutate the replacement Gateway. The default
     * checks the generation before the call and again around its answer, which
     * is what a simple test or unavailable host can honestly offer; production
     * ([GatewayPluginHost]) additionally re-validates the live client at the
     * dispatch itself, because the exchange can be queued behind a dispatcher
     * long enough for a whole switch to land between the first check and the
     * send.
     */
    suspend fun requestAtEndpoint(
        expectedGeneration: Long,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): PluginHostResult {
        if (endpointGeneration.value != expectedGeneration) {
            return PluginHostResult.Refused(0, RECONNECT_MESSAGE)
        }
        val result = request(method, params)
        return if (endpointGeneration.value == expectedGeneration) {
            result
        } else {
            PluginHostResult.Refused(0, RECONNECT_MESSAGE)
        }
    }

    /** Ready-leg identity; a transport reconnect changes it without changing endpointGeneration. */
    val connectionToken: StateFlow<PluginConnectionToken?> get() = NO_READY_LEG

    /** Bind a read sequence to both an endpoint and a ready leg. Unsupported doors fail closed. */
    suspend fun requestAtConnection(
        expectedEndpoint: Long,
        token: PluginConnectionToken,
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
    ): PluginHostResult = PluginHostResult.Refused(0, RECONNECT_MESSAGE)

    /**
     * Whether a live connection exists behind this door, right now.
     *
     * The door resolves the *live* connection per call; this is the same slot
     * as a value, so a plugin that has to recover from a cold start observes
     * the connection instead of polling it. A plugin loads before the app has
     * dialled anything (`HermesApplication` discovers plugins before it starts
     * following the active connection), and [request] refuses while no client
     * exists — so without this edge a plugin's one read at registration lands
     * in a refusal it can never leave.
     *
     * It is a [StateFlow], not a one-shot event: a collector that starts late
     * still receives the current value, so a plugin activated on an
     * already-connected app reads immediately rather than never. The app
     * publishes a client only once the leg it will actually use has answered
     * and clears it on every close, so `true` here means "a request will be
     * sent", not "a socket object exists".
     */
    val connected: StateFlow<Boolean>
        get() = NO_CONNECTION

    /**
     * Which endpoint the connection behind this door belongs to — bumped
     * whenever the app leaves an endpoint and forgets what it told us, and by
     * nothing else.
     *
     * [connected] answers "would a request be sent"; it cannot answer *to which
     * machine*. The two come apart at exactly the moment a plugin that holds
     * its own copy of backend truth has to know: a reconnect to the same
     * Gateway and a switch to a different one both drop the leg and bring one
     * back. The next backend is a different machine that can recycle the same
     * durable ids, so a plugin merging across the second is merging two
     * machines' data — and the app's one wholesale clear is the signal it is
     * not, which this is.
     *
     * The app publishes it straight from `SessionCache.endpointGeneration`,
     * whose only writer is `resetForEndpointSwitch` — the clear
     * `ConnectionSwitchController` runs through `leaveLocked` on every path
     * that leaves an endpoint: a switch to another row, a re-address of this
     * one (the Gateways route form persists per keystroke and tears down
     * through `leaveCurrentEndpoint` after each, so editing an address is one),
     * a disconnect, and an endpoint's removal. What never moves it is a
     * *transport* redial: a dropped socket, a wake from sleep, a failed turn.
     *
     * A door member rather than a plugin-side registration, deliberately: a
     * bundled plugin has no other per-app injection point
     * (`BundledPlugins.ALL` builds `BotsPlugin()` with nothing but the
     * context), a generation is the same idea the app's own endpoint-scoped
     * reader uses (`ChatViewModel`'s Archived pool), and it costs one member
     * here rather than a second lifecycle on `PluginContext` plus a fan-out in
     * `PluginLoader`.
     *
     * A `StateFlow` for the same reason [connected] is one: a plugin activated
     * late reads the current endpoint rather than a stale one.
     */
    val endpointGeneration: StateFlow<Long>
        get() = ENDPOINT_NEVER_MOVES

    /**
     * Subscribe to gateway events by `type`, or `'*'` for everything. Returns
     * a disposer. Listeners are isolated: one that throws never breaks the
     * event pump, and never reaches another subscriber.
     *
     * This is a subscription, not a second reader of the app's event queue: a
     * plugin tap and the app's own pump each receive every event, and
     * disposing a tap leaves the others untouched. Desktop does the same by
     * fanning every inbound event through `emitGatewayEvent`
     * (`apps/desktop/src/contrib/events.ts:31`) before its own dispatch.
     *
     * Direct port of Desktop's `onGatewayEvent`
     * (`apps/desktop/src/contrib/events.ts:16-28` @ the pin).
     */
    fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit

    companion object {
        /** Every event type. */
        const val ALL_EVENTS = "*"

        /**
         * What a door with no live route reports: never connected.
         *
         * The default for an implementation that does not track a connection —
         * so a plugin waiting on an edge is simply never told one, rather than
         * being handed a stream that lies about a live Gateway.
         */
        private val NO_CONNECTION: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

/**
 * One gateway event as a plugin sees it.
 *
 * [sessionId] is null for the session-less global events (`cron.changed`,
 * `pet.changed`, `bot_relay.outbox.pending`) — they belong to no chat.
 */
data class PluginHostEvent(
    val type: String,
    val sessionId: String?,
    val payload: JsonElement,
)

/**
 * Result of a plugin host call. Deliberately the same three-way split as
 * [PluginRestResult], so a plugin answers "not available here" and "refused"
 * the same way whichever door it used.
 */
sealed interface PluginHostResult {
    /** The gateway answered. [result] is its JSON-RPC `result` member. */
    data class Success(val result: JsonElement) : PluginHostResult

    /**
     * The Gateway does not serve this method.
     *
     * The wire signal is JSON-RPC `-32601` (`tui_gateway/server.py:734` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`, `unknown method: {method}`).
     * This is the host door's twin of [PluginRestResult.UnavailableOnGateway]:
     * a capability this build does not have, which a surface should remember
     * rather than retry.
     */
    data object UnavailableOnGateway : PluginHostResult

    /**
     * The call reached the Gateway and it answered with an error, or it could
     * not be completed at all — no live connection, a transport failure, or an
     * exchange the Gateway never answered before its deadline. [safeMessage]
     * is this app's own sentence — never text the backend wrote.
     *
     * [reason] is the one machine datum this door forwards: a
     * [PluginRefusalReason], never a string the backend chose. `null` is "no
     * reason this build knows", an unrecognized string included, and the wire
     * value never reaches a plugin — not even through `toString`.
     */
    data class Refused(
        val code: Int,
        val safeMessage: String,
        val reason: PluginRefusalReason? = null,
    ) : PluginHostResult
}

/**
 * The two reasons the hosted-room handlers answer as `error.data.reason`
 * (`tui_gateway/methods_groups.py:206-210` and `gateway/hosted_rooms.py:185-198`
 * @ `d177b119e9c56c9ddc0b7379ffce52341ec06584`). A closed set, and not every
 * backend reason: `data.reason` also carries `prompt.submit`'s
 * `SESSION_NOT_OWNED` (`data/gateway/GatewayRpc.kt`), which stays unnamed here.
 * Anything else maps to `null` — [fromWire] is the only construction from wire
 * data, and it matches exactly, so a reworded value is unknown, not misread.
 */
enum class PluginRefusalReason(val wireValue: String) {
    /** The room was pruned; the id is retired forever. */
    RoomHistoryExpired("room_history_expired"),

    /** A stale authority tried to mutate the room's hosted state. */
    AuthorityConflict("authority_conflict"),
    ;

    companion object {
        private val byWireValue: Map<String, PluginRefusalReason> =
            entries.associateBy { it.wireValue }

        /** The reason [raw] names exactly, or null when it names none this build knows. */
        fun fromWire(raw: String?): PluginRefusalReason? = raw?.let(byWireValue::get)
    }
}

/**
 * Validate an RPC [method] before it is put on the wire.
 *
 * The host door has no `path`, so the traversal guard [PluginRest] applies to
 * a route has to be spelled differently: a method is a single JSON-RPC name
 * (`<namespace>.<verb>`), so anything blank, spaced, slashed or containing a
 * `..` segment is refused here rather than forwarded. Refusing locally is the
 * point — a malformed method must cost no request.
 */
fun normalizePluginHostMethod(caller: String, method: String): String {
    val trimmed = method.trim()
    if (trimmed.isEmpty() ||
        trimmed.contains("..") ||
        trimmed.contains('/') ||
        trimmed.any { it.isWhitespace() }
    ) {
        throw IllegalArgumentException("$caller: illegal RPC method \"$method\"")
    }
    return trimmed
}

/**
 * The host door of a context with no live Gateway route: every call refuses
 * with the transport's own reconnect sentence, [connected] never turns true,
 * and no event is ever delivered. This is what a plugin gets before the app
 * has connected, and what tests get when they are not exercising the door.
 */
object UnavailablePluginHost : PluginHost {
    override suspend fun request(method: String, params: JsonObject): PluginHostResult {
        normalizePluginHostMethod("PluginHost", method)
        return PluginHostResult.Refused(0, RECONNECT_MESSAGE)
    }

    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
}

/**
 * Production [PluginHost] over the connection-owned [GatewayRpcClient].
 *
 * [clients] is the live connection's client slot, re-read per call, so the
 * door follows a reconnect the way Desktop's lazy `host.request` follows a
 * profile swap. [scope] is the plugin's own scope: the loader cancels it when
 * the plugin is disabled, which is what cuts an in-flight request off rather
 * than letting it outlive `onDispose`.
 */
internal class GatewayPluginHost(
    private val scope: CoroutineScope,
    private val clients: StateFlow<GatewayRpcClient?>,
    /**
     * The endpoint the live client belongs to — the app's endpoint generation,
     * published on the door's own interface.
     *
     * Defaulted to [ENDPOINT_NEVER_MOVES] for the same reason the app's own
     * `EndpointScopedState` seam defaults to a no-op: a test that is not about a
     * switch should not have to say so. `HermesApplication` states the app's own
     * generation at its one wiring site.
     */
    override val endpointGeneration: StateFlow<Long> = ENDPOINT_NEVER_MOVES,
    /** Shared with endpoint teardown; see [EndpointDispatchFence]. */
    private val endpointDispatchFence: EndpointDispatchFence = EndpointDispatchFence(),
) : PluginHost {
    private val tokenLock = Any()
    private var tokenClient: GatewayRpcClient? = null
    private var readyToken: PluginConnectionToken? = null

    private fun snapshotToken(): PluginConnectionToken? = synchronized(tokenLock) {
        val live = clients.value
        if (live !== tokenClient) {
            tokenClient = live
            readyToken = live?.let { PluginConnectionToken() }
        }
        readyToken
    }

    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    override val connectionToken: StateFlow<PluginConnectionToken?> by lazy {
        val published = clients.map { snapshotToken() }
            .stateIn(scope, SharingStarted.Eagerly, snapshotToken())
        // Read-through value prevents a queued collector from advertising an old leg at dispatch.
        object : StateFlow<PluginConnectionToken?> by published {
            override val value: PluginConnectionToken? get() = snapshotToken()
            override val replayCache: List<PluginConnectionToken?> get() = listOf(value)
        }
    }

    override suspend fun requestAtConnection(
        expectedEndpoint: Long,
        token: PluginConnectionToken,
        method: String,
        params: JsonObject,
    ): PluginHostResult {
        val normalized = normalizePluginHostMethod(CALLER, method)
        val rpc = endpointBoundClient(expectedEndpoint) as? EndpointDispatchingGatewayRpcClient
            ?: return refusedWithoutRoute()
        val stillOwns = {
            endpointBoundClient(expectedEndpoint) === rpc && snapshotToken() === token && clients.value === rpc
        }
        val lease = endpointDispatchFence.leaseAt(expectedEndpoint, stillOwns) ?: return refusedWithoutRoute()
        return requestAtEndpoint(rpc, normalized, params, lease, stillOwns)
    }

    /**
     * The client slot as a readiness edge. `GatewayConnection` publishes the
     * client only after an authenticated round trip on the leg the app will
     * use, and clears it in the same place it closes that leg, so this tracks
     * one connection's whole life including every reconnect.
     *
     * Lazily, so a plugin that never asks about the connection does not pay for
     * a collector: the door is built per activation for every plugin, and most
     * never read this. The first reader still gets the slot's *current* value
     * as the initial value, so a late read is a correct read.
     */
    override val connected: StateFlow<Boolean> by lazy {
        clients.map { it != null }.stateIn(scope, SharingStarted.Eagerly, clients.value != null)
    }

    override suspend fun request(method: String, params: JsonObject): PluginHostResult {
        val normalized = normalizePluginHostMethod(CALLER, method)
        val rpc = clients.value ?: return PluginHostResult.Refused(0, RECONNECT_MESSAGE)

        return request(rpc, normalized, params)
    }

    override suspend fun requestAtEndpoint(
        expectedGeneration: Long,
        method: String,
        params: JsonObject,
    ): PluginHostResult {
        val normalized = normalizePluginHostMethod(CALLER, method)
        val rpc = endpointBoundClient(expectedGeneration) ?: return refusedWithoutRoute()
        // The snapshot is only an early refusal. The irreversible hand-off is
        // delegated to a client that explicitly promises to invoke the shared
        // fence around its immediate wire send. Endpoint teardown invalidates
        // that fence before disconnecting, so a switch after this snapshot but
        // before the wire hand-off cannot mutate either endpoint.
        val dispatchingRpc = rpc as? EndpointDispatchingGatewayRpcClient ?: return refusedWithoutRoute()
        val stillOwns = { endpointBoundClient(expectedGeneration) === rpc }
        val lease = endpointDispatchFence.leaseAt(expectedGeneration, stillOwns) ?: return refusedWithoutRoute()
        return requestAtEndpoint(
            rpc = dispatchingRpc,
            normalized = normalized,
            params = params,
            lease = lease,
            stillOwns = stillOwns,
        )
    }

    /**
     * The live client, or null when this door is no longer on
     * [expectedGeneration].
     *
     * Both sides are read twice because the teardown moves them in order —
     * `ConnectionSwitchController.leaveLocked` disconnects (clearing this slot)
     * and only then bumps the generation, and the replacement client is
     * published after that. A single read of either member is therefore not
     * evidence that the pair the caller bound to is still live: a slot read
     * alone can observe the replacement of a generation that moved, and a
     * generation read alone can observe a bump an in-flight clear has not
     * followed yet.
     */
    private fun endpointBoundClient(expectedGeneration: Long): GatewayRpcClient? {
        if (endpointGeneration.value != expectedGeneration) return null
        val rpc = clients.value ?: return null
        return rpc.takeIf { endpointGeneration.value == expectedGeneration && clients.value === rpc }
    }

    private fun refusedWithoutRoute(): PluginHostResult = PluginHostResult.Refused(0, RECONNECT_MESSAGE)

    private suspend fun request(
        rpc: GatewayRpcClient,
        normalized: String,
        params: JsonObject,
    ): PluginHostResult {
        val call = scope.async { exchange(rpc, normalized, params) }
        return awaitRequest(call)
    }

    private suspend fun requestAtEndpoint(
        rpc: EndpointDispatchingGatewayRpcClient,
        normalized: String,
        params: JsonObject,
        lease: Long,
        stillOwns: () -> Boolean,
    ): PluginHostResult {
        val call = scope.async {
            // This is a useful early refusal, but not the dispatch decision:
            // `requestAtEndpointDispatch` calls the shared fence around the
            // immediate wire send below.
            if (!stillOwns()) return@async refusedWithoutRoute()
            val outcome = exchangeAtEndpoint(rpc, normalized, params, lease, stillOwns)
            // A frame may have left before a later switch; its answer never
            // becomes current endpoint truth.
            if (!stillOwns()) return@async refusedWithoutRoute()
            outcome
        }
        return awaitRequest(call)
    }

    private suspend fun awaitRequest(
        call: kotlinx.coroutines.Deferred<PluginHostResult>,
    ): PluginHostResult {
        return try {
            call.await()
        } catch (timedOut: TimeoutCancellationException) {
            // The Gateway never answered inside its own deadline
            // (`GatewayRpc.kt` `withTimeout`). That is an answer a plugin
            // branches on, not a cancellation of the plugin's coroutine: only
            // the exchange ends here, and the plugin's scope stays alive.
            call.cancel()
            PluginHostResult.Refused(0, TIMED_OUT_MESSAGE)
        } catch (cancelled: CancellationException) {
            // The plugin was disposed (the scope is gone, so this is a
            // no-op) or the caller gave up; in both cases the request must not
            // keep running on its own or answer after the plugin is gone.
            call.cancel()
            throw cancelled
        }
    }

    private suspend fun exchangeAtEndpoint(
        rpc: EndpointDispatchingGatewayRpcClient,
        method: String,
        params: JsonObject,
        lease: Long,
        stillOwns: () -> Boolean,
    ): PluginHostResult = try {
        PluginHostResult.Success(
            rpc.requestAtEndpointDispatch(method, params) { send ->
                endpointDispatchFence.dispatchIfCurrent(lease, stillOwns, send)
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: GatewayRpcError) {
        if (error.code == METHOD_NOT_FOUND) {
            PluginHostResult.UnavailableOnGateway
        } else {
            PluginHostResult.Refused(error.code ?: 0, REFUSED_MESSAGE, PluginRefusalReason.fromWire(error.reason))
        }
    } catch (_: GatewayRpcException) {
        PluginHostResult.Refused(0, RECONNECT_MESSAGE)
    }

    private suspend fun exchange(
        rpc: GatewayRpcClient,
        method: String,
        params: JsonObject,
    ): PluginHostResult = try {
        PluginHostResult.Success(rpc.request(method, params))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: GatewayRpcError) {
        if (error.code == METHOD_NOT_FOUND) {
            PluginHostResult.UnavailableOnGateway
        } else {
            PluginHostResult.Refused(error.code ?: 0, REFUSED_MESSAGE, PluginRefusalReason.fromWire(error.reason))
        }
    } catch (_: GatewayRpcException) {
        PluginHostResult.Refused(0, RECONNECT_MESSAGE)
    }

    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit {
        val job = scope.launch {
            // Re-resolve on every client, so a reconnect re-subscribes instead
            // of leaving the plugin with a dead tap. The client's event stream
            // is a broadcast (one pump, many subscribers), so this tap and the
            // app's own pump each receive every event.
            clients.filterNotNull().collectLatest { rpc ->
                rpc.events.collect { event ->
                    if (type != PluginHost.ALL_EVENTS && event.type != type) return@collect
                    try {
                        listener(PluginHostEvent(event.type, event.runtimeSessionId, event.payload))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // A throwing listener is that plugin's problem, never
                        // the pump's and never another subscriber's.
                    }
                }
            }
        }
        return { job.cancel() }
    }

    private companion object {
        const val CALLER = "PluginHost"

        /** JSON-RPC 2.0 `Method not found`. */
        const val METHOD_NOT_FOUND = -32601

        /** This app's sentence for a refused RPC; the backend's own is never shown. */
        const val REFUSED_MESSAGE = "Hermes refused that Gateway request."

        /** This app's sentence for an exchange the Gateway never answered. */
        const val TIMED_OUT_MESSAGE = "The Gateway did not answer in time."
    }
}
