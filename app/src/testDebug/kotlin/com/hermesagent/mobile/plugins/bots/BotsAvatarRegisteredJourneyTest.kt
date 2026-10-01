package com.hermesagent.mobile.plugins.bots

import androidx.compose.ui.test.*
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.profiles.*
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Registered route, real host dispatch/roster admission/decoder, synthetic asset server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotsAvatarRegisteredJourneyTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.v2.createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private var stored: ByteArray? = null
    private val rpc = object : EndpointDispatchingGatewayRpcClient {
        override val events: Flow<GatewayEvent> = emptyFlow()
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("unfenced RPC")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            check(dispatch { calls += method to params; true })
            return when (method) {
                "profiles.list" -> buildJsonObject { put("profiles", buildJsonArray { add(buildJsonObject {
                    put("name", "worker"); put("display_name", "Different Label"); put("has_avatar", stored != null)
                }) }) }
                "profiles.describe" -> Json.parseToJsonElement("""{"name":"worker","description":"","soul":"","model":{"provider":"custom","default":"original"}}""")
                "model.options" -> Json.parseToJsonElement("""{"providers":[]}""")
                "profiles.get_asset" -> stored?.let { avatarReply(it) } ?: Json.parseToJsonElement("""{"found":false}""")
                "profiles.set_asset" -> {
                    assertEquals("worker", params.getValue("name").jsonPrimitive.content)
                    assertEquals("avatar", params.getValue("asset").jsonPrimitive.content)
                    stored = if (params["clear"] == JsonPrimitive(true)) null else
                        java.util.Base64.getDecoder().decode(params.getValue("data").jsonPrimitive.content.substringAfter(','))
                    buildJsonObject { put("ok", true); put("asset", "avatar"); put("size", stored?.size ?: 0) }
                }
                else -> error("Unexpected $method")
            }
        }
        override fun close() {}
    }
    private val host = GatewayPluginHost(scope, MutableStateFlow<GatewayRpcClient?>(rpc), MutableStateFlow(0L), EndpointDispatchFence())
    private val coordinator = AvatarRosterCoordinator(host, ProfileAvatarRepository(scope, decoder = AndroidAvatarDecoder()))
    private var launchedCode = -1
    private val picker = object : androidx.activity.result.ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I,
            options: androidx.core.app.ActivityOptionsCompat?) { launchedCode = requestCode }
    }
    private val pickerOwner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = picker }
    @After fun close() { coordinator.close(); scope.cancel() }

    private fun launch() {
        val registry = ContributionRegistry()
        BotsPlugin(scope = scope, avatarRoster = coordinator).register(createPluginContext(
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
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides pickerOwner,
                com.hermesagent.mobile.ui.common.LocalProfileAvatarRoster provides coordinator,
            ) { HermesTheme(AppearanceSelection()) { route.render!!.invoke() } }
        }
        compose.waitForIdle()
    }
    private fun edit() {
        compose.onNodeWithContentDescription("Actions for Different Label").performClick()
        compose.onNodeWithText("Edit…").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Drag handle")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Expand)
        compose.waitForIdle()
    }
    private fun closeSheet() {
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun bluePixels(): Int {
        val decor = compose.activity.window.decorView
        val pixels = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { decor.draw(android.graphics.Canvas(pixels)) }
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width)
            if (pixels.getPixel(x, y) == android.graphics.Color.rgb(60, 140, 220)) count++
        pixels.recycle()
        return count
    }
    @Test fun registeredPhotoSaveRosterReopenClearRosterReopenHasNoStalePhoto() {
        launch()
        assertEquals(0, bluePixels())
        edit()
        compose.onNodeWithContentDescription("Avatar fallback: worker").performScrollTo().assertIsDisplayed()
        val uri = android.net.Uri.parse("content://synthetic.avatar/registered")
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri,
            java.io.ByteArrayInputStream(com.hermesagent.mobile.fixtureAvatarPng()))
        compose.onNodeWithText("Upload").performScrollTo().performClick()
        compose.runOnIdle { picker.dispatchResult(launchedCode, android.app.Activity.RESULT_OK, android.content.Intent().setData(uri)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Avatar preview").fetchSemanticsNodes().isNotEmpty() }
        assertNull(stored)
        compose.onNodeWithText("Save avatar").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Avatar saved.").performScrollTo()
        compose.onNodeWithText("Avatar saved.").assertIsDisplayed()
        assertNotNull(stored)
        closeSheet()
        compose.waitUntil(5_000) { bluePixels() > 100 }
        edit()
        compose.onNodeWithContentDescription("Avatar preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove image").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Avatar preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("Avatar fallback: worker").performScrollTo().assertIsDisplayed()
        assertNotNull(stored)
        compose.onNodeWithText("Save avatar").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Avatar removed.").performScrollTo().assertIsDisplayed()
        assertNull(stored)
        closeSheet()
        assertEquals(0, bluePixels())
        edit()
        compose.onNodeWithContentDescription("Avatar fallback: worker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Avatar preview").assertDoesNotExist()
        compose.onNodeWithText("Save avatar").performScrollTo().assertIsNotEnabled()
        assertEquals(2, calls.count { it.first == "profiles.set_asset" })
        val writes = calls.filter { it.first == "profiles.set_asset" }
        assertEquals(setOf("name", "asset", "data"), writes.first().second.keys)
        assertEquals(setOf("name", "asset", "clear"), writes.last().second.keys)
        assertTrue(calls.dropWhile { it != writes.last() }.any { it.first == "profiles.get_asset" })
    }
}
