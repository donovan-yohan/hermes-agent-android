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
        clock = { 0L }, newSectionId = { "synthetic-new-section" })

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
        editor.update(editor.state.value, BotModelSelection("synthetic-provider", "synthetic-planner-v2"))
        editor.save(editor.state.value)
        editor.state.first { !it.busy }
        if (state == "bot-model-saved") {
            check(editor.state.value.warning != null)
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
    override suspend fun request(method: String, params: JsonObject): PluginHostResult {
        requests += Call(method, params)
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
internal fun BotManagementParityContent(state: String) {
    val scope = rememberCoroutineScope()
    val fixture = remember { BotManagementParityFixture(scope) }
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
