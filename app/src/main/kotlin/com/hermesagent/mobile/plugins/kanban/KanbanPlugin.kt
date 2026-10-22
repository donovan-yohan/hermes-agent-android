package com.hermesagent.mobile.plugins.kanban

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesagent.mobile.plugins.HermesPlugin
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginContribution
import com.hermesagent.mobile.plugins.PluginContext
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.sessions.SidebarNavRow
import com.hermesagent.mobile.ui.common.HermesIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Compiled Kanban snapshot, filtering, task creation and movement. */
class KanbanPlugin(
    private val scope: CoroutineScope? = null,
) : HermesPlugin {
    override val id = "kanban"
    override val name = "Kanban"
    override val description = "Board snapshot, task creation and movement"

    override fun register(ctx: PluginContext) {
        val pluginScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ctx.onDispose { pluginScope.cancel() }

        val viewModel = KanbanViewModel(
            repository = KanbanPluginRepository(ctx::rest),
            connected = ctx.host.connected,
            endpointGeneration = ctx.host.endpointGeneration,
            pluginScope = pluginScope,
            move = ctx.host.kanbanMove,
            create = ctx.host.kanbanCreate,
        )
        ctx.onDispose { viewModel.closeMove(); viewModel.closeCreate() }

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
                            onMoveTask = viewModel::openMove,
                            onCloseMove = viewModel::closeMove,
                            onConfirmMove = viewModel::moveTask,
                            onNewTask = viewModel::openCreate,
                            onCloseCreate = viewModel::closeCreate,
                            onSubmitTask = viewModel::submitTask,
                            onFilters = viewModel::setFilters,
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
                        SidebarNavRow(
                            label = "Kanban",
                            icon = HermesIcon.Checklist,
                            enabled = true,
                            onClick = { nav.onNavigate("$id:route") },
                            testTag = "sidebar-action-kanban",
                            selected = nav.currentRoute == "$id:route",
                        )
                    },
                ),
            ),
        )
    }
}
