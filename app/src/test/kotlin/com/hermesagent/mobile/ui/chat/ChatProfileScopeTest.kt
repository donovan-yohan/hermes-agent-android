package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.composer.ComposerQueueScope
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.ProfileRouting
import com.hermesagent.mobile.data.gateway.ProjectCreateOutcome
import com.hermesagent.mobile.data.prefs.ProfileScopeStore
import com.hermesagent.mobile.data.profiles.GatewayProfileConnectionState
import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.data.profiles.ProfileRepository
import com.hermesagent.mobile.data.profiles.ProfileRosterState
import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The active profile scope as this app's own state: what it shows, what it
 * routes, and what it must never disturb.
 *
 * Desktop reference at `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`:
 * `apps/desktop/src/store/profile.ts:450-496` and
 * `apps/desktop/src/app/chat/sidebar/profile-scope.ts:5-13`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatProfileScopeTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var cache: SessionCache
    private lateinit var repository: FakeRepository
    private lateinit var profiles: FakeProfileRepository
    private lateinit var scopeStore: RecordingProfileScopeStore
    private lateinit var queueScopes: MutableList<ComposerQueueScope>
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cache = SessionCache().apply {
            upsertSessions(
                listOf(
                    row("home-row", 3_000, null),
                    row("work-row", 2_000, "work"),
                    row("lab-row", 1_000, "lab"),
                ),
            )
        }
        repository = FakeRepository()
        profiles = FakeProfileRepository(
            listOf(
                HermesProfile(name = "default", isDefault = true),
                HermesProfile(name = "work"),
                HermesProfile(name = "lab"),
            ),
        )
        scopeStore = RecordingProfileScopeStore()
        queueScopes = mutableListOf()
        viewModel = ChatViewModel(
            cache = cache,
            repository = repository,
            profileScopeStore = scopeStore,
            profileRepository = profiles,
            switchComposerQueueScope = { queueScopes += it },
            clock = { CLOCK },
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the default scope shows only the Gateway's own rows`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        assertEquals(listOf("home-row"), viewModel.uiState.value.visibleSessionIds())
        assertEquals(listOf("default", "work", "lab"), viewModel.uiState.value.profileRail.profiles.map { it.name })
    }

    @Test
    fun `picking a profile scopes the list and routes new work there`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.selectProfile("work")
        runCurrent()

        assertEquals(listOf("work-row"), viewModel.uiState.value.visibleSessionIds())
        assertEquals("work", repository.routing.activeProfile)
        assertEquals(listOf("work"), repository.routing.listProfiles)
        assertEquals(ProfileScope(activeProfile = "work"), scopeStore.saved.last())
    }

    @Test
    fun `the unified view shows every profile and leaves the active one alone`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.selectProfile("work")
        runCurrent()

        viewModel.showAllProfiles()
        runCurrent()

        assertEquals(
            listOf("home-row", "work-row", "lab-row"),
            viewModel.uiState.value.visibleSessionIds(),
        )
        // The fan-out covers literal default plus every named profile.
        assertEquals(listOf("default", "work", "lab"), repository.routing.listProfiles)
        // Leaving it returns to the profile that was active, not to default.
        assertEquals("work", viewModel.uiState.value.profileRail.scope.activeProfile)
        assertTrue(viewModel.uiState.value.profileRail.scope.isAll)
    }

    @Test
    fun `switching scope never interrupts another profile's running turn`() = runTest(dispatcher) {
        cache.upsertSession(
            requireNotNull(cache.session("home-row")).copy(status = SessionStatus.Working),
        )
        collectState()
        runCurrent()
        assertEquals("home-row", viewModel.uiState.value.activeSession?.id)

        viewModel.selectProfile("lab")
        runCurrent()

        // Nothing was stopped, and the running turn keeps its own session.
        assertEquals(emptyList<String>(), repository.interrupted)
        assertEquals(SessionStatus.Working, cache.session("home-row")?.status)
        // The reader lands on a fresh draft in the profile just picked rather
        // than staying inside a session that belongs to another one.
        assertNull(viewModel.uiState.value.activeSession)
        assertEquals(listOf("lab-row"), viewModel.uiState.value.visibleSessionIds())
    }

    @Test
    fun `re-picking the profile already active leaves the open session alone`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.selectProfile("work")
        runCurrent()
        viewModel.selectSession("work-row")
        runCurrent()

        viewModel.selectProfile("work")
        runCurrent()

        assertEquals("work-row", viewModel.uiState.value.activeSession?.id)
    }

    @Test
    fun `queued text cannot leak across Hermes profiles`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        val onDefault = queueScopes.last()

        viewModel.selectProfile("work")
        runCurrent()
        val onWork = queueScopes.last()

        viewModel.selectProfile("lab")
        runCurrent()

        assertNotEquals(onDefault, onWork)
        assertNotEquals(onWork, queueScopes.last())
        // Returning to the Gateway's own profile returns to the store an
        // install that never used the rail already has.
        viewModel.selectProfile("default")
        runCurrent()
        assertEquals(onDefault, queueScopes.last())
    }

    @Test
    fun `the explicit default scope refreshes separately from launch bootstrap`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        // Omitted bootstrap may have read a named launch, not literal default.
        assertEquals(listOf(listOf("default")), repository.listed.map { it.listProfiles })

        viewModel.selectProfile("work")
        runCurrent()

        assertEquals(listOf(listOf("default"), listOf("work")), repository.listed.map { it.listProfiles })
    }

    @Test
    fun `a saved scope the Gateway no longer has falls back to its own profile`() = runTest(dispatcher) {
        // The Gateway does not refuse an unresolvable profile: it falls back to
        // the launch handle (`tui_gateway/server.py:1556-1571,1599-1613`), so a
        // stale scope would quietly list the launch profile's rows under a name
        // that no longer exists. Once the roster has actually answered, it goes.
        val stale = RecordingProfileScopeStore(ProfileScope(activeProfile = "retired"))
        val subject = ChatViewModel(
            cache = cache,
            repository = repository,
            profileScopeStore = stale,
            profileRepository = profiles,
            clock = { CLOCK },
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        assertEquals(DEFAULT_PROFILE, subject.uiState.value.profileRail.scope.activeProfile)
        assertEquals("default", repository.routing.activeProfile)
        assertEquals("That profile is no longer available.", subject.uiState.value.notice?.text)
    }

    @Test
    fun `a scope the roster has not answered for yet is left alone`() = runTest(dispatcher) {
        // A roster that never loads must not cost the user their scope — that
        // is what keeps the rail's way out reachable on a Gateway whose
        // profiles.list never answers.
        val unanswered = object : ProfileRepository {
            override val roster = MutableStateFlow(ProfileRosterState())
            override suspend fun refreshProfiles(): Boolean = false
        }
        val subject = ChatViewModel(
            cache = cache,
            repository = repository,
            profileScopeStore = RecordingProfileScopeStore(ProfileScope(activeProfile = "retired")),
            profileRepository = unanswered,
            clock = { CLOCK },
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        assertEquals("retired", subject.uiState.value.profileRail.scope.activeProfile)
        assertNull(subject.uiState.value.notice?.text)
    }

    @Test
    fun `the unified view keeps browsing when its target profile retires`() = runTest(dispatcher) {
        val stale = RecordingProfileScopeStore(
            ProfileScope(activeProfile = "retired", showAllProfiles = true),
        )
        val subject = ChatViewModel(
            cache = cache,
            repository = repository,
            profileScopeStore = stale,
            profileRepository = profiles,
            clock = { CLOCK },
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        // Only the profile new work targets was stale, not the choice to browse.
        assertTrue(subject.uiState.value.profileRail.scope.isAll)
        assertEquals(DEFAULT_PROFILE, subject.uiState.value.profileRail.scope.activeProfile)
    }

    @Test
    fun `the saved scope is restored before anything is listed`() = runTest(dispatcher) {
        val restored = RecordingProfileScopeStore(ProfileScope(activeProfile = "lab"))
        val subject = ChatViewModel(
            cache = cache,
            repository = repository,
            profileScopeStore = restored,
            profileRepository = profiles,
            clock = { CLOCK },
        )
        backgroundScope.launch { subject.uiState.collect { } }
        runCurrent()

        assertEquals("lab", subject.uiState.value.profileRail.scope.activeProfile)
        assertEquals(listOf("lab-row"), subject.uiState.value.visibleSessionIds())
    }

    private fun ChatUiState.visibleSessionIds(): List<String> =
        sessionRows.filterIsInstance<SessionListRow.Row>().map { it.session.id }

    private fun TestScope.collectState() {
        backgroundScope.launch { viewModel.uiState.collect { } }
    }

    private fun row(id: String, at: Long, profile: String?) = SessionSummary(
        id = id,
        title = id,
        preview = "",
        lastActiveAtMillis = at,
        remoteProfile = profile,
    )

    private class RecordingProfileScopeStore(
        initial: ProfileScope = ProfileScope(),
    ) : ProfileScopeStore {
        val saved = mutableListOf<ProfileScope>()
        private val state = MutableStateFlow(initial)
        override val profileScope: Flow<ProfileScope> = state

        override suspend fun saveProfileScope(scope: ProfileScope) {
            saved += scope
            state.value = scope
        }
    }

    private class FakeProfileRepository(rows: List<HermesProfile>) : ProfileRepository {
        override val roster = MutableStateFlow(ProfileRosterState(rows, loaded = true))
        val connectionEdges = mutableListOf<GatewayProfileConnectionState>()

        override suspend fun refreshProfiles(): Boolean = true

        override fun connectionChanged(state: GatewayProfileConnectionState) {
            connectionEdges += state
        }
    }

    @Test
    fun `failed ordinary create retains recent owner queue scope`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        repository.onRecentOpened = { cache.upsertSession(row("recent", CLOCK, "work")) }
        viewModel.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { }
        runCurrent()
        repository.sessionCreateFailure = true
        viewModel.createSession(); runCurrent()
        assertEquals("recent", viewModel.uiState.value.activeSessionId)
        assertEquals(ComposerQueueScope.forConnectionProfile("transient", "default", "work"), queueScopes.last())
    }

    @Test
    fun `disconnected ordinary create retains recent owner queue scope`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.selectSession("work-row"); runCurrent()
        repository.connectionState.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        viewModel.createSession(); runCurrent()
        assertEquals("work-row", viewModel.uiState.value.activeSessionId)
        assertEquals(ComposerQueueScope.forConnectionProfile("transient", "default", "work"), queueScopes.last())
    }

    @Test
    fun `stale ordinary create retains newer recent owner queue scope`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        val gate = CompletableDeferred<Unit>()
        repository.sessionCreateGate = gate
        viewModel.createSession(); runCurrent()
        viewModel.selectSession("work-row"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("work-row", viewModel.uiState.value.activeSessionId)
        assertEquals(ComposerQueueScope.forConnectionProfile("transient", "default", "work"), queueScopes.last())
    }

    @Test
    fun `create snapshots profile before launch and cannot adopt after switching scope`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        viewModel.selectProfile("work")
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        repository.sessionCreateGate = gate
        viewModel.createSession()
        // No scheduler drain: profile changes before the create coroutine starts.
        viewModel.selectProfile("lab")
        // Model the repository's independently updated routing while the launch
        // is still queued (the production repository is process-scoped).
        repository.setProfileRouting(ProfileRouting(activeProfile = "lab"))
        runCurrent()
        assertEquals("work", repository.createdProfile)
        assertNull(viewModel.uiState.value.activeSession)
        viewModel.setDraft("lab draft")
        gate.complete(Unit)
        repository.sessionCreateJob!!.join()
        runCurrent()
        assertNull(viewModel.uiState.value.activeSession)
        assertEquals("lab", viewModel.uiState.value.profileRail.scope.activeProfile)
        assertEquals("lab draft", viewModel.uiState.value.draft)
    }

    @Test
    fun `recent bot session resumes explicit owner and never creates or grants canonical capability`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        repository.onRecentOpened = { cache.upsertSession(row("recent", CLOCK, "work")) }
        val results = mutableListOf<Boolean>()
        viewModel.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { results += it }
        runCurrent()
        assertEquals("work" to "recent", repository.openedRecent)
        assertEquals("recent", viewModel.uiState.value.activeSession?.id)
        assertEquals(listOf(true), results)
        assertTrue(!viewModel.uiState.value.botChat)
        assertNull(repository.createdProfile)
    }

    @Test
    fun `recent bot resume cannot take over newer navigation`() = runTest(dispatcher) {
        collectState(); runCurrent()
        val gate = CompletableDeferred<Unit>(); repository.recentOpenGate = gate
        val results = mutableListOf<Boolean>()
        viewModel.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { results += it }
        runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.setDraft("newer draft")
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(false), results)
        assertNull(viewModel.uiState.value.activeSession)
        assertEquals("newer draft", viewModel.uiState.value.draft)
    }

    @Test
    fun `recent bot stale endpoint cannot resume`() = runTest(dispatcher) {
        collectState(); runCurrent()
        val results = mutableListOf<Boolean>()
        viewModel.openBotRecentSession("work", "recent", cache.endpointGeneration.value - 1) { results += it }
        runCurrent()
        assertEquals(listOf(false), results)
        assertNull(repository.openedRecent)
    }

    @Test
    fun `bot ordinary conversation binds queue to owner instead of browsing profile`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { }
        runCurrent()
        assertEquals(com.hermesagent.mobile.data.composer.ComposerQueueScope.forConnectionProfile(
            "transient", "default", "work"), queueScopes.last())
        assertEquals("lab", viewModel.uiState.value.profileRail.scope.activeProfile)
    }

    @Test
    fun `opening idle recent session preserves ready queue without sending`() = runTest(dispatcher) {
        val store = com.hermesagent.mobile.data.composer.TransientComposerQueueStore()
        val sends = mutableListOf<String>()
        val controller = com.hermesagent.mobile.data.composer.ComposerQueueController(store,
            object : com.hermesagent.mobile.data.composer.ComposerQueueSubmitter {
                override suspend fun submitQueued(durableSessionId: String, text: String): com.hermesagent.mobile.data.composer.QueueSubmissionOutcome {
                    sends += text
                    return com.hermesagent.mobile.data.composer.QueueSubmissionOutcome.Accepted
                }
            })
        val subject = ChatViewModel(cache, repository, profileRepository = profiles,
            composerQueueController = controller, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }; runCurrent()
        controller.enqueue("recent", "do not send on open")
        repository.onRecentOpened = { cache.upsertSession(row("recent", CLOCK, "work")) }
        subject.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { }
        runCurrent()
        assertTrue(sends.isEmpty())
        assertEquals(1, controller.queue("recent").size)
        repository.connectionState.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected); runCurrent()
        repository.connectionState.value = GatewayConnectionState(GatewayConnectionStatus.Connected); runCurrent()
        assertTrue(sends.isEmpty())
    }

    @Test
    fun `compressed recent session migrates stored draft to canonical tip`() = runTest(dispatcher) {
        val drafts = com.hermesagent.mobile.data.draft.TransientSessionDraftStore()
        drafts.replace("recent", "keep my work")
        val subject = ChatViewModel(cache, repository, profileRepository = profiles, draftStore = drafts, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }; runCurrent()
        repository.recentResult = "tip"
        repository.onRecentOpened = { cache.upsertSession(row("tip", CLOCK, "work")) }
        subject.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { }
        runCurrent()
        assertEquals("tip", subject.uiState.value.activeSessionId)
        assertEquals("keep my work", subject.uiState.value.draft)
    }

    @Test
    fun `revoked shell lifetime leaves foreground and draft untouched`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectSession("lab-row"); runCurrent()
        viewModel.setDraft("preserve foreground")
        val before = viewModel.uiState.value.activeSessionId
        val gate = CompletableDeferred<Unit>(); repository.recentOpenGate = gate
        var current = true
        val result = mutableListOf<Boolean>()
        viewModel.openBotRecentSession("work", "recent", cache.endpointGeneration.value, { current }) { result += it }
        runCurrent(); current = false; gate.complete(Unit); runCurrent()
        assertEquals(before, viewModel.uiState.value.activeSessionId)
        assertEquals("preserve foreground", viewModel.uiState.value.draft)
        assertEquals(listOf(false), result)
    }

    @Test
    fun `ordinary session rail reopening restores authoritative queue owner`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.selectSession("work-row"); runCurrent()
        assertEquals(ComposerQueueScope.forConnectionProfile("transient", "default", "work"), queueScopes.last())
        viewModel.selectSession("lab-row"); runCurrent()
        assertEquals(ComposerQueueScope.forConnectionProfile("transient", "default", "lab"), queueScopes.last())
        viewModel.selectSession("work-row"); runCurrent()
        assertEquals(ComposerQueueScope.forConnectionProfile("transient", "default", "work"), queueScopes.last())
        assertEquals("lab", viewModel.uiState.value.profileRail.scope.activeProfile)
    }

    @Test
    fun `navigation during compressed migration cannot rebind newer composer`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val storage = com.hermesagent.mobile.data.draft.TransientSessionDraftStore()
        storage.replace("recent", "old draft")
        val held = object : com.hermesagent.mobile.data.draft.SessionDraftStore by storage {
            override suspend fun migrateIfDestinationEmpty(fromDurableId: String, toDurableId: String, sourceText: String?): String? {
                entered.complete(Unit); release.await()
                return storage.migrateIfDestinationEmpty(fromDurableId, toDurableId, sourceText)
            }
        }
        val subject = ChatViewModel(cache, repository, profileRepository = profiles,
            draftStore = held, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }; runCurrent()
        repository.recentResult = "tip"
        repository.onRecentOpened = { cache.upsertSession(row("tip", CLOCK, "work")) }
        val outcomes = mutableListOf<Boolean>()
        subject.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { outcomes += it }
        runCurrent(); assertTrue(entered.isCompleted)
        subject.selectSession("lab-row"); runCurrent()
        val before = subject.uiState.value.composer
        release.complete(Unit); runCurrent()
        assertEquals("lab-row", subject.uiState.value.activeSessionId)
        assertEquals(before, subject.uiState.value.composer)
        assertEquals(listOf(false), outcomes)
    }

    @Test
    fun `obsolete queue migration cannot cancel another sessions debounced draft save`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val queue = com.hermesagent.mobile.data.composer.TransientComposerQueueStore()
        val held = object : com.hermesagent.mobile.data.composer.ComposerQueueStore by queue {
            override suspend fun migrate(fromDurableId: String, toDurableId: String): com.hermesagent.mobile.data.composer.ComposerQueueMutation {
                entered.complete(Unit); release.await()
                return queue.migrate(fromDurableId, toDurableId)
            }
        }
        val controller = com.hermesagent.mobile.data.composer.ComposerQueueController(held,
            object : com.hermesagent.mobile.data.composer.ComposerQueueSubmitter {
                override suspend fun submitQueued(durableSessionId: String, text: String) =
                    com.hermesagent.mobile.data.composer.QueueSubmissionOutcome.Accepted
            })
        val drafts = com.hermesagent.mobile.data.draft.TransientSessionDraftStore()
        val subject = ChatViewModel(cache, repository, profileRepository = profiles,
            composerQueueController = controller, draftStore = drafts, clock = { CLOCK })
        backgroundScope.launch { subject.uiState.collect { } }; runCurrent()
        repository.recentResult = "tip"
        repository.onRecentOpened = { cache.upsertSession(row("tip", CLOCK, "work")) }
        val outcomes = mutableListOf<Boolean>()
        subject.openBotRecentSession("work", "recent", cache.endpointGeneration.value) { outcomes += it }
        runCurrent(); assertTrue(entered.isCompleted)
        subject.selectSession("lab-row"); runCurrent()
        subject.setDraft("persist after old migration")
        release.complete(Unit); runCurrent()
        advanceTimeBy(401); runCurrent()
        assertEquals("persist after old migration", drafts.drafts.first()["lab-row"])
        assertEquals(listOf(false), outcomes)
    }

    @Test
    fun `bot side chat uses explicit roster owner without inherited project or model`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        repository.onSessionCreated = { cache.upsertSession(row("created", CLOCK, "work")) }
        val results = mutableListOf<Boolean>()
        viewModel.newBotSession("work", cache.endpointGeneration.value) { results += it }
        runCurrent()
        assertEquals("work", repository.createdProfile)
        assertNull(repository.createdWorkspace)
        assertNull(repository.createdOverrides)
        assertEquals("created", viewModel.uiState.value.activeSession?.id)
        assertEquals(listOf(true), results)
        assertEquals("lab", viewModel.uiState.value.profileRail.scope.activeProfile)
    }

    @Test
    fun `bot side chat completion cannot hijack newer profile navigation`() = runTest(dispatcher) {
        collectState(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        repository.sessionCreateGate = gate
        val results = mutableListOf<Boolean>()
        viewModel.newBotSession("work", cache.endpointGeneration.value) { results += it }
        runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.setDraft("newer draft")
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(false), results)
        assertNull(viewModel.uiState.value.activeSession)
        assertEquals("newer draft", viewModel.uiState.value.draft)
    }

    @Test
    fun `stale bot endpoint and unknown profile create nothing`() = runTest(dispatcher) {
        collectState(); runCurrent()
        val results = mutableListOf<Boolean>()
        viewModel.newBotSession("work", cache.endpointGeneration.value - 1) { results += it }
        viewModel.newBotSession("missing", cache.endpointGeneration.value) { results += it }
        runCurrent()
        assertEquals(listOf(false, false), results)
        assertNull(repository.createdProfile)
    }

    @Test
    fun `new bot ordinary conversation binds queue to owner instead of browsing profile`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.newBotSession("work", cache.endpointGeneration.value) { }
        runCurrent()
        assertEquals(com.hermesagent.mobile.data.composer.ComposerQueueScope.forConnectionProfile(
            "transient", "default", "work"), queueScopes.last())
        assertEquals("lab", viewModel.uiState.value.profileRail.scope.activeProfile)
    }

    @Test
    fun `new bot revoked shell lifetime leaves foreground and draft untouched`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectSession("lab-row"); runCurrent()
        viewModel.setDraft("preserve foreground")
        val before = viewModel.uiState.value.activeSessionId
        val gate = CompletableDeferred<Unit>(); repository.sessionCreateGate = gate
        var current = true
        val result = mutableListOf<Boolean>()
        viewModel.newBotSession("work", cache.endpointGeneration.value, { current }) { result += it }
        runCurrent(); current = false; gate.complete(Unit); runCurrent()
        assertEquals(before, viewModel.uiState.value.activeSessionId)
        assertEquals("preserve foreground", viewModel.uiState.value.draft)
        assertEquals(listOf(false), result)
    }

    @Test
    fun `failed ordinary create preserves open conversation queue owner`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.selectSession("work-row"); runCurrent()
        viewModel.setDraft("keep work draft")
        val scopesBefore = queueScopes.toList()
        repository.onSessionCreated = { error("create refused") }

        viewModel.createSession(); runCurrent()

        assertEquals("work-row", viewModel.uiState.value.activeSessionId)
        assertEquals("keep work draft", viewModel.uiState.value.draft)
        assertEquals(scopesBefore, queueScopes)
        assertEquals("lab", repository.createdProfile)
    }

    @Test
    fun `disconnected ordinary create preserves open conversation queue owner`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.selectSession("work-row"); runCurrent()
        repository.connectionState.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        viewModel.setDraft("offline work draft")
        runCurrent()
        val scopesBefore = queueScopes.toList()
        val composerBefore = viewModel.uiState.value.composer

        viewModel.createSession(); runCurrent()

        assertEquals(scopesBefore, queueScopes)
        assertEquals(composerBefore, viewModel.uiState.value.composer)
        assertEquals("work-row", viewModel.uiState.value.activeSessionId)
        assertEquals("offline work draft", viewModel.uiState.value.draft)
        assertNull(repository.createdProfile)
    }

    @Test
    fun `pending and stale ordinary create preserve current queue owner until accepted rehome`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.selectSession("work-row"); runCurrent()
        viewModel.setDraft("work draft")
        val scopesBefore = queueScopes.toList()
        val gate = CompletableDeferred<Unit>()
        repository.sessionCreateGate = gate
        repository.onSessionCreated = { cache.upsertSession(row("created", 4_000, "lab")) }

        viewModel.createSession(); runCurrent()
        assertEquals(scopesBefore, queueScopes)
        assertEquals("work-row", viewModel.uiState.value.activeSessionId)
        // Same-row navigation revokes creation without replacing its owner.
        viewModel.selectSession("work-row"); runCurrent()
        gate.complete(Unit); runCurrent()

        assertEquals(scopesBefore, queueScopes)
        assertEquals("work-row", viewModel.uiState.value.activeSessionId)
        assertEquals("work draft", viewModel.uiState.value.draft)
        assertEquals("lab", cache.session("created")?.remoteProfile)
    }

    @Test
    fun `successful ordinary create rehomes queue to authoritative new row owner`() = runTest(dispatcher) {
        collectState(); runCurrent()
        viewModel.selectProfile("lab"); runCurrent()
        viewModel.selectSession("work-row"); runCurrent()
        repository.onSessionCreated = { cache.upsertSession(row("created", 4_000, "lab")) }

        viewModel.createSession(); runCurrent()

        assertEquals("created", viewModel.uiState.value.activeSessionId)
        assertEquals(ComposerQueueScope.forConnectionProfile("transient", "default", "lab"), queueScopes.last())
    }

    private class FakeRepository : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        var routing = ProfileRouting()
        val interrupted = mutableListOf<String>()
        val listed = mutableListOf<ProfileRouting>()
        var createGate: CompletableDeferred<Unit>? = null
        var createOutcome = ProjectCreateOutcome("project-created", catalogRefreshed = true)

        override fun setProfileRouting(routing: ProfileRouting) {
            this.routing = routing
        }

        override suspend fun refreshSessions() {
            listed += routing
        }

        /** Every routing the archived pool was read under, in order. */
        val archivedReads = mutableListOf<ProfileRouting>()

        override suspend fun loadArchivedSessions() {
            archivedReads += routing
        }

        var recentResult: String? = null
        var onRecentOpened: () -> Unit = {}
        var openedRecent: Pair<String, String>? = null
        var recentOpenGate: CompletableDeferred<Unit>? = null
        override suspend fun openSessionAtEndpoint(durableId: String, profile: String, expectedEndpointGeneration: Long): String {
            openedRecent = profile to durableId
            recentOpenGate?.await()
            onRecentOpened()
            return recentResult ?: durableId
        }
        override suspend fun openSession(durableId: String): String = durableId
        override suspend fun openSession(durableId: String, profile: String): String = openSession(durableId)

        var sessionCreateGate: CompletableDeferred<Unit>? = null
        var sessionCreateJob: kotlinx.coroutines.Job? = null
        var onSessionCreated: () -> Unit = {}
        var createdWorkspace: String? = null
        var createdOverrides: com.hermesagent.mobile.data.composer.NewSessionComposerOverrides? = null
        var sessionCreateFailure = false
        var createdProfile: String? = null
        override suspend fun createSession(workspacePath: String?): String {
            createdProfile = routing.activeProfile
            sessionCreateJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            sessionCreateGate?.await()
            return "created"
        }
        override suspend fun createSessionAtEndpoint(
            workspacePath: String?,
            overrides: com.hermesagent.mobile.data.composer.NewSessionComposerOverrides?,
            profile: String?,
            expectedEndpointGeneration: Long,
        ): String {
            createdProfile = profile
            createdWorkspace = workspacePath
            createdOverrides = overrides
            sessionCreateJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            sessionCreateGate?.await()
            check(!sessionCreateFailure)
            onSessionCreated()
            return "created"
        }

        override suspend fun createProject(name: String, folderPath: String): ProjectCreateOutcome {
            createGate?.await()
            return createOutcome
        }

        override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome =
            GatewaySubmitOutcome.Accepted

        override suspend fun interrupt(durableId: String) {
            interrupted += durableId
        }
    }

    /**
     * The archived pool is one profile scope's set exactly as the live list is,
     * and only the live list is re-listed on a scope change. Without a re-read
     * the Archived view keeps showing the scope the reader just left — or
     * `Nothing archived`, which is then false about both — until the filter is
     * toggled off and on again.
     */
    @Test
    fun `changing the profile scope re-reads the archived pool under the new scope`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.setArchivedVisible(true)
        runCurrent()
        assertEquals(listOf(ProfileRouting(activeProfile = "default", listProfiles = listOf("default"))), repository.archivedReads)

        viewModel.selectProfile("work")
        runCurrent()

        assertEquals(2, repository.archivedReads.size)
        assertEquals("work", repository.archivedReads.last().activeProfile)
        assertEquals(ArchivedPoolState.Loaded, viewModel.uiState.value.archivedPool)
    }

    /** A scope change with the filter off asks for nothing: nobody is looking. */
    @Test
    fun `changing the profile scope reads no archived pool nobody is looking at`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        viewModel.selectProfile("work")
        runCurrent()

        assertEquals(emptyList<ProfileRouting>(), repository.archivedReads)
        assertEquals(ArchivedPoolState.Idle, viewModel.uiState.value.archivedPool)
    }

    @Test
    fun `stale profile creation is silently ignored after routing changes`() = runTest(dispatcher) {
        collectState()
        runCurrent()
        repository.createGate = CompletableDeferred()
        viewModel.selectProfile("work")
        runCurrent()
        viewModel.createProject("Demo", "/srv/demo")
        runCurrent()
        viewModel.selectProfile("lab")
        runCurrent()
        repository.createOutcome = ProjectCreateOutcome("project-a", catalogRefreshed = true, scopeCurrent = false)
        repository.createGate?.complete(Unit)
        runCurrent()

        assertEquals("lab", repository.routing.activeProfile)
        assertNull(viewModel.uiState.value.selectedProject)
        assertNull(viewModel.uiState.value.notice)
    }

    private companion object {
        const val CLOCK = 1_700_000_000_000L
    }
}
