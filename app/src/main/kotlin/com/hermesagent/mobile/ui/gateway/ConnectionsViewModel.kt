package com.hermesagent.mobile.ui.gateway

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.connections.ConnectionRegistry
import com.hermesagent.mobile.data.connections.ConnectionRegistryStore
import com.hermesagent.mobile.data.connections.ConnectionSwitchController
import com.hermesagent.mobile.data.connections.SavedConnection
import com.hermesagent.mobile.data.connections.findDuplicateConnection
import com.hermesagent.mobile.data.connections.newConnectionId
import com.hermesagent.mobile.data.connections.sortConnectionsForDisplay
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.RemoteGatewayProfile
import com.hermesagent.mobile.data.ssh.DestinationParse
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.parseSshDestination
import com.hermesagent.mobile.data.ssh.redact
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Adding, editing or removing a saved connection.
 *
 * The kind is chosen on create and fixed afterwards, as Desktop's editor fixes
 * it (`apps/desktop/src/app/settings/connections-registry.tsx:649-654` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`): the fields a row carries, the
 * trust it has accepted and the secret slot it owns all belong to one kind, and
 * changing it under them would quietly invalidate all three.
 */
data class ConnectionEditorState(
    /** Null while creating. */
    val id: String? = null,
    val kind: ConnectionKind = ConnectionKind.Remote,
    val label: String = "",
    val url: String = "",
    val provider: String = "",
    /** The one SSH field: `user@host`, port 22 implicit. */
    val destination: String = "",
    val error: String? = null,
) {
    val canSave: Boolean get() = label.isNotBlank()
}

data class ConnectionsUiState(
    val connections: List<SavedConnection> = emptyList(),
    val activeId: String? = null,
    val pendingId: String? = null,
    val editor: ConnectionEditorState? = null,
    val removeTarget: SavedConnection? = null,
    val loaded: Boolean = false,
) {
    /** One stable order for both the settings list and the session-rail sheet. */
    val ordered: List<SavedConnection> get() = sortConnectionsForDisplay(connections)

    val active: SavedConnection? get() = ConnectionRegistry(connections, activeId).active

    /** Desktop shows no source chrome at all for one connection (`connection-switcher.tsx:118-120`). */
    val switchable: Boolean get() = connections.size > 1

    val canRemove: Boolean get() = connections.size > 1
}

internal class ConnectionsViewModel(
    private val store: ConnectionRegistryStore,
    private val gateway: GatewayConnectionController,
    private val switch: ConnectionSwitchController,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()
    private var switchJob: Job? = null

    init {
        viewModelScope.launch {
            store.connectionRegistry.collect { registry ->
                _uiState.update {
                    it.copy(
                        connections = registry.connections,
                        activeId = registry.active?.id,
                        loaded = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            switch.pendingConnectionId.collect { id -> _uiState.update { it.copy(pendingId = id) } }
        }
    }

    /**
     * Re-home to another saved connection. A second tap while one is in flight
     * is ignored rather than queued: two teardowns racing is how a connection
     * ends up pointing at neither endpoint.
     */
    fun select(id: String) {
        if (switchJob?.isActive == true) return
        if (_uiState.value.activeId == id) return
        switchJob = viewModelScope.launch { switch.select(id) }
    }

    fun beginAdd() {
        _uiState.update { it.copy(editor = ConnectionEditorState()) }
    }

    fun beginEdit(id: String) {
        val row = _uiState.value.connections.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                editor = ConnectionEditorState(
                    id = row.id,
                    kind = row.kind,
                    label = row.label,
                    url = row.remote.baseUrl,
                    provider = row.remote.provider,
                    destination = row.host.destination,
                ),
            )
        }
    }

    fun cancelEditor() {
        _uiState.update { it.copy(editor = null) }
    }

    fun editKind(kind: ConnectionKind) = editEditor { it.copy(kind = kind) }

    fun editLabel(value: String) = editEditor { it.copy(label = value) }

    fun editUrl(value: String) = editEditor { it.copy(url = value) }

    fun editProvider(value: String) = editEditor { it.copy(provider = value) }

    fun editDestination(value: String) = editEditor { it.copy(destination = value) }

    /**
     * Validates, then writes.
     *
     * The SSH destination goes through [parseSshDestination] and is applied
     * with [HostProfile.withDestination], which is what keeps the trust rule
     * true per row: changing the host or the port drops that row's accepted
     * fingerprint so the next probe is a first use again, while renaming the
     * user keeps it — it is the same box with the same key.
     */
    fun saveEditor() {
        val editor = _uiState.value.editor ?: return
        if (!editor.canSave) return
        val existing = _uiState.value.connections.firstOrNull { it.id == editor.id }
        val host = when (editor.kind) {
            ConnectionKind.Ssh -> {
                val base = existing?.host ?: HostProfile()
                when (val parsed = parseSshDestination(editor.destination)) {
                    is DestinationParse.Invalid -> {
                        _uiState.update { it.copy(editor = editor.copy(error = redact(parsed.reason))) }
                        return
                    }

                    is DestinationParse.Valid -> base.withDestination(parsed.destination)
                }
            }

            ConnectionKind.Remote -> existing?.host ?: HostProfile()
        }
        val candidate = SavedConnection(
            id = editor.id ?: newConnectionId(),
            label = editor.label.trim(),
            kind = editor.kind,
            remote = RemoteGatewayProfile(baseUrl = editor.url.trim(), provider = editor.provider.trim()),
            host = host,
        )
        findDuplicateConnection(candidate, _uiState.value.connections)?.let { clash ->
            val message = when (editor.kind) {
                ConnectionKind.Remote -> ConnectionsCopy.duplicateUrl(clash.label)
                ConnectionKind.Ssh -> ConnectionsCopy.duplicateSsh(clash.label)
            }
            _uiState.update { it.copy(editor = editor.copy(error = message)) }
            return
        }
        val readdressed = candidate.id == _uiState.value.activeId &&
            (existing == null || existing.kind != candidate.kind || existing.remote != candidate.remote || existing.host != candidate.host)
        _uiState.update { it.copy(editor = null) }
        viewModelScope.launch {
            // Renaming the connection you are on changes nothing about where it
            // points. Re-addressing it points somewhere else, and the sessions
            // on screen belong to where it pointed before.
            if (readdressed) switch.leaveCurrentEndpoint()
            store.saveConnection(candidate)
        }
    }

    fun requestRemove(id: String) {
        val row = _uiState.value.connections.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(removeTarget = row) }
    }

    fun cancelRemove() {
        _uiState.update { it.copy(removeTarget = null) }
    }

    /**
     * Removes the row and erases its credential.
     *
     * Removing the connection this device is *on* leaves that endpoint first —
     * the same teardown a switch performs — so its sessions are cleared rather
     * than left painted under a row that no longer exists. The store then moves
     * the active marker to the first survivor and the route follower dials it.
     * The Keystore slot goes before the row does: a row that is gone has
     * nothing left to name its secret with.
     */
    fun confirmRemove() {
        val target = _uiState.value.removeTarget ?: return
        val state = _uiState.value
        if (!state.canRemove) {
            _uiState.update { it.copy(removeTarget = null) }
            return
        }
        _uiState.update { it.copy(removeTarget = null) }
        viewModelScope.launch {
            if (state.activeId == target.id) switch.leaveCurrentEndpoint()
            if (target.kind == ConnectionKind.Remote) {
                gateway.forgetRemoteAuthentication(target.remoteProfile)
            }
            store.removeConnection(target.id)
        }
    }

    private fun editEditor(transform: (ConnectionEditorState) -> ConnectionEditorState) {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = transform(editor).copy(error = null))
        }
    }

    companion object {
        fun factory(
            store: ConnectionRegistryStore,
            gateway: GatewayConnectionController,
            switch: ConnectionSwitchController,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ConnectionsViewModel(store, gateway, switch) as T
        }
    }
}
