package com.hermesagent.mobile.ui.gateway

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySettingsViewModelTest {

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
    fun `leaving the Local route erases the token bound to an address it no longer names`() =
        runTest(dispatcher) {
            val store = MemoryProfileStore(
                mode = GatewayConnectionMode.Local,
                local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119", secretSlotId = "row-one"),
            )
            val gateway = RecordingGateway()
            val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
            backgroundScope.launch { subject.uiState.collect { } }
            advanceUntilIdle()

            subject.setMode(GatewayConnectionMode.Remote)
            advanceUntilIdle()

            assertEquals(GatewayConnectionMode.Remote, store.mode.value)
            assertEquals(
                "a slot nothing can address again is litter, not a sealed refusal",
                listOf("row-one"),
                gateway.forgottenLocal.map { it.secretSlotId },
            )
        }

    @Test
    fun `switching between the routes that own no session token erases nothing`() = runTest(dispatcher) {
        val store = MemoryProfileStore(
            mode = GatewayConnectionMode.Remote,
            remote = RemoteGatewayProfile(baseUrl = "https://alpha.test", secretSlotId = "row-one"),
        )
        val gateway = RecordingGateway()
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
        val store = MemoryProfileStore(mode = GatewayConnectionMode.Local, local = LocalGatewayProfile())
        val gateway = RecordingGateway()
        val subject = GatewaySettingsViewModel(store, gateway) { gateway.disconnect() }
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()

        subject.connectLocal()
        advanceUntilIdle()
        assertTrue("a row with no address has nothing to dial", gateway.localDials.isEmpty())

        store.local.value = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119", secretSlotId = "row-one")
        advanceUntilIdle()
        subject.connectLocal()
        advanceUntilIdle()

        assertEquals(listOf("http://127.0.0.1:9119"), gateway.localDials.map { it.baseUrl })
    }

    @Test
    fun `the surface adds no second dial while a restore is still connecting`() = runTest(dispatcher) {
        val store = MemoryProfileStore(
            mode = GatewayConnectionMode.Local,
            local = LocalGatewayProfile(baseUrl = "http://127.0.0.1:9119", secretSlotId = "row-one"),
        )
        val gateway = RecordingGateway()
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

    private class MemoryProfileStore(
        mode: GatewayConnectionMode,
        remote: RemoteGatewayProfile = RemoteGatewayProfile(),
        local: LocalGatewayProfile = LocalGatewayProfile(),
    ) : RemoteGatewayProfileStore {
        val mode = MutableStateFlow(mode)
        val local = MutableStateFlow(local)
        private val remoteProfile = MutableStateFlow(remote)

        override val remoteGatewayProfile: StateFlow<RemoteGatewayProfile> = remoteProfile.asStateFlow()
        override val gatewayConnectionMode: StateFlow<GatewayConnectionMode> = this.mode.asStateFlow()
        override val localGatewayProfile: StateFlow<LocalGatewayProfile> = this.local.asStateFlow()

        override suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile) {
            remoteProfile.value = profile
        }

        override suspend fun saveGatewayConnectionMode(mode: GatewayConnectionMode) {
            this.mode.value = mode
        }
    }

    private class RecordingGateway : GatewayConnectionController {
        private val _state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Disconnected))
        override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()

        /** Stands in for the app-scoped restore, which owns the same state. */
        fun publish(status: GatewayConnectionStatus) {
            _state.value = GatewayConnectionState(status)
        }

        val forgottenLocal = mutableListOf<LocalGatewayProfile>()
        val forgottenRemote = mutableListOf<RemoteGatewayProfile>()
        val localDials = mutableListOf<LocalGatewayProfile>()

        override suspend fun connect(profile: HostProfile, credential: SshCredential): GatewayConnectResult =
            GatewayConnectResult.Connected

        override suspend fun connectRemote(
            profile: RemoteGatewayProfile,
            browser: GatewayBrowserLauncher,
        ): GatewayConnectResult = GatewayConnectResult.Connected

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
