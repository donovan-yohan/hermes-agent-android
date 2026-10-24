package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.GatewayRpcWire
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Deliberately raw, READ-ONLY JSON-RPC: the foreground canonical adapter adds
 * scope=stored to session.list, incompatible with title-as-identity discovery.
 * No live subscription, session attachment or server-request capability. */
internal class NotificationReadRpc(private val wire: GatewayRpcWire, private val current: () -> Boolean) {
    private val ids = AtomicLong()
    private val stopped = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    suspend fun request(method: String, params: JsonObject): JsonElement {
        require(method in READS)
        check(!stopped.get() && current())
        val id = ids.incrementAndGet().toString()
        val result = CompletableDeferred<JsonElement>()
        pending[id] = result
        try {
            val text = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()
            check(current() && !stopped.get() && wire.send(text))
            val answer = withTimeout(8_000) { result.await() }
            check(current())
            return answer
        } finally { pending.remove(id) }
    }
    fun receive(text: String) {
        // Bound UTF-16 before the UTF-8 allocation and before JSON parsing.
        if (text.length > MAX_BYTES || text.toByteArray().size > MAX_BYTES || !current()) { close(); return }
        val frame = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val id = (frame["id"] as? JsonPrimitive)?.content ?: return
        val waiter = pending[id] ?: return
        if (frame["error"] != null) waiter.completeExceptionally(IllegalStateException("Notification read refused."))
        else frame["result"]?.let { waiter.complete(it) }
            ?: waiter.completeExceptionally(IllegalStateException("Invalid notification response."))
    }
    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        pending.values.forEach { it.completeExceptionally(IllegalStateException("Notification reader closed.")) }
        pending.clear()
        wire.close()
    }
    companion object {
        private const val MAX_BYTES = 524288
        private val READS = setOf("runtime.describe", "profiles.list", "session.list", "groups.capabilities", "groups.list", "groups.log")
        suspend fun connect(client: OkHttpClient, url: String, current: () -> Boolean): NotificationReadRpc = withTimeout(8_000) {
            suspendCancellableCoroutine { continuation ->
                var socket: WebSocket? = null
                val reader = NotificationReadRpc(object : GatewayRpcWire {
                    override fun send(text: String) = current() && socket?.send(text) == true
                    override fun close() { socket?.cancel() }
                }, current)
                continuation.invokeOnCancellation { reader.close() }
                socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        if (continuation.isActive && current()) continuation.resume(reader) { _, value, _ -> value.close() }
                        else { reader.close(); if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Notification endpoint changed.")) }
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = reader.receive(text)
                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) { reader.close() }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { reader.close() }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        reader.close()
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Notification reader unavailable."))
                    }
                })
                if (!continuation.isActive) reader.close()
            }
        }
    }
}
