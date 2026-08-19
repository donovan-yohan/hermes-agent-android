package com.hermesagent.mobile.data.ssh

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
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

/** Result of opening an authenticated SSH connection for the gateway. */
internal sealed interface SshOpenResult {
    data class Connected(val transport: SshTransport, val serverVersion: String) : SshOpenResult
    data class HostKeyPending(val fingerprint: String, val keyType: String) : SshOpenResult
    data class HostKeyMismatch(val expected: String, val actual: String) : SshOpenResult
    data class Failed(val kind: ProbeFailure, val message: String) : SshOpenResult
}

/**
 * Opens the same verified/authenticated sshj transport the probe uses, then
 * transfers ownership instead of closing it after one command.
 *
 * The credential copy is cleared immediately after authentication returns.
 * Cancellation or any non-connected result closes the transport.
 */
internal class SshSessionOpener(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transports: SshTransports = SshjTransports,
    private val timeoutMillis: Long = SshProbe.OVERALL_TIMEOUT_MILLIS,
    private val ensureCrypto: () -> CryptoProviderStatus = SshSecurityProvider::ensureReady,
) {
    suspend fun open(profile: HostProfile, credential: SshCredential): SshOpenResult {
        val handle = TransportHandle(transports)
        try {
            return withTimeout(timeoutMillis) {
                val work = async(dispatcher) {
                    when (val crypto = runInterruptible { ensureCrypto() }) {
                        is CryptoProviderStatus.Unavailable -> {
                            credential.clear()
                            SshOpenResult.Failed(ProbeFailure.CryptoUnavailable, redact(crypto.reason))
                        }

                        is CryptoProviderStatus.Ready -> openVerified(handle, profile, credential)
                    }
                }
                try {
                    work.await().also { ensureActive() }
                } catch (stopped: Throwable) {
                    handle.close()
                    credential.clear()
                    throw stopped
                }
            }
        } catch (_: TimeoutCancellationException) {
            credential.clear()
            return SshOpenResult.Failed(ProbeFailure.Timeout, "The host did not answer in time.")
        } catch (cancelled: CancellationException) {
            credential.clear()
            throw cancelled
        } catch (failure: Exception) {
            credential.clear()
            return SshOpenResult.Failed(ProbeFailure.Unknown, safeSshMessage(failure))
        } finally {
            handle.close()
        }
    }

    private suspend fun openVerified(
        handle: TransportHandle,
        profile: HostProfile,
        credential: SshCredential,
    ): SshOpenResult {
        val transport = handle.open(profile.acceptedFingerprint)
        try {
            coroutineContext.ensureActive()
            handle.call(transport) { it.connect(profile.host, profile.port) }
            coroutineContext.ensureActive()

            when (val verdict = transport.hostKeyVerdict) {
                is HostKeyVerdict.FirstUse ->
                    return SshOpenResult.HostKeyPending(verdict.presented, transport.hostKeyType)

                is HostKeyVerdict.Changed ->
                    return SshOpenResult.HostKeyMismatch(verdict.expected, verdict.presented)

                HostKeyVerdict.Trusted -> Unit
                null -> return SshOpenResult.Failed(ProbeFailure.Unreachable, "The host did not present a key.")
            }

            try {
                handle.call(transport) { it.authenticate(profile, credential) }
            } finally {
                credential.clear()
            }
            coroutineContext.ensureActive()
            transport.enableKeepAlive(KEEPALIVE_SECONDS)
            return SshOpenResult.Connected(handle.release(transport), redact(transport.serverVersion))
        } catch (failure: TransportException) {
            return when (val verdict = transport.hostKeyVerdict) {
                is HostKeyVerdict.FirstUse -> SshOpenResult.HostKeyPending(verdict.presented, transport.hostKeyType)
                is HostKeyVerdict.Changed -> SshOpenResult.HostKeyMismatch(verdict.expected, verdict.presented)
                else -> SshOpenResult.Failed(ProbeFailure.Unreachable, safeSshMessage(failure))
            }
        } catch (_: UserAuthException) {
            val kind = if (profile.authMethod.sshAuthType == SshAuthType.None) {
                ProbeFailure.TailscaleSshRefused
            } else {
                ProbeFailure.AuthFailed
            }
            val message = if (kind == ProbeFailure.TailscaleSshRefused) {
                "This host refused Tailscale SSH. Check that Tailscale SSH is enabled and allowed, or choose another method."
            } else {
                "The host refused these credentials. Check the selected method and try again."
            }
            return SshOpenResult.Failed(kind, message)
        } catch (_: SocketTimeoutException) {
            return SshOpenResult.Failed(ProbeFailure.Timeout, "The host did not answer in time.")
        } catch (_: UnknownHostException) {
            return SshOpenResult.Failed(ProbeFailure.Unreachable, "That host name did not resolve. Check the destination.")
        } catch (failure: IOException) {
            return SshOpenResult.Failed(ProbeFailure.Unreachable, safeSshMessage(failure))
        } finally {
            credential.clear()
        }
    }

    private fun safeSshMessage(failure: Throwable): String = when (failure) {
        is UnknownHostException -> "That host name did not resolve. Check the destination."
        else -> "The SSH connection failed. Check the destination and try again."
    }

    private companion object {
        const val KEEPALIVE_SECONDS = 30
    }
}
