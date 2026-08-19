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
    /**
     * Whether this fake host runs Tailscale SSH. False models the common case:
     * a box on the tailnet running ordinary OpenSSH, which answers auth type
     * `none` with a refusal.
     */
    private val tailscaleSshEnabled: Boolean = true,
    private val delayMillis: Long = 250,
) : SshProbe {

    /** What this fake was asked to do, for assertions. */
    val calls = mutableListOf<ProbeCall>()

    override suspend fun probe(profile: HostProfile, credential: SshCredential): ProbeResult {
        calls += ProbeCall(profile, credential.carriesSecret)
        delay(delayMillis)

        return when (val verdict = evaluateHostKey(profile.acceptedFingerprint, presentedFingerprint)) {
            is HostKeyVerdict.FirstUse -> ProbeResult.HostKeyPending(verdict.presented, keyType)
            is HostKeyVerdict.Changed -> ProbeResult.HostKeyMismatch(verdict.expected, verdict.presented)
            HostKeyVerdict.Trusted -> authenticate(profile)
        }
    }

    private fun authenticate(profile: HostProfile): ProbeResult = when {
        profile.authMethod.sshAuthType == SshAuthType.None && !tailscaleSshEnabled ->
            ProbeResult.Failed(ProbeFailure.TailscaleSshRefused, SshProbe.TAILSCALE_SSH_REFUSED)

        !authSucceeds -> ProbeResult.Failed(
            ProbeFailure.AuthFailed,
            "The host refused these credentials. Nothing was stored.",
        )

        else -> ProbeResult.Ok(
            output = SshProbe.EXPECTED_OUTPUT,
            serverVersion = "SSH-2.0-OpenSSH_9.6",
            elapsedMillis = delayMillis,
        )
    }

    /**
     * One recorded probe. [carriedSecret] is a boolean, never the material: it
     * is how a test proves the Tailscale SSH path sends nothing.
     */
    data class ProbeCall(val profile: HostProfile, val carriedSecret: Boolean)

    companion object {
        const val DEFAULT_FINGERPRINT: String = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01"
    }
}
