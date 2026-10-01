package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BotsManagementRepositoryTest {
    private class Host : PluginHost {
        override val endpointGeneration = MutableStateFlow(7L)
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var reply: (String, JsonObject) -> PluginHostResult = { _, _ -> error("Unexpected request") }
        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            calls += method to params
            return reply(method, params)
        }
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }
    private fun ok(text: String) = PluginHostResult.Success(Json.parseToJsonElement(text))
    private val target = BotManagementTarget("worker", 7L)

    @Test fun `metadata read merge CAS write and exact readback preserves unrelated fields`() = runTest {
        val host = Host()
        var saved = false
        host.reply = { method, _ -> when (method) {
            "profiles.list" -> ok(if (saved) """{"profiles":[{"name":"worker","ui_meta_revisions":{"hermes-bots":5},"ui_meta":{"hermes-bots":{"color":"blue","pinned":true}}}]}""" else
                """{"profiles":[{"name":"worker","ui_meta_revisions":{"hermes-bots":4},"ui_meta":{"hermes-bots":{"color":"blue","pinned":false}}}]}""")
            "profiles.configure" -> { saved = true; ok("""{"ok":true,"applied":{"ui_meta":true}}""") }
            else -> error(method)
        } }
        assertEquals(BotManagementResult.Confirmed, BotsManagementRepository(host).patchMeta(target, buildJsonObject { put("pinned", true) }))
        val write = host.calls.single { it.first == "profiles.configure" }.second
        assertEquals(Json.parseToJsonElement("""{"hermes-bots":4}"""), write["ui_meta_expected_revisions"])
        assertEquals(Json.parseToJsonElement("""{"hermes-bots":{"color":"blue","pinned":true}}"""), write["ui_meta"])
        assertEquals(listOf("profiles.list", "profiles.configure", "profiles.list"), host.calls.map { it.first })
    }

    @Test fun `missing CAS capability never overwrites another clients namespace`() = runTest {
        val host = Host().apply { reply = { _, _ -> ok("""{"profiles":[{"name":"worker"}]}""") } }
        assertEquals(BotManagementResult.Unsupported, BotsManagementRepository(host).patchMeta(target, buildJsonObject { put("hidden", true) }))
        assertEquals(listOf("profiles.list"), host.calls.map { it.first })
    }

    @Test fun `endpoint switch between read and mutation prevents write`() = runTest {
        val host = Host()
        host.reply = { _, _ -> host.endpointGeneration.value++; ok("""{"profiles":[{"name":"worker","ui_meta_revisions":{}}]}""") }
        assertNotEquals(BotManagementResult.Confirmed, BotsManagementRepository(host).patchMeta(target, buildJsonObject { put("hidden", true) }))
        assertEquals(1, host.calls.size)
    }

    @Test fun `default deletion is rejected before any wire call`() = runTest {
        val host = Host()
        assertEquals(BotManagementResult.Rejected, BotsManagementRepository(host).delete(BotManagementTarget("default", 7L)))
        assertTrue(host.calls.isEmpty())
    }

    @Test fun `delete is unsupported even when CLI would report successful absence`() = runTest {
        val host = Host()
        host.reply = { method, _ -> if (method == "cli.exec") ok("""{"blocked":false,"code":0,"output":"never rendered"}""") else ok("""{"profiles":[]}""") }
        assertEquals(BotManagementResult.Unsupported, BotsManagementRepository(host).delete(target))
        assertTrue("CLI success cannot establish lifecycle-safe deletion", host.calls.isEmpty())
    }

    @Test fun `create does not copy channels or full history and never retries uncertain writes`() = runTest {
        val host = Host().apply { reply = { _, _ -> PluginHostResult.Refused(0, "not shown") } }
        assertEquals(BotManagementResult.Unconfirmed, BotsManagementRepository(host).create(7L, BotIdentityDraft(name = "copy"), cloneFrom = "worker", createdAtMillis = 100L))
        val payload = host.calls.single().second
        assertEquals(JsonPrimitive(false), payload["clone_channels"])
        assertEquals(JsonPrimitive(false), payload["clone_all"])
        assertEquals(JsonPrimitive(false), payload["share_auth"])
        assertEquals(JsonPrimitive(true), payload["mirror_credentials"])
        assertEquals(JsonPrimitive("worker"), payload["clone_from"])
    }

    @Test fun `create confirms target metadata and identity through readback`() = runTest {
        val host = Host()
        var meta = JsonObject(emptyMap())
        host.reply = { method, params -> when (method) {
            "profiles.create" -> ok("""{"ok":true,"name":"new-bot","soul_written":true}""")
            "profiles.list" -> ok("""{"profiles":[{"name":"new-bot","ui_meta_revisions":{},"ui_meta":{"hermes-bots":$meta}}]}""")
            "profiles.configure" -> {
                meta = params.getValue("ui_meta").jsonObject.getValue("hermes-bots").jsonObject
                ok("""{"ok":true,"applied":{"ui_meta":true}}""")
            }
            "profiles.describe" -> ok("""{"name":"new-bot","description":"Helper","soul":"Be useful"}""")
            else -> error(method)
        } }
        assertEquals(BotManagementResult.Confirmed, BotsManagementRepository(host).create(7L,
            BotIdentityDraft("new-bot", "New Bot", "Helper", "Be useful"), createdAtMillis = 100L))
        assertTrue(host.calls.any { it.first == "profiles.describe" })
        assertEquals(JsonPrimitive(100L), meta["created"])
    }

    @Test fun `edit validates both receipt and description soul title readback`() = runTest {
        val host = Host()
        var meta = JsonObject(emptyMap())
        var description = "old"
        var soul = "old soul"
        host.reply = { method, params -> when (method) {
            "profiles.list" -> PluginHostResult.Success(buildJsonObject {
                put("profiles", JsonArray(listOf(buildJsonObject {
                    put("name", "worker"); put("ui_meta_revisions", JsonObject(emptyMap()))
                    put("ui_meta", buildJsonObject { put("hermes-bots", meta) })
                })))
            })
            "profiles.configure" -> {
                val applied = buildJsonObject {
                    if ("description" in params) { description = params.getValue("description").jsonPrimitive.content; put("description", true) }
                    if ("soul" in params) { soul = params.getValue("soul").jsonPrimitive.content; put("soul", true) }
                    if ("ui_meta" in params) { meta = params.getValue("ui_meta").jsonObject.getValue("hermes-bots").jsonObject; put("ui_meta", true) }
                }
                PluginHostResult.Success(buildJsonObject { put("ok", true); put("applied", applied) })
            }
            "profiles.describe" -> PluginHostResult.Success(buildJsonObject {
                put("name", "worker"); put("description", description); put("soul", soul)
            })
            else -> error(method)
        } }
        assertEquals(BotManagementResult.Confirmed, BotsManagementRepository(host).saveIdentity(target,
            BotIdentityDraft("worker", "Helper", "New description", "New soul")))
        assertEquals("Helper", meta["title"]?.jsonPrimitive?.content)
        assertTrue(host.calls.filter { it.first == "profiles.configure" }.all { it.second["name"] == JsonPrimitive("worker") })
    }

    @Test fun `CAS rejection is not retried or called saved`() = runTest {
        val host = Host()
        host.reply = { method, _ -> if (method == "profiles.list")
            ok("""{"profiles":[{"name":"worker","ui_meta_revisions":{}}]}""")
        else ok("""{"ok":false,"applied":{"ui_meta":false,"ui_meta_conflicts":{"hermes-bots":{}}}}""") }
        assertEquals(BotManagementResult.Unconfirmed, BotsManagementRepository(host).patchMeta(target, buildJsonObject { put("hidden", true) }))
        assertEquals(2, host.calls.size)
    }

    @Test fun `claimed receipt without persisted value cannot confirm pin`() = runTest {
        val host = Host()
        host.reply = { method, _ -> if (method == "profiles.list")
            ok("""{"profiles":[{"name":"worker","ui_meta_revisions":{},"ui_meta":{"hermes-bots":{"pinned":false}}}]}""")
        else ok("""{"ok":true,"applied":{"ui_meta":true}}""") }
        assertEquals(BotManagementResult.Unconfirmed, BotsManagementRepository(host).patchMeta(target, buildJsonObject { put("pinned", true) }))
    }

    @Test fun `malformed roster cannot prove deletion`() = runTest {
        val host = Host()
        host.reply = { method, _ -> if (method == "cli.exec") ok("""{"blocked":false,"code":0}""") else ok("""{"profiles":[{}]}""") }
        assertEquals(BotManagementResult.Unsupported, BotsManagementRepository(host).delete(target))
    }

    @Test fun `duplicate suffix survives maximum length and collision`() {
        val base = "a".repeat(64)
        assertEquals("a".repeat(62) + "-3", nextBotCopyName(base, setOf("a".repeat(62) + "-2")))
    }

    @Test fun `literal identifiers are rejected not repaired`() = runTest {
        val host = Host()
        for (name in listOf(" worker ", "../worker", "-worker", "worker;echo", "")) {
            assertEquals(BotManagementResult.Rejected, BotsManagementRepository(host).create(7L, BotIdentityDraft(name = name), createdAtMillis = 1L))
        }
        assertTrue(host.calls.isEmpty())
    }
}
