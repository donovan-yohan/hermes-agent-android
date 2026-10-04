package com.hermesagent.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.prefs.TransientSidebarViewStore
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Debug-only installed counterpart to ChatJourneyTest: real ViewModel and ChatScreen.
 * All authorities are transient synthetic inputs. No application repository, prefs,
 * connection, disk, network, or user data is read. The original sidebar fixture is unchanged.
 */
class SidebarProjectionParityActivity : ComponentActivity() {
    internal lateinit var fixture: SidebarProjectionParityFixture
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val state = requireNotNull(intent.getStringExtra("visual_parity_state"))
        require(state in SidebarProjectionParityFixture.states)
        val mode = when (intent.getStringExtra("visual_parity_theme")) {
            "dark" -> HermesThemeMode.Dark
            "light" -> HermesThemeMode.Light
            else -> error("Unsupported synthetic sidebar theme")
        }
        fixture = ViewModelProvider(this)[SidebarProjectionFixtureOwner::class.java].fixture
        SidebarProjectionRuntimeProvider.active = fixture
        // Both the synthetic authorities and production model survive Activity recreation.
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = fixture.viewModel as T
        })[ChatViewModel::class.java]
        setContent {
            val uiState by model.uiState.collectAsState()
            LaunchedEffect(model, state) { if (!fixture.ready) fixture.stage(model, state) }
            HermesTheme(AppearanceSelection("mono", mode)) {
                ChatScreen(
                    state = uiState,
                    actions = ChatActions(
                        onQueryChange = model::setQuery,
                        onDraftChange = model::setDraft,
                        onRefreshNavigation = model::refreshSessionNavigation,
                        onSidebarGroupingChange = model::setSidebarGrouping,
                        onSelectProject = model::selectProject,
                        onExitProject = model::exitProject,
                        onSelectSession = model::selectSession,
                    ),
                    onOpenSettings = {},
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }
    override fun onDestroy() {
        if (::fixture.isInitialized && SidebarProjectionRuntimeProvider.active === fixture) {
            SidebarProjectionRuntimeProvider.active = null
        }
        super.onDestroy()
    }
}

internal class SidebarProjectionFixtureOwner : ViewModel() {
    val fixture = SidebarProjectionParityFixture()
}

/** Uses the same construction/publication APIs as the existing live Compose journey. */
internal class SidebarProjectionParityFixture {
    val cache = SessionCache().apply {
        upsertSessions(sessions)
        replaceProjectOverview(projects, activeProjectId = "synthetic-project")
    }
    private val repository = object : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        override suspend fun refreshSessions() = Unit
        override suspend fun openProject(projectId: String) {
            check(projectId == "synthetic-project")
            cache.replaceProjectDetails(projects.single(), sessions)
        }
        override suspend fun openSession(durableId: String): String = durableId.also { check(it in sessions.map { row -> row.id }) }
        override suspend fun openSession(durableId: String, profile: String): String = openSession(durableId)
        override suspend fun createSession(workspacePath: String?): String = error("Capture is read-only")
        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome = error("Capture is read-only")
        override suspend fun interrupt(durableId: String) = error("Capture is read-only")
    }
    val viewModel = ChatViewModel(
        cache, repository, TransientSidebarViewStore(SidebarGrouping.Project), clock = { CLOCK },
    )
    var observedDraftEdits = 0
        private set
    var overviewReused = false
        private set
    var previewsReused = false
        private set
    var ready = false
        private set

    suspend fun stage(model: ChatViewModel, state: String) = withTimeout(10_000) {
        require(state in states)
        model.uiState.first { it.projects.isNotEmpty() }
        when (state) {
            "projection-selected" -> {
                model.selectProject("synthetic-project")
                model.uiState.first { it.selectedProject?.id == "synthetic-project" && !it.projectLoading }
            }
            "projection-search-match" -> {
                model.setQuery("design")
                model.uiState.first { it.query == "design" }
            }
            "projection-search-miss" -> {
                model.setQuery("no synthetic match")
                model.uiState.first { it.query == "no synthetic match" && it.projects.isEmpty() }
            }
            "projection-draft-reuse" -> {
                val before = model.uiState.value.projects
                val preview = before.single().previewSessions
                overviewReused = true
                previewsReused = true
                repeat(20) { edit ->
                    val text = "Synthetic draft $edit"
                    model.setDraft(text)
                    val emitted = model.uiState.first { it.draft == text }
                    overviewReused = overviewReused && emitted.projects === before
                    previewsReused = previewsReused && emitted.projects.single().previewSessions === preview
                    observedDraftEdits++
                }
                check(overviewReused && previewsReused)
            }
        }
        ready = true
    }

    companion object {
        const val CLOCK = 1_789_654_800_000L
        val states = setOf("projection-overview", "projection-selected", "projection-search-match", "projection-search-miss", "projection-draft-reuse")
        private val sessions = listOf(
            SessionSummary("synthetic-design", "Synthetic design review", "Synthetic capture session", CLOCK - 720_000L),
            SessionSummary("synthetic-release", "Synthetic release checklist", "Synthetic capture session", CLOCK - 1_200_000L),
        )
        private val projects = listOf(ProjectSummary(
            "synthetic-project", "Synthetic project", "/synthetic/project", sessionCount = 2, previewSessions = sessions,
        ))
    }
}
