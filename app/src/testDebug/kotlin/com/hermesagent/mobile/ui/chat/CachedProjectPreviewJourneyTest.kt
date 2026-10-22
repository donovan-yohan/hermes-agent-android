package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.prefs.TransientProfileScopeStore
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.data.profiles.ProfileRepository
import com.hermesagent.mobile.data.profiles.ProfileRosterState
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CachedProjectPreviewJourneyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `cached alpha preview click resumes alpha while revalidation waits over beta raw id`() = previewCollision(export = false)

    @Test
    fun `cached alpha preview export reads alpha while revalidation waits over beta raw id`() = previewCollision(export = true)

    @Test
    fun `cached preview export payload rejects recycled endpoint`() = previewCollision(export = true, switchEndpoint = true)

    private fun previewCollision(export: Boolean, switchEndpoint: Boolean = false) = runTest {
        val frames = mutableListOf<JsonObject>()
        val heldTrees = mutableListOf<JsonObject>()
        val heldResumes = mutableListOf<JsonObject>()
        var holdAlphaTree = false
        var holdResume = false
        lateinit var rpc: CorrelatedGatewayRpc
        rpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                frames += frame
                val method = frame["method"]?.jsonPrimitive?.content ?: return true
                val params = frame["params"]?.jsonObject ?: JsonObject(emptyMap())
                val profile = params["profile"]?.jsonPrimitive?.content ?: "launch"
                if (method == "projects.tree" && profile == "alpha" && holdAlphaTree) {
                    heldTrees += frame
                    return true
                }
                if (method == "session.resume" && holdResume) {
                    heldResumes += frame
                    return true
                }
                val result = when (method) {
                    "session.list" -> """{"sessions":[]}"""
                    "projects.tree" -> """{"projects":[{"id":"p","label":"$profile project","sessionCount":1,"previewSessions":[{"id":"s","title":"$profile preview"}]}]}"""
                    "session.resume" -> """{"session_id":"runtime-$profile","session_key":"${params["session_id"]!!.jsonPrimitive.content}","title":"$profile opened","running":false}"""
                    "session.history" -> """{"messages":[],"count":0}"""
                    else -> "{}"
                }
                rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""")
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = UnconfinedTestDispatcher(testScheduler))
        val cache = SessionCache().apply {
            upsertSession(com.hermesagent.mobile.data.session.SessionSummary("bootstrap", "Initial chat", "", NOW, remoteProfile = "alpha"))
        }
        val exports = mutableListOf<GatewayHttpRequest>()
        var prepared: com.hermesagent.mobile.ui.sessions.PreparedSessionExport? = null
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                exports += request
                val profile = request.query["profile"]
                return GatewayHttpResult.Success(200, """{"session_id":"s","messages":[{"id":1,"role":"user","content":"$profile transcript"}],"pagination":{"limit":100,"offset":0,"order":"oldest"}}""".encodeToByteArray())
            }
        }
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope,
            http = { http }, restContext = kotlin.coroutines.EmptyCoroutineContext) { NOW }
        val profiles = object : ProfileRepository {
            override val roster = MutableStateFlow(ProfileRosterState(
                listOf(HermesProfile("default", isDefault = true), HermesProfile("alpha"), HermesProfile("beta")), loaded = true))
            override suspend fun refreshProfiles() = true
            override fun connectionChanged(state: com.hermesagent.mobile.data.profiles.GatewayProfileConnectionState) = Unit
        }
        val viewModel = ChatViewModel(cache, repository,
            profileScopeStore = TransientProfileScopeStore(ProfileScope(activeProfile = "alpha")),
            profileRepository = profiles, clock = { NOW })
        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection()) {
                ChatScreen(state, ChatActions(
                    onSidebarGroupingChange = viewModel::setSidebarGrouping,
                    onSelectSession = viewModel::selectSession,
                    onSelectProjectSession = viewModel::selectProjectSession,
                    onSelectProfile = viewModel::selectProfile,
                    onExportSession = { id -> viewModel.exportSession(id).also { prepared = it } },
                    onExportProjectSession = { session, endpoint -> viewModel.exportProjectSession(session, endpoint).also { prepared = it } },
                ), onOpenSettings = {})
            }
        }
        runCurrent()
        compose.waitForIdle()
        repository.refreshProjects()
        runCurrent()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithText("Project").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("alpha preview").assertIsDisplayed()

        compose.onNodeWithContentDescription("Switch to beta").performClick()
        runCurrent()
        compose.waitForIdle()
        repository.refreshProjects()
        runCurrent()
        compose.waitForIdle()
        compose.onNodeWithText("beta project").assertIsDisplayed()
        assertEquals("beta", cache.session("s")?.remoteProfile)
        cache.upsertSession(requireNotNull(cache.session("s")).copy(title = "beta live"))
        cache.setTranscript("s", listOf(com.hermesagent.mobile.data.session.AssistantTurn("beta-turn", "Beta private reply", NOW)))
        runCurrent()
        compose.waitForIdle()
        val betaSessions = cache.state.value.sessions
        val betaTranscripts = cache.state.value.transcripts

        holdAlphaTree = true
        compose.onNodeWithContentDescription("Switch to alpha").performClick()
        runCurrent()
        compose.waitForIdle()
        assertTrue("Alpha revalidation must still be held", heldTrees.isNotEmpty())
        assertSame("Restoring the preview must not rewind live rows", betaSessions, cache.state.value.sessions)
        assertSame(betaTranscripts, cache.state.value.transcripts)
        compose.onNodeWithText("alpha preview").assertIsDisplayed()
        if (export) {
            exports.clear()
            compose.onNodeWithContentDescription("Session actions").performClick()
            compose.onNodeWithText("Export").performClick()
            runCurrent()
            compose.waitForIdle()
            assertEquals("The displayed alpha preview must never export beta", listOf("alpha"),
                exports.map { it.query["profile"] })
            val payload = requireNotNull(prepared)
            assertTrue(payload.bytes.decodeToString().contains("alpha transcript"))
            assertFalse(payload.bytes.decodeToString().contains("beta transcript"))
            assertTrue(payload.isCurrent())
            assertSame(betaSessions, cache.state.value.sessions)
            assertSame(betaTranscripts, cache.state.value.transcripts)
            val preview = viewModel.uiState.value.projects.single().previewSessions.single()
            val endpoint = cache.endpointGeneration.value
            if (switchEndpoint) cache.resetForEndpointSwitch() else viewModel.selectProfile("beta")
            runCurrent()
            assertFalse("A changed profile or endpoint must retire the chooser payload", payload.isCurrent())
            val beforeStale = exports.size
            assertTrue("A retained stale preview must not dispatch", runCatching {
                viewModel.exportProjectSession(preview, endpoint)
            }.isFailure)
            assertEquals(beforeStale, exports.size)
            payload.bytes.fill(0)
            rpc.close()
            return@runTest
        }
        val beforeClick = frames.size
        holdResume = true
        compose.onNodeWithTag("Session row s").performClick()
        runCurrent()
        compose.waitForIdle()

        val resumes = frames.drop(beforeClick).filter { it["method"] == JsonPrimitive("session.resume") }
        assertEquals("The displayed alpha preview must never resume beta", listOf(JsonPrimitive("alpha")),
            resumes.map { it.getValue("params").jsonObject["profile"] })
        assertEquals("Do not paint the foreign slot while the intended resume waits", null, viewModel.uiState.value.activeSessionId)
        compose.onNodeWithText("Beta private reply").assertDoesNotExist()
        assertEquals("beta", cache.session("s")?.remoteProfile)
        rpc.receive("""{"jsonrpc":"2.0","id":${heldResumes.single().getValue("id")},"result":{"session_id":"runtime-alpha-s","session_key":"s","title":"alpha opened","running":false}}""")
        runCurrent()
        compose.waitForIdle()
        assertEquals("alpha", cache.session("s")?.remoteProfile)
        assertEquals("s", viewModel.uiState.value.activeSessionId)
        compose.onNodeWithText("Beta private reply").assertDoesNotExist()
        rpc.close()
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
