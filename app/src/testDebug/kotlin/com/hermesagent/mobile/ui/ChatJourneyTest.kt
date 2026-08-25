package com.hermesagent.mobile.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hermesagent.mobile.ui.chat.ComposerBusyKind
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.hermesagent.mobile.data.attachments.OutgoingAttachment
import com.hermesagent.mobile.data.composer.ComposerQueueController
import com.hermesagent.mobile.data.composer.ComposerQueueSubmitter
import com.hermesagent.mobile.data.composer.QueueSubmissionOutcome
import com.hermesagent.mobile.data.composer.TransientComposerQueueStore
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayImageLoader
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.ProjectCreateOutcome
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.chat.ChatScreen
import com.hermesagent.mobile.ui.chat.ChatViewModel
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose semantics over live-repository-shaped state; no demo engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatJourneyTest {
    @get:Rule
    val compose = createComposeRule()

    private val cache = SessionCache()
    private lateinit var repository: JourneyRepository
    private lateinit var viewModel: ChatViewModel
    private var themeName by mutableStateOf(BuiltinThemes.DEFAULT_NAME)

    private fun launch(
        connected: Boolean = true,
        withSessions: Boolean = true,
        withRealQueueDrain: Boolean = false,
        loader: GatewayImageLoader? = null,
    ) {
        if (withSessions) {
            cache.upsertSessions(
                listOf(
                    SessionSummary("live-a", "Remote planning", "Gateway preview", NOW),
                    SessionSummary("live-b", "Second remote session", "Other preview", NOW - 86_400_000),
                ),
            )
            cache.setTranscript(
                "live-a",
                listOf(
                    UserTurn("row-u", "What is live?", NOW - 2),
                    AssistantTurn("row-a", "Live reply from Gateway", NOW - 1),
                ),
            )
            cache.setTranscript("live-b", listOf(AssistantTurn("row-b", "Second live transcript", NOW - 1)))
        }
        repository = JourneyRepository(cache, connected, loader)
        viewModel = if (withRealQueueDrain) {
            val submitter = object : ComposerQueueSubmitter {
                override suspend fun submitQueued(
                    durableSessionId: String,
                    text: String,
                ): QueueSubmissionOutcome = when (repository.submit(durableSessionId, text)) {
                    GatewaySubmitOutcome.Accepted -> QueueSubmissionOutcome.Accepted
                    else -> QueueSubmissionOutcome.Rejected
                }
            }
            ChatViewModel(
                cache,
                repository,
                clock = { NOW },
                composerQueueController = ComposerQueueController(
                    store = TransientComposerQueueStore(),
                    submitter = submitter,
                ),
            )
        } else {
            ChatViewModel(cache, repository, clock = { NOW })
        }

        compose.setContent {
            val state by viewModel.uiState.collectAsState()
            HermesTheme(AppearanceSelection(themeName, HermesThemeMode.Dark)) {
                ChatScreen(
                    state = state,
                    actions = ChatActions(
                        onQueryChange = viewModel::setQuery,
                        onDraftChange = viewModel::setDraft,
                        onRefreshNavigation = viewModel::refreshSessionNavigation,
                        onSidebarGroupingChange = viewModel::setSidebarGrouping,
                        onSelectProject = viewModel::selectProject,
                        onExitProject = viewModel::exitProject,
                        onCreateProject = viewModel::createProject,
                        onSelectSession = viewModel::selectSession,
                        onCreateSession = viewModel::createSession,
                        onSend = viewModel::submit,
                        onStop = viewModel::stop,
                        onRedirect = viewModel::redirectDraftFromUi,
                        onQueue = viewModel::queueDraft,
                    ),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `chat opens on newest backend session with connected state`() {
        launch()
        assertTrue(compose.countWithText("Remote planning") >= 1)
        compose.onNodeWithText("Live reply from Gateway").assertIsDisplayed()
        assertTrue(compose.countWithText("Connected") >= 1)
    }

    @Test
    fun `drawer searches and resumes selected durable session`() {
        launch()
        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithContentDescription("Search sessions").performClick()
        compose.onNodeWithContentDescription("Search sessions").performTextInput("second")
        assertEquals(1, compose.countWithText("Second remote session"))
        assertEquals(0, compose.countWithText("Gateway preview"))

        compose.onNodeWithText("Second remote session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Second live transcript").assertIsDisplayed()
        assertTrue(repository.opened.contains("live-b"))
    }

    @Test
    fun `drawer drills from authoritative projects into their session history`() {
        launch()
        val project = ProjectSummary(
            id = "project-mobile",
            label = "Hermes mobile",
            path = "/work/hermes-mobile",
            sessionCount = 1,
            previewSessions = listOf(cache.session("live-b")!!),
        )
        cache.replaceProjectOverview(listOf(project), activeProjectId = "project-mobile")
        repository.projectSessions[project.id] = listOf(cache.session("live-b")!!)
        val projectOpened = CompletableDeferred<Unit>()
        repository.projectOpenResponse = projectOpened
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithContentDescription("Project grouping").performClick()
        compose.onNodeWithText("PROJECTS").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open project Hermes mobile. 1 session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Opening project…").assertIsDisplayed()

        projectOpened.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("All projects").assertIsDisplayed()
        compose.onNodeWithText("Second remote session").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Second live transcript").assertIsDisplayed()
        assertEquals(listOf("project-mobile"), repository.openedProjects)
    }

    @Test
    fun `project header keeps Desktop action order and creates from a remote folder`() {
        launch()
        cache.replaceProjectOverview(emptyList(), activeProjectId = null)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithContentDescription("Project grouping").performClick()
        val create = compose.onNodeWithContentDescription("New project").fetchSemanticsNode()
        val filters = compose.onNodeWithContentDescription("Filters").fetchSemanticsNode()
        assertTrue("New Project stays before Filters", create.boundsInRoot.center.x < filters.boundsInRoot.center.x)

        compose.onNodeWithContentDescription("New project").performClick()
        compose.onNodeWithContentDescription("Name").performTextInput("Demo")
        compose.onNodeWithContentDescription("Remote folder").performTextInput("/srv/demo")
        compose.onNodeWithText("Create project").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Demo" to "/srv/demo"), repository.createdProjects)
        assertEquals("project-created", viewModel.uiState.value.selectedProject?.id)
    }

    @Test
    fun `sidebar grouping menu switches between updated sessions and projects`() {
        launch()
        val project = ProjectSummary(
            id = "project-mobile",
            label = "Hermes mobile",
            path = "/work/hermes-mobile",
            sessionCount = 1,
            previewSessions = listOf(cache.session("live-b")!!),
        )
        cache.replaceProjectOverview(listOf(project), activeProjectId = project.id)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithText("SESSIONS").assertIsDisplayed()
        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithContentDescription("Project grouping").performClick()
        compose.onNodeWithText("PROJECTS").assertIsDisplayed()

        compose.onNodeWithContentDescription("Filters").performClick()
        compose.onNodeWithContentDescription("Updated grouping").performClick()
        compose.onNodeWithText("SESSIONS").assertIsDisplayed()
        assertEquals(SidebarGrouping.Date, viewModel.uiState.value.sidebarGrouping)
    }

    @Test
    fun `restored project grouping keeps a project surface while capability resolves`() {
        launch()
        viewModel.setSidebarGrouping(SidebarGrouping.Project)
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithText("PROJECTS").assertIsDisplayed()
        compose.onNodeWithText("Loading projects…").assertIsDisplayed()
        assertEquals(0, compose.countWithText("No projects"))
        assertEquals(0, compose.countWithText("Create a project with the + above."))

        cache.markProjectsUnavailable()
        compose.waitForIdle()
        compose.onNodeWithText("PROJECTS").assertIsDisplayed()
        compose.onNodeWithText("Project views aren’t available on this Gateway.").assertIsDisplayed()
        assertEquals(0, compose.countWithText("No projects"))
        assertEquals(0, compose.countWithText("Create a project with the + above."))
    }

    @Test
    fun `multiline editor input stays a draft until explicit send`() {
        launch()

        compose.onNodeWithContentDescription("Message Hermes").performTextInput("line one\nline two")
        compose.waitForIdle()

        assertEquals("line one\nline two", viewModel.uiState.value.draft)
        assertTrue(repository.submitted.isEmpty())
    }

    @Test
    fun `send uses live repository and create opens its returned durable session`() {
        launch()
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("send through Gateway")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.waitForIdle()
        assertEquals(listOf("live-a" to "send through Gateway"), repository.submitted)
        assertEquals("send through Gateway", (cache.transcript("live-a").last() as UserTurn).text)

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.onNodeWithContentDescription("New session").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
        assertEquals("created-live", viewModel.uiState.value.activeSession?.id)
    }

    @Test
    fun `ready image without text enables send and reaches repository`() {
        launch()
        viewModel.attachmentReadDispatcher = Dispatchers.Unconfined
        viewModel.openAttachmentStream = { RED_PNG_4X4.inputStream() }

        viewModel.addAttachmentFromGrant("content://fixture/pic.png", "pic.png", "image/png")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Send message").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("live-a" to ""), repository.submitted)
        assertTrue(repository.submittedAttachments.single().second.single() is OutgoingAttachment.Image)
        assertEquals(listOf("live-a" to false), repository.queuedSubmissions)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.isEmpty())
    }

    @Test
    fun `busy screenshot primary queues the whole payload instead of redirecting text`() {
        launch()
        cache.upsertSession(cache.session("live-a")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.Working))
        viewModel.attachmentReadDispatcher = Dispatchers.Unconfined
        viewModel.openAttachmentStream = { RED_PNG_4X4.inputStream() }
        viewModel.addAttachmentFromGrant("content://fixture/shot.png", "shot.png", "image/png")
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("inspect this")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Queue message").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf("live-a" to "inspect this"), repository.submitted)
        assertEquals(listOf("live-a" to true), repository.queuedSubmissions)
        assertTrue(repository.submittedAttachments.single().second.single() is OutgoingAttachment.Image)
        assertTrue(viewModel.uiState.value.composer.runtime.queueEntries.isEmpty())
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.isEmpty())
    }

    @Test
    fun `disconnected chat shows truthful status and disables send`() {
        launch(connected = false)
        cache.upsertSession(cache.session("live-a")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.Working))
        compose.waitForIdle()
        compose.onNodeWithText("Disconnected").assertIsDisplayed()
        assertEquals(0, compose.countWithText("Streaming · Connected"))
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("not sent")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        assertTrue(repository.submitted.isEmpty())
    }

    @Test
    fun `fresh disconnected chat disables new session and points to Gateway setup`() {
        launch(connected = false, withSessions = false)

        compose.onNodeWithContentDescription("Open sessions").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("New session").assertIsNotEnabled()
        compose.onNodeWithText("Connect to a Gateway to start a session.").assertIsDisplayed()
    }

    @Test
    fun `tool completion keeps concise duration while stopped tools do not look successful`() {
        launch()
        cache.setTranscript(
            "live-a",
            listOf(
                ToolActivity("tool-whole", "Whole", "done", ToolState.Done, 2.0),
                ToolActivity("tool-fraction", "Fraction", "done", ToolState.Done, 1.234),
                ToolActivity("tool-stopped", "Stopped", "partial", ToolState.Stopped, 7.5),
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tool Whole, done").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Fraction, done").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Stopped, stopped").assertIsDisplayed()
        compose.onNodeWithText("2s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1s").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.countWithText("7.5s"))
    }

    @Test
    fun `completed reasoning keeps its elapsed disclosure`() {
        launch()
        cache.setTranscript(
            "live-a",
            listOf(ReasoningActivity("reasoning", "check the build", ToolState.Done, elapsedSeconds = 2.0)),
        )
        compose.waitForIdle()

        compose.onNodeWithText("Thought for 2s").assertIsDisplayed()
    }

    @Test
    fun `attached image refs render as a fetched thumbnail and open full size`() {
        val loader = object : GatewayImageLoader {
            override suspend fun load(path: String): Result<ByteArray> = Result.success(RED_PNG_4X4)
        }
        launch(loader = loader)
        cache.setTranscript(
            "live-a",
            listOf(
                UserTurn("row-img", "look at this\n@image:`/home/d/images/shot one.png`", NOW),
            ),
        )
        compose.waitForIdle()
        // The fetch + decode hop off the test's main-clock dispatchers, so a
        // bounded poll — not waitForIdle — is the honest synchronizer.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithContentDescription("shot one.png").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("shot one.png").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("Full size image").assertExists()
        compose.onNodeWithTag("Close full size image").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("Full size image").assertDoesNotExist()
    }

    @Test
    fun `failed image fetch degrades to a quiet file chip`() {
        val loader = object : GatewayImageLoader {
            override suspend fun load(path: String): Result<ByteArray> = Result.failure(Exception("nope"))
        }
        launch(loader = loader)
        cache.setTranscript(
            "live-a",
            listOf(UserTurn("row-img", "@image:/home/d/images/gone.png", NOW)),
        )
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("gone.png").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("gone.png").assertIsDisplayed()
    }

    @Test
    fun `image refs render as a quiet chip when no loader is connected`() {
        launch(loader = null)
        cache.setTranscript(
            "live-a",
            listOf(UserTurn("row-img", "@image:/home/d/images/offline.png", NOW)),
        )
        compose.waitForIdle()

        // No connection-owned loader: the turn must not vanish — the chip is
        // the fallback even with nothing to fetch.
        compose.onNodeWithText("offline.png").assertIsDisplayed()
    }

    @Test
    fun `rich activity rows expose reasoning terminal payload and patched file diff`() {
        launch()
        cache.setTranscript(
            "live-a",
            listOf(
                ReasoningActivity("reasoning", "check the build", ToolState.Done, elapsedSeconds = 2.0),
                ToolActivity(
                    id = "terminal",
                    label = "terminal",
                    detail = "./gradlew check",
                    state = ToolState.Done,
                    elapsedSeconds = 3.0,
                    toolName = "terminal",
                    argsText = """{"command":"./gradlew check"}""",
                    resultText = """{"output":"BUILD SUCCESSFUL","exit_code":0}""",
                ),
                ToolActivity(
                    id = "terminal-multi",
                    label = "terminal",
                    detail = "./gradlew test",
                    state = ToolState.Done,
                    toolName = "terminal",
                    argsText = """{"command":"./gradlew test\n./gradlew lint"}""",
                ),
                ToolActivity(
                    id = "patch",
                    label = "patch",
                    detail = "A.kt",
                    state = ToolState.Done,
                    elapsedSeconds = 1.0,
                    toolName = "patch",
                    argsText = """{"path":"A.kt"}""",
                    inlineDiff = "--- a/A.kt\n+++ b/A.kt\n-old\n+new",
                ),
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Tool Ran ./gradlew check, done").performScrollTo().performClick()
        compose.onNodeWithText("BUILD SUCCESSFUL").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Ran ./gradlew check, done").performClick()
        compose.onNodeWithContentDescription("Tool Ran ./gradlew test + 1 command, done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Tool Patched file, done").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A.kt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("+1  −1").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.countWithText("done"))
    }

    @Test
    fun `inline diff arriving on completion opens its patch body`() {
        launch()
        val running = ToolActivity(
            id = "live-patch",
            label = "patch",
            detail = "",
            state = ToolState.Running,
            toolName = "patch",
        )
        cache.setTranscript("live-a", listOf(running))
        compose.waitForIdle()

        cache.putEntry(
            "live-a",
            running.copy(
                state = ToolState.Done,
                inlineDiff = "--- a/A.kt\n+++ b/A.kt\n-old\n+new",
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithText("+new").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `another running turn keeps stream ownership while an idle thread sends`() {
        launch()
        cache.upsertSession(cache.session("live-a")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.Working))
        viewModel.selectSession("live-b")
        compose.waitUntil(5_000) {
            viewModel.uiState.value.composer.runtime.busyKind == ComposerBusyKind.Idle
        }
        compose.waitForIdle()

        // Desktop parity: live-a owns its running turn and keeps its stop
        // control; the idle selected session (live-b) sends its own text
        // directly instead of silently filing it into the local queue.
        compose.onNodeWithContentDescription("Message Hermes").performTextInput("independent send")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Send message").assertExists()
        viewModel.performComposerPrimaryAction()
        compose.waitForIdle()
        assertEquals(0, viewModel.uiState.value.composer.runtime.queueEntries.size)
        assertEquals(listOf("live-b" to "independent send"), repository.submitted)

        // Back on the running session its own stop control stays truthful.
        viewModel.selectSession("live-a")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Stop generating").assertIsDisplayed()
    }

    @Test
    fun `queued entry drains on settle even while another session keeps running`() {
        launch(withRealQueueDrain = true)
        cache.upsertSession(cache.session("live-b")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.Working))
        viewModel.selectSession("live-a")
        compose.waitForIdle()

        // NeedsInput parks typed text in the local queue without a submit.
        cache.upsertSession(cache.session("live-a")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.NeedsInput))
        compose.waitForIdle()
        viewModel.setDraft("drain me")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Queue message").performClick()
        compose.waitForIdle()
        assertEquals(1, viewModel.uiState.value.composer.runtime.queueEntries.size)
        assertEquals(0, repository.submitted.size)

        // live-a settles; live-b is still working. The drain must not wait
        // for the unrelated session.
        cache.upsertSession(cache.session("live-a")!!.copy(status = com.hermesagent.mobile.data.session.SessionStatus.Idle))
        compose.waitForIdle()

        // The drained entry must be the settled session's, not just any queue.
        assertEquals(listOf("live-a" to "drain me"), repository.submitted)
        assertEquals(0, viewModel.uiState.value.composer.runtime.queueEntries.size)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w1000dp-h800dp")
    fun `wide layout keeps persistent sessions rail`() {
        launch()
        compose.onNodeWithText("SESSIONS").assertIsDisplayed()
        compose.onNodeWithText("Second remote session").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Open sessions")).fetchSemanticsNodes().size)
    }

    @Test
    fun `every builtin theme renders live transcript`() {
        launch()
        for (preset in BuiltinThemes.ALL) {
            themeName = preset.name
            compose.waitForIdle()
            compose.onNodeWithText("Live reply from Gateway").assertIsDisplayed()
        }
    }

    private class JourneyRepository(
        private val cache: SessionCache,
        connected: Boolean,
        private val loader: GatewayImageLoader? = null,
    ) : GatewaySessionRepository {
        override val imageLoader = MutableStateFlow(loader)

        override val pendingInputs =
            MutableStateFlow<Map<com.hermesagent.mobile.data.gateway.PendingInputKey, com.hermesagent.mobile.data.gateway.PendingInputRequest>>(
                emptyMap(),
            )

        override suspend fun respondToPendingInput(
            key: com.hermesagent.mobile.data.gateway.PendingInputKey,
            action: com.hermesagent.mobile.data.gateway.PendingInputAction,
        ): com.hermesagent.mobile.data.gateway.PendingInputResponse =
            com.hermesagent.mobile.data.gateway.PendingInputResponse.Resolved

        override val connectionState = MutableStateFlow(
            GatewayConnectionState(
                if (connected) GatewayConnectionStatus.Connected else GatewayConnectionStatus.Disconnected,
            ),
        )
        val opened = mutableListOf<String>()
        val openedProjects = mutableListOf<String>()
        val createdProjects = mutableListOf<Pair<String, String>>()
        val projectSessions = mutableMapOf<String, List<SessionSummary>>()
        var projectOpenResponse: CompletableDeferred<Unit>? = null
        val submitted = mutableListOf<Pair<String, String>>()
        val submittedAttachments = mutableListOf<Pair<String, List<OutgoingAttachment>>>()
        val queuedSubmissions = mutableListOf<Pair<String, Boolean>>()

        override suspend fun refreshSessions() = Unit
        override suspend fun openProject(projectId: String) {
            openedProjects += projectId
            projectOpenResponse?.await()
            cache.replaceProjectDetails(
                requireNotNull(cache.state.value.projects.projects[projectId]),
                projectSessions[projectId].orEmpty(),
            )
        }
        override suspend fun createProject(name: String, folderPath: String): ProjectCreateOutcome {
            createdProjects += name to folderPath
            val project = ProjectSummary("project-created", name, folderPath, sessionCount = 0)
            cache.replaceProjectOverview(listOf(project), activeProjectId = project.id)
            projectSessions[project.id] = emptyList()
            return ProjectCreateOutcome(project.id, catalogRefreshed = true)
        }
        override suspend fun openSession(durableId: String): String {
            opened += durableId
            return durableId
        }

        override suspend fun createSession(workspacePath: String?): String {
            cache.upsertSession(SessionSummary("created-live", "New session", "", NOW + 1))
            return "created-live"
        }

        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome =
            submit(durableId, text, queued = false, attachments = emptyList())

        override suspend fun submit(
            durableId: String,
            text: String,
            queued: Boolean,
            attachments: List<OutgoingAttachment>,
        ): GatewaySubmitOutcome {
            submitted += durableId to text
            queuedSubmissions += durableId to queued
            if (attachments.isNotEmpty()) submittedAttachments += durableId to attachments
            cache.appendEntry(durableId, UserTurn("submitted", text, NOW))
            return GatewaySubmitOutcome.Accepted
        }

        override suspend fun interrupt(durableId: String) = Unit
    }

    private companion object {
        const val NOW = 1_755_600_000_000L
    }
}

private fun ComposeContentTestRule.countWithText(text: String, substring: Boolean = false): Int =
    onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

/** Decodes a hex string into bytes — deterministic fixture images without hand-built literals. */
private fun hexBytes(hex: String): ByteArray =
    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** A real 4x4 red PNG (generated, not hand-made): deterministic decoder input. */
private val RED_PNG_4X4 = hexBytes(
    "89504e470d0a1a0a0000000d494844520000000400000004080200000026930929" +
        "0000001049444154789c63f8cfc000470cc47100ae930ff1d05f239e0000000049454e44ae426082",
)
