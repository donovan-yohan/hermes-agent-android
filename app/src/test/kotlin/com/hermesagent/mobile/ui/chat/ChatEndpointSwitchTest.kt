package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.prefs.ComposerControlsScope
import com.hermesagent.mobile.data.prefs.ComposerControlsStore
import com.hermesagent.mobile.data.prefs.NewDraftComposerPreference
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What the chat surface does when the device changes gateway.
 *
 * The endpoint identity it watches is the one the preference store already
 * publishes, so this covers both ways it can change: the connection switcher,
 * and editing the active connection's address. Either way the session on
 * screen belonged to the machine we left, and two gateways can hand out the
 * same durable id — so the selection is dropped, not re-pointed, and the new
 * endpoint's own most recent session is what this lands on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatEndpointSwitchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun useVirtualMain() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun releaseMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun `changing endpoint drops the open session and lands on the new endpoint's most recent one`() =
        runTest(dispatcher) {
            val cache = SessionCache()
            val store = MutableScopeStore(ComposerControlsScope("remote:https://alpha.test", "default"))
            val subject = ChatViewModel(cache, FixtureRepository(), composerControlsStore = store)
            backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()
            advanceUntilIdle()
            cache.upsertSession(session("alpha-session", lastActiveAtMillis = 10L))
            advanceUntilIdle()
            assertEquals("alpha-session", subject.uiState.value.activeSession?.id)

            // What a switch does to the cache, then to the active row.
            cache.resetForEndpointSwitch()
            store.scope.value = ComposerControlsScope("remote:https://beta.test", "default")
            advanceUntilIdle()

            assertNull("the machine we left owns that session", subject.uiState.value.activeSession)

            cache.upsertSession(session("beta-older", lastActiveAtMillis = 20L))
            cache.upsertSession(session("beta-newest", lastActiveAtMillis = 30L))
            advanceUntilIdle()

            assertEquals("beta-newest", subject.uiState.value.activeSession?.id)
        }

    @Test
    fun `changing endpoint clears the search and the project drill-in it belonged to`() = runTest(dispatcher) {
        val cache = SessionCache()
        val store = MutableScopeStore(ComposerControlsScope("remote:https://alpha.test", "default"))
        val subject = ChatViewModel(cache, FixtureRepository(), composerControlsStore = store)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()
        advanceUntilIdle()
        subject.setQuery("alpha")
        advanceUntilIdle()
        assertEquals("alpha", subject.uiState.value.query)

        store.scope.value = ComposerControlsScope("ssh:demo-user@demo-host:22", "default")
        advanceUntilIdle()

        assertEquals("", subject.uiState.value.query)
        assertNull(subject.uiState.value.selectedProject)
    }

    @Test
    fun `a profile change on the same endpoint keeps the session on screen`() = runTest(dispatcher) {
        val cache = SessionCache()
        val store = MutableScopeStore(ComposerControlsScope("remote:https://alpha.test", "default"))
        val subject = ChatViewModel(cache, FixtureRepository(), composerControlsStore = store)
        backgroundScope.launch { subject.uiState.collect { } }
        advanceUntilIdle()
        cache.upsertSession(session("alpha-session", lastActiveAtMillis = 10L))
        advanceUntilIdle()
        assertEquals("alpha-session", subject.uiState.value.activeSession?.id)

        store.scope.value = ComposerControlsScope("remote:https://alpha.test", "review")
        advanceUntilIdle()

        assertEquals(
            "the same machine's session is still the one you were reading",
            "alpha-session",
            subject.uiState.value.activeSession?.id,
        )
    }

    private fun session(id: String, lastActiveAtMillis: Long) = SessionSummary(
        id = id,
        title = id,
        preview = "",
        lastActiveAtMillis = lastActiveAtMillis,
        status = SessionStatus.Idle,
    )

    private class MutableScopeStore(initial: ComposerControlsScope) : ComposerControlsStore {
        val scope = MutableStateFlow(initial)
        override val activeScope: Flow<ComposerControlsScope> = scope
        override fun preference(scope: ComposerControlsScope): Flow<NewDraftComposerPreference?> = flowOf(null)
        override suspend fun saveManual(scope: ComposerControlsScope, preference: NewDraftComposerPreference) = Unit
        override suspend fun clearManual(scope: ComposerControlsScope) = Unit
    }

    /** Connected, and otherwise inert: this test is about what the surface forgets. */
    private class FixtureRepository : GatewaySessionRepository {
        override val connectionState = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        override val pendingInputs = MutableStateFlow(emptyMap<PendingInputKey, PendingInputRequest>())
        override suspend fun refreshSessions() = Unit
        override suspend fun openSession(durableId: String): String = durableId
        override suspend fun createSession(workspacePath: String?): String = "created"
        override suspend fun submit(durableId: String, text: String) = GatewaySubmitOutcome.Accepted
        override suspend fun interrupt(durableId: String) = Unit
    }
}
