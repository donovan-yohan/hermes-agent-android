package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.composer.ComposerQueueScope
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.ProfileRouting
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
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
 * Desktop reference at `3ca096de5f8183cb2e0ec23673f294d5978656a3`:
 * `apps/desktop/src/store/profile.ts:437-483` and
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
        // The fan-out covers the launch profile plus every named one.
        assertEquals(listOf(null, "work", "lab"), repository.routing.listProfiles)
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
    fun `an unmoved scope does not ask for a second session list at launch`() = runTest(dispatcher) {
        collectState()
        runCurrent()

        // The connection's own bootstrap refresh already covered these rows.
        assertEquals(emptyList<List<String?>>(), repository.listed.map { it.listProfiles })

        viewModel.selectProfile("work")
        runCurrent()

        assertEquals(listOf(listOf("work")), repository.listed.map { it.listProfiles })
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
        assertNull(repository.routing.activeProfile)
        assertEquals("That profile is no longer available.", subject.uiState.value.notice)
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
        assertNull(subject.uiState.value.notice)
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

    private class FakeRepository : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
        var routing = ProfileRouting()
        val interrupted = mutableListOf<String>()
        val listed = mutableListOf<ProfileRouting>()

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

        override suspend fun openSession(durableId: String): String = durableId

        override suspend fun createSession(workspacePath: String?): String = "created"

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
        assertEquals(listOf(ProfileRouting()), repository.archivedReads)

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

    private companion object {
        const val CLOCK = 1_700_000_000_000L
    }
}
