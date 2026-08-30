package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.gateway.GatewayRpcClient
import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.LiveGatewaySessionRepository
import com.hermesagent.mobile.data.gateway.PendingInputAction
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.PendingInputResponse
import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Answering an approval from the shade.
 *
 * The three outcomes are the whole contract, and each of them means something
 * different to the person holding the phone: it worked, somebody else already
 * answered it, or this app can no longer answer it at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShadeApprovalTest {

    @Test
    fun `a resolved approval withdraws its notification`() = runTest {
        val repository = FakeRepository(PendingInputResponse.Resolved)
        val surface = RecordingNotificationSurface()

        respondFromShade(repository, surface, KEY, "chat", CHOICE_APPROVE)

        assertEquals(listOf(KEY to CHOICE_APPROVE), repository.answered)
        assertEquals(listOf(NotificationKind.Approval to "chat"), surface.cleared)
        assertEquals(emptyList<String>(), surface.degraded)
    }

    @Test
    fun `an approval answered somewhere else is withdrawn without a word`() = runTest {
        // `approval.respond` reports `{resolved: 0}` and no status, which the
        // repository reads as resolved. Nothing was pending, so nothing is owed
        // to the user beyond taking the notification away.
        val repository = FakeRepository(PendingInputResponse.Resolved)
        val surface = RecordingNotificationSurface()

        respondFromShade(repository, surface, KEY, "chat", CHOICE_DENY)

        assertEquals(listOf(NotificationKind.Approval to "chat"), surface.cleared)
    }

    @Test
    fun `an expired request is withdrawn too`() = runTest {
        val surface = RecordingNotificationSurface()

        respondFromShade(FakeRepository(PendingInputResponse.Expired), surface, KEY, "chat", CHOICE_DENY)

        assertEquals(listOf(NotificationKind.Approval to "chat"), surface.cleared)
    }

    @Test
    fun `a connection that moved on leaves the request visible and says where to answer it`() = runTest {
        val surface = RecordingNotificationSurface()

        respondFromShade(FakeRepository(PendingInputResponse.Retryable), surface, KEY, "chat", CHOICE_APPROVE)

        assertEquals(listOf("chat"), surface.degraded)
        assertEquals(emptyList<Pair<NotificationKind, String>>(), surface.cleared)
    }

    @Test
    fun `a notification outliving its process answers nothing and never says it did`() = runTest {
        // The real repository, with the empty pending map a freshly started
        // process has. Before the shade existed nothing could reach
        // `respondToPendingInput` with a key the repository had never seen, so
        // the map miss was read as "already answered" and the notification was
        // withdrawn — telling the user their approval went through while the
        // agent stayed blocked behind a request nobody had answered.
        val repository = freshProcessRepository()
        val surface = RecordingNotificationSurface()

        respondFromShade(repository, surface, KEY, "chat", CHOICE_APPROVE)

        assertEquals(emptyList<Pair<NotificationKind, String>>(), surface.cleared)
        assertEquals(listOf("chat"), surface.degraded)
    }

    @Test
    fun `a stale key whose generation the new process has already reached is still unanswerable`() = runTest {
        // The generation is a per-process counter that restarts at zero, so
        // the number in a notification built by a dead process is a number a
        // fresh one reaches again within milliseconds. Comparing generations
        // alone would read this key as current and answer nothing while
        // reporting success.
        val repository = freshProcessRepository()
        val collidingKey = PendingInputKey(1L, "runtime-1", "req-1", PendingInputKind.Approval)
        val surface = RecordingNotificationSurface()

        respondFromShade(repository, surface, collidingKey, "chat", CHOICE_DENY)

        assertEquals(emptyList<Pair<NotificationKind, String>>(), surface.cleared)
        assertEquals(listOf("chat"), surface.degraded)
    }

    @Test
    fun `a transport that throws is a retry, not a silent success`() = runTest {
        val surface = RecordingNotificationSurface()

        respondFromShade(ThrowingRepository, surface, KEY, "chat", CHOICE_APPROVE)

        assertEquals(listOf("chat"), surface.degraded)
    }
}

private val KEY = PendingInputKey(7L, "runtime-1", "req-1", PendingInputKind.Approval)

/**
 * A real repository in the state a just-launched process is in: connected in
 * principle, and holding no memory of anything that happened before it.
 */
private fun kotlinx.coroutines.test.TestScope.freshProcessRepository(): LiveGatewaySessionRepository {
    val repository = LiveGatewaySessionRepository(
        SessionCache(),
        MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
        MutableStateFlow<GatewayRpcClient?>(null),
        backgroundScope,
    )
    // Let the client collector run, so the generation counter has advanced
    // exactly as it does on a real launch.
    runCurrent()
    return repository
}

private class FakeRepository(private val response: PendingInputResponse) : GatewaySessionRepository {
    val answered = mutableListOf<Pair<PendingInputKey, String>>()

    override val connectionState = MutableStateFlow(com.hermesagent.mobile.data.gateway.GatewayConnectionState())
    override val pendingInputs: StateFlow<Map<PendingInputKey, PendingInputRequest>> =
        MutableStateFlow(emptyMap())

    override suspend fun respondToPendingInput(
        key: PendingInputKey,
        action: PendingInputAction,
    ): PendingInputResponse {
        answered += key to (action as PendingInputAction.ApprovalChoice).choice
        return response
    }

    override suspend fun refreshSessions() = Unit
    override suspend fun openSession(durableId: String): String = durableId
    override suspend fun createSession(workspacePath: String?): String = "new"
    override suspend fun submit(durableId: String, text: String) =
        com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome.Accepted
    override suspend fun interrupt(durableId: String) = Unit
}

private object ThrowingRepository : GatewaySessionRepository {
    override val connectionState = MutableStateFlow(com.hermesagent.mobile.data.gateway.GatewayConnectionState())
    override val pendingInputs: StateFlow<Map<PendingInputKey, PendingInputRequest>> =
        MutableStateFlow(emptyMap())

    override suspend fun respondToPendingInput(
        key: PendingInputKey,
        action: PendingInputAction,
    ): PendingInputResponse = throw IllegalStateException("socket closed")

    override suspend fun refreshSessions() = Unit
    override suspend fun openSession(durableId: String): String = durableId
    override suspend fun createSession(workspacePath: String?): String = "new"
    override suspend fun submit(durableId: String, text: String) =
        com.hermesagent.mobile.data.gateway.GatewaySubmitOutcome.Accepted
    override suspend fun interrupt(durableId: String) = Unit
}
