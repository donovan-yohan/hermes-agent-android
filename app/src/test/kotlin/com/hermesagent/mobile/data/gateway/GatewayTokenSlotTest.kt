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
    }

    @Test
    fun `an unreadable blob is discarded rather than replayed`() = runBlocking {
        val store = AndroidGatewayTokenStore(context, ReversibleCipher())
        store.save(SLOT_A, tokens("alpha"))
        blobs().single().writeBytes(byteArrayOf(9, 9, 9))

        assertNull(store.load(SLOT_A))
        assertTrue(blobs().isEmpty())
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
