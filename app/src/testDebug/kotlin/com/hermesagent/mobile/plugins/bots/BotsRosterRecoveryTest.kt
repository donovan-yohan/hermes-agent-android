package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.gateway.GatewayEvent
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.GatewayPluginHost
import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginNotificationInput
import com.hermesagent.mobile.plugins.PluginOs
import com.hermesagent.mobile.plugins.PluginRest
import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import com.hermesagent.mobile.plugins.PluginSocket
import com.hermesagent.mobile.plugins.PluginStorage
import com.hermesagent.mobile.plugins.createPluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * C1's regression: the roster is read **on the connection's edge**, never into
 * a connection that is not there.
 *
 * This class is written against the API 3a shipped, deliberately: it compiles
 * unchanged at `1f574b8` and is the red-at-base proof for the card. At that
 * base the plugin read the roster exactly once, at registration, while
 * `HermesApplication` had not yet dialled anything — so the read was refused
 * and the list could never update afterwards. Every assertion below fails
 * there; all of them pass at the head.
 *
 * It is not a substitute for the rendered journey
 * (`com.hermesagent.mobile.ui.BotsRosterJourneyTest`), which walks the same
 * recovery through the real surface: this one pins *when the read happens*,
 * which a rendered assertion cannot distinguish from a read that happened
 * earlier and was retried.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotsRosterRecoveryTest {

    /** The live connection slot, exactly as `HermesApplication` hands it over. */
    private val clients = MutableStateFlow<GatewayRpcClient?>(null)

    /** Every method that actually reached a Gateway. */
    private val reads = mutableListOf<String>()

    private lateinit var pluginScope: CoroutineScope

    @Before
    fun setUp() {
        pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After
    fun tearDown() {
        pluginScope.cancel()
    }

    /** Let everything the plugin scope queued on the main looper run. */
    private fun settle() {
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `a cold start reads when the connection lands, not before it`() {
        register()

        // Nothing has dialled yet, so nothing was asked: the host door refuses
        // while no client exists, and a request here would only be thrown away
        // (and, on the base, was thrown away for the rest of the launch).
        settle()
        assertEquals(emptyList<String>(), reads)

        clients.value = rosterRpc("default", "researcher")
        settle()

        assertEquals(listOf("profiles.list"), reads)
    }

    @Test
    fun `a reconnect reads the roster again`() {
        clients.value = rosterRpc("default", "researcher")
        register()
        settle()
        assertEquals(listOf("profiles.list"), reads)

        // The leg drops and comes back: that is a new connection, and the list
        // a person is looking at has to be allowed to move with it.
        clients.value = null
        settle()
        clients.value = rosterRpc("default", "researcher", "writer")
        settle()

        assertEquals(listOf("profiles.list", "profiles.list"), reads)
    }

    private fun register() {
        val registry = ContributionRegistry()
        val plugin = BotsPlugin(scope = pluginScope)
        plugin.register(
            createPluginContext(
                pluginId = plugin.id,
                registry = registry,
                rest = NoRest,
                socket = NoSocket,
                storage = NoStorage,
                os = NoOs,
                host = GatewayPluginHost(pluginScope, clients) as PluginHost,
            ),
        )
    }

    /** One connection leg's client. No test here reaches a socket. */
    private fun rosterRpc(vararg names: String): GatewayRpcClient {
        val rows = names.joinToString(",") { name -> """{"name": "$name"}""" }
        return object : GatewayRpcClient {
            override val events: Flow<GatewayEvent> = emptyFlow()

            override suspend fun request(method: String, params: JsonObject): JsonElement {
                reads += method
                return Json.parseToJsonElement("""{"profiles": [$rows]}""")
            }

            override fun close() {}
        }
    }

    private object NoRest : PluginRest {
        override suspend fun execute(
            pluginId: String,
            path: String,
            options: PluginRestOptions,
        ): PluginRestResult = PluginRestResult.Success(200, "{}".toByteArray())
    }

    private object NoSocket : PluginSocket {
        override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
    }

    private object NoStorage : PluginStorage {
        override suspend fun get(key: String, fallback: String?): String? = fallback
        override suspend fun set(key: String, value: String) {}
        override suspend fun remove(key: String) {}
    }

    private object NoOs : PluginOs {
        override fun notify(input: PluginNotificationInput) {}
        override suspend fun openExternal(url: String): Boolean = true
        override suspend fun writeClipboard(text: String): Boolean = true
        override suspend fun share(text: String, title: String?): Boolean = true
    }
}
