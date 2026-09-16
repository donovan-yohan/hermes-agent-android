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
    private val mutableState = MutableStateFlow(GatewayThemesState())
    val state: StateFlow<GatewayThemesState> = mutableState.asStateFlow()

    suspend fun refresh() {
        val transport = http()
        val generation = endpointGeneration()
        mutex.withLock {
            if (endpointGeneration() != generation) return
            mutableState.value = GatewayThemesState(status = GatewayThemesStatus.Loading)
            val result = if (transport == null) {
                GatewayRestResult.Failed(0, "")
            } else {
                GatewayRestClient(http = { transport }).dashboardThemesEnvelope()
            }
            if (endpointGeneration() != generation) return
            mutableState.value = when (result) {
                is GatewayRestResult.Success -> GatewayThemesState(
                    themes = result.value.themes,
                    status = GatewayThemesStatus.Ready,
                    activeOnGateway = result.value.active,
                )
                is GatewayRestResult.Failed -> GatewayThemesState(status = statusFor(result.statusCode))
            }
        }
    }

    /** Drops cached definitions when the connection-switch seam changes endpoint. */
    fun resetForEndpointSwitch() {
        mutableState.value = GatewayThemesState()
    }

    /**
     * Selects only a theme currently supplied by this endpoint. Capture the concrete transport and
     * generation before waiting: a queued write must never reach a newly switched Gateway.
     */
    suspend fun select(name: String): GatewayThemesStatus {
        val transport = http()
        val generation = endpointGeneration()
        return mutex.withLock {
            if (endpointGeneration() != generation || transport == null || state.value.themes.none { it.name == name }) {
                return@withLock GatewayThemesStatus.Unreachable
            }
            val result = GatewayRestClient(http = { transport }).setDashboardTheme(name)
            if (endpointGeneration() != generation) return@withLock GatewayThemesStatus.Unreachable
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

private fun statusFor(status: Int?): GatewayThemesStatus = when {
    status == 401 || status == 403 -> GatewayThemesStatus.SignInRequired
    status == 404 -> GatewayThemesStatus.Unsupported
    status == 0 || status != null && status >= 500 -> GatewayThemesStatus.Unreachable
    else -> GatewayThemesStatus.Unusable
}
