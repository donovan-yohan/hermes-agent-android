package com.hermesagent.mobile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hermesagent.mobile.plugins.*
import com.hermesagent.mobile.plugins.bots.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

/** A production management model over an in-memory, allowlisted read transport. Writes refuse. */
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
}

internal class BotManagementFixtureHost : PluginHost {
    val calls = mutableListOf<String>()
    override val connected = MutableStateFlow(true)
    override fun onEvent(type: String, listener: (PluginHostEvent) -> Unit): () -> Unit = {}
    override suspend fun request(method: String, params: JsonObject): PluginHostResult {
        calls += method
        return when (method) {
            "profiles.describe" -> PluginHostResult.Success(buildJsonObject {
                put("name", "synthetic-planner"); put("description", "Reviews release plans")
                put("soul", "Review synthetic release plans.")
                put("model", buildJsonObject {
                    put("provider", "synthetic-provider"); put("default", "synthetic-planner-v1")
                })
            })
            "model.options" -> PluginHostResult.Success(buildJsonObject {
                put("providers", buildJsonArray { add(buildJsonObject {
                    put("slug", "synthetic-provider"); put("name", "Synthetic Provider")
                    put("aliases", buildJsonArray {})
                    put("models", buildJsonArray { add("synthetic-planner-v1"); add("synthetic-planner-v2") })
                }) })
            })
            "profiles.list" -> PluginHostResult.Success(buildJsonObject {
                put("profiles", buildJsonArray { add(buildJsonObject {
                    put("name", "synthetic-planner")
                    put("ui_meta", buildJsonObject { put("hermes-bots", buildJsonObject { put("title", "Synthetic Planner") }) })
                }) })
            })
            else -> PluginHostResult.UnavailableOnGateway
        }
    }
}

@Composable
internal fun BotManagementParityContent(state: String) {
    val scope = rememberCoroutineScope()
    val fixture = remember { BotManagementParityFixture(scope) }
    val management by fixture.model.state.collectAsState()
    val modelState by fixture.model.model.state.collectAsState()
    LaunchedEffect(state) { fixture.open(state) }
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
