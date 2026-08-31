package com.hermesagent.mobile.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.gateway.ActiveGatewayRoute
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
    /**
     * What this pane has to say about a sign-in it declined to start, which the
     * live connection cannot say for it: nothing was dialled, so
     * [connection] is still whatever it was. Null the rest of the time, and
     * cleared by the next thing the person does here.
     */
    val signInNotice: String? = null,
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

/**
 * Said when a sign-in is aimed at a connection this app has already left.
 *
 * It names the act and where to look — the pane re-projects onto whichever row
 * is now active, so "shown here" is the Gateway the person can see — and stops
 * there. Which row moved, and that anything was stamped at all, are this app's
 * business; the person's business is that nothing was signed into and the same
 * button is still the way to.
 */
internal const val SIGN_IN_CONNECTION_CHANGED =
    "The active connection changed before sign-in started. " +
        "Check the Gateway shown here, then sign in and connect."

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

    /**
     * A tap on Connect between reading the row it was composed against and
     * handing the sign-in to the process — see [connectRemote].
     *
     * Deliberately not [connectJob]: [project] cancels that one, and a switch
     * landing inside this window is the very case this job exists to report.
     * Only a person doing something else cancels it
     * ([cancelConnectionAttempt]).
     */
    private var signInStartJob: Job? = null

    init {
        // Collected rather than read once, and read as one value rather than
        // assembled from three. The store hands the row, its route and its
        // address out together (`RemoteGatewayProfileStore.activeGatewayRoute`)
        // precisely so a switch cannot reach this pane as a sequence, one step
        // of which is a new connection's route over the last one's address.
        viewModelScope.launch {
            store.activeGatewayRoute.collect(::project)
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
    private fun project(route: ActiveGatewayRoute) {
        if (route.connectionId != activeRowId) {
            // Whether this is a switch at all. A brand-new ViewModel starts with
            // no row, so its *first* projection always takes this branch — and
            // an Activity destroyed behind an open browser is rebuilt into
            // exactly that state. Treating that as a switch would have this
            // screen coming back and killing the process-scoped sign-in it was
            // rebuilt to show the result of, which is the failure this whole
            // slice exists to remove.
            val switched = activeRowId != null
            activeRowId = route.connectionId
            edited = false
            // A dial aimed at the row we just left must not come up under the
            // one that replaced it. Putting the old endpoint down and forgetting
            // what it told us is the switch's own job and it already does both,
            // before it moves the marker
            // (`ConnectionSwitchController.kt:84,150-151`); the attempt this
            // ViewModel is still holding is the part that controller cannot see.
            connectJob?.cancel()
            connectJob = null
            if (switched) gateway.cancelRemoteSignIn()
        }
        if (edited) return
        _uiState.update { it.copy(mode = route.mode, remote = route.remote, loaded = true) }
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
        val row = activeRowId
        edited = true
        cancelConnectionAttempt()
        _uiState.update { it.copy(mode = mode, loaded = true) }
        val abandonedSessionToken = previous.local
            .takeIf { previous.mode == GatewayConnectionMode.Local && it.secretSlotId.isNotBlank() }
        viewModelScope.launch {
            // Both consequences below belong to the row this tap was aimed at.
            // If the store dropped the write, that row is not the one this app
            // is on any more: the connection to put down and the token to erase
            // are the new row's, and neither has been changed by anything. A
            // write that *failed* is not that case and still tears down, since
            // the saved route may have moved regardless.
            var dropped = false
            try {
                dropped = !store.saveGatewayConnectionMode(mode, row)
            } finally {
                if (!dropped) leaveEndpoint()
            }
            if (!dropped) abandonedSessionToken?.let { gateway.forgetLocalAuthentication(it) }
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
     * apart — this pane is a projection, and a projection can be behind. What
     * follows is stamped for exactly that: the row the tap was composed against
     * is carried to the store, which is the only thing that knows which row is
     * active *now*, and a stamp that no longer matches ends the tap here. It is
     * the rule the route form and the mode selector already write under
     * (`RemoteGatewayProfileStore.saveRemoteGatewayProfile`,
     * `saveGatewayConnectionMode`), and a sign-in is the most expensive place
     * to get it wrong: the cheapest failure is a browser opened against the
     * Gateway the person has just left, and the dearest is a credential minted
     * for it. A blank stamp is a caller with no row in mind — a pre-registry
     * install — and dials wherever the marker points, which is that store rule
     * as well.
     *
     * A drop is said rather than swallowed ([SIGN_IN_CONNECTION_CHANGED]).
     * Nothing was dialled, so the connection state has nothing to report and
     * this pane has to.
     *
     * The stamp is compared against the **store**, and deliberately not against
     * [activeRowId]. That field and `remote.secretSlotId` are written by
     * [project] from one value, in one line of each other, so they are the same
     * answer twice: a check between them is `x == x` and cannot fail, least of
     * all in the window where this pane is behind — which is the only window
     * this guard exists for. The store is the one participant that is never a
     * projection.
     *
     * That read is not gapless, and does not need to be. It closes the window
     * that costs something — nothing reaches a browser or a token endpoint
     * until the answer is in — and a switch that lands *after* it is already
     * covered, reactively, by [project] cancelling the sign-in it finds running
     * against the row it left. A gapless version would have to arbitrate inside
     * the process-scoped connection layer, which would need the store injected
     * there; that is a deeper change than this failure is worth, and it is
     * filed rather than smuggled in here.
     *
     * The *sign-in* is deliberately not this ViewModel's coroutine. It sends
     * the person to a browser, and Android may destroy this screen while they
     * are there; a flow in [viewModelScope] would die with it, taking its
     * loopback callback listener along and leaving the person to come back to
     * an app that never noticed. The connection layer is process-scoped and
     * owns it instead — this pane keeps the right to *abandon* it
     * ([cancelConnectionAttempt]), which is not the same as being destroyed.
     * Only the stamp check above is this screen's, and it is over in one
     * store read: a tap whose screen is destroyed inside that window never
     * opened a browser and has nothing to survive.
     */
    fun connectRemote(browser: GatewayBrowserLauncher) {
        val state = _uiState.value
        if (state.mode != GatewayConnectionMode.Remote || !state.canConnectRemote) return
        val intended = state.remote
        cancelConnectionAttempt()
        signInStartJob = viewModelScope.launch {
            val active = store.activeGatewayRoute.first()
            val stamp = intended.secretSlotId
            if (stamp.isNotBlank() && stamp != active.connectionId) {
                _uiState.update { it.copy(signInNotice = SIGN_IN_CONNECTION_CHANGED) }
                return@launch
            }
            gateway.startRemoteSignIn(intended, browser)
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

    /**
     * Abandons whatever this pane started: the Local dial it owns, and the
     * process-scoped Remote sign-in it does not.
     *
     * Every caller is a person doing something — connecting, disconnecting,
     * changing the route, editing the address, signing out. [project] is
     * deliberately not one of them: a screen being rebuilt is not a decision.
     */
    private fun cancelConnectionAttempt() {
        connectJob?.cancel()
        connectJob = null
        signInStartJob?.cancel()
        signInStartJob = null
        // Every caller is the person acting on this pane, and acting is what
        // answers a notice about the last thing they did. Unconditional: a copy
        // that changes nothing is equal to what is there, and a StateFlow does
        // not re-emit that.
        _uiState.update { it.copy(signInNotice = null) }
        gateway.cancelRemoteSignIn()
    }

    /**
     * A destroyed screen is not a decision. The Local dial goes with
     * [viewModelScope] because it is this screen's coroutine and completes in
     * milliseconds; the Remote sign-in deliberately does not, because the
     * person is in a browser and this ViewModel being torn down behind them is
     * the ordinary case, not an abandonment.
     */
    override fun onCleared() {
        connectJob?.cancel()
        connectJob = null
        signInStartJob = null
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
