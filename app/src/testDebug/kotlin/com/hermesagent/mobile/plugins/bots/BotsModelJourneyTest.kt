package com.hermesagent.mobile.plugins.bots

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
class BotsModelJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val endpoint = MutableStateFlow(7L)
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var selection = BotModelSelection("custom", "original")
    private val host = object : PluginHost {
        override val endpointGeneration = endpoint
        override val connected = MutableStateFlow(true)
        override suspend fun request(method: String, params: JsonObject): PluginHostResult {
            calls += method to params
            return PluginHostResult.Success(when (method) {
                "profiles.list" -> Json.parseToJsonElement("""{"profiles":[{"name":"worker","display_name":"Worker","ui_meta":{}}]}""")
                "profiles.describe" -> buildJsonObject {
                    put("name", "worker"); put("description", ""); put("soul", "")
                    put("model", buildJsonObject { put("provider", selection.provider); put("default", selection.model) })
                }
                "model.options" -> Json.parseToJsonElement("""{"providers":[{"slug":"catalog","name":"Catalog","models":["guarded"]}]}""")
                "profiles.configure" -> if (params["confirm_expensive_model"] != JsonPrimitive(true))
                    Json.parseToJsonElement("""{"ok":true,"confirm_required":true,"confirm_message":"Review cost before saving"}""")
                else {
                    selection = BotModelSelection(params.getValue("provider").jsonPrimitive.content, params.getValue("model").jsonPrimitive.content)
                    Json.parseToJsonElement("""{"ok":true,"applied":{"model":true}}""")
                }
                else -> error("Unexpected $method")
            })
        }
        override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    }
    @After fun close() { scope.cancel() }

    private fun launch() {
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
            },
        ))
        val route = registry.getArea(PluginAreas.ROUTES_AREA).single { it.id.endsWith(":route") }
        compose.setContent { HermesTheme(AppearanceSelection()) { route.render!!.invoke() } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for Worker").performClick()
        compose.onNodeWithText("Edit…").performClick()
        compose.waitForIdle()
    }

    @Test fun `registered edit preserves custom value and confirms model only through readback`() {
        launch()
        compose.onNodeWithContentDescription("Model ID").performScrollTo().assertTextEquals("original")
        compose.onNodeWithText("Save model").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Choose provider").performScrollTo().performClick()
        compose.onNodeWithText("Catalog").performClick()
        compose.onNodeWithText("Choose model").performScrollTo().performClick()
        compose.onNodeWithText("guarded").performClick()
        compose.onNodeWithText("Save model").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, calls.count { it.first == "profiles.configure" })
        assertEquals("original", selection.model)
        compose.onNodeWithText("Review cost before saving").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confirm model change").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Model saved.").performScrollTo().assertIsDisplayed()
        val writes = calls.filter { it.first == "profiles.configure" }
        assertEquals(setOf("name", "provider", "model"), writes.first().second.keys)
        assertEquals(setOf("name", "provider", "model", "confirm_expensive_model"), writes.last().second.keys)
        assertEquals(BotModelSelection("catalog", "guarded"), selection)
        assertTrue(calls.dropWhile { it != writes.last() }.any { it.first == "profiles.describe" })
    }

    @Test fun `registered warning callback cannot save after endpoint changes before recomposition`() {
        launch()
        compose.onNodeWithContentDescription("Model ID").performScrollTo().performTextReplacement("guarded")
        compose.onNodeWithText("Save model").performScrollTo().performClick()
        compose.waitForIdle()
        val confirm = compose.onNodeWithText("Confirm model change").performScrollTo().fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.runOnIdle { endpoint.value = 8L; confirm.invoke() }
        compose.waitForIdle()
        assertEquals(1, calls.count { it.first == "profiles.configure" })
        assertEquals("original", selection.model)
    }
}
