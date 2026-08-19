package com.hermesagent.mobile.data.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * The real probe, over sshj 0.40.0.
 *
 * Chosen per the spike (`docs/spikes/native-kotlin-ssh-client-scope.md` §7.2):
 * sshj is the only candidate that satisfied all ten traced capabilities from
 * primary sources, and its pluggable `HostKeyVerifier` maps one-to-one onto
 * the TOFU design. Apache MINA SSHD was the co-finalist; nothing encountered
 * while building this slice argued for switching, and the swap stays cheap
 * because [SshProbe] is one method wide.
 *
 * Everything that can reach a screen goes through [redact]. Nothing here
 * writes to a log: the app ships `slf4j-nop`, so sshj's own logging is
 * compiled in but discarded, which is the cheapest guarantee that a banner or
 * a host name never lands in logcat.
 *
 * ## Stopping means stopping
 *
 * sshj's exchange is a blocking call on a plain socket. Wrapping it in
 * `withTimeout` alone stops the *coroutine* and leaves the *connection* — the
 * screen says "cancelled" while the transport keeps going and can still
 * authenticate. So the blocking half runs in its own child coroutine and the
 * awaiting half closes the transport the instant it is cancelled or the
 * deadline fires; closing the socket is what makes the blocked call return.
 * The probe re-checks before authenticating and before running the command, so
 * a cancellation that lands mid-handshake cannot be followed by a credential.
 *
 * Crypto provider bring-up is part of that blocking half, not a prelude to it.
 * A cold [SshSecurityProvider.ensureReady] loads BouncyCastle, copies its
 * algorithm table and completes a live X25519 agreement; run before the
 * `withTimeout`/`async` pair it would sit on whatever thread called `probe` —
 * the main thread, under `CoroutineStart.UNDISPATCHED` — with no deadline and
 * no cancel path, and outside the elapsed time the screen prints.
 */
class SshjProbe internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val transports: SshTransports,
    private val overallTimeoutMillis: Long,
    /**
     * Crypto provider bring-up. A parameter only so a test can say where it ran
     * and hold it past the deadline; production always gets the real one.
     */
    private val ensureCrypto: () -> CryptoProviderStatus = SshSecurityProvider::ensureReady,
) : SshProbe {

    constructor(dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(dispatcher, SshjTransports, SshProbe.OVERALL_TIMEOUT_MILLIS)

    override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult {
        val handle = TransportHandle(transports)
        // Taken before provider bring-up, so the duration the screen prints is
        // the whole probe and the deadline below bounds the same span.
        val startedAt = System.nanoTime()
        try {
            return withTimeout(overallTimeoutMillis) {
                val exchange = async(dispatcher) {
                    // Provider first, on this dispatcher and inside the
                    // deadline: `DefaultConfig` decides which ciphers it can
                    // offer while it is being constructed, so a provider
                    // installed afterwards is one that arrived too late.
                    val crypto = runInterruptible { ensureCrypto() }
                    // A deadline/cancel that landed during non-cooperative
                    // provider code must never be followed by a transport.
                    coroutineContext.ensureActive()
                    when (crypto) {
                        is CryptoProviderStatus.Unavailable ->
                            ProbeResult.Failed(ProbeFailure.CryptoUnavailable, redact(crypto.reason))

                        is CryptoProviderStatus.Ready -> runProbe(handle, profile, credential, startedAt)
                    }
                }
                try {
                    exchange.await().also { ensureActive() }
                } catch (stopped: Throwable) {
                    // Cancellation and the deadline both arrive here while the
                    // exchange is still blocked on a worker thread. Closing the
                    // transport from this thread is what unblocks it.
                    handle.close()
                    throw stopped
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            return timedOut()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ProbeResult.Failed(ProbeFailure.Unknown, redact(failure.message))
        } finally {
            handle.close()
        }
    }

    private suspend fun runProbe(
        handle: TransportHandle,
        profile: HostProfile,
        credential: SshCredential,
        startedAt: Long,
    ): ProbeResult {
        val transport = handle.open(profile.acceptedFingerprint)
        try {
            // Factory/open calls are blocking. Cancellation marks this worker's
            // Job immediately even when it cannot interrupt the call, so every
            // return boundary checks the Job before another wire action starts.
            coroutineContext.ensureActive()
            if (remainingMillis(startedAt) <= 0) return timedOut()
            handle.call(transport) { it.connect(profile.host, profile.port) }
            coroutineContext.ensureActive()

            when (val decision = transport.hostKeyVerdict) {
                is HostKeyVerdict.FirstUse ->
                    return ProbeResult.HostKeyPending(decision.presented, transport.hostKeyType)

                is HostKeyVerdict.Changed ->
                    return ProbeResult.HostKeyMismatch(decision.expected, decision.presented)

                else -> Unit
            }

            // The credential must not reach the wire after the attempt is over.
            if (remainingMillis(startedAt) <= 0) return timedOut()
            handle.call(transport) { it.authenticate(profile, credential) }
            coroutineContext.ensureActive()

            val remaining = remainingMillis(startedAt)
            if (remaining <= 0) return timedOut()

            val outcome = handle.call(transport) {
                it.runCommand(SshProbe.COMMAND, SshProbe.MAX_OUTPUT_BYTES, remaining)
            }
            coroutineContext.ensureActive()
            return classify(outcome, transport.serverVersion, startedAt)
        } catch (transportFailure: TransportException) {
            // sshj reports a refused verifier as a transport failure; the
            // verdict the verifier already recorded is the truthful answer.
            return when (val decision = transport.hostKeyVerdict) {
                is HostKeyVerdict.FirstUse -> ProbeResult.HostKeyPending(decision.presented, transport.hostKeyType)
                is HostKeyVerdict.Changed -> ProbeResult.HostKeyMismatch(decision.expected, decision.presented)
                else -> ProbeResult.Failed(ProbeFailure.Unreachable, redact(transportFailure.message))
            }
        } catch (auth: UserAuthException) {
            return when (profile.authMethod.sshAuthType) {
                SshAuthType.None -> ProbeResult.Failed(
                    ProbeFailure.TailscaleSshRefused,
                    SshProbe.TAILSCALE_SSH_REFUSED,
                )

                else -> ProbeResult.Failed(
                    ProbeFailure.AuthFailed,
                    "The host refused these credentials. Nothing was stored.",
                )
            }
        } catch (timeout: SocketTimeoutException) {
            return timedOut()
        } catch (unknown: UnknownHostException) {
            return ProbeResult.Failed(ProbeFailure.Unreachable, "That host name did not resolve.")
        } catch (io: IOException) {
            return ProbeResult.Failed(ProbeFailure.Unreachable, redact(io.message))
        } finally {
            handle.close()
        }
    }

    /**
     * What makes a probe a success.
     *
     * All three, or it failed: the exact sentinel the command prints, an exit
     * status of zero, and a finish inside the deadline. A restricted account, a
     * broken login shell or a host that answers with something else is a
     * failure with copy of its own — never a green tick. The unexpected output
     * itself is deliberately not echoed: it is arbitrary remote bytes.
     */
    private fun classify(outcome: CommandOutcome, serverVersion: String, startedAt: Long): ProbeResult {
        val elapsed = elapsedMillis(startedAt)
        return when {
            elapsed >= overallTimeoutMillis -> timedOut()

            outcome.exitStatus == null -> ProbeResult.Failed(
                ProbeFailure.BadCommandResult,
                "The host ran the probe command but never said whether it succeeded, so the " +
                    "connection is not proven. Nothing was stored.",
            )

            outcome.exitStatus != 0 -> ProbeResult.Failed(
                ProbeFailure.BadCommandResult,
                "The probe command exited with status ${outcome.exitStatus}. The account may have a " +
                    "restricted or non-POSIX login shell.",
            )

            outcome.output != SshProbe.EXPECTED_OUTPUT -> ProbeResult.Failed(
                ProbeFailure.BadCommandResult,
                "The host answered, but not with what `${SshProbe.COMMAND}` prints. Something " +
                    "between the shell and this app is rewriting the output.",
            )

            else -> ProbeResult.Ok(
                output = redact(outcome.output),
                serverVersion = redact(serverVersion),
                elapsedMillis = elapsed,
            )
        }
    }

    private fun timedOut() = ProbeResult.Failed(ProbeFailure.Timeout, "The host did not answer in time.")

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun remainingMillis(startedAt: Long): Long = overallTimeoutMillis - elapsedMillis(startedAt)
}

/**
 * Owns the one transport a probe opens, so any thread can close it.
 *
 * Adoption is guarded rather than assumed: a cancellation that lands between
 * opening the connection and publishing it here would otherwise leave a live
 * socket nobody holds. If that happens the transport is closed immediately and
 * the probe stops before it can connect to anything.
 */
private class TransportHandle(private val transports: SshTransports) {

    private val lock = Any()
    private var transport: SshTransport? = null
    private var operation: OperationTicket? = null
    private var stopped = false

    fun open(storedFingerprint: String?): SshTransport {
        val opened = transports.open(storedFingerprint)
        val adopted = synchronized(lock) {
            if (stopped) false else { transport = opened; true }
        }
        if (!adopted) {
            runCatching { opened.close() }
            throw CancellationException("The probe stopped before it opened a connection.")
        }
        return opened
    }

    /**
     * Starts one side effect only if cancellation has not already won.
     *
     * The admission is the operation's linearization point. Once admitted, a
     * concurrent close owns the transport and unblocks the operation; if close
     * gets there first, the operation is never invoked. Keeping the actual I/O
     * outside the lock matters: `close` has to run while sshj is blocked.
     */
    fun <T> call(expected: SshTransport, action: (SshTransport) -> T): T {
        val ticket = synchronized(lock) {
            if (stopped || transport !== expected) {
                throw CancellationException("The probe stopped before it could continue.")
            }
            OperationTicket().also { operation = it }
        }
        try {
            ticket.start()
            return action(expected)
        } finally {
            synchronized(lock) {
                if (operation === ticket) operation = null
            }
        }
    }

    /** Idempotent, and safe to call from a thread that is not the worker's. */
    fun close() {
        val closing = synchronized(lock) {
            stopped = true
            operation?.cancel()
            operation = null
            transport.also { transport = null }
        }
        closing?.let { runCatching { it.close() } }
    }

    private class OperationTicket {
        private val lock = Any()
        private var cancelled = false

        fun start() {
            synchronized(lock) {
                if (cancelled) throw CancellationException("The probe stopped before it could continue.")
            }
        }

        fun cancel() {
            synchronized(lock) { cancelled = true }
        }
    }
}
