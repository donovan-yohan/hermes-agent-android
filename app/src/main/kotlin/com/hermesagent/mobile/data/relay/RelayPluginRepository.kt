package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.consumeBody
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Message wire format accepted by Relay's frozen channels.post contract. */
enum class RelayMessageFormat(val wire: String) {
    MARKDOWN("markdown"),
    TEXT("text"),
}

/** Lane state reported by the plugin's `/connection/status` envelope. */
enum class RelayLaneState {
    READY,
    OFFLINE,
    AUTH_REQUIRED,
    ERROR,
}

data class RelayChannelsStatus(
    val state: RelayLaneState,
    /** Lane-provided human explanation; shown beside the state, never alone. */
    val message: String?,
    /** Server-authored remediation hint for auth-required lanes. */
    val guidance: String?,
)

/**
 * Whether this Gateway exposes the hermes-plugin-relay backend at all. The
 * runtime gate answers 404 for a missing *or* disabled plugin — one honest
 * "not available on this Gateway" state covers both.
 */
sealed interface RelayAvailability {
    /** The plugin answered; [channels] carries the channels lane state. */
    data class Available(val channels: RelayChannelsStatus) : RelayAvailability

    /** Plugin absent or disabled on this Gateway; offer nothing Relay-shaped. */
    data object Missing : RelayAvailability

    /** Gateway authentication did not accept this client's credentials. */
    data object SignInRequired : RelayAvailability

    /** The plugin responded, but not in the pinned v1 shape. */
    data object Incompatible : RelayAvailability

    /** No usable authenticated route reached the Gateway. */
    data object GatewayUnreachable : RelayAvailability
}

data class RelayLastMessage(
    val id: String,
    val seq: Long,
    val preview: String,
    val senderKind: String,
    val status: String,
    val createdAt: String,
    val senderDisplayName: String?,
)

data class RelayChannel(
    val id: String,
    val title: String,
    val kind: String?,
    val visibility: String?,
    val archived: Boolean?,
    val latestSeq: Long?,
    val messageCount: Long?,
    val threadCount: Long?,
    val lastMessage: RelayLastMessage?,
)

data class RelayMessage(
    val id: String,
    val channelId: String,
    val seq: Long,
    val kind: String,
    val status: String,
    val senderKind: String,
    val senderId: String,
    val senderDisplayName: String?,
    val text: String,
    val format: RelayMessageFormat,
    val threadId: String?,
    val parentMessageId: String?,
    val createdAt: String,
    val updatedAt: String,
    val truncated: Boolean?,
    val clientMessageId: String?,
)

data class RelayHistory(
    val messages: List<RelayMessage>,
    val hasMore: Boolean?,
    val nextCursorBeforeSeq: Long?,
    val nextCursorAfterSeq: Long?,
)

/** Outcome of posting one channel message. */
sealed interface RelayPostResult {
    /** Relay accepted the post; [message] is the acknowledged row when returned. */
    data class Accepted(val message: RelayMessage?) : RelayPostResult

    /**
     * Not accepted. [statusCode] mirrors the failing hop (0 = could not reach
     * the Gateway; 409 = Relay conflict, never retry with the same id); null
     * means a 200 response violated the pinned contract.
     */
    data class Failed(val statusCode: Int?, val safeMessage: String) : RelayPostResult
}

/**
 * Typed client for the hermes-plugin-relay backend the Gateway mounts at
 * `/api/plugins/hermes-plugin-relay/` (pinned v1 channel endpoints; see
 * `docs/spikes/plugin-surface-relay.md`). Every Relay credential stays inside
 * the plugin process on the host — this class only renders projected rows and
 * posts bodies of exactly `{text, format, clientMessageId}`.
 *
 * Responses are trusted only field-by-field: any row that misses a required
 * projected field fails the whole parse so callers keep their previous data
 * instead of painting a half-truth.
 */
class RelayPluginRepository(private val http: () -> GatewayHttp?) {

    /** Probe the plugin without touching channel data. Cheap enough to poll. */
    suspend fun availability(): RelayAvailability = withContext(Dispatchers.IO) {
        val transport = http() ?: return@withContext RelayAvailability.GatewayUnreachable
        when (val result = transport.execute(relayRequest(CONNECTION_STATUS))) {
            is GatewayHttpResult.Rejected -> when (result.statusCode) {
                404 -> RelayAvailability.Missing
                401, 403 -> RelayAvailability.SignInRequired
                else -> RelayAvailability.GatewayUnreachable
            }

            is GatewayHttpResult.Success -> result.consumeBody(::parseAvailability)
        }
    }

    /**
     * Redeem the server-held grant (if any). Sends a deliberately empty body:
     * the endpoint rejects any content so a client cannot smuggle scope.
     */
    suspend fun reauthorize(): Boolean = withContext(Dispatchers.IO) {
        val transport = http() ?: return@withContext false
        val empty = ByteArray(0).toRequestBody(null)
        when (val result = transport.execute(relayRequest(CONNECTION_AUTHORIZE, method = "POST", body = empty))) {
            is GatewayHttpResult.Rejected -> false
            is GatewayHttpResult.Success -> result.consumeBody(::parseAuthorize)
        }
    }

    /** Projected channel inventory; null on any failure (keep prior data). */
    suspend fun channels(): List<RelayChannel>? = withContext(Dispatchers.IO) {
        val transport = http() ?: return@withContext null
        when (val result = transport.execute(relayRequest(CHANNELS))) {
            is GatewayHttpResult.Rejected -> null
            is GatewayHttpResult.Success -> result.consumeBody(::parseChannels)
        }
    }

    /** Bounded newest-first window; [limit] clamps to the server's 1–50 range. */
    suspend fun history(channelId: String, limit: Int = MAX_HISTORY_LIMIT): RelayHistory? =
        withContext(Dispatchers.IO) {
            val safeChannel = validChannelId(channelId) ?: return@withContext null
            val clamped = limit.coerceIn(1, MAX_HISTORY_LIMIT)
            val transport = http() ?: return@withContext null
            when (
                val result = transport.execute(
                    relayRequest(
                        "${CHANNELS_PREFIX}${encodeSegment(safeChannel)}/messages",
                        query = mapOf("limit" to clamped.toString()),
                    ),
                )
            ) {
                is GatewayHttpResult.Rejected -> null
                is GatewayHttpResult.Success -> result.consumeBody(::parseHistory)
            }
        }

    /**
     * Post one message. Retries must pass byte-identical [clientMessageId] —
     * Relay's exactly-once contract keys on it. Locally invalid input fails
     * with 400 before any network call, mirroring the server's own bounds.
     */
    suspend fun post(
        channelId: String,
        text: String,
        format: RelayMessageFormat,
        clientMessageId: String,
    ): RelayPostResult = withContext(Dispatchers.IO) {
        val safeChannel = validChannelId(channelId)
            ?: return@withContext RelayPostResult.Failed(400, PICK_CHANNEL_MESSAGE)
        when {
            text.isBlank() ->
                return@withContext RelayPostResult.Failed(400, EMPTY_TEXT_MESSAGE)

            text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES ->
                return@withContext RelayPostResult.Failed(400, LARGE_TEXT_MESSAGE)
        }
        if (clientMessageId.isBlank() || clientMessageId.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES) {
            return@withContext RelayPostResult.Failed(400, RETRY_ID_MESSAGE)
        }
        val transport = http() ?: return@withContext RelayPostResult.Failed(0, TRANSPORT_DOWN_MESSAGE)
        val payload = buildJsonObject {
            put("text", text)
            put("format", format.wire)
            put("clientMessageId", clientMessageId)
        }.toString()
        val body = payload.toRequestBody(JSON_MEDIA_TYPE)
        when (val result = transport.execute(relayRequest("${CHANNELS_PREFIX}${encodeSegment(safeChannel)}/messages", method = "POST", body = body))) {
            is GatewayHttpResult.Rejected ->
                RelayPostResult.Failed(result.statusCode, result.safeMessage)

            is GatewayHttpResult.Success -> result.consumeBody(::parsePost)
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing — every helper is fail-closed on the pinned projection shape.
// ---------------------------------------------------------------------------

private fun parseAvailability(bytes: ByteArray): RelayAvailability {
    val root = parseObject(bytes) ?: return RelayAvailability.Incompatible
    val channels = root.obj("channels") ?: return RelayAvailability.Incompatible
    val state = channels.string("status")?.toLaneState() ?: return RelayAvailability.Incompatible
    return RelayAvailability.Available(
        RelayChannelsStatus(
            state = state,
            message = channels.string("message"),
            guidance = channels.string("guidance"),
        ),
    )
}

private fun parseAuthorize(bytes: ByteArray): Boolean =
    parseObject(bytes)?.string("status") == "ready"

private fun parseChannels(bytes: ByteArray): List<RelayChannel>? {
    val rows = parseObject(bytes)?.array("channels") ?: return null
    val parsed = ArrayList<RelayChannel>(rows.size)
    for (row in rows) {
        val channel = (row as? JsonObject)?.let(::parseChannel) ?: return null
        parsed.add(channel)
    }
    return parsed
}

private fun parseChannel(row: JsonObject): RelayChannel? {
    val id = row.string("id") ?: return null
    val title = row.string("title") ?: return null
    val last = row.obj("lastMessage")
    return RelayChannel(
        id = id,
        title = title,
        kind = row.string("kind"),
        visibility = row.string("visibility"),
        archived = row.bool("archived"),
        latestSeq = row.long("latestSeq"),
        messageCount = row.long("messageCount"),
        threadCount = row.long("threadCount"),
        lastMessage = last?.let(::parseLastMessage),
    )
}

private fun parseLastMessage(last: JsonObject): RelayLastMessage? {
    val seq = last.long("seq") ?: return null
    return RelayLastMessage(
        id = last.string("id") ?: return null,
        seq = seq,
        preview = last.string("preview") ?: return null,
        senderKind = last.string("senderKind") ?: return null,
        status = last.string("status") ?: return null,
        createdAt = last.string("createdAt") ?: return null,
        senderDisplayName = last.string("senderDisplayName"),
    )
}

private fun parseHistory(bytes: ByteArray): RelayHistory? {
    val root = parseObject(bytes) ?: return null
    val rows = root.array("messages") ?: return null
    val messages = ArrayList<RelayMessage>(rows.size)
    for (row in rows) {
        val message = (row as? JsonObject)?.let(::parseMessage) ?: return null
        messages.add(message)
    }
    val cursor = root.obj("nextCursor")
    return RelayHistory(
        messages = messages,
        hasMore = root.bool("hasMore"),
        nextCursorBeforeSeq = cursor?.long("beforeSeq"),
        nextCursorAfterSeq = cursor?.long("afterSeq"),
    )
}

private fun parseMessage(row: JsonObject): RelayMessage? {
    val seq = row.long("seq") ?: return null
    val sender = row.obj("sender") ?: return null
    val body = row.obj("body") ?: return null
    val format = body.string("format")?.let(::messageFormatFromWire) ?: return null
    return RelayMessage(
        id = row.string("id") ?: return null,
        channelId = row.string("channelId") ?: return null,
        seq = seq,
        kind = row.string("kind") ?: return null,
        status = row.string("status") ?: return null,
        senderKind = sender.string("kind") ?: return null,
        senderId = sender.string("id") ?: return null,
        senderDisplayName = sender.string("displayName"),
        text = body.string("text") ?: return null,
        format = format,
        threadId = row.string("threadId"),
        parentMessageId = row.string("parentMessageId"),
        createdAt = row.string("createdAt") ?: return null,
        updatedAt = row.string("updatedAt") ?: return null,
        truncated = row.bool("truncated"),
        clientMessageId = row.string("clientMessageId"),
    )
}

private fun parsePost(bytes: ByteArray): RelayPostResult {
    val root = parseObject(bytes) ?: return CONTRACT_VIOLATION
    val message = root.obj("message")?.let(::parseMessage) ?: return CONTRACT_VIOLATION
    return RelayPostResult.Accepted(message)
}

private fun parseObject(bytes: ByteArray): JsonObject? =
    runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()

private fun String.toLaneState(): RelayLaneState? = when (this) {
    "ready" -> RelayLaneState.READY
    "offline" -> RelayLaneState.OFFLINE
    "auth_required" -> RelayLaneState.AUTH_REQUIRED
    "error" -> RelayLaneState.ERROR
    else -> null
}

private fun messageFormatFromWire(wire: String): RelayMessageFormat? = when (wire) {
    "markdown" -> RelayMessageFormat.MARKDOWN
    "text" -> RelayMessageFormat.TEXT
    else -> null
}

/** Non-blank and within the server's byte bound; ids are refused, not mangled. */
private fun validChannelId(raw: String): String? =
    raw.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES }

/**
 * Percent-encode one path segment. `+`-for-space is a form/query convention;
 * paths need `%20`, and `/` must never survive as a separator.
 */
private fun encodeSegment(raw: String): String =
    URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")

private fun relayRequest(
    suffix: String,
    method: String = "GET",
    body: RequestBody? = null,
    query: Map<String, String> = emptyMap(),
) = GatewayHttpRequest(
    path = "$BASE_PATH/$suffix",
    method = method,
    body = body,
    timeoutMillis = TIMEOUT_MILLIS,
    query = query,
    maxResponseBytes = MAX_RESPONSE_BYTES,
)

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private val CONTRACT_VIOLATION = RelayPostResult.Failed(null, UNUSABLE_RESPONSE_MESSAGE)

// Mirrors relay_proxy.py bounds at the plugin source of truth; the server
// remains authoritative and re-validates everything.
internal const val MAX_HISTORY_LIMIT = 50
private const val MAX_TEXT_BYTES = 64 * 1024
private const val MAX_ID_BYTES = 512

private const val PLUGIN_ID = "hermes-plugin-relay"
private const val BASE_PATH = "api/plugins/$PLUGIN_ID"
private const val CONNECTION_STATUS = "connection/status"
private const val CONNECTION_AUTHORIZE = "connection/authorize"
private const val CHANNELS = "channels"
private const val CHANNELS_PREFIX = "channels/"
private const val TIMEOUT_MILLIS = 8_000L
private const val MAX_RESPONSE_BYTES = 1024L * 1024L

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal const val PICK_CHANNEL_MESSAGE = "Pick a channel before sending."
internal const val EMPTY_TEXT_MESSAGE = "Type a message before sending."
internal const val LARGE_TEXT_MESSAGE = "That message is too large to send."
internal const val RETRY_ID_MESSAGE = "Hermes could not prepare that message. Try again."
internal const val TRANSPORT_DOWN_MESSAGE = "Reconnect to the Gateway and try again."
internal const val UNUSABLE_RESPONSE_MESSAGE = "The Relay workspace returned an unusable response. Try again."
