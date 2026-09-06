package com.hermesagent.mobile.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.HermesPlugin
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginDecisionStore
import com.hermesagent.mobile.plugins.PluginContribution
import com.hermesagent.mobile.plugins.PluginKeyValueStore
import com.hermesagent.mobile.plugins.PluginLoader
import com.hermesagent.mobile.plugins.PluginNotificationInput
import com.hermesagent.mobile.plugins.PluginOs
import com.hermesagent.mobile.plugins.PluginRest
import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import com.hermesagent.mobile.plugins.PluginSocket
import com.hermesagent.mobile.plugins.PluginStore
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.settings.PluginsCopy
import com.hermesagent.mobile.ui.settings.PLUGINS_TITLE_TAG
import com.hermesagent.mobile.ui.settings.pluginRowTag
import com.hermesagent.mobile.ui.settings.pluginToggleTag
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginsSettingsJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    private class TestDecisionStore : PluginDecisionStore {
        private val _decisions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override val pluginDecisions: Flow<Map<String, Boolean>> = _decisions.asStateFlow()
        override suspend fun savePluginDecision(id: String, enabled: Boolean) {
            _decisions.value = _decisions.value + (id to enabled)
        }
    }

    @Test
    fun `settings plugins lists bundled plugins and toggles them live`() = runTest {
        val pluginStore = pluginStore(testScheduler)
        val registry = ContributionRegistry()
        val loader = pluginLoader(registry, pluginStore)
        loader.discover(listOf(TestPlugin()))

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
                pluginStore = pluginStore,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag(PLUGINS_ROW).performClick()

        compose.onNodeWithTag(PLUGINS_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText(PluginsCopy.count(1)).assertIsDisplayed()
        compose.onNodeWithText(PluginsCopy.BLURB).assertIsDisplayed()

        compose.onNodeWithTag(pluginRowTag(TEST_PLUGIN_ID)).assertIsDisplayed()
        compose.onNodeWithText("Test plugin").assertIsDisplayed()
        compose.onNodeWithText(PluginsCopy.KIND_BUNDLED).assertIsDisplayed()

        compose.onNodeWithTag(pluginToggleTag(TEST_PLUGIN_ID)).assertIsOn()
        compose.onNodeWithContentDescription("${PluginsCopy.DISABLE} Test plugin").assertIsDisplayed()
        assertEquals(1, registry.getArea(PluginAreas.ROUTES_AREA).size)

        // Disable -> record flips to Disabled and the label becomes Enable.
        compose.onNodeWithTag(pluginToggleTag(TEST_PLUGIN_ID)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(pluginToggleTag(TEST_PLUGIN_ID)).assertIsOff()
        compose.onNodeWithContentDescription("${PluginsCopy.ENABLE} Test plugin").assertIsDisplayed()
        assertEquals(0, registry.getArea(PluginAreas.ROUTES_AREA).size)

        // Re-enable -> contributions return.
        compose.onNodeWithTag(pluginToggleTag(TEST_PLUGIN_ID)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(pluginToggleTag(TEST_PLUGIN_ID)).assertIsOn()
        assertEquals(1, registry.getArea(PluginAreas.ROUTES_AREA).size)
    }

    @Test
    fun `disabling a plugin persists through saved-state restore and keeps its contributions unloaded`() = runTest {
        val pluginStore = pluginStore(testScheduler)
        val registry = ContributionRegistry()
        val loader = pluginLoader(registry, pluginStore)
        loader.discover(listOf(TestPlugin()))

        val restoration = StateRestorationTester(compose)
        restoration.setContent {
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
                pluginStore = pluginStore,
            )
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag(PLUGINS_ROW).performClick()

        // Disable the plugin.
        compose.onNodeWithTag(pluginToggleTag(TEST_PLUGIN_ID)).performClick()
        compose.waitForIdle()
        assertEquals(0, registry.getArea(PluginAreas.ROUTES_AREA).size)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        // Still on Plugins, still disabled, still unloaded.
        compose.onNodeWithTag(PLUGINS_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(pluginToggleTag(TEST_PLUGIN_ID)).assertIsOff()
        assertEquals(0, registry.getArea(PluginAreas.ROUTES_AREA).size)
    }

    private companion object {
        const val PLUGINS_ROW = "settings-row-plugins"
    }

    private fun pluginStore(scheduler: TestCoroutineScheduler): PluginStore {
        val storeScope = CoroutineScope(StandardTestDispatcher(scheduler))
        return PluginStore(storeScope, TestDecisionStore())
    }

    private fun pluginLoader(registry: ContributionRegistry, store: PluginStore): PluginLoader =
        PluginLoader(
            registry = registry,
            store = store,
            rest = object : PluginRest {
                override suspend fun execute(pluginId: String, path: String, options: PluginRestOptions): PluginRestResult =
                    PluginRestResult.Refused(0, "unavailable in test")
            },
            socket = object : PluginSocket {
                override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
            },
            storageFactory = { pluginId ->
                com.hermesagent.mobile.plugins.ScopedPluginStorage(
                    pluginId,
                    object : PluginKeyValueStore {
                        private val map = mutableMapOf<String, String>()
                        override suspend fun read(scopedKey: String): String? = map[scopedKey]
                        override suspend fun write(scopedKey: String, value: String?) {
                            if (value == null) map.remove(scopedKey) else map[scopedKey] = value
                        }
                    },
                )
            },
            osFactory = {
                object : PluginOs {
                    override fun notify(input: PluginNotificationInput) = Unit
                    override suspend fun openExternal(url: String): Boolean = true
                    override suspend fun writeClipboard(text: String): Boolean = true
                    override suspend fun share(text: String, title: String?): Boolean = true
                }
            },
        )
}

private class TestPlugin : HermesPlugin {
    override val id: String = TEST_PLUGIN_ID
    override val name: String = "Test plugin"
    override val description: String = "A test plugin used by the Plugins journey"
    override val defaultEnabled: Boolean = true

    override fun register(ctx: com.hermesagent.mobile.plugins.PluginContext) {
        ctx.registerMany(
            listOf(
                PluginContribution(
                    id = "route",
                    area = PluginAreas.ROUTES_AREA,
                    title = "Test plugin route",
                    render = { Text("Test plugin route") },
                ),
            ),
        )
    }
}

private const val TEST_PLUGIN_ID: String = "test-plugin"

