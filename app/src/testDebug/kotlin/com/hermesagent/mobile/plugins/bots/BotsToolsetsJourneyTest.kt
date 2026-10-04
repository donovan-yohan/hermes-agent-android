package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsToolsetsJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var pinned = false
    private var selected = setOf("web")
    private val host = object : PluginHost {
        override val endpointGeneration = MutableStateFlow(7L)
        override val connected = MutableStateFlow(true)
        override suspend fun request(method: String, params: JsonObject): PluginHostResult =
            requestAtEndpointGuarded(7L, method, params) { true }
        override suspend fun requestAtEndpoint(expectedGeneration: Long, method: String, params: JsonObject): PluginHostResult =
            requestAtEndpointGuarded(expectedGeneration, method, params) { true }
        override suspend fun requestAtEndpointGuarded(expectedGeneration: Long, method: String, params: JsonObject, dispatchAllowed: () -> Boolean): PluginHostResult {
            check(dispatchAllowed()); calls += method to params
            return PluginHostResult.Success(when (method) {
                "profiles.list" -> Json.parseToJsonElement("""{"profiles":[{"name":"worker","display_name":"Worker","ui_meta":{}}]}""")
                "model.options" -> Json.parseToJsonElement("""{"providers":[]}""")
                "profiles.describe" -> buildJsonObject {
                    put("description", ""); put("soul", "")
                    put("model", buildJsonObject { put("provider", "synthetic"); put("default", "synthetic") })
                    put("name", "worker"); put("toolsets_pinned", pinned)
                    putJsonArray("toolsets") {
                        for (name in listOf("web", "terminal")) add(buildJsonObject {
                            put("name", name); put("enabled", name in selected); put("description", "Description $name"); put("tool_count", 2)
                        })
                    }
                }
                "profiles.configure" -> {
                    val names = params.getValue("enabled_toolsets").jsonArray.map { it.jsonPrimitive.content }.toSet()
                    pinned = names.isNotEmpty(); selected = names.ifEmpty { setOf("web") }
                    Json.parseToJsonElement("""{"ok":true,"applied":{"toolsets":true}}""")
                }
                else -> error("Unexpected method")
            })
        }
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }
    @After fun close() { scope.cancel() }
    private fun launch(): BotsToolsetsViewModel {
        val vm = BotsToolsetsViewModel(host, scope, {})
        compose.setContent {
            val state by vm.state.collectAsState()
            HermesTheme(AppearanceSelection()) { Column {
                BotToolsetsEditor(state, BotToolsetsActions(
                    onToggle = { vm.toggle(state, it) }, onSave = { vm.save(state) },
                    onRestore = { vm.requestDefaults(state) }, onConfirm = { vm.confirmDefaults(state) },
                    onCancel = { vm.cancelDefaults(state) }))
            } }
        }
        compose.runOnIdle { vm.open(BotManagementTarget("worker", 7L)) }; compose.waitForIdle()
        return vm
    }
    @Test fun `registered existing bot editor writes toolsets only and keeps unsupported sections visible`() {
        val registry = ContributionRegistry()
        BotsPlugin(scope = scope).register(createPluginContext(
            pluginId = "bots", registry = registry, host = host,
            rest = object : PluginRest {
                override suspend fun execute(pluginId: String, path: String, options: PluginRestOptions) = PluginRestResult.Success(200, "{}".toByteArray())
            }, socket = object : PluginSocket {
                override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
            }, storage = object : PluginStorage {
                override suspend fun get(key: String, fallback: String?) = fallback
                override suspend fun set(key: String, value: String) {}
                override suspend fun remove(key: String) {}
            }, os = object : PluginOs {
                override fun notify(input: PluginNotificationInput) {}
                override suspend fun openExternal(url: String) = false
                override suspend fun writeClipboard(text: String) = false
                override suspend fun share(text: String, title: String?) = false
            }))
        val route = registry.getArea(PluginAreas.ROUTES_AREA).single { it.id.endsWith(":route") }
        compose.setContent { HermesTheme(AppearanceSelection()) { route.render!!.invoke() } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for Worker").performClick()
        compose.onNodeWithText("Edit…").performClick(); compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Expand))
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand)
        compose.onNodeWithContentDescription("Display name").performScrollTo().performTextReplacement("Unsaved identity")
        compose.onNodeWithContentDescription("Model ID").performScrollTo().performTextReplacement("unsaved-model")
        compose.onNodeWithText("Save model").assertIsEnabled()
        compose.onNodeWithContentDescription("Toolset terminal").performScrollTo().assertIsOff().performClick()
        assertFalse(calls.any { it.first == "profiles.configure" })
        compose.onNodeWithText("Save toolsets").performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Toolsets saved.").performScrollTo().assertIsDisplayed()
        assertEquals(setOf("name", "enabled_toolsets"), calls.single { it.first == "profiles.configure" }.second.keys)
        compose.onNodeWithContentDescription("Display name").performScrollTo().assertTextEquals("Unsaved identity")
        compose.onNodeWithContentDescription("Model ID").performScrollTo().assertTextEquals("unsaved-model")
        compose.onNodeWithText("Save model").assertIsEnabled()
        assertFalse(calls.any { it.first == "profiles.set_asset" })
        compose.onNodeWithText("Skills").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MCP servers").performScrollTo().assertIsDisplayed()
    }
    @Test fun `checkbox selection has no write until separate save and all selected pins`() {
        launch()
        compose.onNodeWithContentDescription("Toolset web").assertIsOn()
        compose.onNodeWithContentDescription("Toolset terminal").assertIsOff().performClick()
        compose.onNodeWithText("2 of 2 enabled").assertIsDisplayed()
        assertFalse(calls.any { it.first == "profiles.configure" })
        compose.onNodeWithText("Save toolsets").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Toolsets saved.").assertIsDisplayed()
        assertTrue(pinned)
        assertEquals(setOf("name", "enabled_toolsets"), calls.single { it.first == "profiles.configure" }.second.keys)
    }
    @Test fun `zero selection cannot save and restore requires explicit confirmation`() {
        pinned = true; launch()
        compose.onNodeWithContentDescription("Toolset web").performClick()
        compose.onNodeWithText("Save toolsets").assertIsNotEnabled()
        compose.onNodeWithText("Restore defaults").performClick()
        assertFalse(calls.any { it.first == "profiles.configure" })
        compose.onNodeWithText("Cancel restore").performClick()
        assertTrue(pinned)
        compose.onNodeWithText("Restore defaults").performClick()
        compose.onNodeWithText("Confirm restore defaults").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Defaults restored.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Toolset web").assertIsOn()
        compose.onNodeWithContentDescription("Toolset terminal").assertIsOff()
        assertFalse(pinned)
    }
}
