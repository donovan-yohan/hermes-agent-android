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
)

sealed interface GatewayHttpResult {
    /** Response body bytes; ownership transfers to the caller, who must wipe. */
    class Success(val statusCode: Int, val bodyBytes: ByteArray) : GatewayHttpResult
    class Rejected(val statusCode: Int, val safeMessage: String) : GatewayHttpResult
}

/** Consume transferred response ownership and wipe the mutable byte buffer. */
internal inline fun <T> GatewayHttpResult.Success.consumeBody(block: (ByteArray) -> T): T =
    try {
        block(bodyBytes)
    } finally {
        bodyBytes.fill(0)
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
            ?: return GatewayHttpResult.Rejected(0, "Reconnect to the Gateway and try again.")
        val authorization = resolveAuthorization()
            ?: return GatewayHttpResult.Rejected(0, "Reconnect to the Gateway and try again.")
        val url = (endpoint.trimEnd('/') + "/" + request.path.trimStart('/'))
            .toHttpUrlOrNull()
            ?.newBuilder()
            ?.apply { request.query.forEach(::addQueryParameter) }
            ?.build()
            ?: return GatewayHttpResult.Rejected(0, "Hermes could not form that Gateway request.")
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
                    ?: return GatewayHttpResult.Rejected(0, "Hermes got an incomplete Gateway request.")
                "GET" -> builder.get()
                "PUT" -> request.body?.let(builder::put)
                    ?: return GatewayHttpResult.Rejected(0, "Hermes got an incomplete Gateway request.")
                else -> return GatewayHttpResult.Rejected(0, "Hermes got an unsupported Gateway request.")
            }
            scoped.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    if (body == null) {
                        GatewayHttpResult.Success(response.code, ByteArray(0))
                    } else if (request.maxResponseBytes <= 0 ||
                        body.contentLength() > request.maxResponseBytes
                    ) {
                        GatewayHttpResult.Rejected(0, "The Gateway returned too much data for this request.")
                    } else {
                        val bytes = body.readBounded(request.maxResponseBytes)
                            ?: return GatewayHttpResult.Rejected(
                                0,
                                "The Gateway returned too much data for this request.",
                            )
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

private const val DEFAULT_MAX_RESPONSE_BYTES = 24L * 1024L * 1024L
