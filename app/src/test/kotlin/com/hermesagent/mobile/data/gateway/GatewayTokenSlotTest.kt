package com.hermesagent.mobile.data.gateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One Keystore entry per saved connection.
 *
 * What has to hold: two connections never share a slot, removing one erases
 * only its own credential, and an install that upgrades from the
 * single-connection build keeps the sign-in it already had — exactly once,
 * so a second connection to the same URL cannot inherit it afterwards.
 *
 * The Android Keystore itself is not what these assertions are about, so the
 * cipher is the injectable seam and a reversible test double stands in for it.
 * That the production cipher is a non-exportable `AndroidKeyStore` AES-GCM key
 * below `noBackupFilesDir` is asserted by `BackupRulesTest` against the source.
 *
 * No token here is a real credential and no URL is a real Gateway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewayTokenSlotTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.noBackupFilesDir, "gateway-auth")

    @Before
    fun startFromNothing() {
        directory.deleteRecursively()
    }

    @Test
    fun `two connections to different gateways keep separate slots`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())

        store.save(SLOT_A, tokens("alpha"))
        store.save(SLOT_B, tokens("beta"))

        assertEquals("alpha-access", store.load(SLOT_A)?.accessToken)
        assertEquals("beta-access", store.load(SLOT_B)?.accessToken)
        assertEquals("one file per connection", 2, blobs().size)
    }

    @Test
    fun `removing one connection erases only that connection's credential`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        store.save(SLOT_A, tokens("alpha"))
        store.save(SLOT_B, tokens("beta"))

        store.clear(SLOT_A)

        assertNull(store.load(SLOT_A))
        assertEquals("the sibling is untouched", "beta-access", store.load(SLOT_B)?.accessToken)
        assertEquals(1, blobs().size)
    }

    @Test
    fun `a removed credential is overwritten before it is unlinked`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        store.save(SLOT_A, tokens("alpha"))
        val blob = blobs().single()
        val stored = blob.readBytes()
        assertTrue("something was actually written", stored.any { it != ZERO })
        // A second name for the same inode, so the bytes the erase wrote are
        // still readable after the entry itself is unlinked. On flash an
        // overwrite is best effort — the class doc says so — but the plaintext
        // never reached disk at all.
        val witness = File(directory, "witness.link")
        Files.createLink(witness.toPath(), blob.toPath())

        store.clear(SLOT_A)

        assertFalse("the entry is unlinked", blob.exists())
        assertArrayEquals("and its bytes were zeroed first", ByteArray(stored.size), witness.readBytes())
    }

    @Test
    fun `two connections to the same gateway never share one slot`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        val sameUrlOtherRow = GatewaySecretSlot("connection-c", SLOT_A.normalizedBaseUrl)

        store.save(SLOT_A, tokens("alpha"))

        assertNull("a different row starts signed out", store.load(sameUrlOtherRow))
    }

    @Test
    fun `a row's file name is pinned, so a future normalisation cannot rename every slot`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())

        store.save(GatewaySecretSlot("connection-a", "https://alpha.test"), tokens("alpha"))

        // SHA-256 of "connection" + U+0000 + "connection-a". Renaming this input
        // renames every stored slot, which signs every user out silently, so the
        // bytes are pinned here rather than left to whatever the source happens
        // to say. The separator must be an escape: a raw control byte makes Git
        // treat the store as binary and hides its diff from review.
        assertEquals(PINNED_SLOT_FILE, blobs().single().name)
        assertEquals(
            "connection\u0000connection-a",
            AndroidGatewayTokenStore.slotDigestInput("connection-a"),
        )
    }

    @Test
    fun `a row with no usable URL can still be erased`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        store.save(SLOT_A, tokens("alpha"))
        val blob = blobs().single()
        val stored = blob.readBytes()
        val witness = File(directory, "witness.link")
        Files.createLink(witness.toPath(), blob.toPath())

        // What a Remote row looks like once someone blanks its URL: there is no
        // address any more, only a row. Erasure must still reach the file, or
        // the credential outlives every UI that could remove it.
        store.clear(GatewaySecretSlot.forRow("connection-a"))

        assertTrue("the entry is unlinked", blobs().isEmpty())
        assertArrayEquals("and its bytes were zeroed first", ByteArray(stored.size), witness.readBytes())
    }

    @Test
    fun `a credential is refused, and erased, when its row now points at another gateway`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        store.save(SLOT_A, tokens("alpha"))

        // The same row, re-addressed. The stored credential was minted by
        // alpha; presenting it to beta would hand one host's bearer token to
        // another.
        val readdressed = GatewaySecretSlot("connection-a", "https://beta.test")

        assertNull(store.load(readdressed))
        assertTrue("and the abandoned credential does not linger", blobs().isEmpty())
        assertNull("nor does it come back for the original address", store.load(SLOT_A))
    }

    @Test
    fun `a row-named credential that names no host is refused rather than trusted`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        // A blob under a row's name with no host recorded cannot be proved to
        // belong to whoever is asking, so it is not returned to anyone.
        writeUnboundBlob(AndroidGatewayTokenStore.slotDigestInput("connection-a"))

        assertNull(store.load(SLOT_A))
        assertTrue(blobs().isEmpty())
    }

    @Test
    fun `an upgrading install adopts its pre-registry sign-in exactly once`() = runBlocking {
        val legacy = AndroidGatewayTokenStore(context, ReversibleCipher())
        // What the single-connection build wrote: a slot with no row behind it,
        // named after the Gateway URL.
        legacy.save(GatewaySecretSlot("", SLOT_A.normalizedBaseUrl), tokens("alpha"))
        assertEquals(1, blobs().size)

        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        assertEquals("the person stays signed in", "alpha-access", store.load(SLOT_A)?.accessToken)
        assertEquals("and the old file moved rather than being copied", 1, blobs().size)

        val secondRowSameUrl = GatewaySecretSlot("connection-c", SLOT_A.normalizedBaseUrl)
        assertNull("adoption happens once, not per row", store.load(secondRowSameUrl))

        // Adoption also binds: the file is rewritten naming the host it came
        // from, so re-addressing that row afterwards is refused like any other.
        assertNull(
            "an adopted credential is still that host's",
            store.load(GatewaySecretSlot("connection-a", "https://beta.test")),
        )
    }

    @Test
    fun `an unreadable blob is discarded rather than replayed`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        store.save(SLOT_A, tokens("alpha"))
        blobs().single().writeBytes(byteArrayOf(9, 9, 9))

        assertNull(store.load(SLOT_A))
        assertTrue(blobs().isEmpty())
    }

    /** A pre-binding blob written straight under a row's name, as no released build makes. */
    private fun writeUnboundBlob(digestInput: String) {
        val name = java.security.MessageDigest.getInstance("SHA-256")
            .digest(digestInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val plaintext = """{"accessToken":"unbound-access","refreshToken":"unbound-refresh"}"""
            .toByteArray(Charsets.UTF_8)
        val cipher = ReversibleCipher()
        val (iv, ciphertext) = cipher.seal(plaintext)
        directory.mkdirs()
        File(directory, "$name.bin").writeBytes(
            byteArrayOf(AndroidGatewayTokenStore.FORMAT_VERSION) + iv + ciphertext,
        )
    }

    @Test
    fun `a re-addressed row presents no bearer minted for the gateway it left`() = runBlocking {
        val api = RecordingAuthApi()
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = store,
            login = GatewayNativeLogin { _, _ -> tokens("alpha") },
            nowSeconds = { 1_000L },
        )
        val onAlpha = RemoteGatewayProfile("https://alpha.test", secretSlotId = "connection-a")
        val onBeta = RemoteGatewayProfile("https://beta.test", secretSlotId = "connection-a")

        // Sign in once against alpha, the ordinary way.
        authenticator.ticket(onAlpha, GatewayBrowserLauncher {})
        assertEquals(listOf("alpha-access"), api.presentedTo["https://alpha.test"])

        // The same row, now pointed at beta, with no sign-in there yet. It must
        // ask for one rather than reach for the credential alpha issued.
        val refusal = runCatching { authenticator.ticket(onBeta, browser = null) }.exceptionOrNull()

        assertTrue(refusal is GatewayAuthException)
        assertEquals(
            "the person is asked to sign in, not silently connected",
            "Sign in to this Gateway before reconnecting.",
            refusal?.message,
        )
        assertNull("beta was never handed a token", api.presentedTo["https://beta.test"])
        assertTrue("and no token was minted anywhere for beta", api.mintedFor.none { it == "https://beta.test" })
    }

    @Test
    fun `a row that goes back to its own gateway is not resurrected from a stale blob`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        store.save(SLOT_A, tokens("alpha"))

        // Away and back. The mismatch on the way out erased it, so returning
        // means signing in again rather than reusing a credential that spent
        // time pointing somewhere else.
        assertNull(store.load(GatewaySecretSlot("connection-a", "https://beta.test")))

        assertNull(store.load(SLOT_A))
    }

    /** Records which host each access token was presented to. */
    private class RecordingAuthApi : GatewayNativeAuthApi {
        val presentedTo = mutableMapOf<String, List<String>>()
        val mintedFor = mutableListOf<String>()

        override suspend fun status(baseUrl: String) =
            GatewayAuthStatus(authRequired = true, authFlows = setOf("native_pkce"))

        override suspend fun exchange(baseUrl: String, code: String, verifier: String) = tokens("alpha")

        override suspend fun refresh(baseUrl: String, refreshToken: String, provider: String): GatewayNativeTokens? =
            null

        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String {
            presentedTo[baseUrl] = presentedTo.getOrDefault(baseUrl, emptyList()) + accessToken
            mintedFor += baseUrl
            return "ticket-${mintedFor.size}"
        }
    }

    private fun blobs(): List<File> =
        directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".bin") }

    /**
     * A reversible stand-in for the Keystore key. It is deliberately not
     * encryption: it exists so the slot behaviour can be asserted without a
     * hardware-backed keystore, and it records the pattern an erase writes.
     */
    private class ReversibleCipher : SecretCipher {
        override fun seal(plaintext: ByteArray): Pair<ByteArray, ByteArray> =
            ByteArray(12) { 7 } to plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

        override fun open(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            require(iv.size == 12) { "unexpected iv" }
            return ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private companion object {
        const val ZERO: Byte = 0

        /** SHA-256("connection" + U+0000 + "connection-a"). */
        const val PINNED_SLOT_FILE =
            "3dd7954644c364f9dc48d6325dbe62fdad29a71c7945aece5ad505225dea1831.bin"
        val SLOT_A = GatewaySecretSlot("connection-a", "https://alpha.test")
        val SLOT_B = GatewaySecretSlot("connection-b", "https://beta.test")

        fun tokens(prefix: String) = GatewayNativeTokens(
            accessToken = "$prefix-access",
            refreshToken = "$prefix-refresh",
            expiresAt = 4_102_444_800L,
            provider = "fixture-provider",
            userId = "fixture-user",
        )
    }
}
