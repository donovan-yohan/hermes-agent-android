package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forced redial a finished backend update asks for.
 *
 * A backend update restarts the gateway on the host; over a tunnel the old TCP
 * connection often dies with no close event, so this client's socket reads open
 * while every RPC on it hangs (`apps/desktop/src/store/updates.ts:543-550` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`). The ordinary transport-failure
 * ladder never fires, because nothing ever failed. These tests pin the three
 * things the deliberate redial must and must not do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayBackendUpdateRedialTest {

    @Test
    fun `a live Remote connection is retired and redialled`() = runTest {
        val opened = mutableListOf<FakeRpc>()
        val manager = manager(opened)

        assertEquals(GatewayConnectResult.Connected, manager.restoreRemote(PROFILE))
        val first = opened.single()
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)

        manager.redialAfterBackendUpdate()
        // The ladder's first attempt is a jittered delay under 300 ms, so a
        // short advance is enough — this is a redial, not a backoff.
        settle()

        assertTrue("the stranded socket must be closed by this client", first.closedByClient)
        assertEquals(2, opened.size)
        assertNotSame(first, opened.last())
        assertEquals(GatewayConnectionStatus.Connected, manager.state.value.status)
        manager.disconnect()
    }

    @Test
    fun `a connection the person put down stays down`() = runTest {
        val opened = mutableListOf<FakeRpc>()
        val manager = manager(opened)

        assertEquals(GatewayConnectResult.Connected, manager.restoreRemote(PROFILE))
        manager.disconnect()
        runCurrent()
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)

        manager.redialAfterBackendUpdate()
        settle()

        // A user-driven Disconnected clears the desired route, and nothing
        // about an update on some host is permission to dial again.
        assertEquals(1, opened.size)
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
    }

    @Test
    fun `it is a no-op when nothing is connected at all`() = runTest {
        val opened = mutableListOf<FakeRpc>()
        val manager = manager(opened)

        manager.redialAfterBackendUpdate()
        settle()

        assertEquals(0, opened.size)
        assertEquals(GatewayConnectionStatus.Disconnected, manager.state.value.status)
    }

    @Test
    fun `it is a no-op on a route that is not Remote`() = runTest {
        // Managed SSH's credential is created by its connection and dies with
        // it, and a Local Hermes was not restarted by a remote host's update.
        // Neither is this call's to dial, so it must not reach either opener.
        val opened = mutableListOf<FakeRpc>()
        var sshOpens = 0
        val manager = GatewayConnectionManager(
            scope = backgroundScope,
            installStore = GatewayInstallStore { "install-fixture" },
            sshOpen = { _, _ ->
                sshOpens += 1
                error("the SSH opener must not be reached by a backend-update redial")
            },
            remoteConnector = connector(opened),
            reconnectWait = { },
            reconnectJitter = { 0.0 },
        )

        manager.redialAfterBackendUpdate()
        settle()

        assertEquals(0, sshOpens)
        assertEquals(0, opened.size)
    }

    @Test
    fun `a switch that landed mid-update wins over the update's own redial`() = runTest {
        val opened = mutableListOf<FakeRpc>()
        val manager = manager(opened)

        assertEquals(GatewayConnectResult.Connected, manager.restoreRemote(PROFILE))
        val live = opened.single()

        // The person switched connections while the update ran. `disconnect`
        // is what a switch does first, so the desired route is no longer the
        // one the apply was watching.
        manager.disconnect()
        runCurrent()
        manager.redialAfterBackendUpdate()
        settle()

        assertSame(live, opened.single())
        assertFalse(manager.state.value.status == GatewayConnectionStatus.Connected)
    }

    // -----------------------------------------------------------------------

    private fun TestScope.manager(opened: MutableList<FakeRpc>) = GatewayConnectionManager(
        scope = backgroundScope,
        installStore = GatewayInstallStore { "install-fixture" },
        remoteConnector = connector(opened),
        // The ladder's wait is injected away: what is under test is *that* a
        // redial is armed, not how long full-jitter backoff sleeps first.
        reconnectWait = { },
        reconnectJitter = { 0.0 },
    )

    private fun connector(opened: MutableList<FakeRpc>) = RemoteGatewayConnector(
        NativeGatewayAuthenticator(
            api = StubAuthApi(),
            store = MemoryTokenStore(VALID_TOKENS),
            // A stored, unexpired sign-in is the whole premise: a redial must
            // never open a browser, so the interactive leg is an error.
            login = GatewayNativeLogin { _, _ -> error("a redial must never sign in interactively") },
            nowSeconds = { 1_000L },
        ),
    ) { _, _ -> FakeRpc().also { opened += it } }

    private fun TestScope.settle() {
        advanceTimeBy(5_000L)
        runCurrent()
    }

    private class StubAuthApi : GatewayNativeAuthApi {
        override suspend fun status(baseUrl: String) =
            GatewayAuthStatus(authRequired = true, authFlows = setOf("native_pkce"))

        override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens =
            error("a redial must never open a browser")

        override suspend fun refresh(
            baseUrl: String,
            refreshToken: String,
            provider: String,
        ): GatewayNativeTokens = VALID_TOKENS

        override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String = "ticket"
    }

    private class MemoryTokenStore(private var tokens: GatewayNativeTokens?) : GatewayTokenStore {
        override suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens? = tokens

        override suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens) {
            this.tokens = tokens
        }

        override suspend fun clear(slot: GatewaySecretSlot) {
            tokens = null
        }
    }

    private class FakeRpc : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        override val closed = MutableSharedFlow<GatewayCloseCause>(extraBufferCapacity = 1)
        var closedByClient = false

        override suspend fun request(method: String, params: JsonObject): JsonElement = buildJsonObject {}

        override fun close() {
            closedByClient = true
        }
    }

    private companion object {
        val PROFILE = RemoteGatewayProfile(
            "https://gateway.example/hermes/",
            provider = "fixture-provider",
        )

        val VALID_TOKENS = GatewayNativeTokens(
            accessToken = "access-fixture",
            refreshToken = "refresh-fixture",
            expiresAt = 10_000L,
            provider = "fixture-provider",
            userId = "fixture-user",
        )
    }
}
