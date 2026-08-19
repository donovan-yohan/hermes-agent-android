package com.hermesagent.mobile.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds known secrets through the redactor. This is the gate the scope doc
 * calls G2 (`docs/spikes/native-kotlin-ssh-client-scope.md` §7.3): the
 * guarantee is a test, not a convention.
 */
class SecretRedactionTest {

    private val secrets = listOf(
        "hunter2correcthorse",
        "sk-live-9f2b7c1d4e6a8b0c",
        "MIIEpAIBAAKCAQEA0Z3VS5JJcds3xfNn54LjHW",
    )

    @Test
    fun `a gateway session token never survives`() {
        val redacted = redact("env HERMES_DASHBOARD_SESSION_TOKEN=${secrets[1]} hermes serve")
        assertEquals("env HERMES_DASHBOARD_SESSION_TOKEN=<redacted> hermes serve", redacted)
    }

    @Test
    fun `session-token headers and bearer tokens are stripped`() {
        assertTrue(redact("X-Hermes-Session-Token: ${secrets[1]}").endsWith("<redacted>"))
        assertTrue(redact("""{"x-hermes-session-token":"${secrets[1]}"}""").contains("<redacted>"))
        assertTrue(redact("Authorization: Bearer ${secrets[1]}").endsWith("<redacted>"))
    }

    @Test
    fun `token and ticket query parameters are stripped`() {
        assertEquals(
            "ws://127.0.0.1:8731/api/ws?token=<redacted>",
            redact("ws://127.0.0.1:8731/api/ws?token=${secrets[1]}"),
        )
        assertTrue(redact("https://h/api/ws?a=1&ticket=${secrets[1]}").contains("ticket=<redacted>"))
    }

    @Test
    fun `a password typed into the host field does not survive`() {
        // Desktop's last rule (ssh-connection.ts:139-141): a non-numeric segment
        // where a port belongs is almost always a mistyped secret.
        assertEquals("hermes@box:<redacted>", redact("hermes@box:${secrets[0]}"))
        assertEquals("hermes@box:22", redact("hermes@box:22"))
    }

    @Test
    fun `a pasted private key collapses to a marker`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n${secrets[2]}\nmore\n-----END OPENSSH PRIVATE KEY-----"
        val redacted = redact("failed to load key:\n$pem")

        assertFalse(redacted.contains(secrets[2]))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test
    fun `a labelled password is stripped`() {
        assertTrue(redact("""password="${secrets[0]}"""").contains("<redacted>"))
        assertTrue(redact("password: ${secrets[0]}").contains("<redacted>"))
    }

    @Test
    fun `no known secret survives any of the shapes we emit`() {
        val carriers = listOf(
            "HERMES_DASHBOARD_SESSION_TOKEN=%s",
            "Authorization: Bearer %s",
            "X-Hermes-Session-Token: %s",
            "ws://h/api/ws?token=%s",
            "user@host:%s",
            "password=%s",
        )

        for (secret in secrets) {
            for (carrier in carriers) {
                val redacted = redact(carrier.format(secret))
                assertFalse("`$carrier` leaked `$secret` as: $redacted", redacted.contains(secret))
            }
        }
    }

    @Test
    fun `harmless text is left alone`() {
        assertEquals("HERMES_ANDROID_SSH_OK", redact("HERMES_ANDROID_SSH_OK"))
        assertEquals("SSH-2.0-OpenSSH_9.6", redact("SSH-2.0-OpenSSH_9.6"))
        assertEquals("", redact(null))
    }

    @Test
    fun `the credential holder cannot print its own contents`() {
        val credential = SshCredential.password(secrets[0])
        assertFalse(credential.toString().contains(secrets[0]))
        assertEquals("SshCredential(redacted)", credential.toString())
    }

    @Test
    fun `clearing a credential zeroes the buffers`() {
        val credential = SshCredential.privateKey("-----BEGIN OPENSSH PRIVATE KEY-----", "pass")
        val key = requireNotNull(credential.privateKey)
        val passphrase = requireNotNull(credential.passphrase)
        assertTrue("precondition: the material is really there", key.any { it != NUL })

        credential.clear()

        assertTrue(key.all { it == NUL })
        assertTrue(passphrase.all { it == NUL })
    }

    private companion object {
        const val NUL = '\u0000'
    }
}
