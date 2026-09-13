package com.hermesagent.mobile.plugins.kanban

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesagent.mobile.plugins.HermesPlugin
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginContribution
import com.hermesagent.mobile.plugins.PluginContext
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.settings.SettingsRow

/** Compiled, read-only Kanban board snapshot plugin. */
class KanbanPlugin : HermesPlugin {
    override val id = "kanban"
    override val name = "Kanban"
    override val description = "Read-only board snapshot"

    override fun register(ctx: PluginContext) {
        val viewModel = KanbanViewModel(KanbanPluginRepository(ctx::rest), ctx.host.connected, ctx.host.endpointGeneration)
        ctx.registerMany(listOf(
            PluginContribution(id = "route", area = PluginAreas.ROUTES_AREA, title = "Kanban", render = {
                val nav = LocalPluginNavigation.current
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                KanbanScreen(state, nav.onBack, viewModel::refreshCurrent, viewModel::openTask, viewModel::closeDetail)
            }),
            PluginContribution(id = "sidebar-nav", area = PluginAreas.SIDEBAR_NAV_AREA, title = "Kanban", order = 450, render = {
                val nav = LocalPluginNavigation.current
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                SettingsRow(label = "Kanban", description = if (state.phase == KanbanPhase.Unavailable) KANBAN_UNAVAILABLE else "A read-only snapshot of the current board.", traversalIndex = 4.5f, enabled = true, onClick = { nav.onNavigate("$id:route") })
            }),
        ))
    }
}
