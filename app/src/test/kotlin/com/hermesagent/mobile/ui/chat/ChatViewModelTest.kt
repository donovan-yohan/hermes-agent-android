package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.ProjectCreateOutcome
import com.hermesagent.mobile.data.gateway.SessionRehome
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.prefs.SidebarViewStore
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var cache: SessionCache
    private lateinit var repository: FakeRepository
    private lateinit var sidebarStore: FakeSidebarViewStore
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cache = SessionCache().apply {
            upsertSessions(listOf(summary("session-a", 2_000), summary("session-b", 1_000)))
        }
        repository = FakeRepository(cache)
        sidebarStore = FakeSidebarViewStore()
        viewModel = ChatViewModel(cache, repository, sidebarStore, clock = { CLOCK })
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `backend cache starts without demo seed and selects newest live session`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        assertEquals(listOf("session-a", "session-b"), cache.state.value.sessions.keys.toList())
        assertEquals("session-a", viewModel.uiState.value.activeSession?.id)
        assertTrue(cache.state.value.sessions.keys.none { it.contains("demo", ignoreCase = true) })
    }

    @Test
    fun `selecting and submitting call the live repository with durable id`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.selectSession("session-b")
        viewModel.setDraft("  send remotely  ")
        runCurrent()
        viewModel.submit()
        runCurrent()

        assertEquals(listOf("session-a", "session-b"), repository.opened)
        assertEquals(listOf("session-b" to "send remotely"), repository.submitted)
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals(SessionStatus.Working, cache.session("session-b")?.status)
    }

    @Test
    fun `authoritatively rejected submit restores the current draft with concise action`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.failSubmit = true
        viewModel.setDraft("keep me")
        runCurrent()
        viewModel.submit()
        runCurrent()

        assertEquals("keep me", viewModel.uiState.value.draft)
        assertEquals("The message was not sent. Reconnect to the Gateway and try again.", viewModel.uiState.value.notice)
    }

    @Test
    fun `ambiguous submit keeps the draft empty and tells the user to check and wait`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.submitOutcome = GatewaySubmitOutcome.Ambiguous
        viewModel.setDraft("send once")
        runCurrent()

        viewModel.submit()
        runCurrent()

        assertEquals(listOf("session-a" to "send once"), repository.submitted)
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals(
            "This message may have been sent. Check this session and wait for Hermes before trying again.",
            viewModel.uiState.value.notice,
        )
        assertFalse(viewModel.uiState.value.notice.orEmpty().contains("not sent"))
    }

    @Test
    fun `completion after a session switch marks only the source unread`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(cache.session("session-a")!!.copy(status = SessionStatus.Working))
        runCurrent()
        viewModel.selectSession("session-b")
        runCurrent()
        cache.upsertSession(cache.session("session-a")!!.copy(status = SessionStatus.Idle))
        runCurrent()

        assertEquals("session-b", viewModel.uiState.value.activeSession?.id)
        assertEquals(SessionStatus.Unread, cache.session("session-a")?.status)
        assertEquals(SessionStatus.Idle, cache.session("session-b")?.status)
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun `active gateway progress reaches the existing composer status surface`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(
            cache.session("session-a")!!.copy(
                status = SessionStatus.Working,
                progress = SessionProgress("compacting", "Summarizing context…"),
            ),
        )
        runCurrent()

        assertEquals("Summarizing context…", viewModel.uiState.value.liveStatusText)
    }

    @Test
    fun `resumed needs-input and background sessions block global send without streaming active chat`() =
        runTest(dispatcher) {
            collectState()
            runCurrent()
            viewModel.setDraft("wait for the resumed turn")
            runCurrent()
            assertTrue(viewModel.uiState.value.canSend)

            for (busyStatus in listOf(SessionStatus.NeedsInput, SessionStatus.Background)) {
                cache.upsertSession(cache.session("session-b")!!.copy(status = busyStatus))
                runCurrent()

                assertEquals(1, viewModel.uiState.value.runningCount)
                assertFalse(viewModel.uiState.value.canSend)
                assertFalse(viewModel.uiState.value.isStreaming)
                viewModel.submit()
                runCurrent()
                assertTrue(repository.submitted.isEmpty())

                cache.upsertSession(cache.session("session-b")!!.copy(status = SessionStatus.Idle))
                runCurrent()
            }
        }

    @Test
    fun `create selects backend-returned durable session`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.createSession()
        runCurrent()

        assertEquals(1, repository.created)
        assertEquals("created-1", viewModel.uiState.value.activeSession?.id)
        assertTrue(viewModel.uiState.value.transcriptIsEmpty)
        assertTrue(viewModel.uiState.value.canCreateSession)
    }

    @Test
    fun `project drill in filters authoritative membership without rerouting the active session`() = runTest(dispatcher) {
        cache.replaceProjectOverview(
            rows = listOf(
                ProjectSummary(
                    id = "project-a",
                    label = "Project A",
                    path = "/work/a",
                    sessionCount = 1,
                    previewSessions = listOf(summary("session-a", 2_000)),
                ),
                ProjectSummary(
                    id = "project-b",
                    label = "Project B",
                    path = "/work/b",
                    sessionCount = 1,
                    previewSessions = listOf(summary("session-b", 1_000)),
                ),
            ),
            activeProjectId = "project-a",
        )
        repository.projectSessions["project-b"] = listOf(summary("session-b", 1_000))
        collectState()
        runCurrent()

        viewModel.selectProject("project-b")
        runCurrent()

        assertEquals(listOf("project-b"), repository.openedProjects)
        assertEquals("session-a", viewModel.uiState.value.activeSession?.id)
        assertEquals(
            listOf("session-b"),
            viewModel.uiState.value.sessionRows.filterIsInstance<com.hermesagent.mobile.data.session.SessionListRow.Row>()
                .map { it.session.id },
        )

        viewModel.createSession()
        runCurrent()
        assertEquals("/work/b", repository.createdWorkspace)
    }

    @Test
    fun `grouping choice persists and updated view exits project scope`() = runTest(dispatcher) {
        val project = ProjectSummary("project-a", "Project A", "/work/a", sessionCount = 0)
        cache.replaceProjectOverview(listOf(project), activeProjectId = project.id)
        repository.projectSessions[project.id] = emptyList()
        collectState()
        runCurrent()

        viewModel.setSidebarGrouping(SidebarGrouping.Project)
        viewModel.selectProject(project.id)
        runCurrent()
        assertEquals(project.id, viewModel.uiState.value.selectedProject?.id)

        viewModel.setSidebarGrouping(SidebarGrouping.Date)
        runCurrent()

        assertEquals(SidebarGrouping.Date, viewModel.uiState.value.sidebarGrouping)
        assertEquals(null, viewModel.uiState.value.selectedProject)
        assertEquals(listOf(SidebarGrouping.Project, SidebarGrouping.Date), sidebarStore.saved)
    }

    @Test
    fun `saved project grouping is restored into navigation state`() = runTest(dispatcher) {
        val restoredStore = FakeSidebarViewStore(SidebarGrouping.Project)
        val subject = ChatViewModel(cache, repository, restoredStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        assertEquals(SidebarGrouping.Project, subject.uiState.value.sidebarGrouping)
    }

    @Test
    fun `delayed restore cannot overwrite a newer grouping choice`() = runTest(dispatcher) {
        val delayedStore = DelayedSidebarViewStore()
        val subject = ChatViewModel(cache, repository, delayedStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setSidebarGrouping(SidebarGrouping.Project)
        runCurrent()
        delayedStore.emitRestored(SidebarGrouping.Date)
        runCurrent()

        assertEquals(SidebarGrouping.Project, subject.uiState.value.sidebarGrouping)
    }

    @Test
    fun `authoritative refresh exits a project that no longer exists`() = runTest(dispatcher) {
        val project = ProjectSummary(
            id = "project-a",
            label = "Project A",
            path = "/work/a",
            sessionCount = 1,
            previewSessions = listOf(summary("session-a", 2_000)),
        )
        cache.replaceProjectOverview(listOf(project), activeProjectId = project.id)
        repository.projectSessions[project.id] = listOf(summary("session-a", 2_000))
        collectState()
        runCurrent()
        viewModel.selectProject(project.id)
        runCurrent()

        cache.replaceProjectOverview(emptyList(), activeProjectId = null)
        runCurrent()

        assertEquals(null, viewModel.uiState.value.selectedProject)
        assertEquals("That project is no longer available.", viewModel.uiState.value.notice)
    }

    @Test
    fun `creating a project selects the refreshed backend project`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.createProject("Demo", "/srv/demo")
        runCurrent()

        assertEquals(listOf("Demo" to "/srv/demo"), repository.createdProjects)
        assertEquals("project-created", viewModel.uiState.value.selectedProject?.id)
        assertEquals(listOf("project-created"), repository.openedProjects)
    }

    @Test
    fun `created project with a failed catalog refresh does not invite a duplicate retry`() = runTest(dispatcher) {
        repository.catalogRefreshedAfterCreate = false
        collectState()
        runCurrent()

        viewModel.createProject("Demo", "/srv/demo")
        runCurrent()

        assertEquals(null, viewModel.uiState.value.selectedProject)
        assertEquals(
            "The project was created, but Projects could not be refreshed. Reopen Sessions to refresh.",
            viewModel.uiState.value.notice,
        )
    }

    @Test
    fun `project create completion does not override newer navigation`() = runTest(dispatcher) {
        val first = ProjectSummary("project-a", "A", "/work/a", sessionCount = 0)
        val second = ProjectSummary("project-b", "B", "/work/b", sessionCount = 0)
        cache.replaceProjectOverview(listOf(first, second), activeProjectId = first.id)
        repository.projectSessions[first.id] = emptyList()
        repository.projectSessions[second.id] = emptyList()
        repository.createProjectGate = CompletableDeferred()
        collectState()
        runCurrent()

        viewModel.createProject("Demo", "/srv/demo")
        runCurrent()
        viewModel.selectProject(second.id)
        runCurrent()
        repository.createProjectGate?.complete(Unit)
        runCurrent()

        assertEquals(second.id, viewModel.uiState.value.selectedProject?.id)
        assertEquals(listOf(second.id), repository.openedProjects)
    }

    @Test
    fun `disconnected chat disables send and explains create next action`() = runTest(dispatcher) {
        collectState()
        repository.connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        viewModel.setDraft("cannot send")
        runCurrent()
        assertFalse(viewModel.uiState.value.canSend)
        assertFalse(viewModel.uiState.value.canCreateSession)

        viewModel.createSession()
        runCurrent()
        assertEquals(0, repository.created)
        assertEquals("Connect to a Gateway before starting a session.", viewModel.uiState.value.notice)
    }

    @Test
    fun `canonical session rehome preserves the active transcript and draft`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val activeIds = mutableListOf<String?>()
        backgroundScope.launch { viewModel.uiState.collect { activeIds += it.activeSession?.id } }
        cache.setTranscript("session-a", listOf(UserTurn("u1", "kept", CLOCK)))
        viewModel.setDraft("draft in progress")
        runCurrent()
        activeIds.clear()

        repository.rehome("session-a", "session-tip")
        runCurrent()

        assertEquals("session-tip", viewModel.uiState.value.activeSession?.id)
        assertEquals("kept", (viewModel.uiState.value.transcript.single() as UserTurn).text)
        assertEquals("draft in progress", viewModel.uiState.value.draft)
        assertFalse("the active session must not render blank during an atomic rehome", activeIds.contains(null))
    }

    @Test
    fun `stop interrupts the active durable session`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.stop()
        runCurrent()
        assertEquals(listOf("session-a"), repository.interrupted)
    }

    private fun kotlinx.coroutines.test.TestScope.collectState() {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    private class FakeRepository(private val cache: SessionCache) : GatewaySessionRepository {
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val connectionState = connection
        private val rehomeEvents = MutableSharedFlow<SessionRehome>(extraBufferCapacity = 1)
        override val sessionRehomes = rehomeEvents
        val opened = mutableListOf<String>()
        val submitted = mutableListOf<Pair<String, String>>()
        val interrupted = mutableListOf<String>()
        val openedProjects = mutableListOf<String>()
        val createdProjects = mutableListOf<Pair<String, String>>()
        val projectSessions = mutableMapOf<String, List<SessionSummary>>()
        var createProjectGate: CompletableDeferred<Unit>? = null
        var catalogRefreshedAfterCreate = true
        var created = 0
        var createdWorkspace: String? = null
        var failSubmit = false
        var submitOutcome: GatewaySubmitOutcome = GatewaySubmitOutcome.Accepted

        fun rehome(fromId: String, toId: String) {
            val row = requireNotNull(cache.session(fromId)).copy(id = toId)
            cache.rehomeSession(fromId, row, cache.transcript(fromId))
            check(rehomeEvents.tryEmit(SessionRehome(fromId, toId)))
        }

        override suspend fun refreshSessions() = Unit

        override suspend fun openProject(projectId: String) {
            openedProjects += projectId
            val project = requireNotNull(cache.state.value.projects.projects[projectId])
            cache.replaceProjectDetails(project, projectSessions[projectId].orEmpty())
        }

        override suspend fun createProject(name: String, folderPath: String): ProjectCreateOutcome {
            createdProjects += name to folderPath
            createProjectGate?.await()
            val project = ProjectSummary(
                id = "project-created",
                label = name,
                path = folderPath,
                sessionCount = 0,
            )
            if (catalogRefreshedAfterCreate) {
                val projects = cache.state.value.projects.projects.values.filterNot(ProjectSummary::isHome) + project
                cache.replaceProjectOverview(projects, activeProjectId = project.id)
            }
            projectSessions[project.id] = emptyList()
            return ProjectCreateOutcome(project.id, catalogRefreshedAfterCreate)
        }

        override suspend fun openSession(durableId: String): String {
            opened += durableId
            return durableId
        }

        override suspend fun createSession(workspacePath: String?): String {
            created++
            createdWorkspace = workspacePath
            val id = "created-$created"
            cache.upsertSession(summary(id, CLOCK).copy(title = "New session"))
            return id
        }

        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome {
            if (failSubmit) error("fixture failure")
            submitted += durableId to text
            cache.session(durableId)?.let { cache.upsertSession(it.copy(status = SessionStatus.Working)) }
            return submitOutcome
        }

        override suspend fun interrupt(durableId: String) {
            interrupted += durableId
        }
    }

    private class FakeSidebarViewStore(initial: SidebarGrouping = SidebarGrouping.Date) : SidebarViewStore {
        private val state = MutableStateFlow(initial)
        override val sidebarGrouping = state
        val saved = mutableListOf<SidebarGrouping>()

        override suspend fun saveSidebarGrouping(grouping: SidebarGrouping) {
            saved += grouping
            state.value = grouping
        }
    }

    private class DelayedSidebarViewStore : SidebarViewStore {
        private val restored = MutableSharedFlow<SidebarGrouping>()
        override val sidebarGrouping = restored

        suspend fun emitRestored(grouping: SidebarGrouping) {
            restored.emit(grouping)
        }

        override suspend fun saveSidebarGrouping(grouping: SidebarGrouping) = Unit
    }

    private companion object {
        const val CLOCK = 1_800_000_000_000L
        fun summary(id: String, at: Long) = SessionSummary(id, "Session $id", "", at)
    }
}
