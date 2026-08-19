package com.hermesagent.mobile.data.ssh

/**
 * The one thing the SSH screen asks for: `user@host`, optionally `:port`.
 *
 * Three fields on a phone keyboard is three chances to mistype and two fields
 * whose answer is almost always the same. `ssh` itself takes one argument, and
 * so does this — on a tailnet the normal value is a MagicDNS short name
 * (`you@test-host`), which resolves on any signed-in device
 * (https://tailscale.com/docs/features/magicdns).
 *
 * Port 22 is implicit in both directions: it is never required on input and
 * never printed on output, so [format] round-trips through
 * [parseSshDestination].
 */
data class SshDestination(
    val username: String,
    val host: String,
    val port: Int = DEFAULT_PORT,
) {
    /**
     * The canonical text for this destination. An IPv6 literal is bracketed,
     * because `fd7a::1:2222` cannot be read back unambiguously.
     */
    fun format(): String {
        val target = if (host.contains(':')) "[$host]" else host
        return if (port == DEFAULT_PORT) "$username@$target" else "$username@$target:$port"
    }

    companion object {
        const val DEFAULT_PORT: Int = 22
    }
}

/** Either a destination or the sentence the screen shows instead. */
sealed interface DestinationParse {
    data class Valid(val destination: SshDestination) : DestinationParse
    data class Invalid(val reason: String) : DestinationParse
}

/**
 * Parses `user@host`, `user@host:port` and `user@[ipv6]:port`.
 *
 * Outer whitespace is trimmed — a paste or an autocomplete often carries a
 * trailing space. Whitespace *inside* is refused rather than stripped: a host
 * name has none, so its presence means the input is not what the user thinks it
 * is. A bare IPv6 address is refused for the same reason `ssh` requires
 * brackets: `fd7a::1:2222` has no single reading.
 */
fun parseSshDestination(raw: String): DestinationParse {
    val text = raw.trim()
    if (text.isEmpty()) return invalid("Enter a destination, for example you@hermes-box.")
    if (text.any(Char::isWhitespace)) return invalid("A destination cannot contain spaces.")

    // Last '@', as OpenSSH does: the host part is the one that cannot contain one.
    val at = text.lastIndexOf('@')
    if (at < 0) return invalid("A destination needs a user, as in you@hermes-box.")

    val username = text.substring(0, at)
    val target = text.substring(at + 1)
    if (username.isEmpty()) return invalid("A destination needs a user before the @.")

    if (target.startsWith('[')) {
        val close = target.indexOf(']')
        if (close < 0) return invalid("An IPv6 address needs a closing bracket, as in you@[fd7a::1].")

        val host = target.substring(1, close)
        hostProblem(host)?.let { return invalid(it) }

        val rest = target.substring(close + 1)
        val port = when {
            rest.isEmpty() -> SshDestination.DEFAULT_PORT
            rest.startsWith(':') -> parsePort(rest.drop(1)) ?: return invalid(PORT_PROBLEM)
            else -> return invalid("Put the port after the bracket, as in you@[fd7a::1]:2222.")
        }
        return DestinationParse.Valid(SshDestination(username, host, port))
    }

    return when (target.count { it == ':' }) {
        0 -> {
            hostProblem(target)?.let { return invalid(it) }
            DestinationParse.Valid(SshDestination(username, target))
        }

        1 -> {
            val host = target.substringBefore(':')
            hostProblem(host)?.let { return invalid(it) }
            val port = parsePort(target.substringAfter(':')) ?: return invalid(PORT_PROBLEM)
            DestinationParse.Valid(SshDestination(username, host, port))
        }

        else -> invalid("Wrap an IPv6 address in brackets, as in you@[fd7a::1]:2222.")
    }
}

private const val PORT_PROBLEM = "The port must be a number from 1 to 65535."

private fun invalid(reason: String) = DestinationParse.Invalid(reason)

private fun hostProblem(host: String): String? = when {
    host.isEmpty() -> "A destination needs a host after the @."
    host.any { it in "@[]/\\" } -> "That is not a host name."
    else -> null
}

/** Digits only: `toIntOrNull` alone would accept `+22` and `-22`. */
private fun parsePort(text: String): Int? = text
    .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    ?.toIntOrNull()
    ?.takeIf { it in 1..65535 }
