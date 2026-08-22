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
 * Profile-scoped OAuth tokens encrypted with a non-exportable Android Keystore
 * key and written below `noBackupFilesDir`.
 *
 * The ciphertext is not eligible for cloud backup or device transfer, and the
 * key cannot leave this Android install. A failed decrypt clears the unusable
 * blob rather than repeatedly replaying it.
 */
internal class AndroidGatewayTokenStore(
    context: Context,
) : GatewayTokenStore {
    private val directory = File(context.noBackupFilesDir, "gateway-auth")

    override suspend fun load(baseUrl: String): GatewayNativeTokens? = withContext(Dispatchers.IO) {
        val file = tokenFile(baseUrl)
        if (!file.isFile) return@withContext null
        val encoded = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        var plaintext: ByteArray? = null
        try {
            if (encoded.size <= HEADER_BYTES || encoded[0] != FORMAT_VERSION) {
                file.delete()
                return@withContext null
            }
            val iv = encoded.copyOfRange(1, 1 + IV_BYTES)
            val ciphertext = encoded.copyOfRange(1 + IV_BYTES, encoded.size)
            plaintext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, iv))
                doFinal(ciphertext)
            }
            parseStoredTokens(plaintext.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            file.delete()
            null
        } finally {
            encoded.fill(0)
            plaintext?.fill(0)
        }
    }

    override suspend fun save(baseUrl: String, tokens: GatewayNativeTokens) = withContext(Dispatchers.IO) {
        requireNotNull(normalizeRemoteGatewayUrl(baseUrl))
        directory.mkdirs()
        val plaintext = buildJsonObject {
            put("accessToken", JsonPrimitive(tokens.accessToken))
            put("refreshToken", JsonPrimitive(tokens.refreshToken))
            put("expiresAt", JsonPrimitive(tokens.expiresAt))
            put("provider", JsonPrimitive(tokens.provider))
            put("userId", JsonPrimitive(tokens.userId))
        }.toString().toByteArray(Charsets.UTF_8)
        var encrypted: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, encryptionKey())
            }
            val ciphertext = cipher.doFinal(plaintext)
            encrypted = byteArrayOf(FORMAT_VERSION) + cipher.iv + ciphertext
            val target = tokenFile(baseUrl)
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
        } finally {
            plaintext.fill(0)
            encrypted?.fill(0)
        }
    }

    override suspend fun clear(baseUrl: String) {
        withContext(Dispatchers.IO) {
            tokenFile(baseUrl).delete()
        }
    }

    private fun tokenFile(baseUrl: String): File {
        val normalized = requireNotNull(normalizeRemoteGatewayUrl(baseUrl))
        val name = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, "$name.bin")
    }

    private fun encryptionKey(): SecretKey {
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

    private fun parseStoredTokens(raw: String): GatewayNativeTokens? {
        val body = runCatching { JSON.parseToJsonElement(raw) as JsonObject }.getOrNull() ?: return null
        val accessToken = body.string("accessToken").orEmpty()
        if (accessToken.isBlank()) return null
        return GatewayNativeTokens(
            accessToken = accessToken,
            refreshToken = body.string("refreshToken").orEmpty(),
            expiresAt = (body["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            provider = body.string("provider").orEmpty(),
            userId = body.string("userId").orEmpty(),
        )
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hermes.gateway.native-auth.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
        const val HEADER_BYTES = 1 + IV_BYTES
        const val FORMAT_VERSION: Byte = 1
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
