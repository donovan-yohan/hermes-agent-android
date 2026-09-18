package com.hermesagent.mobile.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hermesagent.mobile.data.gateway.EndpointDispatchFence
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.data.profiles.AndroidAvatarDecoder
import com.hermesagent.mobile.data.profiles.AvatarRosterCoordinator
import com.hermesagent.mobile.data.profiles.GatewayProfileRepository
import com.hermesagent.mobile.data.profiles.ProfileAvatar
import com.hermesagent.mobile.data.profiles.ProfileAvatarRepository
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Real ProfileGlyph + coordinator + decoder render proof for the stale-frame fence.
 * The owner is revoked after composition and before the next synchronous window draw;
 * no avatar/revision collector is allowed to settle first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileAvatarDrawGuardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val clients = MutableStateFlow<GatewayRpcClient?>(null)
    private val endpoint = MutableStateFlow(0L)
    private val fence = EndpointDispatchFence()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rpc = DrawGuardRpc()
    private val host = GatewayPluginHost(scope, clients, endpoint, fence)
    private val repository = ProfileAvatarRepository(scope, decoder = AndroidAvatarDecoder())
    private val coordinator = AvatarRosterCoordinator(host, repository)
    private val producer = coordinator.open(AvatarRosterCoordinator.Kind.Core)

    @After
    fun tearDown() {
        coordinator.close()
        scope.coroutineContext.cancel()
    }

    @Test
    fun `draw guard suppresses ready bitmap after owner loss before draw`() = runBlocking {
        clients.value = rpc
        val profiles = GatewayProfileRepository({ error("captured path required") }, avatarProducer = producer)
        assertTrue(profiles.refreshProfiles())
        val profile = profiles.roster.value.profiles.single()
        val ref = checkNotNull(profile.avatarRef)
        val binding = checkNotNull(coordinator.bind(ref))
        val readyState = withTimeout(5_000) {
            binding.updates.first { it !is ProfileAvatar.Loading }
        }
        assertTrue("synthetic PNG should decode as Ready, got $readyState", readyState is ProfileAvatar.Ready)
        val ready = readyState as ProfileAvatar.Ready

        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                CompositionLocalProvider(LocalProfileAvatarRoster provides coordinator) {
                    ProfileGlyph(
                        profile = profile,
                        size = androidx.compose.ui.unit.Dp(64f),
                        modifier = Modifier.testTag(GLYPH_TAG),
                        contentDescription = "Avatar",
                    )
                }
            }
        }
        compose.waitForIdle()
        val readyPixel = drawCenterPixel()
        assertEqualsColor(Color.Blue, readyPixel, "ready avatar should be visible before revocation")

        // Do not call waitForIdle after reset: this intentionally draws the old composed
        // Image while its owner collector/revision collector still holds the previous frame.
        coordinator.reset()
        val staleFramePixel = drawCenterPixel()
        assertNotEquals(
            "owner loss must suppress the old bitmap in the composition-to-draw gap",
            Color.Blue.toArgb(),
            staleFramePixel,
        )
        assertTrue(binding.current() is ProfileAvatar.Unavailable)
        ready.bitmap.recycle()
        binding.close()
    }

    private fun drawCenterPixel(): Int {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val bounds = compose.onNodeWithTag(GLYPH_TAG).fetchSemanticsNode().boundsInWindow
        return bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
    }

    private fun assertEqualsColor(expected: Color, actual: Int, message: String) {
        assertTrue(message, expected.toArgb() == actual)
    }

    private class DrawGuardRpc : EndpointDispatchingGatewayRpcClient {
        override val events: Flow<GatewayEvent> = emptyFlow()
        override suspend fun request(method: String, params: JsonObject): JsonElement =
            error("ordinary RPC path must not be used")

        override suspend fun requestAtEndpointDispatch(
            method: String,
            params: JsonObject,
            dispatch: (() -> Boolean) -> Boolean,
        ): JsonElement {
            if (!dispatch { true }) throw GatewayRpcException("stale synthetic lease")
            return when (method) {
                "profiles.list" -> roster
                "profiles.get_asset" -> asset
                else -> error("unexpected method")
            }
        }

        override fun close() = Unit

        companion object {
            val roster: JsonElement = buildJsonObject {
                put("profiles", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "draw-guard")
                        put("display_name", "Draw Guard")
                        put("has_avatar", true)
                    })
                })
            }
            val asset: JsonElement = run {
                val bytes = ByteArrayOutputStream().also { output ->
                    Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.BLUE)
                        compress(Bitmap.CompressFormat.PNG, 100, output)
                        recycle()
                    }
                }.toByteArray()
                buildJsonObject {
                    put("found", true)
                    put("mime", "image/png")
                    put("size", bytes.size)
                    put("data", "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}")
                }
            }
        }
    }

    private companion object {
        const val GLYPH_TAG = "avatar-draw-guard-glyph"
    }
}
