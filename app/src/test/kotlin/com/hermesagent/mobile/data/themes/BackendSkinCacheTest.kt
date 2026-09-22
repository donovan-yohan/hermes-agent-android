package com.hermesagent.mobile.data.themes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendSkinCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun skin(): JsonObject = Json.parseToJsonElement(
        """{"name":"cached-skin","colors":{"background":"#123","ui_text":"#fff","asset":"discard"},"banner_logo":"discard","customCSS":"discard"}""",
    ) as JsonObject

    @Test fun `definitions survive cache recreation without crossing connection or profile`() {
        val directory = temporary.newFolder()
        assertTrue(BackendSkinCache(directory).write("connection-a", "default", listOf(skin())))
        val restarted = BackendSkinCache(directory)
        val restored = restarted.read("connection-a", "default").single()
        assertEquals(parseBackendSkin(skin()), parseBackendSkin(restored))
        assertFalse(restored.toString().contains("discard"))
        assertTrue(restarted.read("connection-b", "default").isEmpty())
        assertTrue(restarted.read("connection-a", "other").isEmpty())
        assertFalse(directory.listFiles()!!.single().name.contains("connection-a"))
    }

    @Test fun `corrupt and oversized disk data are ignored`() {
        val directory = temporary.newFolder()
        val cache = BackendSkinCache(directory)
        assertTrue(cache.write("connection-a", "default", listOf(skin())))
        val file = directory.listFiles()!!.single()
        file.writeText("not json")
        assertTrue(cache.read("connection-a", "default").isEmpty())
        file.writeText(" ".repeat(256 * 1024 + 1))
        assertTrue(cache.read("connection-a", "default").isEmpty())
    }

    @Test fun `invalid names never become persisted definitions and replacement is complete`() {
        val directory = temporary.newFolder()
        val cache = BackendSkinCache(directory)
        assertTrue(cache.write("connection-a", "default", listOf(skin(), skin())))
        assertEquals(1, cache.read("connection-a", "default").size)
        val invalid = Json.parseToJsonElement("""{"name":" ","colors":{"background":"#123"}}""") as JsonObject
        assertTrue(cache.write("connection-a", "default", listOf(invalid)))
        assertTrue(cache.read("connection-a", "default").isEmpty())
        assertEquals(1, directory.listFiles()!!.size)
    }
}
