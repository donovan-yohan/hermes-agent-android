package com.hermesagent.mobile.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfileStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State for selecting a host-owned Remote Gateway, app-managed SSH, or a Hermes on this device. */
data class GatewaySettingsUiState(
    val mode: GatewayConnectionMode = GatewayConnectionMode.Remote,
    val remote: RemoteGatewayProfile = RemoteGatewayProfile(),
    /**
     * The active row's Local route, when it has one. Read-only here: the
     * registry owns every saved field, and this screen only dials what it
     * names.
     */
    val local: LocalGatewayProfile = LocalGatewayProfile(),
    val connection: GatewayConnectionState = GatewayConnectionState(),
    val loaded: Boolean = false,
) {
    val remoteUrlError: String?
        get() = if (remote.baseUrl.isBlank() || remote.isValid) null else "Enter an HTTPS Gateway URL."

    val canConnectRemote: Boolean
        get() = remote.isValid && connection.status != GatewayConnectionStatus.Connecting

    /**
     * An address this app can use is all this can check. Whether the session
     * token is still the right one, and whether `hermes serve` is running in
     * Termux at this moment, are answers only the dial has.
     */
    val canConnectLocal: Boolean
        get() = local.isValid && connection.status != GatewayConnectionStatus.Connecting
}

internal class GatewaySettingsViewModel(
    private val store: RemoteGatewayProfileStore,
    private val gateway: GatewayConnectionController,
    /**
     * What a saved route change costs: the live connection, and the sessions
     * that came from where it used to point. Two gateways can hand out the same
     * durable id, so the previous one's rows go rather than being merged into
     * whatever the next one reports.
     */
    private val leaveEndpoint: suspend () -> Unit = { gateway.disconnect() },
) : ViewModel() {
    private val _uiState = MutableStateFlow(GatewaySettingsUiState())
    val uiState: StateFlow<GatewaySettingsUiState> = _uiState.asStateFlow()
    private var edited = false
    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            val mode = store.gatewayConnectionMode.first()
            val remote = store.remoteGatewayProfile.first()
            if (!edited) _uiState.update { it.copy(mode = mode, remote = remote, loaded = true) }
        }
        viewModelScope.launch {
            gateway.state.collect { connection -> _uiState.update { it.copy(connection = connection) } }
        }
        // Collected rather than read once: this route's address is a projection
        // of the active registry row, so editing that row below has to reach the
        // pane above it without a reopen.
        viewModelScope.launch {
            store.localGatewayProfile.collect { local -> _uiState.update { it.copy(local = local) } }
        }
    }

    /**
     * Switches which route the active row is.
     *
     * This control rewrites the *saved row's kind* — see
     * `HermesPreferences.saveGatewayConnectionMode` — so moving off the Local
     * route leaves that row pointing at no loopback address at all, and its
     * session token bound to an address the row no longer names. The registry
     * editor erases a credential on exactly that edge, and so does this: a slot
     * nothing can address again is not "sealed and refused", it is litter, and
     * a session token is cheap to paste back where an OAuth sign-in is not.
     *
     * Deliberately narrowed to the Local slot. Remote's sign-in survives a
     * route change today, and turning a single tap into a sign-out is a change
     * to behaviour that predates this route — it belongs with the wider
     * question of whether this control should rewrite a saved row's kind at
     * all, not with adding a third option to it.
     */
    fun setMode(mode: GatewayConnectionMode) {
        val previous = _uiState.value
        if (previous.mode == mode) return
        edited = true
        cancelConnectionAttempt()
        _uiState.update { it.copy(mode = mode, loaded = true) }
        val abandonedSessionToken = previous.local
            .takeIf { previous.mode == GatewayConnectionMode.Local && it.secretSlotId.isNotBlank() }
        viewModelScope.launch {
            try {
                store.saveGatewayConnectionMode(mode)
            } finally {
                leaveEndpoint()
            }
            abandonedSessionToken?.let { gateway.forgetLocalAuthentication(it) }
        }
    }

    fun setRemoteUrl(value: String) = editRemote { it.copy(baseUrl = value) }

    fun setProvider(value: String) = editRemote { it.copy(provider = value) }

    fun connectRemote(browser: GatewayBrowserLauncher) {
        val state = _uiState.value
        if (state.mode != GatewayConnectionMode.Remote || !state.canConnectRemote) return
        val profile = state.remote
        connectJob?.cancel()
        connectJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.connectRemote(profile, browser)
        }
    }

    /**
     * Dials the Hermes on this device.
     *
     * No browser, no ticket and no process to start: the whole route is a
     * socket and the token the saved row already owns. It is still explicit,
     * because a token this app holds is not permission to dial a server the
     * person may have deliberately stopped.
     */
    fun connectLocal() {
        val state = _uiState.value
        if (state.mode != GatewayConnectionMode.Local || !state.canConnectLocal) return
        val profile = state.local
        connectJob?.cancel()
        connectJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.connectLocal(profile)
        }
    }

    /**
     * Both of these put the live connection down, so both go through
     * [leaveEndpoint] rather than reaching for the connection directly: it is
     * what serializes a teardown against an in-flight connection switch, and a
     * disconnect that lands after a switch has opened its socket would close
     * the wrong one.
     */
    fun disconnect() {
        cancelConnectionAttempt()
        viewModelScope.launch { leaveEndpoint() }
    }

    fun forgetSignIn() {
        val profile = _uiState.value.remote
        cancelConnectionAttempt()
        viewModelScope.launch {
            leaveEndpoint()
            gateway.forgetRemoteAuthentication(profile)
        }
    }

    private fun editRemote(transform: (RemoteGatewayProfile) -> RemoteGatewayProfile) {
        edited = true
        cancelConnectionAttempt()
        val updated = transform(_uiState.value.remote)
        _uiState.update { it.copy(remote = updated, loaded = true) }
        viewModelScope.launch {
            try {
                store.saveRemoteGatewayProfile(updated)
            } finally {
                leaveEndpoint()
            }
        }
    }

    private fun cancelConnectionAttempt() {
        connectJob?.cancel()
        connectJob = null
    }

    override fun onCleared() {
        cancelConnectionAttempt()
        super.onCleared()
    }

    companion object {
        fun factory(
            store: RemoteGatewayProfileStore,
            gateway: GatewayConnectionController,
            leaveEndpoint: suspend () -> Unit = { gateway.disconnect() },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GatewaySettingsViewModel(store, gateway, leaveEndpoint) as T
        }
    }
}
