package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.ConnectionRegistry
import com.hermesagent.mobile.data.connections.ConnectionRegistryStore
import com.hermesagent.mobile.data.connections.ConnectionSwitchController
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ConnectionsViewModel`: add/edit/remove against a real
 * [ConnectionSwitchController] over in-memory fakes.
 *
 * The point of most of these is ordering and refusal: a credential must be
 * erased before its row can disappear (even when the row's URL is unusable),
 * an unaddressable or duplicate URL must never reach the store, and only a
 * re-addressed *active* row should tear anything down — a rename must not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useVirtualMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun releaseMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `delete erases then removes`() = runTest(dispatcher) {
        val target = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val other = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(target, other), activeId = "two")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        var forgottenBeforeRemoval = false
        store.onRemoveConnection = { id ->
            if (id == "one") {
                forgottenBeforeRemoval = gateway.forgotten.any { it.secretSlotId == "one" }
            }
        }

        subject.requestRemove("one")
        subject.confirmRemove()
        advanceUntilIdle()

        assertTrue("forget must land before the row is removed", forgottenBeforeRemoval)
        assertEquals(1, gateway.forgotten.size)
        assertEquals("one", gateway.forgotten.single().secretSlotId)
        assertNull(subject.uiState.value.connections.firstOrNull { it.id == "one" })
    }

    @Test
    fun `delete still erases a row whose URL is blank`() = runTest(dispatcher) {
        val blank = SavedConnection(
            id = "blank",
            label = "Blank",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = ""),
        )
        val other = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(blank, other), activeId = "two")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.requestRemove("blank")
        subject.confirmRemove()
        advanceUntilIdle()

        assertEquals(1, gateway.forgotten.size)
        assertEquals("blank", gateway.forgotten.single().secretSlotId)
        assertNull(subject.uiState.value.connections.firstOrNull { it.id == "blank" })
    }

    @Test
    fun `the last row cannot be removed`() = runTest(dispatcher) {
        val only = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(only), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.requestRemove("one")
        subject.confirmRemove()
        advanceUntilIdle()

        assertTrue(gateway.calls.isEmpty())
        assertTrue(gateway.forgotten.isEmpty())
        assertEquals(listOf(only), subject.uiState.value.connections)
    }

    @Test
    fun `an invalid Remote URL is refused with product copy`() = runTest(dispatcher) {
        val existing = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(existing), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginAdd()
        subject.editLabel("Alpha")
        subject.editUrl("not a url")
        subject.saveEditor()
        advanceUntilIdle()

        val editor = subject.uiState.value.editor
        assertNotNull(editor)
        assertEquals(ConnectionsCopy.INVALID_URL, editor?.error)
        assertEquals(listOf(existing), store.connectionRegistry.first().connections)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `a duplicate gateway URL is refused inline`() = runTest(dispatcher) {
        val one = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val two = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(one, two), activeId = "two")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editUrl("https://beta.test")
        subject.saveEditor()
        advanceUntilIdle()

        val editor = subject.uiState.value.editor
        assertNotNull(editor)
        assertEquals(ConnectionsCopy.duplicateUrl("Beta"), editor?.error)
        assertEquals(
            listOf(one, two),
            store.connectionRegistry.first().connections,
        )
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `re-addressing the active row tears the old endpoint down`() = runTest(dispatcher) {
        val one = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val two = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(one, two), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editUrl("https://alpha2.test")
        subject.saveEditor()
        advanceUntilIdle()

        assertTrue("the old endpoint must be torn down", gateway.calls.contains("disconnect"))
        assertTrue(
            "the abandoned credential must be erased",
            gateway.forgotten.any { it.secretSlotId == "one" && it.baseUrl == "https://alpha.test" },
        )
        assertEquals(
            "https://alpha2.test",
            store.connectionRegistry.first().connections.first { it.id == "one" }.remote.baseUrl,
        )
    }

    @Test
    fun `renaming the active row does not tear anything down`() = runTest(dispatcher) {
        val one = SavedConnection(
            id = "one",
            label = "Alpha",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"),
        )
        val two = SavedConnection(
            id = "two",
            label = "Beta",
            kind = ConnectionKind.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://beta.test"),
        )
        val gateway = RecordingGateway()
        val store = MemoryRegistryStore(listOf(one, two), activeId = "one")
        val subject = buildSubject(store, gateway)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.beginEdit("one")
        subject.editLabel("Alpha Renamed")
        subject.saveEditor()
        advanceUntilIdle()

        assertFalse(gateway.calls.contains("disconnect"))
        assertTrue(gateway.forgotten.isEmpty())
        assertEquals(
            "Alpha Renamed",
            store.connectionRegistry.first().connections.first { it.id == "one" }.label,
        )
    }

    private fun buildSubject(store: MemoryRegistryStore, gateway: RecordingGateway): ConnectionsViewModel {
        val switch = ConnectionSwitchController(store, gateway, SessionCache(), settleTimeoutMillis = 50L)
        return ConnectionsViewModel(store, gateway, switch)
    }

    private class RecordingGateway : GatewayConnectionController {
        private val _state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Disconnected))
        override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()

        /** Ordered record of every call this fake saw. */
        val calls = mutableListOf<String>()

        /** Every profile handed to [forgetRemoteAuthentication], in order. */
        val forgotten = mutableListOf<RemoteGatewayProfile>()

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            GatewayConnectResult.Connected

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = GatewayConnectResult.Connected

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) {
            calls += "forget"
            forgotten += profile
        }

        override suspend fun disconnect() {
            calls += "disconnect"
            // Publish Connected immediately so a caller's settle wait
            // resolves without burning virtual time.
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        }
    }

    private class MemoryRegistryStore(
        rows: List<SavedConnection>,
        activeId: String?,
    ) : ConnectionRegistryStore {
        private val registry = MutableStateFlow(ConnectionRegistry(rows, activeId))
        override val connectionRegistry: StateFlow<ConnectionRegistry> = registry.asStateFlow()

        /** Fired at the start of [removeConnection], before the row is gone, for ordering assertions. */
        var onRemoveConnection: ((String) -> Unit)? = null

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
            onRemoveConnection?.invoke(id)
            registry.update { current ->
                val rows = current.connections.filterNot { it.id == id }
                val active = if (current.activeId == id) rows.firstOrNull()?.id else current.activeId
                current.copy(connections = rows, activeId = active)
            }
        }

        override suspend fun setActiveConnection(id: String) {
            registry.update { it.copy(activeId = id) }
        }
    }
}
