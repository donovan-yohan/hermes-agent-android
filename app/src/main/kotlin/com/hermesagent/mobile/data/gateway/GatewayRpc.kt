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
) : Exception(message)

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
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement
}

/**
 * JSON-RPC 2.0 request correlation and event parsing, independent of WebSocket
 * framing. Unknown notifications and malformed unsolicited frames are ignored;
 * a malformed response to one of our ids fails that request explicitly.
 */
internal class CorrelatedGatewayRpc(
    private val wire: GatewayRpcWire,
    private val timeoutMillisForMethod: (String) -> Long = ::gatewayRpcTimeoutMillis,
    eventPumpDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GatewayRpcClient {
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

    override suspend fun request(method: String, params: JsonObject): JsonElement {
        require(method.isNotBlank())
        val id = "m${nextId.incrementAndGet()}"
        val answer = CompletableDeferred<JsonElement>()
        synchronized(lock) {
            if (isClosed) throw GatewayRpcException("The gateway connection is closed.")
            pending[id] = answer
        }

        val frame = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
        }.toString()
        val sent = runCatching { wire.send(frame) }.getOrElse {
            synchronized(lock) { pending.remove(id) }
            throw GatewayRpcException("The gateway connection could not send the request.")
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
            val request = synchronized(lock) { pending.remove(id) } ?: return
            when {
                "result" in frame -> request.complete(frame.getValue("result"))
                frame["error"] is JsonObject -> {
                    val error = frame.getValue("error").jsonObject
                    request.completeExceptionally(
                        GatewayRpcError(
                            code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull(),
                            message = error.string("message") ?: "The gateway rejected the request.",
                        ),
                    )
                }

                else -> request.completeExceptionally(GatewayRpcException("The gateway returned a malformed response."))
            }
            return
        }

        if (frame.string("method") != "event") return
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
        eventPump.launch {
            while (true) {
                eventListeners.subscriptionCount.first { it > 0 }
                val event = eventChannel.receiveCatching().getOrNull() ?: return@launch
                if (!eventListeners.tryEmit(event)) {
                    connectionClosed(EVENT_OVERFLOW_MESSAGE)
                    return@launch
                }
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
        closedFlow.tryEmit(cause)
    }

    override fun close() {
        connectionClosed()
        wire.close()
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 1_024
        const val EVENT_OVERFLOW_MESSAGE = "The gateway event stream exceeded its safe buffer."
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
            "tool.progress",
            "tool.complete",
            "status.update",
            "error",
            "clarify.request",
            "approval.request",
            "sudo.request",
            "secret.request",
        ) + GATEWAY_GLOBAL_EVENT_TYPES
    }
}

/** Authenticated OkHttp WebSocket, with JSON-RPC delegated to the pure core. */
internal class OkHttpGatewayRpcClient private constructor(
    private val socketWire: SocketWire,
    private val rpc: CorrelatedGatewayRpc,
) : GatewayRpcClient by rpc {

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
        "prompt.submit" -> 1_800_000L
        // profiles.list rides the Gateway's slow-method lane
        // (tui_gateway/server.py:297-305): it walks each profile's skill tree
        // and opens each profile's state.db, which is seconds-scale on a cold
        // disk. Desktop gives the same call its own 60s boot budget rather
        // than the generic one (apps/desktop/src/hermes.ts:77-88).
        "profiles.list" -> 60_000L
        else -> defaultTimeoutMillis
    }
