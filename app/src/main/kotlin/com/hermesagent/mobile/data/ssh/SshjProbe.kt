package com.hermesagent.mobile.data.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.transport.TransportException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
 */
class SshjProbe(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshProbe {

    override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult =
        withContext(dispatcher) {
            try {
                withTimeout(SshProbe.OVERALL_TIMEOUT_MILLIS) { runProbe(profile, credential) }
            } catch (timeout: TimeoutCancellationException) {
                ProbeResult.Failed(ProbeFailure.Timeout, "The host did not answer in time.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }

    private fun runProbe(profile: HostProfile, credential: SshCredential): ProbeResult {
        val verifier = TofuHostKeyVerifier(profile.acceptedFingerprint)
        val client = SSHClient(DefaultConfig())
        client.addHostKeyVerifier(verifier)
        client.connectTimeout = SshProbe.CONNECT_TIMEOUT_MILLIS
        client.timeout = SshProbe.CONNECT_TIMEOUT_MILLIS

        val startedAt = System.nanoTime()
        try {
            client.connect(profile.host, profile.port)

            when (val decision = verifier.verdict) {
                is HostKeyVerdict.FirstUse ->
                    return ProbeResult.HostKeyPending(decision.presented, verifier.keyType)

                is HostKeyVerdict.Changed ->
                    return ProbeResult.HostKeyMismatch(decision.expected, decision.presented)

                else -> Unit
            }

            authenticate(client, profile, credential)

            val output = client.startSession().use { session ->
                session.exec(SshProbe.COMMAND).use { command ->
                    val bytes = command.inputStream.readBounded(SshProbe.MAX_OUTPUT_BYTES)
                    command.join()
                    String(bytes, Charsets.UTF_8).trim()
                }
            }

            return ProbeResult.Ok(
                output = redact(output),
                serverVersion = redact(client.transport.serverVersion.orEmpty()),
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
            )
        } catch (transport: TransportException) {
            // sshj reports a refused verifier as a transport failure; the
            // verdict the verifier already recorded is the truthful answer.
            return when (val decision = verifier.verdict) {
                is HostKeyVerdict.FirstUse -> ProbeResult.HostKeyPending(decision.presented, verifier.keyType)
                is HostKeyVerdict.Changed -> ProbeResult.HostKeyMismatch(decision.expected, decision.presented)
                else -> ProbeResult.Failed(ProbeFailure.Unreachable, redact(transport.message))
            }
        } catch (auth: UserAuthException) {
            return ProbeResult.Failed(
                ProbeFailure.AuthFailed,
                "The host refused these credentials. Nothing was stored.",
            )
        } catch (timeout: SocketTimeoutException) {
            return ProbeResult.Failed(ProbeFailure.Timeout, "The host did not answer in time.")
        } catch (unknown: UnknownHostException) {
            return ProbeResult.Failed(ProbeFailure.Unreachable, "That host name did not resolve.")
        } catch (io: IOException) {
            return ProbeResult.Failed(ProbeFailure.Unreachable, redact(io.message))
        } finally {
            runCatching { client.disconnect() }
            runCatching { client.close() }
        }
    }

    private fun authenticate(client: SSHClient, profile: HostProfile, credential: SshCredential) {
        when (profile.authMethod) {
            AuthMethod.Password -> {
                val password = credential.password
                    ?: throw UserAuthException("No password supplied.")
                client.authPassword(profile.username, password)
            }

            AuthMethod.PrivateKey -> {
                val pem = credential.privateKey
                    ?: throw UserAuthException("No private key supplied.")
                val finder = credential.passphrase?.let { PasswordUtils.createOneOff(it) }
                val keys = client.loadKeys(String(pem), null, finder)
                client.authPublickey(profile.username, keys)
            }
        }
    }
}
