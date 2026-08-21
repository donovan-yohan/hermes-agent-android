package com.hermesagent.mobile.data.gateway

import android.util.Log
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
)

internal class GatewayRpcException(
    message: String,
    /** True when the frame was sent and a lost response cannot prove rejection. */
    val requestMayHaveBeenAccepted: Boolean = false,
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

internal interface GatewayRpcClient : Closeable {
    val events: Flow<GatewayEvent>
    val closed: Flow<Unit> get() = emptyFlow()
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
) : GatewayRpcClient {
    constructor(wire: GatewayRpcWire, timeoutMillis: Long) : this(wire, { timeoutMillis })

    private val nextId = AtomicLong(0)
    private val lock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    // WebSocket callbacks cannot suspend. A bounded channel preserves ordered
    // deltas through normal bursts; overflow fails the connection rather than
    // silently dropping transcript bytes or allowing unbounded remote input.
    private val eventChannel = Channel<GatewayEvent>(EVENT_BUFFER_CAPACITY)
    private val closedFlow = MutableSharedFlow<Unit>(replay = 1)
    private var isClosed = false

    override val events: Flow<GatewayEvent> = eventChannel.receiveAsFlow()
    override val closed: Flow<Unit> = closedFlow

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
            ),
        )
        if (accepted.isFailure) connectionClosed("The gateway event stream exceeded its safe buffer.")
    }

    fun connectionClosed(message: String = "The gateway connection closed.") {
        val abandoned = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            pending.values.toList().also { pending.clear() }
        }
        val failure = GatewayRpcException(message, requestMayHaveBeenAccepted = true)
        abandoned.forEach { it.completeExceptionally(failure) }
        eventChannel.close()
        closedFlow.tryEmit(Unit)
    }

    override fun close() {
        connectionClosed()
        wire.close()
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 1_024
        val JSON = Json { ignoreUnknownKeys = true }
        val SUPPORTED_EVENTS = setOf(
            "gateway.ready",
            "session.reclaimed",
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
        )
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

        /** Opens a shared remote Gateway with a fresh, single-use WS ticket. */
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
            val rpc = CorrelatedGatewayRpc(wire) { method ->
                gatewayRpcTimeoutMillis(method, defaultTimeoutMillis = requestTimeoutMillis)
            }

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
                        rpc.connectionClosed("The gateway WebSocket failed.")
                        if (continuation.isActive) {
                            continuation.resumeWithException(GatewayRpcException("The gateway WebSocket was refused."))
                        }
                    }
                })
                continuation.invokeOnCancellation {
                    connected?.close() ?: socket.cancel()
                }
            }
        }

        private const val LOG_TAG = "HermesGateway"
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
 * Source: NousResearch/hermes-agent @ f82f2dbabd9e66b714f2b4f8a40447fe0c13e732,
 * apps/desktop/src/hermes.ts:85-104 and tui_gateway/methods_prompt.py:714-819.
 */
internal fun gatewayRpcTimeoutMillis(method: String, defaultTimeoutMillis: Long = 15_000L): Long =
    when (method) {
        "prompt.submit" -> 1_800_000L
        else -> defaultTimeoutMillis
    }
