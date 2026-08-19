package com.hermesagent.mobile.data.ssh

import kotlinx.coroutines.delay

/**
 * The deterministic double behind [SshProbe].
 *
 * It exists so the onboarding journey — first use, accept, retry, success, and
 * the changed-key hard stop — is exercised by tests and by Compose previews
 * without a server, credentials, or a network. It fabricates the *fingerprints*
 * only; the policy it runs them through is the same [evaluateHostKey] the real
 * adapter uses, so a test that passes here is testing real policy.
 */
class FakeSshProbe(
    /** Fingerprint this fake host presents. */
    private val presentedFingerprint: String = DEFAULT_FINGERPRINT,
    private val keyType: String = "ssh-ed25519",
    /** Auth outcome once the host key is trusted. */
    private val authSucceeds: Boolean = true,
    private val delayMillis: Long = 250,
) : SshProbe {

    /** Profiles this fake was asked about, for assertions. */
    val calls = mutableListOf<HostProfile>()

    override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult {
        calls += profile
        delay(delayMillis)

        return when (val verdict = evaluateHostKey(profile.acceptedFingerprint, presentedFingerprint)) {
            is HostKeyVerdict.FirstUse -> ProbeResult.HostKeyPending(verdict.presented, keyType)
            is HostKeyVerdict.Changed -> ProbeResult.HostKeyMismatch(verdict.expected, verdict.presented)
            HostKeyVerdict.Trusted -> if (authSucceeds) {
                ProbeResult.Ok(
                    output = SshProbe.EXPECTED_OUTPUT,
                    serverVersion = "SSH-2.0-OpenSSH_9.6",
                    elapsedMillis = delayMillis,
                )
            } else {
                ProbeResult.Failed(
                    ProbeFailure.AuthFailed,
                    "The host refused these credentials. Nothing was stored.",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_FINGERPRINT: String = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01"
    }
}
