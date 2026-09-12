package com.hermesagent.mobile.plugins

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
     */
    data class Refused(val code: Int, val safeMessage: String) : PluginHostResult
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
) : PluginHost {
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

        val call = scope.async { exchange(rpc, normalized, params) }
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
            PluginHostResult.Refused(error.code ?: 0, REFUSED_MESSAGE)
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
