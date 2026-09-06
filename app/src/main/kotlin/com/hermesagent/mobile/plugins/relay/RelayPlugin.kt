package com.hermesagent.mobile.plugins.relay

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.SignInOrigin
import com.hermesagent.mobile.plugins.HermesPlugin
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginContribution
import com.hermesagent.mobile.plugins.PluginContext
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.settings.SettingsRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bundled Relay plugin for Hermes Android.
 *
 * Implements [HermesPlugin] to contribute the Relay channels workspace:
 * - A route contribution under [PluginAreas.ROUTES_AREA] rendering [RelayScreen]
 * - A sidebar navigation contribution under [PluginAreas.SIDEBAR_NAV_AREA]
 *   rendering the Settings entry point for Relay channels.
 */
class RelayPlugin(
    private val connection: Flow<GatewayConnectionState>? = null,
    private val configured: Flow<Boolean>? = null,
    private val credentials: RelayCredentialRefresher? = null,
    private val scope: CoroutineScope? = null,
) : HermesPlugin {

    override val id: String = "hermes-plugin-relay"
    override val name: String = "Relay"
    override val description: String = "Relay workspace for channels and messaging"
    override val defaultEnabled: Boolean = true

    override fun register(ctx: PluginContext) {
        val pluginScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ctx.onDispose { pluginScope.cancel() }

        val conn = connection ?: defaultConnection ?: MutableStateFlow(GatewayConnectionState())
        val conf = configured ?: defaultConfigured ?: MutableStateFlow(false)
        val creds = credentials ?: defaultCredentials ?: object : RelayCredentialRefresher {
            override suspend fun refreshOnce(): Boolean = false
            override suspend fun signInAvailable(): Boolean = false
        }

        val repository = RelayPluginRepository(ctx::rest)
        val availabilityController = RelayAvailabilityController(
            scope = pluginScope,
            probe = repository,
            connection = conn,
            configured = conf,
            credentials = creds,
        )
        val viewModel = RelayViewModel(
            availability = availabilityController.state,
            refreshAvailability = availabilityController::refresh,
            reader = repository,
            poster = repository,
        )
        val relayActions = RelayActions(
            onSelectChannel = viewModel::selectChannel,
            onClearSelection = viewModel::clearSelection,
            onRetry = viewModel::retry,
            onDraftChange = viewModel::setDraft,
            onSend = viewModel::sendDraft,
            onRetrySend = viewModel::retrySend,
            onResume = viewModel::surfaceResumed,
            onPause = viewModel::surfacePaused,
        )

        ctx.registerMany(
            listOf(
                PluginContribution(
                    id = "route",
                    area = PluginAreas.ROUTES_AREA,
                    title = "Relay channels",
                    render = {
                        val nav = LocalPluginNavigation.current
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        RelayScreen(
                            state = state,
                            actions = relayActions,
                            onLeave = nav.onBack,
                            onOpenGateways = { nav.onOpenGateways(SignInOrigin.Gateways) },
                        )
                    },
                ),
                PluginContribution(
                    id = "sidebar-nav",
                    area = PluginAreas.SIDEBAR_NAV_AREA,
                    title = "Relay channels",
                    order = 300,
                    render = {
                        val nav = LocalPluginNavigation.current
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        val relayAvailable = !state.unavailableOnGateway
                        SettingsRow(
                            label = "Relay channels",
                            description = if (relayAvailable) {
                                "Channels, transcripts, and messaging live in their own workspace."
                            } else {
                                RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
                            },
                            traversalIndex = 3f,
                            enabled = relayAvailable,
                            onClick = { nav.onNavigate("hermes-plugin-relay:route") },
                        )
                    },
                ),
            ),
        )
    }

    companion object {
        var defaultConnection: Flow<GatewayConnectionState>? = null
        var defaultConfigured: Flow<Boolean>? = null
        var defaultCredentials: RelayCredentialRefresher? = null
    }
}
