package com.hermesagent.mobile.plugins.bots

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

/**
 * The bundled Bots plugin.
 *
 * Contributes Desktop's Bot Mode roster as a full-screen destination:
 *
 * - a `routes` contribution rendering [BotsRosterScreen]
 * - a `sidebarNav` contribution rendering its Settings entry point
 *
 * Modelled on [com.hermesagent.mobile.plugins.relay.RelayPlugin], which is the
 * app's existing bundled-plugin shape: the plugin takes its own scope, reads
 * the Gateway through `ctx.host` rather than a module global, and disposes
 * everything it registers. There is deliberately no mutable companion state —
 * a plugin that cannot be constructed twice is a plugin whose tests have to
 * share a singleton.
 *
 * [sections] and [metaByKey] are the user's own pin/hide/section choices
 * (Desktop's `$botSections` / `$botMeta`). They are constructor inputs, and the
 * plugin storage door is where they will be read from once the editing surface
 * that writes them lands.
 */
class BotsPlugin(
    private val sections: List<BotSection> = emptyList(),
    private val metaByKey: Map<String, BotMeta> = emptyMap(),
    private val scope: CoroutineScope? = null,
) : HermesPlugin {

    override val id: String = "bots"
    override val name: String = "Bots"
    override val description: String = "Bot roster"
    override val defaultEnabled: Boolean = true

    override fun register(ctx: PluginContext) {
        val pluginScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ctx.onDispose { pluginScope.cancel() }

        val viewModel = BotsViewModel(
            repository = BotsPluginRepository(ctx.host),
            scope = pluginScope,
            sections = sections,
            metaByKey = metaByKey,
        )
        val actions = BotsActions(
            onRefresh = viewModel::refresh,
            onSearchChange = viewModel::setSearchQuery,
            onKindFilterChange = viewModel::setKindFilter,
            onActivityFilterChange = viewModel::setActivityFilter,
            onSetHiddenExpanded = viewModel::setHiddenExpanded,
            onClearFilters = viewModel::clearFilters,
        )

        // The roster is read once at registration; the surface refreshes it.
        viewModel.refresh()

        ctx.registerMany(
            listOf(
                PluginContribution(
                    id = "route",
                    area = PluginAreas.ROUTES_AREA,
                    title = "Bots roster",
                    render = {
                        val nav = LocalPluginNavigation.current
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        BotsRosterScreen(
                            state = state,
                            onBack = nav.onBack,
                            actions = actions,
                        )
                    },
                ),
                PluginContribution(
                    id = "sidebar-nav",
                    area = PluginAreas.SIDEBAR_NAV_AREA,
                    title = "Bots",
                    order = 400,
                    render = {
                        val nav = LocalPluginNavigation.current
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        // A Gateway build without `profiles.list` cannot answer a
                        // roster at all, so the entry point says so and stays
                        // closed rather than opening an empty destination.
                        val unavailable = state.phase == BotsRosterPhase.UnavailableOnGateway
                        SettingsRow(
                            label = "Bots",
                            description = if (unavailable) {
                                BotsRosterCopy.rosterUnavailable(GATEWAY_PREDATES_REASON)
                            } else {
                                "The bot roster for this Gateway."
                            },
                            traversalIndex = 5f,
                            enabled = !unavailable,
                            onClick = { nav.onNavigate("$id:route") },
                        )
                    },
                ),
            ),
        )
    }

    private companion object {
        const val GATEWAY_PREDATES_REASON = "this Gateway does not serve profiles.list"
    }
}
