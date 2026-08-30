package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.GatewaySessionRepository
import com.hermesagent.mobile.data.gateway.PendingInputAction
import com.hermesagent.mobile.data.gateway.PendingInputKey
import com.hermesagent.mobile.data.gateway.PendingInputKind
import com.hermesagent.mobile.data.gateway.PendingInputRequest
import com.hermesagent.mobile.data.gateway.PendingInputResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `a transport that throws is a retry, not a silent success`() = runTest {
        val surface = RecordingNotificationSurface()

        respondFromShade(ThrowingRepository, surface, KEY, "chat", CHOICE_APPROVE)

        assertEquals(listOf("chat"), surface.degraded)
    }
}

private val KEY = PendingInputKey(7L, "runtime-1", "req-1", PendingInputKind.Approval)

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
