package com.hermesagent.mobile.ui.system

import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.data.ssh.redactPrivateKeys
import com.hermesagent.mobile.data.updates.safeBackendLine

private val AWS_CREDENTIAL_KEYS = setOf("aws_access_key_id", "aws_secret_access_key", "aws_security_token", "aws_session_token")
private val HOST_KEYS = setOf("host", "hostname", "fingerprint", "server", "machine", "node", "peer", "endpoint")

// Consume each identifier and value once. In particular, do not retry an
// arbitrary-length credential-key prefix at every position in a long nonmatch.
private fun redactAssignments(text: String, checkpoint: () -> Unit): String = buildString {
    var i = 0
    var copied = 0
    while (i < text.length) {
        if (i % 1024 == 0) checkpoint()
        if (!text[i].isLetter() && text[i] != '_') { i++; continue }
        val start = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) {
            if (i % 1024 == 0) checkpoint()
            i++
        }
        val key = text.substring(start, i).lowercase(java.util.Locale.ROOT).replace('-', '_')
        val credential = key == "authorization" || key == "bearer" ||
            key.endsWith("token") || key.endsWith("password") || key.endsWith("secret") ||
            key.endsWith("api_key") || key.endsWith("apikey") ||
            key in AWS_CREDENTIAL_KEYS
        val identity = key in HOST_KEYS
        if (!credential && !identity) continue
        var value = i
        if (value < text.length && text[value] in "\"'") value++
        while (value < text.length && text[value].isWhitespace()) value++
        if (value >= text.length || text[value] !in ":=") continue
        value++
        while (value < text.length && text[value].isWhitespace()) value++
        if (value == text.length) continue
        val quote = text[value].takeIf { it in "\"'" }
        var end = value + if (quote != null) 1 else 0
        if (quote != null) {
            // Escaped quotes do not end a quoted JSON/log value; missing closer
            // consumes the remaining tail, including continuation lines.
            while (end < text.length) {
                if (end % 1024 == 0) checkpoint()
                if (text[end] == '\\' && end + 1 < text.length) { end += 2; continue }
                if (text[end++] == quote) break
            }
        } else {
            while (end < text.length && if (key == "authorization") text[end] !in "\r\n" else !text[end].isWhitespace() && text[end] !in ",;") {
                if (end % 1024 == 0) checkpoint()
                end++
            }
        }
        append(text, copied, value)
        append("<redacted>")
        copied = end
        i = end
    }
    append(text, copied, text.length)
}

private val LOG_AUTH = Regex("(?i)\\b(?:Bearer|Basic)\\s+[^\\s\"',;]+")
// Structured paths, not every slash (which would destroy dates and severity text).
// Quoted absolute paths may contain spaces. All unquoted runs are possessive.
private val LOG_PATH = Regex("[\"'](?:/|[A-Za-z]:[\\\\/]|\\\\\\\\)[^\"'\\r\\n]*+[\"']|(?<![A-Za-z0-9])(?:[A-Za-z]:[\\\\/]|\\\\\\\\|~/|\\.\\.?/|/)[^\\s\"'<>;,)]++")
private val LOG_CONTEXT_HOST = Regex("(?i)(\\b(?:connected to|connecting to|connection to|resolved host|remote host|on host|from host)\\s+)[A-Za-z0-9_][A-Za-z0-9_.:-]*+")
// product-copy-allow: Bounded network-identity pattern, never product copy.
private val LOG_ADDRESS = Regex("(?i)(?:https?|wss?|ssh)://[^\\s\\\"'<>]++|SHA256:[A-Za-z0-9+/=]++|\\b(?:[a-z0-9_-]+\\.){1,127}[a-z][a-z0-9-]*\\b|\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b|(?<![a-z0-9])(?=[0-9a-f:]{0,39}::)(?:[0-9a-f]{0,4}:){2,8}[0-9a-f:]{0,39}|(?<![a-z0-9])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![a-z0-9])")

internal fun sanitizeLogTail(lines: List<String>, checkpoint: () -> Unit = {}): List<String> {
    if (lines.isEmpty()) return emptyList()
    checkpoint()
    // PEM first, assignments before shared SSH redaction: shared password rules
    // otherwise split multiline quoted values and leave their continuation.
    var text = redactPrivateKeys(lines.joinToString("\n"))
    text = redactAssignments(text, checkpoint)
    checkpoint()
    text = LOG_AUTH.replace(text, "<redacted>")
    text = LOG_CONTEXT_HOST.replace(text, "$1<redacted>")
    text = LOG_ADDRESS.replace(text, "<redacted>")
    text = LOG_PATH.replace(text, "<redacted>")
    text = redact(text)
    checkpoint()
    return text.lines().take(200).map {
        checkpoint()
        safeBackendLine(it).filter { ch -> !ch.isISOControl() || ch == '\t' }
    }
}
