package com.hermesagent.mobile.data.themes

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayRestClient
import com.hermesagent.mobile.data.gateway.GatewayRestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GatewayThemesState(
    val themes: List<GatewayTheme> = emptyList(),
    val status: GatewayThemesStatus = GatewayThemesStatus.Idle,
    /** The host's active name is informational; the connection row restores phone appearance. */
    val activeOnGateway: String? = null,
)

enum class GatewayThemesStatus {
    Idle, Loading, Ready, SignInRequired, Unsupported, Unreachable, Unusable;

    fun productCopy(): String = when (this) {
        Idle -> "Load this Gateway's themes when it connects."
        Loading -> "Loading this Gateway's themes."
        Ready -> ""
        SignInRequired -> "Sign in to load this Gateway's themes."
        Unsupported -> "This Gateway does not offer custom themes."
        Unreachable -> "Could not load this Gateway's themes. Try again."
        Unusable -> "The Gateway returned themes this app could not read."
    }
}

/** Endpoint-owned custom-theme list. It never retains a list from another Gateway. */
internal class GatewayThemeRepository(
    private val http: () -> GatewayHttp?,
    private val endpointGeneration: () -> Long = { 0L },
) {
    private val mutex = Mutex()
    // Never held across HTTP or disk I/O. Reset and every publication share this lock.
    private val stateLock = Any()
    private var resetRevision = 0L
    private val mutableState = MutableStateFlow(GatewayThemesState())
    private val backendSkinNames = mutableSetOf<String>()
    private var lastBackendSkin: String? = null
    private var lastBackendSkinApplied = false
    val state: StateFlow<GatewayThemesState> = mutableState.asStateFlow()

    suspend fun refresh() {
        val transport = http()
        val generation = endpointGeneration()
        val revision = synchronized(stateLock) { resetRevision }
        mutex.withLock {
            synchronized(stateLock) {
                if (endpointGeneration() != generation || resetRevision != revision) return
                mutableState.value = state.value.copy(status = GatewayThemesStatus.Loading)
            }
            val result = if (transport == null) {
                GatewayRestResult.Failed(0, "")
            } else {
                GatewayRestClient(http = { transport }).dashboardThemesEnvelope()
            }
            synchronized(stateLock) {
                if (endpointGeneration() != generation || resetRevision != revision) return
                mutableState.value = when (result) {
                is GatewayRestResult.Success -> GatewayThemesState(
                    themes = (result.value.themes + state.value.themes.filter { it.name in backendSkinNames })
                        .distinctBy { it.name },
                    status = GatewayThemesStatus.Ready,
                    activeOnGateway = result.value.active,
                )
                is GatewayRestResult.Failed -> state.value.copy(status = statusFor(result.statusCode))
                }
            }
        }
    }

    /** Returns true only when this announcement requests a new local appearance choice. */
    fun ingestBackendSkin(theme: GatewayTheme, apply: Boolean, expectedGeneration: Long = endpointGeneration()): Boolean = synchronized(stateLock) {
        if (endpointGeneration() != expectedGeneration) return@synchronized false
        backendSkinNames += theme.name
        val current = mutableState.value
        val themes = (current.themes.filterNot { it.name == theme.name } + theme)
            .distinctBy { it.name }
        mutableState.value = current.copy(themes = themes)
        requestBackendSkinApply(theme.name, apply, expectedGeneration)
    }

    /** Built-in names are apply targets too, but never backend definitions. */
    fun requestBackendSkinApply(name: String, apply: Boolean, expectedGeneration: Long = endpointGeneration()): Boolean = synchronized(stateLock) {
        if (endpointGeneration() != expectedGeneration) return@synchronized false
        if (apply) mutableState.value = state.value.copy(activeOnGateway = name)
        if (lastBackendSkin != name) {
            lastBackendSkin = name
            lastBackendSkinApplied = false
        }
        // A reconnect seed preserves a previous explicit apply. Otherwise a
        // repeated activation would undo the person's later manual selection.
        val shouldApply = apply && !lastBackendSkinApplied
        shouldApply
    }

    /** Only a successful preference transaction acknowledges an activation. */
    fun acknowledgeBackendSkinApply(name: String, expectedGeneration: Long) = synchronized(stateLock) {
        if (endpointGeneration() == expectedGeneration && lastBackendSkin == name) {
            lastBackendSkinApplied = true
        }
    }

    /** Backend skins arrive over the socket and must never be sent to Dashboard PUT. */
    fun isBackendSkin(name: String): Boolean = synchronized(stateLock) { name in backendSkinNames }

    /** Drops cached definitions when the connection-switch seam changes endpoint. */
    fun resetForEndpointSwitch() = synchronized(stateLock) {
        resetRevision++
        backendSkinNames.clear()
        lastBackendSkin = null
        lastBackendSkinApplied = false
        mutableState.value = GatewayThemesState()
    }

    /**
     * Selects only a theme currently supplied by this endpoint. Capture the concrete transport and
     * generation before waiting: a queued write must never reach a newly switched Gateway.
     */
    suspend fun select(name: String): GatewayThemesStatus {
        val transport = http()
        val generation = endpointGeneration()
        val revision = synchronized(stateLock) { resetRevision }
        return mutex.withLock {
            synchronized(stateLock) {
                if (endpointGeneration() != generation || resetRevision != revision || transport == null || state.value.themes.none { it.name == name }) {
                    return@withLock GatewayThemesStatus.Unreachable
                }
            }
            val result = GatewayRestClient(http = { transport }).setDashboardTheme(name)
            synchronized(stateLock) {
                if (endpointGeneration() != generation || resetRevision != revision) return@withLock GatewayThemesStatus.Unreachable
                when (result) {
                is GatewayRestResult.Success -> {
                    mutableState.value = state.value.copy(activeOnGateway = result.value)
                    GatewayThemesStatus.Ready
                }
                is GatewayRestResult.Failed -> statusFor(result.statusCode)
                }
            }
        }
    }
}

private fun statusFor(status: Int?): GatewayThemesStatus = when {
    status == 401 || status == 403 -> GatewayThemesStatus.SignInRequired
    status == 404 -> GatewayThemesStatus.Unsupported
    status == 0 || status != null && status >= 500 -> GatewayThemesStatus.Unreachable
    else -> GatewayThemesStatus.Unusable
}
