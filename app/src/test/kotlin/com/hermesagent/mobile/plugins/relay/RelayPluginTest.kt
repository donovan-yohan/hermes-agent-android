package com.hermesagent.mobile.plugins.relay

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginLoader
import com.hermesagent.mobile.plugins.PluginNotificationInput
import com.hermesagent.mobile.plugins.PluginOs
import com.hermesagent.mobile.plugins.PluginRest
import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import com.hermesagent.mobile.plugins.PluginSocket
import com.hermesagent.mobile.plugins.PluginStorage
import com.hermesagent.mobile.plugins.PluginStore
import com.hermesagent.mobile.plugins.createPluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPluginTest {

    @Test
    fun `plugin declares canonical metadata and default enabled`() {
        val plugin = RelayPlugin()
        assertEquals("hermes-plugin-relay", plugin.id)
        assertEquals("Relay", plugin.name)
        assertEquals("Relay workspace for channels and messaging", plugin.description)
        assertTrue(plugin.defaultEnabled)
    }

    @Test
    fun `register contributes routes and sidebar navigation areas`() = runTest {
        val registry = ContributionRegistry()
        val pluginScope = CoroutineScope(SupervisorJob())
        val plugin = RelayPlugin(
            connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            configured = MutableStateFlow(true),
            scope = pluginScope,
        )
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
            onDispose = { disposers.add(it) },
        )

        plugin.register(ctx)

        val routes = registry.getArea(PluginAreas.ROUTES_AREA)
        assertEquals(1, routes.size)
        val route = routes.single()
        assertEquals("hermes-plugin-relay:route", route.id)
        assertEquals(PluginAreas.ROUTES_AREA, route.area)
        assertEquals("plugin:hermes-plugin-relay", route.source)
        assertEquals("Relay channels", route.title)
        assertNotNull(route.render)

        val sidebarNav = registry.getArea(PluginAreas.SIDEBAR_NAV_AREA)
        assertEquals(1, sidebarNav.size)
        val nav = sidebarNav.single()
        assertEquals("hermes-plugin-relay:sidebar-nav", nav.id)
        assertEquals(PluginAreas.SIDEBAR_NAV_AREA, nav.area)
        assertEquals("plugin:hermes-plugin-relay", nav.source)
        assertEquals("Relay channels", nav.title)
        assertEquals(300, nav.order)
        assertNotNull(nav.render)

        // Disposing cleans up registered contributions
        disposers.forEach { it.invoke() }
    }
}
