package com.hermesagent.mobile.data.gateway

import android.os.Looper
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which thread a sign-in's network legs actually run on.
 *
 * This is the #114 regression that two device runs were spent finding, and it
 * is invisible to a plain JVM test because it needs a real main looper to be
 * wrong about. `startRemoteSignIn` launches UNDISPATCHED so it can claim its
 * connect intent on the caller's thread, and the process scope names
 * `Dispatchers.IO` — so the coroutine's *context* already says IO while the
 * coroutine is still physically on the main thread. `withContext(Dispatchers.IO)`
 * compares interceptors, finds them equal, and runs inline. Every network leg of
 * a sign-in then ran on the main thread and threw
 * `NetworkOnMainThreadException` before one request left the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GatewaySignInDispatchTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stopScope() {
        scope.cancel()
    }

    @Test
    fun `a sign-in started from the main thread never puts a network leg on it`() {
        val api = ThreadRecordingAuthApi()
        val manager = manager(api)

        // A tap arrives on the main thread. That is the whole premise.
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        manager.startRemoteSignIn(PROFILE, GatewayBrowserLauncher {})
        awaitSettled(manager)

        assertTrue("the flow has to have reached the wire at all", api.loopers().isNotEmpty())
        // Asserted here and not only inside the fake: `openRemote` catches
        // Throwable, so an AssertionError thrown on the wire thread would be
        // swallowed into a connection failure and the test would pass.
        assertEquals(emptyList<Looper?>(), api.loopers().filter { it === Looper.getMainLooper() })
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
    }

    /**
     * The gate above is only worth having if it can fail. This reproduces the
     * shipped-and-broken shape exactly — an UNDISPATCHED start on an IO-context
     * scope, begun on the main thread, with no hop before the network — and
     * asserts the wire really did land on the main looper.
     */
    @Test
    fun `the dispatch gate has teeth`() {
        val api = ThreadRecordingAuthApi(requireOffMain = false)
        val manager = manager(api)

        runBlocking {
            val regression = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                manager.connectRemote(PROFILE, GatewayBrowserLauncher {})
            }
            withTimeout(TIMEOUT_MILLIS) { regression.join() }
        }

        assertTrue(
            "without a hop the wire runs on whatever thread tapped Connect",
            api.loopers().any { it === Looper.getMainLooper() },
        )
    }

    private fun manager(api: GatewayNativeAuthApi): GatewayConnectionManager {
        val authenticator = NativeGatewayAuthenticator(
            api = api,
            store = MemoryTokenStore(),
            // The browser leg is out of scope here; this test is about the wire.
            login = GatewayNativeLogin { _, _ -> TOKENS },
            nowSeconds = { 1_000L },
        )
        return GatewayConnectionManager(
            scope = scope,
            installStore = GatewayInstallStore { error("Remote Gateway route has no process ownership") },
            remoteConnector = RemoteGatewayConnector(authenticator) { _, _ -> InertRpc() },
        )
    }

    private fun awaitSettled(manager: GatewayConnectionManager) = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            manager.state.first {
                it.status == GatewayConnectionStatus.Connected ||
                    it.status == GatewayConnectionStatus.NeedsAttention
            }
        }
    }

    /**
     * Every method wraps its body the way the real one does
     * (`OkHttpGatewayNativeAuthApi.requestJson`), because that wrapper being a
     * no-op is the entire bug.
     */
    private class ThreadRecordingAuthApi(
        private val requireOffMain: Boolean = true,
    ) : GatewayNativeAuthApi {
        private val seen = Collections.synchronizedList(mutableListOf<Looper?>())

        fun loopers(): List<Looper?> = seen.toList()

        private suspend fun <T> onWire(block: () -> T): T = withContext(Dispatchers.IO) {
            seen += Looper.myLooper()
            if (requireOffMain) {
                assertNotSame(
                    "a Gateway network leg must never run on the main looper",
                    Looper.getMainLooper(),
                    Looper.myLooper(),
                )
            }
            block()
        }

        override suspend fun status(baseUrl: String): GatewayAuthStatus =
            onWire { GatewayAuthStatus(authRequired = true, authFlows = setOf("native_pkce")) }

        override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens =
            onWire { TOKENS }

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
            provider: String,
        ): GatewayNativeTokens = onWire { TOKENS }

        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String =
            onWire { "ticket-dispatch" }
    }

    private class MemoryTokenStore : GatewayTokenStore {
        private var tokens: GatewayNativeTokens? = null
        override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = tokens
        override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) {
            this.tokens = tokens
        }

        override suspend fun clear(slot: GatewaySecretSlot) {
            tokens = null
        }
    }

    private class InertRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<GatewayCloseCause>(extraBufferCapacity = 1)
        override suspend fun request(method: String, params: JsonObject): JsonElement = buildJsonObject {}
        override fun close() = Unit
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        val PROFILE = RemoteGatewayProfile("https://gateway.example/hermes", provider = "fixture-provider")
        val TOKENS = GatewayNativeTokens(
            accessToken = "access-dispatch",
            refreshToken = "refresh-dispatch",
            expiresAt = 10_000L,
            provider = "fixture-provider",
            userId = "fixture-user",
        )
    }
}
