package com.hermesagent.mobile.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.gateway.GatewayBrowserLauncher
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
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

/** State for selecting Desktop-compatible Remote versus app-managed SSH. */
data class GatewaySettingsUiState(
    val mode: GatewayConnectionMode = GatewayConnectionMode.Remote,
    val remote: RemoteGatewayProfile = RemoteGatewayProfile(),
    val connection: GatewayConnectionState = GatewayConnectionState(),
    val loaded: Boolean = false,
) {
    val remoteUrlError: String?
        get() = if (remote.baseUrl.isBlank() || remote.isValid) null else "Enter an HTTPS Gateway URL."

    val canConnectRemote: Boolean
        get() = remote.isValid && connection.status != GatewayConnectionStatus.Connecting
}

internal class GatewaySettingsViewModel(
    private val store: RemoteGatewayProfileStore,
    private val gateway: GatewayConnectionController,
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
    }

    fun setMode(mode: GatewayConnectionMode) {
        if (_uiState.value.mode == mode) return
        edited = true
        cancelConnectionAttempt()
        _uiState.update { it.copy(mode = mode, loaded = true) }
        viewModelScope.launch {
            try {
                store.saveGatewayConnectionMode(mode)
            } finally {
                gateway.disconnect()
            }
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

    fun disconnect() {
        cancelConnectionAttempt()
        viewModelScope.launch { gateway.disconnect() }
    }

    fun forgetSignIn() {
        val profile = _uiState.value.remote
        cancelConnectionAttempt()
        viewModelScope.launch {
            gateway.disconnect()
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
                gateway.disconnect()
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
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GatewaySettingsViewModel(store, gateway) as T
        }
    }
}
