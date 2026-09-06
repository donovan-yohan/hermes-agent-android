package com.hermesagent.mobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.GatewayPluginRest
import com.hermesagent.mobile.plugins.GatewayPluginSocket
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginDecisionStore
import com.hermesagent.mobile.plugins.PluginKeyValueStore
import com.hermesagent.mobile.plugins.PluginLoader
import com.hermesagent.mobile.plugins.PluginNotificationInput
import com.hermesagent.mobile.plugins.PluginOs
import com.hermesagent.mobile.plugins.PluginStore
import com.hermesagent.mobile.plugins.ScopedPluginStorage
import com.hermesagent.mobile.plugins.relay.RelayPlugin
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelayPluginToggleJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private class TestDecisionStore : PluginDecisionStore {
        private val _decisions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override val pluginDecisions: Flow<Map<String, Boolean>> = _decisions.asStateFlow()
        override suspend fun savePluginDecision(id: String, enabled: Boolean) {
            _decisions.value = _decisions.value + (id to enabled)
        }
    }

    private class TestKeyValueStore : PluginKeyValueStore {
        val map = mutableMapOf<String, String>()
        override suspend fun read(scopedKey: String): String? = map[scopedKey]
        override suspend fun write(scopedKey: String, value: String?) {
            if (value == null) map.remove(scopedKey) else map[scopedKey] = value
        }
    }

    private class TestPluginOs : PluginOs {
        override fun notify(input: PluginNotificationInput) {}
        override suspend fun openExternal(url: String): Boolean = true
        override suspend fun writeClipboard(text: String): Boolean = true
        override suspend fun share(text: String, title: String?): Boolean = true
    }

    @Test
    fun `toggling relay plugin dynamically unloads and reloads settings row and route`() = runTest {
        val registry = ContributionRegistry()
        val decisionStore = TestDecisionStore()
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val pluginStore = PluginStore(storeScope, decisionStore)
        val mockHttp = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                if (request.path.endsWith("connection/status")) {
                    return GatewayHttpResult.Success(200, """{"status":"ready"}""".toByteArray())
                }
                if (request.path.endsWith("channels")) {
                    return GatewayHttpResult.Success(200, """{"channels":[]}""".toByteArray())
                }
                return GatewayHttpResult.Success(200, """{}""".toByteArray())
            }
        }
        val rest = GatewayPluginRest { mockHttp }
        val socket = GatewayPluginSocket { false }
        val kvStore = TestKeyValueStore()
        val os = TestPluginOs()

        val relayPlugin = RelayPlugin(
            connection = MutableStateFlow(GatewayConnectionState(status = GatewayConnectionStatus.Connected)),
            configured = MutableStateFlow(true),
        )

        val loader = PluginLoader(
            registry = registry,
            store = pluginStore,
            rest = rest,
            socket = socket,
            storageFactory = { ScopedPluginStorage(it, kvStore) },
            osFactory = { os },
        )
        loader.discover(listOf(relayPlugin))

        compose.setContent {
            HermesApp(
                chatState = ChatUiState(),
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                pluginRegistry = registry,
            )
        }
        compose.waitForIdle()

        // 1. Open Settings -> Relay channels row is displayed and route is registered
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithTag(RELAY_ROW).assertIsDisplayed()
        assertEquals(1, registry.getArea(PluginAreas.ROUTES_AREA).size)
        assertEquals(1, registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).size)

        // 2. Open Relay route -> Relay channels screen is displayed
        compose.onNodeWithTag(RELAY_ROW).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Relay channels").assertIsDisplayed()

        // 3. Disable Relay plugin -> Route drops back to Settings, Settings row and destination route are removed
        pluginStore.setPluginEnabled("hermes-plugin-relay", false)
        compose.waitForIdle()

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onAllNodesWithTag(RELAY_ROW).assertCountEquals(0)
        assertEquals(0, registry.getArea(PluginAreas.ROUTES_AREA).size)
        assertEquals(0, registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).size)

        // 4. Re-enable Relay plugin -> Settings row and route are restored
        pluginStore.setPluginEnabled("hermes-plugin-relay", true)
        compose.waitForIdle()

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithTag(RELAY_ROW).assertIsDisplayed()
        assertEquals(1, registry.getArea(PluginAreas.ROUTES_AREA).size)
        assertEquals(1, registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).size)

        // 5. Navigate to Relay again
        compose.onNodeWithTag(RELAY_ROW).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Relay channels").assertIsDisplayed()
    }

    private companion object {
        const val RELAY_ROW = "settings-row-relay channels"
    }
}
