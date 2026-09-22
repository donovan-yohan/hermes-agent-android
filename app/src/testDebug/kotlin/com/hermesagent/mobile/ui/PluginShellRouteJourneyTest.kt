package com.hermesagent.mobile.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.plugins.ContributionRegistry
import com.hermesagent.mobile.plugins.HermesPlugin
import com.hermesagent.mobile.plugins.PluginAreas
import com.hermesagent.mobile.plugins.PluginContext
import com.hermesagent.mobile.plugins.PluginContribution
import com.hermesagent.mobile.plugins.PluginDecisionStore
import com.hermesagent.mobile.plugins.PluginKeyValueStore
import com.hermesagent.mobile.plugins.PluginLoader
import com.hermesagent.mobile.plugins.PluginNotificationInput
import com.hermesagent.mobile.plugins.PluginOs
import com.hermesagent.mobile.plugins.PluginRest
import com.hermesagent.mobile.plugins.PluginRestOptions
import com.hermesagent.mobile.plugins.PluginRestResult
import com.hermesagent.mobile.plugins.PluginSocket
import com.hermesagent.mobile.plugins.PluginStore
import com.hermesagent.mobile.plugins.ScopedPluginStorage
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.settings.SettingsRow
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

/**
 * The shell's plugin route branch, driven end to end by a synthetic plugin.
 *
 * A contributed Settings row opens a `Route(<pluginId>:<contributionId>)`
 * destination; disabling the plugin unloads the route and the row and drops the
 * shell back to Settings; re-enabling restores both. This is the shell contract
 * every bundled plugin rides, so the fixture is deliberately synthetic rather
 * than any one plugin's surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginShellRouteJourneyTest {

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
        private val map = mutableMapOf<String, String>()
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

    /** A plugin that contributes one shell route and one Settings launcher row. */
    private class TestPlugin(private val botFinished: (Boolean) -> Unit = {}) : HermesPlugin {
        override val id: String = PLUGIN_ID
        override val name: String = "Test plugin"
        override val description: String = "Synthetic plugin for the shell route contract"

        override fun register(ctx: PluginContext) {
            ctx.registerMany(
                listOf(
                    PluginContribution(
                        id = "route",
                        area = PluginAreas.ROUTES_AREA,
                        title = ROW_LABEL,
                        render = {
                            val nav = LocalPluginNavigation.current
                            OverlayScaffold(title = "Feature header", onBack = nav.onBack) {
                                Text(ROUTE_BODY, modifier = Modifier.testTag(ROUTE_BODY_TAG))
                                TextButton(onClick = nav.onBack) { Text("Return from feature") }
                            }
                        },
                    ),
                    PluginContribution(
                        id = "sidebar-nav",
                        area = PluginAreas.SIDEBAR_NAV_AREA,
                        title = ROW_LABEL,
                        order = 300,
                        render = {
                            val nav = LocalPluginNavigation.current
                            SettingsRow(
                                label = ROW_LABEL,
                                description = "A synthetic workspace for the shell route contract.",
                                traversalIndex = 4f,
                                onClick = { nav.onNavigate("$PLUGIN_ID:route") },
                            )
                            TextButton(onClick = { nav.onOpenBotChat("fixture", "bot-chat", botFinished) }) {
                                Text("Open fixture bot")
                            }
                        },
                    ),
                ),
            )
        }
    }

    @Test
    fun `a plugin route opens from its settings row and unloads when the plugin is disabled`() = runTest {
        val registry = ContributionRegistry()
        val decisionStore = TestDecisionStore()
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val pluginStore = PluginStore(storeScope, decisionStore)
        val rest = object : PluginRest {
            override suspend fun execute(
                pluginId: String,
                path: String,
                options: PluginRestOptions,
            ): PluginRestResult = PluginRestResult.UnavailableOnGateway
        }
        val socket = object : PluginSocket {
            override fun connect(pluginId: String, path: String, onMessage: (String) -> Unit): () -> Unit = {}
        }
        val kvStore = TestKeyValueStore()
        val os = TestPluginOs()

        val loader = PluginLoader(
            registry = registry,
            store = pluginStore,
            rest = rest,
            socket = socket,
            storageFactory = { ScopedPluginStorage(it, kvStore) },
            osFactory = { os },
        )
        val completions = mutableListOf<Boolean>()
        var botOpens = false
        loader.discover(listOf(TestPlugin(completions::add)))

        compose.setContent {
            HermesApp(
                chatState = ChatUiState(),
                gatewayState = GatewaySettingsUiState(),
                sshState = SshUiState(),
                appearance = AppearanceSelection(),
                chatActions = ChatActions(onOpenBotChat = { _, _, finished -> finished(botOpens) }),
                appearanceActions = AppearanceActions(),
                gatewayActions = GatewayActions(),
                sshActions = SshActions(),
                pluginRegistry = registry,
                pluginStore = pluginStore,
            )
        }
        compose.waitForIdle()

        // 1. Feature launchers live in the sessions drawer/rail, while Settings
        // keeps the separate Plugins management preference row.
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithTag(ROW_TAG).assertIsDisplayed()
        assertEquals(1, registry.getArea(PluginAreas.ROUTES_AREA).size)
        assertEquals(1, registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).size)

        // 2. The sidebar launcher opens the contributed route destination.
        compose.onNodeWithTag(ROW_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ROUTE_BODY_TAG).assertIsDisplayed()

        val door = compose.onNodeWithContentDescription("Open sessions").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("Feature header").getUnclippedBoundsInRoot()
        org.junit.Assert.assertEquals((title.top.value + title.bottom.value) / 2, (door.top.value + door.bottom.value) / 2, 1f)

        // A feature retains a drawer door; choosing the same route closes it again.
        compose.onNodeWithContentDescription("Open sessions").assertIsDisplayed().performClick()
        compose.onNodeWithTag(ROW_TAG).assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ROUTE_BODY_TAG).assertIsDisplayed()

        // Returning from a sidebar feature restores Chat, not Settings.
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithText("Open fixture bot").performClick()
        compose.onNodeWithText("Open fixture bot").assertIsDisplayed()
        assertEquals(listOf(false), completions)
        compose.runOnIdle { botOpens = true }
        compose.onNodeWithText("Open fixture bot").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Open fixture bot").assertIsNotDisplayed()
        compose.onNodeWithTag(ROUTE_BODY_TAG).assertDoesNotExist()
        assertEquals(listOf(false, true), completions)
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithTag(ROW_TAG).performClick()
        compose.onNodeWithText("Return from feature").performClick()
        compose.onNodeWithContentDescription("Open sessions").assertIsDisplayed().performClick()
        compose.onNodeWithTag(ROW_TAG).performClick()
        compose.onNodeWithTag(ROUTE_BODY_TAG).assertIsDisplayed()

        // 3. Disabling the plugin unloads the route and the row, and the shell
        //    falls back to Settings rather than painting a dead destination.
        pluginStore.setPluginEnabled(PLUGIN_ID, false)
        compose.waitForIdle()

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onAllNodesWithTag(ROW_TAG).assertCountEquals(0)
        compose.onAllNodesWithTag(ROUTE_BODY_TAG).assertCountEquals(0)
        assertEquals(0, registry.getArea(PluginAreas.ROUTES_AREA).size)
        assertEquals(0, registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).size)

        // 4. Re-enabling restores the row and the route.
        pluginStore.setPluginEnabled(PLUGIN_ID, true)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithTag(ROW_TAG).assertIsDisplayed()
        assertEquals(1, registry.getArea(PluginAreas.ROUTES_AREA).size)
        assertEquals(1, registry.getArea(PluginAreas.SIDEBAR_NAV_AREA).size)

        compose.onNodeWithTag(ROW_TAG).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ROUTE_BODY_TAG).assertIsDisplayed()
    }

    private companion object {
        const val PLUGIN_ID = "test-plugin"
        const val ROW_LABEL = "Test workspace"
        const val ROUTE_BODY = "Test workspace route"
        const val ROUTE_BODY_TAG = "test-plugin-route-body"
        const val ROW_TAG = "settings-row-test workspace"
    }
}
