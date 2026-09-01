package com.hermesagent.mobile.data.ssh

/**
 * A saved host. **Non-secret fields only** — this is the type that reaches
 * disk. Passwords, passphrases and private keys are carried separately in
 * [SshCredential], which is never persisted and never logged.
 * The destination the user types is not another persisted field: it is [destination],
 * derived from the parsed host, port and username, so there is exactly one
 * canonical copy of that answer.
 */
data class HostProfile(
    val host: String = "",
    val port: Int = SshDestination.DEFAULT_PORT,
    val username: String = "",
    /** Optional remote Hermes profile name. Non-secret and validated before use. */
    val remoteHermesProfile: String = "",
    /**
     * Tailscale SSH by default, because on a tailnet it is the choice that
     * needs no secret at all. It is still a choice: a target running ordinary
     * OpenSSH refuses it, and the screen says so.
     */
    val authMethod: AuthMethod = AuthMethod.TailscaleSsh,
    /**
     * Accepted host key, `SHA256:…` as `ssh-keygen -lf` prints it. Null means
     * this host has never been trusted, so the next probe is a first use.
     */
    val acceptedFingerprint: String? = null,
) {
    val isValid: Boolean
        get() = host.isNotBlank() && username.isNotBlank() &&
            host.none(Char::isWhitespace) && port in 1..65535

    /** `user@host`, port 22 implicit. Never includes anything secret. */
    val destination: String
        get() = if (host.isEmpty() && username.isEmpty()) {
            ""
        } else {
            SshDestination(username, host, port).format()
        }

    /**
     * Applies a parsed destination.
     *
     * Trust is scoped to a host and a port, not to an account: renaming the
     * user keeps the fingerprint that was accepted for this box, while any
     * change to where the connection goes drops it so the next probe is a first
     * use again. Dropping it is the safe direction — the worst case is one
     * extra fingerprint review.
     */
    fun withDestination(destination: SshDestination): HostProfile = copy(
        host = destination.host,
        port = destination.port,
        username = destination.username,
        acceptedFingerprint = acceptedFingerprint
            ?.takeIf { destination.host == host && destination.port == port },
    )

    /** What trust is scoped to. See [HostAnchor]. */
    val anchor: HostAnchor get() = HostAnchor(host, port)
}

/**
 * The `(host, port)` a host key belongs to.
 *
 * Trust is anchored here and nowhere else: an account rename is still the same
 * box with the same key, while a different host or port is a different sshd
 * whose key has never been reviewed. Carrying it as a value — rather than
 * comparing two strings at the call site — is what lets a pending review and a
 * completed probe be checked against the profile they were actually started
 * for, so host A's fingerprint can never be stored under host B.
 */
data class HostAnchor(val host: String, val port: Int)

/**
 * How the SSH layer proves who the user is.
 *
 * Persisted by [Enum.name], never by ordinal, so the order here is a UI
 * decision and adding an entry cannot rewrite an existing install's choice.
 */
enum class AuthMethod { TailscaleSsh, Password, PrivateKey }

/**
 * The authentication type each method puts on the wire.
 *
 * [SshAuthType.None] is not a fallback and never follows a failed attempt: it
 * is what Tailscale SSH uses deliberately, because the tailnet already
 * authenticated the node over WireGuard and checked the tailnet SSH policy, so
 * the SSH layer has nothing left to prove
 * (https://tailscale.com/docs/features/tailscale-ssh). Kept as a value rather
 * than inlined in the sshj adapter so that "exactly one method sends `none`" is
 * assertable without a live server.
 */
enum class SshAuthType { None, Password, PublicKey }

val AuthMethod.sshAuthType: SshAuthType
    get() = when (this) {
        AuthMethod.TailscaleSsh -> SshAuthType.None
        AuthMethod.Password -> SshAuthType.Password
        AuthMethod.PrivateKey -> SshAuthType.PublicKey
    }

/**
 * In-memory auth material for exactly one SSH attempt.
 *
 * There is no `toString` override needed because there is no data class here:
 * this is a plain class with no generated `toString`, so it cannot leak into a
 * log line by accident. [clear] zeroes the arrays best-effort — the JVM may
 * still hold copies, which is stated honestly rather than hidden.
 */
class SshCredential private constructor(
    internal val password: CharArray?,
    internal val privateKey: CharArray?,
    internal val passphrase: CharArray?,
) {
    /**
     * Whether this carries anything secret at all. A boolean, never the value:
     * it is how the Tailscale SSH path proves it sends nothing.
     */
    val carriesSecret: Boolean get() = password != null || privateKey != null || passphrase != null

    fun clear() {
        password?.fill(ZERO)
        privateKey?.fill(ZERO)
        passphrase?.fill(ZERO)
    }

    override fun toString(): String = "SshCredential(redacted)"

    companion object {
        /** Explicit NUL rather than a space literal: an invisible character in a
         *  wipe routine is a bug waiting to happen. */
        private const val ZERO = '\u0000'

        /** Tailscale SSH: there is nothing to send, and nothing to zero. */
        fun none() = SshCredential(null, null, null)

        fun password(value: String) = SshCredential(value.toCharArray(), null, null)

        /**
         * Copies [pem] rather than adopting it: the screen keeps its own array
         * so it can still offer a retry, and each side wipes what it holds.
         */
        fun privateKey(pem: CharArray, passphrase: String?) =
            SshCredential(null, pem.copyOf(), passphrase?.takeIf { it.isNotEmpty() }?.toCharArray())
    }
}

/** Outcome of a probe. Every branch is something the UI has copy for. */
sealed interface ProbeResult {
    /** Connected, authenticated, ran the command, closed cleanly. */
    data class Ok(
        val output: String,
        val serverVersion: String,
        val elapsedMillis: Long,
    ) : ProbeResult

    /**
     * First contact with this host. The probe stopped *before* authenticating:
     * nothing secret has been sent yet, and the user must accept the
     * fingerprint before a retry.
     */
    data class HostKeyPending(val fingerprint: String, val keyType: String) : ProbeResult

    /**
     * The host key changed. A hard failure, never a prompt — this is the one
     * outcome that can mean an active attack.
     */
    data class HostKeyMismatch(val expected: String, val actual: String) : ProbeResult

    data class Failed(val kind: ProbeFailure, val message: String) : ProbeResult
}

/**
 * Failure kinds, mirroring Desktop's classifier
 * (`apps/desktop/electron/ssh-connection.ts:324-362` @ `29112bef`).
 * `HostKeyChanged` is absent on purpose: on Android that is a typed result,
 * not a string we parse back out of stderr. [TailscaleSshRefused] has no
 * Desktop equivalent at all — Desktop's OpenSSH would simply move on to the
 * next auth method, and this app deliberately does not.
 *
 * [CryptoUnavailable] and [BadCommandResult] have no Desktop equivalent either:
 * Desktop shells out to OpenSSH, so it has neither a JCE provider to resolve
 * nor a sentinel to check.
 */
enum class ProbeFailure {
    Unreachable,
    AuthFailed,
    TailscaleSshRefused,
    Timeout,
    Cancelled,

    /**
     * This runtime cannot supply the algorithms an SSH handshake needs. See
     * [SshSecurityProvider]; the probe fails closed rather than negotiating
     * down to whatever is left.
     */
    CryptoUnavailable,

    /**
     * Connected and authenticated, but the probe command did not produce the
     * exact sentinel with exit status zero. Not a success.
     */
    BadCommandResult,

    Unknown,
}
