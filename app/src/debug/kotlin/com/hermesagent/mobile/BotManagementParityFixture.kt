package com.hermesagent.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.plugins.bots.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

internal val BOT_MODEL_CAPTURE_STATES = setOf("loaded", "inventory-loading", "inventory-error", "manual",
    "confirmation", "saved", "save-refused").map { "bot-model-$it" }.toSet()

/** Production actions over synthetic memory only; legacy fixtures still refuse every write. */
internal class BotManagementParityFixture(scope: CoroutineScope) {
    val host = BotManagementFixtureHost()
    private val storage = object : PluginStorage {
        private val values = mutableMapOf<String, String>()
        override suspend fun get(key: String, fallback: String?) = values[key] ?: fallback
        override suspend fun set(key: String, value: String) { values[key] = value }
        override suspend fun remove(key: String) { values.remove(key) }
    }
    private val row = BotRosterRow("synthetic-planner", displayName = "Synthetic Planner", description = "Reviews release plans")
    val roster = BotsRosterUiState(
        phase = BotsRosterPhase.Ready, connectionUp = true, managementRows = listOf(row),
        userSections = listOf(BotSection("synthetic-team", "Planning")),
        sections = listOf(BotSectionBlock(null, "section:unassigned", "Unassigned", listOf(row))),
    )
    val model = BotsManagementViewModel(host, storage, scope, {}, initialSections = roster.userSections,
        storageEndpoint = MutableStateFlow(BotStorageEndpoint("synthetic-capture", 0L)),
        clock = { java.time.Instant.parse("2026-09-17T16:00:00Z").toEpochMilli() }, newSectionId = { "synthetic-new-section" })

    fun open(state: String) {
        host.scenario = state.takeIf { it in BOT_MODEL_CAPTURE_STATES }
        if (state in BOT_MODEL_CAPTURE_STATES) {
            model.act(row, BotRowAction.Edit, roster)
            return
        }
        when (state) {
            "bot-roster" -> Unit
            "bot-new" -> model.openNew(0L)
            "bot-edit" -> model.act(row, BotRowAction.Edit, roster)
            "bot-duplicate" -> model.act(row, BotRowAction.Duplicate, roster)
            "bot-move" -> model.act(row, BotRowAction.Move, roster)
            "bot-section" -> model.openSection(0L, roster.userSections.single(), choices = roster.userSections)
            else -> error("unsupported bot management parity state")
        }
    }

    // Capture staging, not recorded user gestures. Never overwrite a UI state/result.
    suspend fun stage(state: String) {
        if (state !in setOf("bot-model-confirmation", "bot-model-saved", "bot-model-save-refused")) return
        val editor = model.model
        editor.state.first { it.editable && !it.inventoryLoading }
        host.runtimeEvents += "model-edit:staged"
        editor.update(editor.state.value, BotModelSelection("synthetic-provider", "synthetic-planner-v2"))
        host.runtimeEvents += "save:staged"
        editor.save(editor.state.value)
        editor.state.first { !it.busy }
        if (state == "bot-model-saved") {
            check(editor.state.value.warning != null)
            host.runtimeEvents += "confirm:staged"
            editor.confirm(editor.state.value)
            editor.state.first { !it.busy }
        }
    }
}

internal class BotManagementFixtureHost : PluginHost {
    data class Call(val method: String, val params: JsonObject)
    val requests = mutableListOf<Call>()
    val calls: List<String> get() = requests.map { it.method }
    var scenario: String? = null
        set(value) { require(value == null || value in BOT_MODEL_CAPTURE_STATES); field = value }
    var authoritative = BotModelSelection("synthetic-provider", "synthetic-planner-v1")
        private set
    private var warned: JsonObject? = null
    var readbackGate: CompletableDeferred<Unit>? = null
    private var applied = false
    override val connected = MutableStateFlow(true)
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    private var sequence = 0
    private data class RuntimeCall(val sequence: Int, val method: String, val params: JsonObject,
        val started: Long, var response: JsonElement = JsonNull, var outcome: String = "pending")
    private val runtimeCalls = mutableListOf<RuntimeCall>()
    val runtimeEvents = mutableListOf<String>()
    fun runtimeSnapshot(): JsonObject = buildJsonObject {
        put("authoritative_model", authoritative.model)
        put("events", buildJsonArray { runtimeEvents.forEach { add(it) } })
        put("requests", buildJsonArray {
            runtimeCalls.forEach { call -> add(buildJsonObject {
                put("sequence", call.sequence); put("request_id", "synthetic-rpc-${call.sequence}")
                put("method", call.method); put("params", call.params)
                put("response", call.response); put("outcome", call.outcome)
                put("pending", call.outcome == "pending")
                put("error", if (call.outcome in setOf("refused", "cancelled")) JsonPrimitive(call.outcome) else JsonNull)
                put("elapsed_ms", (System.nanoTime() - call.started) / 1_000_000.0)
            }) }
        })
    }
    override suspend fun request(method: String, params: JsonObject): PluginHostResult {
        requests += Call(method, params)
        // Never export arbitrary caller input, even from this synthetic host.
        val safe = method in setOf("profiles.describe", "profiles.configure", "model.options", "profiles.list") &&
            params.all { (key, value) -> when (key) {
                "name", "profile" -> value == JsonPrimitive("synthetic-planner")
                "provider" -> value == JsonPrimitive("synthetic-provider")
                "model" -> value == JsonPrimitive("synthetic-planner-v2")
                "include_unconfigured", "include_sessions", "confirm_expensive_model" -> value == JsonPrimitive(true)
                "explicit_only" -> value == JsonPrimitive(false)
                else -> false
            } }
        if (!safe) return PluginHostResult.UnavailableOnGateway
        val call = RuntimeCall(++sequence, method, params, System.nanoTime())
        runtimeCalls += call
        try {
            val result = answer(method, params)
            call.response = (result as? PluginHostResult.Success)?.result ?: JsonNull
            call.outcome = if (result is PluginHostResult.Success) "loaded" else "refused"
            when (method) {
                "profiles.describe" -> if (result is PluginHostResult.Success) runtimeEvents += if (applied) "describe:saved-pair" else "describe:loaded"
                "model.options" -> runtimeEvents += "model.options:${call.outcome}"
                "profiles.configure" -> {
                    call.outcome = if (result !is PluginHostResult.Success) "refused" else if (applied) "applied" else "warning"
                    runtimeEvents += when (call.outcome) {
                        "warning" -> "configure:confirmation-required"
                        "applied" -> "configure:applied"
                        else -> "configure:initial-write-refused"
                    }
                }
            }
            return result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            call.outcome = "cancelled"
            throw cancelled
        }
    }
    private suspend fun answer(method: String, params: JsonObject): PluginHostResult {
        return when (method) {
            "profiles.describe" -> {
                if (params != buildJsonObject { put("name", "synthetic-planner") }) return PluginHostResult.UnavailableOnGateway
                if (applied) readbackGate?.await()
                PluginHostResult.Success(buildJsonObject {
                put("name", "synthetic-planner"); put("description", "Reviews release plans")
                put("soul", "Review synthetic release plans.")
                put("model", buildJsonObject {
                    put("provider", authoritative.provider); put("default", authoritative.model)
                })
            })
            }
            "profiles.configure" -> configure(params)
            "model.options" -> {
                if (params != buildJsonObject {
                    put("profile", "synthetic-planner"); put("include_unconfigured", true); put("explicit_only", false)
                }) return PluginHostResult.UnavailableOnGateway
                if (scenario == "bot-model-inventory-loading") awaitCancellation()
                if (scenario == "bot-model-inventory-error") return PluginHostResult.UnavailableOnGateway
                PluginHostResult.Success(buildJsonObject {
                put("providers", buildJsonArray { add(buildJsonObject {
                    put("slug", "synthetic-provider"); put("name", "Synthetic Provider")
                    put("aliases", buildJsonArray {})
                    put("models", buildJsonArray { add("synthetic-planner-v1"); add("synthetic-planner-v2") })
                }) })
            })
            }
            "profiles.list" -> PluginHostResult.Success(buildJsonObject {
                put("profiles", buildJsonArray { add(buildJsonObject {
                    put("name", "synthetic-planner")
                    put("ui_meta", buildJsonObject { put("hermes-bots", buildJsonObject { put("title", "Synthetic Planner") }) })
                }) })
            })
            else -> PluginHostResult.UnavailableOnGateway
        }
    }

    private fun configure(params: JsonObject): PluginHostResult {
        val pair = buildJsonObject {
            put("name", "synthetic-planner"); put("provider", "synthetic-provider"); put("model", "synthetic-planner-v2")
        }
        val consent = JsonObject(pair + ("confirm_expensive_model" to JsonPrimitive(true)))
        if (scenario !in setOf("bot-model-confirmation", "bot-model-saved") || applied)
            return PluginHostResult.UnavailableOnGateway
        if (params == pair) {
            warned = pair
            return PluginHostResult.Success(buildJsonObject {
                put("ok", true); put("confirm_required", true)
                put("confirm_message", "Synthetic model cost and data policy require confirmation.")
                put("applied", buildJsonObject { put("model", false) })
            })
        }
        if (params != consent || warned != pair) return PluginHostResult.UnavailableOnGateway
        authoritative = BotModelSelection("synthetic-provider", "synthetic-planner-v2")
        applied = true
        return PluginHostResult.Success(buildJsonObject {
            put("ok", true); put("applied", buildJsonObject { put("model", true) })
        })
    }
}

@Composable
internal fun BotManagementParityContent(state: String, theme: String = "dark") {
    val scope = rememberCoroutineScope()
    val fixture = remember { BotManagementParityFixture(scope) }
    DisposableEffect(fixture, state, theme) {
        val exporter = {
            val draft = fixture.model.model.state.value.draft
            check(draft.provider == "synthetic-provider" && draft.model in setOf("synthetic-planner-v1", "synthetic-planner-v2")) {
                "Only allowlisted synthetic selections can be exported"
            }
            buildJsonObject {
            put("fixture_id", "bot-model-config-synthetic-v2"); put("state", state)
            put("theme", theme); put("runtime", fixture.host.runtimeSnapshot())
            put("capture_inputs", buildJsonObject {
                put("skin", "mono"); put("theme", theme); put("locale", java.util.Locale.getDefault().toLanguageTag())
                put("timezone", java.util.TimeZone.getDefault().id); put("clock", "2026-09-17T16:00:00Z"); put("timers", "live")
            })
            put("synthetic_inputs", buildJsonObject {
                put("name", "synthetic-planner"); put("title", "Synthetic Planner")
                put("description", "Reviews release plans"); put("soul", "Review synthetic release plans.")
                put("provider", "synthetic-provider"); put("provider_name", "Synthetic Provider")
                put("initial_model", "synthetic-planner-v1"); put("requested_model", "synthetic-planner-v2")
                put("confirmation_message", "Synthetic model cost and data policy require confirmation.")
            })
            put("draft", buildJsonObject {
                put("provider", fixture.model.model.state.value.draft.provider)
                put("model", fixture.model.model.state.value.draft.model)
            })
        } }
        if (state in BOT_MODEL_CAPTURE_STATES) BotModelRuntimeProvider.snapshot = exporter
        onDispose { if (BotModelRuntimeProvider.snapshot === exporter) BotModelRuntimeProvider.snapshot = null }
    }
    val management by fixture.model.state.collectAsState()
    val modelState by fixture.model.model.state.collectAsState()
    LaunchedEffect(state) { fixture.open(state); fixture.stage(state) }
    BotsRosterScreen(fixture.roster, onBack = {}, modifier = Modifier.fillMaxSize(), actions = BotsActions(
        onNewBot = fixture.model::openNew,
        onRowAction = fixture.model::act,
        onSection = { section, roster -> fixture.model.openSection(roster.endpoint, section, roster.managementRows, roster.userSections) },
    ))
    BotManagementSheet(management, BotManagementActions(
        onClose = fixture.model::close, onUpdate = fixture.model::updateDraft,
        onSectionName = fixture.model::updateSectionName, onSection = fixture.model::selectSection,
        onSubmit = fixture.model::submit, onDeleteSection = fixture.model::deleteSection,
        onMoveSection = fixture.model::moveSection,
    ), modelState = modelState, modelActions = BotModelActions(
        onUpdate = { fixture.model.model.update(modelState, it) },
        onSave = { fixture.model.model.save(modelState) },
        onConfirm = { fixture.model.model.confirm(modelState) },
        onCancelWarning = { fixture.model.model.cancelWarning(modelState) },
    ))
}
