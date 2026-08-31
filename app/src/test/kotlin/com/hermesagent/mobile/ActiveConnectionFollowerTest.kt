package com.hermesagent.mobile

import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.ConnectionRegistry
import com.hermesagent.mobile.data.connections.ConnectionRegistryStore
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfileStore
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshCredential
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app-scoped follower that re-dials when the active connection changes.
 *
 * It keys on which row is active, never on the row's fields, because editing a
 * Gateway URL is somebody typing — re-dialling per keystroke would put a
 * network round trip behind every character.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveConnectionFollowerTest {

    @Test
    fun `the active row is restored, and a different active row re-arms the restore`() = runTest {
        val store = MemoryStore(ROWS, activeId = "one")
        val connection = RecordingConnection()

        val follower = backgroundScope.launch { followActiveConnection(store, store, connection) }
        runCurrent()
        assertEquals(listOf("https://alpha.test"), connection.restored.map(RemoteGatewayProfile::baseUrl))

        store.registry.value = ConnectionRegistry(ROWS, activeId = "two")
        runCurrent()

        assertEquals(
            listOf("https://alpha.test", "https://beta.test"),
            connection.restored.map(RemoteGatewayProfile::baseUrl),
        )
        follower.cancel()
    }

    @Test
    fun `typing into the active row's address does not re-dial it`() = runTest {
        val store = MemoryStore(ROWS, activeId = "one")
        val connection = RecordingConnection()

        val follower = backgroundScope.launch { followActiveConnection(store, store, connection) }
        runCurrent()

        val partiallyTyped = ROWS.first().copy(remote = RemoteGatewayProfile("https://alpha.tes"))
        store.registry.value = ConnectionRegistry(listOf(partiallyTyped, ROWS[1]), activeId = "one")
        runCurrent()

        assertEquals(1, connection.restored.size)
        follower.cancel()
    }

    @Test
    fun `an explicit re-arm re-dials a row whose id did not move`() = runTest {
        val store = MemoryStore(ROWS, activeId = "one")
        val connection = RecordingConnection()
        val rearm = MutableStateFlow(0L)

        val follower = backgroundScope.launch { followActiveConnection(store, store, connection, rearm) }
        runCurrent()
        assertEquals(1, connection.restored.size)

        // Re-addressing the active row: the id is unchanged, so only the
        // explicit signal can tell the follower there is somewhere new to dial.
        store.registry.value = ConnectionRegistry(
            listOf(ROWS.first().copy(remote = RemoteGatewayProfile("https://renamed.test")), ROWS[1]),
            activeId = "one",
        )
        runCurrent()
        assertEquals("a persisted edit alone must not re-dial", 1, connection.restored.size)

        rearm.value += 1
        runCurrent()

        assertEquals(
            listOf("https://alpha.test", "https://renamed.test"),
            connection.restored.map(RemoteGatewayProfile::baseUrl),
        )
        follower.cancel()
    }

    @Test
    fun `a managed SSH row is not dialled, because its credential dies with the connection`() = runTest {
        val rows = listOf(
            ROWS.first(),
            SavedConnection("two", "Beta", ConnectionKind.Ssh, host = HostProfile("demo-host", 22, "demo-user")),
        )
        val store = MemoryStore(rows, activeId = "two")
        val connection = RecordingConnection()

        val follower = backgroundScope.launch { followActiveConnection(store, store, connection) }
        runCurrent()

        assertEquals(emptyList<RemoteGatewayProfile>(), connection.restored)
        follower.cancel()
    }

    @Test
    fun `switching to a Local row restores it once, on the route it is actually on`() = runTest {
        val rows = listOf(
            ROWS.first(),
            SavedConnection(
                "two",
                "Termux",
                ConnectionKind.Local,
                local = LocalGatewayProfile("http://127.0.0.1:9119"),
            ),
        )
        val store = MemoryStore(rows, activeId = "one")
        val connection = RecordingConnection()

        val follower = backgroundScope.launch { followActiveConnection(store, store, connection) }
        runCurrent()
        assertEquals(1, connection.restored.size)

        store.registry.value = ConnectionRegistry(rows, activeId = "two")
        runCurrent()

        assertEquals(
            "the row's own id is what addresses its Keystore slot",
            listOf("http://127.0.0.1:9119" to "two"),
            connection.restoredLocal.map { it.baseUrl to it.secretSlotId },
        )
        assertEquals("and the Remote route was not dialled again", 1, connection.restored.size)
        follower.cancel()
    }

    @Test
    fun `a Local row with no address is not dialled`() = runTest {
        val rows = listOf(SavedConnection("one", "Termux", ConnectionKind.Local))
        val store = MemoryStore(rows, activeId = "one")
        val connection = RecordingConnection()

        val follower = backgroundScope.launch { followActiveConnection(store, store, connection) }
        runCurrent()

        assertEquals(emptyList<LocalGatewayProfile>(), connection.restoredLocal)
        follower.cancel()
    }

    private class MemoryStore(
        rows: List<SavedConnection>,
        activeId: String?,
    ) : ConnectionRegistryStore, RemoteGatewayProfileStore {
        val registry = MutableStateFlow(ConnectionRegistry(rows, activeId))
        override val connectionRegistry: Flow<ConnectionRegistry> = registry
        override val remoteGatewayProfile: Flow<RemoteGatewayProfile> =
            registry.map { it.active?.remoteProfile ?: RemoteGatewayProfile() }
        override val gatewayConnectionMode: Flow<GatewayConnectionMode> =
            registry.map { it.active?.kind?.mode ?: GatewayConnectionMode.Remote }
        override val localGatewayProfile: Flow<LocalGatewayProfile> =
            registry.map { it.active?.localProfile ?: LocalGatewayProfile() }

        override suspend fun saveConnection(connection: SavedConnection) = Unit
        override suspend fun removeConnection(id: String) = Unit
        override suspend fun setActiveConnection(id: String) {
            registry.value = registry.value.copy(activeId = id)
        }

        override suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile) = Unit
        override suspend fun saveGatewayConnectionMode(
            mode: GatewayConnectionMode,
            expectedConnectionId: String?,
        ): Boolean = false
    }

    private class RecordingConnection : GatewayConnectionController {
        override val state = MutableStateFlow(GatewayConnectionState())
        val restored = mutableListOf<RemoteGatewayProfile>()
        val restoredLocal = mutableListOf<LocalGatewayProfile>()

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            error("SSH is never dialled by the follower")

        override fun startRemoteSignIn(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher) =
            error("interactive sign-in is never started by the follower")

        override fun cancelRemoteSignIn() = Unit

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = error("interactive sign-in is never started by the follower")

        override suspend fun restoreRemote(profile: RemoteGatewayProfile): GatewayConnectResult {
            restored += profile
            return GatewayConnectResult.Connected
        }

        override suspend fun restoreLocal(profile: LocalGatewayProfile): GatewayConnectResult {
            restoredLocal += profile
            return GatewayConnectResult.Connected
        }

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) = Unit

        override suspend fun disconnect() = Unit
    }

    private companion object {
        val ROWS = listOf(
            SavedConnection("one", "Alpha", ConnectionKind.Remote, RemoteGatewayProfile("https://alpha.test")),
            SavedConnection("two", "Beta", ConnectionKind.Remote, RemoteGatewayProfile("https://beta.test")),
        )
    }
}
