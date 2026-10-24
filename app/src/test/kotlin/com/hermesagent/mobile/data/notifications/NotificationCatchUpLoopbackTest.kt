package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.connections.*
import com.hermesagent.mobile.data.gateway.*
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real production capture -> HTTP + raw authenticated WS -> poller -> inbox.
 * Loopback fixture only, no Gateway process and no live graph. */
class NotificationCatchUpLoopbackTest {
    @Test fun savedLocalRouteDiscoversDesktopReplyOverRealReadOnlySockets() = runBlocking {
        val server = ReadOnlyNotificationServer()
        try {
            val row = SavedConnection("row", "Fixture", ConnectionKind.Local,
                local = LocalGatewayProfile("http://127.0.0.1:${server.port}"))
            val nativeTokens = object : GatewayTokenStore {
                override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = error("not remote")
                override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) = error("no write")
                override suspend fun clear(slot: GatewaySecretSlot) = error("no clear")
            }
            val authApi = object : GatewayNativeAuthApi {
                override suspend fun status(baseUrl: String): GatewayAuthStatus = error("not remote")
                override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens = error("no browser")
                override suspend fun refresh(baseUrl: String, refreshToken: String, provider: String): GatewayNativeTokens? = error("not remote")
                override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String? = error("not remote")
            }
            val localTokens = object : GatewaySessionTokenStore {
                override suspend fun loadSessionToken(slot: GatewaySecretSlot): SessionTokenRead = SessionTokenRead.Found("fixture".toByteArray())
                override suspend fun saveSessionToken(slot: GatewaySecretSlot, token: ByteArray) = error("no write")
                override suspend fun clearSessionToken(slot: GatewaySecretSlot) = error("no clear")
            }
            val auth = NativeGatewayAuthenticator(authApi, nativeTokens, { _, _ -> error("no browser") })
            val store = MemoryNotificationCheckpoints()
            val inbox = NotificationInbox(store)
            val delivered = mutableListOf<BotMessage>()
            withTimeout(15_000) {
                val first = SavedGatewayNotificationTransport.capture(row, auth, localTokens) { true }!!
                GatewayNotificationCatchUp(store, nowMillis = { 1000 }).run(first, inbox) { delivered += it }
                assertTrue(delivered.isEmpty())
                server.fresh.set(true)
                val second = SavedGatewayNotificationTransport.capture(row, auth, localTokens) { true }!!
                GatewayNotificationCatchUp(store, nowMillis = { 3000 }).run(second, inbox) { delivered += it }
            }
            assertEquals(listOf("3"), delivered.map { it.messageId })
            val namespaceTuple = JsonArray(listOf("local", row.id, row.localProfile.normalizedBaseUrl!!).map(::JsonPrimitive)).toString()
            val foregroundNamespace = MessageDigest.getInstance("SHA-256").digest(namespaceTuple.toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertEquals("desktop-new", GatewaySessionIdentity(foregroundNamespace).apply { bind("bot", "owner") }
                .address(delivered.single().conversation.id, "bot")!!.rawId)
            assertTrue(server.methods.containsAll(listOf("runtime.describe", "session.list", "groups.capabilities", "groups.list")))
            assertFalse(server.methods.any { it in setOf("session.resume", "session.create", "groups.send") })
            assertTrue(server.requests.filter { it.contains("/messages?") }.all { it.contains("inline_images=false") })
            assertNull(server.failure.get())
        } finally { server.close() }
    }
}

private class ReadOnlyNotificationServer : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    val port = server.localPort
    val fresh = AtomicBoolean(false)
    val methods = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<String>()
    val failure = AtomicReference<Throwable?>()
    private val executor = Executors.newCachedThreadPool()
    private val sockets = CopyOnWriteArrayList<Socket>()
    init {
        executor.execute {
            try { while (!server.isClosed) { val socket = server.accept(); sockets += socket; executor.execute { handle(socket) } } }
            catch (_: SocketException) { }
        }
    }
    private fun handle(socket: Socket) {
        try { socket.use {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val first = line(input)
            requests += first
            val headers = mutableMapOf<String, String>()
            while (true) { val header = line(input); if (header.isEmpty()) break
                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            if (headers["upgrade"]?.lowercase() == "websocket") {
                check(first.contains("token=fixture"))
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((headers.getValue("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray()); output.flush()
                while (true) {
                    val text = readFrame(input) ?: break
                    val frame = Json.parseToJsonElement(text).jsonObject
                    val method = frame.getValue("method").jsonPrimitive.content
                    methods += method
                    val result = when (method) {
                        "runtime.describe" -> """{"instance_id":"fixture-instance","profile_id":"owner","authority_epoch":"fixture-epoch","capabilities":["durable-admission-v1","event-replay-v1"],"session_create":{"sources":["gui"],"parameters":["title"]},"skin":{}}"""
                        "profiles.list" -> """{"profiles":[{"name":"bot","path":"owner","is_default":false,"model":"","provider":"","description":"","display_name":"bot","skill_count":0,"ui_meta_revisions":{},"has_avatar":false}],"bot_mode_protocol":true}"""
                        "session.list" -> """{"sessions":[]}"""
                        "groups.capabilities" -> """{"protocol_version":2,"driver":true,"persistent_process":true,"authority_gateway_id":"gw","room_link":{"enabled":true},"features":[],"methods":["groups.capabilities","groups.list","groups.state","groups.log"],"max_log_limit":100}"""
                        "groups.list" -> """{"rooms":[],"next_offset":null}"""
                        else -> error("Unexpected write: $method")
                    }
                    writeFrame(output, buildJsonObject { put("jsonrpc", "2.0"); put("id", frame.getValue("id")); put("result", Json.parseToJsonElement(result)) }.toString())
                }
            } else {
                check(headers["x-hermes-session-token"] == "fixture")
                val new = fresh.get()
                val id = if (new) "desktop-new" else "desktop-old"
                val body = if (first.contains("/messages?")) {
                    val rows = if (new) """[{"id":2,"role":"user","content":"prompt","timestamp":2},{"id":3,"role":"assistant","content":"desktop reply","timestamp":2}]"""
                        else """[{"id":1,"role":"assistant","content":"old","timestamp":0.5}]"""
                    """{"session_id":"$id","profile":"bot","messages":$rows,"pagination":{"limit":40,"offset":0,"order":"latest","returned":${if (new) 2 else 1}}}"""
                } else """{"sessions":[{"id":"$id","profile":"bot","title":"Desktop only","started_at":${if (new) 2 else 0.1}}],"total":1,"limit":40,"offset":0,"errors":[],"storage":{}}"""
                val bytes = body.toByteArray()
                output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                output.write(bytes); output.flush()
            }
        } } catch (_: EOFException) { } catch (_: SocketException) { }
        catch (t: Throwable) { failure.compareAndSet(null, t) }
    }
    private fun line(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (true) { val b = input.read(); if (b == -1) throw EOFException(); if (b == 10) break; if (b != 13) out.write(b) }
        return out.toString("UTF-8")
    }
    private fun readFrame(input: InputStream): String? {
        val first = input.read(); if (first == -1 || first and 15 == 8) return null
        val second = input.read(); var length = second and 127
        if (length == 126) length = input.read() * 256 + input.read()
        require(length < 65536)
        val mask = if (second and 128 != 0) input.readNBytes(4) else null
        val bytes = input.readNBytes(length)
        if (mask != null) bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return bytes.toString(Charsets.UTF_8)
    }
    private fun writeFrame(output: OutputStream, text: String) {
        val bytes = text.toByteArray()
        output.write(129)
        if (bytes.size < 126) output.write(bytes.size) else { output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255) }
        output.write(bytes); output.flush()
    }
    override fun close() {
        server.close(); sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow(); check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}
