package com.hermesagent.mobile.data.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.PublicKey

/**
 * The sshj `HostKeyVerifier` that enforces [evaluateHostKey].
 *
 * It returns `false` for anything but an exact match, which makes sshj abort
 * the transport **before** authentication — so a first use or a changed key
 * never sees a password or a signature. The verdict is recorded so the caller
 * can turn "refused" into the right typed [ProbeResult] instead of a generic
 * failure.
 *
 * This class is why there is no accept-all verifier anywhere: sshj needs one
 * verifier, and this is it.
 */
class TofuHostKeyVerifier(private val storedFingerprint: String?) : HostKeyVerifier {

    /** Set by the first [verify] call. Null means the transport never got a key. */
    var verdict: HostKeyVerdict? = null
        private set

    /** Wire name of the offered key, e.g. `ssh-ed25519`. Non-secret. */
    var keyType: String = "unknown"
        private set

    override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
        if (key == null) return false
        keyType = runCatching { KeyType.fromKey(key).toString() }.getOrDefault("unknown")
        val decision = evaluateHostKey(storedFingerprint, sshFingerprintOf(key))
        verdict = decision
        return decision is HostKeyVerdict.Trusted
    }

    override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
}

/**
 * `SHA256:…` over the SSH wire encoding of the public key — byte-for-byte what
 * `ssh-keygen -lf` hashes, so the string on the phone and the string on the
 * server are comparable by eye.
 */
fun sshFingerprintOf(key: PublicKey): String =
    sha256Fingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData)
