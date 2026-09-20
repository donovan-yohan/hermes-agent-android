package com.hermesagent.mobile.data.gateway

import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class GatewayEvent(
    val type: String,
    val runtimeSessionId: String?,
    val payload: JsonElement,
    /**
     * The gateway's per-session replay sequence
     * (`tui_gateway/event_replay.py:39-60` @ `72a3277cd7`). Absent on
     * session-less frames, which upstream never stamps.
     */
    val seq: Long? = null,
)

/** One logcat tag for the whole gateway package: connections and the sign-in that opens them. */
internal const val GATEWAY_LOG_TAG = "HermesGateway"

/**
 * Prefix every cancellation this module raises on purpose. It marks a message
 * as one of our own fixed phrases, so a breadcrumb can quote it without risking
 * a value from somewhere else reaching logcat.
 */
internal const val GATEWAY_CANCELLED_BY_PREFIX = "Cancelled by "

internal class GatewayRpcException(
    message: String,
    /** True when the frame was sent and a lost response cannot prove rejection. */
    val requestMayHaveBeenAccepted: Boolean = false,
    /**
     * The HTTP status a refused upgrade answered with, when there was one.
     *
     * A WebSocket that never opened failed as an ordinary HTTP exchange, and
     * the status is the only thing that says *why*. The transport reports it
     * rather than interpreting it: what a 403 on the upgrade means is a
     * question about the route, and the routes disagree.
     */
    val statusCode: Int? = null,
) : Exception(message)

internal class GatewayRpcError(
    val code: Int?,
    override val message: String,
    /**
     * `error.data.reason`, when the Gateway sent one.
     *
     * The refusal this exists for ships as machine data — `prompt.submit`
     * answers JSON-RPC 4090 with `data.reason = SESSION_NOT_OWNED`
     * (`apps/desktop/.../use-message-stream/submit.ts` @ `564aef2946`).
     * Desktop matched the English sentence first and then removed that, for
     * the reason this app should never add it: prose "would silently miss a
     * reworded or localized message".
     */
    val reason: String? = null,
) : Exception(message)

/**
 * One server→client JSON-RPC **request**: the backend asking this client a
 * question and blocking until the response frame carrying the same [id]
 * arrives (`tui_gateway/server_requests.py:1-13` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`).
 *
 * A request frame is neither an event frame nor a response to one of ours. It
 * carries a `method` that is never `"event"` and an id nobody in this app
 * minted (`srq-<12 hex>`, `server_requests.py:8-10`), which is what lets
 * [CorrelatedGatewayRpc.receive] tell it apart from a late answer to our own
 * call. [params] is the frame's `params` object as it arrived, `session_id`
 * included: that field is how the question routes to the session that owns it.
 */
internal data class GatewayServerRequest(
    val id: String,
    val method: String,
    val runtimeSessionId: String?,
    val params: JsonObject,
)

/**
 * The response half of the server→client request channel: one frame carrying
 * the answered request's own id, sent back over the socket the request arrived
 * on.
 *
 * Its own interface, and not a defaulted member of [GatewayRpcClient], because
 * a client that can never be asked a question has nothing to answer: a
 * `= Unit` body would let one that *is* asked drop every answer silently, and
 * the backend would block until its own timeout with nothing on screen.
 */
internal interface GatewayServerRequestResponder {
    /**
     * Hands the transport exactly one response frame for [id].
     *
     * Throws [GatewayRpcException] when the leg is closed or the frame never
     * reached it. There is no acknowledgement to wait for — a response frame is
     * one-way by construction, so "sent" is the whole of what the transport can
     * promise, and a backend that had already withdrawn the request simply
     * drops it (`server_requests.py:139-146` @ the pin).
     */
    suspend fun respondToServerRequest(id: String, result: JsonObject)
}

/** The small wire seam needed to prove correlation and close behavior offline. */
internal interface GatewayRpcWire {
    fun send(text: String): Boolean
    fun close()
}

/**
 * Why a connection ended, for the one decision that turns on it: what the
 * person is told.
 *
 * A closed socket looks the same from here whether the process holding it died
 * or the app closed the leg itself, and on the Local route those two want
 * opposite sentences — "start Hermes" is wrong, and unactionable, for a server
 * that is still running and still holding the port.
 */
internal enum class GatewayCloseCause {
    /** The transport failed. The far side is gone, or the path to it is. */
    TransportFailure,

    /**
     * Anything closed in an orderly way: this app closing the leg, the Gateway
     * closing the socket itself, or this client failing the connection over its
     * own event buffer. The server may well still be running, so nothing here
     * may be reported as one that stopped.
     */
    Orderly,
}

internal interface GatewayRpcClient : Closeable {
    /**
     * Gateway events, in arrival order, to **every** collector.
     *
     * This is a broadcast, not a queue with one reader: the app's transcript
     * pump and a plugin's tap each receive every event, and one of them
     * stopping ends only its own subscription. A burst that arrives before the
     * first collector is buffered, not dropped, and a close still drains. The
     * stream is a live subscription, so it does not complete when the client
     * closes — [closed] is what reports that — and a collector that stops
     * draining fills its buffer, which fails the connection rather than
     * dropping transcript bytes.
     */
    val events: Flow<GatewayEvent>
    val closed: Flow<GatewayCloseCause> get() = emptyFlow()

    /**
     * Backend questions parked on this connection, in arrival order, to
     * **every** collector — the same broadcast, buffering and overflow rules as
     * [events], because dropping one is a turn parked with nothing on screen
     * until the backend gives up.
     *
     * Defaulted to empty for the same reason [closed] is: a client that cannot
     * receive a request never has one to report, and a test double that had to
     * invent an empty stream would be describing a wire it does not speak.
     */
    val serverRequests: Flow<GatewayServerRequest> get() = emptyFlow()

    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement
}

/**
 * JSON-RPC 2.0 correlation in both directions, independent of WebSocket
 * framing. Unknown notifications and malformed unsolicited frames are ignored;
 * a malformed response to one of our ids fails that request explicitly, and a
 * server→client request is delivered with its id intact so the answer can carry
 * it back.
 */
internal class CorrelatedGatewayRpc(
    private val wire: GatewayRpcWire,
    private val timeoutMillisForMethod: (String) -> Long = ::gatewayRpcTimeoutMillis,
    eventPumpDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EndpointDispatchingGatewayRpcClient, GatewayServerRequestResponder {
    constructor(wire: GatewayRpcWire, timeoutMillis: Long) : this(wire, { timeoutMillis })

    private val nextId = AtomicLong(0)
    private val lock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    // WebSocket callbacks cannot suspend. A bounded channel preserves ordered
    // deltas through normal bursts; overflow fails the connection rather than
    // silently dropping transcript bytes or allowing unbounded remote input.
    private val eventChannel = Channel<GatewayEvent>(EVENT_BUFFER_CAPACITY)
    // That channel is the ingest buffer; this is the fan-out. Every consumer of
    // `events` — the app's transcript pump, a plugin's tap — is a subscriber
    // here rather than a competing receiver of one queue, so a subscriber
    // ends only its own subscription and no longer takes events from the
    // others. A subscriber that stops draining fills its buffer, and that —
    // like the ingest channel overflowing — fails the connection rather than
    // dropping transcript bytes.
    private val eventListeners = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )
    // The same ingest-then-fan-out shape one family over. A backend question
    // that arrives before the repository subscribes waits in this channel
    // rather than being dropped, because a dropped question is a turned parked
    // with nothing on screen; a subscriber that stops draining fails the
    // connection instead of losing one.
    private val serverRequestChannel = Channel<GatewayServerRequest>(SERVER_REQUEST_BUFFER_CAPACITY)
    private val serverRequestListeners = MutableSharedFlow<GatewayServerRequest>(
        extraBufferCapacity = SERVER_REQUEST_BUFFER_CAPACITY,
    )
    // One drain for every subscriber, owned by the connection rather than by
    // whichever subscriber arrived first.
    private val eventPump = CoroutineScope(SupervisorJob() + eventPumpDispatcher)
    private val eventPumpStarted = AtomicBoolean(false)
    private val closedFlow = MutableSharedFlow<GatewayCloseCause>(replay = 1)
    private var isClosed = false

    override val events: Flow<GatewayEvent> = flow {
        startEventPump()
        emitAll(eventListeners)
    }
    override val closed: Flow<GatewayCloseCause> = closedFlow

    override val serverRequests: Flow<GatewayServerRequest> = flow {
        startEventPump()
        emitAll(serverRequestListeners)
    }

    override suspend fun request(method: String, params: JsonObject): JsonElement =
        requestInternal(method, params) { send -> send() }

    /**
     * The endpoint-bound form admits the actual `wire.send` through its caller's
     * shared dispatch fence while this RPC's close/send lock is held. A close
     * that wins first refuses the frame; an endpoint invalidation that wins first
     * makes [dispatch] return false before the wire sees it.
     */
    override suspend fun requestAtEndpointDispatch(
        method: String,
        params: JsonObject,
        dispatch: (() -> Boolean) -> Boolean,
    ): JsonElement = requestInternal(method, params, dispatch)

    private suspend fun requestInternal(
        method: String,
        params: JsonObject,
        dispatch: (() -> Boolean) -> Boolean,
    ): JsonElement {
        require(method.isNotBlank())
        val id = "m${nextId.incrementAndGet()}"
        val answer = CompletableDeferred<JsonElement>()
        // One act under one lock: `close()` and [connectionClosed] set
        // `isClosed` under this same lock, so a teardown that gets here first
        // refuses this frame here rather than after it was queued, and one that
        // arrives second cannot stop a frame already handed to the transport.
        // That serialization is what lets an endpoint-bound caller treat "the
        // leg is closed" as "the frame never left", which an unsynchronized
        // send could not promise.
        val frame = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString()
        val sent = synchronized(lock) {
            if (isClosed) throw GatewayRpcException("The gateway connection is closed.")
            pending[id] = answer
            runCatching { dispatch { wire.send(frame) } }.getOrElse {
                pending.remove(id)
                throw GatewayRpcException("The gateway connection could not send the request.")
            }
        }
        if (!sent) {
            synchronized(lock) { pending.remove(id) }
            throw GatewayRpcException("The gateway connection could not send the request.")
        }

        return try {
            withTimeout(timeoutMillisForMethod(method).also { require(it > 0) }) { answer.await() }
        } finally {
            synchronized(lock) { pending.remove(id) }
        }
    }

    fun receive(text: String) {
        val frame = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = frame.string("id")
        if (id != null) {
            // Our own id first, and deliberately before the shape test: a frame
            // carrying one is an answer to a call this client made, even a
            // malformed one, and the correlation is what makes it ours.
            val request = synchronized(lock) { pending.remove(id) }
            if (request != null) {
                when {
                    "result" in frame -> request.complete(frame.getValue("result"))
                    frame["error"] is JsonObject -> {
                        val error = frame.getValue("error").jsonObject
                        request.completeExceptionally(
                            GatewayRpcError(
                                code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull(),
                                message = error.string("message") ?: "The gateway rejected the request.",
                                reason = (error["data"] as? JsonObject)?.string("reason"),
                            ),
                        )
                    }

                    else -> request.completeExceptionally(GatewayRpcException("The gateway returned a malformed response."))
                }
                return
            }
        }

        val method = frame.string("method")
        if (method != null && method != "event") {
            // The other direction: a server→client request, the backend asking
            // this client a question and blocking on the response frame
            // (`tui_gateway/server_requests.py:1-13` @ the pin). Both halves
            // are required — the response the question is waiting for carries
            // this id, and a request without one can never be answered — so a
            // frame missing either is dropped rather than half-delivered.
            if (id == null) return
            val params = frame["params"] as? JsonObject ?: return
            val accepted = serverRequestChannel.trySend(
                GatewayServerRequest(
                    id = id,
                    method = method,
                    runtimeSessionId = params.string("session_id")?.takeIf(String::isNotBlank),
                    params = params,
                ),
            )
            if (accepted.isFailure) connectionClosed(SERVER_REQUEST_OVERFLOW_MESSAGE)
            return
        }

        if (method != "event") return
        val params = frame["params"] as? JsonObject ?: return
        val type = params.string("type") ?: return
        if (type !in SUPPORTED_EVENTS) return
        val accepted = eventChannel.trySend(
            GatewayEvent(
                type,
                params.string("session_id")?.takeIf(String::isNotBlank),
                params["payload"] ?: JsonNull,
                (params["seq"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.longOrNull,
            ),
        )
        if (accepted.isFailure) connectionClosed(EVENT_OVERFLOW_MESSAGE)
    }

    /**
     * One response frame for a backend question, carrying the question's own id.
     *
     * Synchronized with the same lock that closes the leg, for the reason every
     * other frame here is: a teardown that got there first must refuse this send
     * rather than have it land on a socket the app has already left. Nothing is
     * recorded on success — a response frame is one-way, and the backend drops
     * it silently when the request was already withdrawn, which is the
     * tolerated-late-answer case rather than an error
     * (`tui_gateway/server_requests.py:139-146` @ the pin).
     */
    override suspend fun respondToServerRequest(id: String, result: JsonObject) {
        require(id.isNotBlank()) { "A server request id is required to answer." }
        val frame = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("result", result)
        }.toString()
        val sent = synchronized(lock) {
            if (isClosed) throw GatewayRpcException("The gateway connection is closed.")
            runCatching { wire.send(frame) }.getOrElse {
                throw GatewayRpcException("The gateway connection could not send the response.")
            }
        }
        if (!sent) throw GatewayRpcException("The gateway connection could not send the response.")
    }

    /**
     * Drain the ingest channel into the listener fan-out, once per client.
     *
     * Nothing leaves the buffer until something is listening, and nothing is
     * taken while every listener has gone — so a burst that arrives before the
     * app's pump subscribes, the reason the ingest channel is buffered at all,
     * is still delivered, and neither a plugin attaching or detaching nor the
     * app's pump restarting can consume another subscriber's events.
     */
    private fun startEventPump() {
        if (!eventPumpStarted.compareAndSet(false, true)) return
        eventPump.launch { drainInto(eventChannel, eventListeners, EVENT_OVERFLOW_MESSAGE) }
        eventPump.launch {
            drainInto(serverRequestChannel, serverRequestListeners, SERVER_REQUEST_OVERFLOW_MESSAGE)
        }
    }

    /**
     * Drain one ingest channel into its listener fan-out, once per client.
     *
     * Nothing leaves the buffer until something is listening, and nothing is
     * taken while every listener has gone — so a burst that arrives before the
     * app's pump subscribes, the reason the ingest channel is buffered at all,
     * is still delivered, and neither a plugin attaching or detaching nor the
     * app's pump restarting can consume another subscriber's events.
     */
    private suspend fun <T> drainInto(
        ingest: Channel<T>,
        listeners: MutableSharedFlow<T>,
        overflowMessage: String,
    ) {
        while (true) {
            listeners.subscriptionCount.first { it > 0 }
            val value = ingest.receiveCatching().getOrNull() ?: return
            if (!listeners.tryEmit(value)) {
                connectionClosed(overflowMessage)
                return
            }
        }
    }

    /**
     * [cause] defaults to [GatewayCloseCause.Orderly], so a close only claims
     * the far side is gone where a caller saw the transport fail.
     */
    fun connectionClosed(
        message: String = "The gateway connection closed.",
        cause: GatewayCloseCause = GatewayCloseCause.Orderly,
    ) {
        val abandoned = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            pending.values.toList().also { pending.clear() }
        }
        val failure = GatewayRpcException(message, requestMayHaveBeenAccepted = true)
        abandoned.forEach { it.completeExceptionally(failure) }
        eventChannel.close()
        serverRequestChannel.close()
        closedFlow.tryEmit(cause)
    }

    override fun close() {
        connectionClosed()
        wire.close()
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 1_024
        const val EVENT_OVERFLOW_MESSAGE = "The gateway event stream exceeded its safe buffer."

        /**
         * Two orders of magnitude below the event buffer on purpose. Events are
         * a stream of deltas; requests are a handful of questions a person has
         * to answer, and anything past a few dozen parked on one connection is a
         * backend that is not waiting for an answer in any useful sense.
         */
        const val SERVER_REQUEST_BUFFER_CAPACITY = 64
        const val SERVER_REQUEST_OVERFLOW_MESSAGE = "The gateway prompt stream exceeded its safe buffer."
        val JSON = Json { ignoreUnknownKeys = true }

        /**
         * The types this client admits at all: the session-scoped ones, plus
         * every session-less broadcast [gatewayEventLane] classifies as global.
         * Deriving the second half keeps the two lists from drifting — a
         * broadcast the lane handles can never be silently refused here.
         *
         * Session-less frames carry an empty `session_id` and are never
         * seq-stamped or buffered upstream, so nothing in that half is data:
         * each one is a refetch trigger
         * (`tui_gateway/change_watcher.py:177-184` @
         * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
         *
         * Blocking prompts are not here and cannot be: since the re-pin every
         * one of them is a server→client *request* frame, and the one event
         * left in the family is the backend withdrawing it —
         * `request.cancel {id, method, reason}`
         * (`tui_gateway/contracts/server_requests.py:217-224` @ the pin). The
         * deleted `clarify.request` / `approval.request` / `sudo.request` /
         * `secret.request` pair stays out deliberately: subscribing to it again
         * would give a prompt two homes, one of which a pinned Gateway never
         * posts to.
         *
         * `tool.progress` is out for the same kind of reason: no JSON-RPC
         * event table entry declares it (`tui_gateway/contracts/events.py:250,
         * 268,277,290` @ `437116f9497c80d242ce034ff7f5d81dc277a337`), the
         * channel's tool emitter writes the lifecycle pair plus
         * `tool.output_risk` (`tui_gateway/tool_progress.py:252,286,300` @ the
         * pin), and the one emitter that does exist serves the Session API's
         * own SSE stream, a different transport from this socket
         * (`POST /api/sessions/{session_id}/chat/stream`,
         * `gateway/platforms/api_server.py:1549,3153,3168` @ the pin).
         * Advertising it subscribed this client to a frame the channel cannot
         * produce (#181); the `tool.start` / `tool.complete` pair carries the
         * tool row.
         *
         * `tool.generating` is refused for a *different* reason, and the
         * difference has to be stated because this frame is not dead the way
         * `tool.progress` was. It really is declared
         * (`tui_gateway/contracts/events.py:271-277` @
         * `437116f9497c80d242ce034ff7f5d81dc277a337`) and really is emitted on
         * this socket: `agent_callbacks` wires `tool_gen_callback` straight to
         * it (`tui_gateway/agent_callbacks.py:94` @ the pin) and that table is
         * what this channel's agent is built with (`tui_gateway/server.py:2374`
         * @ the pin). The gate in front of it (`_tool_progress_enabled`) is a
         * session's `tool_progress_mode`, whose accessor defaults to `all`
         * (`tui_gateway/server.py:1906-1907,1914-1915` @ the pin).
         *
         * What it is *not* is a row. The payload is `{name}` and nothing more —
         * no `tool_id`, no args (`tui_gateway/contracts/events.py:271-274` @ the
         * pin) — because it fires while the model is still writing the call's
         * JSON. Both clients that consume it spend it on a transient *status*
         * rather than a tool row, and Desktop says why in its own comment: a row
         * materialized from it strands an argless placeholder whenever the
         * bubble is sealed before the real `tool.start` arrives
         * (`apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/tools.ts:30-44`
         * @ the pin), so Desktop keeps it as a per-session drafting label
         * (`apps/desktop/src/store/tool-drafting.ts:3-7` @ the pin) and the TUI
         * spends it on one transient trail line, `drafting <name>…`
         * (`ui-tui/src/app/createGatewayEventHandler.ts:1192-1197` @ the pin).
         *
         * This client has no surface for it. Tool frames reach exactly one
         * consumer in the app's own dispatch —
         * `GatewaySessionRepository.applyEvent`, whose only branch for them is
         * the `tool.start` / `tool.complete` pair feeding `applyTool`. The
         * plugin door (`PluginHost.onEvent`) taps this same broadcast, but no
         * bundled plugin subscribes to a tool frame, so admitting the name here
         * would subscribe to a frame nothing consumes — the same dead-socket
         * shape #181 removed, arriving from the opposite direction. Reusing
         * that branch is no better. `applyTool` keys a row by `tool_id` and
         * falls back to the sole live id, so a name-only frame either adopts
         * that row (rewriting its label, and flipping a finished one back to
         * Running, since every non-`tool.complete` type re-puts the activity)
         * or mints an argless `gateway-tool-N`: the placeholder Desktop refuses
         * to create.
         * The turn-progress line is not a home for it either: it renders
         * `SessionProgress.text`, which `applyStatusUpdate` writes from
         * backend-authored `status.update` sentences (kind-filtered by
         * `KNOWN_STATUS_UPDATE_KINDS`) and from the `thinking.delta` projection.
         * Desktop does not print the name there either — it renders a category
         * verb from `toolPresentVerb` (`apps/desktop/src/components/assistant-ui/tool/run-summary.ts:80-86`
         * @ the pin) behind a reveal delay in the status hint
         * (`apps/desktop/src/components/assistant-ui/thread/status.tsx:187-219`
         * @ the pin). So admitting the frame means building that pre-row
         * surface — its verb map and precedence — first. It stays out until
         * then, refused for its shape rather than its silence, and the
         * `tool.start` / `tool.complete` pair keeps carrying the tool row.
         */
        val SUPPORTED_EVENTS = setOf(
            "session.info",
            "message.start",
            "message.delta",
            "message.complete",
            "reasoning.delta",
            "reasoning.available",
            "thinking.delta",
            "tool.start",
            "tool.complete",
            "status.update",
            "error",
            "request.cancel",
        ) + GATEWAY_GLOBAL_EVENT_TYPES
    }
}

/** Authenticated OkHttp WebSocket, with JSON-RPC delegated to the pure core. */
internal class OkHttpGatewayRpcClient private constructor(
    private val socketWire: SocketWire,
    private val rpc: CorrelatedGatewayRpc,
) : EndpointDispatchingGatewayRpcClient by rpc, GatewayServerRequestResponder by rpc {

    companion object {
        suspend fun connect(
            http: OkHttpClient,
            localPort: Int,
            token: ByteArray,
            requestTimeoutMillis: Long = 15_000,
        ): OkHttpGatewayRpcClient {
            require(localPort in 1..65535)
            val tokenText = token.toString(Charsets.US_ASCII)
            val url = okhttp3.HttpUrl.Builder()
                .scheme("http")
                .host("127.0.0.1")
                .port(localPort)
                .addPathSegments("api/ws")
                .addQueryParameter("token", tokenText)
                .build()
            // OkHttp accepts an HTTP URL here and performs the WebSocket
            // upgrade itself. Keeping HttpUrl's supported scheme also ensures
            // the query parameter remains correctly encoded.
            val request = Request.Builder().url(url).build()
            return connectRequest(http, request, requestTimeoutMillis)
        }

        /**
         * Opens a Hermes running on this device, over loopback.
         *
         * Addressed by the whole normalized base URL rather than a port: the
         * person may have saved `localhost` or `[::1]`, and a server bound to
         * one of those is not reachable at the others.
         */
        suspend fun connectLocal(
            http: OkHttpClient,
            normalizedBaseUrl: String,
            token: ByteArray,
            requestTimeoutMillis: Long = 15_000,
        ): OkHttpGatewayRpcClient {
            // OkHttp accepts an HTTP URL here and performs the WebSocket
            // upgrade itself, which also keeps the query parameter encoded.
            val request = Request.Builder().url(localGatewayWebSocketUrl(normalizedBaseUrl, token)).build()
            return connectRequest(loopbackClient(http), request, requestTimeoutMillis)
        }

        /** Opens a Remote Gateway with a fresh, single-use WS ticket. */
        suspend fun connectRemote(
            http: OkHttpClient,
            baseUrl: String,
            ticket: String,
            requestTimeoutMillis: Long = 15_000,
        ): OkHttpGatewayRpcClient {
            val url = remoteGatewayWebSocketUrl(baseUrl, ticket)
            return connectRequest(http, Request.Builder().url(url).build(), requestTimeoutMillis)
        }

        private suspend fun connectRequest(
            http: OkHttpClient,
            request: Request,
            requestTimeoutMillis: Long,
        ): OkHttpGatewayRpcClient {
            val wire = SocketWire()
            val rpc = CorrelatedGatewayRpc(
                wire,
                timeoutMillisForMethod = { method ->
                    gatewayRpcTimeoutMillis(method, defaultTimeoutMillis = requestTimeoutMillis)
                },
            )

            return suspendCancellableCoroutine { continuation ->
                var connected: OkHttpGatewayRpcClient? = null
                val socket = http.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        wire.attach(webSocket)
                        val client = OkHttpGatewayRpcClient(wire, rpc)
                        connected = client
                        if (continuation.isActive) continuation.resume(client)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = rpc.receive(text)

                    // A close frame is orderly by definition, so a `hermes serve`
                    // that shuts down cleanly lands here and gets the neutral
                    // copy rather than "start it" — deliberate: the next Connect
                    // finds the port empty and says the right thing then.
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.w(LOG_TAG, "Gateway WebSocket closing (code=$code)")
                        rpc.connectionClosed()
                        webSocket.close(code, "")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.w(LOG_TAG, "Gateway WebSocket closed (code=$code)")
                        rpc.connectionClosed()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(LOG_TAG, "Gateway WebSocket failed (http=${response?.code ?: "none"})")
                        rpc.connectionClosed(
                            "The gateway WebSocket failed.",
                            GatewayCloseCause.TransportFailure,
                        )
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                GatewayRpcException(
                                    "The gateway WebSocket was refused.",
                                    statusCode = response?.code,
                                ),
                            )
                        }
                    }
                })
                continuation.invokeOnCancellation {
                    connected?.close() ?: socket.cancel()
                }
            }
        }

        private const val LOG_TAG = GATEWAY_LOG_TAG
    }

    internal class SocketWire : GatewayRpcWire {
        @Volatile
        private var socket: WebSocket? = null

        fun attach(value: WebSocket) {
            socket = value
        }

        override fun send(text: String): Boolean = socket?.send(text) == true

        override fun close() {
            socket?.close(1000, "")
            socket = null
        }
    }
}

internal fun remoteGatewayWebSocketUrl(baseUrl: String, ticket: String): okhttp3.HttpUrl {
    require(ticket.isNotBlank())
    return requireNotNull(normalizeRemoteGatewayUrl(baseUrl)?.toHttpUrlOrNull())
        .newBuilder()
        .addPathSegments("api/ws")
        .addQueryParameter("ticket", ticket)
        .build()
}

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless { it is JsonNull }
        ?.content

/**
 * Keep long-running acknowledgement policy at the method boundary. A prompt
 * is already an accepted in-flight turn before its RPC response returns; its
 * terminal result arrives through events.
 *
 * Source: NousResearch/hermes-agent @ 3ca096de5f8183cb2e0ec23673f294d5978656a3,
 * apps/desktop/src/hermes.ts:85-104 and tui_gateway/methods_prompt.py:731-836.
 */
internal fun gatewayRpcTimeoutMillis(method: String, defaultTimeoutMillis: Long = 15_000L): Long =
    when (method) {
        "diagnostics.share_nous" -> 120_000L
        "prompt.submit" -> 1_800_000L
        // profiles.list rides the Gateway's slow-method lane
        // (tui_gateway/server.py:297-305): it walks each profile's skill tree
        // and opens each profile's state.db, which is seconds-scale on a cold
        // disk. Desktop gives the same call its own 60s boot budget rather
        // than the generic one (apps/desktop/src/hermes.ts:77-88).
        "profiles.list" -> 60_000L
        else -> defaultTimeoutMillis
    }
