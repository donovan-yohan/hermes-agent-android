package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSdkTest {

    private class TestDecisionStore(initial: Map<String, Boolean> = emptyMap()) : PluginDecisionStore {
        private val _decisions = MutableStateFlow(initial)
        override val pluginDecisions: Flow<Map<String, Boolean>> = _decisions.asStateFlow()

        override suspend fun savePluginDecision(id: String, enabled: Boolean) {
            _decisions.value = _decisions.value + (id to enabled)
        }
    }

    private class TestKeyValueStore : PluginKeyValueStore {
        val map = mutableMapOf<String, String>()
        override suspend fun read(scopedKey: String): String? = map[scopedKey]
        override suspend fun write(scopedKey: String, value: String?) {
            if (value == null) map.remove(scopedKey) else map[scopedKey] = value
        }
    }

    private class TestPluginOs : PluginOs {
        val notifications = mutableListOf<PluginNotificationInput>()
        var externalUrl: String? = null
        var clipboardText: String? = null
        var sharedText: String? = null

        override fun notify(input: PluginNotificationInput) {
            notifications.add(input)
        }

        override suspend fun openExternal(url: String): Boolean {
            externalUrl = url
            return true
        }

        override suspend fun writeClipboard(text: String): Boolean {
            clipboardText = text
            return true
        }

        override suspend fun share(text: String, title: String?): Boolean {
            sharedText = text
            return true
        }
    }

    @Test
    fun `id namespacing and source stamping apply to contributions registered via context`() {
        val registry = ContributionRegistry()
        val rest = GatewayPluginRest { null }
        val socket = GatewayPluginSocket { true }
        val storage = ScopedPluginStorage("my-plugin", TestKeyValueStore())
        val os = TestPluginOs()

        val ctx = createPluginContext(
            pluginId = "my-plugin",
            registry = registry,
            rest = rest,
            socket = socket,
            storage = storage,
            os = os,
        )

        assertEquals("plugin:my-plugin", ctx.source)

        ctx.register(
            PluginContribution(
                id = "item-1",
                area = PluginAreas.Composer.TOP,
                title = "My Banner",
            )
        )

        val contributions = registry.getArea(PluginAreas.Composer.TOP)
        assertEquals(1, contributions.size)
        assertEquals("my-plugin:item-1", contributions[0].id)
        assertEquals("plugin:my-plugin", contributions[0].source)
        assertEquals("My Banner", contributions[0].title)
    }

    @Test
    fun `disposer removes contributions registered individually or in batch`() {
        val registry = ContributionRegistry()
        val rest = GatewayPluginRest { null }
        val socket = GatewayPluginSocket { true }
        val storage = ScopedPluginStorage("test-plugin", TestKeyValueStore())
        val os = TestPluginOs()

        val ctx = createPluginContext(
            pluginId = "test-plugin",
            registry = registry,
            rest = rest,
            socket = socket,
            storage = storage,
            os = os,
        )

        val disposeSingle = ctx.register(
            PluginContribution(id = "single", area = "custom-area")
        )
        assertEquals(1, registry.getArea("custom-area").size)

        disposeSingle()
        assertEquals(0, registry.getArea("custom-area").size)

        val disposeMany = ctx.registerMany(
            listOf(
                PluginContribution(id = "batch-1", area = "batch-area"),
                PluginContribution(id = "batch-2", area = "batch-area"),
            )
        )
        assertEquals(2, registry.getArea("batch-area").size)

        disposeMany()
        assertEquals(0, registry.getArea("batch-area").size)
    }

    @Test
    fun `plugin whose register throws becomes status Error and loader continues next plugin`() = runTest {
        val registry = ContributionRegistry()
        val decisionStore = TestDecisionStore()
        val store = PluginStore(backgroundScope, decisionStore)
        val rest = GatewayPluginRest { null }
        val socket = GatewayPluginSocket { true }
        val kv = TestKeyValueStore()

        val loader = PluginLoader(
            registry = registry,
            store = store,
            rest = rest,
            socket = socket,
            storageFactory = { id -> ScopedPluginStorage(id, kv) },
            osFactory = { TestPluginOs() },
        )

        val faultyPlugin = object : HermesPlugin {
            override val id = "faulty"
            override val name = "Faulty Plugin"
            override fun register(ctx: PluginContext) {
                error("Boom! Registration failed.")
            }
        }

        val healthyPlugin = object : HermesPlugin {
            override val id = "healthy"
            override val name = "Healthy Plugin"
            override fun register(ctx: PluginContext) {
                ctx.register(PluginContribution(id = "nav", area = PluginAreas.SIDEBAR_NAV_AREA))
            }
        }

        loader.discover(listOf(faultyPlugin, healthyPlugin))

        val records = store.records.value
        assertEquals(PluginStatus.Error, records["faulty"]?.status)
        assertTrue(records["faulty"]?.error?.contains("Boom! Registration failed.") == true)

        assertEquals(PluginStatus.Loaded, records["healthy"]?.status)
        assertEquals(1, registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).size)
        assertEquals("healthy:nav", registry.getArea(PluginAreas.SIDEBAR_NAV_AREA)[0].id)
    }

    @Test
    fun `disabling a plugin cancels the scope its host door runs on`() = runTest {
        val store = PluginStore(backgroundScope, TestDecisionStore())
        var scope: CoroutineScope? = null

        val loader = PluginLoader(
            registry = ContributionRegistry(),
            store = store,
            rest = GatewayPluginRest { null },
            socket = GatewayPluginSocket { true },
            storageFactory = { id -> ScopedPluginStorage(id, TestKeyValueStore()) },
            osFactory = { TestPluginOs() },
            hostFactory = { created ->
                scope = created
                // The door itself is not under test here — only the lifecycle
                // the loader gives it.
                object : PluginHost {
                    override suspend fun request(method: String, params: JsonObject): PluginHostResult =
                        PluginHostResult.Refused(0, "unused")

                    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
                }
            },
        )

        loader.discover(
            listOf(
                object : HermesPlugin {
                    override val id = "hosted"
                    override fun register(ctx: PluginContext) {}
                }
            )
        )

        val activated = scope
        assertNotNull("an enabled plugin is given a host scope", activated)
        assertTrue("that scope is live while the plugin is loaded", activated!!.isActive)

        store.setPluginEnabled("hosted", false)
        assertFalse("disabling the plugin cancels the scope its requests run on", activated.isActive)
    }

    @Test
    fun `pluginActive respects default-enabled and persisted decisions`() = runTest {
        val decisionStore = TestDecisionStore(mapOf("explicit-off" to false, "explicit-on" to true))
        val store = PluginStore(backgroundScope, decisionStore, initialDecisions = mapOf("explicit-off" to false, "explicit-on" to true))

        // No decision recorded: falls back to defaultEnabled
        assertTrue(store.pluginActive("opt-in", defaultEnabled = true))
        assertFalse(store.pluginActive("opt-in", defaultEnabled = false))

        // Explicit decisions override defaultEnabled
        assertFalse(store.pluginActive("explicit-off", defaultEnabled = true))
        assertTrue(store.pluginActive("explicit-on", defaultEnabled = false))

        // Live toggling
        var activated = false
        var deactivated = false
        val handle = object : PluginHandle {
            override suspend fun activate() { activated = true }
            override fun deactivate() { deactivated = true }
        }

        store.publishPlugin(
            PluginRecord(id = "toggle-plugin", name = "Toggle", status = PluginStatus.Disabled),
            handle = handle,
        )

        store.setPluginEnabled("toggle-plugin", true)
        assertTrue(activated)
        assertTrue(store.pluginActive("toggle-plugin"))
        assertEquals(PluginStatus.Loaded, store.records.value["toggle-plugin"]?.status)

        store.setPluginEnabled("toggle-plugin", false)
        assertTrue(deactivated)
        assertFalse(store.pluginActive("toggle-plugin"))
        assertEquals(PluginStatus.Disabled, store.records.value["toggle-plugin"]?.status)
    }

    @Test
    fun `normalizePluginPathSuffix rejects traversal and REST maps 404 to UnavailableOnGateway`() = runTest {
        // Path traversal rejection
        assertThrows(IllegalArgumentException::class.java) {
            normalizePluginPathSuffix("Test", "../secrets")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizePluginPathSuffix("Test", "api/../admin")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizePluginPathSuffix("Test", "/foo/bar/../../etc/passwd")
        }

        assertEquals("/board", normalizePluginPathSuffix("Test", "board"))
        assertEquals("/board?filter=active", normalizePluginPathSuffix("Test", "/board?filter=active"))

        // Mock 404 from GatewayHttp (e.g. disabled plugin runtime gate)
        val mock404Http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                return GatewayHttpResult.Rejected(404, "Not Found")
            }
        }

        val rest404 = GatewayPluginRest { mock404Http }
        val result404 = rest404.execute("test-plugin", "/status")
        assertEquals(PluginRestResult.UnavailableOnGateway, result404)

        // Mock 200 Success
        val mock200Http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                assertEquals("api/plugins/test-plugin/status", request.path)
                return GatewayHttpResult.Success(200, "{\"ok\":true}".toByteArray())
            }
        }

        val rest200 = GatewayPluginRest { mock200Http }
        val result200 = rest200.execute("test-plugin", "/status")
        assertTrue(result200 is PluginRestResult.Success)
        assertEquals(200, (result200 as PluginRestResult.Success).statusCode)
        assertEquals("{\"ok\":true}", String(result200.bodyBytes))
    }

    @Test
    fun `GatewayHttpResult Rejected with status 404 and non-empty envelope maps to PluginRestResult Refused`() = runTest {
        val mock404WithEnvelope = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                return GatewayHttpResult.Rejected(
                    statusCode = 404,
                    safeMessage = "Not Found",
                    envelopeBytes = """{"error":"channel_not_found"}""".toByteArray(),
                )
            }
        }

        val rest = GatewayPluginRest { mock404WithEnvelope }
        val result = rest.execute("test-plugin", "/channels/c99")
        assertTrue(result is PluginRestResult.Refused)
        val refused = result as PluginRestResult.Refused
        assertEquals(404, refused.statusCode)
        assertEquals("Not Found", refused.safeMessage)
        assertEquals("""{"error":"channel_not_found"}""", String(refused.envelopeBytes))
    }


    @Test
    fun `socket on OAuth leg returns no-op disposer and rejects traversal`() {
        val socket = GatewayPluginSocket(isOAuthLeg = { true })

        assertThrows(IllegalArgumentException::class.java) {
            socket.connect("test-plugin", "../escape") {}
        }

        val disposer = socket.connect("test-plugin", "/events") {}
        assertNotNull(disposer)
        // Disposer executes without throwing or failing
        disposer()
    }

    @Test
    fun `context wires the host door and disposal drops its locale bundles`() = runTest {
        val locales = PluginLocaleRegistry(activeLocale = { "en" })
        val called = mutableListOf<String>()
        val host = object : PluginHost {
            override suspend fun request(method: String, params: JsonObject): PluginHostResult {
                called.add(method)
                return PluginHostResult.Success(JsonPrimitive(method))
            }

            override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
        }
        val disposed = mutableListOf<() -> Unit>()

        val ctx = createPluginContext(
            pluginId = "my-plugin",
            registry = ContributionRegistry(),
            rest = GatewayPluginRest { null },
            socket = GatewayPluginSocket { true },
            storage = ScopedPluginStorage("my-plugin", TestKeyValueStore()),
            os = TestPluginOs(),
            host = host,
            locales = locales,
            onDispose = { disposed.add(it) },
        )

        assertEquals("plugin:my-plugin", ctx.source)
        assertSame("the host door is the one the loader built", host, ctx.host)

        ctx.host.request("bot_relay.deliver")
        assertEquals(listOf("bot_relay.deliver"), called)

        ctx.i18n.register(mapOf("en" to mapOf("title" to "Board")))
        assertEquals("Board", ctx.i18n.t("title"))

        // Disabling the plugin runs every tracked disposer, i18n bundles included.
        disposed.forEach { it() }
        assertEquals("title", ctx.i18n.t("title"))
    }

    @Test
    fun `unsupported or custom area registers cleanly without throwing`() {
        val registry = ContributionRegistry()

        registry.register(
            Contribution(
                id = "pane-1",
                area = PluginAreas.PANES_AREA,
                title = "Custom Pane",
                data = "test-pane-data",
            )
        )

        registry.register(
            Contribution(
                id = "theme-1",
                area = PluginAreas.THEMES_AREA,
                title = "User Theme",
                data = "test-theme-data",
            )
        )

        registry.register(
            Contribution(
                id = "unknown-1",
                area = "arbitrary.future.area",
                title = "Future Extensibility",
            )
        )

        val panes = registry.getArea(PluginAreas.PANES_AREA)
        assertEquals(1, panes.size)
        assertEquals("pane-1", panes[0].id)
        assertEquals("test-pane-data", panes[0].data)

        val themes = registry.getArea(PluginAreas.THEMES_AREA)
        assertEquals(1, themes.size)
        assertEquals("theme-1", themes[0].id)

        val unknowns = registry.getArea("arbitrary.future.area")
        assertEquals(1, unknowns.size)
        assertEquals("unknown-1", unknowns[0].id)
    }

    @Test
    fun `PluginStorage scopes keys and separates plugins`() = runTest {
        val kv = TestKeyValueStore()
        val storageA = ScopedPluginStorage("plugin-a", kv)
        val storageB = ScopedPluginStorage("plugin-b", kv)

        storageA.set("count", "42")
        storageB.set("count", "99")

        assertEquals("42", storageA.get("count"))
        assertEquals("99", storageB.get("count"))
        assertEquals("42", kv.read("hermes.plugin.plugin-a.count"))
        assertEquals("99", kv.read("hermes.plugin.plugin-b.count"))

        storageA.remove("count")
        assertNull(storageA.get("count"))
        assertEquals("99", storageB.get("count"))
        assertEquals("fallback", storageA.get("count", fallback = "fallback"))
    }

    @Test
    fun `PluginDecisionsCodec round trips decisions map`() {
        val map = mapOf("plugin-a" to true, "plugin-b" to false)
        val encoded = PluginDecisionsCodec.encode(map)
        val decoded = PluginDecisionsCodec.decode(encoded)

        assertEquals(map, decoded)
        assertEquals(emptyMap<String, Boolean>(), PluginDecisionsCodec.decode(null))
        assertEquals(emptyMap<String, Boolean>(), PluginDecisionsCodec.decode(""))
        assertEquals(emptyMap<String, Boolean>(), PluginDecisionsCodec.decode("invalid-json"))
    }
}
