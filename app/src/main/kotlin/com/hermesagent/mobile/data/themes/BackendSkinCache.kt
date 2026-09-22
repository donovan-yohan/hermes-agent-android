package com.hermesagent.mobile.data.themes

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Validated skin definitions only; callers provide the authoritative connection/profile identity. */
internal class BackendSkinCache(private val directory: File) {
    fun read(connection: String, profile: String): List<JsonObject> = runCatching {
        val file = file(connection, profile)
        if (!file.isFile || file.length() > MAX_BYTES) return emptyList()
        val entries = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return emptyList()
        if (entries.size > MAX_SKINS) return emptyList()
        entries.mapNotNull { sanitize(it as? JsonObject) }.distinctBy { it["name"] }
    }.getOrDefault(emptyList())

    /** Writes through a same-directory temporary file so an interrupted write cannot truncate the cache. */
    fun write(connection: String, profile: String, skins: List<JsonObject>): Boolean = runCatching {
        val entries = skins.mapNotNull(::sanitize).distinctBy { it["name"] }
        if (entries.size > MAX_SKINS) return false
        val bytes = JsonArray(entries).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) return false
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val temporary = File.createTempFile("skin-", ".pending", directory)
        try {
            temporary.outputStream().use { it.write(bytes) }
            temporary.renameTo(file(connection, profile))
        } finally {
            temporary.delete()
        }
    }.getOrDefault(false)

    private fun file(connection: String, profile: String): File {
        val identity = JsonArray(listOf(JsonPrimitive(connection), JsonPrimitive(profile))).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(directory, "$digest.json")
    }

    private fun sanitize(payload: JsonObject?): JsonObject? {
        payload ?: return null
        val colors = payload["colors"] as? JsonObject ?: return null
        val safe = JsonObject(buildMap {
            payload["name"]?.let { put("name", it) }
            (payload["description"] as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= 512 }
                ?.let { put("description", it) }
            put("colors", JsonObject(colors.filter { (key, value) ->
                key in COLOR_KEYS && value is JsonPrimitive && value.isString && value.content.length <= 16
            }))
        })
        return safe.takeIf { parseBackendSkin(it) != null }
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024
        const val MAX_SKINS = 200
        val COLOR_KEYS = setOf(
            "background", "status_bar_bg", "ui_text", "banner_text", "status_bar_text",
            "ui_accent", "banner_accent", "banner_title", "ui_border", "banner_border",
            "banner_dim", "session_border", "completion_menu_bg", "ui_error",
        )
    }
}
