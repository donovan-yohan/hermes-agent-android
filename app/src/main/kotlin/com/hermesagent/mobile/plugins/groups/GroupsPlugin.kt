package com.hermesagent.mobile.plugins.groups

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.ui.LocalPluginNavigation
import com.hermesagent.mobile.ui.sessions.SidebarNavRow
import com.hermesagent.mobile.ui.common.HermesIcon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun groupConnections(host: PluginHost, scope: CoroutineScope): StateFlow<GroupReadConnection?> =
    combine(host.connectionToken, host.endpointGeneration) { token, generation ->
        token?.let {
            GroupReadConnection(
                GroupsRepository({ method, params -> host.requestAtConnection(generation, token, method, params) }),
                { host.endpointGeneration.value == generation && host.connectionToken.value === token },
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

class GroupsPlugin : HermesPlugin {
    override val id = "groups"
    override val name = "Group Chats"
    override val description = "Hosted Group Chats, read only"
    override val defaultEnabled = true

    override fun register(ctx: PluginContext) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        ctx.onDispose { scope.cancel() }
        val model = GroupsViewModel(scope, groupConnections(ctx.host, scope), ctx.host.endpointGeneration)
        val actions = GroupsActions(model::open, model::closeRoom, model::refresh, model::setForeground)
        ctx.registerMany(listOf(
            PluginContribution(id = "route", area = PluginAreas.ROUTES_AREA, title = name, render = {
                val navigation = LocalPluginNavigation.current
                val state by model.uiState.collectAsStateWithLifecycle()
                // Reuse the app's already-read roster; this surface sends no profiles RPC or ui_meta read.
                val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? com.hermesagent.mobile.HermesApplication
                val profiles = app?.profileRepository?.roster?.collectAsStateWithLifecycle()?.value?.profiles.orEmpty()
                GroupsScreen(state, navigation.onBack, actions, profiles)
            }),
            PluginContribution(id = "sidebar-nav", area = PluginAreas.SIDEBAR_NAV_AREA, title = name, order = 410, render = {
                val navigation = LocalPluginNavigation.current
                SidebarNavRow(label = name, icon = HermesIcon.Mail, enabled = true,
                    onClick = { navigation.onNavigate("$id:route") }, testTag = "sidebar-action-group-chats")
            }),
        ))
    }
}
