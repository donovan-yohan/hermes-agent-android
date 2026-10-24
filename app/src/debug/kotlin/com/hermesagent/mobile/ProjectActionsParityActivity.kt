package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hermesagent.mobile.data.connections.StorageEndpoint
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.prefs.*
import com.hermesagent.mobile.data.session.*
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.*
import com.hermesagent.mobile.ui.sessions.ProjectActions
import com.hermesagent.mobile.ui.theme.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Installed synthetic authority, never the application graph, personal preferences or network.
 * Retained activity owner keeps synthetic storage across Activity recreation. A new process resets it.
 * Staging: --es visual_parity_state overview|entered|auto|home --es visual_parity_theme dark|light.
 */
class ProjectActionsParityActivity : ComponentActivity() {
    internal lateinit var fixture: ProjectActionsFixtureOwner
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fixture = ViewModelProvider(this)[ProjectActionsFixtureOwner::class.java]
        val state = intent.getStringExtra("visual_parity_state") ?: "overview"
        require(state in listOf("overview", "entered", "auto", "home"))
        val mode = if (intent.getStringExtra("visual_parity_theme") == "light") HermesThemeMode.Light else HermesThemeMode.Dark
        val model = fixture.model
        setContent {
            val ui by model.uiState.collectAsState()
            LaunchedEffect(Unit) {
                if (!fixture.ready) {
                    model.uiState.first { !it.projectManagement.hiddenLoading && it.projects.size == 3 }
                    if (state != "overview") model.selectProject(when (state) { "entered" -> "synthetic-project"; "auto" -> "synthetic-auto"; else -> "__no_project__" })
                    fixture.ready = true
                }
            }
            HermesTheme(AppearanceSelection("mono", mode)) {
                ChatScreen(ui, ChatActions(
                    onQueryChange = model::setQuery, onDraftChange = model::setDraft,
                    onRefreshNavigation = model::refreshSessionNavigation,
                    onSidebarGroupingChange = model::setSidebarGrouping,
                    onSelectProject = model::selectProject, onExitProject = model::exitProject,
                    onSelectProjectSession = model::selectProjectSession,
                    onCreateProject = model::createProject,
                    projectActions = ProjectActions(model::openProjectActions, model::dismissProjectActions,
                        model::projectActionPage, model::executeProjectAction, model::hideProject,
                        model::restoreHiddenProject, model::projectPath,
                        model::openProjectRecovery, model::dismissProjectRecovery, model::selectProjectRecovery,
                        model::refreshProjectRecovery, model::reviewProjectRecovery, model::allowProjectAttempt),
                ), onOpenSettings = {}, modifier = Modifier.fillMaxSize().safeDrawingPadding())
            }
        }
    }
}

/** Real live repository + real ViewModel. Only wire/connection/storage producers are synthetic. */
internal class ProjectActionsFixtureOwner : ViewModel() {
    val cache = SessionCache()
    val rpc = ProjectActionsFixtureRpc()
    val repository = LiveGatewaySessionRepository(cache,
        MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
        MutableStateFlow<GatewayRpcClient?>(rpc), viewModelScope) { 1_800_000_000_000 }
    val storage = TransientHiddenProjectsStore()
    val model = ChatViewModel(cache, repository, TransientSidebarViewStore(SidebarGrouping.Project),
        hiddenProjectsStore = storage, storageEndpoint = MutableStateFlow(StorageEndpoint("synthetic-row-address", 0)),
        clock = { 1_800_000_000_000 })
    var ready = false
}

internal class ProjectActionsFixtureRpc : EndpointDispatchingGatewayRpcClient {
    override val events = emptyFlow<GatewayEvent>()
    override fun close() = Unit
    val requests = mutableListOf<Pair<String, JsonObject>>()
    var uncertainCreate = false
    var failInventory = false
    var failWrites = false
    var failReadback = false
    private var active: String? = null
    private val rows = linkedMapOf(
        "synthetic-project" to ProjectSummary("synthetic-project", "Synthetic project", "/workspace/project", sessionCount = 1),
        "synthetic-auto" to ProjectSummary("synthetic-auto", "Synthetic repository", "/workspace/repository", isAuto = true),
        "__no_project__" to ProjectSummary("__no_project__", "Home", null, isHome = true),
    )
    override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
        check(dispatch { true }) { "Retired synthetic owner" }
        return request(method, params)
    }
    override suspend fun request(method: String, params: JsonObject): JsonElement {
        requests += method to params
        fun text(key: String) = params[key]?.jsonPrimitive?.contentOrNull
        val id = text("id")
        if (failWrites && method in setOf("projects.update", "projects.add_folder", "projects.set_active", "projects.delete", "projects.create")) {
            // Model a definite no-write refusal, not a generic error that could follow a commit.
            throw GatewayRpcError(JSON_RPC_METHOD_NOT_FOUND, "Synthetic missing mutation method")
        }
        when (method) {
            "projects.tree" -> { check(!failReadback); return buildJsonObject {
                put("projects", JsonArray(rows.values.map(::node))); put("active_id", active?.let(::JsonPrimitive) ?: JsonNull)
            } }
            "projects.list" -> {
                check(!failInventory)
                return buildJsonObject { put("projects", JsonArray(rows.values.filter { !it.isAuto && !it.isHome }.map { row ->
                    buildJsonObject {
                        put("id", row.id); put("name", row.label); put("archived", false)
                        put("primary_path", row.path?.let(::JsonPrimitive) ?: JsonNull)
                        put("folders", JsonArray(listOfNotNull(row.path).map { buildJsonObject { put("path", it) } }))
                    }
                })) }
            }
            "projects.project_sessions" -> return buildJsonObject { put("project", node(rows.getValue(text("project_id")!!))) }
            "projects.update" -> {
                val row = rows.getValue(id!!)
                rows[id] = row.copy(label = text("name") ?: row.label,
                    color = if ("color" in params) text("color")?.takeIf { it.isNotEmpty() } else row.color,
                    icon = if ("icon" in params) text("icon")?.takeIf { it.isNotEmpty() } else row.icon)
            }
            "projects.add_folder" -> if (params["is_primary"]?.jsonPrimitive?.boolean == true) rows[id!!] = rows.getValue(id).copy(path = text("path"))
            "projects.set_active" -> active = id
            "projects.delete" -> { rows.remove(id); if (active == id) active = null }
            "projects.create" -> {
                check(!uncertainCreate) { "Synthetic lost acknowledgement" }
                val path = text("primary_path")!!
                rows.entries.removeAll { it.value.isAuto && it.value.path == path }
                rows["synthetic-adopted"] = ProjectSummary("synthetic-adopted", text("name")!!, path, color = text("color"), icon = text("icon"))
                return buildJsonObject { put("project", buildJsonObject { put("id", JsonPrimitive("synthetic-adopted")) }) }
            }
            "session.list" -> return Json.parseToJsonElement("""{"sessions":[]}""")
        }
        return buildJsonObject { put("active_id", active?.let(::JsonPrimitive) ?: JsonNull) }
    }
    private fun node(row: ProjectSummary): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(row.id)); put("label", JsonPrimitive(row.label)); row.path?.let { put("path", JsonPrimitive(it)) }
        put("isAuto", JsonPrimitive(row.isAuto)); put("isNoProject", JsonPrimitive(row.isHome))
        row.color?.let { put("color", JsonPrimitive(it)) }; row.icon?.let { put("icon", JsonPrimitive(it)) }
        put("sessionCount", JsonPrimitive(row.sessionCount)); put("repos", JsonArray(emptyList())); put("previewSessions", JsonArray(emptyList()))
    }
}
