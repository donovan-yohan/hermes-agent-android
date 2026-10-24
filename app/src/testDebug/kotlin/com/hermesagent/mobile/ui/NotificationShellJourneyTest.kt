package com.hermesagent.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationShellJourneyTest {
    @get:Rule val compose = createComposeRule()
    @Test fun coldRoomAskWaitsForPluginRegistration() {
        val registry = com.hermesagent.mobile.plugins.ContributionRegistry()
        val visited = mutableListOf<HermesDestination>()
        compose.setContent {
            HermesApp(ChatUiState(), GatewaySettingsUiState(), SshUiState(), AppearanceSelection(),
                chatActions = ChatActions(), appearanceActions = AppearanceActions(), gatewayActions = GatewayActions(),
                sshActions = SshActions(), pluginStore = testPluginStore(), pluginRegistry = registry,
                navigationAsk = HermesNavigationAsk(HermesDestination.Route("groups:route"), 1, true),
                onDestinationChanged = { visited += it })
        }
        compose.waitForIdle()
        compose.runOnIdle {
            registry.register(com.hermesagent.mobile.plugins.Contribution("groups:route", com.hermesagent.mobile.plugins.PluginAreas.ROUTES_AREA,
                render = { androidx.compose.material3.Text("Synthetic room") }))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(HermesDestination.Route("groups:route"), visited.last()) }
    }

    @Test fun coldAndWarmNotificationAsksLeaveSettingsAndReportActualDestination() {
        val ask = mutableStateOf(HermesNavigationAsk(HermesDestination.Settings, 1))
        val visited = mutableListOf<HermesDestination>()
        val registry = com.hermesagent.mobile.plugins.ContributionRegistry()
        registry.register(com.hermesagent.mobile.plugins.Contribution("groups:route", com.hermesagent.mobile.plugins.PluginAreas.ROUTES_AREA,
            render = { androidx.compose.material3.Text("Synthetic room") }))
        compose.setContent {
            HermesApp(ChatUiState(), GatewaySettingsUiState(), SshUiState(), AppearanceSelection(),
                chatActions = ChatActions(), appearanceActions = AppearanceActions(), gatewayActions = GatewayActions(),
                sshActions = SshActions(), pluginStore = testPluginStore(), pluginRegistry = registry, navigationAsk = ask.value,
                onDestinationChanged = { visited += it })
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(HermesDestination.Settings, visited.last()); ask.value = HermesNavigationAsk(HermesDestination.Chat, 2) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(HermesDestination.Chat, visited.last()); ask.value = HermesNavigationAsk(HermesDestination.Route("groups:route"), 3) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(HermesDestination.Route("groups:route"), visited.last()) }
    }
}
