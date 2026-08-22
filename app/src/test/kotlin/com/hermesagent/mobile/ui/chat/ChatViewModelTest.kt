package com.hermesagent.mobile.ui.chat

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
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.ProjectCreateOutcome
import com.hermesagent.mobile.data.gateway.SessionRehome
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.prefs.SidebarViewStore
import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.data.prefs.ComposerControlsStore
import com.hermesagent.mobile.data.prefs.NewDraftComposerPreference
import com.hermesagent.mobile.data.prefs.TransientComposerControlsStore
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    fun `session references use the scoped Gateway profile rather than hard-coded default`() = runTest(dispatcher) {
        cache.upsertSession(requireNotNull(cache.session("session-b")).copy(remoteProfile = "worker"))
        collectState()
        runCurrent()

        viewModel.onEditorSelectionChange("@", 1, 1)
        testScheduler.advanceTimeBy(120)
        runCurrent()

        val references = viewModel.uiState.value.composer.completion.items
            .filter { it.kind == "session" && it.text.startsWith("@session:`") }
        assertTrue(references.isNotEmpty())
        assertTrue(references.all { it.text.startsWith("@session:`worker/") })
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
        private val composerControlEvents = MutableSharedFlow<SessionComposerControls>(extraBufferCapacity = 4)
        override val composerControls: Flow<SessionComposerControls> = composerControlEvents
        val opened = mutableListOf<String>()
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
        var submitOutcome: GatewaySubmitOutcome = GatewaySubmitOutcome.Accepted
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

        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome {
            submitGate?.await()
            if (failSubmit) error("fixture failure")
            submitted += durableId to text
            cache.session(durableId)?.let { cache.upsertSession(it.copy(status = SessionStatus.Working)) }
            return submitOutcome
        }

        override suspend fun interrupt(durableId: String) {
            interrupted += durableId
        }
    }

    private class DelayedDraftStore : SessionDraftStore {
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
