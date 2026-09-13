package com.hermesagent.mobile.plugins.kanban

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KanbanPluginTest {
    @Test fun `bundled plugin contributes route and settings entry then disposes both`() = runTest {
        val registry = ContributionRegistry(); val disposers = mutableListOf<() -> Unit>(); val plugin = KanbanPlugin()
        plugin.register(createPluginContext(plugin.id, registry, Rest, Socket, Storage, Os, Host, onDispose = { disposers += it }))
        assertTrue(BundledPlugins.ALL.any { it.id == "kanban" })
        assertEquals(listOf("kanban:route"), registry.getArea(PluginAreas.ROUTES_AREA).map { it.id })
        assertEquals(listOf("kanban:sidebar-nav"), registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).map { it.id })
        disposers.forEach { it() }
        assertTrue(registry.getArea(PluginAreas.ROUTES_AREA).isEmpty()); assertTrue(registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).isEmpty())
    }
    private object Rest : PluginRest { override suspend fun execute(pluginId: String, path: String, options: PluginRestOptions) = PluginRestResult.Success(200, """{"columns":[]}""".toByteArray()) }
    private object Socket : PluginSocket { override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit) = {} }
    private object Storage : PluginStorage { override suspend fun get(key: String, fallback: String?) = fallback; override suspend fun set(key: String, value: String) {}; override suspend fun remove(key: String) {} }
    private object Os : PluginOs { override fun notify(input: PluginNotificationInput) {}; override suspend fun openExternal(url: String) = false; override suspend fun writeClipboard(text: String) = false; override suspend fun share(text: String, title: String?) = false }
    private object Host : PluginHost { override val connected = MutableStateFlow(false); override val endpointGeneration = MutableStateFlow(0L); override suspend fun request(method: String, params: JsonObject) = PluginHostResult.UnavailableOnGateway; override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit) = {} }
}
