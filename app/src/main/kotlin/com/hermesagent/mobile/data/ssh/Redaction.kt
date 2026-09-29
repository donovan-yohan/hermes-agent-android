package com.hermesagent.mobile.data.ssh

/**
 * Secret redaction, ported from Desktop's `redactSecrets`
 * (`apps/desktop/electron/ssh-connection.ts:130-157` @ `72a3277cd7`).
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
)

private val NON_WHITESPACE = Regex("\\S+")
private val NUMERIC_PORT_PREFIX = Regex("\\d+\\b")

// Try each scheme-shaped run once, not every suffix of an unbroken tool result.
// Preserve leading digits/punctuation: the original rule could start after them.
private val URL_USERINFO = Regex(
    "(?<![A-Za-z0-9+.-])([0-9+.-]*)([A-Za-z][A-Za-z0-9+.-]*+://)[^/?#\\s@]++@",
)

fun redact(text: String?): String {
    var out = text ?: ""
    for ((pattern, replacement) in REDACTIONS) {
        out = pattern.replace(out, replacement)
    }
    out = redactSshTargets(out)
    return URL_USERINFO.replace(out, "$1$2<redacted>@")
}

private fun redactSshTargets(text: String): String = NON_WHITESPACE.replace(text) { match ->
    val token = match.value
    var firstAt = -1
    var secretStart = -1
    var secretEnd = -1
    for (index in token.indices) {
        when (token[index]) {
            '@' -> if (index > 0 && firstAt < 0) firstAt = index
            ':' -> {
                val start = index + 1
                if (firstAt >= 0 && firstAt < index - 1 && start < token.length &&
                    token[start] != ':' && NUMERIC_PORT_PREFIX.matchAt(token, start) == null
                ) {
                    // The old greedy username selected the last eligible host:secret
                    // in this token. A host can contain @, but cannot contain a colon.
                    secretStart = start
                    secretEnd = token.indexOf(':', start).takeIf { it >= 0 } ?: token.length
                }
                firstAt = -1
            }
        }
    }
    if (secretStart < 0) token else token.replaceRange(secretStart, secretEnd, "<redacted>")
}
