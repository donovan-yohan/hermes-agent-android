package com.hermesagent.mobile.data.ssh

/**
 * A saved host. **Non-secret fields only** — this is the type that reaches
 * disk. Passwords, passphrases and private keys are carried separately in
 * [SshCredential], which is never persisted and never logged.
 */
data class HostProfile(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: AuthMethod = AuthMethod.Password,
    /**
     * Accepted host key, `SHA256:…` as `ssh-keygen -lf` prints it. Null means
     * this host has never been trusted, so the next probe is a first use.
     */
    val acceptedFingerprint: String? = null,
    /** Display name of the imported key document, for the UI only. */
    val importedKeyName: String? = null,
) {
    fun validate(): List<String> = buildList {
        if (host.isBlank()) add("Host is required.")
        if (host.contains(' ')) add("Host must not contain spaces.")
        if (port !in 1..65535) add("Port must be between 1 and 65535.")
        if (username.isBlank()) add("Username is required.")
    }

    val isValid: Boolean get() = validate().isEmpty()

    /** `user@host:port`, for labels. Never includes anything secret. */
    val label: String get() = "$username@$host:$port"
}

enum class AuthMethod { Password, PrivateKey }

/**
 * In-memory auth material for exactly one probe.
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

        fun password(value: String) = SshCredential(value.toCharArray(), null, null)

        fun privateKey(pem: String, passphrase: String?) =
            SshCredential(null, pem.toCharArray(), passphrase?.takeIf { it.isNotEmpty() }?.toCharArray())
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
 * (`apps/desktop/electron/ssh-connection.ts:324-362` @ `f82f2dba`).
 * `HostKeyChanged` is absent on purpose: on Android that is a typed result,
 * not a string we parse back out of stderr.
 */
enum class ProbeFailure { Unreachable, AuthFailed, Timeout, Cancelled, Unknown }
