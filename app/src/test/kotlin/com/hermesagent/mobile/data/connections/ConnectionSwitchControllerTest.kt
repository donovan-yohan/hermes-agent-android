package com.hermesagent.mobile.data.connections

import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.draft.TransientSessionDraftStore
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshCredential
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switching connections, on virtual time.
 *
 * The order is the contract: disconnect through the existing path, clear the
 * old machine's sessions, then move the active marker. Anything else — a
 * marker that moves before the teardown, a cache that survives it — is how two
 * hosts' sessions end up in one list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSwitchControllerTest {

    @Test
    fun `a switch disconnects, clears the old endpoint's sessions, then moves the marker`() = runTest {
        val gateway = RecordingGateway()
        val cache = SessionCache().withFixtureSession()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, cache)
        gateway.settleOnConnect()

        controller.select("two")
        advanceUntilIdle()

        assertEquals(listOf("disconnect"), gateway.calls)
        assertTrue("the previous machine's sessions must not survive", cache.state.value.sessions.isEmpty())
        assertTrue(cache.state.value.transcripts.isEmpty())
        assertEquals("two", store.connectionRegistry.first().activeId)
    }

    @Test
    fun `the cache is cleared before the marker moves, never after`() = runTest {
        val gateway = RecordingGateway()
        val cache = SessionCache().withFixtureSession()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        store.onSetActive = {
            assertTrue("a projection must never see the old endpoint's rows", cache.state.value.sessions.isEmpty())
            assertEquals("the old endpoint is already down", listOf("disconnect"), gateway.calls)
        }
        gateway.settleOnConnect()

        ConnectionSwitchController(store, gateway, cache).select("two")
        advanceUntilIdle()

        assertTrue(store.setActiveObserved)
    }

    @Test
    fun `the pending marker is held while the new endpoint comes up and then released`() = runTest {
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, SessionCache())

        val switch = launch { controller.select("two") }
        runCurrent()
        assertEquals("two", controller.pendingConnectionId.value)

        gateway.publish(GatewayConnectionStatus.Connected)
        switch.join()

        assertNull(controller.pendingConnectionId.value)
    }

    @Test
    fun `a new endpoint that needs a sign-in releases the pending marker rather than hanging on it`() = runTest {
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, SessionCache())

        val switch = launch { controller.select("two") }
        runCurrent()
        gateway.publish(GatewayConnectionStatus.NeedsAttention)
        switch.join()

        assertNull(controller.pendingConnectionId.value)
        assertEquals("two", store.connectionRegistry.first().activeId)
    }

    @Test
    fun `a pending marker that nothing answers gives up on its own clock`() = runTest {
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, SessionCache(), settleTimeoutMillis = 5_000)

        val switch = launch { controller.select("two") }
        runCurrent()
        assertEquals("two", controller.pendingConnectionId.value)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals("still waiting", "two", controller.pendingConnectionId.value)

        advanceTimeBy(2)
        switch.join()
        assertNull(controller.pendingConnectionId.value)
    }

    @Test
    fun `a Local target is waited on, because its token is already on this device`() = runTest {
        val rows = listOf(
            TWO_ROWS.first(),
            SavedConnection(
                "two",
                "Termux",
                ConnectionKind.Local,
                local = LocalGatewayProfile("http://127.0.0.1:9119"),
            ),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(rows, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, SessionCache(), settleTimeoutMillis = 5_000)

        val switch = launch { controller.select("two") }
        runCurrent()

        // The follower restores this row with no interaction, so the badge has
        // something real to wait for — the difference from Managed SSH, whose
        // credential died with the connection that was just closed.
        assertEquals("two", controller.pendingConnectionId.value)

        gateway.publish(GatewayConnectionStatus.Connected)
        switch.join()

        assertNull(controller.pendingConnectionId.value)
        assertEquals("two", store.connectionRegistry.first().activeId)
    }

    @Test
    fun `a managed SSH target is not waited on, because nothing dials it by itself`() = runTest {
        val rows = listOf(
            TWO_ROWS.first(),
            SavedConnection("two", "Beta", ConnectionKind.Ssh, host = HostProfile("demo-host", 22, "demo-user")),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(rows, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, SessionCache())

        controller.select("two")

        assertNull(controller.pendingConnectionId.value)
        assertEquals("two", store.connectionRegistry.first().activeId)
        assertEquals(listOf("disconnect"), gateway.calls)
    }

    @Test
    fun `re-addressing the connection you are on leaves that endpoint without moving the marker`() = runTest {
        val gateway = RecordingGateway()
        val cache = SessionCache().withFixtureSession()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")

        ConnectionSwitchController(store, gateway, cache).leaveCurrentEndpoint()

        assertEquals(listOf("disconnect"), gateway.calls)
        assertTrue("the address you left owns those sessions", cache.state.value.sessions.isEmpty())
        assertEquals("and you are still on the same row", "one", store.connectionRegistry.first().activeId)
        assertFalse(store.setActiveObserved)
    }


    @Test
    fun `leaving an endpoint drops the drafts that were typed against it`() = runTest {
        val gateway = RecordingGateway()
        val drafts = TransientSessionDraftStore()
        drafts.replace("session-1", "half a sentence")
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        gateway.settleOnConnect()

        ConnectionSwitchController(store, gateway, SessionCache(), drafts).select("two")
        advanceUntilIdle()

        assertTrue(
            "a draft keyed by a durable id another gateway can recycle must not follow you",
            drafts.drafts.first().isEmpty(),
        )
    }

    @Test
    fun `editing the route form does not drop drafts`() = runTest {
        val gateway = RecordingGateway()
        val drafts = TransientSessionDraftStore()
        drafts.replace("session-1", "half a sentence")
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, SessionCache(), drafts)

        // The Gateways route form persists on every keystroke and calls this
        // after each one. It cannot tell a finished address from a half-typed
        // one, so it must not be allowed to destroy anything that has no other
        // copy. The connection and the cache both repopulate; draft text does
        // not.
        repeat(3) { controller.leaveCurrentEndpoint() }
        advanceUntilIdle()

        assertEquals("half a sentence", drafts.drafts.first()["session-1"])
        assertEquals("and it is still a real teardown", 3, gateway.calls.count { it == "disconnect" })
    }

    @Test
    fun `abandoning the endpoint, as removing the active row does, drops its drafts`() = runTest {
        val gateway = RecordingGateway()
        val drafts = TransientSessionDraftStore()
        drafts.replace("session-1", "half a sentence")
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")

        ConnectionSwitchController(store, gateway, SessionCache(), drafts).abandonCurrentEndpoint()
        advanceUntilIdle()

        assertTrue(drafts.drafts.first().isEmpty())
    }

    @Test
    fun `re-addressing the active row drops the drafts written against the old address`() = runTest {
        val gateway = RecordingGateway()
        val drafts = TransientSessionDraftStore()
        drafts.replace("session-1", "half a sentence")
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        gateway.settleOnConnect()

        ConnectionSwitchController(store, gateway, SessionCache(), drafts).readdressActive {
            store.saveConnection(TWO_ROWS.first().copy(remote = RemoteGatewayProfile("https://renamed.test")))
        }
        advanceUntilIdle()

        assertTrue(drafts.drafts.first().isEmpty())
    }

    @Test
    fun `a teardown that arrives mid-switch waits, instead of killing what the switch just opened`() = runTest {
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, SessionCache())

        val switch = launch { controller.select("two") }
        runCurrent()
        assertEquals("the switch is mid-flight, waiting to settle", listOf("disconnect"), gateway.calls)

        // A removal racing the switch. It must not disconnect anything yet.
        val teardown = launch { controller.leaveCurrentEndpoint() }
        runCurrent()
        assertEquals("still exactly the switch's own teardown", listOf("disconnect"), gateway.calls)

        gateway.publish(GatewayConnectionStatus.Connected)
        switch.join()
        teardown.join()

        assertEquals(listOf("disconnect", "disconnect"), gateway.calls)
    }

    @Test
    fun `re-addressing the active row leaves it, saves, and re-arms the route follower`() = runTest {
        val gateway = RecordingGateway()
        val cache = SessionCache().withFixtureSession()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")
        val controller = ConnectionSwitchController(store, gateway, cache)
        val generationBefore = controller.routeGeneration.value
        var savedWhilePending: String? = null

        val readdress = launch {
            controller.readdressActive {
                savedWhilePending = controller.pendingConnectionId.value
                store.saveConnection(
                    TWO_ROWS.first().copy(remote = RemoteGatewayProfile("https://renamed.test")),
                )
            }
        }
        runCurrent()
        gateway.publish(GatewayConnectionStatus.Connected)
        readdress.join()

        assertEquals("the row being re-addressed is the pending one", "one", savedWhilePending)
        assertEquals("it left the old address first", listOf("disconnect"), gateway.calls)
        assertTrue("and the old address's sessions went with it", cache.state.value.sessions.isEmpty())
        assertEquals(
            "https://renamed.test",
            store.connectionRegistry.first().connections.first { it.id == "one" }.remote.baseUrl,
        )
        assertTrue(
            "the follower is told to re-dial a row whose id did not move",
            controller.routeGeneration.value > generationBefore,
        )
        assertNull(controller.pendingConnectionId.value)
        assertEquals("and it is still the row you were on", "one", store.connectionRegistry.first().activeId)
    }

    @Test
    fun `selecting the connection already in use changes nothing`() = runTest {
        val gateway = RecordingGateway()
        val cache = SessionCache().withFixtureSession()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")

        ConnectionSwitchController(store, gateway, cache).select("one")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), gateway.calls)
        assertFalse(cache.state.value.sessions.isEmpty())
    }

    @Test
    fun `a connection this device has never heard of is ignored`() = runTest {
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(TWO_ROWS, activeId = "one")

        ConnectionSwitchController(store, gateway, SessionCache()).select("missing")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), gateway.calls)
        assertEquals("one", store.connectionRegistry.first().activeId)
    }

    private class RecordingGateway : GatewayConnectionController {
        private val _state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()
        val calls = mutableListOf<String>()
        private var settleImmediately = false

        /** Land the new endpoint the moment it is dialled, for tests that are not about waiting. */
        fun settleOnConnect() {
            settleImmediately = true
        }

        fun publish(status: GatewayConnectionStatus) {
            _state.value = GatewayConnectionState(status)
        }

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            GatewayConnectResult.Connected

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = GatewayConnectResult.Connected

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) {
            calls += "forget"
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            _state.value = GatewayConnectionState(
                if (settleImmediately) GatewayConnectionStatus.Connected else GatewayConnectionStatus.Disconnected,
            )
        }
    }

    private class MemoryRegistryStore(
        rows: List<SavedConnection>,
        activeId: String?,
    ) : ConnectionRegistryStore {
        private val registry = MutableStateFlow(ConnectionRegistry(rows, activeId))
        override val connectionRegistry: StateFlow<ConnectionRegistry> = registry.asStateFlow()
        var onSetActive: (() -> Unit)? = null
        var setActiveObserved = false

        override suspend fun saveConnection(connection: SavedConnection) {
            registry.update { current ->
                val index = current.connections.indexOfFirst { it.id == connection.id }
                val rows = if (index >= 0) {
                    current.connections.toMutableList().also { it[index] = connection }
                } else {
                    current.connections + connection
                }
                current.copy(connections = rows)
            }
        }

        override suspend fun removeConnection(id: String) {
            registry.update { current -> current.copy(connections = current.connections.filterNot { it.id == id }) }
        }

        override suspend fun setActiveConnection(id: String) {
            setActiveObserved = true
            onSetActive?.invoke()
            registry.update { it.copy(activeId = id) }
        }
    }

    private companion object {
        val TWO_ROWS = listOf(
            SavedConnection(
                id = "one",
                label = "Alpha",
                kind = ConnectionKind.Remote,
                remote = RemoteGatewayProfile("https://alpha.test"),
            ),
            SavedConnection(
                id = "two",
                label = "Beta",
                kind = ConnectionKind.Remote,
                remote = RemoteGatewayProfile("https://beta.test"),
            ),
        )

        fun SessionCache.withFixtureSession(): SessionCache = apply {
            upsertSession(
                SessionSummary(
                    id = "session-1",
                    title = "Fixture",
                    preview = "",
                    status = SessionStatus.Idle,
                    lastActiveAtMillis = 0L,
                ),
            )
            setTranscript("session-1", listOf(UserTurn("entry-1", "hello", 0L)))
        }
    }
}
