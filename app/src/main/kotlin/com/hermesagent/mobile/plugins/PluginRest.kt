package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.DEFAULT_MAX_RESPONSE_BYTES
import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.RECONNECT_MESSAGE
import okhttp3.RequestBody

/**
 * Options for a plugin REST call, mirroring Desktop's `PluginRestOptions`
 * (`apps/desktop/src/api/plugins.ts:25-30` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
data class PluginRestOptions(
    val method: String = "GET",
    val body: RequestBody? = null,
    val query: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 10_000L,
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    val captureEnvelope: Boolean = false,
)

/**
 * Result of a plugin REST execution.
 */
sealed interface PluginRestResult {
    /** HTTP 2xx response. Body bytes ownership belongs to the caller. */
    data class Success(val statusCode: Int, val bodyBytes: ByteArray) : PluginRestResult

    /**
     * The plugin is not installed or is disabled in the Gateway's config.
     * The Gateway's runtime gate middleware returns 404 for disabled plugins.
     */
    data object UnavailableOnGateway : PluginRestResult

    /**
     * The request was rejected by the Gateway or could not be completed.
     */
    data class Refused(
        val statusCode: Int,
        val safeMessage: String,
        val envelopeBytes: ByteArray = ByteArray(0),
    ) : PluginRestResult
}

/**
 * Normalize `path` to a leading-slash suffix relative to `/api/plugins/<id>`.
 * Refuses path traversal containing `..` segments.
 *
 * Direct port of Desktop's `pluginPathSuffix`
 * (`apps/desktop/src/api/plugins.ts:33-41` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
fun normalizePluginPathSuffix(caller: String, path: String): String {
    val suffix = if (path.startsWith("/")) path else "/$path"
    val pathOnly = suffix.split(Regex("[?#]"), limit = 2)[0]
    if (pathOnly.split("/").contains("..")) {
        throw IllegalArgumentException("$caller: illegal path traversal in \"$path\"")
    }
    return suffix
}

/**
 * The plugin REST door. Calls are scoped BY CONSTRUCTION to the plugin's own
 * backend namespace (`/api/plugins/<id>`).
 *
 * Direct Kotlin port of Desktop's `pluginRest`
 * (`apps/desktop/src/api/plugins.ts:44-55` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
interface PluginRest {
    suspend fun execute(
        pluginId: String,
        path: String,
        options: PluginRestOptions = PluginRestOptions(),
    ): PluginRestResult
}

/**
 * Production implementation of [PluginRest] over [GatewayHttp].
 */
class GatewayPluginRest(
    private val http: () -> GatewayHttp?,
) : PluginRest {
    override suspend fun execute(
        pluginId: String,
        path: String,
        options: PluginRestOptions,
    ): PluginRestResult {
        val suffix = normalizePluginPathSuffix("PluginRest", path)
        val transport = http() ?: return PluginRestResult.Refused(0, RECONNECT_MESSAGE)

        val request = GatewayHttpRequest(
            path = "api/plugins/$pluginId$suffix",
            method = options.method,
            body = options.body,
            timeoutMillis = options.timeoutMillis,
            query = options.query,
            maxResponseBytes = options.maxResponseBytes,
            captureEnvelope = options.captureEnvelope,
        )

        // A bare 404 is the Gateway's plugin runtime gate (missing or disabled
        // plugin); a 404 carrying a JSON envelope is the plugin itself refusing
        // and is returned as Refused.
        return when (val result = transport.execute(request)) {
            is GatewayHttpResult.Success -> PluginRestResult.Success(result.statusCode, result.bodyBytes)
            is GatewayHttpResult.Rejected -> {
                if (result.statusCode == 404 && result.envelopeBytes.isEmpty()) {
                    PluginRestResult.UnavailableOnGateway
                } else {
                    PluginRestResult.Refused(result.statusCode, result.safeMessage, result.envelopeBytes)
                }
            }
        }
    }
}
