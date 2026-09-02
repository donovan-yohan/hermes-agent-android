package com.hermesagent.mobile.data.gateway

import java.io.IOException
import java.util.concurrent.TimeUnit
import okio.Buffer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One bounded authenticated HTTP request to the active Gateway. */
class GatewayHttpRequest(
    val path: String,
    val method: String,
    val body: RequestBody?,
    val timeoutMillis: Long,
    val query: Map<String, String> = emptyMap(),
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    /**
     * Whether a refusal's own explanation is worth reading back.
     *
     * Off by default because a refusal body is only useful to a caller that
     * acts differently on it, and [GatewayHttpResult.Rejected.envelopeBytes]
     * transfers ownership: a caller that never classifies a refusal would
     * otherwise pay an extra bounded read and hold a backend-authored buffer
     * it never wipes.
     */
    val captureEnvelope: Boolean = false,
)

sealed interface GatewayHttpResult {
    /** Response body bytes; ownership transfers to the caller, who must wipe. */
    class Success(val statusCode: Int, val bodyBytes: ByteArray) : GatewayHttpResult

    /**
     * A refused hop. [safeMessage] remains the only thing a surface may show.
     *
     * [statusCode] is `0` for exactly one condition: no HTTP exchange happened
     * at all — no endpoint, no credential, a URL that would not build, a verb
     * this transport does not serve, or a connection that never completed.
     * Every hop that did reach the Gateway reports the status the Gateway
     * answered with, *including* a 2xx whose body then overran the caller's
     * bound. A caller must be able to tell "the route does not exist here"
     * (404, a capability to remember) from "the request never left" (0, a
     * connection to fix) from "it answered, and the answer was too big for what
     * I asked" (2xx with the oversize sentence) — one shared code for the last
     * two would make a page too large for its bound look like a dead link.
     *
     * [envelopeBytes] is the refusing service's own structured explanation,
     * read under a tight bound. A status code alone cannot tell "this
     * credential lapsed" from "no credential at all", or "the backend is down"
     * from "the backend answered nonsense"; the services that know write it
     * down, so a caller that must act differently reads their answer instead of
     * guessing. It is empty when the hop wrote no body, and empty for every
     * request that did not ask for it via
     * [GatewayHttpRequest.captureEnvelope]. Ownership transfers to the caller,
     * who must wipe it, and nothing inside it may be shown verbatim.
     */
    class Rejected(
        val statusCode: Int,
        val safeMessage: String,
        val envelopeBytes: ByteArray = ByteArray(0),
    ) : GatewayHttpResult
}

/**
 * Consume transferred response ownership and wipe the mutable byte buffer.
 *
 * Be precise about what this does and does not clear. It zeroes *this* array —
 * the one array a caller decoded and could otherwise keep. It does not reach
 * the okio segments the body was streamed through on the way here:
 * `Buffer.readByteArray` copies out and returns its segments to okio's
 * `SegmentPool`, which recycles them without zeroing, so a copy of the response
 * can survive in pooled memory until something else overwrites it. That is a
 * property of the HTTP stack, not something this call can fix; the honest claim
 * is "the decoded buffer does not outlive the call", not "the bytes are gone
 * from the process". Redaction, not erasure, is what keeps a token or a
 * fingerprint out of anything a surface can read.
 */
internal inline fun <T> GatewayHttpResult.Success.consumeBody(block: (ByteArray) -> T): T =
    try {
        block(bodyBytes)
    } finally {
        bodyBytes.fill(0)
    }

/** Consume a refusal envelope and wipe the mutable byte buffer. */
internal inline fun <T> GatewayHttpResult.Rejected.consumeEnvelope(block: (ByteArray) -> T): T =
    try {
        block(envelopeBytes)
    } finally {
        envelopeBytes.fill(0)
    }

/**
 * Connection-owned authenticated HTTP transport for Gateway REST routes. The
 * manager resolves credentials per active leg (SSH loopback session token or
 * remote native bearer); callers never see a token, URL origin, or ticket.
 */
interface GatewayHttp {
    suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult
}

internal class OkHttpGatewayHttp(
    private val http: OkHttpClient,
    private val resolveEndpoint: () -> String?,
    private val resolveAuthorization: suspend () -> Pair<String, String>?,
) : GatewayHttp {
    override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
        val endpoint = resolveEndpoint()
            ?: return GatewayHttpResult.Rejected(0, RECONNECT_MESSAGE)
        val authorization = resolveAuthorization()
            ?: return GatewayHttpResult.Rejected(0, RECONNECT_MESSAGE)
        val url = (endpoint.trimEnd('/') + "/" + request.path.trimStart('/'))
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply { request.query.forEach(::addQueryParameter) }
            ?.build()
            ?: return GatewayHttpResult.Rejected(0, MALFORMED_REQUEST_MESSAGE)
        val scoped = http.newBuilder()
            .callTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
        return try {
            val builder = Request.Builder()
                .url(url)
                .header(authorization.first, authorization.second)
            when (request.method.uppercase()) {
                "POST" -> request.body?.let(builder::post)
                    ?: return GatewayHttpResult.Rejected(0, INCOMPLETE_MESSAGE)
                "GET" -> builder.get()
                "PUT" -> request.body?.let(builder::put)
                    ?: return GatewayHttpResult.Rejected(0, INCOMPLETE_MESSAGE)
                "PATCH" -> request.body?.let(builder::patch)
                    ?: return GatewayHttpResult.Rejected(0, INCOMPLETE_MESSAGE)
                // Destructive, and body-less like GET. The routes that delete
                // scope themselves in the query (hermes-agent @
                // 3ca096de5f8183cb2e0ec23673f294d5978656a3,
                // hermes_cli/web_routers/sessions.py:657-658), so a body on a
                // DELETE means a caller believes it is sending scope the
                // Gateway would ignore — and a delete that runs under a scope
                // its caller did not intend is the one mistake this transport
                // must refuse rather than forward.
                "DELETE" -> if (request.body != null) {
                    return GatewayHttpResult.Rejected(0, UNSUPPORTED_MESSAGE)
                } else {
                    builder.delete(null)
                }
                else -> return GatewayHttpResult.Rejected(0, UNSUPPORTED_MESSAGE)
            }
            scoped.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    if (body == null) {
                        GatewayHttpResult.Success(response.code, ByteArray(0))
                    } else if (request.maxResponseBytes <= 0 ||
                        body.contentLength() > request.maxResponseBytes
                    ) {
                        // The Gateway answered; this hop is not a dead route or
                        // a broken connection, so it does not report `0`.
                        GatewayHttpResult.Rejected(response.code, OVERSIZE_MESSAGE)
                    } else {
                        val bytes = body.readBounded(request.maxResponseBytes)
                            ?: return GatewayHttpResult.Rejected(response.code, OVERSIZE_MESSAGE)
                        GatewayHttpResult.Success(response.code, bytes)
                    }
                } else {
                    GatewayHttpResult.Rejected(
                        response.code,
                        when (response.code) {
                            401, 403 -> "Hermes did not accept this connection. Reconnect and try again."
                            in 500..599 -> "The Gateway could not complete that request. Try again."
                            else -> "Hermes refused that Gateway request."
                        },
                        // Read only for a caller that asked, and bounded hard
                        // even then: a refusal envelope is a handful of JSON
                        // fields, and anything larger is not one. An oversized
                        // or unreadable body simply yields no envelope, which
                        // callers already have to handle.
                        envelopeBytes = if (request.captureEnvelope) {
                            response.body?.readBounded(MAX_ENVELOPE_BYTES) ?: ByteArray(0)
                        } else {
                            ByteArray(0)
                        },
                    )
                }
            }
        } catch (_: IOException) {
            GatewayHttpResult.Rejected(0, "The Gateway route could not be reached. Check the connection and try again.")
        }
    }
}

private fun okhttp3.ResponseBody.readBounded(maxBytes: Long): ByteArray? {
    val sink = Buffer()
    val source = source()
    var total = 0L
    while (true) {
        val allowed = (maxBytes + 1L - total).coerceAtMost(8_192L)
        if (allowed <= 0L) return null
        val read = source.read(sink, allowed)
        if (read == -1L) return sink.readByteArray()
        total += read
        if (total > maxBytes) return null
    }
}

/**
 * The largest body this transport will read for a request that names no bound
 * of its own. Also the ceiling a caller that sizes its own bound should stay
 * under: asking for more than this is asking for a read that never happens.
 */
internal const val DEFAULT_MAX_RESPONSE_BYTES = 24L * 1024L * 1024L

/**
 * The transport's refusal vocabulary. Named rather than typed at each site so
 * a second caller of this transport says the same sentence for the same
 * condition, and so drift is a compile error instead of a stale comment.
 */
internal const val RECONNECT_MESSAGE = "Reconnect to the Gateway and try again."
internal const val MALFORMED_REQUEST_MESSAGE = "Hermes could not form that Gateway request."
private const val INCOMPLETE_MESSAGE = "Hermes got an incomplete Gateway request."
private const val UNSUPPORTED_MESSAGE = "Hermes got an unsupported Gateway request."
internal const val OVERSIZE_MESSAGE = "The Gateway returned too much data for this request."

/** A refusal envelope is a handful of JSON fields; anything larger is not one. */
private const val MAX_ENVELOPE_BYTES = 8L * 1024L
