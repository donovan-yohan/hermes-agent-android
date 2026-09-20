package com.hermesagent.mobile.data.session

import com.hermesagent.mobile.data.ssh.redact
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Advisory wire descriptor; never use the foreground composer's model as failure identity. */
data class TurnErrorDetails(
    val details: String,
    val layer: String? = null,
    val code: String? = null,
    val retryable: Boolean = true,
    val provider: String? = null,
    val model: String? = null,
) {
    val title: String get() = when (layer) {
        "auth" -> "Authentication error"
        "billing" -> "Out of credits"
        "disk" -> "Disk full"
        "endpoint" -> "Custom endpoint error"
        "provider" -> "Provider error"
        "streaming" -> "Streaming connection error"
        "runtime" -> "Local runtime error"
        else -> "Hermes hit a problem"
    }

    fun copyText(summary: String): String = safeTurnErrorDetails(buildList {
        add("── Hermes error details ──")
        layer?.let { add("layer: $it") }
        code?.let { add("code: $it") }
        add("retryable: $retryable")
        provider?.let { add("provider: $it") }
        model?.let { add("model: $it") }
        add("error: $summary")
        if (details.isNotBlank()) add("details: $details")
    }.joinToString("\n"))
}

// Compile once; scan quoted values to avoid backtracking on long or malformed secrets.
private val CREDENTIAL_FIELD = Regex(
    """\b(authorization|proxy-authorization|password|passwd|api[_-]?key|(?:access|refresh|session|auth)[_-]?token|token|secret|client[_-]?secret|x-hermes-session-token|hermes_dashboard_session_token)["']?\s*[:=]\s*""",
    RegexOption.IGNORE_CASE,
)
private val DETAIL_REDACTIONS = listOf(
    Regex("""\bBearer\s+[^\s"']+""", RegexOption.IGNORE_CASE) to "Bearer <redacted>",
    Regex("""\bsk-[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE) to "<redacted>",
    Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""") to "<redacted endpoint>",
    Regex("""\b(?:[A-Za-z0-9-]+\.)+[a-z]{2,}(?::[0-9]+)?\b""", RegexOption.IGNORE_CASE) to "<redacted host>",
    Regex("""\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?::[0-9]+)?\b""") to "<redacted address>",
    Regex("""SHA256:[A-Za-z0-9+/=]+""") to "<redacted fingerprint>",
    Regex("""[\p{Cntrl}&&[^\n\t]]""") to "",
)

private fun redactCredentialFields(raw: String): String = buildString {
    var copiedThrough = 0
    var field = CREDENTIAL_FIELD.find(raw)
    while (field != null) {
        val start = field.range.last + 1
        append(raw, copiedThrough, start)
        var end = start
        val quote = raw.getOrNull(start)?.takeIf { it == '\'' || it == '"' }
        if (quote != null) {
            end++
            while (end < raw.length) {
                val character = raw[end++]
                if (character == '\\' && end < raw.length) end++
                else if (character == quote) break
            }
        } else if (field.groupValues[1].endsWith("authorization", ignoreCase = true)) {
            // Unknown schemes and Digest parameters are credentials too. Fail closed
            // through the entire header line, not just its first whitespace token.
            while (end < raw.length && raw[end] != '\r' && raw[end] != '\n') end++
        } else {
            while (end < raw.length && !raw[end].isWhitespace() && raw[end] !in "\"',;&}]") end++
        }
        append("<redacted>")
        copiedThrough = end
        field = CREDENTIAL_FIELD.find(raw, end)
    }
    append(raw, copiedThrough, raw.length)
}

/** Redact BEFORE bounding, and before SSH redaction can break quoted values. */
fun safeTurnErrorDetails(raw: String?): String {
    var safe = redact(redactCredentialFields(raw.orEmpty()))
    for ((pattern, replacement) in DETAIL_REDACTIONS) safe = pattern.replace(safe, replacement)
    return safe.trim().take(4_096)
}

/** Desktop lib/error-surface.ts:42-71 @ 437116f9497c80d242ce034ff7f5d81dc277a337. */
fun parseTurnErrorDetails(raw: String?, descriptor: JsonElement?): TurnErrorDetails {
    val obj = descriptor as? JsonObject
    fun string(key: String): String? = (obj?.get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    val layer = string("layer")?.takeIf { it in ERROR_LAYERS }
    return TurnErrorDetails(
        details = safeTurnErrorDetails(raw),
        layer = layer,
        code = if (layer != null) safeTurnErrorDetails(string("code") ?: "unknown").take(120) else null,
        retryable = layer == null || (obj?.get("retryable") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != false,
        provider = if (layer != null) string("provider")?.let(::safeTurnErrorDetails)?.take(120) else null,
        model = if (layer != null) string("model")?.let(::safeTurnErrorDetails)?.take(160) else null,
    )
}

private val ERROR_LAYERS = setOf("provider", "endpoint", "streaming", "auth", "billing", "gateway", "runtime", "disk")
