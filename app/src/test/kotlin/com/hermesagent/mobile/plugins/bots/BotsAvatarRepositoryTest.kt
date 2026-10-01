package com.hermesagent.mobile.plugins.bots

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

internal fun avatarPng(): ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAKklEQVR4nO3NQQEAAATAQCTUvwwl+N0C7DJ64rN6vQMAAAAAAAAAAIDDFmtiAY8TwohVAAAAAElFTkSuQmCC")
internal fun avatarReply(bytes: ByteArray) = buildJsonObject {
    put("found", true); put("mime", "image/png"); put("size", bytes.size)
    put("data", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
}
class BotsAvatarRepositoryTest {
    private val target = BotManagementTarget("worker", 7)
    @Test fun `unchanged missing avatar cannot erase another clients new image`() = runTest {
        val host = ModelTestHost()
        assertEquals(BotAvatarSave.Unchanged, BotsAvatarRepository(host).save(target, null, BotAvatarChange.Unchanged))
        assertTrue(host.calls.isEmpty())
    }
    @Test fun `clear is explicit and requires absent readback`() = runTest {
        val host = ModelTestHost().apply { answer = { method, _ -> modelReply(if (method == "profiles.set_asset")
            """{"ok":true,"asset":"avatar","size":0,"removed":1}""" else """{"found":false}""") } }
        assertEquals(BotAvatarSave.Saved, BotsAvatarRepository(host).save(target, avatarPng(), BotAvatarChange.Clear))
        assertEquals(listOf("profiles.set_asset", "profiles.get_asset"), host.calls.map { it.first })
        assertEquals(Json.parseToJsonElement("""{"name":"worker","asset":"avatar","clear":true}"""), host.calls.first().second)
    }
    @Test fun `upload is one inline png write with exact byte readback`() = runTest {
        val bytes = avatarPng()
        val host = ModelTestHost().apply { answer = { method, _ -> if (method == "profiles.set_asset")
            modelReply("""{"ok":true,"asset":"avatar","size":${bytes.size}}""") else
            com.hermesagent.mobile.plugins.PluginHostResult.Success(avatarReply(bytes)) } }
        assertEquals(BotAvatarSave.Saved, BotsAvatarRepository(host).save(target, null, BotAvatarChange.Image(bytes)))
        assertEquals(setOf("name", "asset", "data"), host.calls.first().second.keys)
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes), host.calls.first().second["data"]!!.jsonPrimitive.content)
    }
    @Test fun `unchanged bytes absent clear and over wire cap do not write`() = runTest {
        val host = ModelTestHost(); val repo = BotsAvatarRepository(host); val bytes = avatarPng()
        assertEquals(BotAvatarSave.Unchanged, repo.save(target, bytes, BotAvatarChange.Image(bytes.copyOf())))
        assertEquals(BotAvatarSave.Unchanged, repo.save(target, null, BotAvatarChange.Clear))
        assertEquals(BotAvatarSave.Rejected, repo.save(target, null, BotAvatarChange.Image(ByteArray(2_000_001))))
        assertTrue(host.calls.isEmpty())
    }
    @Test fun `wire cap counts decoded bytes not base64 characters and is inclusive`() = runTest {
        val bytes = avatarPng().copyOf(2_000_000)
        val host = ModelTestHost().apply { answer = { method, _ -> if (method == "profiles.set_asset")
            modelReply("""{"ok":true,"asset":"avatar","size":2000000}""") else
            com.hermesagent.mobile.plugins.PluginHostResult.Success(avatarReply(bytes)) } }
        assertEquals(BotAvatarSave.Saved, BotsAvatarRepository(host).save(target, null, BotAvatarChange.Image(bytes)))
        assertTrue(host.calls.first().second.getValue("data").jsonPrimitive.content.length > 2_000_000)
    }
    @Test fun `malformed receipts refuse readback and invalid identity never dispatches`() = runTest {
        for (receipt in listOf("{}", """{"ok":"true","asset":"avatar","size":0}""", """{"ok":true,"asset":"pet","size":0}""")) {
            val host = ModelTestHost().apply { answer = { _, _ -> modelReply(receipt) } }
            assertEquals(BotAvatarSave.Unconfirmed, BotsAvatarRepository(host).save(target, avatarPng(), BotAvatarChange.Clear))
            assertEquals(1, host.calls.size)
        }
        val host = ModelTestHost()
        assertEquals(BotAvatarSave.Rejected, BotsAvatarRepository(host).save(BotManagementTarget(" worker", 7), avatarPng(), BotAvatarChange.Clear))
        assertTrue(host.calls.isEmpty())
    }
    @Test fun `failed clear readback and old gateway never claim success`() = runTest {
        val host = ModelTestHost().apply { answer = { method, _ -> if (method == "profiles.set_asset")
            modelReply("""{"ok":true,"asset":"avatar","size":0}""") else
            com.hermesagent.mobile.plugins.PluginHostResult.Success(avatarReply(avatarPng())) } }
        assertEquals(BotAvatarSave.Unconfirmed, BotsAvatarRepository(host).save(target, avatarPng(), BotAvatarChange.Clear))
        host.answer = { _, _ -> com.hermesagent.mobile.plugins.PluginHostResult.Refused(-32601, "private filesystem") }
        assertEquals(BotAvatarSave.Unconfirmed, BotsAvatarRepository(host).save(target, avatarPng(), BotAvatarChange.Clear))
    }
}
