package com.hermesagent.mobile.plugins.kanban

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

/** Compiled, read-only Kanban board snapshot plugin. */
class KanbanPlugin(
    private val scope: CoroutineScope? = null,
) : HermesPlugin {
    override val id = "kanban"
    override val name = "Kanban"
    override val description = "Read-only board snapshot"

    override fun register(ctx: PluginContext) {
        val pluginScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ctx.onDispose { pluginScope.cancel() }

        val viewModel = KanbanViewModel(
            repository = KanbanPluginRepository(ctx::rest),
            connected = ctx.host.connected,
            endpointGeneration = ctx.host.endpointGeneration,
            pluginScope = pluginScope,
        )

        ctx.registerMany(
            listOf(
                PluginContribution(
                    id = "route",
                    area = PluginAreas.ROUTES_AREA,
                    title = "Kanban",
                    render = {
                        val nav = LocalPluginNavigation.current
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        KanbanScreen(
                            state = state,
                            onBack = nav.onBack,
                            onRefresh = viewModel::refreshCurrent,
                            onOpenTask = viewModel::openTask,
                            onCloseDetail = viewModel::closeDetail,
                        )
                    },
                ),
                PluginContribution(
                    id = "sidebar-nav",
                    area = PluginAreas.SIDEBAR_NAV_AREA,
                    title = "Kanban",
                    order = 450,
                    render = {
                        val nav = LocalPluginNavigation.current
                        val state by viewModel.uiState.collectAsStateWithLifecycle()
                        SettingsRow(
                            label = "Kanban",
                            description = if (state.phase == KanbanPhase.Unavailable) {
                                KANBAN_UNAVAILABLE
                            } else {
                                "A read-only snapshot of the current board."
                            },
                            traversalIndex = 4.5f,
                            enabled = true,
                            onClick = { nav.onNavigate("$id:route") },
                        )
                    },
                ),
            ),
        )
    }
}
