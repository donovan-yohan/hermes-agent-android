package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCacheState
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TurnProtectionControllerTest {

    private val activeTurnsFlow = MutableStateFlow<Set<String>>(emptySet())
    private val sessionsFlow = MutableStateFlow(SessionCacheState())
    private val pendingInputsFlow = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
    private val connectionStateFlow = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
    private val appForegroundedFlow = MutableStateFlow(true)
    private val host = RecordingServiceHost()
    private var protectionActive = false

    private fun startController(
        lingerGraceMillis: Long = 5_000L,
        maxHoldMillis: Long = 30 * 60 * 1000L,
        needsAttentionGraceMillis: Long = 3 * 60 * 1000L,
        scope: kotlinx.coroutines.CoroutineScope,
    ): TurnProtectionController {
        val controller = TurnProtectionController(
            activeTurns = activeTurnsFlow,
            sessions = sessionsFlow,
            pendingInputs = pendingInputsFlow,
            connectionState = connectionStateFlow,
            appForegrounded = appForegroundedFlow,
            serviceHost = host,
            onProtectionActiveChanged = { active -> protectionActive = active },
            lingerGraceMillis = lingerGraceMillis,
            maxHoldMillis = maxHoldMillis,
            needsAttentionGraceMillis = needsAttentionGraceMillis,
        )
        controller.start(scope)
        return controller
    }

    @Test
    fun `turn starts while foregrounded requests service start`() = runTest {
        startController(scope = backgroundScope)
        runCurrent()

        assertEquals(0, host.startCalls)
        assertFalse(protectionActive)

        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()

        assertEquals(1, host.startCalls)
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)
    }

    @Test
    fun `turn ends stops service after linger grace, new turn within grace cancels stop`() = runTest {
        startController(lingerGraceMillis = 5_000L, scope = backgroundScope)
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // App is backgrounded while turn is running
        appForegroundedFlow.value = false
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Turn ends
        activeTurnsFlow.value = emptySet()
        sessionsFlow.value = sessionState("s1", SessionStatus.Idle)
        runCurrent()

        // 2 seconds in: still lingering, service not stopped
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // New turn begins within linger window
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()

        // Advance past original 5s window: stop was cancelled
        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Turn ends again
        activeTurnsFlow.value = emptySet()
        sessionsFlow.value = sessionState("s1", SessionStatus.Idle)
        runCurrent()

        // Advance 4.9s: still not stopped
        advanceTimeBy(4_900L)
        runCurrent()
        assertEquals(0, host.stopCalls)

        // Reach 5.0s: linger expires, service stopped
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(1, host.stopCalls)
        assertFalse(protectionActive)
    }

    @Test
    fun `linger vs new turn race cancels linger stop without stopping new turn`() = runTest {
        startController(lingerGraceMillis = 5_000L, scope = backgroundScope)
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Turn completes -> linger starts
        activeTurnsFlow.value = emptySet()
        sessionsFlow.value = sessionState("s1", SessionStatus.Idle)
        runCurrent()

        // Advance into the linger window
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(0, host.stopCalls)

        // New turn begins while linger job is in-flight
        activeTurnsFlow.value = setOf("s2")
        sessionsFlow.value = sessionState("s2", SessionStatus.Working)
        runCurrent()

        // Advance past original 5000ms linger window
        advanceTimeBy(4_000L)
        runCurrent()

        // Service must NOT be stopped
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)
    }

    @Test
    fun `start failure keeps protection inactive and retries on next foreground`() = runTest {
        host.startResult = false
        startController(scope = backgroundScope)
        runCurrent()

        // Turn starts while foregrounded, but service start fails (e.g. refused by OS)
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()

        assertEquals(1, host.startCalls)
        assertFalse(protectionActive)

        // App leaves and returns to foreground with turn still active
        appForegroundedFlow.value = false
        runCurrent()
        appForegroundedFlow.value = true
        host.startResult = true
        runCurrent()

        // Retry succeeded
        assertEquals(2, host.startCalls)
        assertTrue(protectionActive)
    }

    @Test
    fun `asynchronous service start refusal drops protection and retries on foreground`() = runTest {
        startController(scope = backgroundScope)
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()

        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Service reports refusal in onStartCommand (e.g. background race)
        host.simulateServiceRefusal()
        runCurrent()

        assertFalse(protectionActive)

        // Next foreground return retries service start
        appForegroundedFlow.value = false
        runCurrent()
        appForegroundedFlow.value = true
        runCurrent()

        assertEquals(2, host.startCalls)
        assertTrue(protectionActive)
    }

    @Test
    fun `refusal after a normal stop and restart still drops protection and retries`() = runTest {
        // A host that forgets its refusal registration when the service stops: the
        // controller must re-arm on each protection start rather than assume the
        // single registration it made at startup is still live.
        host.forgetsRefusalCallbackOnStop = true
        startController(lingerGraceMillis = 5_000L, scope = backgroundScope)

        // First protection cycle, released normally through the linger grace.
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        activeTurnsFlow.value = emptySet()
        sessionsFlow.value = sessionState("s1", SessionStatus.Idle)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, host.stopCalls)
        assertFalse(protectionActive)

        // Second cycle: protection is taken again.
        activeTurnsFlow.value = setOf("s2")
        sessionsFlow.value = sessionState("s2", SessionStatus.Working)
        runCurrent()
        assertEquals(2, host.startCalls)
        assertTrue(protectionActive)

        // The service refuses asynchronously after that restart.
        host.simulateServiceRefusal()
        runCurrent()
        assertFalse(protectionActive)

        // Returning to the foreground still retries the service start.
        appForegroundedFlow.value = false
        runCurrent()
        appForegroundedFlow.value = true
        runCurrent()
        assertEquals(3, host.startCalls)
        assertTrue(protectionActive)
    }

    @Test
    fun `maximum hold ceiling stops service even if session is stuck Working`() = runTest {
        startController(
            lingerGraceMillis = 5_000L,
            maxHoldMillis = 10_000L,
            scope = backgroundScope,
        )
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()

        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Session stays stuck Working for past maxHoldMillis
        advanceTimeBy(9_999L)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, host.stopCalls)
        assertFalse(protectionActive)
    }

    @Test
    fun `pending approval outstanding keeps service alive after turn ends`() = runTest {
        startController(lingerGraceMillis = 5_000L, scope = backgroundScope)
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        val inputKey = PendingInputKey(
            connectionGeneration = 1L,
            runtimeSessionId = "r1",
            requestId = "req-1",
            kind = PendingInputKind.Approval,
        )
        pendingInputsFlow.value = mapOf(
            inputKey to ApprovalPending(
                key = inputKey,
                durableSessionId = "s1",
                runtimeSessionId = "r1",
                command = "ls",
                description = "Approve tool execution",
                choices = listOf("Run", "Deny"),
            ),
        )
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Turn settles to Idle in cache, but pending input is still outstanding
        activeTurnsFlow.value = emptySet()
        sessionsFlow.value = sessionState("s1", SessionStatus.Idle)
        runCurrent()

        // Advance time well past linger grace
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Pending input is answered / cleared
        pendingInputsFlow.value = emptyMap()
        runCurrent()

        // Linger grace passes
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, host.stopCalls)
        assertFalse(protectionActive)
    }

    @Test
    fun `endpoint switch or connection close triggers immediate stop`() = runTest {
        startController(lingerGraceMillis = 5_000L, scope = backgroundScope)
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Deliberate disconnect / endpoint switch
        connectionStateFlow.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()

        // Immediate stop without waiting for linger
        assertEquals(1, host.stopCalls)
        assertFalse(protectionActive)
    }

    @Test
    fun `NeedsAttention blip shorter than grace keeps protection`() = runTest {
        startController(
            lingerGraceMillis = 5_000L,
            needsAttentionGraceMillis = 180_000L,
            scope = backgroundScope,
        )
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // App backgrounded during live turn
        appForegroundedFlow.value = false
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Temporary network drop (e.g. wifi to cellular handoff) -> NeedsAttention
        connectionStateFlow.value = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention)
        runCurrent()

        // Protection is NOT dropped immediately; held through grace period
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Advance into grace window (e.g. 45s escalation threshold)
        advanceTimeBy(45_000L)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Network connection recovers before grace expires
        connectionStateFlow.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        // Advance well past the original 180s grace window: protection remains active
        advanceTimeBy(200_000L)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)
    }

    @Test
    fun `NeedsAttention longer than grace releases protection`() = runTest {
        startController(
            lingerGraceMillis = 5_000L,
            needsAttentionGraceMillis = 180_000L,
            scope = backgroundScope,
        )
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Connection transitions to NeedsAttention
        connectionStateFlow.value = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Advance time just before grace expiry
        advanceTimeBy(179_999L)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Reach grace expiry: protection released
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, host.stopCalls)
        assertFalse(protectionActive)
    }

    @Test
    fun `terminal Disconnected status stops protection immediately even during NeedsAttention grace`() = runTest {
        startController(
            lingerGraceMillis = 5_000L,
            needsAttentionGraceMillis = 180_000L,
            scope = backgroundScope,
        )
        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)

        // Enters NeedsAttention
        connectionStateFlow.value = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention)
        runCurrent()
        assertEquals(0, host.stopCalls)
        assertTrue(protectionActive)

        // Deliberate disconnect / endpoint switch while in NeedsAttention
        connectionStateFlow.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()

        // Immediate stop without waiting for grace expiry
        assertEquals(1, host.stopCalls)
        assertFalse(protectionActive)
    }

    @Test
    fun `turn starting while backgrounded does not start service until foregrounded`() = runTest {
        appForegroundedFlow.value = false
        startController(scope = backgroundScope)
        runCurrent()

        activeTurnsFlow.value = setOf("s1")
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()

        // Cannot start from background
        assertEquals(0, host.startCalls)
        assertFalse(protectionActive)

        // App enters foreground
        appForegroundedFlow.value = true
        runCurrent()

        // Service starts
        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)
    }

    @Test
    fun `roster-only turn running on another client does not start service`() = runTest {
        startController(scope = backgroundScope)
        runCurrent()

        // Desktop is running turn s1: cache shows Working from roster, but s1 is not in activeTurnsFlow
        sessionsFlow.value = sessionState("s1", SessionStatus.Working)
        runCurrent()

        assertEquals(0, host.startCalls)
        assertFalse(protectionActive)

        // Only when this app submits a turn (in activeTurnsFlow) does protection start
        activeTurnsFlow.value = setOf("s1")
        runCurrent()

        assertEquals(1, host.startCalls)
        assertTrue(protectionActive)
    }

    private fun sessionState(id: String, status: SessionStatus) = SessionCacheState(
        sessions = mapOf(
            id to SessionSummary(
                id = id,
                title = "Test Session",
                preview = "preview",
                lastActiveAtMillis = 1000L,
                status = status,
            ),
        ),
    )

    private class RecordingServiceHost : TurnProtectionServiceHost {
        var startCalls = 0
        var stopCalls = 0
        var startResult = true

        /** Models a host whose single-registrant refusal seam does not survive a stop. */
        var forgetsRefusalCallbackOnStop = false
        private var refusalCallback: (() -> Unit)? = null

        override fun startService(): Boolean {
            startCalls += 1
            return startResult
        }

        override fun stopService() {
            stopCalls += 1
            if (forgetsRefusalCallbackOnStop) {
                refusalCallback = null
            }
        }

        override fun onServiceRefused(callback: () -> Unit) {
            refusalCallback = callback
        }

        fun simulateServiceRefusal() {
            refusalCallback?.invoke()
        }
    }
}
