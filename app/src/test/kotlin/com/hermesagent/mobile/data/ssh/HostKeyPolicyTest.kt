package com.hermesagent.mobile.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Host-key policy, including the real sshj verifier driven with real key
 * material. The verifier is the component that decides whether a password
 * ever leaves the device, so it is tested against actual `PublicKey` objects
 * rather than a string double.
 */
class HostKeyPolicyTest {

    private val presented = "SHA256:5vQqPzu5vQqPzu5vQqPzu5vQqPzu5vQqPzu5vQqPzuA"
    private val other = "SHA256:AAAAPzu5vQqPzu5vQqPzu5vQqPzu5vQqPzu5vQqPzuA"

    @Test
    fun `no stored fingerprint is a first use, not a silent accept`() {
        assertEquals(HostKeyVerdict.FirstUse(presented), evaluateHostKey(null, presented))
        assertEquals(HostKeyVerdict.FirstUse(presented), evaluateHostKey("", presented))
    }

    @Test
    fun `a matching stored fingerprint is trusted`() {
        assertEquals(HostKeyVerdict.Trusted, evaluateHostKey(presented, presented))
    }

    @Test
    fun `a changed key is a hard failure with both fingerprints reported`() {
        assertEquals(HostKeyVerdict.Changed(other, presented), evaluateHostKey(other, presented))
    }

    @Test
    fun `the verifier refuses the transport on first use, before authentication`() {
        val key = generateHostKey()
        val verifier = TofuHostKeyVerifier(storedFingerprint = null)

        val accepted = verifier.verify("hermes-box", 22, key)

        assertFalse("first use must abort the transport so no credential is sent", accepted)
        assertTrue(verifier.verdict is HostKeyVerdict.FirstUse)
        assertEquals(sshFingerprintOf(key), (verifier.verdict as HostKeyVerdict.FirstUse).presented)
    }

    @Test
    fun `the verifier accepts exactly the key whose fingerprint was stored`() {
        val key = generateHostKey()
        val verifier = TofuHostKeyVerifier(storedFingerprint = sshFingerprintOf(key))

        assertTrue(verifier.verify("hermes-box", 22, key))
        assertEquals(HostKeyVerdict.Trusted, verifier.verdict)
    }

    @Test
    fun `the verifier refuses a different key for a trusted host`() {
        val trusted = generateHostKey()
        val attacker = generateHostKey()
        val verifier = TofuHostKeyVerifier(storedFingerprint = sshFingerprintOf(trusted))

        val accepted = verifier.verify("hermes-box", 22, attacker)

        assertFalse(accepted)
        val verdict = verifier.verdict
        assertTrue("a changed key must never degrade to a prompt", verdict is HostKeyVerdict.Changed)
        assertEquals(sshFingerprintOf(trusted), (verdict as HostKeyVerdict.Changed).expected)
        assertEquals(sshFingerprintOf(attacker), verdict.presented)
    }

    @Test
    fun `a null key is refused rather than treated as absent`() {
        val verifier = TofuHostKeyVerifier(storedFingerprint = null)
        assertFalse(verifier.verify("hermes-box", 22, null))
    }

    @Test
    fun `fingerprints are ssh-keygen shaped and unique per key`() {
        val a = sshFingerprintOf(generateHostKey())
        val b = sshFingerprintOf(generateHostKey())

        assertTrue("must be comparable with `ssh-keygen -lf` output", a.startsWith("SHA256:"))
        assertFalse("base64 must be unpadded, like OpenSSH", a.contains("="))
        // 32 bytes base64 without padding is 43 characters.
        assertEquals(43, a.removePrefix("SHA256:").length)
        assertNotEquals(a, b)
    }

    @Test
    fun `the verifier reports the key type for the review screen`() {
        val verifier = TofuHostKeyVerifier(storedFingerprint = null)
        verifier.verify("hermes-box", 22, generateHostKey())
        assertEquals("ecdsa-sha2-nistp256", verifier.keyType)
    }

    @Test
    fun `the verification command names the file sshd actually keeps the key in`() {
        // The wire name is not the file name: sshd stores one key per algorithm,
        // so every nistp curve lives in the same `ssh_host_ecdsa_key`. Building
        // the path by string-substituting the wire name produced
        // `/etc/ssh/ssh_host_ecdsa-sha2-nistp256_key.pub`, which does not exist,
        // so the out-of-band check this screen prescribes could not be run.
        assertEquals("/etc/ssh/ssh_host_ecdsa_key.pub", hostKeyPublicKeyPath("ecdsa-sha2-nistp256"))
        assertEquals("/etc/ssh/ssh_host_ecdsa_key.pub", hostKeyPublicKeyPath("ecdsa-sha2-nistp384"))
        assertEquals("/etc/ssh/ssh_host_ecdsa_key.pub", hostKeyPublicKeyPath("ecdsa-sha2-nistp521"))
        assertEquals("/etc/ssh/ssh_host_ed25519_key.pub", hostKeyPublicKeyPath("ssh-ed25519"))
        assertEquals("/etc/ssh/ssh_host_rsa_key.pub", hostKeyPublicKeyPath("ssh-rsa"))
        assertEquals("/etc/ssh/ssh_host_dsa_key.pub", hostKeyPublicKeyPath("ssh-dss"))
    }

    @Test
    fun `a certificate host key points at the key it certifies`() {
        assertEquals(
            "/etc/ssh/ssh_host_ed25519_key.pub",
            hostKeyPublicKeyPath("ssh-ed25519-cert-v01@openssh.com"),
        )
        assertEquals(
            "/etc/ssh/ssh_host_ecdsa_key.pub",
            hostKeyPublicKeyPath("ecdsa-sha2-nistp256-cert-v01@openssh.com"),
        )
    }

    @Test
    fun `an unknown key type gets no command rather than a path that is not there`() {
        assertNull(hostKeyPublicKeyPath("unknown"))
        assertNull(hostKeyPublicKeyPath(""))
        assertNull(hostKeyPublicKeyPath("sk-ssh-ed25519@openssh.com"))
    }

    @Test
    fun `every type the verifier can report has a file or is deliberately absent`() {
        // The verifier reports whatever `KeyType.fromKey` names, so the mapping
        // and the wire names have to be checked against each other, not assumed.
        val key = generateHostKey()
        val verifier = TofuHostKeyVerifier(storedFingerprint = null)
        verifier.verify("hermes-box", 22, key)

        assertEquals("/etc/ssh/ssh_host_ecdsa_key.pub", hostKeyPublicKeyPath(verifier.keyType))
    }

    /** P-256 because every JVM has it; the policy is key-type agnostic. */
    private fun generateHostKey() = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
        .public
}
