package com.hermesagent.mobile.data.ssh

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSessionOpenerTest {
    private val trustedFingerprint = "SHA256:test-fixture-only"
    private val profile = HostProfile(
        host = "gateway.example.invalid",
        username = "fixture-user",
        authMethod = AuthMethod.Password,
        acceptedFingerprint = trustedFingerprint,
    )

    @Test
    fun `authenticated transport transfers ownership and credential clears immediately`() = runBlocking {
        val transport = OpeningTransport(HostKeyVerdict.Trusted)
        val credential = SshCredential.password("test-only-password")
        val opener = SshSessionOpener(Dispatchers.Unconfined, { transport }, ensureCrypto = READY)

        val result = opener.open(profile, credential)

        assertTrue(result is SshOpenResult.Connected)
        assertEquals("test-only-password", transport.passwordSeen)
        assertTrue(credential.password!!.all { it == '\u0000' })
        assertEquals(30, transport.keepAliveSeconds)
        assertFalse("the connected transport belongs to the caller", transport.closed)
        (result as SshOpenResult.Connected).transport.close()
        assertTrue(transport.closed)
    }

    @Test
    fun `first use and changed key abort before auth and close`() = runBlocking {
        val firstUse = OpeningTransport(HostKeyVerdict.FirstUse("SHA256:first"))
        val pending = SshSessionOpener(Dispatchers.Unconfined, { firstUse }, ensureCrypto = READY)
            .open(profile.copy(acceptedFingerprint = null), SshCredential.password("unused"))
        assertTrue(pending is SshOpenResult.HostKeyPending)
        assertFalse(firstUse.authenticated)
        assertTrue(firstUse.closed)

        val changed = OpeningTransport(HostKeyVerdict.Changed(trustedFingerprint, "SHA256:changed"))
        val mismatch = SshSessionOpener(Dispatchers.Unconfined, { changed }, ensureCrypto = READY)
            .open(profile, SshCredential.password("unused"))
        assertTrue(mismatch is SshOpenResult.HostKeyMismatch)
        assertFalse(changed.authenticated)
        assertTrue(changed.closed)
    }

    @Test
    fun `cancelling a blocked open closes transport and clears credential`() = runBlocking {
        val transport = OpeningTransport(HostKeyVerdict.Trusted, blockConnect = true)
        val credential = SshCredential.password("test-only-password")
        val opener = SshSessionOpener(Dispatchers.IO, { transport }, ensureCrypto = READY)
        val job = launch(Dispatchers.Default) { opener.open(profile, credential) }
        assertTrue(transport.reachedConnect.await(5, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertTrue(transport.closed)
        assertFalse(transport.authenticated)
        assertTrue(credential.password!!.all { it == '\u0000' })
    }

    @Test
    fun `forward listener binds and holds an actual loopback port`() {
        bindLoopbackListener().use { listener ->
            assertTrue(listener.isBound)
            assertEquals("127.0.0.1", listener.inetAddress.hostAddress)
            assertTrue(listener.localPort in 1..65535)
        }
    }

    private class OpeningTransport(
        override val hostKeyVerdict: HostKeyVerdict?,
        private val blockConnect: Boolean = false,
    ) : SshTransport {
        val reachedConnect = CountDownLatch(1)
        @Volatile var closed = false
        @Volatile var authenticated = false
        var passwordSeen: String? = null
        var keepAliveSeconds: Int? = null

        override val hostKeyType = "ssh-ed25519"
        override val serverVersion = "SSH-2.0-test"

        override fun connect(host: String, port: Int) {
            reachedConnect.countDown()
            if (blockConnect) {
                while (!closed) Thread.onSpinWait()
                throw java.io.IOException("closed")
            }
        }

        override fun authenticate(profile: HostProfile, credential: SshCredential) {
            passwordSeen = credential.password?.concatToString()
            authenticated = true
        }

        override fun runCommand(command: String, maxBytes: Int, timeoutMillis: Long) =
            CommandOutcome("", 0)

        override fun enableKeepAlive(intervalSeconds: Int) {
            keepAliveSeconds = intervalSeconds
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val READY: () -> CryptoProviderStatus = { CryptoProviderStatus.Ready("test-provider") }
    }
}
