package com.hermesagent.mobile.data.ssh

/**
 * Secret redaction, ported from Desktop's `redactSecrets`
 * (`apps/desktop/electron/ssh-connection.ts:130-157` @ `f82f2dba`).
 *
 * Everything the SSH layer surfaces — error messages, probe output, anything
 * that could reach a screen, a bug report or a log — goes through [redact]
 * first. The last rule is the important one on mobile: a password typed into
 * the *host* field must not survive into a shared error string.
 *
 * `SecretRedactionTest` feeds known secrets through this function; that test is
 * the release gate, not this comment.
 */
private val REDACTIONS: List<Pair<Regex, String>> = listOf(
    Regex("(HERMES_DASHBOARD_SESSION_TOKEN=)(\\S+)") to "$1<redacted>",
    Regex("(X-Hermes-Session-Token[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"'&]+)", RegexOption.IGNORE_CASE) to "$1<redacted>",
    Regex("(Authorization[\"']?\\s*:\\s*Bearer\\s+)(\\S+)", RegexOption.IGNORE_CASE) to "$1<redacted>",
    Regex("([?&](?:token|ticket)=)([^\\s&\"']+)", RegexOption.IGNORE_CASE) to "$1<redacted>",
    // Android-only additions: the two shapes an OpenSSH private key takes when
    // a paste or an import goes somewhere it should not.
    Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----") to
        "-----BEGIN PRIVATE KEY----- <redacted> -----END PRIVATE KEY-----",
    Regex("(password[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',]+)", RegexOption.IGNORE_CASE) to "$1<redacted>",
    // SSH target with a non-numeric segment where a port belongs.
    Regex("(\\S+@[^\\s:]+):(?!\\d+\\b)[^\\s:]+") to "$1:<redacted>",
)

fun redact(text: String?): String {
    var out = text ?: ""
    for ((pattern, replacement) in REDACTIONS) {
        out = pattern.replace(out, replacement)
    }
    return out
}
