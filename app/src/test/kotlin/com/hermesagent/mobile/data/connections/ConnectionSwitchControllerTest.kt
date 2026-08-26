package com.hermesagent.mobile.data.connections

import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
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
