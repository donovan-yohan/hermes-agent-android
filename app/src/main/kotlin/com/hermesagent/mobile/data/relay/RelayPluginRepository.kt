package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.consumeBody
import com.hermesagent.mobile.data.gateway.consumeEnvelope
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
    /**
     * The lane's own explanation of [state], written by the plugin on the host.
     *
     * Shown beside the state and never alone, and never raw: a surface reaches
     * it through [statusDetail], which redacts and bounds it the way every
     * other backend-authored string on a screen is. The app's own line for a
     * state comes from [statusMessage] instead, which is why that function
     * never returns this.
     */
    val message: String?,
    /**
     * Server-authored remediation hint carried by the forward-compatible
     * nested lane envelope only; no plugin at the pin sends one, and nothing
     * renders it yet.
     *
     * Parsed rather than dropped so the shape stays covered by
     * `RelayPluginRepositoryTest` while the Relay surface is still being
     * built. Whenever a surface does show it, it is server-authored text and
     * must go through the same redaction [statusDetail] applies to [message].
     */
    val guidance: String?,
)

/**
 * Why the Gateway refused this client, taken from the refusal envelope its
 * author wrote rather than guessed from the status code. The two answers have
 * different remedies, so they are different values.
 */
enum class RelaySignInReason {
    /** A credential was presented and has lapsed; one rotation may recover it. */
    SessionExpired,

    /** Nothing the Gateway recognises was presented; rotating would be a guess. */
    NoCredential,
}

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

    /**
     * Gateway authentication did not accept this client's credentials.
     * [reason] decides whether a rotation is worth spending before asking the
     * person to sign in again.
     */
    data class SignInRequired(val reason: RelaySignInReason) : RelayAvailability

    /** The plugin responded, but not in the pinned v1 shape. */
    data object Incompatible : RelayAvailability

    /**
     * No usable answer came back: either no authenticated route reached the
     * Gateway at all, or the hop was refused with nothing that identifies who
     * refused it or why. Deliberately the residual state — a refusal the
     * plugin explains is classified from its own envelope instead.
     */
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
    /**
     * Whether Relay says this channel is closed to new messages, as of the
     * window that just arrived.
     *
     * Null at the pinned plugin, which projects neither field:
     * `project_history` returns `messages`, `hasMore` and `nextCursor` and
     * nothing else (hermes-plugin-relay @ `563a8c8`,
     * `relay_proxy.py:342-359`). Desktop reads it from the same response
     * regardless (`desktop/plugin.js:141`) and gates its composer on it
     * (`:1087`), so this reads it too rather than porting half of that gate.
     *
     * It earns its place because the channel list is the only other source and
     * that list is not refreshed while a transcript is open: a channel archived
     * mid-read would otherwise keep accepting posts until someone navigated
     * back. Until a plugin sends it, the channel row remains the only signal
     * and this stays null.
     */
    val archived: Boolean? = null,
)

/** Outcome of posting one channel message. */
sealed interface RelayPostResult {
    /**
     * Relay accepted the post and acknowledged the stored row. A 200 without a
     * usable row is a contract violation, not an acceptance, so this is never
     * an acceptance without a message.
     */
    data class Accepted(val message: RelayMessage) : RelayPostResult

    /**
     * Not accepted. [statusCode] mirrors the failing hop (0 = could not reach
     * the Gateway; 409 = Relay conflict, never retry with the same id); null
     * means a 200 response violated the pinned contract.
     *
     * [retryable] is Relay's own classification, never this client's guess: it
     * is true only when the plugin's refusal envelope says the same request may
     * be sent again. Re-posting always reuses the original `clientMessageId`,
     * so a retry Relay has already accepted stays exactly-once.
     *
     * [code] is the plugin's own name for what it refused
     * (`dashboard/plugin_api.py:85-88` at the pin), carried out because
     * [safeMessage] cannot say it: the transport writes one generic sentence
     * for every status it has no remedy for, so "the text is too large" and
     * "the format is unknown" arrive at the composer wearing the same words. A
     * refusal this client made itself carries the same vocabulary, so one
     * mapping serves both and a local rejection and its wire twin cannot drift
     * apart. Null when nothing classified the refusal.
     */
    data class Failed(
        val statusCode: Int?,
        val safeMessage: String,
        val retryable: Boolean = false,
        val code: String? = null,
    ) : RelayPostResult
}

/** The plugin's structured refusal classification. */
internal data class RelayErrorEnvelope(val code: String, val retryable: Boolean)

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
class RelayPluginRepository(private val http: () -> GatewayHttp?) : RelayAvailabilityProbe {

    /** Probe the plugin without touching channel data. Cheap enough to poll. */
    override suspend fun availability(): RelayAvailability = withContext(Dispatchers.IO) {
        val transport = http() ?: return@withContext RelayAvailability.GatewayUnreachable
        when (val result = transport.execute(relayRequest(CONNECTION_STATUS, captureEnvelope = true))) {
            is GatewayHttpResult.Rejected ->
                result.consumeEnvelope { refusalToAvailability(result.statusCode, it) }

            is GatewayHttpResult.Success -> result.consumeBody(::parseAvailability)
        }
    }

    /**
     * Redeem the server-held grant (if any) and report the lane state that came
     * back: the endpoint answers the same flat envelope as `/connection/status`.
     * Sends a deliberately empty body — the endpoint rejects any content so a
     * client cannot smuggle scope.
     *
     * A grant is redeemable exactly once, so this is one deliberate action and
     * never a retry loop. Reporting the real lane state rather than a bare
     * success flag is what lets a caller tell "authorized and ready" from
     * "still needs authorization on the host" from "the Gateway was not
     * reachable at all" — three outcomes with three different next steps.
     */
    suspend fun reauthorize(): RelayAvailability = withContext(Dispatchers.IO) {
        val transport = http() ?: return@withContext RelayAvailability.GatewayUnreachable
        val empty = ByteArray(0).toRequestBody(null)
        when (val result = transport.execute(relayRequest(CONNECTION_AUTHORIZE, method = "POST", body = empty, captureEnvelope = true))) {
            is GatewayHttpResult.Rejected ->
                result.consumeEnvelope { refusalToAvailability(result.statusCode, it) }

            is GatewayHttpResult.Success -> result.consumeBody(::parseAvailability)
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
     * Relay's exactly-once contract keys on it. Locally invalid input is
     * refused before any network call, under the same status and the same
     * code the wire would have answered with.
     */
    suspend fun post(
        channelId: String,
        text: String,
        format: RelayMessageFormat,
        clientMessageId: String,
    ): RelayPostResult = withContext(Dispatchers.IO) {
        val safeChannel = validChannelId(channelId)
            ?: return@withContext RelayPostResult.Failed(
                400,
                PICK_CHANNEL_MESSAGE,
                code = ERROR_INVALID_CHANNEL,
            )
        // These mirror the server's own bounds *and* the status and code it
        // answers with (`dashboard/plugin_api.py:121-136,174-198` at the pin):
        // a size refusal is 413 there, so refusing locally under 400 would
        // teach a caller a different contract than the wire's.
        if (text.isBlank()) {
            return@withContext RelayPostResult.Failed(400, EMPTY_TEXT_MESSAGE, code = ERROR_INVALID_TEXT)
        }
        if (clientMessageId.isBlank()) {
            return@withContext RelayPostResult.Failed(
                400,
                RETRY_ID_MESSAGE,
                code = ERROR_INVALID_CLIENT_MESSAGE_ID,
            )
        }
        if (clientMessageId.toByteArray(Charsets.UTF_8).size > MAX_CLIENT_MESSAGE_ID_BYTES) {
            return@withContext RelayPostResult.Failed(
                413,
                RETRY_ID_MESSAGE,
                code = ERROR_CLIENT_MESSAGE_ID_TOO_LARGE,
            )
        }
        // The size gate is on the *encoded body*, because that is the one the
        // plugin actually applies. Encoded once and then sent: the bytes that
        // were measured are the bytes that go on the wire, and a body this
        // client already knows is too large costs no request — this runs before
        // the transport is even resolved.
        val encoded = relayPostBody(text, format, clientMessageId).toByteArray(Charsets.UTF_8)
        if (relayPostBodyTooLarge(encoded)) {
            return@withContext RelayPostResult.Failed(
                413,
                LARGE_TEXT_MESSAGE,
                code = ERROR_REQUEST_TOO_LARGE,
            )
        }
        val transport = http() ?: return@withContext RelayPostResult.Failed(0, TRANSPORT_DOWN_MESSAGE)
        val body = encoded.toRequestBody(JSON_MEDIA_TYPE)
        val request = relayRequest(
            "${CHANNELS_PREFIX}${encodeSegment(safeChannel)}/messages",
            method = "POST",
            body = body,
            captureEnvelope = true,
        )
        when (val result = transport.execute(request)) {
            is GatewayHttpResult.Rejected -> {
                val envelope = result.consumeEnvelope(::parseRelayError)
                RelayPostResult.Failed(
                    result.statusCode,
                    result.safeMessage,
                    // A conflict is exactly-once *working*; re-sending it is
                    // the one retry the contract forbids, whatever the
                    // envelope's own flag says.
                    retryable = envelope != null &&
                        envelope.retryable &&
                        envelope.code != ERROR_CONFLICT,
                    // Only the plugin's own classification, never any text it
                    // wrote: the code is a fixed vocabulary this build maps to
                    // its own sentence, and a code it does not know changes
                    // nothing.
                    code = envelope?.code,
                )
            }

            is GatewayHttpResult.Success -> result.consumeBody(::parsePost)
        }
    }
}

// ---------------------------------------------------------------------------
// Parsing — every helper is fail-closed on the pinned projection shape.
// ---------------------------------------------------------------------------

/**
 * The pinned lane envelope is flat — `{"status": …, "message"?: …}` straight
 * from `ConnectionStatus.to_wire()` (hermes-plugin-relay @
 * `563a8c846ab997dc965c20080787f46b4f644b29`, `relay_proxy.py:508-512`),
 * returned unwrapped by both `/connection/status` and `/connection/authorize`
 * (`dashboard/plugin_api.py:216-218,220-238`).
 *
 * A later plugin may nest that same object under `channels` beside other
 * lanes, so the nested shape is accepted as a forward-compatible fallback and
 * an optional `guidance` hint is read wherever it appears. Neither exists at
 * the pin. A body that is neither shape fails closed rather than rendering a
 * guess.
 */
private fun parseAvailability(bytes: ByteArray): RelayAvailability {
    val root = parseObject(bytes) ?: return RelayAvailability.Incompatible
    val lane = if (root.containsKey("status")) root else root.obj("channels")
    val state = lane?.string("status")?.toLaneState() ?: return RelayAvailability.Incompatible
    return RelayAvailability.Available(
        RelayChannelsStatus(
            state = state,
            message = lane.string("message"),
            guidance = lane.string("guidance"),
        ),
    )
}

/**
 * Read a refusal the way its author wrote it.
 *
 * Two different services can refuse the same request, and only one of them is
 * about a credential this device holds:
 *
 * - The **plugin** answers `{"error":{"code","message","retryable"}}`
 *   (`dashboard/plugin_api.py:85-88` at the pin). Its `auth_required` is the
 *   *host's* own Relay credential, which no sign-in on this device can supply,
 *   so it reads as the lane state it actually is. That the plugin wrote an
 *   envelope at all also proves it is mounted and enabled, which leaves the
 *   runtime gate's envelope-less 404 as the only honest "not on this Gateway".
 * - The **Gateway's auth gate** answers `{"error":"session_expired"` or
 *   `"unauthenticated","reason":…}` (hermes-agent @
 *   `936b970e281d5d28e930c5698f36bc4ebb54c7ba`,
 *   `hermes_cli/dashboard_auth/middleware.py:112-163`; a presented bearer that
 *   did not verify takes the `invalid_or_expired_session` branch at
 *   `:356-373`). That is this client's credential, and only it may cost a
 *   rotation or send someone to sign in.
 *
 * Only the classification is used; no text from an envelope is carried out. A
 * refusal this build cannot classify never spends a rotation.
 */
private fun refusalToAvailability(statusCode: Int, envelope: ByteArray): RelayAvailability {
    val root = parseObject(envelope)
    root?.obj("error")?.let { pluginError ->
        return when (pluginError.string("code")) {
            ERROR_AUTH_REQUIRED -> lane(RelayLaneState.AUTH_REQUIRED)
            ERROR_RELAY_UNAVAILABLE -> lane(RelayLaneState.OFFLINE)
            ERROR_RELAY_INVALID_RESPONSE, ERROR_RELAY_FAILED -> lane(RelayLaneState.ERROR)
            // A resource-shaped refusal cannot describe a connection probe.
            else -> RelayAvailability.Incompatible
        }
    }
    if (root != null && root.containsKey("error")) {
        val lapsed = root.string("error") == GATE_SESSION_EXPIRED ||
            root.string("reason") in GATE_LAPSED_REASONS
        return if (lapsed) SESSION_EXPIRED else NO_CREDENTIAL
    }
    return when (statusCode) {
        404 -> RelayAvailability.Missing
        401, 403 -> NO_CREDENTIAL
        else -> RelayAvailability.GatewayUnreachable
    }
}

private fun lane(state: RelayLaneState) =
    RelayAvailability.Available(RelayChannelsStatus(state, message = null, guidance = null))

/** The plugin's structured refusal; null when the body is not one. */
private fun parseRelayError(bytes: ByteArray): RelayErrorEnvelope? {
    val error = parseObject(bytes)?.obj("error") ?: return null
    val code = error.string("code") ?: return null
    return RelayErrorEnvelope(code, error.bool("retryable") ?: false)
}

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
    // A malformed preview poisons the row, not just the preview: a channel
    // whose last message cannot be trusted must not be rendered as one that
    // simply has no messages.
    val last = row.obj("lastMessage")?.let { parseLastMessage(it) ?: return null }
    return RelayChannel(
        id = id,
        title = title,
        kind = row.string("kind"),
        visibility = row.string("visibility"),
        archived = row.bool("archived"),
        latestSeq = row.long("latestSeq"),
        messageCount = row.long("messageCount"),
        threadCount = row.long("threadCount"),
        lastMessage = last,
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
        // Both spellings Desktop accepts (`desktop/plugin.js:141`), and only
        // those two: an absent signal stays absent rather than becoming false,
        // so "the plugin did not say" never reads as "the channel is open".
        archived = root.bool("archived")
            ?: true.takeIf { root.string("status") == ARCHIVED_STATUS },
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
    raw.takeIf { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_CHANNEL_ID_BYTES }

/**
 * The exact bytes one post puts on the wire.
 *
 * Extracted so the size gate and the request are the same string: a bound
 * checked against anything other than what is actually sent is a bound on a
 * different message. The three keys and their order are the frozen body
 * `_post_body` demands (`dashboard/plugin_api.py:174-181` at the pin).
 */
internal fun relayPostBody(
    text: String,
    format: RelayMessageFormat,
    clientMessageId: String,
): String = buildJsonObject {
    put("text", text)
    put("format", format.wire)
    put("clientMessageId", clientMessageId)
}.toString()

/**
 * Whether an encoded body is past the plugin's whole-request gate.
 *
 * Takes the finished bytes rather than the parts so there is one comparison in
 * the app, not one per caller: the composer asks this before it dispatches and
 * [post] asks it again about the very bytes it is about to send. The [String]
 * overload is for the caller that has no reason to keep the encoding; [post]
 * does, and hands the same array to the request body.
 */
internal fun relayPostBodyTooLarge(encoded: ByteArray): Boolean =
    encoded.size > MAX_REQUEST_BODY_BYTES

internal fun relayPostBodyTooLarge(payload: String): Boolean =
    relayPostBodyTooLarge(payload.toByteArray(Charsets.UTF_8))

/**
 * Percent-encode one path segment. `+`-for-space is a form/query convention;
 * paths need `%20`, and `/` must never survive as a separator.
 */
private fun encodeSegment(raw: String): String =
    URLEncoder.encode(raw, Charsets.UTF_8.name()).replace("+", "%20")

/**
 * [captureEnvelope] is set only by the three callers that actually classify a
 * refusal with [consumeEnvelope]. The two that answer `null` on any rejection
 * must not ask for a body they would then drop un-wiped.
 */
private fun relayRequest(
    suffix: String,
    method: String = "GET",
    body: RequestBody? = null,
    query: Map<String, String> = emptyMap(),
    captureEnvelope: Boolean = false,
) = GatewayHttpRequest(
    path = "$BASE_PATH/$suffix",
    method = method,
    body = body,
    timeoutMillis = TIMEOUT_MILLIS,
    query = query,
    maxResponseBytes = MAX_RESPONSE_BYTES,
    captureEnvelope = captureEnvelope,
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

/**
 * The bound one post is actually held to: the *whole encoded request body*.
 *
 * The plugin reads every request under `MAX_DESKTOP_REQUEST_BYTES` and refuses
 * a longer one with 413 `request_too_large` before it has looked at a single
 * field (hermes-plugin-relay @ `563a8c8`, `dashboard/plugin_api.py:25,121-136`,
 * called ahead of `_post_body` at `:174-198`). Its text bound
 * (`MAX_MESSAGE_TEXT_BYTES`, `relay_proxy.py:28`) is the same 64 KiB, and JSON
 * framing plus escaping is never zero bytes, so the body gate always binds
 * first — a text at exactly the text bound is already a 65,624-byte body. One
 * bound is therefore both bounds, and it is this one.
 */
internal const val MAX_REQUEST_BODY_BYTES = 64 * 1024

/** `MAX_CHANNEL_ID_BYTES` (`relay_proxy.py:30`); over it is 400 `invalid_channel`. */
internal const val MAX_CHANNEL_ID_BYTES = 512

/** `MAX_CLIENT_MESSAGE_ID_BYTES` (`relay_proxy.py:29`); over it is 413. */
internal const val MAX_CLIENT_MESSAGE_ID_BYTES = 512

// Refusal codes the plugin writes into its error envelope
// (`dashboard/plugin_api.py:92-113` at the pin). Codes this build does not
// know stay unclassified rather than being folded into a neighbour.
private val SESSION_EXPIRED = RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired)
private val NO_CREDENTIAL = RelayAvailability.SignInRequired(RelaySignInReason.NoCredential)

/** The auth gate's error code for a credential that was presented and lapsed. */
private const val GATE_SESSION_EXPIRED = "session_expired"

/**
 * Gate reasons meaning a credential existed and lapsed, not that none was sent.
 *
 * Only the reasons `_unauth_response` actually puts on the wire belong here:
 * at hermes-agent @ `936b970e281d5d28e930c5698f36bc4ebb54c7ba` those are
 * `invalid_or_expired_session` (`hermes_cli/dashboard_auth/middleware.py:373`,
 * `:507`) and `no_cookie` (`:202`, `:388`), and only the first means a
 * credential was presented. `refresh_expired` (`:565-572`) is an audit-log
 * reason and never reaches a client, so classifying on it would be inventing a
 * wire value.
 *
 * At the pin this is redundant with the [GATE_SESSION_EXPIRED] check beside it:
 * `error` is derived from `reason` (`:149-153`), and a relay path is always
 * under `/api/`, so the two fields cannot disagree. Both are read on purpose —
 * a gate that later adds a lapsed reason without changing `error`, or narrows
 * `error` without changing `reason`, should keep classifying rather than
 * silently fall through to "nothing was presented" and stop spending the
 * rotation that would fix it.
 */
private val GATE_LAPSED_REASONS = setOf("invalid_or_expired_session")

/**
 * The plugin's refusal vocabulary, as `dashboard/plugin_api.py` writes it at
 * the pin: `_error` for the lane classifications (`:92-111`) and
 * `RequestValidationError` for the body ones (`:126-198`). Internal rather
 * than file-private because the composer maps the same codes to the sentence a
 * person reads, and two copies of a wire vocabulary is how they drift.
 */
internal const val ERROR_AUTH_REQUIRED = "auth_required"
internal const val ERROR_CONFLICT = "conflict"
internal const val ERROR_RELAY_UNAVAILABLE = "relay_unavailable"
internal const val ERROR_RELAY_INVALID_RESPONSE = "relay_invalid_response"
internal const val ERROR_RELAY_FAILED = "relay_error"

/** 413 from the whole-body gate, before any field is read (`:126-136`). */
internal const val ERROR_REQUEST_TOO_LARGE = "request_too_large"

/** 413 from the text field's own bound (`:187-188`). */
internal const val ERROR_TEXT_TOO_LARGE = "text_too_large"

/** 400 for text that is absent or blank (`:185-186`). */
internal const val ERROR_INVALID_TEXT = "invalid_text"

/** 400 for a format outside `{markdown, text}` (`:189-190`). */
internal const val ERROR_INVALID_FORMAT = "invalid_format"

/** 400 for a channel id that is empty or over its bound (`:153-156`). */
internal const val ERROR_INVALID_CHANNEL = "invalid_channel"

/** 400 for a missing or non-string retry id (`:191-194`). */
internal const val ERROR_INVALID_CLIENT_MESSAGE_ID = "invalid_client_message_id"

/** 413 for a retry id over its bound (`:195-198`). */
internal const val ERROR_CLIENT_MESSAGE_ID_TOO_LARGE = "client_message_id_too_large"

private const val PLUGIN_ID = "hermes-plugin-relay"
private const val BASE_PATH = "api/plugins/$PLUGIN_ID"
private const val CONNECTION_STATUS = "connection/status"
private const val CONNECTION_AUTHORIZE = "connection/authorize"
private const val CHANNELS = "channels"
private const val CHANNELS_PREFIX = "channels/"
private const val ARCHIVED_STATUS = "archived"
private const val TIMEOUT_MILLIS = 8_000L
private const val MAX_RESPONSE_BYTES = 1024L * 1024L

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

internal const val PICK_CHANNEL_MESSAGE = "Pick a channel before sending."
internal const val EMPTY_TEXT_MESSAGE = "Type a message before sending."
internal const val LARGE_TEXT_MESSAGE = "That message is too large to send."
internal const val UNSUPPORTED_FORMAT_MESSAGE =
    "Relay would not accept this message's format. Update Relay on the Gateway host, then send it again."
internal const val RETRY_ID_MESSAGE = "Hermes could not prepare that message. Try again."
internal const val TRANSPORT_DOWN_MESSAGE = "Reconnect to the Gateway and try again."
internal const val UNUSABLE_RESPONSE_MESSAGE = "The Relay workspace returned an unusable response. Try again."
