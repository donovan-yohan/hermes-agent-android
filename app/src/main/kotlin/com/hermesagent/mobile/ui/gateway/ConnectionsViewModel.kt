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
import com.hermesagent.mobile.data.connections.normalizeGatewayUrl
import com.hermesagent.mobile.data.connections.sortConnectionsForDisplay
import com.hermesagent.mobile.data.gateway.DEFAULT_LOCAL_GATEWAY_URL
import com.hermesagent.mobile.data.gateway.GatewayConnectionController
import com.hermesagent.mobile.data.gateway.LocalGatewayCopy
import com.hermesagent.mobile.data.gateway.LocalGatewayProfile
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
    /**
     * The Local route's session token, for the life of this form only.
     *
     * Never read back out of the Keystore slot — a form that could show a
     * stored token would make every screenshot of this screen a credential —
     * so blank on an existing row means "keep the saved one" and blank on a new
     * or re-addressed one is refused.
     */
    val token: String = "",
    val error: String? = null,
) {
    val canSave: Boolean get() = label.isNotBlank()

    /**
     * Hand-written, like [com.hermesagent.mobile.ui.ssh.SshUiState]'s: a
     * generated `toString()` would print a live session token into whatever log
     * or crash report happened to be holding this state.
     */
    override fun toString(): String =
        "ConnectionEditorState(id=$id, kind=$kind, label=$label, url=$url, provider=$provider, " +
            "destination=$destination, token=<redacted>, error=$error)"
}

data class ConnectionsUiState(
    val connections: List<SavedConnection> = emptyList(),
    val activeId: String? = null,
    val pendingId: String? = null,
    val editor: ConnectionEditorState? = null,
    val removeTarget: SavedConnection? = null,
    val loaded: Boolean = false,
    /** False when the stored registry belongs to a build this one cannot read. */
    val writable: Boolean = true,
) {
    /** One stable order for both the settings list and the session-rail sheet. */
    val ordered: List<SavedConnection> get() = sortConnectionsForDisplay(connections)

    val active: SavedConnection? get() = ConnectionRegistry(connections, activeId).active

    /** Desktop shows no source chrome at all for one connection (`connection-switcher.tsx:118-120`). */
    val switchable: Boolean get() = connections.size > 1

    val canRemove: Boolean get() = connections.size > 1

    /**
     * The label of another saved Remote row already pointing at [baseUrl], or
     * null. Desktop's dedupe key exactly (`normalizeGatewayUrl`), asked from
     * the route form above the list, which has no discrete save to refuse.
     */
    fun duplicateRemoteLabel(baseUrl: String): String? {
        val key = normalizeGatewayUrl(baseUrl)
        if (key.isEmpty()) return null
        return connections
            .firstOrNull { it.id != activeId && it.kind == ConnectionKind.Remote && normalizeGatewayUrl(it.remote.baseUrl) == key }
            ?.label
    }
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
        viewModelScope.launch {
            store.connectionRegistryWritable.collect { writable ->
                _uiState.update { it.copy(writable = writable) }
            }
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
                    url = row.endpointUrl.ifBlank { prefilledUrl(row.kind) },
                    provider = row.remote.provider,
                    destination = row.host.destination,
                ),
            )
        }
    }

    /**
     * Closes the form and drops what it was holding, the token included. The
     * same call covers Cancel and a save that has been accepted.
     */
    fun cancelEditor() {
        _uiState.update { it.copy(editor = null) }
    }

    /**
     * Ends this form's secret lifetime when the Gateways surface goes.
     *
     * This ViewModel is Activity-scoped while the surface is one destination
     * inside a single composition, so leaving it destroys nothing by itself —
     * without this a typed session token would still be here when the screen is
     * reopened, and would still be in a state snapshot in between. The form
     * itself stays: the address and the name are not secrets, and losing them
     * on a stray back gesture would be a worse screen, not a safer one.
     *
     * Called from the same disposal that clears `FLAG_SECURE`, and ahead of it.
     * Idempotent, and safe on a screen that never held anything.
     */
    fun releaseScreen() {
        _uiState.update { state ->
            val editor = state.editor ?: return@update state
            if (editor.token.isEmpty()) state else state.copy(editor = editor.copy(token = ""))
        }
    }

    /**
     * The kind is a choice only while creating, and refused rather than quietly
     * applied afterwards (`connections-registry.tsx:649-654` @ `f82f2dba`, whose
     * buttons disable on edit): the fields a row carries, the trust it has
     * accepted and the secret slot it owns all belong to one kind.
     *
     * The address travels with the choice. Local is the one route with an
     * address worth guessing — there is exactly one Hermes anyone starts by
     * default, on the port upstream documents — so choosing it fills that in,
     * and choosing away from it takes the guess back out rather than leaving a
     * loopback address sitting in a Remote form.
     */
    fun editKind(kind: ConnectionKind) = editEditor { editor ->
        if (editor.id != null) {
            editor
        } else {
            editor.copy(
                kind = kind,
                url = when {
                    editor.url.isBlank() -> prefilledUrl(kind)
                    editor.url == DEFAULT_LOCAL_GATEWAY_URL && kind != ConnectionKind.Local -> ""
                    else -> editor.url
                },
                token = if (kind == ConnectionKind.Local) editor.token else "",
            )
        }
    }

    fun editLabel(value: String) = editEditor { it.copy(label = value) }

    fun editUrl(value: String) = editEditor { it.copy(url = value) }

    fun editProvider(value: String) = editEditor { it.copy(provider = value) }

    fun editDestination(value: String) = editEditor { it.copy(destination = value) }

    fun editToken(value: String) = editEditor { it.copy(token = value) }

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
        // A store that cannot be written says so here, with the editor still
        // open and the typing still in it. Closing the editor over a write that
        // never happened is the one outcome that looks like success.
        if (!_uiState.value.writable) {
            _uiState.update { it.copy(editor = editor.copy(error = ConnectionsCopy.REGISTRY_LOCKED)) }
            return
        }
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

            ConnectionKind.Remote, ConnectionKind.Local -> existing?.host ?: HostProfile()
        }
        val url = editor.url.trim()
        val candidate = SavedConnection(
            id = editor.id ?: newConnectionId(),
            label = editor.label.trim(),
            kind = editor.kind,
            // One typed address, filed under the kind that can use it. A Remote
            // row and a Local row are refused by different normalizers, and a
            // row that carried both would be claiming to be two connections.
            remote = if (editor.kind == ConnectionKind.Local) {
                RemoteGatewayProfile()
            } else {
                RemoteGatewayProfile(baseUrl = url, provider = editor.provider.trim())
            },
            host = host,
            local = if (editor.kind == ConnectionKind.Local) LocalGatewayProfile(baseUrl = url) else LocalGatewayProfile(),
        )
        // A Remote row whose URL cannot be addressed is refused rather than
        // saved: an unaddressable row is one whose sign-in nothing can reach,
        // and it is exactly how a credential ends up orphaned on disk. The same
        // check rejects a URL carrying userinfo, which normalization refuses.
        if (candidate.kind == ConnectionKind.Remote && !candidate.remote.isValid) {
            _uiState.update { it.copy(editor = editor.copy(error = ConnectionsCopy.INVALID_URL)) }
            return
        }
        // Same rule, other normalizer: a Local row this app cannot address is a
        // row whose session token nothing can reach.
        if (candidate.kind == ConnectionKind.Local && !candidate.local.isValid) {
            _uiState.update { it.copy(editor = editor.copy(error = LocalGatewayCopy.INVALID_URL)) }
            return
        }
        findDuplicateConnection(candidate, _uiState.value.connections)?.let { clash ->
            val message = when (editor.kind) {
                ConnectionKind.Remote, ConnectionKind.Local -> ConnectionsCopy.duplicateUrl(clash.label)
                ConnectionKind.Ssh -> ConnectionsCopy.duplicateSsh(clash.label)
            }
            _uiState.update { it.copy(editor = editor.copy(error = message)) }
            return
        }
        // The Local route's one credential, asked for where the person can
        // still supply it. A new row has no saved token, and a re-addressed one
        // may not use the token it has — the slot is bound to the address that
        // minted it — so both are refused here rather than at the first dial,
        // where the only remaining advice would be to come back to this form.
        val wasLocalAtSameAddress = existing?.kind == ConnectionKind.Local &&
            existing.local.normalizedBaseUrl == candidate.local.normalizedBaseUrl
        // Trimmed once, and the same value decides both the refusal and the
        // write. A token is pasted out of a Termux terminal, so it arrives with
        // the newline that ended the line it was on, and the Gateway compares
        // it literally — an untrimmed paste is a permanent 401 with nothing on
        // screen to explain it. Two different emptiness tests would be the same
        // bug from the other side: a field holding only spaces would fail to
        // count as missing and would overwrite a working token with them.
        val token = editor.token.trim()
        if (candidate.kind == ConnectionKind.Local && token.isEmpty() && !wasLocalAtSameAddress) {
            val message = if (existing?.kind == ConnectionKind.Local) {
                ConnectionsCopy.TOKEN_READDRESSED
            } else {
                ConnectionsCopy.TOKEN_REQUIRED
            }
            _uiState.update { it.copy(editor = editor.copy(error = message)) }
            return
        }
        // What survives the trim still has to be something the header can
        // carry. Anything outside printable ASCII would be flattened to `?` by
        // the ASCII encoding below and then refused by a Gateway that never
        // minted it; a control character is refused by the HTTP client itself,
        // as an exception rather than a sentence. Neither is a diagnosis, so
        // the refusal happens here instead.
        if (candidate.kind == ConnectionKind.Local && token.any { it.code !in 0x21..0x7E }) {
            _uiState.update { it.copy(editor = editor.copy(error = ConnectionsCopy.TOKEN_UNREADABLE)) }
            return
        }
        val readdressed = candidate.id == _uiState.value.activeId &&
            (
                existing == null ||
                    existing.kind != candidate.kind ||
                    existing.remote != candidate.remote ||
                    // The Local address lives in its own profile, so a Local row
                    // that was re-addressed changes nothing the two clauses
                    // above can see — and would come up on the old endpoint.
                    // Normalized, like the token rule above and the erase rule
                    // below: raw equality would call a trailing slash a new
                    // address and drop a live socket that never moved.
                    existing.local.normalizedBaseUrl != candidate.local.normalizedBaseUrl ||
                    existing.host != candidate.host
                )
        // A stored sign-in belongs to the host that minted it. When a row stops
        // pointing at that host — a new URL, or a change of kind — its
        // credential is erased here rather than left for the load path to
        // refuse later. The refusal is still the guarantee; this is the tidy-up.
        val abandonedSignIn = existing
            ?.takeIf { it.kind == ConnectionKind.Remote }
            ?.takeIf {
                candidate.kind != ConnectionKind.Remote ||
                    it.remote.normalizedBaseUrl != candidate.remote.normalizedBaseUrl
            }
        // The Local route's session token is bound to its address the same way,
        // so it is abandoned on the same two edges: a change of kind, or a
        // change of address.
        val abandonedSessionToken = existing
            ?.takeIf { it.kind == ConnectionKind.Local }
            ?.takeIf {
                candidate.kind != ConnectionKind.Local ||
                    it.local.normalizedBaseUrl != candidate.local.normalizedBaseUrl
            }
        // Read out of the editor before it is dropped, and handed on as bytes
        // the store takes ownership of and zeroes.
        val typedToken = token
            .takeIf { candidate.kind == ConnectionKind.Local && it.isNotEmpty() }
            ?.toByteArray(Charsets.US_ASCII)
        _uiState.update { it.copy(editor = null) }
        viewModelScope.launch {
            abandonedSignIn?.let { gateway.forgetRemoteAuthentication(it.remoteProfile) }
            abandonedSessionToken?.let { gateway.forgetLocalAuthentication(it.localProfile) }
            // After the erase and never before it: a re-addressed row keeps its
            // id, so the two are the same slot, and writing first would hand the
            // new token to the erase that was meant for the old one. And before
            // the row is written, because re-addressing the *active* row redials
            // as part of that write — a token that landed afterwards would miss
            // the dial it exists for. The slot is bound to the address, not to
            // the stored row, so nothing here depends on the row existing yet.
            // Guarded, unlike the erase beside it: sealing a credential can
            // fail on a Keystore whose alias was invalidated, and this write
            // also touches the filesystem. Uncaught, it would take the process
            // down *and* skip the row write below — losing the connection the
            // person just saved, with the form already closed behind them. The
            // erase still lands first either way.
            val tokenStored = typedToken == null ||
                runCatching { gateway.saveLocalSessionToken(candidate.localProfile, typedToken) }.isSuccess
            if (!tokenStored) {
                // Nothing was written, so nothing is half-saved: the row does
                // not go in, and the form comes back with the typing still in
                // it rather than reporting a success that did not happen.
                _uiState.update { it.copy(editor = editor.copy(token = token, error = ConnectionsCopy.TOKEN_NOT_STORED)) }
                return@launch
            }
            // Renaming the connection you are on changes nothing about where it
            // points. Re-addressing it points somewhere else, so it leaves the
            // old address and comes up on the new one under a pending state,
            // rather than being left disconnected with no way back.
            if (readdressed) {
                switch.readdressActive { store.saveConnection(candidate) }
            } else {
                store.saveConnection(candidate)
            }
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
        if (!state.writable || !state.canRemove) {
            _uiState.update { it.copy(removeTarget = null) }
            return
        }
        _uiState.update { it.copy(removeTarget = null) }
        viewModelScope.launch {
            if (state.activeId == target.id) switch.abandonCurrentEndpoint()
            when (target.kind) {
                ConnectionKind.Remote -> gateway.forgetRemoteAuthentication(target.remoteProfile)
                ConnectionKind.Local -> gateway.forgetLocalAuthentication(target.localProfile)
                ConnectionKind.Ssh -> Unit
            }
            store.removeConnection(target.id)
        }
    }

    /**
     * What a kind's address field starts as. Only the Local route has one worth
     * guessing: upstream documents exactly one `hermes serve` on one port
     * (`website/docs/getting-started/termux.md` @ `f82f2dba`), and it is still
     * editable — the address rule refuses whatever it cannot use.
     */
    private fun prefilledUrl(kind: ConnectionKind): String =
        if (kind == ConnectionKind.Local) DEFAULT_LOCAL_GATEWAY_URL else ""

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
