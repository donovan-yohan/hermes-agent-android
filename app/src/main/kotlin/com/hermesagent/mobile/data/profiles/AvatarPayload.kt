package com.hermesagent.mobile.data.profiles

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Mobile policy, independent of the server's upload limit. No encoded data is retained. */
internal object AvatarLimits {
    const val BYTES = 2_000_000
    const val BASE64_CHARS = 2_666_668
    const val SOURCE_EDGE = 8192
    const val SOURCE_PIXELS = 16_777_216L
    const val IMAGE_EDGE = 256
    const val IMAGE_BYTES = 262_144
    const val CACHE_BYTES = 8 * 1024 * 1024
    const val ENTRIES = 64
    const val WORKERS = 2
    const val SUBSCRIBERS = 256
    const val TTL_MS = 60_000L
    const val ERROR_MS = 15_000L
}

internal sealed interface AvatarPayload {
    data object Missing : AvatarPayload
    class Raster(val bytes: ByteArray) : AvatarPayload
}

/** Null is invalid, never authoritative absence. All failure text is app-owned. */
internal fun parseAvatarPayload(value: JsonElement): AvatarPayload? {
    val obj = value as? JsonObject ?: return null
    val found = obj["found"] as? JsonPrimitive ?: return null
    if (found.isString) return null
    if (found.content == "false") return AvatarPayload.Missing
    if (found.content != "true") return null
    val mime = (obj["mime"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (mime != "image/png" && mime != "image/jpeg" && mime != "image/webp") return null
    val sizeField = obj["size"] as? JsonPrimitive ?: return null
    if (sizeField.isString || !sizeField.content.all { it in '0'..'9' }) return null
    val size = sizeField.content.toIntOrNull() ?: return null
    if (size !in 1..AvatarLimits.BYTES) return null
    val data = (obj["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val header = "data:$mime;base64,"
    if (data.length > header.length + AvatarLimits.BASE64_CHARS || !data.startsWith(header)) return null
    val length = data.length - header.length
    if (length == 0 || length % 4 != 0) return null
    // Require canonical padded base64, including zero unused bits. Java alone accepts unpadded input.
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val padding = when {
        data.endsWith("==") -> 2
        data.endsWith("=") -> 1
        else -> 0
    }
    // Bound the decoded allocation before copying the base64 substring or decoding it.
    if (length / 4 * 3 - padding != size) return null
    for (i in header.length until data.length - padding) {
        if (alphabet.indexOf(data[i]) < 0) return null
    }
    val last = alphabet.indexOf(data[data.length - padding - 1])
    if (padding == 2 && last and 15 != 0 || padding == 1 && last and 3 != 0) return null
    val bytes = try {
        Base64.getDecoder().decode(data.substring(header.length))
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bytes.size != size || bytes.size > AvatarLimits.BYTES) return null
    fun at(offset: Int, signature: IntArray) = bytes.size >= offset + signature.size &&
        signature.indices.all { bytes[offset + it].toInt() and 255 == signature[it] }
    val matches = when (mime) {
        "image/png" -> at(0, intArrayOf(137, 80, 78, 71, 13, 10, 26, 10))
        "image/jpeg" -> at(0, intArrayOf(255, 216, 255))
        else -> at(0, intArrayOf(82, 73, 70, 70)) && at(8, intArrayOf(87, 69, 66, 80))
    }
    return if (matches) AvatarPayload.Raster(bytes) else null
}
