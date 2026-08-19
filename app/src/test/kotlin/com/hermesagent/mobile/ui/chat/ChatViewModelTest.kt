package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.SessionRehome
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cache = SessionCache().apply {
            upsertSessions(listOf(summary("session-a", 2_000), summary("session-b", 1_000)))
        }
        repository = FakeRepository(cache)
        viewModel = ChatViewModel(cache, repository, clock = { CLOCK })
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
        var created = 0
        var failSubmit = false
        var submitOutcome: GatewaySubmitOutcome = GatewaySubmitOutcome.Accepted

        fun rehome(fromId: String, toId: String) {
            val row = requireNotNull(cache.session(fromId)).copy(id = toId)
            cache.rehomeSession(fromId, row, cache.transcript(fromId))
            check(rehomeEvents.tryEmit(SessionRehome(fromId, toId)))
        }

        override suspend fun refreshSessions() = Unit

        override suspend fun openSession(durableId: String): String {
            opened += durableId
            return durableId
        }

        override suspend fun createSession(): String {
            created++
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

    private companion object {
        const val CLOCK = 1_800_000_000_000L
        fun summary(id: String, at: Long) = SessionSummary(id, "Session $id", "", at)
    }
}
