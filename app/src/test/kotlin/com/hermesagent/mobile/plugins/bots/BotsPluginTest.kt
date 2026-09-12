package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.BundledPlugins
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostEvent
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.plugins.PluginNotificationInput
import com.hermesagent.mobile.plugins.PluginOs
import com.hermesagent.mobile.plugins.PluginRest
import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import com.hermesagent.mobile.plugins.PluginSocket
import com.hermesagent.mobile.plugins.PluginStorage
import com.hermesagent.mobile.plugins.createPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin's wiring: the areas it contributes to, the ids it registers
 * under, and its membership in the bundled roster.
 */
class BotsPluginTest {

    private class StubHost : PluginHost {
        override suspend fun request(method: String, params: JsonObject): PluginHostResult =
            PluginHostResult.Success(Json.parseToJsonElement("""{"profiles": []}"""))

        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }

    @Test
    fun `the plugin declares its own identity and ships enabled`() {
        val plugin = BotsPlugin()

        assertEquals("bots", plugin.id)
        assertEquals("Bots", plugin.name)
        assertEquals("Bot roster", plugin.description)
        assertTrue(plugin.defaultEnabled)
    }

    @Test
    fun `the plugin is in the bundled roster so it is discovered`() {
        assertTrue(BundledPlugins.ALL.any { it.id == "bots" })
    }

    @Test
    fun `register contributes a route and a sidebar entry`() = runTest {
        val registry = ContributionRegistry()
        val plugin = BotsPlugin(scope = backgroundScope)
        val disposers = mutableListOf<() -> Unit>()
        val ctx = createPluginContext(
            pluginId = plugin.id,
            registry = registry,
            rest = object : PluginRest {
                override suspend fun execute(
                    pluginId: String,
                    path: String,
                    options: PluginRestOptions,
                ): PluginRestResult = PluginRestResult.Success(200, "{}".toByteArray())
            },
            socket = object : PluginSocket {
                override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
            },
            storage = object : PluginStorage {
                override suspend fun get(key: String, fallback: String?): String? = fallback
                override suspend fun set(key: String, value: String) {}
                override suspend fun remove(key: String) {}
            },
            os = object : PluginOs {
                override fun notify(input: PluginNotificationInput) {}
                override suspend fun openExternal(url: String): Boolean = true
                override suspend fun writeClipboard(text: String): Boolean = true
                override suspend fun share(text: String, title: String?): Boolean = true
            },
            host = StubHost(),
            onDispose = { disposers.add(it) },
        )

        plugin.register(ctx)

        val routes = registry.getArea(PluginAreas.ROUTES_AREA)
        assertEquals(1, routes.size)
        val route = routes.single()
        assertEquals("bots:route", route.id)
        assertEquals(PluginAreas.ROUTES_AREA, route.area)
        assertEquals("plugin:bots", route.source)
        assertEquals("Bots roster", route.title)
        assertNotNull(route.render)

        val sidebarNav = registry.getArea(PluginAreas.SIDEBAR_NAV_AREA)
        assertEquals(1, sidebarNav.size)
        val nav = sidebarNav.single()
        assertEquals("bots:sidebar-nav", nav.id)
        assertEquals(PluginAreas.SIDEBAR_NAV_AREA, nav.area)
        assertEquals("plugin:bots", nav.source)
        assertEquals("Bots", nav.title)
        assertEquals(400, nav.order)
        assertNotNull(nav.render)

        disposers.forEach { it.invoke() }
        assertEquals(emptyList<Any>(), registry.getArea(PluginAreas.ROUTES_AREA))
        assertEquals(emptyList<Any>(), registry.getArea(PluginAreas.SIDEBAR_NAV_AREA))
    }

    @Test
    fun `the plugin declares no mutable global state`() {
        // The regression this guards: a `companion object var defaultConnection`
        // makes two plugin instances share one connection, and makes every test
        // that touches it order-dependent.
        val targets = buildList {
            add(BotsPlugin::class.java)
            runCatching { add(Class.forName("${BotsPlugin::class.java.name}\$Companion")) }
        }
        val mutableStatics = targets
            .flatMap { it.declaredFields.toList() }
            .filter { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) &&
                    !java.lang.reflect.Modifier.isFinal(field.modifiers)
            }

        assertEquals(emptyList<java.lang.reflect.Field>(), mutableStatics)
    }
}
