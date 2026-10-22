package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
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
    fun `cached alpha preview click resumes alpha while revalidation waits over beta raw id`() = previewCollision()

    @Test
    fun `cached alpha preview move keeps owner while beta owns raw id`() = previewCollision(move = true)

    @Test
    fun `cached alpha move picker cannot dispatch after endpoint changes`() = previewCollision(move = true, staleEndpoint = true)

    @Test
    fun `cached alpha preview export reads alpha while revalidation waits over beta raw id`() = previewCollision(export = true)

    @Test
    fun `cached preview export payload rejects recycled endpoint`() = previewCollision(export = true, switchEndpoint = true)

    @Test
    fun `cached alpha preview branch preserves alpha owner over beta raw id`() = previewCollision(export = false, branch = true)

    private fun previewCollision(export: Boolean = false, switchEndpoint: Boolean = false,
        branch: Boolean = false, move: Boolean = false, staleEndpoint: Boolean = false) = runTest {
        var loadedTargets = emptyList<com.hermesagent.mobile.data.session.ProjectSummary>()
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
                if (method == "projects.tree" && profile == "alpha" && holdAlphaTree && params["preview_limit"] != JsonPrimitive(0)) {
                    heldTrees += frame
                    return true
                }
                if (method == "session.resume" && holdResume) {
                    heldResumes += frame
                    return true
                }
                val result = when (method) {
                    "session.list" -> """{"sessions":[]}"""
                    "projects.tree" -> """{"projects":[{"id":"p","label":"$profile project","path":"/work/$profile","sessionCount":1,"previewSessions":[{"id":"s","title":"$profile preview"}]}]}"""
                    "session.workspace.move" -> """{"cwd":"/work/$profile","branch":null}"""
                    "projects.project_sessions" -> """{"project":{"id":"p","label":"$profile project","path":"/work/$profile","previewSessions":[{"id":"s","title":"$profile preview"}]}}"""
                    "session.resume" -> """{"session_id":"runtime-$profile","session_key":"${params["session_id"]!!.jsonPrimitive.content}","title":"$profile opened","running":false}"""
                    "session.history" -> """{"messages":[],"count":0}"""
                    "session.branch_whole" -> """{"session_id":"runtime-child","stored_session_id":"child","title":"Child"}"""
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
                    onBranchSession = viewModel::branchSession,
                    onBranchProjectSession = viewModel::branchProjectSession,
                    onSelectProfile = viewModel::selectProfile,
                    sessionProjectActions = com.hermesagent.mobile.ui.sessions.SessionProjectActions(
                        { id, row -> viewModel.sessionProjectTargets(id, row).also { loadedTargets = it } },
                        viewModel::moveSessionToProject,
                    ),
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
            assertTrue("The displayed title, not beta's shared row, belongs in the export",
                payload.bytes.decodeToString().contains("alpha preview"))
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
        if (move) {
            compose.onNodeWithContentDescription("Session actions").performClick()
            compose.onNodeWithText("Move to project").performClick()
            runCurrent()
            compose.waitForIdle()
            val targets = frames.drop(beforeClick).filter { it["method"] == JsonPrimitive("projects.tree") }
            assertEquals("Picker must use the displayed owner, not the shared raw-id slot", listOf(JsonPrimitive("alpha")),
                targets.map { it.getValue("params").jsonObject["profile"] })
            assertSame(betaSessions, cache.state.value.sessions)
            assertSame(betaTranscripts, cache.state.value.transcripts)
            if (staleEndpoint) {
                // Keep the actual picker-returned identity across retirement.
                // Calling the VM directly avoids relying on dialog unmount as
                // the safety fence (its coroutine scope is already cancelled).
                cache.resetForEndpointSwitch()
                val beforeMove = frames.size
                assertTrue(runCatching { viewModel.moveSessionToProject("s", loadedTargets.single()) }.isFailure)
                runCurrent()
                compose.waitForIdle()
                assertTrue(frames.drop(beforeMove).none { it["method"] == JsonPrimitive("session.resume") ||
                    it["method"] == JsonPrimitive("session.workspace.move") })
                rpc.close()
                return@runTest
            }
            // Let only the move's authoritative readback finish; the older held
            // alpha overview remains outstanding throughout the command.
            holdAlphaTree = false
            compose.onNode(hasText("alpha project") and hasAnyAncestor(isDialog())).performClick()
            runCurrent()
            compose.waitForIdle()
            val writes = frames.drop(beforeClick).filter { it["method"] == JsonPrimitive("session.workspace.move") }
            assertEquals(1, writes.size)
            assertEquals(JsonPrimitive("alpha"), writes.single().getValue("params").jsonObject["profile"])
            assertEquals(JsonPrimitive("/work/alpha"), writes.single().getValue("params").jsonObject["cwd"])
            assertTrue(frames.drop(beforeClick).filter { it["method"] == JsonPrimitive("session.resume") }
                .all { it.getValue("params").jsonObject["profile"] == JsonPrimitive("alpha") })
            rpc.close()
            return@runTest
        }
        holdResume = true
        if (branch) {
            compose.onAllNodesWithContentDescription("Session actions")[0].performClick()
            compose.onNodeWithText("Branch").performClick()
        } else compose.onNodeWithTag("Session row s").performClick()
        runCurrent()
        compose.waitForIdle()

        val resumes = frames.drop(beforeClick).filter { it["method"] == JsonPrimitive("session.resume") }
        if (branch) compose.onNodeWithTag("Session row s").assertIsNotDisplayed()
        assertEquals("The displayed alpha preview must never resume beta", listOf(JsonPrimitive("alpha")),
            resumes.map { it.getValue("params").jsonObject["profile"] })
        assertEquals("Do not paint the foreign slot while the intended resume waits", null, viewModel.uiState.value.activeSessionId)
        compose.onNodeWithText("Beta private reply").assertDoesNotExist()
        assertEquals("beta", cache.session("s")?.remoteProfile)
        rpc.receive("""{"jsonrpc":"2.0","id":${heldResumes.single().getValue("id")},"result":{"session_id":"runtime-alpha-s","session_key":"s","title":"alpha opened","running":false}}""")
        runCurrent()
        compose.waitForIdle()
        assertEquals("alpha", cache.session("s")?.remoteProfile)
        assertEquals(if (branch) "child" else "s", viewModel.uiState.value.activeSessionId)
        if (branch) {
            assertEquals(listOf(JsonPrimitive("runtime-alpha-s")), frames.filter {
                it["method"] == JsonPrimitive("session.branch_whole")
            }.map { it.getValue("params").jsonObject["session_id"] })
        }
        compose.onNodeWithText("Beta private reply").assertDoesNotExist()
        rpc.close()
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
