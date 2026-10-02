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
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.plugins.bots.*
import com.hermesagent.mobile.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Debug-only synthetic model transport; never reaches application accounts or network. */
class BotSkillsParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scenario = intent.getStringExtra("visual_parity_state") ?: "skills-loaded"
        require(scenario in BotSkillsFixture.STATES)
        val theme = if (intent.getStringExtra("visual_parity_theme") == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent {
            val scope = rememberCoroutineScope()
            val fixture = remember(scenario) { BotSkillsFixture(scenario, scope) }
            LaunchedEffect(fixture) { fixture.stage() }
            DisposableEffect(fixture) { onDispose { fixture.vm.close() } }
            val state by fixture.vm.state.collectAsState()
            HermesTheme(AppearanceSelection("mono", theme)) {
                Column(Modifier.fillMaxSize().background(HermesTheme.tokens.cardSurface).systemBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Edit profile", style = HermesTheme.type.screenTitle)
                    BotSkillsEditor(state, BotSkillsActions(onToggle = { fixture.vm.toggle(state, it) },
                        onRefresh = { fixture.vm.refresh(state) }))
                }
            }
        }
    }
}

/** Real typed host parsing/readback over allowlisted synthetic HTTP outcomes, with production deadlines. */
internal class BotSkillsFixture(val scenario: String, scope: CoroutineScope) : PluginHost {
    init { require(scenario in STATES) }
    override val endpointGeneration = MutableStateFlow(0L)
    override val connected = MutableStateFlow(true)
    val calls = mutableListOf<String>()
    private var enabled = scenario == "skills-loaded"
    private var wrote = false
    private val transport = object : EndpointDispatchingGatewayHttp {
        override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("Unguarded fixture request")
        override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult {
            check(request.path in setOf("/api/skills", "/api/skills/toggle"))
            check(request.query["profile"] == "synthetic-skills" || scenario == "skills-unavailable" && request.method == "GET")
            if (!dispatch { calls += request.method; true }) return GatewayHttpResult.Rejected(0, "Synthetic revoked")
            if (request.method == "GET") {
                if (scenario == "skills-loading") return withTimeoutOrNull(request.timeoutMillis) {
                    awaitCancellation()
                } ?: GatewayHttpResult.Rejected(0, "Synthetic deadline")
                if (scenario == "skills-error") return GatewayHttpResult.Rejected(403, "Synthetic refusal")
                if (scenario == "skills-unavailable") return GatewayHttpResult.Rejected(404, "Synthetic absent route")
                if (scenario == "skills-unconfirmed" && wrote) return GatewayHttpResult.Rejected(0, "Synthetic lost readback")
                return success(if (scenario == "skills-empty") "[]" else
                    """[{"name":"synthetic-research","enabled":$enabled},{"name":"hermes-agent","enabled":true}]""")
            }
            check(request.method == "PUT" && request.path == "/api/skills/toggle")
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            check(body.keys == setOf("name", "enabled", "profile") && body["profile"] == JsonPrimitive("synthetic-skills"))
            val name = body.getValue("name").jsonPrimitive.content
            check(name in setOf("synthetic-research", "hermes-agent"))
            val desired = body.getValue("enabled").jsonPrimitive.boolean
            if (scenario == "skills-pending") return withTimeoutOrNull(request.timeoutMillis) {
                awaitCancellation()
            } ?: GatewayHttpResult.Rejected(0, "Synthetic deadline")
            if (scenario == "skills-refused") return GatewayHttpResult.Rejected(403, "Synthetic refused mutation")
            wrote = true
            if (name != "hermes-agent") enabled = desired
            return success("""{"ok":true,"name":"$name","enabled":$desired}""")
        }
    }
    override val skills: PluginSkills = GatewayPluginSkills(scope, { transport }, endpointGeneration, EndpointDispatchFence())
    val vm = BotsSkillsViewModel(this, scope)
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("No fixture RPC")
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    suspend fun stage() {
        val target = BotManagementTarget("synthetic-skills", 0L)
        vm.open(target)
        if (scenario == "skills-loading") return
        vm.state.first { !it.loading }
        if (scenario in setOf("skills-pending", "skills-saved", "skills-reopened", "skills-refused", "skills-essential", "skills-unconfirmed")) {
            vm.toggle(vm.state.value, if (scenario == "skills-essential") "hermes-agent" else "synthetic-research")
            if (scenario == "skills-pending") return
            vm.state.first { !it.busy && !it.loading }
        }
        if (scenario == "skills-reopened") { vm.close(); vm.open(target); vm.state.first { !it.loading } }
    }
    private fun success(body: String) = GatewayHttpResult.Success(200, body.toByteArray())
    companion object { val STATES = setOf("skills-loaded", "skills-disabled", "skills-loading", "skills-pending",
        "skills-saved", "skills-reopened", "skills-error", "skills-unavailable", "skills-refused", "skills-essential",
        "skills-empty", "skills-unconfirmed") }
}
