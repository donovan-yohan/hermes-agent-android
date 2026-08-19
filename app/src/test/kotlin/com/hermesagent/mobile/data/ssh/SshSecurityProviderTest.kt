package com.hermesagent.mobile.data.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import java.security.Signature
import javax.crypto.KeyAgreement

/**
 * The physical blocker, offline.
 *
 * A Pixel 10 Pro on Android 17 failed the real probe with
 * `no such algorithm: X25519 for provider BC`. These tests stand a stripped
 * provider up under the name `BC` — which is what Android ships — and then
 * assert both halves: that sshj's own lookup produces that exact failure, and
 * that [SshSecurityProvider] fixes it without disturbing the provider it found.
 *
 * The JVM's provider list is process-global, so every test installs what it
 * needs and [tearDown] removes it. What these cannot prove is that *Android's*
 * `BC` is stripped in exactly this way; the device error is that evidence, and
 * the rerun on the Pixel is the confirmation.
 */
class SshSecurityProviderTest {

    private lateinit var originalProviders: List<Provider>
    private var originalSshjProvider: String? = null

    @Before
    fun setUp() {
        // getSecurityProvider initialises sshj once, so capture the state it
        // actually exposes before the test mutates its process-global JCA list.
        originalSshjProvider = SecurityUtils.getSecurityProvider()
        originalProviders = Security.getProviders().toList()
        resetProviderState()
    }

    @After
    fun tearDown() {
        Security.getProviders().forEach { Security.removeProvider(it.name) }
        originalProviders.forEachIndexed { index, provider ->
            Security.insertProviderAt(provider, index + 1)
        }
        SecurityUtils.setSecurityProvider(originalSshjProvider)
        SshSecurityProvider.resetForTest()
    }

    private fun resetProviderState() {
        Security.removeProvider(SshSecurityProvider.PROVIDER_NAME)
        SshSecurityProvider.resetForTest()
        // Un-pins sshj and re-arms its own registration for the next test.
        SecurityUtils.setSecurityProvider(null)
    }

    @Test
    fun `sshj binds to the stale provider by name, which is the device failure`() {
        installStale()

        val failure = assertThrows(NoSuchAlgorithmException::class.java) {
            SecurityUtils.getKeyPairGenerator("X25519")
        }

        assertEquals(
            "sshj records the provider by name, and the name is the stale one",
            "BC",
            SecurityUtils.getSecurityProvider(),
        )
        assertEquals(
            "this is the string the Pixel showed, character for character",
            "no such algorithm: X25519 for provider BC",
            failure.message,
        )
    }

    @Test
    fun `the bundled provider cannot take the name it needs`() {
        installStale()

        val bundled = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
            .getDeclaredConstructor()
            .newInstance() as Provider

        assertEquals(
            "addProvider refuses a name that is taken, which is why sshj's own registration " +
                "silently leaves the stripped provider in charge",
            -1,
            Security.addProvider(bundled),
        )
    }

    @Test
    fun `after the repair every lookup sshj makes resolves`() {
        installStale()

        val status = SshSecurityProvider.ensureReady()

        assertEquals(CryptoProviderStatus.Ready(SshSecurityProvider.PROVIDER_NAME), status)
        assertEquals(SshSecurityProvider.PROVIDER_NAME, SecurityUtils.getSecurityProvider())

        // Exactly the calls Curve25519DH, Ed25519KeyFactory, ECDSAKeyFactory,
        // SignatureRSA and the MAC factories make on a real handshake.
        assertNotNull(SecurityUtils.getKeyPairGenerator("X25519"))
        assertNotNull(SecurityUtils.getKeyAgreement("X25519"))
        assertNotNull(SecurityUtils.getKeyFactory("X25519"))
        assertNotNull(SecurityUtils.getSignature("Ed25519"))
        assertNotNull(SecurityUtils.getKeyFactory("Ed25519"))
        assertNotNull(SecurityUtils.getKeyFactory("ECDSA"))
        assertNotNull(SecurityUtils.getSignature("SHA256withECDSA"))
        assertNotNull(SecurityUtils.getKeyFactory("RSA"))
        assertNotNull(SecurityUtils.getSignature("SHA256withRSA"))
        assertNotNull(SecurityUtils.getMessageDigest("SHA-256"))
        assertNotNull(SecurityUtils.getMAC("HmacSHA256"))
    }

    @Test
    fun `a real curve25519 exchange and an ed25519 signature both complete`() {
        installStale()
        SshSecurityProvider.ensureReady()

        // Resolving a service is not the same as being able to use it, and the
        // device failure was a lookup that only broke once something asked.
        val name = SshSecurityProvider.PROVIDER_NAME
        val generator = KeyPairGenerator.getInstance("X25519", name)
        val local = generator.generateKeyPair()
        val peer = generator.generateKeyPair()

        val ours = KeyAgreement.getInstance("X25519", name).run {
            init(local.private)
            doPhase(peer.public, true)
            generateSecret()
        }
        val theirs = KeyAgreement.getInstance("X25519", name).run {
            init(peer.private)
            doPhase(local.public, true)
            generateSecret()
        }

        assertEquals(32, ours.size)
        assertTrue("both sides must derive the same secret", ours.contentEquals(theirs))

        val signer = KeyPairGenerator.getInstance("Ed25519", name).generateKeyPair()
        val signature = Signature.getInstance("Ed25519", name).run {
            initSign(signer.private)
            update(EXCHANGE_HASH)
            sign()
        }
        val verified = Signature.getInstance("Ed25519", name).run {
            initVerify(signer.public)
            update(EXCHANGE_HASH)
            verify(signature)
        }

        assertTrue("an ssh-ed25519 host key has to verify", verified)
    }

    @Test
    fun `the provider that was already there keeps its name and its place`() {
        val stale = installStale()
        val before = Security.getProviders().map(Provider::getName)

        SshSecurityProvider.ensureReady()

        val after = Security.getProviders().map(Provider::getName)
        assertSame("the platform provider must not be replaced", stale, Security.getProvider("BC"))
        assertEquals("nor moved", before.indexOf("BC"), after.indexOf("BC"))
        assertEquals(
            "the new one goes last, so no unqualified lookup changes hands",
            after.size - 1,
            after.indexOf(SshSecurityProvider.PROVIDER_NAME),
        )
        assertEquals(
            "and nothing else in the list moves",
            before,
            after.filterNot { it == SshSecurityProvider.PROVIDER_NAME },
        )
    }

    @Test
    fun `installing twice is one provider and one answer`() {
        installStale()

        val first = SshSecurityProvider.ensureReady()
        val second = SshSecurityProvider.ensureReady()

        assertEquals(first, second)
        assertEquals(
            "a second call must not stack a second provider",
            1,
            Security.getProviders().count { it.name == SshSecurityProvider.PROVIDER_NAME },
        )
    }

    @Test
    fun `a verified existing alias re-pins sshj after its pin changes`() {
        installStale()
        assertEquals(CryptoProviderStatus.Ready(SshSecurityProvider.PROVIDER_NAME), SshSecurityProvider.ensureReady())

        // Simulates a test reset or another sshj user changing the global pin
        // after Hermes already installed its verified alias.
        SecurityUtils.setSecurityProvider("BC")
        assertEquals(CryptoProviderStatus.Ready(SshSecurityProvider.PROVIDER_NAME), SshSecurityProvider.ensureReady())
        assertEquals(SshSecurityProvider.PROVIDER_NAME, SecurityUtils.getSecurityProvider())
        assertNotNull(SecurityUtils.getKeyPairGenerator("X25519"))

        SshSecurityProvider.resetForTest()
        SecurityUtils.setSecurityProvider("BC")
        assertEquals(CryptoProviderStatus.Ready(SshSecurityProvider.PROVIDER_NAME), SshSecurityProvider.ensureReady())
        assertEquals(SshSecurityProvider.PROVIDER_NAME, SecurityUtils.getSecurityProvider())
    }

    @Test
    fun `a provider that cannot carry a handshake is refused, not installed`() {
        val name = "HermesTestStrippedProvider"

        val status = SshSecurityProvider.install(name) { StalePlatformProvider(name) }

        val unavailable = status as CryptoProviderStatus.Unavailable
        assertTrue("the reason has to name what is missing: ${unavailable.reason}",
            unavailable.reason.contains("KeyPairGenerator.X25519"))
        assertNull("nothing may be registered under a name that failed", Security.getProvider(name))
        assertNotEquals("and sshj must not be pointed at it", name, SecurityUtils.getSecurityProvider())
    }

    @Test
    fun `the failure it reports carries no host, no credential and no stack`() {
        val name = "HermesTestStrippedProvider"

        val unavailable = SshSecurityProvider.install(name) { StalePlatformProvider(name) }
            as CryptoProviderStatus.Unavailable

        assertEquals(
            "a reason that goes on screen must survive redaction unchanged",
            unavailable.reason,
            redact(unavailable.reason),
        )
        assertTrue(unavailable.reason.contains("Nothing was sent"))
    }

    @Test
    fun `a provider setup exception fails closed without exposing its detail`() {
        val name = "HermesTestThrowingProvider"
        val unavailable = SshSecurityProvider.install(name) {
            throw SecurityException("do not expose this implementation detail")
        } as CryptoProviderStatus.Unavailable

        assertTrue(unavailable.reason.contains("could not be installed"))
        assertTrue(!unavailable.reason.contains("do not expose"))
        assertNull(Security.getProvider(name))
        assertNotEquals(name, SecurityUtils.getSecurityProvider())
    }

    /** Stands Android's stripped `BC` up where Android puts it. */
    private fun installStale(): Provider {
        val stale = StalePlatformProvider("BC")
        Security.removeProvider("BC")
        // Third, behind Conscrypt, is where the platform keeps it.
        Security.insertProviderAt(stale, 3)
        return stale
    }

    /**
     * A provider that owns a name and supplies nothing — which is what the
     * platform one looks like from sshj's side for every algorithm a modern
     * handshake needs.
     */
    @Suppress("DEPRECATION")
    private class StalePlatformProvider(name: String) :
        Provider(name, 1.0, "stand-in for Android's stripped platform provider")

    private companion object {
        val EXCHANGE_HASH = ByteArray(32) { it.toByte() }
    }
}
