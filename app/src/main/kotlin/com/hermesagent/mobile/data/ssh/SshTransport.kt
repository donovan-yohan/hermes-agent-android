package com.hermesagent.mobile.data.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.CharArrayReader
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * The five things one probe does to a live SSH connection.
 *
 * It exists so the lifecycle contract — a cancelled or timed-out probe closes
 * the socket, never authenticates afterwards, and never publishes a result —
 * can be proven by a test without a server, a network, or a credential.
 * [SshjTransport] is the only production implementation; the seam is
 * `internal`, so it is a test affordance and not a second public API.
 */
internal interface SshTransport : Closeable {

    /** Connects and runs the key exchange, including host-key verification. */
    fun connect(host: String, port: Int)

    /** What the host-key verifier decided, or null if no key was ever offered. */
    val hostKeyVerdict: HostKeyVerdict?

    /** Wire name of the offered host key, e.g. `ssh-ed25519`. Non-secret. */
    val hostKeyType: String

    /** The peer's identification string, or empty before the exchange. */
    val serverVersion: String

    fun authenticate(profile: HostProfile, credential: SshCredential)

    /**
     * Runs one command and waits at most [timeoutMillis] for it to finish.
     *
     * The output is read bounded and the exit status is reported as it was
     * found: an implementation must not invent a zero for a command that never
     * reported one.
     */
    fun runCommand(command: String, maxBytes: Int, timeoutMillis: Long): CommandOutcome
}

/**
 * What one probe command produced.
 *
 * [exitStatus] is nullable because a channel can close without one — a peer
 * that dies, or a `join` that hits the deadline. That is a failure, and
 * modelling it as `null` rather than as `0` is what keeps it one.
 */
internal data class CommandOutcome(val output: String, val exitStatus: Int?)

/** Opens a transport for a probe. One call, one connection. */
internal fun interface SshTransports {
    fun open(storedFingerprint: String?): SshTransport
}

/** The real one, over sshj 0.40.0. */
internal object SshjTransports : SshTransports {
    override fun open(storedFingerprint: String?): SshTransport = SshjTransport(storedFingerprint)
}

/**
 * sshj behind [SshTransport].
 *
 * [close] is callable from any thread and is what makes cancellation real:
 * sshj's exchange blocks on a plain socket, which no interrupt can unstick, so
 * the only way to stop it from another thread is to close the transport under
 * it and let the blocked call fail.
 */
private class SshjTransport(storedFingerprint: String?) : SshTransport {

    private val verifier = TofuHostKeyVerifier(storedFingerprint)

    /**
     * Built here rather than shared: `DefaultConfig` decides which ciphers this
     * runtime can offer while it is being constructed, so it has to be younger
     * than [SshSecurityProvider.ensureReady].
     */
    private val config = DefaultConfig()

    private val client = SSHClient(config).apply {
        addHostKeyVerifier(verifier)
        connectTimeout = SshProbe.CONNECT_TIMEOUT_MILLIS
        timeout = SshProbe.CONNECT_TIMEOUT_MILLIS
    }

    override fun connect(host: String, port: Int) = client.connect(host, port)

    override val hostKeyVerdict: HostKeyVerdict? get() = verifier.verdict

    override val hostKeyType: String get() = verifier.keyType

    override val serverVersion: String get() = client.transport.serverVersion.orEmpty()

    /**
     * One attempt, one method. sshj would happily be handed a list to try in
     * turn; it is not, because a fallback is how a keyless choice quietly turns
     * into a password on the wire.
     */
    override fun authenticate(profile: HostProfile, credential: SshCredential) {
        when (profile.authMethod.sshAuthType) {
            // Tailscale already authenticated this node over WireGuard and
            // checked the tailnet SSH policy, so the SSH layer sends type
            // `none` and no secret exists to send.
            SshAuthType.None -> client.auth(profile.username, AuthNone())

            SshAuthType.Password -> {
                val password = credential.password ?: throw UserAuthException("No password supplied.")
                client.authPassword(profile.username, password)
            }

            SshAuthType.PublicKey -> {
                val pem = credential.privateKey ?: throw UserAuthException("No private key supplied.")
                val finder = credential.passphrase?.let { PasswordUtils.createOneOff(it) }
                client.authPublickey(profile.username, keyProvider(pem, finder))
            }
        }
    }

    override fun runCommand(command: String, maxBytes: Int, timeoutMillis: Long): CommandOutcome =
        client.run {
            // sshj's command stream is backed by this socket. Give reads the
            // same remaining overall deadline as `join`, not the 15s connect
            // timeout left over from setup.
            timeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            startSession().use { session ->
                session.exec(command).use { running ->
                    val bytes = running.inputStream.readBounded(maxBytes)
                    // A join that does not finish inside what is left of the
                    // deadline leaves the exit status unknown, and unknown is
                    // a failure — never a silent zero.
                    runCatching { running.join(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
                    // The sentinel is deliberately exact. Trimming would turn
                    // a banner, prompt, or whitespace-rewritten response into
                    // a false success.
                    CommandOutcome(String(bytes, Charsets.UTF_8), running.exitStatus)
                }
            }
        }

    override fun close() {
        // `disconnect()` first sends transport data and can contend with the
        // worker. Closing the raw socket wakes a thread blocked in connect,
        // KEX, authentication, or stdout read before that best-effort cleanup.
        runCatching { client.socket?.close() }
        runCatching { client.close() }
    }

    /**
     * The key file, read straight from the caller's `char[]`.
     *
     * `SSHClient.loadKeys` takes a `String`, which would mint an immutable copy
     * of the PEM that nothing can wipe. sshj's own seam underneath it takes a
     * `Reader`, so this goes there instead: [CharArrayReader] wraps the array
     * the caller already owns and copies nothing, which leaves
     * [SshCredential.clear] able to zero the only copy this app made. The
     * format is detected from a second reader over the same array because
     * detection consumes one.
     */
    private fun keyProvider(pem: CharArray, finder: PasswordFinder?): KeyProvider {
        val format = KeyProviderUtil.detectKeyFileFormat(CharArrayReader(pem), false)
        val provider: FileKeyProvider = Factory.Named.Util.create(config.fileKeyProviderFactories, format.toString())
            ?: throw UserAuthException("That key is not in a format this app can read.")
        provider.init(CharArrayReader(pem), null, finder)
        return provider
    }
}
