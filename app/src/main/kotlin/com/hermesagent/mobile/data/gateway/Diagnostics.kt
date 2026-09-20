package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.safeTurnErrorDetails
import java.net.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Pinned contract: tui_gateway/methods_config.py:342-375 @ 437116f9497c80d242ce034ff7f5d81dc277a337.
 * The authenticated socket grants access; there is no diagnostics capability query or per-method
 * permission bit. Never probe support by invoking this mutating method before consent.
 */
sealed interface DiagnosticsResult {
    data class Uploaded(val viewUrl: String?, val uploadId: String?, val expiresAt: String?) : DiagnosticsResult
    data object Unsupported : DiagnosticsResult
    data object Refused : DiagnosticsResult
    data object Failed : DiagnosticsResult
}

/** Only the production Nous portal may be opened. Operator overrides are not browser authority.
 * No userinfo, nondefault ports, fragments, escapes in the authority, or alternate URL schemes.
 * Keep the original signed path/query intact; never repair an untrusted URL into an accepted one.
 */
fun safeDiagnosticsViewUrl(raw: String?): String? {
    if (raw == null || raw.length > 4_096 || raw.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    return raw.takeIf {
        uri.scheme == "https" && uri.rawAuthority == "portal.nousresearch.com" &&
            uri.host == "portal.nousresearch.com" && uri.rawUserInfo == null && uri.port == -1 &&
            uri.rawFragment == null && !uri.isOpaque
    }
}

internal fun parseDiagnosticsResult(value: JsonElement): DiagnosticsResult {
    val obj = value as? JsonObject ?: return DiagnosticsResult.Failed
    val ok = (obj["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    if (ok != true) return DiagnosticsResult.Failed
    fun text(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val url = safeDiagnosticsViewUrl(text("view_url"))
    val id = text("upload_id")?.takeIf { it.isNotBlank() && it.length <= 200 }
        ?.let(::safeTurnErrorDetails)
    val expiry = text("expires_at")?.takeIf { it.length <= 100 }
        ?.let { runCatching { java.time.Instant.parse(it).toString() }.getOrNull() }
    return if (url != null || !id.isNullOrBlank()) DiagnosticsResult.Uploaded(url, id, expiry)
    else DiagnosticsResult.Failed
}
