package com.hermesagent.mobile.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a destination edit is allowed to do to a saved profile, and what a
 * saved profile is allowed to contain.
 */
class HostProfileTest {

    private val trusted = HostProfile(
        host = "hermes-box",
        port = 22,
        username = "hermes",
        acceptedFingerprint = "SHA256:0pXQ0M2fEXAMPLEfingerprintDEMOonlyNOTreal01",
    )

    @Test
    fun `renaming only the user keeps the fingerprint accepted for this box`() {
        val renamed = trusted.withDestination(SshDestination("test-user", "hermes-box", 22))

        assertEquals("test-user", renamed.username)
        assertEquals(
            "trust is scoped to the host key, not to the account",
            trusted.acceptedFingerprint,
            renamed.acceptedFingerprint,
        )
    }

    @Test
    fun `changing the host drops the fingerprint`() {
        val moved = trusted.withDestination(SshDestination("hermes", "other-box", 22))

        assertEquals("other-box", moved.host)
        assertNull("a different host is a different key, so the next probe is a first use", moved.acceptedFingerprint)
    }

    @Test
    fun `changing the port drops the fingerprint`() {
        // A different port can be a different sshd, or a forward to somewhere
        // else entirely. Re-review rather than assume.
        val moved = trusted.withDestination(SshDestination("hermes", "hermes-box", 2222))

        assertEquals(2222, moved.port)
        assertNull(moved.acceptedFingerprint)
    }

    @Test
    fun `an unchanged destination is a no-op on trust`() {
        assertEquals(trusted, trusted.withDestination(SshDestination("hermes", "hermes-box", 22)))
    }

    @Test
    fun `the profile renders the destination it was parsed from`() {
        assertEquals("hermes@hermes-box", trusted.destination)
        assertEquals("hermes@hermes-box:2222", trusted.copy(port = 2222).destination)
        assertEquals("", HostProfile().destination)
    }

    @Test
    fun `a fresh profile starts on Tailscale SSH and is not yet probeable`() {
        val fresh = HostProfile()

        assertEquals(AuthMethod.TailscaleSsh, fresh.authMethod)
        assertEquals(SshDestination.DEFAULT_PORT, fresh.port)
        assertFalse("an empty profile has nowhere to dial", fresh.isValid)
    }

    @Test
    fun `validity needs a host, a user and a port in range`() {
        assertTrue(trusted.isValid)
        assertFalse(trusted.copy(host = "").isValid)
        assertFalse(trusted.copy(host = "hermes box").isValid)
        assertFalse(trusted.copy(username = "").isValid)
        assertFalse(trusted.copy(port = 0).isValid)
        assertFalse(trusted.copy(port = 65536).isValid)
    }

    @Test
    fun `the persisted names of the existing auth methods are unchanged`() {
        // These strings are on disk in every install that has used this app;
        // renaming an entry would silently reset someone's choice.
        assertEquals(AuthMethod.Password, AuthMethod.valueOf("Password"))
        assertEquals(AuthMethod.PrivateKey, AuthMethod.valueOf("PrivateKey"))
        assertEquals(AuthMethod.TailscaleSsh, AuthMethod.valueOf("TailscaleSsh"))
    }

    @Test
    fun `exactly one method sends SSH auth type none`() {
        val keyless = AuthMethod.entries.filter { it.sshAuthType == SshAuthType.None }

        assertEquals(listOf(AuthMethod.TailscaleSsh), keyless)
        assertEquals(SshAuthType.Password, AuthMethod.Password.sshAuthType)
        assertEquals(SshAuthType.PublicKey, AuthMethod.PrivateKey.sshAuthType)
    }

    @Test
    fun `the Tailscale SSH credential carries nothing`() {
        assertFalse(SshCredential.none().carriesSecret)
        assertTrue(SshCredential.password("s3cret").carriesSecret)
        assertTrue(SshCredential.privateKey("-----BEGIN OPENSSH PRIVATE KEY-----".toCharArray(), null).carriesSecret)
    }
}
