package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.ConnectionRegistry
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.gateway.ActiveGatewayRoute
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectResult
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfileStore
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.SshCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The route selector, which is not merely a view: it rewrites the *active
 * row's kind* (`HermesPreferences.saveGatewayConnectionMode`).
 *
 * That makes leaving the Local route a credential edge, not a navigation one —
 * the row stops naming any loopback address, so its session token is bound to
 * an address nothing can reach again. These pin that the slot is erased there,
 * and that the routes which did not gain a Keystore slot in this slice are left
 * exactly as they were.
 *
 * And the pane itself, which is a projection of one saved row: switching rows
 * has to re-project it, because every field on it autosaves into whichever row
 * is active — a pane left showing the row it switched away from is one
 * connection's address being typed into another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val processScopes = mutableListOf<CoroutineScope>()

    /**
     * Stands in for the process-scoped connection layer, which is where an
     * interactive sign-in runs now that a destroyed screen must not take it
     * ([GatewaySettingsViewModel.connectRemote]).
     *
     * Deliberately not `backgroundScope`: `advanceUntilIdle` does not drive
     * background work, and what this scope does when it is cancelled is the
     * very thing these tests assert on.
     */
    private fun processScope(): CoroutineScope = CoroutineScope(dispatcher + Job())
        .also { processScopes += it }

    @Before
    fun useVirtualMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun releaseMain() {
        processScopes.forEach { it.cancel() }
        processScopes.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `leaving the Local route erases the token bound to an address it no longer names`() =
        runTest(dispatcher) {
            val store = oneRow(
                ConnectionKind.Local,
                local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119"),
            )
            val gateway = RecordingGateway(processScope())
            val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()

            subject.setMode(GatewayConnectionMode.Remote)
            advanceUntilIdle()

            assertEquals(ConnectionKind.Remote, store.row("row-one").kind)
            assertEquals(
                "a slot nothing can address again is litter, not a sealed refusal",
                listOf("row-one"),
                gateway.forgottenLocal.map { it.secretSlotId },
            )
        }

    @Test
    fun `switching between the routes that own no session token erases nothing`() = runTest(dispatcher) {
        val store = oneRow(ConnectionKind.Remote, remote = RemoteGatewayProfile(baseUrl = "https://alpha.test"))
        val gateway = RecordingGateway(processScope())
        val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.setMode(GatewayConnectionMode.Ssh)
        advanceUntilIdle()

        assertTrue(
            "a browser sign-in is not cheap to redo, and this edge predates the Local route",
            gateway.forgottenLocal.isEmpty(),
        )
        assertTrue(gateway.forgottenRemote.isEmpty())
    }

    @Test
    fun `the Local route dials only an address this app can use`() = runTest(dispatcher) {
        val store = oneRow(ConnectionKind.Local)
        val gateway = RecordingGateway(processScope())
        val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.connectLocal()
        advanceUntilIdle()
        assertTrue("a row with no address has nothing to dial", gateway.localDials.isEmpty())

        store.setLocal(LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119"))
        advanceUntilIdle()
        subject.connectLocal()
        advanceUntilIdle()

        assertEquals(listOf("http://127.0.0.1:9119"), gateway.localDials.map { it.baseUrl })
    }

    @Test
    fun `the surface adds no second dial while a restore is still connecting`() = runTest(dispatcher) {
        val store = oneRow(
            ConnectionKind.Local,
            local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119"),
        )
        val gateway = RecordingGateway(processScope())
        val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        // A cold-start restore is dialling this very row, and the connection it
        // publishes is what tells this screen so.
        gateway.publish(GatewayConnectionStatus.Connecting)
        advanceUntilIdle()

        subject.connectLocal()
        advanceUntilIdle()

        assertTrue("the dial in flight is the dial", gateway.localDials.isEmpty())

        // Busy, never disabled: the same tap after that restore lands dials
        // exactly as it did before there was a restore at all.
        gateway.publish(GatewayConnectionStatus.NeedsAttention)
        advanceUntilIdle()
        subject.connectLocal()
        advanceUntilIdle()

        assertEquals(listOf("http://127.0.0.1:9119"), gateway.localDials.map { it.baseUrl })
    }

    @Test
    fun `switching connections re-projects the route, its fields and what Connect would dial`() =
        runTest(dispatcher) {
            val store = twoRows()
            store.switchTo("row-ssh")
            val gateway = RecordingGateway(processScope())
            val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()

            assertEquals(GatewayConnectionMode.Ssh, subject.uiState.value.mode)
            assertFalse(
                "an SSH row names no Gateway URL, so this pane has nothing to dial",
                subject.uiState.value.canConnectRemote,
            )

            store.switchTo("row-beta")
            advanceUntilIdle()

            val state = subject.uiState.value
            assertEquals(GatewayConnectionMode.Remote, state.mode)
            assertEquals("https://beta.test", state.remote.baseUrl)
            assertEquals("beta-provider", state.remote.provider)
            assertTrue("the row now active is one this pane can dial", state.canConnectRemote)

            subject.connectRemote { }
            advanceUntilIdle()

            assertEquals(
                "Connect dials the row on screen, not the one it switched away from",
                listOf("https://beta.test"),
                gateway.remoteDials.map { it.baseUrl },
            )
        }

    /**
     * The P1 this slice exists for: the pane's fields autosave into whichever
     * row is active, so text left over from the row a switch departed is text
     * about to be written into the row it arrived at.
     */
    @Test
    fun `a switch drops the unsaved edit that belonged to the row it left`() = runTest(dispatcher) {
        val store = twoRows()
        val gateway = RecordingGateway(processScope())
        val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.setRemoteUrl("https://alpha.test/typed")
        advanceUntilIdle()

        store.switchTo("row-beta")
        advanceUntilIdle()

        assertEquals(
            "the pane shows the row it switched to",
            "https://beta.test",
            subject.uiState.value.remote.baseUrl,
        )
        assertEquals(
            "arriving at a row does not rewrite it",
            "https://beta.test",
            store.row("row-beta").remote.baseUrl,
        )

        // The next keystroke is an edit of the row now on screen, and it has to
        // be composed from that row rather than from what was left of the last.
        subject.setProvider("switched-provider")
        advanceUntilIdle()

        assertEquals("https://beta.test", store.row("row-beta").remote.baseUrl)
        assertEquals("switched-provider", store.row("row-beta").remote.provider)
        assertEquals(
            "the row switched away from keeps what was typed into it, and only that",
            "https://alpha.test/typed",
            store.row("row-alpha").remote.baseUrl,
        )
    }

    @Test
    fun `a dial aimed at the row a switch left is abandoned rather than landing under the new one`() =
        runTest(dispatcher) {
            val store = twoRows()
            val gateway = RecordingGateway(processScope(), holdRemoteDial = true)
            val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()

            subject.connectRemote { }
            advanceUntilIdle()
            assertEquals(listOf("https://alpha.test"), gateway.remoteDials.map { it.baseUrl })

            store.switchTo("row-beta")
            advanceUntilIdle()

            assertEquals(
                "the attempt the switch controller cannot see is the one this pane has to drop",
                "https://alpha.test",
                gateway.abandonedRemoteDials.single().baseUrl,
            )
        }

    /**
     * The failure this whole slice exists to remove, from the other side.
     *
     * The person taps Connect, leaves for the browser, and Android destroys the
     * Activity behind them. When they come back the screen is rebuilt — a new
     * ViewModel over the same store, whose first projection has no row yet. If
     * that first projection is treated as a switch, the screen coming back kills
     * the process-scoped sign-in it was rebuilt to show the result of, and the
     * person watches their completed sign-in turn into "cancelled".
     */
    @Test
    fun `a screen rebuilt behind the browser does not abandon the sign-in`() = runTest(dispatcher) {
        val store = twoRows()
        val gateway = RecordingGateway(processScope(), holdRemoteDial = true)
        val screen = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
        backgroundScope.launch { screen.uiState.collect { } }
        advanceUntilIdle()

        screen.connectRemote { }
        advanceUntilIdle()
        assertEquals(listOf("https://alpha.test"), gateway.remoteDials.map { it.baseUrl })

        // The Activity is destroyed under the open browser and rebuilt.
        val rebuilt = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
        backgroundScope.launch { rebuilt.uiState.collect { } }
        advanceUntilIdle()

        assertTrue(
            "a screen coming back is not a decision to abandon a sign-in",
            gateway.abandonedRemoteDials.isEmpty(),
        )
        assertEquals(
            "and the rebuilt pane still shows the row that sign-in is for",
            "https://alpha.test",
            rebuilt.uiState.value.remote.baseUrl,
        )
    }

    /**
     * The switch window: the marker has moved and this pane has not been told
     * yet, so what it is showing — and anything typed into it — belongs to the
     * row that is already behind it.
     */
    @Test
    fun `a keystroke racing a switch is dropped, and the pane still lands on the row switched to`() =
        runTest(dispatcher) {
            val store = twoRows()
            val gateway = RecordingGateway(processScope())
            val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()

            store.switchTo("row-beta")
            subject.setRemoteUrl("https://alpha.test/typed")
            advanceUntilIdle()

            assertEquals(
                "the pane ends on the row the switch landed on, not pinned by the character it raced",
                "https://beta.test",
                subject.uiState.value.remote.baseUrl,
            )
            assertEquals("the row it arrived at is untouched", "https://beta.test", store.row("row-beta").remote.baseUrl)
            assertEquals(
                "and the character was not redirected into it",
                "https://alpha.test",
                store.row("row-alpha").remote.baseUrl,
            )
            assertEquals(listOf("row-alpha"), store.droppedWrites)
        }

    /**
     * The same window for the route selector. It is the more expensive one to
     * get wrong: a landed write rewrites the new row's *kind*, and the erase it
     * drags behind it would take a token belonging to a row nobody changed.
     */
    @Test
    fun `a route tap racing a switch is dropped, and the row it was aimed at keeps its token`() =
        runTest(dispatcher) {
            val store = MemoryProfileStore(
                listOf(
                    SavedConnection(
                        "row-local",
                        "Alpha",
                        ConnectionKind.Local,
                        local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119"),
                    ),
                    SavedConnection("row-ssh", "Beta", ConnectionKind.Ssh),
                ),
            )
            val gateway = RecordingGateway(processScope())
            val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()

            store.switchTo("row-ssh")
            subject.setMode(GatewayConnectionMode.Remote)
            advanceUntilIdle()

            assertEquals("the row it landed on keeps its kind", ConnectionKind.Ssh, store.row("row-ssh").kind)
            assertEquals("and so does the row it was aimed at", ConnectionKind.Local, store.row("row-local").kind)
            assertEquals(listOf("row-local"), store.droppedWrites)
            assertTrue(
                "a write that changed no route stranded no token",
                gateway.forgottenLocal.isEmpty(),
            )
            assertEquals(GatewayConnectionMode.Ssh, subject.uiState.value.mode)
        }

    /**
     * S-U5 (#116). The same switch window as the two above, at the pane's most
     * expensive control.
     *
     * A dropped keystroke costs a character. A sign-in composed against the row
     * this app has already left opens a browser at the previous Gateway and, if
     * the person completes it, mints a credential for it — so the tap carries
     * the row it was composed against and the store, which is the only thing
     * that knows which row is active now, is what answers.
     *
     * The second half is the teeth: the guard has to be a stamp check and not a
     * refusal to connect, so the same tap once the pane has caught up signs in.
     */
    @Test
    fun `a sign-in racing a switch is dropped rather than opening a browser at the row it left`() =
        runTest(dispatcher) {
            val store = twoRows()
            val gateway = RecordingGateway(processScope())
            val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()
            var browsersOpened = 0

            // The marker moves, and this pane has not been told yet: the tap is
            // composed against Alpha while Beta is the connection this app is on.
            store.switchTo("row-beta")
            subject.connectRemote { browsersOpened += 1 }
            advanceUntilIdle()

            assertTrue(
                "no sign-in was started, so nothing could reach the Gateway it left",
                gateway.remoteDials.isEmpty(),
            )
            assertEquals("and no browser was opened at all", 0, browsersOpened)
            assertEquals(
                "a drop the person can see, since nothing was dialled for the connection state to report",
                SIGN_IN_CONNECTION_CHANGED,
                subject.uiState.value.signInNotice,
            )
            assertEquals(
                "the pane is showing the row it landed on, which is what the notice points at",
                "https://beta.test",
                subject.uiState.value.remote.baseUrl,
            )

            subject.connectRemote { browsersOpened += 1 }
            advanceUntilIdle()

            assertEquals(
                "the same tap, once the pane has caught up, signs in to the row on screen",
                listOf("https://beta.test"),
                gateway.remoteDials.map { it.baseUrl },
            )
            assertNull("and the notice goes with the act that answered it", subject.uiState.value.signInNotice)
        }

    private fun oneRow(
        kind: ConnectionKind,
        remote: RemoteGatewayProfile = RemoteGatewayProfile(),
        local: LocalGatewayProfile = LocalGatewayProfile(),
    ) = MemoryProfileStore(listOf(SavedConnection("row-one", "Alpha", kind, remote = remote, local = local)))

    private fun twoRows() = MemoryProfileStore(
        listOf(
            SavedConnection(
                "row-alpha",
                "Alpha",
                ConnectionKind.Remote,
                remote = RemoteGatewayProfile(baseUrl = "https://alpha.test", provider = "alpha-provider"),
            ),
            SavedConnection(
                "row-beta",
                "Beta",
                ConnectionKind.Remote,
                remote = RemoteGatewayProfile(baseUrl = "https://beta.test", provider = "beta-provider"),
            ),
            SavedConnection("row-ssh", "Gamma", ConnectionKind.Ssh),
        ),
    )

    /**
     * Shaped like the real store: rows, a marker, every projection read off
     * whichever row the marker names, and the route handed out as one value.
     *
     * It honours the stamp rule too, because that rule is stated on
     * [RemoteGatewayProfileStore] rather than discovered in one implementation
     * of it — a fake that accepted a write the contract says is dropped would
     * make every test above it optimistic. Which row a dropped write was
     * composed against is recorded, since that is the only part of a drop a
     * caller can be held to.
     */
    private class MemoryProfileStore(rows: List<SavedConnection>) : RemoteGatewayProfileStore {
        private val registry = MutableStateFlow(ConnectionRegistry(rows, rows.first().id))

        override val remoteGatewayProfile: Flow<RemoteGatewayProfile> =
            registry.map { it.active?.remoteProfile ?: RemoteGatewayProfile() }
        override val gatewayConnectionMode: Flow<GatewayConnectionMode> =
            registry.map { it.active?.kind?.mode ?: GatewayConnectionMode.Remote }
        override val localGatewayProfile: Flow<LocalGatewayProfile> =
            registry.map { it.active?.localProfile ?: LocalGatewayProfile() }
        override val activeGatewayRoute: Flow<ActiveGatewayRoute> = registry
            .map { current ->
                val active = current.active
                ActiveGatewayRoute(
                    connectionId = active?.id,
                    mode = active?.kind?.mode ?: GatewayConnectionMode.Remote,
                    remote = active?.remoteProfile ?: RemoteGatewayProfile(),
                )
            }
            .distinctUntilChanged()

        /** The row each dropped write named, in the order they were dropped. */
        val droppedWrites = mutableListOf<String>()

        fun row(id: String): SavedConnection = registry.value.connections.first { it.id == id }

        /** Stands in for the switcher, which owns the marker and nothing else here. */
        fun switchTo(id: String) = registry.update { it.copy(activeId = id) }

        /** Stands in for the registry editor, the one writer of a row's Local route. */
        fun setLocal(profile: LocalGatewayProfile) {
            editActive(expected = null) { it.copy(local = profile) }
        }

        override suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile) {
            editActive(profile.secretSlotId.takeIf { it.isNotBlank() }) { active ->
                active.copy(remote = RemoteGatewayProfile(baseUrl = profile.baseUrl, provider = profile.provider))
            }
        }

        override suspend fun saveGatewayConnectionMode(
            mode: GatewayConnectionMode,
            expectedConnectionId: String?,
        ): Boolean = editActive(expectedConnectionId) { it.copy(kind = ConnectionKind.of(mode)) }

        private fun editActive(expected: String?, transform: (SavedConnection) -> SavedConnection): Boolean {
            val active = registry.value.active ?: return false
            if (expected != null && expected != active.id) {
                droppedWrites += expected
                return false
            }
            registry.update { current ->
                current.copy(
                    connections = current.connections.map { if (it.id == active.id) transform(it) else it },
                )
            }
            return true
        }
    }

    private class RecordingGateway(
        /**
         * Where an interactive sign-in actually runs now: the process, not the
         * screen. The ViewModel hands the flow over
         * ([GatewaySettingsViewModel.connectRemote]) precisely so a destroyed
         * Activity cannot take it, so the fake has to own a scope the way the
         * connection layer does.
         */
        private val scope: CoroutineScope,
        /** Holds the dial open, the way a browser sign-in does, so a cancel is observable. */
        private val holdRemoteDial: Boolean = false,
    ) : GatewayConnectionController {
        private var signIn: Job? = null
        private val _state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Disconnected))
        override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()

        /** Stands in for the app-scoped restore, which owns the same state. */
        fun publish(status: GatewayConnectionStatus) {
            _state.value = GatewayConnectionState(status)
        }

        val forgottenLocal = mutableListOf<LocalGatewayProfile>()
        val forgottenRemote = mutableListOf<RemoteGatewayProfile>()
        val localDials = mutableListOf<LocalGatewayProfile>()
        val remoteDials = mutableListOf<RemoteGatewayProfile>()
        val abandonedRemoteDials = mutableListOf<RemoteGatewayProfile>()

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            GatewayConnectResult.Connected

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult {
            remoteDials += profile
            if (holdRemoteDial) {
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    abandonedRemoteDials += profile
                    throw cancelled
                }
            }
            return GatewayConnectResult.Connected
        }

        override fun startRemoteSignIn(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher) {
            signIn = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectRemote(profile, browser) }
        }

        override fun cancelRemoteSignIn() {
            signIn?.cancel()
            signIn = null
        }

        override suspend fun connectLocal(profile: LocalGatewayProfile): GatewayConnectResult {
            localDials += profile
            return GatewayConnectResult.Connected
        }

        override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) {
            forgottenRemote += profile
        }

        override suspend fun forgetLocalAuthentication(profile: LocalGatewayProfile) {
            forgottenLocal += profile
        }

        override suspend fun disconnect() {
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        }
    }
}
