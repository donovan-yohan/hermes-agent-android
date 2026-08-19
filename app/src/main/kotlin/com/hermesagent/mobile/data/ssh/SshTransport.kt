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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import net.schmizz.sshj.connection.channel.direct.Parameters

/**
 * The internal transport shared by the SSH diagnostic and live Gateway path.
 *
 * Its seam lets cancellation, bounded exec, forwarding, and close ownership be
 * proven by tests without a server, network, or credential.
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

    /**
     * Runs a bounded command on this authenticated connection.
     *
     * [stdin] is written to the channel and never placed in argv. Implementors
     * must cap both output streams and report truncation rather than returning
     * an apparently complete value. The default preserves probe doubles; the
     * live transport implements stdin and stderr fully.
     */
    fun exec(
        command: String,
        stdin: ByteArray? = null,
        maxBytes: Int = DEFAULT_EXEC_BYTES,
        timeoutMillis: Long = DEFAULT_EXEC_TIMEOUT_MILLIS,
    ): ExecOutcome {
        require(stdin == null) { "This transport does not support command stdin." }
        val outcome = runCommand(command, maxBytes, timeoutMillis)
        return ExecOutcome(
            stdout = outcome.output.toByteArray(),
            stderr = ByteArray(0),
            exitStatus = outcome.exitStatus,
            truncated = false,
        )
    }

    /** Starts a forward whose actual listener is already bound to 127.0.0.1. */
    fun openLoopbackForward(remotePort: Int): SshForward =
        throw UnsupportedOperationException("This transport does not support forwarding.")

    /** Enables client-owned keepalive for a connection that outlives one exec. */
    fun enableKeepAlive(intervalSeconds: Int) = Unit

    companion object {
        const val DEFAULT_EXEC_BYTES: Int = 64 * 1024
        const val DEFAULT_EXEC_TIMEOUT_MILLIS: Long = 15_000
    }
}

/**
 * What one probe command produced.
 *
 * [exitStatus] is nullable because a channel can close without one — a peer
 * that dies, or a `join` that hits the deadline. That is a failure, and
 * modelling it as `null` rather than as `0` is what keeps it one.
 */
internal data class CommandOutcome(val output: String, val exitStatus: Int?)

internal data class ExecOutcome(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitStatus: Int?,
    val truncated: Boolean,
) {
    fun clear() {
        stdout.fill(0)
        stderr.fill(0)
    }
}

/** One loopback listener carried by the authenticated SSH connection. */
internal interface SshForward : Closeable {
    val localPort: Int
    val bindAddress: String
    val isOpen: Boolean
}

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
internal class SshjTransport(storedFingerprint: String?) : SshTransport {

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
    private val forwards = mutableSetOf<SshForward>()

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

    override fun exec(
        command: String,
        stdin: ByteArray?,
        maxBytes: Int,
        timeoutMillis: Long,
    ): ExecOutcome = client.run {
        require(maxBytes in 1..MAX_EXEC_BYTES) { "maxBytes is outside the bounded exec limit." }
        timeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        startSession().use { session ->
            session.exec(command).use { running ->
                stdin?.let { input ->
                    running.outputStream.use { stream ->
                        stream.write(input)
                        stream.flush()
                    }
                }
                val stdoutRead = running.inputStream.readBounded(maxBytes + 1)
                val stderrRead = running.errorStream.readBounded(maxBytes + 1)
                val truncated = stdoutRead.size > maxBytes || stderrRead.size > maxBytes
                val stdout = stdoutRead.copyOf(minOf(stdoutRead.size, maxBytes))
                val stderr = stderrRead.copyOf(minOf(stderrRead.size, maxBytes))
                stdoutRead.fill(0)
                stderrRead.fill(0)
                if (truncated) runCatching { running.close() }
                runCatching { running.join(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
                ExecOutcome(stdout, stderr, running.exitStatus, truncated)
            }
        }
    }

    override fun openLoopbackForward(remotePort: Int): SshForward {
        require(remotePort in 1..65535) { "Remote port is outside 1..65535." }
        val listener = bindLoopbackListener()
        val parameters = Parameters(LOOPBACK, listener.localPort, LOOPBACK, remotePort)
        val forwarder = client.newLocalPortForwarder(parameters, listener)
        val handle = SshjForward(listener, forwarder) { closed ->
            synchronized(forwards) { forwards.remove(closed) }
        }
        synchronized(forwards) { forwards += handle }
        handle.start()
        return handle
    }

    override fun enableKeepAlive(intervalSeconds: Int) {
        require(intervalSeconds > 0)
        client.connection.keepAlive.keepAliveInterval = intervalSeconds
    }

    override fun close() {
        val active = synchronized(forwards) { forwards.toList().also { forwards.clear() } }
        active.forEach { runCatching { it.close() } }
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

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAX_EXEC_BYTES = 256 * 1024
    }
}

/** Bind first and hand this exact listener to sshj; there is no pick-then-bind gap. */
internal fun bindLoopbackListener(): ServerSocket = ServerSocket().apply {
    reuseAddress = false
    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
}

private class SshjForward(
    private val listener: ServerSocket,
    private val forwarder: net.schmizz.sshj.connection.channel.direct.LocalPortForwarder,
    private val onClosed: (SshForward) -> Unit,
) : SshForward {
    @Volatile
    private var open = true

    override val localPort: Int = listener.localPort
    override val bindAddress: String = listener.inetAddress.hostAddress ?: ""
    override val isOpen: Boolean get() = open && !listener.isClosed

    private val thread = Thread({
        try {
            forwarder.listen()
        } finally {
            close()
        }
    }, "hermes-ssh-forward-$localPort").apply { isDaemon = true }

    fun start() = thread.start()

    override fun close() {
        if (!open) return
        open = false
        runCatching { forwarder.close() }
        runCatching { listener.close() }
        onClosed(this)
    }
}
