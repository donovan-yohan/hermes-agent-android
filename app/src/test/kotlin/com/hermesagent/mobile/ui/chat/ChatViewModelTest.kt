package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.attachments.OutgoingAttachment
import com.hermesagent.mobile.data.attachments.AttachmentStage
import com.hermesagent.mobile.data.attachments.AttachmentPolicy
import com.hermesagent.mobile.data.draft.SessionDraftStore
import com.hermesagent.mobile.data.draft.TransientSessionDraftStore
import com.hermesagent.mobile.data.composer.CompletionItem
import com.hermesagent.mobile.data.composer.CompletionResult
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ControlMutationResult
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.ModelCatalog
import com.hermesagent.mobile.data.composer.ModelControlsSnapshot
import com.hermesagent.mobile.data.composer.NewSessionComposerOverrides
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.composer.SessionComposerControls
import com.hermesagent.mobile.data.gateway.ARCHIVED_UNSUPPORTED
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.GatewayInterruptOutcome
import com.hermesagent.mobile.data.gateway.GatewayRedirectOutcome
import com.hermesagent.mobile.data.gateway.ProjectCreateOutcome
import com.hermesagent.mobile.data.gateway.SessionRehome
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.prefs.SidebarViewStore
import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.data.prefs.ComposerControlsStore
import com.hermesagent.mobile.data.prefs.NewDraftComposerPreference
import com.hermesagent.mobile.data.prefs.TransientComposerControlsStore
import com.hermesagent.mobile.data.session.ComposerGatewayQueuedPrompt
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.gateway.GatewayRpcException
import com.hermesagent.mobile.data.composer.QueuedPromptDelivery
import com.hermesagent.mobile.data.composer.ComposerQueueController
import com.hermesagent.mobile.data.composer.ComposerQueueSubmitter
import com.hermesagent.mobile.data.composer.QueueSubmissionOutcome
import com.hermesagent.mobile.data.composer.TransientComposerQueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `coding context uses only active session worktree truth and opens authenticated review`() = runTest(dispatcher) {
        cache.upsertSession(
            requireNotNull(cache.session("session-a")).copy(
                gitBranch = "feat/composer",
                worktreePath = "/srv/worktrees/composer",
            ),
        )
        val provider = RecordingCodingContextProvider(
            context = CodingContext.Available(
                branch = "feat/composer",
                worktreePath = "/srv/worktrees/composer",
                additions = 12,
                deletions = 3,
            ),
            review = CodingReviewResult.Available(
                listOf(CodingReviewFile("app/Main.kt", 12, 3, "M", staged = false)),
            ),
        )
        val subject = ChatViewModel(
            cache = cache,
            repository = repository,
            sidebarViewStore = sidebarStore,
            clock = { CLOCK },
            codingContextProvider = provider,
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.refreshCodingContext()
        runCurrent()
        assertEquals(listOf("/srv/worktrees/composer"), provider.contextPaths)
        assertEquals(listOf("/srv/worktrees/composer" to "feat/composer"), provider.pullRequestPaths)
        assertEquals(12, (subject.uiState.value.composer.codingContext as CodingContext.Available).additions)

        subject.openCodingReview()
        runCurrent()
        assertEquals(listOf("/srv/worktrees/composer"), provider.reviewPaths)
        val review = subject.uiState.value.composer.codingReview as CodingReviewUiState.Ready
        assertEquals("app/Main.kt", review.files.single().path)
    }

    @Test
    fun `verified git status paints before slower pull request lookup`() = runTest(dispatcher) {
        cache.upsertSession(
            requireNotNull(cache.session("session-a")).copy(
                gitBranch = "feat/composer",
                worktreePath = "/srv/worktrees/composer",
            ),
        )
        val pullRequestGate = CompletableDeferred<CodingPullRequest?>()
        val provider = object : CodingContextProvider {
            override suspend fun contextFor(worktreePath: String) = CodingContext.Available(
                branch = "feat/composer",
                worktreePath = worktreePath,
                additions = 12,
                deletions = 3,
            )

            override suspend fun pullRequestFor(worktreePath: String, branch: String): CodingPullRequest? =
                pullRequestGate.await()

            override suspend fun reviewFor(worktreePath: String) = CodingReviewResult.Unavailable
        }
        val subject = ChatViewModel(
            cache = cache,
            repository = repository,
            sidebarViewStore = sidebarStore,
            clock = { CLOCK },
            codingContextProvider = provider,
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.refreshCodingContext()
        runCurrent()
        assertNull((subject.uiState.value.composer.codingContext as CodingContext.Available).pullRequest)

        pullRequestGate.complete(
            CodingPullRequest(23, "https://github.com/acme/repo/pull/23", "open", draft = false),
        )
        runCurrent()
        assertEquals(23, (subject.uiState.value.composer.codingContext as CodingContext.Available).pullRequest?.number)
    }

    @Test
    fun `coding context makes no status request without server worktree truth`() = runTest(dispatcher) {
        val provider = RecordingCodingContextProvider()
        val subject = ChatViewModel(
            cache = cache,
            repository = repository,
            sidebarViewStore = sidebarStore,
            clock = { CLOCK },
            codingContextProvider = provider,
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.refreshCodingContext()
        runCurrent()

        assertTrue(provider.contextPaths.isEmpty())
        assertTrue(subject.uiState.value.composer.codingContext is CodingContext.Unavailable)
    }

    @Test
    fun `fresh manual model remains local and becomes the create override`() = runTest(dispatcher) {
        val emptyCache = SessionCache()
        val scope = ComposerControlsScope("test-gateway", "default")
        val composerStore = TransientComposerControlsStore(scope)
        val freshRepository = FakeRepository(emptyCache)
        val subject = ChatViewModel(
            emptyCache,
            freshRepository,
            composerControlsStore = composerStore,
            clock = { CLOCK },
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.selectModel(ComposerModelSelection("model/manual", "provider"))
        runCurrent()

        assertEquals("model/manual", subject.uiState.value.composer.controls.selection?.model)
        assertTrue(subject.uiState.value.composer.isManualNewDraft)
        assertEquals(
            "model/manual",
            composerStore.preference(scope).first()?.selection?.model,
        )
        subject.createSession()
        runCurrent()
        assertEquals("model/manual", freshRepository.createdOverrides?.selection?.model)
    }

    @Test
    fun `delayed preference snapshot cannot replace a newer fresh draft choice`() = runTest(dispatcher) {
        val emptyCache = SessionCache()
        val composerStore = DelayedComposerControlsStore()
        val subject = ChatViewModel(
            emptyCache,
            FakeRepository(emptyCache),
            composerControlsStore = composerStore,
            clock = { CLOCK },
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.selectModel(ComposerModelSelection("model/manual", "provider"))
        runCurrent()
        composerStore.snapshots.emit(
            NewDraftComposerPreference(
                selection = ComposerModelSelection("model/stale", "provider", ComposerModelSelection.Source.Manual),
            ),
        )
        runCurrent()

        assertEquals("model/manual", subject.uiState.value.composer.controls.selection?.model)
        assertEquals("model/manual", composerStore.saved?.selection?.model)
    }

    @Test
    fun `create snapshots gateway seeded fresh defaults before suspension`() = runTest(dispatcher) {
        val emptyCache = SessionCache()
        val freshRepository = FakeRepository(emptyCache)
        val subject = ChatViewModel(
            emptyCache,
            freshRepository,
            composerControlsStore = TransientComposerControlsStore(),
            clock = { CLOCK },
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()
        assertEquals("model/default", subject.uiState.value.composer.controls.selection?.model)
        freshRepository.createSessionGate = CompletableDeferred()

        subject.createSession()
        runCurrent()
        subject.selectModel(ComposerModelSelection("model/later", "other-provider"))
        subject.selectReasoning(ReasoningEffort.High)
        subject.selectFast(FastMode.Fast)
        runCurrent()
        freshRepository.createSessionGate?.complete(Unit)
        runCurrent()

        assertEquals(
            NewSessionComposerOverrides(
                selection = ComposerModelSelection("model/default", "provider"),
                reasoning = ReasoningEffort.Medium,
                fast = FastMode.Normal,
            ),
            freshRepository.createdOverrides,
        )
        assertEquals(
            "a pre-build model.options read must not replace the accepted create snapshot",
            "model/default",
            subject.uiState.value.composer.controls.selection?.model,
        )
    }

    @Test
    fun `deferred live model keeps the requested next turn selection`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.modelMutation = ControlMutationResult.Deferred

        viewModel.selectModel(ComposerModelSelection("model/next", "provider"))
        runCurrent()

        assertEquals("model/next", viewModel.uiState.value.composer.controls.selection?.model)
        assertEquals(ComposerMutationUiState.Deferred, viewModel.uiState.value.composer.mutation)
    }

    @Test
    fun `applied live model stays optimistic until session info confirms effective state`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.selectModel(ComposerModelSelection("model/next", "provider"))
        runCurrent()

        assertEquals("model/next", viewModel.uiState.value.composer.controls.selection?.model)
        assertEquals(ComposerMutationUiState.Idle, viewModel.uiState.value.composer.mutation)
    }

    @Test
    fun `session info during catalog hydration is retained without cancelling the catalog`() = runTest(dispatcher) {
        val gatedRepository = FakeRepository(cache).apply {
            modelOptionsGate = CompletableDeferred()
        }
        val subject = ChatViewModel(cache, gatedRepository, sidebarStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        val authoritative = ComposerModelSelection("model/session-info", "authoritative-provider")
        gatedRepository.emitComposerControls(
            SessionComposerControls(
                durableId = "session-a",
                selection = authoritative,
                hasSelection = true,
                reasoning = ReasoningEffort.High,
                hasReasoning = true,
            ),
        )
        runCurrent()
        assertEquals(authoritative, subject.uiState.value.composer.controls.selection)
        assertTrue(subject.uiState.value.composer.catalog is ComposerCatalogUiState.Loading)

        gatedRepository.modelOptionsGate?.complete(Unit)
        runCurrent()
        assertTrue(subject.uiState.value.composer.catalog is ComposerCatalogUiState.Ready)
        assertEquals(authoritative, subject.uiState.value.composer.controls.selection)
        assertEquals(ReasoningEffort.High, subject.uiState.value.composer.controls.reasoning)
        assertEquals(FastMode.Normal, subject.uiState.value.composer.controls.fast)
    }

    @Test
    fun `turn settle does not clobber a deferred model before session info confirms it`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        repository.modelMutation = ControlMutationResult.Deferred
        runCurrent()

        viewModel.selectModel(ComposerModelSelection("model/next", "provider"))
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Idle))
        runCurrent()

        assertEquals("model/next", viewModel.uiState.value.composer.controls.selection?.model)
        assertEquals(ComposerMutationUiState.Deferred, viewModel.uiState.value.composer.mutation)
    }

    @Test
    fun `legacy session info cannot clobber a deferred pick before the next accepted submit`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.modelMutation = ControlMutationResult.Deferred
        viewModel.selectModel(ComposerModelSelection("model/next", "provider"))
        runCurrent()

        repository.emitComposerControls(
            SessionComposerControls(
                durableId = "session-a",
                selection = ComposerModelSelection("model/still-running", "provider"),
                hasSelection = true,
                reasoning = ReasoningEffort.High,
                hasReasoning = true,
            ),
        )
        runCurrent()

        assertEquals("model/next", viewModel.uiState.value.composer.controls.selection?.model)
        assertEquals(ReasoningEffort.High, viewModel.uiState.value.composer.controls.reasoning)
        assertEquals(ComposerMutationUiState.Deferred, viewModel.uiState.value.composer.mutation)
        viewModel.selectReasoning(ReasoningEffort.Low)
        viewModel.selectFast(FastMode.Fast)
        runCurrent()
        assertTrue(repository.reasoningSelections.isEmpty())
        assertTrue(repository.fastSelections.isEmpty())

        viewModel.setDraft("next turn")
        viewModel.submit()
        runCurrent()
        assertEquals(ComposerMutationUiState.Idle, viewModel.uiState.value.composer.mutation)
    }

    @Test
    fun `terminal session info before deferred ack remains authoritative`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val ack = CompletableDeferred<ControlMutationResult>()
        repository.modelMutationGate = ack
        val requested = ComposerModelSelection("model/next", "next-provider")

        viewModel.selectModel(requested)
        runCurrent()
        assertEquals(ComposerMutationUiState.Saving, viewModel.uiState.value.composer.mutation)
        repository.emitComposerControls(
            SessionComposerControls(
                durableId = "session-a",
                selection = requested,
                hasSelection = true,
                reasoning = ReasoningEffort.High,
                hasReasoning = true,
                fast = FastMode.Fast,
                hasFast = true,
            ),
        )
        runCurrent()
        ack.complete(ControlMutationResult.Deferred)
        runCurrent()

        assertEquals(requested, viewModel.uiState.value.composer.controls.selection)
        assertEquals(ReasoningEffort.High, viewModel.uiState.value.composer.controls.reasoning)
        assertEquals(FastMode.Fast, viewModel.uiState.value.composer.controls.fast)
        assertEquals(ComposerMutationUiState.Idle, viewModel.uiState.value.composer.mutation)
    }

    @Test
    fun `external session info fences a stale rejected mutation reply`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val ack = CompletableDeferred<ControlMutationResult>()
        repository.modelMutationGate = ack
        viewModel.selectModel(ComposerModelSelection("model/requested", "provider"))
        runCurrent()

        val external = ComposerModelSelection("model/external", "external-provider")
        repository.emitComposerControls(
            SessionComposerControls("session-a", selection = external, hasSelection = true),
        )
        runCurrent()
        ack.complete(ControlMutationResult.Rejected("stale rejection"))
        runCurrent()

        assertEquals(external, viewModel.uiState.value.composer.controls.selection)
        assertEquals(ComposerMutationUiState.Idle, viewModel.uiState.value.composer.mutation)
    }

    @Test
    fun `a saving live mutation serializes rapid control picks`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val ack = CompletableDeferred<ControlMutationResult>()
        repository.modelMutationGate = ack

        viewModel.selectModel(ComposerModelSelection("model/first", "provider"))
        runCurrent()
        viewModel.selectModel(ComposerModelSelection("model/second", "provider"))
        runCurrent()

        assertEquals(listOf("model/first"), repository.modelSelections.map { it.model })
        assertEquals("model/first", viewModel.uiState.value.composer.controls.selection?.model)
        ack.complete(ControlMutationResult.Deferred)
        runCurrent()
        assertEquals(ComposerMutationUiState.Deferred, viewModel.uiState.value.composer.mutation)
    }

    @Test
    fun `live control rejection restores the complete previous controls snapshot`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.controls = ModelControlsSnapshot(
            selection = ComposerModelSelection("model/current", "provider"),
            reasoning = ReasoningEffort.Medium,
            fast = FastMode.Normal,
        )
        viewModel.selectSession("session-b")
        runCurrent()
        repository.modelMutation = ControlMutationResult.Rejected("Choose another model.")

        viewModel.selectModel(ComposerModelSelection("model/rejected", "provider"))
        runCurrent()

        assertEquals("model/current", viewModel.uiState.value.composer.controls.selection?.model)
        assertEquals(ReasoningEffort.Medium, viewModel.uiState.value.composer.controls.reasoning)
        assertEquals(FastMode.Normal, viewModel.uiState.value.composer.controls.fast)
        assertEquals(
            ComposerMutationUiState.Error("Choose another model."),
            viewModel.uiState.value.composer.mutation,
        )
    }

    @Test
    fun `stale completion cannot replace the newer editor generation`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val first = CompletableDeferred<Unit>()
        repository.firstSlashGate = first

        viewModel.onEditorSelectionChange("/old", 4, 4)
        testScheduler.advanceTimeBy(120)
        runCurrent()
        viewModel.onEditorSelectionChange("/new", 4, 4)
        testScheduler.advanceTimeBy(120)
        runCurrent()

        assertEquals("new", viewModel.uiState.value.composer.completion.query)
        assertEquals("new", viewModel.uiState.value.composer.completion.items.single().text)
        first.complete(Unit)
        runCurrent()
        assertEquals("new", viewModel.uiState.value.composer.completion.items.single().text)
    }

    @Test
    fun `slash command replacement keeps one leading slash`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.slashReplaceFrom = 1

        viewModel.onEditorSelectionChange("/he", 3, 3)
        testScheduler.advanceTimeBy(120)
        runCurrent()

        val completion = viewModel.uiState.value.composer.completion
        assertEquals(0, completion.replaceStart)
        assertEquals(3, completion.replaceEnd)
    }

    @Test
    fun `slash argument replacement honors the Gateway argument offset`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.slashReplaceFrom = 13

        viewModel.onEditorSelectionChange("/personality al", 15, 15)
        testScheduler.advanceTimeBy(120)
        runCurrent()

        val completion = viewModel.uiState.value.composer.completion
        assertEquals(13, completion.replaceStart)
        assertEquals(15, completion.replaceEnd)
    }

    @Test
    fun `each session reference names the profile that owns that session`() = runTest(dispatcher) {
        // The cache holds rows from several profiles at once once the sidebar
        // can browse them all, so a row that names no profile is a
        // launch-profile row — never a reason to borrow a sibling's.
        cache.upsertSession(requireNotNull(cache.session("session-b")).copy(remoteProfile = "worker"))
        collectState()
        runCurrent()

        viewModel.onEditorSelectionChange("@", 1, 1)
        testScheduler.advanceTimeBy(120)
        runCurrent()

        val references = viewModel.uiState.value.composer.completion.items
            .filter { it.kind == "session" && it.text.startsWith("@session:`") }
        assertTrue(references.isNotEmpty())
        assertTrue(references.any { it.text.startsWith("@session:`worker/session-b") })
        assertTrue(references.any { it.text.startsWith("@session:`default/session-a") })
        assertFalse(references.any { it.text.startsWith("@session:`worker/session-a") })
    }

    @Test
    fun `project switch clears and fences an in-flight path completion`() = runTest(dispatcher) {
        val first = ProjectSummary("project-a", "A", "/work/a", sessionCount = 0)
        val second = ProjectSummary("project-b", "B", "/work/b", sessionCount = 0)
        cache.replaceProjectOverview(listOf(first, second), activeProjectId = first.id)
        repository.projectSessions[first.id] = emptyList()
        repository.projectSessions[second.id] = emptyList()
        repository.pathGate = CompletableDeferred()
        collectState()
        runCurrent()

        viewModel.selectProject(first.id)
        runCurrent()
        viewModel.onEditorSelectionChange("@src", 4, 4)
        testScheduler.advanceTimeBy(120)
        runCurrent()
        assertTrue(viewModel.uiState.value.composer.completion.loading)

        viewModel.selectProject(second.id)
        runCurrent()
        assertEquals(null, viewModel.uiState.value.composer.completion.trigger)
        repository.pathGate?.complete(Unit)
        runCurrent()
        assertEquals(null, viewModel.uiState.value.composer.completion.trigger)
    }

    @Test
    fun `live path completion lets the Gateway resolve the runtime session cwd`() = runTest(dispatcher) {
        val browsedProject = ProjectSummary("project-b", "B", "/work/b", sessionCount = 0)
        cache.replaceProjectOverview(listOf(browsedProject), activeProjectId = browsedProject.id)
        repository.projectSessions[browsedProject.id] = emptyList()
        collectState()
        runCurrent()

        viewModel.selectProject(browsedProject.id)
        runCurrent()
        viewModel.onEditorSelectionChange("@src", 4, 4)
        testScheduler.advanceTimeBy(120)
        runCurrent()

        assertEquals("session-a", repository.lastPathDurableId)
        assertEquals("", repository.lastPathCwd)
    }

    @Test
    fun `fresh project draft path completion uses the selected remote project cwd`() = runTest(dispatcher) {
        val emptyCache = SessionCache()
        val project = ProjectSummary("project-b", "B", "/work/b", sessionCount = 0)
        emptyCache.replaceProjectOverview(listOf(project), activeProjectId = project.id)
        val freshRepository = FakeRepository(emptyCache).apply { projectSessions[project.id] = emptyList() }
        val subject = ChatViewModel(emptyCache, freshRepository, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.selectProject(project.id)
        runCurrent()
        subject.onEditorSelectionChange("@src", 4, 4)
        testScheduler.advanceTimeBy(120)
        runCurrent()

        assertEquals(null, freshRepository.lastPathDurableId)
        assertEquals("/work/b", freshRepository.lastPathCwd)
    }

    @Test
    fun `per-session drafts flush before navigation and restore independently`() = runTest(dispatcher) {
        val draftStore = TransientSessionDraftStore()
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("draft A")
        testScheduler.advanceTimeBy(400)
        runCurrent()
        subject.selectSession("session-b")
        runCurrent()
        subject.setDraft("draft B")
        testScheduler.advanceTimeBy(400)
        runCurrent()
        subject.selectSession("session-a")
        runCurrent()

        assertEquals("draft A", subject.uiState.value.draft)
        assertEquals("draft B", draftStore.drafts.first()["session-b"])
    }

    @Test
    fun `late persistent snapshot cannot replace newer local drafts`() = runTest(dispatcher) {
        val draftStore = DelayedDraftStore()
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("draft A")
        subject.selectSession("session-b")
        subject.setDraft("draft B")
        draftStore.emit(linkedMapOf("session-a" to "stale A", "session-b" to "stale B"))
        runCurrent()

        assertEquals("draft B", subject.uiState.value.draft)
        subject.selectSession("session-a")
        runCurrent()
        assertEquals("draft A", subject.uiState.value.draft)
    }

    @Test
    fun `definite rejection never replaces a newer edit in the source session`() = runTest(dispatcher) {
        val draftStore = TransientSessionDraftStore()
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        repository.submitGate = CompletableDeferred()
        repository.failSubmit = true
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("submitted")
        subject.submit()
        runCurrent()
        subject.setDraft("newer edit")
        testScheduler.advanceTimeBy(400)
        runCurrent()
        repository.submitGate?.complete(Unit)
        runCurrent()

        assertEquals("newer edit", subject.uiState.value.draft)
        assertEquals("newer edit", draftStore.drafts.first()["session-a"])
    }

    @Test
    fun `definite rejection after navigation restores only the source draft`() = runTest(dispatcher) {
        val draftStore = TransientSessionDraftStore()
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        repository.submitGate = CompletableDeferred()
        repository.failSubmit = true
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("restore in A")
        subject.submit()
        runCurrent()
        subject.selectSession("session-b")
        runCurrent()
        repository.submitGate?.complete(Unit)
        runCurrent()

        assertEquals("session-b", subject.uiState.value.activeSession?.id)
        assertEquals("", subject.uiState.value.draft)
        assertEquals("restore in A", draftStore.drafts.first()["session-a"])
    }

    @Test
    fun `canonical destination draft wins without deleting the obsolete source`() = runTest(dispatcher) {
        val draftStore = TransientSessionDraftStore()
        draftStore.replace("session-a", "source draft")
        draftStore.replace("session-tip", "newer destination")
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        repository.rehome("session-a", "session-tip")
        runCurrent()

        assertEquals("session-tip", subject.uiState.value.activeSession?.id)
        assertEquals("newer destination", subject.uiState.value.draft)
        assertEquals("source draft", draftStore.drafts.first()["session-a"])
    }

    @Test
    fun `canonical rehome retargets accumulated session control authority`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val selection = ComposerModelSelection("model/before-rehome", "provider")
        repository.emitComposerControls(
            SessionComposerControls("session-a", selection = selection, hasSelection = true),
        )
        runCurrent()

        repository.rehome("session-a", "session-tip")
        runCurrent()
        repository.emitComposerControls(
            SessionComposerControls(
                "session-tip",
                reasoning = ReasoningEffort.High,
                hasReasoning = true,
            ),
        )
        runCurrent()

        assertEquals("session-tip", viewModel.uiState.value.activeSession?.id)
        assertEquals(selection, viewModel.uiState.value.composer.controls.selection)
        assertEquals(ReasoningEffort.High, viewModel.uiState.value.composer.controls.reasoning)
    }

    @Test
    fun `canonical rehome persists a local draft before its debounce fires`() = runTest(dispatcher) {
        val draftStore = TransientSessionDraftStore()
        draftStore.replace("session-a", "persisted earlier")
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("pending local draft")
        repository.rehome("session-a", "session-tip")
        runCurrent()

        assertEquals("pending local draft", subject.uiState.value.draft)
        assertEquals("pending local draft", draftStore.drafts.first()["session-tip"])
        assertTrue("obsolete key must not be resurrected", "session-a" !in draftStore.drafts.first())
    }

    @Test
    fun `canonical rehome does not resurrect a locally cleared draft before debounce`() = runTest(dispatcher) {
        val draftStore = TransientSessionDraftStore()
        draftStore.replace("session-a", "persisted earlier")
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("")
        repository.rehome("session-a", "session-tip")
        runCurrent()

        assertEquals("", subject.uiState.value.draft)
        assertTrue("stale source must be removed", "session-a" !in draftStore.drafts.first())
        assertTrue("blank destination must not be stored", "session-tip" !in draftStore.drafts.first())
    }

    @Test
    fun `draft storage failure keeps local editing and canonical rehome alive`() = runTest(dispatcher) {
        val subject = ChatViewModel(cache, repository, sidebarStore, FailingDraftStore(), clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("local only")
        testScheduler.advanceTimeBy(400)
        runCurrent()
        repository.rehome("session-a", "session-tip")
        runCurrent()

        assertEquals("session-tip", subject.uiState.value.activeSession?.id)
        assertEquals("local only", subject.uiState.value.draft)
    }

    @Test
    fun `edit during canonical migration becomes the canonical winner`() = runTest(dispatcher) {
        val draftStore = GatedMigrationDraftStore()
        val subject = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        subject.setDraft("before rehome")
        repository.rehome("session-a", "session-tip")
        runCurrent()
        assertTrue(draftStore.migrationStarted.isCompleted)
        subject.setDraft("typed during rehome")
        draftStore.releaseMigration.complete(Unit)
        runCurrent()

        assertEquals("session-tip", subject.uiState.value.activeSession?.id)
        assertEquals("typed during rehome", subject.uiState.value.draft)
        assertTrue(draftStore.writes.contains("session-tip" to "typed during rehome"))
        assertTrue(draftStore.writes.contains("session-a" to ""))
    }

    @Test
    fun `a recreated view model restores the active durable draft`() = runTest(dispatcher) {
        val draftStore = TransientSessionDraftStore()
        draftStore.replace("session-a", "restored")
        val recreated = ChatViewModel(cache, repository, sidebarStore, draftStore, clock = { CLOCK })
        backgroundScope.launch { recreated.uiState.collect { } }
        runCurrent()

        assertEquals("session-a", recreated.uiState.value.activeSession?.id)
        assertEquals("restored", recreated.uiState.value.draft)
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
    fun `active gateway progress remains available to the transcript projection`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(
            cache.session("session-a")!!.copy(
                status = SessionStatus.Working,
                progress = SessionProgress("compacting", "Summarizing context…"),
            ),
        )
        runCurrent()

        assertEquals("Summarizing context…", viewModel.uiState.value.activeSession?.progress?.text)
    }

    @Test
    fun `needs-input and background turns elsewhere keep an idle selected session sendable`() =
        runTest(dispatcher) {
            collectState()
            runCurrent()
            viewModel.setDraft("wait for the resumed turn")
            runCurrent()
            assertTrue(viewModel.uiState.value.canSend)

            for (busyStatus in listOf(SessionStatus.NeedsInput, SessionStatus.Background)) {
                cache.upsertSession(cache.session("session-a")!!.copy(status = SessionStatus.Idle))
                cache.upsertSession(cache.session("session-b")!!.copy(status = busyStatus))
                viewModel.setDraft("wait for the resumed turn")
                runCurrent()

                // Desktop parity: per-target-session busy gates. Another
                // session's parked turn shows in runningCount but must not
                // convert this idle thread's send into a local queue entry.
                assertEquals(1, viewModel.uiState.value.runningCount)
                assertTrue(viewModel.uiState.value.canSend)
                assertFalse(viewModel.uiState.value.isStreaming)
                val before = repository.submitted.size
                viewModel.submit()
                runCurrent()
                assertEquals(before + 1, repository.submitted.size)
            }
            cache.upsertSession(cache.session("session-a")!!.copy(status = SessionStatus.Idle))
            runCurrent()
        }

    @Test
    fun `a working other session does not block sending into an idle selected session`() =
        runTest(dispatcher) {
            collectState()
            runCurrent()
            cache.upsertSession(cache.session("session-b")!!.copy(status = SessionStatus.Working))
            viewModel.setDraft("independent thread")
            runCurrent()

            assertEquals(1, viewModel.uiState.value.runningCount)
            assertEquals(ComposerBusyKind.Idle, viewModel.uiState.value.composer.runtime.busyKind)
            assertTrue(viewModel.uiState.value.canSend)
            val before = repository.submitted.size
            viewModel.submit()
            runCurrent()
            assertEquals(before + 1, repository.submitted.size)
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
            viewModel.uiState.value.sessionRows.filterIsInstance<SessionListRow.Row>()
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
    fun `disconnected chat refuses branching and does not call branch`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        cache.setTranscript(
            "session-a",
            listOf(UserTurn("u1", "hello", 1_000), AssistantTurn("a1", "reply", 1_100)),
        )
        runCurrent()

        viewModel.branchFromReply("a1")
        runCurrent()

        assertEquals("Nothing to branch. Start or resume a chat before branching.", viewModel.uiState.value.notice)
        assertEquals(emptyList<Pair<String, Int?>>(), repository.branchCalls)
    }

    @Test
    fun `working chat refuses branching and does not call branch`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        runCurrent()
        cache.setTranscript(
            "session-a",
            listOf(UserTurn("u1", "hello", 1_000), AssistantTurn("a1", "reply", 1_100)),
        )
        runCurrent()

        viewModel.branchFromReply("a1")
        runCurrent()

        assertEquals("Session busy. Stop the current turn before branching this chat.", viewModel.uiState.value.notice)
        assertEquals(emptyList<Pair<String, Int?>>(), repository.branchCalls)
    }

    @Test
    fun `unread chat allows branching and invokes branch`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Unread))
        runCurrent()
        cache.setTranscript(
            "session-a",
            listOf(UserTurn("u1", "hello", 1_000), AssistantTurn("a1", "reply", 1_100)),
        )
        repository.historyResult = listOf(
            UserTurn("h-u1", "hello", 2_000),
            AssistantTurn("h-a1", "reply", 2_100),
        )
        runCurrent()

        viewModel.branchFromReply("a1")
        runCurrent()

        assertEquals(listOf("session-a" to null), repository.branchCalls)
    }

    @Test
    fun `branching from reply at end of transcript starts a whole chat branch`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.setTranscript(
            "session-a",
            listOf(UserTurn("u1", "hello", 1_000), AssistantTurn("a1", "reply", 1_100)),
        )
        repository.historyResult = listOf(
            UserTurn("h-u1", "hello", 2_000),
            AssistantTurn("h-a1", "reply", 2_100),
        )
        runCurrent()

        viewModel.branchFromReply("a1")
        runCurrent()

        assertEquals(listOf("session-a" to null), repository.branchCalls)
        assertEquals("new-durable", viewModel.uiState.value.activeSessionId)
        assertEquals("new-durable", viewModel.uiState.value.activeSession?.id)
    }

    @Test
    fun `branching from reply in middle of transcript starts a partial chat branch`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.setTranscript(
            "session-a",
            listOf(
                UserTurn("u1", "hello", 1_000),
                AssistantTurn("a1", "reply", 1_100),
                UserTurn("u2", "more", 1_200),
            ),
        )
        repository.historyResult = listOf(
            UserTurn("h-u1", "hello", 2_000),
            AssistantTurn("h-a1", "reply", 2_100),
            UserTurn("h-u2", "more", 2_200),
        )
        runCurrent()

        viewModel.branchFromReply("a1")
        runCurrent()

        assertEquals(listOf("session-a" to 2), repository.branchCalls)
        assertEquals("new-durable", viewModel.uiState.value.activeSessionId)
        assertEquals("new-durable", viewModel.uiState.value.activeSession?.id)
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

    @Test
    fun `settle and reconnect schedule only one automatic queue submit`() = runTest(dispatcher) {
        val submitted = mutableListOf<Pair<String, String>>()
        val releaseFirstSubmit = CompletableDeferred<QueueSubmissionOutcome>()
        val controller = ComposerQueueController(
            store = TransientComposerQueueStore(),
            submitter = object : ComposerQueueSubmitter {
                override suspend fun submitQueued(durableSessionId: String, text: String): QueueSubmissionOutcome {
                    submitted += durableSessionId to text
                    return releaseFirstSubmit.await()
                }
            },
        )
        val subject = ChatViewModel(
            cache,
            repository,
            sidebarStore,
            clock = { CLOCK },
            composerQueueController = controller,
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()
        controller.enqueue("session-a", "first")
        controller.enqueue("session-a", "second")
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Idle))
        runCurrent()
        assertEquals(listOf("session-a" to "first"), submitted)

        repository.connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        repository.connection.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        assertEquals(listOf("session-a" to "first"), submitted)
        releaseFirstSubmit.complete(QueueSubmissionOutcome.Accepted)
        runCurrent()
        assertEquals(listOf("session-a" to "first"), submitted)
    }

    @Test
    fun `rejected redirect keeps one durable local queue entry and clears the delivered draft`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        repository.redirectOutcome = GatewayRedirectOutcome.Rejected
        viewModel.setDraft("Use the smaller scope")
        runCurrent()

        viewModel.redirectDraftFromUi()
        runCurrent()

        assertEquals(listOf("session-a" to "Use the smaller scope"), repository.redirects)
        assertEquals("", viewModel.uiState.value.draft)
        assertEquals(1, viewModel.uiState.value.composer.runtime.queueEntries.size)
        assertEquals(QueuedPromptDelivery.Ready, viewModel.uiState.value.composer.runtime.queueEntries.single().delivery)
    }

    @Test
    fun `busy primary queues attachment bytes with text instead of redirecting text alone`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        viewModel.attachmentReadDispatcher = dispatcher
        viewModel.openAttachmentStream = { "screenshot bytes".toByteArray().inputStream() }
        viewModel.addAttachmentFromGrant("content://fixture/shot", "shot.bin", null)
        viewModel.setDraft("inspect this")
        runCurrent()

        viewModel.performComposerPrimaryAction()
        runCurrent()

        assertTrue(repository.redirects.isEmpty())
        assertEquals(listOf("session-a" to true), repository.queuedSubmissions)
        assertEquals("session-a" to "inspect this", repository.submitted.single())
        assertTrue(repository.submittedAttachments.single().second.single() is OutgoingAttachment.GenericFile)
        assertEquals("", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.isEmpty())
    }

    @Test
    fun `busy attachment has one in-flight owner across repeated Queue taps`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        viewModel.attachmentReadDispatcher = dispatcher
        viewModel.openAttachmentStream = { "screenshot bytes".toByteArray().inputStream() }
        viewModel.addAttachmentFromGrant("content://fixture/shot", "shot.bin", null)
        viewModel.setDraft("inspect once")
        repository.submitGate = CompletableDeferred()
        runCurrent()

        viewModel.performComposerPrimaryAction()
        runCurrent()
        assertEquals(1, repository.submitAttempts)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.single().stage is AttachmentStage.Staging)

        viewModel.performComposerPrimaryAction()
        runCurrent()
        assertEquals(1, repository.submitAttempts)

        repository.submitGate?.complete(Unit)
        runCurrent()
        assertEquals(listOf("session-a" to true), repository.queuedSubmissions)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.isEmpty())
    }

    @Test
    fun `accepted submit clears only its claimed chips and keeps later additions`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        viewModel.attachmentReadDispatcher = dispatcher
        viewModel.openAttachmentStream = { "first bytes".toByteArray().inputStream() }
        viewModel.addAttachmentFromGrant("content://fixture/first", "first.bin", null)
        viewModel.setDraft("send first")
        repository.submitGate = CompletableDeferred()
        runCurrent()

        viewModel.performComposerPrimaryAction()
        runCurrent()

        viewModel.openAttachmentStream = { "second bytes".toByteArray().inputStream() }
        viewModel.addAttachmentFromGrant("content://fixture/second", "second.bin", null)
        runCurrent()
        assertEquals(2, viewModel.uiState.value.composer.runtime.attachments.size)

        repository.submitGate?.complete(Unit)
        runCurrent()

        val remaining = viewModel.uiState.value.composer.runtime.attachments
        assertEquals(1, remaining.size)
        assertEquals("second.bin", remaining.single().displayName)
        assertEquals(1, repository.submittedAttachments.size)
    }

    @Test
    fun `definite attachment rejection restores the draft and ready chip for retry`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.attachmentReadDispatcher = dispatcher
        viewModel.openAttachmentStream = { "screenshot bytes".toByteArray().inputStream() }
        viewModel.addAttachmentFromGrant("content://fixture/shot", "shot.bin", null)
        viewModel.setDraft("inspect this")
        repository.failSubmit = true
        runCurrent()

        viewModel.submit()
        runCurrent()

        assertEquals("inspect this", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.single().stage is AttachmentStage.Ready)
        assertEquals(
            "The message was not sent. Reconnect to the Gateway and try again.",
            viewModel.uiState.value.notice,
        )

        repository.failSubmit = false
        viewModel.submit()
        runCurrent()

        assertEquals(2, repository.submitAttempts)
        assertEquals("", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.isEmpty())
    }

    @Test
    fun `accepted text keeps the warning for a refused attachment chip`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.attachmentReadDispatcher = dispatcher
        viewModel.openAttachmentStream = { null }
        viewModel.addAttachmentFromGrant("content://fixture/unreadable", "unreadable.bin", null)
        runCurrent()
        val refused = viewModel.uiState.value.composer.runtime.attachments.single().stage as AttachmentStage.Refused
        viewModel.setDraft("send the healthy text")

        viewModel.submit()
        runCurrent()

        assertEquals(listOf("session-a" to "send the healthy text"), repository.submitted)
        assertEquals(refused.safeMessage, viewModel.uiState.value.notice)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.single().stage is AttachmentStage.Refused)
    }

    @Test
    fun `ambiguous busy attachment restores the caption for review without clobbering a newer draft`() =
        runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        viewModel.attachmentReadDispatcher = dispatcher
        viewModel.openAttachmentStream = { "screenshot bytes".toByteArray().inputStream() }
        viewModel.addAttachmentFromGrant("content://fixture/shot", "shot.bin", null)
        viewModel.setDraft("inspect this")
        repository.submitOutcome = GatewaySubmitOutcome.Ambiguous
        repository.submitGate = CompletableDeferred()
        runCurrent()

        viewModel.performComposerPrimaryAction()
        runCurrent()

        assertTrue(viewModel.uiState.value.composer.runtime.attachments.single().stage is AttachmentStage.Staging)

        // The user starts a new thought while the ambiguous result is pending.
        viewModel.setDraft("newer thought")
        repository.submitGate?.complete(Unit)
        runCurrent()

        assertTrue(repository.redirects.isEmpty())
        assertEquals(1, repository.submitAttempts)
        assertTrue(viewModel.uiState.value.composer.runtime.queueEntries.isEmpty())
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.single().stage is AttachmentStage.ReviewRequired)
        assertEquals("newer thought", viewModel.uiState.value.draft)
        val reviewChip = viewModel.uiState.value.composer.runtime.attachments.single().stage as AttachmentStage.ReviewRequired
        assertEquals("inspect this", reviewChip.submittedText)

        // The review-required chip blocks any automatic re-send of the
        // possibly accepted payload.
        viewModel.performComposerPrimaryAction()
        runCurrent()

        assertEquals(1, repository.submitAttempts)
    }

    @Test
    fun `ambiguous redirect is visible but cannot auto retry`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        repository.redirectOutcome = GatewayRedirectOutcome.Ambiguous
        viewModel.setDraft("Keep this correction")
        runCurrent()

        viewModel.redirectDraftFromUi()
        runCurrent()

        assertEquals(QueuedPromptDelivery.Ambiguous, viewModel.uiState.value.composer.runtime.queueEntries.single().delivery)
        assertTrue(viewModel.uiState.value.notice!!.contains("may have reached Hermes"))
    }

    @Test
    fun `stop parks queue before guarded interrupt while needs input never interrupts`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        viewModel.setDraft("after this turn")
        viewModel.queueDraft()
        runCurrent()

        viewModel.stop()
        runCurrent()

        assertEquals(listOf("session-a"), repository.interrupted)
        assertTrue(viewModel.uiState.value.composer.runtime.queueParked)

        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.NeedsInput))
        viewModel.stop()
        runCurrent()

        assertEquals("NeedsInput must not send a second interrupt", listOf("session-a"), repository.interrupted)
    }

    @Test
    fun `stop reports discarded Gateway queue after interrupt clears its projection`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val current = requireNotNull(cache.session("session-a"))
        cache.upsertSession(
            current.copy(
                status = SessionStatus.Working,
                composerStatus = ComposerStatusState(
                    gatewayQueuedPrompts = listOf(ComposerGatewayQueuedPrompt("queued-1", "after this")),
                ),
            ),
        )
        repository.clearGatewayQueueOnInterrupt = true
        runCurrent()

        viewModel.stop()
        runCurrent()

        assertTrue(cache.session("session-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
        assertEquals(
            "Stopped. Any queued next-turn messages were discarded with the turn.",
            viewModel.uiState.value.notice,
        )
    }

    @Test
    fun `history is session-local newest-first and undo is local only`() = runTest(dispatcher) {
        cache.setTranscript(
            "session-a",
            listOf(
                UserTurn("old", "older user turn", 1),
                UserTurn("new", "newest user turn", 2),
            ),
        )
        collectState()
        runCurrent()

        assertTrue(viewModel.historyOlder())
        runCurrent()
        assertEquals("newest user turn", viewModel.uiState.value.draft)
        assertTrue(viewModel.historyOlder())
        runCurrent()
        assertEquals("older user turn", viewModel.uiState.value.draft)
        assertTrue(viewModel.historyNewer())
        runCurrent()
        assertEquals("newest user turn", viewModel.uiState.value.draft)
        assertTrue(viewModel.historyNewer())
        runCurrent()
        assertEquals("", viewModel.uiState.value.draft)

        viewModel.setDraft("local one")
        viewModel.setDraft("local two")
        assertTrue(viewModel.undoDraft())
        runCurrent()
        assertEquals("local one", viewModel.uiState.value.draft)
        assertEquals(listOf("older user turn", "newest user turn"), cache.transcript("session-a").filterIsInstance<UserTurn>().map(UserTurn::text))
    }

    // -----------------------------------------------------------------------
    // Durable unread, pin and archive (#66).
    // -----------------------------------------------------------------------

    /**
     * Opening a session retires both unread sources: the transient dot here and
     * now, and the durable watermark behind it — Desktop's `clearUnreadOnOpen`
     * (`apps/desktop/src/store/session-unread-remote.ts:65-79` @ `3ca096de`),
     * best-effort because the reader did not ask for it.
     */
    @Test
    fun `opening a session retires the durable watermark as well as the dot`() = runTest(dispatcher) {
        collectState()
        cache.upsertSession(
            summary("session-b", 1_000).copy(status = SessionStatus.Unread, unread = true),
        )
        runCurrent()

        viewModel.selectSession("session-b")
        runCurrent()

        assertEquals(listOf(Triple("unread", "session-b", false)), repository.flagWrites)
        assertEquals(SessionStatus.Idle, cache.session("session-b")?.status)
        assertEquals(false, cache.session("session-b")?.unread)
    }

    /** A row the backend never called unread is not marked read on open. */
    @Test
    fun `opening a read session writes nothing`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.selectSession("session-b")
        runCurrent()

        assertTrue(repository.flagWrites.isEmpty())
    }

    @Test
    fun `the unread count sees both sources and ignores archived rows`() = runTest(dispatcher) {
        collectState()
        cache.upsertSessions(
            listOf(
                summary("session-a", 2_000).copy(unread = true),
                summary("session-b", 1_000).copy(status = SessionStatus.Unread),
                summary("session-c", 900).copy(unread = true, archived = true),
                summary("session-d", 800),
            ),
        )
        runCurrent()

        assertEquals(2, viewModel.uiState.value.unreadCount)
    }

    /**
     * Desktop's mark-all fans out one write per row
     * (`app/chat/sidebar/index.tsx:1735-1741` @ `3ca096de`) and has no string
     * for a partial failure, so the outcome is this app's own: the number that
     * did not move, never a blanket success.
     */
    @Test
    fun `mark all as read walks the unread rows and reports what refused`() = runTest(dispatcher) {
        collectState()
        cache.upsertSessions(
            listOf(
                summary("session-a", 2_000).copy(unread = true),
                summary("session-b", 1_000).copy(status = SessionStatus.Unread),
            ),
        )
        runCurrent()
        repository.refuseFlagWritesFor += "session-b"

        viewModel.markAllSessionsRead()
        runCurrent()

        assertEquals(
            listOf(Triple("unread", "session-a", false), Triple("unread", "session-b", false)),
            repository.flagWrites,
        )
        assertEquals(false, cache.session("session-a")?.unread)
        // `unreadFailed` verbatim (`i18n/en.ts:2307`) plus the honest count.
        assertEquals("Could not update unread state for 1 of 2 chats.", viewModel.uiState.value.notice)
    }

    @Test
    fun `mark all as read says nothing when every row moves`() = runTest(dispatcher) {
        collectState()
        cache.upsertSession(summary("session-a", 2_000).copy(unread = true))
        runCurrent()

        viewModel.markAllSessionsRead()
        runCurrent()

        assertEquals(1, repository.flagWrites.size)
        assertNull(viewModel.uiState.value.notice)
        assertEquals(0, viewModel.uiState.value.unreadCount)
    }

    /**
     * The `Archived` filter is a view choice: turning it on reads the archived
     * pool and the list renders that set instead of the live one. Turning it
     * back off asks for nothing — the live list was never carrying these rows,
     * which is Desktop's own shape (`sidebar/index.tsx:1352-1358` @ `3ca096de`,
     * `if (showArchived) void loadArchivedSessions()`).
     */
    @Test
    fun `the Archived filter swaps the pool and reads the archived set`() = runTest(dispatcher) {
        collectState()
        cache.upsertSessions(
            listOf(
                summary("session-a", 2_000),
                summary("session-c", 900).copy(archived = true),
            ),
        )
        runCurrent()

        assertEquals(
            listOf("session-a", "session-b"),
            viewModel.uiState.value.sessionRows.rowIds(),
        )

        viewModel.setArchivedVisible(true)
        runCurrent()

        assertEquals(1, repository.archivedLoads)
        assertTrue(viewModel.uiState.value.archivedVisible)
        assertEquals(listOf("session-c"), viewModel.uiState.value.sessionRows.rowIds())

        viewModel.setArchivedVisible(false)
        runCurrent()

        assertEquals(1, repository.archivedLoads)
        assertEquals(
            listOf("session-a", "session-b"),
            viewModel.uiState.value.sessionRows.rowIds(),
        )
    }

    /**
     * Archiving the open session leaves the reader on a fresh draft rather than
     * staring at a conversation that is no longer in the list — the same move
     * deleting the open session already makes.
     */
    @Test
    fun `archiving the open session leaves the reader on a fresh draft`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.selectSession("session-a")
        runCurrent()
        assertEquals("session-a", viewModel.uiState.value.activeSession?.id)

        viewModel.setSessionArchivedAsync("session-a", true)
        runCurrent()

        assertEquals(listOf(Triple("archived", "session-a", true)), repository.flagWrites)
        assertNull(viewModel.uiState.value.activeSession)
    }

    /** A refused flag write is reported on the one outcome slot this app has. */
    @Test
    fun `a refused pin is reported rather than silently dropped`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.refuseFlagWritesFor += "session-a"

        viewModel.setSessionPinnedAsync("session-a", true)
        runCurrent()

        // The pin's own sentence, not the read-state one.
        assertEquals(
            "Could not update pin. Check the Gateway and try again.",
            viewModel.uiState.value.notice,
        )
    }

    /**
     * The failure the whole verb set shares if the press owns the coroutine:
     * each of these takes the row off the list it was pressed on — an archive
     * leaves the live pool, a pin moves into the Pinned section — so a scope
     * belonging to that row's composition is cancelled mid-`PATCH`. The Gateway
     * is never told, and neither the success nor the rollback branch runs.
     *
     * These entry points do not suspend their caller: they hand the write to
     * [ChatViewModel]'s own scope, which outlives every row.
     */
    @Test
    fun `a flag write survives the row that started it leaving the screen`() = runTest(dispatcher) {
        // All three verbs, because all three move the row: an archive leaves
        // the live pool, a pin moves into the Pinned section, and marking read
        // is what the unread section is grouped by.
        val verbs = listOf<Pair<String, (String, Boolean) -> Unit>>(
            "archived" to viewModel::setSessionArchivedAsync,
            "pinned" to viewModel::setSessionPinnedAsync,
            "unread" to viewModel::setSessionUnreadAsync,
        )
        collectState()
        runCurrent()

        for ((flag, write) in verbs) {
            val id = "session-$flag"
            cache.upsertSession(summary(id, 2_000))
            runCurrent()
            // The PATCH is in flight and has not answered yet.
            val inFlight = CompletableDeferred<Unit>()
            repository.holdFlagWrites = inFlight

            // The row's own composition scope: the tap runs here.
            val rowScope = kotlinx.coroutines.CoroutineScope(dispatcher)
            rowScope.launch { write(id, true) }
            runCurrent()

            // The write takes the row off the list it was pressed on, so the row
            // leaves composition on the next frame and its scope is cancelled.
            rowScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            runCurrent()
            inFlight.complete(Unit)
            repository.holdFlagWrites = null
            runCurrent()

            assertTrue(
                "$flag was never written",
                Triple(flag, id, true) in repository.flagWrites,
            )
            assertNull(viewModel.uiState.value.notice)
        }

        assertEquals(true, cache.session("session-archived")?.archived)
        assertEquals(true, cache.session("session-pinned")?.pinned)
        assertEquals(true, cache.session("session-unread")?.unread)
    }

    /**
     * The archived set is one backend's, and a connection switch is a different
     * machine. `SessionCache.resetForEndpointSwitch()` already wipes every row
     * (`ConnectionSwitchController.kt:223`); what used to survive it was this
     * view's *belief* that it had already read the pool, so the Archived list
     * sat on the new Gateway showing `Nothing archived` — false about both
     * backends — until the reader toggled the filter off and on again.
     */
    @Test
    fun `the Archived view re-reads its pool after a connection switch`() = runTest(dispatcher) {
        collectState()
        repository.archivedPoolRows = listOf(summary("alpha-archived", 900).copy(archived = true))
        runCurrent()

        viewModel.setArchivedVisible(true)
        runCurrent()
        assertEquals(1, repository.archivedLoads)
        assertEquals(listOf("alpha-archived"), viewModel.uiState.value.sessionRows.rowIds())

        // The switch: the one wholesale clear, and a different machine's rows.
        cache.resetForEndpointSwitch()
        repository.archivedPoolRows = listOf(summary("beta-archived", 800).copy(archived = true))
        runCurrent()

        assertEquals(2, repository.archivedLoads)
        assertEquals(ArchivedPoolState.Loaded, viewModel.uiState.value.archivedPool)
        assertEquals(listOf("beta-archived"), viewModel.uiState.value.sessionRows.rowIds())
    }

    /**
     * And it does not paint the previous backend's answer while the new one is
     * being read: the marker goes back to "nothing has answered", which is
     * never `Nothing archived`.
     */
    @Test
    fun `an endpoint switch stops the Archived view claiming anything about the new backend`() =
        runTest(dispatcher) {
            collectState()
            runCurrent()
            viewModel.setArchivedVisible(true)
            runCurrent()
            assertEquals(ArchivedPoolState.Loaded, viewModel.uiState.value.archivedPool)

            val nextBackend = CompletableDeferred<Unit>()
            repository.holdArchivedLoads = nextBackend
            cache.resetForEndpointSwitch()
            runCurrent()

            assertEquals(ArchivedPoolState.Loading, viewModel.uiState.value.archivedPool)

            nextBackend.complete(Unit)
            runCurrent()
            assertEquals(ArchivedPoolState.Loaded, viewModel.uiState.value.archivedPool)
        }

    /**
     * `Nothing archived` is a claim about the account, so it waits for the
     * Gateway to have actually answered. The archived pool deliberately does
     * not publish through the live list's pager
     * (`GatewaySessionRepository.readSessionPages`), so this marker is the only
     * thing that can hold that sentence back. Desktop keeps the same marker
     * (`$archivedSessionsLoading`, `store/sidebar-archive.ts:12,19,28` @
     * `3ca096de`) but renders nothing with it.
     */
    @Test
    fun `the Archived view is loading until its pool answers`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val answer = CompletableDeferred<Unit>()
        repository.holdArchivedLoads = answer

        viewModel.setArchivedVisible(true)
        runCurrent()

        assertEquals(ArchivedPoolState.Loading, viewModel.uiState.value.archivedPool)

        answer.complete(Unit)
        runCurrent()

        // Answered, and the answer really was "nothing".
        assertEquals(ArchivedPoolState.Loaded, viewModel.uiState.value.archivedPool)
        assertEquals(emptyList<String>(), viewModel.uiState.value.sessionRows.rowIds())
    }

    /** A read that failed is not an account with nothing archived. */
    @Test
    fun `a failed archived read is reported as a failure, not as an empty set`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.archivedFailure =
            com.hermesagent.mobile.data.gateway.GatewayRpcException("Could not reach the Gateway.")

        viewModel.setArchivedVisible(true)
        runCurrent()

        assertEquals(ArchivedPoolState.Failed, viewModel.uiState.value.archivedPool)
        assertEquals("Could not reach the Gateway.", viewModel.uiState.value.notice)
    }

    /**
     * And a Gateway that cannot be asked at all is its own answer: the
     * `session.list` RPC has no archived filter, so the honest sentence is
     * about this Gateway rather than about the account.
     */
    @Test
    fun `a Gateway that cannot list archived chats says so`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.archivedFailure =
            com.hermesagent.mobile.data.gateway.GatewayRpcException(ARCHIVED_UNSUPPORTED)

        viewModel.setArchivedVisible(true)
        runCurrent()

        assertEquals(ArchivedPoolState.Unsupported, viewModel.uiState.value.archivedPool)
        assertEquals(ARCHIVED_UNSUPPORTED, viewModel.uiState.value.notice)
    }

    /**
     * The notice slot is product-facing. A Gateway refusal carries app copy —
     * `safeMessage` is redacted by contract — but a parse or state failure
     * carries an implementation sentence, and that must never reach the screen.
     */
    @Test
    fun `an archived read that fails outside the Gateway contract shows product copy`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.archivedFailure = IllegalStateException("kotlinx.serialization: unexpected JSON token")

        viewModel.setArchivedVisible(true)
        runCurrent()

        assertEquals(ArchivedPoolState.Failed, viewModel.uiState.value.archivedPool)
        assertEquals(
            "Could not load archived chats. Check the Gateway and try again.",
            viewModel.uiState.value.notice,
        )
    }

    private fun List<SessionListRow>.rowIds(): List<String> =
        filterIsInstance<SessionListRow.Row>().map { it.session.id }

    private fun kotlinx.coroutines.test.TestScope.collectState() {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    private fun kotlinx.coroutines.test.TestScope.collectState(viewModel: ChatViewModel) {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    private class FakeRepository(private val cache: SessionCache) : GatewaySessionRepository {
        val regenerateCalls = mutableListOf<Triple<String, String, TranscriptRowId>>()
        val regenerateEntryIds = mutableListOf<String>()
        val regenerateFailures = ArrayDeque<GatewayRpcException>()
        var regenerateFailsWithOther = false
        override suspend fun regenerate(
            durableId: String,
            text: String,
            truncateBeforeRowId: TranscriptRowId,
            truncateBeforeEntryId: String,
        ): GatewaySubmitOutcome {
            if (regenerateFailsWithOther) throw GatewayRpcException("Regenerate failed")
            regenerateFailures.removeFirstOrNull()?.let { throw it }
            regenerateCalls.add(Triple(durableId, text, truncateBeforeRowId))
            regenerateEntryIds.add(truncateBeforeEntryId)
            return GatewaySubmitOutcome.Accepted
        }

        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val pendingInputs =
            MutableStateFlow<Map<com.hermesagent.mobile.data.gateway.PendingInputKey, com.hermesagent.mobile.data.gateway.PendingInputRequest>>(
                emptyMap(),
            )

        override suspend fun respondToPendingInput(
            key: com.hermesagent.mobile.data.gateway.PendingInputKey,
            action: com.hermesagent.mobile.data.gateway.PendingInputAction,
        ): com.hermesagent.mobile.data.gateway.PendingInputResponse =
            com.hermesagent.mobile.data.gateway.PendingInputResponse.Resolved

        override val connectionState = connection
        private val rehomeEvents = MutableSharedFlow<SessionRehome>(extraBufferCapacity = 1)
        override val sessionRehomes = rehomeEvents
        private val composerControlEvents = MutableSharedFlow<SessionComposerControls>(extraBufferCapacity = 4)
        override val composerControls: Flow<SessionComposerControls> = composerControlEvents
        val opened = mutableListOf<String>()

        /** Every backend search this repository was actually asked for. */
        val searches = mutableListOf<Pair<String, String?>>()

        /** What the Gateway answers; null is "no server-side search here". */
        var searchAnswer: List<SessionSummary>? = null

        override suspend fun searchSessions(query: String, profile: String?): List<SessionSummary>? {
            searches += query to profile
            return searchAnswer
        }
        val submitted = mutableListOf<Pair<String, String>>()
        val interrupted = mutableListOf<String>()
        val openedProjects = mutableListOf<String>()
        val createdProjects = mutableListOf<Pair<String, String>>()
        val projectSessions = mutableMapOf<String, List<SessionSummary>>()
        var createProjectGate: CompletableDeferred<Unit>? = null
        var createSessionGate: CompletableDeferred<Unit>? = null
        var catalogRefreshedAfterCreate = true
        var created = 0
        var createdWorkspace: String? = null
        var failSubmit = false
        var submitGate: CompletableDeferred<Unit>? = null
        var submitAttempts = 0
        var submitOutcome: GatewaySubmitOutcome = GatewaySubmitOutcome.Accepted
        var redirectOutcome: GatewayRedirectOutcome = GatewayRedirectOutcome.Unsupported
        val redirects = mutableListOf<Pair<String, String>>()
        var controls = ModelControlsSnapshot(
            selection = ComposerModelSelection("model/default", "provider"),
            reasoning = ReasoningEffort.Medium,
            fast = FastMode.Normal,
        )
        var modelMutation: ControlMutationResult = ControlMutationResult.Applied
        var modelMutationGate: CompletableDeferred<ControlMutationResult>? = null
        var modelOptionsGate: CompletableDeferred<Unit>? = null
        val modelSelections = mutableListOf<ComposerModelSelection>()
        val reasoningSelections = mutableListOf<ReasoningEffort>()
        val fastSelections = mutableListOf<FastMode>()
        var createdOverrides: NewSessionComposerOverrides? = null
        var firstSlashGate: CompletableDeferred<Unit>? = null
        var slashReplaceFrom: Int? = null
        var pathGate: CompletableDeferred<Unit>? = null
        var lastPathDurableId: String? = null
        val submittedAttachments = mutableListOf<Pair<String, List<OutgoingAttachment>>>()
        val queuedSubmissions = mutableListOf<Pair<String, Boolean>>()
        var lastPathCwd: String? = null
        private var slashCalls = 0

        fun rehome(fromId: String, toId: String) {
            val row = requireNotNull(cache.session(fromId)).copy(id = toId)
            cache.rehomeSession(fromId, row, cache.transcript(fromId))
            check(rehomeEvents.tryEmit(SessionRehome(fromId, toId)))
        }

        fun emitComposerControls(event: SessionComposerControls) {
            check(composerControlEvents.tryEmit(event))
        }

        override suspend fun refreshSessions() = Unit

        /** Every flag write this fake was asked for, in order. */
        val flagWrites = mutableListOf<Triple<String, String, Boolean>>()

        /** How many times the archived pool was read. */
        var archivedLoads = 0

        /** Session ids whose next flag write is refused. */
        val refuseFlagWritesFor = mutableSetOf<String>()

        /**
         * The repository raises a different sentence per flag, so a fake that
         * raised one message for all three would let a test named for pin pass
         * on the unread copy.
         */
        private fun refusalFor(flag: String) = when (flag) {
            "pinned" -> "Could not update pin. Check the Gateway and try again."
            "archived" -> "Could not archive that chat. Check the Gateway and try again."
            else -> "Could not update unread state"
        }

        /** Parks every flag write until completed, so a test can cancel mid-write. */
        var holdFlagWrites: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        private suspend fun writeFlag(flag: String, durableId: String, value: Boolean) {
            holdFlagWrites?.await()
            flagWrites += Triple(flag, durableId, value)
            if (durableId in refuseFlagWritesFor) {
                throw com.hermesagent.mobile.data.gateway.GatewayRpcException(refusalFor(flag))
            }
            val row = cache.session(durableId) ?: return
            cache.upsertSession(
                when (flag) {
                    "pinned" -> row.copy(pinned = value)
                    "archived" -> row.copy(archived = value)
                    else -> row.copy(
                        unread = value,
                        status = if (!value && row.status == SessionStatus.Unread) SessionStatus.Idle else row.status,
                    )
                },
            )
        }

        override suspend fun setSessionPinned(durableId: String, pinned: Boolean) =
            writeFlag("pinned", durableId, pinned)

        override suspend fun setSessionArchived(durableId: String, archived: Boolean) =
            writeFlag("archived", durableId, archived)

        override suspend fun setSessionUnread(durableId: String, unread: Boolean) =
            writeFlag("unread", durableId, unread)

        /** What the `archived=only` pool answers with on the next read. */
        var archivedPoolRows: List<SessionSummary> = emptyList()

        /** Parks the archived read, so a test can look at the view mid-flight. */
        var holdArchivedLoads: CompletableDeferred<Unit>? = null

        /** What the archived read raises instead of answering. */
        var archivedFailure: Throwable? = null

        override suspend fun loadArchivedSessions() {
            archivedLoads++
            holdArchivedLoads?.await()
            archivedFailure?.let { throw it }
            cache.upsertSessions(archivedPoolRows)
        }

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

        @JvmField
        var statusOnOpen: SessionStatus? = null

        override suspend fun openSession(durableId: String): String {
            opened += durableId
            statusOnOpen?.let { status ->
                cache.session(durableId)?.let { cache.upsertSession(it.copy(status = status)) }
            }
            return durableId
        }


        var branchResult = "new-durable"
        var historyResult = emptyList<TranscriptEntry>()
        var historyGate: CompletableDeferred<List<TranscriptEntry>>? = null
        val branchCalls = mutableListOf<Pair<String, Int?>>()

        override suspend fun branchSession(durableId: String, count: Int?): String {
            branchCalls.add(durableId to count)
            cache.upsertSession(summary(branchResult, CLOCK).copy(title = "Branched from $durableId"))
            return branchResult
        }

        override suspend fun fetchSessionHistory(durableId: String): List<TranscriptEntry> {
            return historyGate?.await() ?: historyResult
        }
        override suspend fun createSession(workspacePath: String?): String {
            created++
            createdWorkspace = workspacePath
            val id = "created-$created"
            cache.upsertSession(summary(id, CLOCK).copy(title = "New session"))
            return id
        }

        override suspend fun createSession(
            workspacePath: String?,
            overrides: NewSessionComposerOverrides?,
        ): String {
            createSessionGate?.await()
            createdOverrides = overrides
            return createSession(workspacePath)
        }

        override suspend fun loadModelOptions(durableId: String?): ModelCatalog {
            modelOptionsGate?.await()
            return ModelCatalog(effectiveSelection = controls.selection)
        }

        override suspend fun loadComposerControls(durableId: String?): ModelControlsSnapshot = controls

        override suspend fun setLiveModel(
            durableId: String,
            selection: ComposerModelSelection,
        ): ControlMutationResult {
            modelSelections += selection
            return modelMutationGate?.await() ?: modelMutation
        }

        override suspend fun setLiveReasoning(
            durableId: String,
            effort: ReasoningEffort,
        ): ControlMutationResult {
            reasoningSelections += effort
            return ControlMutationResult.Applied
        }

        override suspend fun setLiveFast(durableId: String, mode: FastMode): ControlMutationResult {
            fastSelections += mode
            return ControlMutationResult.Applied
        }

        override suspend fun completeSlash(query: String): CompletionResult {
            slashCalls += 1
            if (slashCalls == 1) firstSlashGate?.await()
            return CompletionResult(
                items = listOf(CompletionItem(query.removePrefix("/"))),
                replaceFrom = slashReplaceFrom,
            )
        }

        override suspend fun completePath(durableId: String?, query: String, cwd: String): CompletionResult {
            lastPathDurableId = durableId
            lastPathCwd = cwd
            pathGate?.await()
            return CompletionResult(listOf(CompletionItem("@file:src/main.kt")))
        }

        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome =
            submit(durableId, text, queued = false)

        override suspend fun submit(
            durableId: String,
            text: String,
            queued: Boolean,
            attachments: List<OutgoingAttachment>,
        ): GatewaySubmitOutcome {
            submitAttempts += 1
            submitGate?.await()
            if (failSubmit) error("fixture failure")
            queuedSubmissions += durableId to queued
            if (attachments.isNotEmpty()) submittedAttachments += durableId to attachments
            submitted += durableId to text
            cache.session(durableId)?.let { cache.upsertSession(it.copy(status = SessionStatus.Working)) }
            return submitOutcome
        }

        override suspend fun redirect(durableId: String, text: String): GatewayRedirectOutcome {
            redirects += durableId to text
            return redirectOutcome
        }

        override suspend fun interrupt(durableId: String) {
            interrupted += durableId
        }

        var interruptOutcome: GatewayInterruptOutcome? = null
        var clearGatewayQueueOnInterrupt = false

        override suspend fun requestInterrupt(durableId: String): GatewayInterruptOutcome {
            interrupted += durableId
            if (clearGatewayQueueOnInterrupt) {
                cache.session(durableId)?.let { session ->
                    cache.upsertSession(
                        session.copy(
                            composerStatus = session.composerStatus?.copy(gatewayQueuedPrompts = emptyList()),
                        ),
                    )
                }
            }
            return interruptOutcome ?: when (cache.session(durableId)?.status) {
                SessionStatus.NeedsInput -> GatewayInterruptOutcome.NeedsInput
                else -> GatewayInterruptOutcome.Interrupted
            }
        }

        val renamed = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()

        override suspend fun renameSession(durableId: String, title: String): String {
            renamed.add(durableId to title)
            val existing = cache.session(durableId)
            if (existing != null) {
                cache.upsertSession(existing.copy(title = title))
            }
            return title
        }

        override suspend fun deleteSession(durableId: String) {
            deleted.add(durableId)
            cache.removeSession(durableId)
        }
    }

    private class DelayedDraftStore : SessionDraftStore {
        override suspend fun clear() = Unit

        private val restored = MutableSharedFlow<LinkedHashMap<String, String>>(extraBufferCapacity = 1)
        override val drafts: Flow<LinkedHashMap<String, String>> = restored

        suspend fun emit(value: LinkedHashMap<String, String>) {
            restored.emit(value)
        }

        override suspend fun replace(durableSessionId: String, text: String) = Unit

        override suspend fun migrateIfDestinationEmpty(
            fromDurableId: String,
            toDurableId: String,
            sourceText: String?,
        ): String? = null
    }

    private class GatedMigrationDraftStore : SessionDraftStore {
        override suspend fun clear() = Unit

        override val drafts = MutableStateFlow(linkedMapOf<String, String>())
        val migrationStarted = CompletableDeferred<Unit>()
        val releaseMigration = CompletableDeferred<Unit>()
        val writes = mutableListOf<Pair<String, String>>()

        override suspend fun replace(durableSessionId: String, text: String) {
            writes += durableSessionId to text
        }

        override suspend fun migrateIfDestinationEmpty(
            fromDurableId: String,
            toDurableId: String,
            sourceText: String?,
        ): String? {
            migrationStarted.complete(Unit)
            releaseMigration.await()
            return sourceText
        }
    }

    private class FailingDraftStore : SessionDraftStore {
        override suspend fun clear() = Unit

        override val drafts = MutableStateFlow(linkedMapOf<String, String>())

        override suspend fun replace(durableSessionId: String, text: String): Unit = error("fixture write failure")

        override suspend fun migrateIfDestinationEmpty(
            fromDurableId: String,
            toDurableId: String,
            sourceText: String?,
        ): String? = error("fixture migration failure")
    }

    private class DelayedComposerControlsStore : ComposerControlsStore {
        private val scope = ComposerControlsScope("test-gateway", "default")
        override val activeScope = MutableStateFlow(scope)
        val snapshots = MutableSharedFlow<NewDraftComposerPreference?>()
        var saved: NewDraftComposerPreference? = null

        override fun preference(scope: ComposerControlsScope): Flow<NewDraftComposerPreference?> = snapshots

        override suspend fun saveManual(
            scope: ComposerControlsScope,
            preference: NewDraftComposerPreference,
        ) {
            saved = preference
        }

        override suspend fun clearManual(scope: ComposerControlsScope) {
            saved = null
        }
    }

    private class RecordingCodingContextProvider(
        private val context: CodingContext = CodingContext.Unavailable,
        private val pullRequest: CodingPullRequest? = null,
        private val review: CodingReviewResult = CodingReviewResult.Unavailable,
    ) : CodingContextProvider {
        val contextPaths = mutableListOf<String>()
        val pullRequestPaths = mutableListOf<Pair<String, String>>()
        val reviewPaths = mutableListOf<String>()

        override suspend fun contextFor(worktreePath: String): CodingContext {
            contextPaths += worktreePath
            return context
        }

        override suspend fun pullRequestFor(worktreePath: String, branch: String): CodingPullRequest? {
            pullRequestPaths += worktreePath to branch
            return pullRequest
        }

        override suspend fun reviewFor(worktreePath: String): CodingReviewResult {
            reviewPaths += worktreePath
            return review
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

    @Test
    fun `regenerateReply on newest reply with known row id`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript("a", listOf(
            UserTurn("1", "first", CLOCK, rowId = TranscriptRowId(1L)),
            AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L))
        ))
        vm.selectSession("a")
        runCurrent()
        
        vm.regenerateReply("2")
        runCurrent()
        
        assertEquals(1, repository.regenerateCalls.size)
        assertEquals(Triple("a", "first", TranscriptRowId(1L)), repository.regenerateCalls.first())
        assertEquals(listOf("1"), repository.regenerateEntryIds)
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun `regenerateReply fetches history for missing row id`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript("a", listOf(
            UserTurn("1", "first", CLOCK),
            AssistantTurn("2", "reply", CLOCK)
        ))
        repository.historyResult = listOf(UserTurn("3", "first", CLOCK, rowId = TranscriptRowId(10L)))
        vm.selectSession("a")
        runCurrent()
        
        vm.regenerateReply("2")
        runCurrent()
        
        assertEquals(1, repository.regenerateCalls.size)
        assertEquals(Triple("a", "first", TranscriptRowId(10L)), repository.regenerateCalls.first())
    }

    @Test
    fun `regenerateReply ambiguous history refusal`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript("a", listOf(
            UserTurn("1", "first", CLOCK),
            AssistantTurn("2", "reply", CLOCK)
        ))
        repository.historyResult = listOf(
            UserTurn("3", "first", CLOCK, rowId = TranscriptRowId(10L)),
            UserTurn("4", "first", CLOCK, rowId = TranscriptRowId(20L)),
            UserTurn("5", "different", CLOCK, rowId = TranscriptRowId(30L)),
            AssistantTurn("6", "reply", CLOCK, rowId = TranscriptRowId(40L)),
        )
        vm.selectSession("a")
        runCurrent()
        
        vm.regenerateReply("2")
        runCurrent()
        
        assertEquals(0, repository.regenerateCalls.size)
        assertEquals("Refresh could not find this turn in the session history. Reopen the session and try again.", vm.uiState.value.notice)
    }

    @Test
    fun `regenerateReply working session interrupts after planning`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", status = SessionStatus.Working, title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript("a", listOf(
            UserTurn("1", "first", CLOCK, rowId = TranscriptRowId(1L)),
            AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L))
        ))
        vm.selectSession("a")
        runCurrent()
        
        vm.regenerateReply("2")
        runCurrent()
        
        assertEquals(1, repository.interrupted.size)
        assertEquals(1, repository.regenerateCalls.size)
    }

    @Test
    fun `regenerateReply interrupts when session becomes working during history resolution`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript(
            "a",
            listOf(
                UserTurn("1", "first", CLOCK),
                AssistantTurn("2", "reply", CLOCK),
            ),
        )
        repository.historyGate = CompletableDeferred()
        vm.selectSession("a")
        runCurrent()

        vm.regenerateReply("2")
        runCurrent()
        assertTrue(repository.interrupted.isEmpty())
        assertTrue(repository.regenerateCalls.isEmpty())

        cache.upsertSession(requireNotNull(cache.session("a")).copy(status = SessionStatus.Working))
        repository.historyGate?.complete(
            listOf(UserTurn("history-user", "first", CLOCK, rowId = TranscriptRowId(10L))),
        )
        runCurrent()

        assertEquals(listOf("a"), repository.interrupted)
        assertEquals(1, repository.regenerateCalls.size)
    }

    @Test
    fun `regenerateReply skips stale interrupt when session settles during history resolution`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(
            SessionSummary(
                "a",
                status = SessionStatus.Working,
                title = "",
                preview = "",
                lastActiveAtMillis = CLOCK,
            ),
        )
        cache.setTranscript(
            "a",
            listOf(
                UserTurn("1", "first", CLOCK),
                AssistantTurn("2", "reply", CLOCK),
            ),
        )
        repository.historyGate = CompletableDeferred()
        vm.selectSession("a")
        runCurrent()

        vm.regenerateReply("2")
        runCurrent()
        assertTrue(repository.regenerateCalls.isEmpty())

        cache.upsertSession(requireNotNull(cache.session("a")).copy(status = SessionStatus.Idle))
        repository.historyGate?.complete(
            listOf(UserTurn("history-user", "first", CLOCK, rowId = TranscriptRowId(10L))),
        )
        runCurrent()

        assertTrue(repository.interrupted.isEmpty())
        assertEquals(1, repository.regenerateCalls.size)
    }

    @Test
    fun `regenerateReply does not interrupt a working session without a source`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(
            SessionSummary(
                "a",
                status = SessionStatus.Working,
                title = "",
                preview = "",
                lastActiveAtMillis = CLOCK,
            ),
        )
        cache.setTranscript("a", listOf(AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L))))
        vm.selectSession("a")
        runCurrent()

        vm.regenerateReply("2")
        runCurrent()

        assertTrue(repository.interrupted.isEmpty())
        assertTrue(repository.regenerateCalls.isEmpty())
        assertNull(vm.uiState.value.notice)
    }

    @Test
    fun `regenerateReply reports interrupt refusal and does not regenerate`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(
            SessionSummary(
                "a",
                status = SessionStatus.Working,
                title = "",
                preview = "",
                lastActiveAtMillis = CLOCK,
            ),
        )
        cache.setTranscript(
            "a",
            listOf(
                UserTurn("1", "first", CLOCK, rowId = TranscriptRowId(1L)),
                AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L)),
            ),
        )
        repository.interruptOutcome = GatewayInterruptOutcome.Rejected
        vm.selectSession("a")
        runCurrent()

        vm.regenerateReply("2")
        runCurrent()

        assertEquals(listOf("a"), repository.interrupted)
        assertTrue(repository.regenerateCalls.isEmpty())
        assertEquals("Hermes could not be stopped. Check the Gateway connection.", vm.uiState.value.notice)
    }

    @Test
    fun `regenerateReply retries the Gateway busy response after interrupting`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript(
            "a",
            listOf(
                UserTurn("1", "first", CLOCK, rowId = TranscriptRowId(1L)),
                AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L)),
            ),
        )
        repository.regenerateFailures.add(GatewayRpcException("Gateway 4090: session busy"))
        vm.selectSession("a")
        runCurrent()

        vm.regenerateReply("2")
        runCurrent()

        assertEquals(listOf("a"), repository.interrupted)
        assertEquals(1, repository.regenerateCalls.size)
    }

    @Test
    fun `regenerateReply tells a disconnected user to connect`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript(
            "a",
            listOf(
                UserTurn("1", "first", CLOCK, rowId = TranscriptRowId(1L)),
                AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L)),
            ),
        )
        repository.connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        vm.selectSession("a")
        runCurrent()

        vm.regenerateReply("2")
        runCurrent()

        assertTrue(repository.regenerateCalls.isEmpty())
        assertEquals("Connect to a Gateway before refreshing this reply.", vm.uiState.value.notice)
    }

    @Test
    fun `regenerateReply needs input session refused`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", status = SessionStatus.NeedsInput, title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript(
            "a",
            listOf(
                UserTurn("1", "first", CLOCK, rowId = TranscriptRowId(1L)),
                AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L)),
            ),
        )
        vm.selectSession("a")
        runCurrent()
        
        vm.regenerateReply("2")
        runCurrent()
        
        assertEquals(0, repository.regenerateCalls.size)
        assertEquals("Hermes needs a response. Answer the request above.", vm.uiState.value.notice)
    }

    @Test
    fun `regenerateReply rejection notice`() = runTest(dispatcher) {
        val cache = SessionCache()
        val repository = FakeRepository(cache)
        val vm = ChatViewModel(cache, repository, clock = { CLOCK })
        collectState(vm)
        cache.upsertSession(SessionSummary("a", title = "", preview = "", lastActiveAtMillis = CLOCK))
        cache.setTranscript("a", listOf(
            UserTurn("1", "first", CLOCK, rowId = TranscriptRowId(1L)),
            AssistantTurn("2", "reply", CLOCK, rowId = TranscriptRowId(2L))
        ))
        repository.regenerateFailsWithOther = true
        vm.selectSession("a")
        runCurrent()
        
        vm.regenerateReply("2")
        runCurrent()
        
        assertEquals("Regenerate failed. Check the Gateway and try again.", vm.uiState.value.notice)
    }
    private companion object {
        const val CLOCK = 1_800_000_000_000L
        fun summary(id: String, at: Long) = SessionSummary(id, "Session $id", "", at)
    }

@Test
    fun `attachment grant is read bounded and submitted as bytes with the prompt`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.openAttachmentStream = { "hello gateway".toByteArray().inputStream() }
        viewModel.attachmentReadDispatcher = dispatcher

        viewModel.addAttachmentFromGrant("content://fixture/grant", "notes.txt", "text/plain")
        runCurrent()

        val chip = viewModel.uiState.value.composer.runtime.attachments.single()
        assertEquals("notes.txt", chip.displayName)
        assertTrue(chip.stage is AttachmentStage.Ready)
        assertEquals(13, (chip.stage as AttachmentStage.Ready).byteCount)

        viewModel.submit()
        runCurrent()

        val sent = repository.submittedAttachments.single()
        assertEquals("session-a", sent.first)
        val attachment = sent.second.single()
        assertTrue(attachment is OutgoingAttachment.GenericFile)
        assertEquals("notes.txt", attachment.displayName)
        assertTrue(viewModel.uiState.value.composer.runtime.attachments.isEmpty())
    }

    @Test
    fun `concurrent picks reserve the aggregate bound before payloads land`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val big = ByteArray(AttachmentPolicy.MAX_BYTES_PER_ATTACHMENT) { 0x61 }
        val streams = ArrayDeque(
            listOf(big.inputStream(), big.inputStream(), "tiny".toByteArray().inputStream()),
        )
        viewModel.openAttachmentStream = { streams.removeFirstOrNull() }
        viewModel.attachmentReadDispatcher = dispatcher

        viewModel.addAttachmentFromGrant("content://fixture/big1", "big1.bin", null)
        viewModel.addAttachmentFromGrant("content://fixture/big2", "big2.bin", null)
        // Issued while both 8 MB reads are still in flight: the optimistic
        // reservation already accounts 16 MB, so even 4 bytes must refuse.
        viewModel.addAttachmentFromGrant("content://fixture/tiny", "tiny.txt", null)
        runCurrent()

        val stages = viewModel.uiState.value.composer.runtime.attachments.associate { it.displayName to it.stage }
        assertTrue(stages["big1.bin"] is AttachmentStage.Ready)
        assertTrue(stages["big2.bin"] is AttachmentStage.Ready)
        assertTrue(stages.getValue("tiny.txt") is AttachmentStage.Refused)
    }

    @Test
    fun `send next rehydrates a stale streaming row before interrupting`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        viewModel.setDraft("queued follow-up")
        runCurrent()
        viewModel.queueDraft()
        runCurrent()
        val entryId = viewModel.uiState.value.composer.runtime.queueEntries.single().id

        repository.statusOnOpen = SessionStatus.Idle
        viewModel.setDraft("")
        runCurrent()
        viewModel.sendNext(entryId)
        runCurrent()

        assertTrue("session-a" in repository.opened)
        assertNull(viewModel.uiState.value.notice)
    }

    @Test
    fun `ambiguous attachment submit keeps the caption and blocks automatic retry`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        cache.upsertSession(requireNotNull(cache.session("session-a")).copy(status = SessionStatus.Working))
        viewModel.openAttachmentStream = { "hello gateway".toByteArray().inputStream() }
        viewModel.attachmentReadDispatcher = dispatcher
        viewModel.addAttachmentFromGrant("content://fixture/g", "notes.txt", "text/plain")
        runCurrent()
        repository.submitOutcome = GatewaySubmitOutcome.Ambiguous

        viewModel.setDraft("with a file")
        runCurrent()
        viewModel.performComposerPrimaryAction()
        runCurrent()

        val chips = viewModel.uiState.value.composer.runtime.attachments
        assertEquals(1, chips.size)
        assertTrue(chips.single().stage is AttachmentStage.ReviewRequired)
        // The editor stays clear (the send was accepted into the wire), but the
        // chip itself carries the exact caption for review.
        assertTrue(viewModel.uiState.value.notice!!.contains("may have been sent"))
        val chip = chips.single().stage as AttachmentStage.ReviewRequired
        assertEquals("with a file", chip.submittedText)

        // A review-required chip blocks any automatic re-send of the possibly
        // accepted payload.
        viewModel.performComposerPrimaryAction()
        runCurrent()
        assertEquals(1, repository.submitAttempts)
    }

    @Test
    fun `deleting the active session routes to a fresh draft and displays notice`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        assertEquals("session-a", viewModel.uiState.value.activeSession?.id)

        viewModel.deleteSession("session-a")
        runCurrent()

        assertEquals(listOf("session-a"), repository.deleted)
        assertNull(viewModel.uiState.value.activeSession)
        assertEquals("Session deleted", viewModel.uiState.value.notice)
        assertEquals(listOf("session-b"), cache.state.value.sessions.keys.toList())
    }

    @Test
    fun `deleting an inactive session removes it without resetting active session draft`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        assertEquals("session-a", viewModel.uiState.value.activeSession?.id)
        viewModel.setDraft("active draft")
        runCurrent()

        viewModel.deleteSession("session-b")
        runCurrent()

        assertEquals(listOf("session-b"), repository.deleted)
        assertEquals("session-a", viewModel.uiState.value.activeSession?.id)
        assertEquals("active draft", viewModel.uiState.value.draft)
        assertEquals("Session deleted", viewModel.uiState.value.notice)
    }

    @Test
    fun `renaming a session updates repository and cache`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.renameSession("session-a", "Updated Session Title")
        runCurrent()

        assertEquals(listOf("session-a" to "Updated Session Title"), repository.renamed)
        assertEquals("Updated Session Title", cache.session("session-a")?.title)
    }
    // -----------------------------------------------------------------------
    // Backend session search: Desktop's 200 ms debounce and its local-first
    // merge (`apps/desktop/src/app/chat/sidebar/index.tsx:619-678` @
    // `3ca096de5f8183cb2e0ec23673f294d5978656a3`). All of it on virtual time.
    // -----------------------------------------------------------------------

    /** `setTimeout(…, 200)` at `sidebar/index.tsx:647`, and no minimum length. */
    @Test
    fun `no backend search is issued before the debounce elapses`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.setQuery("t")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS - 1)
        runCurrent()

        assertEquals(emptyList<Pair<String, String?>>(), repository.searches)

        advanceTimeBy(1)
        runCurrent()

        // One character is a legitimate search; the wait, not a length floor,
        // is what keeps this from being a request per keystroke.
        assertEquals(listOf("t" to null), repository.searches)
    }

    /** A retyped query cancels the pending one: one request, for the last word. */
    @Test
    fun `a query retyped inside the debounce window issues one search for the final text`() =
        runTest(dispatcher) {
            collectState()
            runCurrent()

            viewModel.setQuery("tun")
            runCurrent()
            advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS - 50)
            viewModel.setQuery("tunnel")
            runCurrent()
            advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS - 50)
            runCurrent()

            assertEquals(emptyList<Pair<String, String?>>(), repository.searches)

            advanceTimeBy(50)
            runCurrent()

            assertEquals(listOf("tunnel" to null), repository.searches)
        }

    /** The trimmed query is what travels, and whitespace alone is not a query. */
    @Test
    fun `the search is the trimmed query, and a blank one asks nothing`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.setQuery("   ")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(emptyList<Pair<String, String?>>(), repository.searches)

        viewModel.setQuery("  tunnel  ")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf("tunnel" to null), repository.searches)
    }

    /**
     * Loaded rows answer instantly; the backend's slower answer is appended
     * behind them, and the loaded row object wins for the same conversation
     * (`sidebar/index.tsx:655-678`).
     */
    @Test
    fun `local matches answer at once and server hits are appended behind them`() = runTest(dispatcher) {
        cache.upsertSessions(
            listOf(
                summary("session-a", 2_000).copy(title = "Tunnel probe", lineageRootId = "root-a"),
                summary("session-b", 1_000).copy(title = "Themes"),
            ),
        )
        repository.searchAnswer = listOf(
            // Same conversation as `session-a`, reached under its lineage root.
            stub("root-a", "tunnel again"),
            stub("session-z", "another tunnel"),
        )
        collectState()
        runCurrent()

        viewModel.setQuery("tunnel")
        runCurrent()

        // Before the debounce: the local match alone, already under `Results`.
        assertEquals(SessionListRow.ResultsLabel, viewModel.uiState.value.sessionRows.first())
        assertEquals(listOf("session-a"), viewModel.uiState.value.sessionRows.rowIds())

        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("session-a", "session-z"), viewModel.uiState.value.sessionRows.rowIds())
    }

    /**
     * A Gateway that cannot be asked, or that refuses, leaves the client-side
     * matches exactly where they were — no banner, no empty list. Desktop
     * swallows the same failure (`sidebar/index.tsx:641`).
     */
    @Test
    fun `a Gateway without backend search still answers from the loaded rows`() = runTest(dispatcher) {
        cache.upsertSessions(listOf(summary("session-a", 2_000).copy(title = "Tunnel probe")))
        repository.searchAnswer = null
        collectState()
        runCurrent()

        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("session-a"), viewModel.uiState.value.sessionRows.rowIds())
        assertNull(viewModel.uiState.value.notice)
    }

    /**
     * Skeletons stand in for the *server* answer only, and only while nothing
     * else is on screen — Desktop hangs them on the section's empty state
     * (`sidebar/index.tsx:1615-1623`). Settled with nothing, the sentence
     * replaces them.
     */
    @Test
    fun `an unmatched query shows skeletons while the backend answers, then Desktop's sentence`() =
        runTest(dispatcher) {
            repository.searchAnswer = emptyList()
            collectState()
            runCurrent()

            viewModel.setQuery("nothing here")
            runCurrent()

            assertTrue(
                viewModel.uiState.value.sessionRows.contains(
                    SessionListRow.SearchSkeletons,
                ),
            )

            advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            assertEquals(
                SessionListRow.NoResultsNote("nothing here"),
                viewModel.uiState.value.sessionRows.last(),
            )
        }

    /** Clearing the field drops the server half with it. */
    @Test
    fun `clearing the query drops the server hits and restores the ordinary list`() = runTest(dispatcher) {
        repository.searchAnswer = listOf(stub("session-z", "a tunnel"))
        collectState()
        runCurrent()

        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf("session-z"), viewModel.uiState.value.sessionRows.rowIds())

        viewModel.setQuery("")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("session-a", "session-b"), viewModel.uiState.value.sessionRows.rowIds())
        assertEquals(listOf("tunnel" to null), repository.searches)
    }

    /**
     * A stub is UI state. It never reaches the cache, because a row with an
     * invented message count and no archive or pin flag must not become
     * indistinguishable from a listed one.
     */
    @Test
    fun `a search stub never enters the session cache`() = runTest(dispatcher) {
        repository.searchAnswer = listOf(stub("session-z", "a tunnel"))
        collectState()
        runCurrent()

        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertNull(cache.session("session-z"))
    }

    /**
     * A stub carries a durable id and nothing that says whose it is. The rail
     * standing in another profile is a different set of conversations, so the
     * previous scope's stubs stop being an answer the moment the scope moves —
     * not 200 ms and a round trip later.
     */
    @Test
    fun `a profile switch drops the previous scope's stubs and re-asks for the new one`() =
        runTest(dispatcher) {
            repository.searchAnswer = listOf(stub("session-z", "a tunnel"))
            collectState()
            runCurrent()

            viewModel.setQuery("tunnel")
            runCurrent()
            advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(listOf("session-z"), viewModel.uiState.value.sessionRows.rowIds())

            repository.searchAnswer = listOf(stub("session-y", "another tunnel"))
            viewModel.selectProfile("research")
            runCurrent()

            // Immediately: gone. And nothing new has been asked for yet, so
            // this is the clear rather than a fresh answer arriving.
            assertEquals(emptyList<String>(), viewModel.uiState.value.sessionRows.rowIds())
            assertEquals(listOf("tunnel" to null), repository.searches)

            advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            // Re-asked for the new scope, and only for the new scope.
            assertEquals(listOf("tunnel" to null, "tunnel" to "research"), repository.searches)
            assertEquals(listOf("session-y"), viewModel.uiState.value.sessionRows.rowIds())
        }

    /**
     * The endpoint half of the same key. The next backend is a different
     * machine that can recycle the same durable ids — which is why
     * `SessionCache.resetForEndpointSwitch()` exists — so a stub from the
     * previous one is not a narrower answer, it is a row that may open a
     * different conversation.
     */
    @Test
    fun `a connection switch drops the previous Gateway's stubs and re-asks`() = runTest(dispatcher) {
        repository.searchAnswer = listOf(stub("session-z", "a tunnel"))
        collectState()
        runCurrent()

        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf("session-z"), viewModel.uiState.value.sessionRows.rowIds())

        repository.searchAnswer = listOf(stub("session-y", "another tunnel"))
        cache.resetForEndpointSwitch()
        runCurrent()

        assertEquals(emptyList<String>(), viewModel.uiState.value.sessionRows.rowIds())
        assertEquals(listOf("tunnel" to null), repository.searches)

        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("tunnel" to null, "tunnel" to null), repository.searches)
        assertEquals(listOf("session-y"), viewModel.uiState.value.sessionRows.rowIds())
    }

    /**
     * The unified browse view has no union to ask for: the route opens exactly
     * one profile's database (`hermes_cli/web_routers/sessions.py:227` @
     * `3ca096de`). Sending the active profile there would merge one profile's
     * server hits into an all-profile list, so it sends none — ledgered in
     * `docs/parity/session-search.md`.
     */
    @Test
    fun `the unified profile view searches with no profile at all`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.selectProfile("research")
        runCurrent()
        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf("tunnel" to "research"), repository.searches)

        viewModel.showAllProfiles()
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("tunnel" to "research", "tunnel" to null), repository.searches)
    }

    /**
     * The project overview reuses this same field as a filter over projects and
     * draws no session row at all, so a backend session search there would be a
     * request per settled keystroke for an answer nothing renders.
     */
    @Test
    fun `the project overview does not ask the Gateway for sessions`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.setSidebarGrouping(SidebarGrouping.Project)
        runCurrent()
        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(emptyList<Pair<String, String?>>(), repository.searches)

        viewModel.setSidebarGrouping(SidebarGrouping.Date)
        runCurrent()
        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()

        assertEquals(listOf("tunnel" to null), repository.searches)
    }

    /**
     * Desktop's `.catch(() => undefined)` leaves the previous query's hits on
     * screen (`sidebar/index.tsx:641`); this drops them, because on a phone the
     * Gateway is across a network that drops and a stale answer under a new
     * query is a claim about the wrong words. The local matches — what Desktop
     * is really protecting — are untouched. Ledgered.
     */
    @Test
    fun `a failure after an answer empties the server half and keeps the local matches`() =
        runTest(dispatcher) {
            cache.upsertSessions(listOf(summary("session-a", 2_000).copy(title = "Tunnel probe")))
            repository.searchAnswer = listOf(stub("session-z", "a tunnel"))
            collectState()
            runCurrent()

            viewModel.setQuery("tunnel")
            runCurrent()
            advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(
                listOf("session-a", "session-z"),
                viewModel.uiState.value.sessionRows.rowIds(),
            )

            repository.searchAnswer = null
            viewModel.setQuery("tunnel probe")
            runCurrent()
            advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            assertEquals(listOf("session-a"), viewModel.uiState.value.sessionRows.rowIds())
            assertNull(viewModel.uiState.value.notice)
        }

    /**
     * The whole point of a stub row: the conversation it names is one this app
     * has never paged in, so selecting it has to reach the Gateway rather than
     * look the id up in the cache.
     */
    @Test
    fun `selecting a search stub opens the session it names`() = runTest(dispatcher) {
        repository.searchAnswer = listOf(stub("session-z", "a tunnel"))
        collectState()
        runCurrent()

        viewModel.setQuery("tunnel")
        runCurrent()
        advanceTimeBy(ChatViewModel.SESSION_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(listOf("session-z"), viewModel.uiState.value.sessionRows.rowIds())
        assertNull(cache.session("session-z"))

        viewModel.selectSession("session-z")
        runCurrent()

        assertTrue("session-z" in repository.opened)
    }

    /** A search hit built the way the repository builds one. */
    private fun stub(id: String, preview: String) = SessionSummary(
        id = id,
        title = "",
        preview = preview,
        lastActiveAtMillis = CLOCK,
        lineageRootId = id,
    )
}
