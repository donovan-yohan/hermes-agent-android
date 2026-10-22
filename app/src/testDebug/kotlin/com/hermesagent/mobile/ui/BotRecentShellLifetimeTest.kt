package com.hermesagent.mobile.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotRecentShellLifetimeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `held success cannot override later shell navigation or leave return ABA`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = PluginStore(scope, object : PluginDecisionStore {
            override val pluginDecisions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
            override suspend fun savePluginDecision(id: String, enabled: Boolean) = Unit
        })
        val registry = ContributionRegistry()
        var completion: ((Boolean) -> Unit)? = null
        val outcomes = mutableListOf<Boolean>()
        registry.register(Contribution("fixture:one", PluginAreas.ROUTES_AREA, render = {
            val nav = LocalPluginNavigation.current
            androidx.compose.foundation.layout.Column {
            Text("First route")
            com.hermesagent.mobile.ui.common.TextButton("Start bot action", {
                nav.onOpenBotRecentSession("work", "recent", 0L) { outcomes += it }
            })
            com.hermesagent.mobile.ui.common.TextButton("Other route", { nav.onNavigate("fixture:two") })
            }
        }))
        registry.register(Contribution("fixture:two", PluginAreas.ROUTES_AREA, render = {
            val nav = LocalPluginNavigation.current
            androidx.compose.foundation.layout.Column {
            Text("Second route")
            com.hermesagent.mobile.ui.common.TextButton("Return route", { nav.onNavigate("fixture:one") })
            }
        }))
        compose.setContent { HermesApp(ChatUiState(), GatewaySettingsUiState(), SshUiState(),
            AppearanceSelection(), chatActions = ChatActions(onOpenBotRecentSession = { _, _, _, done -> completion = done }),
            appearanceActions = AppearanceActions(), gatewayActions = GatewayActions(), sshActions = SshActions(),
            pluginRegistry = registry, pluginStore = store,
            navigationAsk = HermesNavigationAsk(HermesDestination.Route("fixture:one"), 1L)) }
        compose.onNodeWithText("Start bot action").performClick()
        compose.onNodeWithText("Other route").performClick()
        compose.onNodeWithText("Return route").performClick()
        compose.runOnIdle { completion!!(true) }
        compose.onNodeWithText("First route").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(false), outcomes); scope.cancel() }
    }
}
