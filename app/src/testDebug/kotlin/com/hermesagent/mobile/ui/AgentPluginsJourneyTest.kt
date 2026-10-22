package com.hermesagent.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.ui.chat.ChatUiState
import com.hermesagent.mobile.ui.gateway.GatewaySettingsUiState
import com.hermesagent.mobile.ui.sessions.ProfileRailState
import com.hermesagent.mobile.ui.ssh.SshUiState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentPluginsJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsLoadsSelectedProfileAndConfirmsOnlyExactKey() = runTest {
        val rpc = Rpc()
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val selected = mutableStateOf("research")
        val store = PluginStore(backgroundScope, Decisions())
        compose.setContent {
            HermesApp(
                chatState = ChatUiState(profileRail = ProfileRailState(scope = ProfileScope(selected.value))),
                gatewayState = GatewaySettingsUiState(), sshState = SshUiState(), appearance = AppearanceSelection(),
                chatActions = ChatActions(), appearanceActions = AppearanceActions(), gatewayActions = GatewayActions(),
                sshActions = SshActions(), pluginStore = store, agentPluginsHost = host,
            )
        }
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.onNodeWithTag("settings-row-plugins").performClick()
        drain(testScheduler)
        scrollTo("agent-plugin-toggle-image_gen/fal").assertIsOff().performClick()
        drain(testScheduler)
        compose.onNodeWithTag("agent-plugin-toggle-image_gen/fal").assertIsOn()
        scrollTo("agent-plugin-toggle-video_gen/fal").assertIsOff()
        assertEquals(listOf("list", "toggle", "list"), rpc.calls.map { it["action"]?.jsonPrimitive?.content })
        assertTrue(rpc.calls.all { it["profile"] == JsonPrimitive("research") })
        assertEquals(JsonPrimitive("image_gen/fal"), rpc.calls[1]["key"])
        assertFalse(rpc.calls[1].containsKey("name"))
        scrollTo("agent-plugin-toggle-image_gen/fal").performClick()
        drain(testScheduler)
        compose.onNodeWithTag("agent-plugin-toggle-image_gen/fal").assertIsOff()
        assertEquals(JsonPrimitive(false), rpc.calls[3]["enable"])
        compose.runOnIdle { selected.value = "work" }
        drain(testScheduler)
        scrollTo("agent-plugin-toggle-image_gen/fal").assertIsOff()
        assertEquals(JsonPrimitive("work"), rpc.calls.last()["profile"])
    }

    @Test fun legacyReadOnlyAndRefreshFailureRecovery() = runTest {
        val rpc = Rpc().apply { legacy = true }
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val store = PluginStore(backgroundScope, Decisions())
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                com.hermesagent.mobile.ui.settings.PluginsScreen(store, agentHost = host, selectedProfile = "default")
            }
        }
        drain(testScheduler)
        // A real key matching the legacy index label must not collide in LazyColumn identity.
        compose.onNodeWithTag("plugins-list").performScrollToNode(hasContentDescription("Enable Legacy"))
        compose.onNodeWithContentDescription("Enable Legacy").assertIsNotEnabled()
        assertEquals(1, rpc.calls.size)
        rpc.failure = true
        compose.onNodeWithTag("agent-plugins-refresh").performScrollTo().performClick()
        drain(testScheduler)
        compose.onNodeWithText("Could not load agent plugins").assertExists()
        rpc.failure = false; rpc.legacy = false
        compose.onNodeWithTag("agent-plugins-refresh").performClick()
        drain(testScheduler)
        scrollTo("agent-plugin-toggle-image_gen/fal").assertIsEnabled()
    }

    @Test fun unsupportedIsUnavailableNotEmptyOrWip() = runTest {
        val rpc = Rpc().apply { unsupported = true }
        val host = GatewayPluginHost(backgroundScope, MutableStateFlow(rpc))
        val store = PluginStore(backgroundScope, Decisions())
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                com.hermesagent.mobile.ui.settings.PluginsScreen(store, agentHost = host)
            }
        }
        drain(testScheduler)
        compose.onNodeWithText("Agent plugins are unavailable on this Gateway. Update Hermes and reconnect.").assertExists()
        compose.onNodeWithText("No agent plugins installed for this profile.").assertDoesNotExist()
        compose.onNodeWithTag("WIP", useUnmergedTree = true).assertDoesNotExist()
    }

    // Composition -> host request -> composition -> independent readback -> composition.
    // Both schedulers are advanced explicitly; no sleeps or real network.
    private fun drain(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) {
        repeat(3) { compose.waitForIdle(); scheduler.runCurrent() }
        compose.waitForIdle()
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("plugins-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private class Decisions : PluginDecisionStore {
        override val pluginDecisions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        override suspend fun savePluginDecision(id: String, enabled: Boolean) {}
    }

    private class Rpc : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        val calls = mutableListOf<JsonObject>()
        var legacy = false
        var failure = false
        var unsupported = false
        val enabled = mutableSetOf<String>()
        override suspend fun request(method: String, params: JsonObject): JsonElement = error("unguarded")
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            if (!dispatch { calls += params; true }) throw GatewayRpcException("revoked")
            if (unsupported) throw GatewayRpcError(-32601, "unsupported")
            if (failure) throw GatewayRpcException("synthetic failure")
            val profile = params.getValue("profile").jsonPrimitive.content
            if (params["action"] == JsonPrimitive("toggle")) {
                val identity = "$profile:${params.getValue("key").jsonPrimitive.content}"
                if (params["enable"] == JsonPrimitive(true)) enabled += identity else enabled -= identity
                return buildJsonObject { put("ok", true) }
            }
            return buildJsonObject {
                put("plugins", JsonArray((if (legacy) listOf("legacy-1", null) else listOf("image_gen/fal", "video_gen/fal")).map { key ->
                    buildJsonObject {
                        if (key != null) put("key", key)
                        put("name", if (key == null) "Legacy" else "fal")
                        put("status", if ("$profile:$key" in enabled) "enabled" else "disabled")
                        put("description", "Synthetic plugin"); put("source", "user"); put("version", "1.0")
                    }
                }))
            }
        }
        override fun close() {}
    }
}
