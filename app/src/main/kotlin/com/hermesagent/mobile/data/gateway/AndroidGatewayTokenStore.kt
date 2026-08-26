package com.hermesagent.mobile.data.gateway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Envelope encryption for one stored blob.
 *
 * Production is [KeystoreSecretCipher], whose key is generated inside the
 * Android Keystore and cannot leave the device. It is a seam because the
 * *slot* behaviour this file owns -- one file per saved connection, one-time
 * adoption of the pre-registry file, binding a credential to the host that
 * minted it, and erasing exactly one connection's credential -- is what needs
 * proving, and proving it must not depend on a hardware-backed keystore being
 * present in a JVM test.
 */
internal interface SecretCipher {
    /** Returns iv + ciphertext. */
    fun seal(plaintext: ByteArray): Pair<ByteArray, ByteArray>

    fun open(iv: ByteArray, ciphertext: ByteArray): ByteArray
}

/** Non-exportable AES-256-GCM key held by the Android Keystore. */
internal class KeystoreSecretCipher : SecretCipher {
    override fun seal(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv to cipher.doFinal(plaintext)
    }

    override fun open(iv: ByteArray, ciphertext: ByteArray): ByteArray =
        Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            doFinal(ciphertext)
        }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hermes.gateway.native-auth.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * Per-connection OAuth tokens encrypted with a non-exportable Android Keystore
 * key and written below `noBackupFilesDir`.
 *
 * One file per saved connection, named after that connection's random local row
 * id, so removing a connection erases exactly its credential and leaves every
 * sibling's alone. Erasure is addressable by the row id **alone**: a row whose
 * URL was blanked or mistyped still owns a file, and being unable to parse
 * where a credential was for is the worst possible reason to leave it on disk.
 *
 * A stored credential also carries the normalized URL of the host that minted
 * it, and is refused -- but kept, so a mistyped address is recoverable -- if
 * that host is not the one now being asked. Naming the file after the row is what makes a row's secret follow the
 * row; binding the blob to its host is what stops a re-addressed row from
 * presenting gateway A's bearer token to gateway B.
 *
 * The ciphertext is not eligible for cloud backup or device transfer, and the
 * key cannot leave this Android install. Erasure is a best-effort
 * zero-overwrite followed by a delete. On flash storage an overwrite is not a
 * guaranteed shred -- that is stated rather than implied -- but the plaintext
 * never reaches disk at all: it exists only in a byte array that is zeroed in
 * a `finally`.
 */
internal class AndroidGatewayTokenStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) : GatewayTokenStore {
    private val directory = File(context.noBackupFilesDir, "gateway-auth")

    override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = withContext(Dispatchers.IO) {
        // Reading a credential is always "for this host"; a slot with no URL
        // can be erased but never read.
        val expectedUrl = slot.normalizedBaseUrl ?: return@withContext null
        val (file, adopted) = adoptedFile(slot) ?: return@withContext null
        val encoded = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        var plaintext: ByteArray? = null
        val stored = try {
            if (encoded.size <= HEADER_BYTES || encoded[0] != FORMAT_VERSION) {
                // `file`, not `slotFile(slot)`: when adoption's rename failed
                // this read came from the pre-registry file, and that is the
                // unusable one to remove.
                erase(file)
                return@withContext null
            }
            val iv = encoded.copyOfRange(1, 1 + IV_BYTES)
            val ciphertext = encoded.copyOfRange(1 + IV_BYTES, encoded.size)
            plaintext = cipher.open(iv, ciphertext)
            parseStoredTokens(plaintext.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            erase(file)
            null
        } finally {
            encoded.fill(0)
            plaintext?.fill(0)
        } ?: return@withContext null

        // Refusal is the guarantee, and refusal alone. Reading is not the place
        // to destroy anything: a mistyped URL is a read against the wrong host,
        // and erasing there would spend the credential for the *correct* one on
        // a typo. The credential stays sealed and unusable until the row points
        // at its minting host again, or until something deliberate — removing
        // the row, or re-addressing it through the editor — erases it.
        when (stored.boundUrl) {
            // The pre-registry file was named after its Gateway URL, so the
            // file this was just adopted from *is* the binding. Rewrite it
            // bound, once, so the next read has the same guarantee as any
            // other and a later re-address cannot slip past this branch.
            null -> if (adopted) {
                save(slot, stored.tokens)
                stored.tokens
            } else {
                // A row-named blob naming no host cannot be proved to belong to
                // whoever is asking, so it is returned to nobody.
                null
            }

            expectedUrl -> stored.tokens

            // Minted by another Gateway. The credential is that Gateway's, this
            // row now points elsewhere, and presenting it here would hand one
            // host's bearer token to another.
            else -> null
        }
    }

    override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) = withContext(Dispatchers.IO) {
        val boundUrl = requireNotNull(slot.normalizedBaseUrl) {
            "A credential can only be stored for a Gateway this app can address."
        }
        directory.mkdirs()
        val plaintext = buildJsonObject {
            put("accessToken", JsonPrimitive(tokens.accessToken))
            put("refreshToken", JsonPrimitive(tokens.refreshToken))
            put("expiresAt", JsonPrimitive(tokens.expiresAt))
            put("provider", JsonPrimitive(tokens.provider))
            put("userId", JsonPrimitive(tokens.userId))
            // Which Gateway minted this. Read back on every load; a mismatch is
            // refused rather than presented to the wrong host.
            put(BOUND_URL, JsonPrimitive(boundUrl))
        }.toString().toByteArray(Charsets.UTF_8)
        var encrypted: ByteArray? = null
        try {
            val (iv, ciphertext) = cipher.seal(plaintext)
            encrypted = byteArrayOf(FORMAT_VERSION) + iv + ciphertext
            val target = slotFile(slot)
            val temporary = File(directory, ".${target.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(encrypted)
                    output.fd.sync()
                }
                check(temporary.renameTo(target)) { "Could not store the Gateway sign-in." }
            } finally {
                temporary.delete()
            }
            // The pre-registry file is superseded the moment this connection has
            // its own; leaving it would be a second copy of the same credential.
            legacyFile(slot)?.takeIf { it != target }?.let { superseded -> erase(superseded) }
            Unit
        } finally {
            plaintext.fill(0)
            encrypted?.fill(0)
        }
    }

    override suspend fun clear(slot: GatewaySecretSlot) {
        withContext(Dispatchers.IO) {
            erase(slotFile(slot))
            legacyFile(slot)?.let(::erase)
        }
    }

    /**
     * This connection's own file plus whether it was just adopted, adopting the
     * pre-registry URL-named file exactly once if that is all this install has.
     * Adoption is a rename, so a second connection to the same URL cannot
     * inherit the first one's credential afterwards.
     */
    private fun adoptedFile(slot: GatewaySecretSlot): Pair<File, Boolean>? {
        val target = slotFile(slot)
        if (target.isFile) return target to false
        val legacy = legacyFile(slot)?.takeIf { it.isFile && it != target } ?: return null
        directory.mkdirs()
        return if (legacy.renameTo(target)) target to true else legacy to true
    }

    /**
     * The row's file when there is a row, otherwise the pre-registry
     * URL-derived one.
     *
     * The domain separator is written as a source escape on purpose: typing a
     * raw control byte here makes Git classify the most security-sensitive file
     * in this module as binary, which hides its diff from review entirely.
     * `slotDigestInput` is public to this module so a test can pin the exact
     * bytes -- a future "harmless" normalisation of this string would rename
     * every slot file and sign every user out.
     */
    private fun slotFile(slot: GatewaySecretSlot): File = when {
        slot.connectionId.isNotBlank() -> File(directory, "${digest(slotDigestInput(slot.connectionId))}.bin")
        else -> urlFile(requireNotNull(slot.normalizedBaseUrl))
    }

    private fun legacyFile(slot: GatewaySecretSlot): File? = when {
        slot.connectionId.isBlank() -> null
        else -> slot.normalizedBaseUrl?.let(::urlFile)
    }

    private fun urlFile(normalizedBaseUrl: String): File =
        File(directory, "${digest(requireNotNull(normalizeRemoteGatewayUrl(normalizedBaseUrl)))}.bin")

    /** Zero the ciphertext in place, then unlink. Best effort; never throws. */
    private fun erase(file: File) {
        runCatching {
            if (file.isFile) {
                val length = file.length()
                if (length > 0) {
                    FileOutputStream(file).use { output ->
                        output.write(ByteArray(length.coerceAtMost(MAX_ERASE_BYTES).toInt()))
                        output.fd.sync()
                    }
                }
            }
        }
        file.delete()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun parseStoredTokens(raw: String): StoredCredential? {
        val body = runCatching { JSON.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return null
        val accessToken = body.string("accessToken").orEmpty()
        if (accessToken.isBlank()) return null
        return StoredCredential(
            tokens = GatewayNativeTokens(
                accessToken = accessToken,
                refreshToken = body.string("refreshToken").orEmpty(),
                expiresAt = (body["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                provider = body.string("provider").orEmpty(),
                userId = body.string("userId").orEmpty(),
            ),
            boundUrl = body.string(BOUND_URL)?.takeIf(String::isNotBlank),
        )
    }

    /** A decrypted blob plus the Gateway it names, absent on a pre-registry one. */
    private data class StoredCredential(val tokens: GatewayNativeTokens, val boundUrl: String?)

    internal companion object {
        const val IV_BYTES = 12
        const val HEADER_BYTES = 1 + IV_BYTES
        const val FORMAT_VERSION: Byte = 1
        const val BOUND_URL = "baseUrl"

        /** A stored sign-in is a few hundred bytes; this only bounds a corrupt length. */
        const val MAX_ERASE_BYTES = 64L * 1024L
        val JSON = Json { ignoreUnknownKeys = true }

        /** Exactly the bytes a row's file name is derived from. Pinned by `SlotDigestTest`. */
        fun slotDigestInput(connectionId: String): String = "connection\u0000$connectionId"
    }
}
