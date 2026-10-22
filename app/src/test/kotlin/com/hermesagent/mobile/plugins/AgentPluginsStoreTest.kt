package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Native rows and collision behavior follow upstream plugins-tab.test.tsx:357-459 and
 * tests/plugins/test_plugins_manage_profile_scope.py:13-74 @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa.
 * The held dispatch tests exercise the real GatewayPluginHost, not a permissive host fake.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentPluginsStoreTest {
    @Test fun explicitProfileKeyOnlyToggleAndIndependentReadback() = runTest {
        val rpc = Rpc()
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val store = AgentPluginsStore(host, "research")
        store.refresh()
        store.toggle("image_gen/fal", true)
        assertEquals(listOf("list", "toggle", "list"), rpc.calls.map { it["action"]?.jsonPrimitive?.content })
        assertTrue(rpc.calls.all { it["profile"] == JsonPrimitive("research") })
        val toggle = rpc.calls[1]
        assertEquals(setOf("action", "profile", "key", "enable"), toggle.keys)
        assertEquals(JsonPrimitive("image_gen/fal"), toggle["key"])
        assertEquals(JsonPrimitive(true), toggle["enable"])
        assertTrue(store.state.value.rows.first().enabled)
        assertFalse(store.state.value.rows.last().enabled)
        assertFalse(store.state.value.unconfirmed)
        store.toggle("image_gen/fal", false)
        assertFalse(store.state.value.rows.first().enabled)
        assertEquals(JsonPrimitive(false), rpc.calls[3]["enable"])
        assertEquals("list", rpc.calls.last()["action"]?.jsonPrimitive?.content)
        assertFalse(store.state.value.unconfirmed)
    }

    @Test fun defaultIsExplicitEvenOnNamedLaunch() = runTest {
        val rpc = Rpc()
        val store = AgentPluginsStore(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc)), "default")
        store.refresh()
        assertEquals(JsonPrimitive("default"), rpc.calls.single()["profile"])
    }

    @Test fun receiptCannotStandInForReadback() = runTest {
        val rpc = Rpc().apply { applyToggle = false }
        val store = AgentPluginsStore(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc)), "work")
        store.refresh(); store.toggle("image_gen/fal", true)
        assertTrue(store.state.value.unconfirmed)
        assertFalse(store.state.value.rows.first().enabled)
        store.toggle("image_gen/fal", true)
        assertEquals(3, rpc.calls.size)
        store.refresh()
        assertFalse(store.state.value.unconfirmed)
    }

    @Test fun missingOrWrongKeyReadbackIsUnconfirmed() = runTest {
        val rpc = Rpc().apply { afterToggle = inventory(row("video_gen/fal", "enabled")) }
        val store = AgentPluginsStore(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc)), "work")
        store.refresh(); store.toggle("image_gen/fal", true)
        assertTrue(store.state.value.unconfirmed)
        assertEquals("video_gen/fal", store.state.value.rows.single().key)
    }

    @Test fun keylessRowsNeverUseNameFallback() = runTest {
        val rpc = Rpc().apply { initial = inventory(row(null, "disabled")) }
        val store = AgentPluginsStore(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc)), "work")
        store.refresh(); store.toggle("fal", true)
        assertNull(store.state.value.rows.single().key)
        assertEquals(1, rpc.calls.size)
    }

    @Test fun unsupportedAndMalformedInventoryAreNotEmptySuccess() = runTest {
        val rpc = Rpc().apply { unavailable = true }
        val store = AgentPluginsStore(GatewayPluginHost(backgroundScope, MutableStateFlow(rpc)), "work")
        store.refresh()
        assertEquals(AgentPluginsPhase.Unavailable, store.state.value.phase)
        rpc.unavailable = false; rpc.initial = buildJsonObject { put("plugins", JsonNull) }
        store.refresh()
        assertEquals(AgentPluginsPhase.Error, store.state.value.phase)
        rpc.initial = inventory(row("same", "enabled"), row("same", "disabled"))
        store.refresh()
        assertEquals(AgentPluginsPhase.Error, store.state.value.phase)
    }

    @Test fun curationMatchesDesktopAndOnlyDisplayMetadataSurvives() {
        val rows = parseAgentPlugins(inventory(
            row("platforms/example", "enabled", "bundled"), row("disk-cleanup", "enabled", "bundled"),
            row("security-guidance", "disabled", "bundled"), row("unknown-builtin", "enabled", "bundled"),
            row("dashboard_auth/custom", "enabled"), row("model-providers/custom", "enabled"),
            row("mine", "not enabled"), row(null, "disabled"),
        ))
        assertEquals(listOf("disk-cleanup", "security-guidance", "mine", null), rows.map { it.key })
        assertEquals("1.0", rows.first().version)
        assertTrue(rows.first().portable)
        assertEquals("Reviewed", rows.first().catalogTier)
        assertFalse(rows[2].enabled)
    }

    @Test fun disposedOwnerOrProfileSwitchBeforeQueuedDispatchSendsNothing() = runTest {
        for (close in listOf(false, true)) {
            val rpc = Rpc()
            val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
            val owner = AgentPluginsOwner()
            val store = owner.open(host, "work")
            store.refresh()
            val gate = CompletableDeferred<Unit>()
            rpc.before = { gate.await() }
            val pending = async { store.toggle("image_gen/fal", true) }
            runCurrent()
            assertEquals("image_gen/fal", store.state.value.busyKey)
            if (close) store.close() else owner.open(host, "other")
            gate.complete(Unit); pending.await()
            assertEquals(1, rpc.calls.size)
        }
    }

    @Test fun sameEndpointReconnectBeforeDispatchCannotSendToEitherLeg() = runTest {
        val rpc = Rpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val host = GatewayPluginHost(backgroundScope, clients)
        val store = AgentPluginsStore(host, "work")
        store.refresh()
        val gate = CompletableDeferred<Unit>()
        rpc.before = { gate.await() }
        val pending = async { store.toggle("image_gen/fal", true) }
        runCurrent()
        val replacement = Rpc()
        clients.value = replacement // no observer yield before releasing dispatch
        gate.complete(Unit); pending.await()
        assertEquals(1, rpc.calls.size)
        assertTrue(replacement.calls.isEmpty())
    }

    @Test fun endpointChangeBeforeDispatchCannotMutateOldOrNewHost() = runTest {
        val rpc = Rpc()
        val endpoint = MutableStateFlow(0L)
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc), endpoint)
        val store = AgentPluginsStore(host, "work")
        store.refresh()
        val gate = CompletableDeferred<Unit>()
        rpc.before = { gate.await() }
        val pending = async { store.toggle("image_gen/fal", true) }
        runCurrent(); endpoint.value = 1L; gate.complete(Unit); pending.await()
        assertEquals(1, rpc.calls.size)
    }

    @Test fun lateListCannotPublishIntoRevokedOwner() = runTest {
        val rpc = Rpc()
        val gate = CompletableDeferred<Unit>()
        rpc.answerGate = { gate.await() }
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val store = AgentPluginsStore(host, "work")
        val pending = async { store.refresh() }
        runCurrent(); store.close(); gate.complete(Unit); pending.await()
        assertTrue(store.state.value.rows.isEmpty())
    }

    @Test fun busyStoreRejectsDuplicateMutationAndRefresh() = runTest {
        val rpc = Rpc()
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val store = AgentPluginsStore(host, "work")
        store.refresh()
        val gate = CompletableDeferred<Unit>()
        rpc.answerGate = { gate.await() }
        val pending = async { store.toggle("image_gen/fal", true) }
        runCurrent()
        store.toggle("image_gen/fal", true); store.refresh()
        assertEquals(2, rpc.calls.size)
        gate.complete(Unit); pending.await()
        assertEquals(3, rpc.calls.size)
        assertNull(store.state.value.busyKey)
    }

    private class Rpc : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        val calls = mutableListOf<JsonObject>()
        var enabled = false
        var applyToggle = true
        var unavailable = false
        var initial: JsonElement? = null
        var afterToggle: JsonElement? = null
        var before: suspend () -> Unit = {}
        var answerGate: suspend () -> Unit = {}
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("unguarded request")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            before()
            if (!dispatch { calls += params; true }) throw GatewayRpcException("revoked")
            assertEquals("plugins.manage", method)
            answerGate()
            if (unavailable) throw GatewayRpcError(-32601, "unsupported")
            return if (params["action"] == JsonPrimitive("toggle")) {
                if (applyToggle) enabled = params["enable"] == JsonPrimitive(true)
                buildJsonObject { put("ok", true); put("plugin", row("image_gen/fal", "enabled")) }
            } else afterToggle?.takeIf { calls.any { it["action"] == JsonPrimitive("toggle") } }
                ?: initial ?: inventory(row("image_gen/fal", if (enabled) "enabled" else "disabled"), row("video_gen/fal", "disabled"))
        }
        override fun close() {}
    }

    companion object {
        private fun inventory(vararg rows: JsonObject) = buildJsonObject { put("plugins", JsonArray(rows.toList())) }
        private fun row(key: String?, status: String, source: String = "user") = buildJsonObject {
            put("name", "fal"); if (key != null) put("key", key)
            put("status", status); put("source", source); put("version", "1.0")
            put("description", "Synthetic plugin"); put("portable", true)
            put("catalog_name", "Example"); put("catalog_tier", "Reviewed")
            put("settings_schema", JsonArray(emptyList())); put("install_dir", "/synthetic/not-retained")
        }
    }
}
