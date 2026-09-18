package com.hermesagent.mobile.data.profiles

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

internal fun avatarWire(bytes: ByteArray, mime: String = "image/png"): JsonElement = buildJsonObject {
    put("found", true)
    put("mime", mime)
    put("size", bytes.size)
    put("data", "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes))
}

class AvatarPayloadTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private fun edited(key: String, value: JsonElement): JsonElement =
        kotlinx.serialization.json.JsonObject((avatarWire(png) as kotlinx.serialization.json.JsonObject) + (key to value))

    @Test fun literalFalseAloneIsAbsence() {
        assertSame(AvatarPayload.Missing, parseAvatarPayload(Json.parseToJsonElement("{\"found\":false}")))
        listOf("{}", "[]", "null", "{\"found\":\"false\"}", "{\"found\":0}").forEach {
            assertNull(it, parseAvatarPayload(Json.parseToJsonElement(it)))
        }
    }

    @Test fun strictTypesSizesAndHeaders() {
        assertTrue(parseAvatarPayload(avatarWire(png)) is AvatarPayload.Raster)
        listOf(JsonPrimitive(-1), JsonPrimitive(0), JsonPrimitive(7), JsonPrimitive(2_000_001),
            JsonPrimitive("8"), JsonPrimitive(8.0), JsonPrimitive(true)).forEach {
            assertNull(parseAvatarPayload(edited("size", it)))
        }
        listOf("image/gif", "image/svg+xml", "IMAGE/PNG", "image/jpeg", "image/webp").forEach {
            assertNull(parseAvatarPayload(avatarWire(png, it)))
        }
        val encoded = Base64.getEncoder().encodeToString(png)
        listOf(encoded, "data:image/png,$encoded", "data:image/png;charset=utf-8;base64,$encoded",
            "data:image/jpeg;base64,$encoded", "data:image/png;base64,$encoded\n",
            "data:image/png;base64," + encoded.dropLast(1), "data:image/png;base64,!!!!",
            "data:image/png;base64,====", "data:image/png;base64,%123").forEach {
            assertNull(parseAvatarPayload(edited("data", JsonPrimitive(it))))
        }
    }

    @Test fun fullMagicAndCanonicalPadding() {
        for (index in png.indices) {
            val corrupt = png.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertNull(parseAvatarPayload(avatarWire(corrupt)))
        }
        assertNull(parseAvatarPayload(edited("data", JsonPrimitive("data:image/png;base64,iVBORw0KGgp="))))
        assertTrue(parseAvatarPayload(avatarWire(byteArrayOf(-1, -40, -1), "image/jpeg")) is AvatarPayload.Raster)
        assertTrue(parseAvatarPayload(avatarWire("RIFF0000WEBP".toByteArray(), "image/webp")) is AvatarPayload.Raster)
        assertNull(parseAvatarPayload(avatarWire("RIFF0000WEBX".toByteArray(), "image/webp")))
    }

    @Test fun actualByteAndEncodedLimitsAreIndependentOfReportedSize() {
        val max = ByteArray(AvatarLimits.BYTES).also { png.copyInto(it) }
        assertTrue(parseAvatarPayload(avatarWire(max)) is AvatarPayload.Raster)
        val tooLarge = avatarWire(max + byteArrayOf(0)) as kotlinx.serialization.json.JsonObject
        assertNull(parseAvatarPayload(kotlinx.serialization.json.JsonObject(tooLarge + ("size" to JsonPrimitive(8)))))
        assertNull(parseAvatarPayload(edited("data", JsonPrimitive("data:image/png;base64," + "A".repeat(AvatarLimits.BASE64_CHARS + 4)))))
    }
}
