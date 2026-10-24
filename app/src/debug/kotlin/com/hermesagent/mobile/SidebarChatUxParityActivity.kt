package com.hermesagent.mobile

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.prefs.TransientSidebarViewStore
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first


/** Real chat shell and navigation over isolated synthetic, in-memory authorities. */
class SidebarChatUxParityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val fixture = SidebarChatUxFixture()
        SidebarChatUxRuntimeProvider.active = fixture
        val theme = when (intent.getStringExtra("visual_parity_theme")) {
            "light" -> HermesThemeMode.Light
            "dark" -> HermesThemeMode.Dark
            else -> error("A capture theme is required")
        }
        val captureState = intent.getStringExtra("visual_parity_state") ?: "switch"
        require(captureState in setOf("switch", "loading", "attention"))
        setContent {
            val state by fixture.model.uiState.collectAsState()
            LaunchedEffect(fixture) {
                fixture.model.uiState.first { it.activeSessionId == SidebarChatUxFixture.PREVIOUS }
                if (captureState == "loading") fixture.model.selectProjectSession(fixture.target)
            }
            HermesTheme(AppearanceSelection("nous", theme)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(
                        onSelectSession = fixture.model::selectSession,
                        onSelectProjectSession = fixture.model::selectProjectSession,
                        onDraftChange = fixture.model::setDraft,
                        onSidebarGroupingChange = fixture.model::setSidebarGrouping,
                        onSelectProject = fixture.model::selectProject,
                        onExitProject = fixture.model::exitProject,
                    ),
                    onOpenSettings = {},
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }

    }

    override fun onDestroy() {
        SidebarChatUxRuntimeProvider.active = null
        super.onDestroy()
    }
}

internal class SidebarChatUxFixture {
    val target = SessionSummary("synthetic-target", "Review implementation", "A synthetic conversation", NOW - 60_000L,
        remoteProfile = "default", status = SessionStatus.NeedsInput)
    private val previous = SessionSummary(PREVIOUS, "Earlier conversation", "Previous synthetic reply", NOW,
        remoteProfile = "default")
    val cache = SessionCache().apply {
        activateProjectScope("default")
        upsertSessions(listOf(previous, target))
        setTranscript(PREVIOUS, listOf(AssistantTurn("synthetic-old-reply", "This is the earlier conversation.", NOW)))
        replaceProjectOverview(listOf(project()), null)
    }
    val openGate = CompletableDeferred<Unit>()
    val repository = object : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val pendingInputs = MutableStateFlow<Map<com.hermesagent.mobile.data.gateway.PendingInputKey,
            com.hermesagent.mobile.data.gateway.PendingInputRequest>>(emptyMap())
        override fun setProfileRouting(routing: com.hermesagent.mobile.data.gateway.ProfileRouting) {
            cache.activateProjectScope(routing.activeProfile)
        }
        override suspend fun refreshSessions() = Unit
        override suspend fun refreshProjects() { cache.replaceProjectOverview(listOf(project()), null) }
        override suspend fun openProject(projectId: String) { cache.replaceProjectDetails(project(), listOf(previous, target)) }
        override suspend fun openSession(durableId: String): String {
            if (durableId == target.id) {
                openGate.await()
                cache.setTranscript(durableId, listOf(AssistantTurn("synthetic-new-reply", "The selected conversation is ready.", NOW)))
            }
            return durableId
        }
        override suspend fun openSession(durableId: String, profile: String): String {
            check(profile == "default")
            return openSession(durableId)
        }
        override suspend fun createSession(workspacePath: String?): String = error("Capture is read-only")
        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome = error("Capture is read-only")
        override suspend fun interrupt(durableId: String) = error("Capture is read-only")
    }
    val model = ChatViewModel(cache, repository, TransientSidebarViewStore(SidebarGrouping.Project), clock = { NOW })
    private fun project() = ProjectSummary("synthetic-project", "Synthetic project", "/synthetic/project", sessionCount = 2,
        previewSessions = listOf(previous, target))
    companion object {
        const val PREVIOUS = "synthetic-previous"
        const val NOW = 1_789_654_800_000L
    }
}

/** A DUMP-only door to release the synthetic response, never a production connection. */
class SidebarChatUxRuntimeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val fixture = checkNotNull(active)
        return Bundle().apply {
            when (method) {
                "release" -> fixture.openGate.complete(Unit)
                "fail" -> fixture.openGate.completeExceptionally(IllegalStateException("Synthetic open failed"))
                "snapshot" -> putString("state", fixture.model.uiState.value.sessionOpen.toString())
                else -> error("Unsupported capture command")
            }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    companion object { internal var active: SidebarChatUxFixture? = null }
}
