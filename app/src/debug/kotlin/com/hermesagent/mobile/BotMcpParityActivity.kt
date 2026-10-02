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

/** Debug-only synthetic transport. No application accounts, repositories or network. */
class BotMcpParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val scenario = intent.getStringExtra("visual_parity_state") ?: "mcp-loaded"
        require(scenario in BotMcpFixture.STATES)
        val theme = if (intent.getStringExtra("visual_parity_theme") == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        setContent {
            val scope = rememberCoroutineScope()
            val fixture = remember(scenario) { BotMcpFixture(scenario, scope, if (theme == HermesThemeMode.Light) "light" else "dark") }
            LaunchedEffect(fixture) { fixture.stage() }
            DisposableEffect(fixture) {
                val exporter = { fixture.snapshot() }
                BotMcpRuntimeProvider.snapshot = exporter
                onDispose {
                    fixture.vm.close()
                    if (BotMcpRuntimeProvider.snapshot === exporter) BotMcpRuntimeProvider.snapshot = null
                }
            }
            val state by fixture.vm.state.collectAsState()
            HermesTheme(AppearanceSelection("mono", theme)) {
                Column(Modifier.fillMaxSize().background(HermesTheme.tokens.cardSurface).systemBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Edit profile", style = HermesTheme.type.screenTitle)
                    BotMcpEditor(state, BotMcpActions(onToggle = { fixture.vm.toggle(state, it) },
                        onRefresh = { fixture.vm.refresh(state) }))
                }
            }
        }
    }
}

/** Real typed host projection and readback; only allowlisted synthetic requests can be admitted. */
internal class BotMcpFixture(val scenario: String, scope: CoroutineScope, private val theme: String = "dark") : PluginHost {
    init { require(scenario in STATES) }
    override val endpointGeneration = MutableStateFlow(0L)
    override val connected = MutableStateFlow(true)
    val calls = mutableListOf<String>()
    private val requests = mutableListOf<JsonObject>()
    private var enabled = scenario == "mcp-loaded"
    private var wrote = false
    var cancelAfterPut = false
    var afterPut: suspend () -> Unit = {}
    private val transport = object : EndpointDispatchingGatewayHttp {
        override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("Unguarded fixture request")
        override suspend fun executeAtDispatch(request: GatewayHttpRequest, dispatch: (() -> Boolean) -> Boolean): GatewayHttpResult {
            check(request.path == "/api/mcp/servers" && request.method == "GET" ||
                request.path == "/api/mcp/servers/synthetic-research/enabled" && request.method == "PUT")
            check(request.query == mapOf("profile" to "synthetic-mcp") ||
                scenario == "mcp-unavailable" && request.method == "GET" && request.query.isEmpty())
            val body = request.body?.let {
                val buffer = okio.Buffer(); it.writeTo(buffer)
                Json.parseToJsonElement(buffer.readUtf8()).jsonObject.also { value ->
                    check(value.keys == setOf("enabled", "profile"))
                    check(value["profile"] == JsonPrimitive("synthetic-mcp"))
                    check(value["enabled"] == JsonPrimitive(true) || value["enabled"] == JsonPrimitive(false))
                }
            }
            if (!dispatch { true }) return GatewayHttpResult.Rejected(0, "Synthetic revoked")
            calls += request.method
            val index = requests.size
            val started = System.nanoTime()
            fun record(outcome: String, status: Int? = null, response: JsonElement? = null) = buildJsonObject {
                put("sequence", index + 1); put("method", request.method); put("path", request.path)
                put("query", buildJsonObject { request.query.forEach { (key, value) -> put(key, value) } })
                put("body", body ?: JsonNull); put("startedNanos", started); put("observedNanos", System.nanoTime())
                put("outcome", outcome); status?.let { put("status", it) }; response?.let { put("response", it) }
            }
            requests += record("pending")
            try {
                val result = if (request.method == "GET") read(request) else {
                    if (scenario == "mcp-pending") deadline(request)
                    else if (scenario == "mcp-refused") GatewayHttpResult.Rejected(403, "Synthetic refused mutation")
                    else {
                        wrote = true
                        enabled = body!!.getValue("enabled").jsonPrimitive.boolean
                        afterPut()
                        if (cancelAfterPut) throw CancellationException("Synthetic admitted cancellation")
                        success("""{"ok":true,"name":"synthetic-research","enabled":$enabled}""")
                    }
                }
                requests[index] = when (result) {
                    is GatewayHttpResult.Success -> record("completed", 200, Json.parseToJsonElement(result.bodyBytes.toString(Charsets.UTF_8)))
                    is GatewayHttpResult.Rejected -> record("completed", result.statusCode)
                }
                return result
            } catch (cancelled: CancellationException) {
                requests[index] = record("cancelled")
                throw cancelled
            }
        }
    }
    private suspend fun deadline(request: GatewayHttpRequest): GatewayHttpResult =
        withTimeoutOrNull(request.timeoutMillis) { awaitCancellation() }
            ?: GatewayHttpResult.Rejected(0, "Synthetic deadline")
    private suspend fun read(request: GatewayHttpRequest): GatewayHttpResult {
        if (scenario == "mcp-loading") return deadline(request)
        if (scenario == "mcp-error") return GatewayHttpResult.Rejected(403, "Synthetic refusal")
        if (scenario == "mcp-unavailable") return GatewayHttpResult.Rejected(404, "Synthetic absent route")
        if (scenario == "mcp-unconfirmed" && wrote) return GatewayHttpResult.Rejected(0, "Synthetic lost readback")
        val source = when (scenario) { "mcp-plugin" -> "plugin"; "mcp-unknown" -> "future-source"; else -> "config" }
        return success(if (scenario == "mcp-empty") """{"servers":[]}""" else
            """{"servers":[{"name":"synthetic-research","enabled":$enabled,"source":"$source"}]}""")
    }
    override val mcp: PluginMcp = GatewayPluginMcp(scope, { transport }, endpointGeneration, EndpointDispatchFence())
    val vm = BotsMcpViewModel(this, scope)
    override suspend fun request(method: String, params: JsonObject): PluginHostResult = error("No fixture RPC")
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    suspend fun stage() {
        val target = BotManagementTarget("synthetic-mcp", 0L)
        vm.open(target)
        if (scenario == "mcp-loading") return
        vm.state.first { !it.loading }
        if (scenario in setOf("mcp-pending", "mcp-saved", "mcp-reopened", "mcp-refused", "mcp-unconfirmed")) {
            vm.toggle(vm.state.value, "synthetic-research")
            if (scenario == "mcp-pending") return
            vm.state.first { !it.busy && !it.loading }
        }
        if (scenario == "mcp-reopened") { vm.close(); vm.open(target); vm.state.first { !it.loading } }
    }
    fun snapshot(): JsonObject = buildJsonObject {
        put("fixture", "bot-configured-mcp-synthetic-v1"); put("scenario", scenario); put("theme", theme)
        put("observedNanos", System.nanoTime())
        put("requests", JsonArray(requests.toList()))
        put("authoritativeEnabled", enabled)
        put("loading", vm.state.value.loading); put("busy", vm.state.value.busy)
        put("message", vm.state.value.message?.let(::JsonPrimitive) ?: JsonNull)
        put("rows", vm.state.value.rows?.let { rows -> JsonArray(rows.map { row -> buildJsonObject {
            check(row.name == "synthetic-research")
            put("name", row.name); put("enabled", row.enabled); put("source", row.source.name)
        } }) } ?: JsonNull)
    }
    private fun success(body: String) = GatewayHttpResult.Success(200, body.toByteArray())
    companion object { val STATES = setOf("mcp-loaded", "mcp-disabled", "mcp-plugin", "mcp-unknown", "mcp-loading",
        "mcp-pending", "mcp-saved", "mcp-reopened", "mcp-error", "mcp-unavailable", "mcp-refused", "mcp-empty", "mcp-unconfirmed") }
}
