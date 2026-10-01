package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.plugins.bots.*
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Debug-only synthetic capture. Never resolves an app repository, account, or Gateway. */
class BotToolsetsParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scenario = intent.getStringExtra("visual_parity_state") ?: "defaults"
        require(scenario in BotToolsetsFixture.STATES)
        val theme = if (intent.getStringExtra("visual_parity_theme") == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent {
            val scope = rememberCoroutineScope()
            val fixture = remember(scenario) { BotToolsetsFixture(scenario, scope) }
            LaunchedEffect(fixture) { fixture.stage() }
            val state by fixture.vm.state.collectAsState()
            HermesTheme(AppearanceSelection("mono", theme)) {
                Column(Modifier.fillMaxSize().background(HermesTheme.tokens.cardSurface).systemBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Edit profile", style = HermesTheme.type.screenTitle)
                    BotToolsetsEditor(state, BotToolsetsActions(
                        onToggle = { fixture.vm.toggle(state, it) }, onSave = { fixture.vm.save(state) },
                        onRestore = { fixture.vm.requestDefaults(state) }, onConfirm = { fixture.vm.confirmDefaults(state) },
                        onCancel = { fixture.vm.cancelDefaults(state) }))
                    ComingSoonAction("Skills")
                    ComingSoonAction("MCP servers")
                }
            }
        }
    }
}

/** Stages production actions, never fabricated success flags. Loading retains the real read timeout. */
internal class BotToolsetsFixture(val scenario: String, scope: CoroutineScope) : PluginHost {
    init { require(scenario in STATES) }
    override val endpointGeneration = MutableStateFlow(7L)
    override val connected = MutableStateFlow(true)
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var pinned = scenario !in setOf("defaults", "loading", "empty", "error")
        private set
    var selected = setOf("web")
        private set
    val vm = BotsToolsetsViewModel(this, scope, {})
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("Unfenced fixture request")
    override suspend fun requestAtEndpoint(expectedGeneration: Long, method: String, params: JsonObject): PluginHostResult =
        requestAtEndpointGuarded(expectedGeneration, method, params) { true }
    override suspend fun requestAtEndpointGuarded(expectedGeneration: Long, method: String, params: JsonObject,
        dispatchAllowed: () -> Boolean): PluginHostResult {
        check(expectedGeneration == 7L && dispatchAllowed())
        check(params["name"] == JsonPrimitive("synthetic-toolsets"))
        calls += method to params
        return when (method) {
            "profiles.describe" -> {
                check(params.keys == setOf("name"))
                if (scenario == "loading") awaitCancellation()
                if (scenario == "error") return PluginHostResult.Refused(0, "Synthetic read failure")
                PluginHostResult.Success(buildJsonObject {
                    put("name", "synthetic-toolsets"); put("toolsets_pinned", pinned)
                    putJsonArray("toolsets") {
                        if (scenario != "empty") for ((name, label, description) in listOf(
                            Triple("web", "Web", "Search and read web pages"), Triple("terminal", "Terminal", "Run shell commands"))) {
                            add(buildJsonObject {
                                put("name", name); put("label", label); put("description", description)
                                put("enabled", name in selected); put("tool_count", if (name == "web") 2 else 1)
                            })
                        }
                    }
                })
            }
            "profiles.configure" -> {
                check(params.keys == setOf("name", "enabled_toolsets"))
                val wanted = params.getValue("enabled_toolsets").jsonArray.map { it.jsonPrimitive.content }.toSet()
                check(wanted.all { it in setOf("web", "terminal") })
                pinned = wanted.isNotEmpty(); selected = wanted.ifEmpty { setOf("terminal") }
                if (scenario == "unconfirmed") PluginHostResult.Refused(0, "Synthetic lost receipt")
                else PluginHostResult.Success(Json.parseToJsonElement("""{"ok":true,"applied":{"toolsets":true}}"""))
            }
            else -> error("Unexpected fixture method")
        }
    }
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    suspend fun stage() {
        vm.open(BotManagementTarget("synthetic-toolsets", 7L))
        if (scenario == "loading") return
        vm.state.first { !it.loading }
        when (scenario) {
            "changed", "all-selected", "saved", "unconfirmed" -> vm.toggle(vm.state.value, "terminal")
            "empty-selection" -> vm.toggle(vm.state.value, "web")
            "reset-confirmation", "restored" -> vm.requestDefaults(vm.state.value)
        }
        if (scenario in setOf("saved", "unconfirmed")) vm.save(vm.state.value)
        if (scenario == "restored") vm.confirmDefaults(vm.state.value)
        vm.state.first { !it.busy }
    }
    companion object {
        val STATES = setOf("loading", "defaults", "pinned", "changed", "all-selected", "empty-selection",
            "reset-confirmation", "saved", "restored", "error", "empty", "unconfirmed")
    }
}
