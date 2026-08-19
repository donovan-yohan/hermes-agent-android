package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.demo.DemoTurnEngine
import com.hermesagent.mobile.data.demo.TurnTiming
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.UserTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Streaming, cancellation and session switching on **virtual time**.
 *
 * `TurnTiming` is injected, so nothing here sleeps: `advanceTimeBy` walks the
 * turn forward deterministically. A test that waited on real delays would be
 * slow and flaky, and would not be able to assert the mid-stream states that
 * matter (a half-arrived turn, a stop between two deltas).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val cache = SessionCache()
    private val timing = TurnTiming(firstDelayMillis = 100, deltaDelayMillis = 10, toolRunMillis = 500)

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cache.upsertSessions(
            listOf(
                summary("s-1", at = 2_000),
                summary("s-2", at = 1_000),
            ),
        )
        viewModel = ChatViewModel(cache, DemoTurnEngine(timing), clock = { CLOCK })
        viewModel.selectSession("s-1")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `submitting appends the user turn immediately and streams the reply`() = runTest(dispatcher) {
        collectState()

        viewModel.setDraft("what is real?")
        viewModel.submit()
        settle()

        // Direct manipulation paints first: the user turn is there before any
        // virtual time has passed.
        assertEquals(listOf("s-1-u1"), cache.transcript("s-1").map { it.id })
        assertEquals("what is real?", (cache.transcript("s-1").single() as UserTurn).text)
        assertEquals("", viewModel.uiState.value.draft)
        assertTrue(viewModel.uiState.value.isStreaming)

        advanceTimeBy(150)
        val partial = cache.transcript("s-1").filterIsInstance<AssistantTurn>().last()
        assertTrue("the tail block must be marked streaming", partial.streaming)
        assertTrue("some text must have arrived", partial.markdown.isNotEmpty())

        advanceUntilIdle()
        val settled = cache.transcript("s-1").filterIsInstance<AssistantTurn>().last()
        assertFalse(settled.streaming)
        assertFalse(viewModel.uiState.value.isStreaming)
        assertTrue(settled.markdown.contains("six Desktop themes"))
    }

    @Test
    fun `a tool run interleaves as scaffolding between two prose blocks`() = runTest(dispatcher) {
        collectState()

        viewModel.setDraft("show me the code")
        viewModel.submit()
        advanceUntilIdle()

        val kinds = cache.transcript("s-1").map {
            when (it) {
                is UserTurn -> "user"
                is AssistantTurn -> "assistant"
                is ToolActivity -> "tool"
            }
        }
        assertEquals(listOf("user", "assistant", "tool", "assistant"), kinds)

        val tool = cache.transcript("s-1").filterIsInstance<ToolActivity>().single()
        assertEquals(ToolState.Done, tool.state)
        assertTrue("the settled row keeps the running row's label", tool.label.startsWith("Read "))
    }

    @Test
    fun `the tool row is visibly running before it settles`() = runTest(dispatcher) {
        collectState()

        viewModel.setDraft("show me the code")
        viewModel.submit()

        // Walk to just after the tool starts but before it finishes.
        advanceTimeBy(timing.firstDelayMillis + 40 * timing.deltaDelayMillis + 1)
        val running = cache.transcript("s-1").filterIsInstance<ToolActivity>().singleOrNull()
        assertEquals(ToolState.Running, running?.state)

        advanceUntilIdle()
        assertEquals(ToolState.Done, cache.transcript("s-1").filterIsInstance<ToolActivity>().single().state)
    }

    @Test
    fun `stop ends the turn, keeps the partial text, and marks it stopped`() = runTest(dispatcher) {
        collectState()

        viewModel.setDraft("what is real?")
        viewModel.submit()
        advanceTimeBy(150)
        val partialLength = cache.transcript("s-1").filterIsInstance<AssistantTurn>().last().markdown.length

        viewModel.stop()
        advanceUntilIdle()

        val settled = cache.transcript("s-1").filterIsInstance<AssistantTurn>().last()
        assertFalse("streaming must clear on stop", settled.streaming)
        assertTrue("the partial reply is kept, not discarded", settled.stopped)
        assertEquals(partialLength, settled.markdown.length)
        assertFalse(viewModel.uiState.value.isStreaming)
    }

    @Test
    fun `stopping does not leave the session marked as working`() = runTest(dispatcher) {
        collectState()
        viewModel.setDraft("hello")
        viewModel.submit()
        advanceTimeBy(150)
        viewModel.stop()
        advanceUntilIdle()

        assertEquals(SessionStatus.Idle, cache.session("s-1")?.status)
    }

    @Test
    fun `a background turn keeps writing to its own session and lands as unread`() = runTest(dispatcher) {
        collectState()

        viewModel.setDraft("hello")
        viewModel.submit()
        advanceTimeBy(150)

        viewModel.selectSession("s-2")
        settle()
        assertFalse("the foreground must not show another session's stream", viewModel.uiState.value.isStreaming)
        assertEquals(1, viewModel.uiState.value.runningCount)
        assertTrue("s-2's transcript stays empty", viewModel.uiState.value.transcript.isEmpty())

        advanceUntilIdle()

        assertEquals(SessionStatus.Unread, cache.session("s-1")?.status)
        assertTrue(cache.transcript("s-1").isNotEmpty())
        assertTrue(cache.transcript("s-2").isEmpty())
    }

    @Test
    fun `selecting an unread session marks it read`() = runTest(dispatcher) {
        collectState()
        cache.upsertSession(summary("s-2", at = 1_000).copy(status = SessionStatus.Unread))

        viewModel.selectSession("s-2")

        assertEquals(SessionStatus.Idle, cache.session("s-2")?.status)
    }

    @Test
    fun `switching sessions drops the draft rather than carrying it over`() = runTest(dispatcher) {
        collectState()
        viewModel.setDraft("half-written")

        viewModel.selectSession("s-2")
        settle()

        assertEquals("", viewModel.uiState.value.draft)
    }

    @Test
    fun `creating a session selects it and starts it empty`() = runTest(dispatcher) {
        collectState()

        val id = viewModel.createSession()
        advanceUntilIdle()

        assertEquals(id, viewModel.uiState.value.activeSession?.id)
        assertTrue(viewModel.uiState.value.transcriptIsEmpty)
        assertEquals("New session", viewModel.uiState.value.activeSession?.title)
    }

    @Test
    fun `a recreated view model creates a new empty transcript without clobbering local session data`() = runTest(dispatcher) {
        val firstId = viewModel.createSession()
        val firstSession = cache.session(firstId)!!.copy(title = "Existing local session", preview = "kept")
        val firstTranscript = UserTurn(id = "$firstId-u1", text = "keep this", atMillis = CLOCK)
        cache.upsertSession(firstSession)
        cache.appendEntry(firstId, firstTranscript)

        val recreated = ChatViewModel(cache, DemoTurnEngine(timing), clock = { CLOCK })
        val secondId = recreated.createSession()

        assertNotEquals("a recreated ViewModel must not reuse a process-cache id", firstId, secondId)
        assertEquals(firstSession, cache.session(firstId))
        assertEquals(listOf(firstTranscript), cache.transcript(firstId))
        assertEquals("New session", cache.session(secondId)?.title)
        assertTrue("the new session cannot inherit another transcript", cache.transcript(secondId).isEmpty())
    }

    @Test
    fun `a new session takes its title from the first prompt`() = runTest(dispatcher) {
        collectState()
        viewModel.createSession()
        viewModel.setDraft("Wire the local forward")
        viewModel.submit()
        advanceUntilIdle()

        assertEquals("Wire the local forward", viewModel.uiState.value.activeSession?.title)
    }

    @Test
    fun `a second submit is refused while the session is already producing`() = runTest(dispatcher) {
        collectState()
        viewModel.setDraft("first")
        viewModel.submit()
        advanceTimeBy(120)

        viewModel.setDraft("second")
        viewModel.submit()
        advanceUntilIdle()
        settle()

        assertEquals(
            "only one user turn may exist for one accepted submit",
            1,
            cache.transcript("s-1").filterIsInstance<UserTurn>().size,
        )
        assertEquals("second", viewModel.uiState.value.draft)
    }

    @Test
    fun `canSend is false while streaming and false on an empty draft`() = runTest(dispatcher) {
        collectState()
        assertFalse(viewModel.uiState.value.canSend)

        viewModel.setDraft("   ")
        settle()
        assertFalse("whitespace is not a message", viewModel.uiState.value.canSend)

        viewModel.setDraft("real")
        settle()
        assertTrue(viewModel.uiState.value.canSend)

        viewModel.submit()
        advanceTimeBy(120)
        viewModel.setDraft("another")
        settle()
        assertFalse("cannot send while the turn runs", viewModel.uiState.value.canSend)
    }

    @Test
    fun `archiving the active session moves focus to the newest live one`() = runTest(dispatcher) {
        collectState()

        viewModel.setArchived("s-1", archived = true)
        advanceUntilIdle()

        assertEquals("s-2", viewModel.uiState.value.activeSession?.id)
        assertTrue(cache.session("s-1")!!.archived)
    }

    @Test
    fun `archiving the active session does not carry its draft into the replacement`() = runTest(dispatcher) {
        collectState()
        viewModel.setDraft("half-written prompt for s-1")
        settle()

        viewModel.setArchived("s-1", archived = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("s-2", state.activeSession?.id)
        assertEquals("the draft belonged to the archived session", "", state.draft)
        assertFalse("an empty draft cannot be sent to the replacement", state.canSend)
    }

    @Test
    fun `archiving the last live session clears the draft along with the foreground`() = runTest(dispatcher) {
        collectState()
        viewModel.setDraft("still typing")
        viewModel.setArchived("s-2", archived = true)
        viewModel.setArchived("s-1", archived = true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("nothing live is left to land on", state.activeSession)
        assertEquals("", state.draft)
    }

    @Test
    fun `restoring the final archived session selects it and makes sending executable`() = runTest(dispatcher) {
        collectState()
        viewModel.setArchived("s-2", archived = true)
        viewModel.setArchived("s-1", archived = true)
        settle()

        viewModel.setDraft("nowhere to send")
        settle()
        assertNull(viewModel.uiState.value.activeSession)
        assertFalse("send must not be offered without a foreground", viewModel.uiState.value.canSend)

        viewModel.setArchived("s-1", archived = false)
        settle()
        assertEquals("s-1", viewModel.uiState.value.activeSession?.id)

        viewModel.setDraft("continue here")
        settle()
        assertTrue(viewModel.uiState.value.canSend)
        viewModel.submit()

        assertEquals("continue here", (cache.transcript("s-1").last() as UserTurn).text)
    }

    @Test
    fun `archiving a session the user is not in leaves the draft alone`() = runTest(dispatcher) {
        collectState()
        viewModel.setDraft("keep me")

        viewModel.setArchived("s-2", archived = true)
        advanceUntilIdle()

        assertEquals("s-1", viewModel.uiState.value.activeSession?.id)
        assertEquals("keep me", viewModel.uiState.value.draft)
    }

    @Test
    fun `rename ignores blank titles`() = runTest(dispatcher) {
        collectState()
        viewModel.renameSession("s-1", "  ")
        assertEquals("Session s-1", cache.session("s-1")?.title)

        viewModel.renameSession("s-1", "  Tunnel work  ")
        assertEquals("Tunnel work", cache.session("s-1")?.title)
    }

    /**
     * `uiState` is a `WhileSubscribed` flow built with `combine`: without a
     * live collector it never recomputes, and even with one the recomputation
     * is a dispatched task. Every test starts a collector and then drains the
     * queue, and every assertion on `uiState` after a state change is preceded
     * by [settle].
     */
    private fun TestScope.collectState() {
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
    }

    /** Let `combine` recompute without letting virtual time move. */
    private fun TestScope.settle() = runCurrent()

    private fun summary(id: String, at: Long) =
        SessionSummary(id = id, title = "Session $id", preview = "", lastActiveAtMillis = at)

    private companion object {
        const val CLOCK = 1_755_600_000_000L
    }
}
