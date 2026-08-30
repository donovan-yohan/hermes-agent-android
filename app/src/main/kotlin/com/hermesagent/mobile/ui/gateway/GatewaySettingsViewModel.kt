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
import kotlinx.coroutines.flow.combine
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

    /**
     * Whether this pane's own fields are ahead of the store.
     *
     * The route form persists on every keystroke, so the store echoes back what
     * was typed one write later; without this latch that echo would arrive
     * behind the next character and undo it. It says nothing about which row is
     * being edited, which is why [project] resets it.
     */
    private var edited = false

    /** The row [_uiState] is currently a projection of. */
    private var activeRowId: String? = null
    private var connectJob: Job? = null

    init {
        // Collected rather than read once, and collected together. The route,
        // the Gateway URL and the row they belong to are three projections of
        // one saved row (`HermesPreferences.connectionRegistry`), so a switch
        // has to reach this pane as a single change — a mode that arrived
        // without its URL would render one connection's route over another's
        // address.
        viewModelScope.launch {
            combine(
                store.activeConnectionId,
                store.gatewayConnectionMode,
                store.remoteGatewayProfile,
                ::Triple,
            ).collect { (row, mode, remote) -> project(row, mode, remote) }
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
     * Re-projects this pane onto whichever row is active.
     *
     * **A switch discards unsaved edits made in this pane, and the pane shows
     * the new row.** Switching is an explicit navigation to another connection,
     * not a way of moving text between two of them; the alternative keeps the
     * old row's half-typed address on screen while every field here autosaves
     * into whatever row is active, which is one connection's address written
     * into another. The store refuses that write as well
     * ([RemoteGatewayProfileStore.saveRemoteGatewayProfile]) — the two are one
     * rule, stated where it can be seen and enforced where it cannot be raced.
     * What is lost is at most the characters typed since the last keystroke
     * landed, all of them belonging to a connection the person has just
     * navigated away from.
     */
    private fun project(row: String?, mode: GatewayConnectionMode, remote: RemoteGatewayProfile) {
        if (row != activeRowId) {
            activeRowId = row
            edited = false
            // A dial aimed at the row we just left must not come up under the
            // one that replaced it. Putting the old endpoint down and forgetting
            // what it told us is the switch's own job and it already does both,
            // before it moves the marker
            // (`ConnectionSwitchController.kt:84,150-151`); the attempt this
            // ViewModel is still holding is the part that controller cannot see.
            cancelConnectionAttempt()
        }
        if (edited) return
        _uiState.update { it.copy(mode = mode, remote = remote, loaded = true) }
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

    /**
     * Dials the host-owned Gateway this pane is showing.
     *
     * Both the offer and the target come from the same projected row, so a tap
     * can only ever dial the connection whose address is on screen. A tap that
     * lands in the instant before a switch is the one case where those two come
     * apart, and it is caught on the way out rather than on the way in: [project]
     * cancels the attempt when the active row moves, and the switch itself has
     * already put the old endpoint down and cleared what it told us
     * (`ConnectionSwitchController.kt:84,150-151`).
     */
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
