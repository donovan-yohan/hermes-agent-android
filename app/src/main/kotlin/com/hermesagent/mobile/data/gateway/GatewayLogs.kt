package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.safeTurnErrorDetails
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

sealed interface GatewayLogsResult {
    data class Content(val text: String, val truncated: Boolean) : GatewayLogsResult
    data object Empty : GatewayLogsResult
    data object Refused : GatewayLogsResult
    data object Unsupported : GatewayLogsResult
    data object Oversize : GatewayLogsResult
    data object Failed : GatewayLogsResult
}

internal const val GATEWAY_LOG_MAX_BYTES = 256L * 1024L
internal const val GATEWAY_LOG_MAX_TEXT = 4_096

/** GET /api/logs, hermes_cli/web_routers/status.py:720-754; hermes_cli/logs.py:17-26,167-209
 * @ 437116f9497c80d242ce034ff7f5d81dc277a337. Operator-dashboard authorization, not a
 * session permission. Reads the effective process home's errors log, never a selected profile.
 */
internal suspend fun readGatewayLogs(http: GatewayHttp, current: () -> Boolean): GatewayLogsResult =
    // The blocking transport may finish after dismissal/cancellation. Always consume and wipe
    // its transferred bytes, then discard stale results. Transport enforces the 10-second bound.
    withContext(NonCancellable) {
        if (!current()) return@withContext GatewayLogsResult.Failed
        try {
            when (val response = http.execute(GatewayHttpRequest(
                path = "/api/logs", method = "GET", body = null, timeoutMillis = 10_000,
                query = mapOf("file" to "errors", "lines" to "100"),
                maxResponseBytes = GATEWAY_LOG_MAX_BYTES, isCurrent = current,
            ))) {
                is GatewayHttpResult.Success -> response.consumeBody { bytes ->
                    if (!current()) GatewayLogsResult.Failed
                    else if (bytes.size > GATEWAY_LOG_MAX_BYTES) GatewayLogsResult.Oversize
                    else parseGatewayLogs(bytes)
                }
                is GatewayHttpResult.Rejected -> response.consumeEnvelope {
                    when {
                        response.statusCode == 401 || response.statusCode == 403 -> GatewayLogsResult.Refused
                        response.statusCode == 404 || response.statusCode == 405 || response.statusCode == 501 -> GatewayLogsResult.Unsupported
                        response.statusCode in 200..299 && response.safeMessage == OVERSIZE_MESSAGE -> GatewayLogsResult.Oversize
                        else -> GatewayLogsResult.Failed
                    }
                }
            }
        } catch (_: Exception) {
            GatewayLogsResult.Failed // Never retain backend errors or exception messages.
        }
    }

private fun parseGatewayLogs(bytes: ByteArray): GatewayLogsResult {
    val envelope = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        ?: return GatewayLogsResult.Failed
    if (envelope.keys != setOf("file", "lines")) return GatewayLogsResult.Failed
    val file = envelope["file"] as? JsonPrimitive ?: return GatewayLogsResult.Failed
    if (!file.isString || file.content != "errors") return GatewayLogsResult.Failed
    val rows = envelope["lines"] as? JsonArray ?: return GatewayLogsResult.Failed
    if (rows.any { it !is JsonPrimitive || !it.isString }) return GatewayLogsResult.Failed
    if (rows.isEmpty()) return GatewayLogsResult.Empty
    // Redact the whole bounded excerpt before retaining any text (including multiline secrets).
    val raw = rows.takeLast(100).joinToString("\n") { (it as JsonPrimitive).content }
    val safe = safeTurnErrorDetails(raw)
    return GatewayLogsResult.Content(safe, rows.size > 100 || safe.length == GATEWAY_LOG_MAX_TEXT)
}
