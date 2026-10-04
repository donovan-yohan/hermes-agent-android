package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class ToolsetsTestHost : PluginHost {
    override val endpointGeneration = MutableStateFlow(7L)
    override val connected = MutableStateFlow(true)
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var beforeDispatch: suspend (String) -> Unit = {}
    var answer: suspend (String, JsonObject) -> PluginHostResult = { _, _ -> error("Unexpected request") }
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("Unfenced call")
    override suspend fun requestAtEndpoint(expectedGeneration: Long, method: String, params: JsonObject) =
        requestAtEndpointGuarded(expectedGeneration, method, params) { true }
    override suspend fun requestAtEndpointGuarded(expectedGeneration: Long, method: String, params: JsonObject, dispatchAllowed: () -> Boolean): PluginHostResult {
        beforeDispatch(method)
        if (expectedGeneration != endpointGeneration.value || !dispatchAllowed()) return PluginHostResult.Refused(0, "Changed")
        calls += method to params
        val result = answer(method, params)
        return if (expectedGeneration == endpointGeneration.value && dispatchAllowed()) result else PluginHostResult.Refused(0, "Changed")
    }
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
}
internal fun toolsetsReply(text: String) = PluginHostResult.Success(Json.parseToJsonElement(text))
internal fun toolsetsJson(pinned: Boolean = false, a: Boolean = true, b: Boolean = false) =
    """{"name":"worker","toolsets_pinned":$pinned,"toolsets":[{"name":"web","enabled":$a,"description":"Search"},{"name":"terminal","enabled":$b,"description":"Commands"}]}"""

class BotsToolsetsRepositoryTest {
    private val target = BotManagementTarget("worker", 7L)
    @Test fun `save preflights then writes only selected names and confirms actual readback`() = runTest {
        val host = ToolsetsTestHost()
        var changed = false
        host.answer = { method, _ ->
            if (method == "profiles.configure") { changed = true; toolsetsReply("""{"ok":true,"applied":{"toolsets":true}}""") }
            else toolsetsReply(toolsetsJson(pinned = changed, b = changed))
        }
        val repo = BotsToolsetsRepository(host)
        val baseline = repo.describe(target)!!
        host.calls.clear()
        val result = repo.save(target, baseline, setOf("web", "terminal")) { true }
        assertTrue(result is BotToolsetsSave.Saved)
        assertEquals(listOf("profiles.describe", "profiles.configure", "profiles.describe"), host.calls.map { it.first })
        assertEquals(Json.parseToJsonElement("""{"name":"worker","enabled_toolsets":["web","terminal"]}"""), host.calls[1].second)
        assertTrue((result as BotToolsetsSave.Saved).actual.pinned)
    }
    @Test fun `server supplied unknown name roundtrips without client allowlist loss`() = runTest {
        val unknown = "future-plugin-toolset"
        var wrote = false
        val host = ToolsetsTestHost().apply { answer = { method, _ ->
            if (method == "profiles.configure") {
                wrote = true; toolsetsReply("""{"ok":true,"applied":{"toolsets":true}}""")
            } else toolsetsReply(toolsetsJson(pinned = wrote, b = wrote).replace("terminal", unknown))
        } }
        val repo = BotsToolsetsRepository(host)
        val baseline = repo.describe(target)!!
        assertEquals(listOf("web", unknown), baseline.rows.map { it.name })
        val saved = repo.save(target, baseline, setOf("web", unknown)) { true } as BotToolsetsSave.Saved
        assertEquals(setOf("web", unknown), saved.actual.enabled)
        assertTrue(saved.actual.pinned)
        assertEquals(Json.parseToJsonElement("""{"name":"worker","enabled_toolsets":["web","future-plugin-toolset"]}"""),
            host.calls.single { it.first == "profiles.configure" }.second)
        assertEquals(saved.actual, repo.describe(target))
    }
    @Test fun `invalid selection never dispatches and stale baseline never configures`() = runTest {
        val host = ToolsetsTestHost().apply { answer = { _, _ -> toolsetsReply(toolsetsJson()) } }
        val repo = BotsToolsetsRepository(host); val baseline = repo.describe(target)!!
        host.calls.clear()
        for (wanted in listOf(emptySet(), setOf("unknown"), setOf(" web")))
            assertEquals(BotToolsetsSave.Rejected, repo.save(target, baseline, wanted) { true })
        assertTrue(host.calls.isEmpty())
        host.answer = { _, _ -> toolsetsReply(toolsetsJson(b = true)) }
        assertEquals(BotToolsetsSave.Stale, repo.save(target, baseline, setOf("web")) { true })
        assertEquals(listOf("profiles.describe"), host.calls.map { it.first })
    }
    @Test fun `receipt must be strict before any named readback`() = runTest {
        for (receipt in listOf("{}", """{"ok":true}""", """{"ok":"true","applied":{"toolsets":true}}""",
            """{"ok":true,"applied":{"toolsets":"true"}}""", """{"ok":false,"applied":{"toolsets":true}}""")) {
            val host = ToolsetsTestHost().apply { answer = { _, _ -> toolsetsReply(toolsetsJson()) } }
            val repo = BotsToolsetsRepository(host); val baseline = repo.describe(target)!!
            host.calls.clear()
            host.answer = { method, _ -> toolsetsReply(if (method == "profiles.configure") receipt else toolsetsJson()) }
            assertEquals(BotToolsetsSave.Unconfirmed, repo.save(target, baseline, setOf("web")) { true })
            assertEquals(listOf("profiles.describe", "profiles.configure"), host.calls.map { it.first })
        }
    }
    @Test fun `only default off unchecked disappearance is tolerated and extra enabled is unconfirmed`() = runTest {
        for ((before, after, success) in listOf(
            Triple(toolsetsJson().replace("terminal", "spotify"), """{"name":"worker","toolsets_pinned":true,"toolsets":[{"name":"web","enabled":true}]}""", true),
            Triple(toolsetsJson(), """{"name":"worker","toolsets_pinned":true,"toolsets":[{"name":"web","enabled":true}]}""", false),
            Triple(toolsetsJson(), toolsetsJson(pinned = true, b = true), false),
            Triple(toolsetsJson(), toolsetsJson(), false),
            Triple(toolsetsJson(), toolsetsJson(pinned = true).replace("terminal", "new-enabled").replace("\"enabled\":false", "\"enabled\":true"), false)
        )) {
            var wrote = false
            val host = ToolsetsTestHost().apply { answer = { method, _ ->
                if (method == "profiles.configure") { wrote = true; toolsetsReply("""{"ok":true,"applied":{"toolsets":true}}""") }
                else toolsetsReply(if (wrote) after else before)
            } }
            val repo = BotsToolsetsRepository(host); val baseline = repo.describe(target)!!
            assertEquals(success, repo.save(target, baseline, setOf("web")) { true } is BotToolsetsSave.Saved)
        }
    }
    @Test fun `restore sends empty array and publishes actual defaults not assumed all selected`() = runTest {
        var wrote = false
        val host = ToolsetsTestHost().apply { answer = { method, _ ->
            if (method == "profiles.configure") { wrote = true; toolsetsReply("""{"ok":true,"applied":{"toolsets":true}}""") }
            else toolsetsReply(toolsetsJson(pinned = !wrote, a = !wrote, b = wrote))
        } }
        val repo = BotsToolsetsRepository(host); val baseline = repo.describe(target)!!
        val result = repo.restoreDefaults(target, baseline) { true } as BotToolsetsSave.Saved
        assertFalse(result.actual.pinned); assertEquals(setOf("terminal"), result.actual.enabled)
        assertEquals(Json.parseToJsonElement("""{"name":"worker","enabled_toolsets":[]}"""), host.calls.first { it.first == "profiles.configure" }.second)
    }
    @Test fun `describe requires exact name strict booleans and unique nonblank rows`() = runTest {
        val valid = toolsetsJson()
        val host = ToolsetsTestHost()
        val repo = BotsToolsetsRepository(host)
        for (bad in listOf(valid.replace("worker", "other"), valid.replace("\"toolsets_pinned\":false,", ""),
            valid.replace("\"toolsets_pinned\":false", "\"toolsets_pinned\":\"false\""), valid.replace("\"enabled\":true", "\"enabled\":\"true\""),
            valid.replace("terminal", "web"), valid.replace("terminal", " "), valid.replace("terminal", " terminal"))) {
            host.answer = { _, _ -> toolsetsReply(bad) }
            assertNull(bad, repo.describe(target))
        }
        host.answer = { _, _ -> toolsetsReply(valid) }
        val read = repo.describe(target)!!
        assertFalse(read.pinned)
        assertEquals(listOf("web", "terminal"), read.rows.map { it.name })
        assertEquals(setOf("web"), read.enabled)
        assertTrue(host.calls.all { it.second == Json.parseToJsonElement("""{"name":"worker"}""") })
    }
}
