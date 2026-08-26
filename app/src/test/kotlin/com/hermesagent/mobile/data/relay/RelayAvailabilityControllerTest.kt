package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything here runs on virtual time. Nothing in the controller may reach a
 * real clock, so a Gateway that never answers has to settle in test time or
 * these tests hang rather than pass slowly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayAvailabilityControllerTest {

    /**
     * The controller owns a process-scoped collector, so each test gives it its
     * own scope on the test scheduler and tears it down afterwards. It must not
     * ride `backgroundScope`: work parked there is not driven by
     * `advanceUntilIdle`, which would let a stalled cycle read as a settled one.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelControllerScopes() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun `becoming connected probes once and settles on the answer`() = runTest {
        val probe = ScriptedProbe(READY)
        val connection = MutableStateFlow(GatewayConnectionState())
        val controller = controller(probe, connection)

        // Disconnected is an answer in itself: nothing authenticated exists to
        // ask, and saying so costs no probe.
        runCurrent()
        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertEquals(0, probe.calls)

        connection.value = connected()
        advanceUntilIdle()

        assertEquals(READY, controller.state.value.availability)
        assertFalse(controller.state.value.probing)
        assertEquals(1, probe.calls)
    }

    @Test
    fun `only a status transition costs a probe`() = runTest {
        val probe = ScriptedProbe(READY, READY)
        val connection = MutableStateFlow(connected())
        val controller = controller(probe, connection)
        advanceUntilIdle()
        assertEquals(1, probe.calls)

        // The same status arriving again is not a new fact about the Gateway.
        connection.value = connected()
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connected, message = "still here")
        advanceUntilIdle()
        assertEquals(1, probe.calls)

        // A real edge away and back is. The drop has to be observed rather than
        // conflated away, or there is no edge to come back from.
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        connection.value = connected()
        advanceUntilIdle()
        assertEquals(2, probe.calls)
        assertEquals(READY, controller.state.value.availability)
    }

    @Test
    fun `a runtime-gate 404 settles as a state, never as a pending screen`() = runTest {
        val controller = controller(ScriptedProbe(RelayAvailability.Missing), MutableStateFlow(connected()))
        advanceUntilIdle()

        val settled = controller.state.value
        assertEquals(RelayAvailability.Missing, settled.availability)
        assertFalse(settled.probing)
        assertFalse(settled.awaitingFirstAnswer)
        // The copy that belongs beside where Relay would live, not in an error.
        assertEquals(
            RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE,
            RelayAvailability.Missing.statusMessage(),
        )
    }

    @Test
    fun `an unreachable Gateway retries a bounded number of times and then stops`() = runTest {
        val waits = mutableListOf<Long>()
        val probe = ScriptedProbe(
            RelayAvailability.GatewayUnreachable,
            RelayAvailability.GatewayUnreachable,
            RelayAvailability.GatewayUnreachable,
        )
        val controller = controller(probe, MutableStateFlow(connected()), waits = waits)
        advanceUntilIdle()

        assertEquals(3, probe.calls)
        assertEquals(listOf(1_000L, 2_000L), waits)
        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        // The spinner trap this slice exists to prevent.
        assertFalse(controller.state.value.probing)

        // And nothing polls afterwards: a further hour of virtual time is quiet.
        advanceTimeBy(60 * 60 * 1_000L)
        advanceUntilIdle()
        assertEquals(3, probe.calls)
    }

    @Test
    fun `a lapsed credential spends one rotation and one retry, then asks for sign-in`() = runTest {
        val probe = ScriptedProbe(LAPSED, LAPSED)
        val refresher = CountingRefresher(rotates = true)
        val controller = controller(probe, MutableStateFlow(connected()), refresher = refresher)
        advanceUntilIdle()

        // Exactly one rotation and exactly one re-probe, then the surface asks
        // the person to sign in rather than rotating again.
        assertEquals(1, refresher.calls)
        assertEquals(2, probe.calls)
        assertEquals(LAPSED, controller.state.value.availability)
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `a rotation that works leaves the surface available without a sign-in`() = runTest {
        val probe = ScriptedProbe(LAPSED, READY)
        val refresher = CountingRefresher(rotates = true)
        val controller = controller(probe, MutableStateFlow(connected()), refresher = refresher)
        advanceUntilIdle()

        assertEquals(1, refresher.calls)
        assertEquals(2, probe.calls)
        assertEquals(READY, controller.state.value.availability)
    }

    @Test
    fun `a rotation the connection cannot perform goes straight to sign-in`() = runTest {
        // The SSH-tunneled and token-mode legs carry a connection-lifetime
        // token with nothing to rotate; they must not re-probe on a refusal.
        val probe = ScriptedProbe(LAPSED)
        val refresher = CountingRefresher(rotates = false)
        val controller = controller(probe, MutableStateFlow(connected()), refresher = refresher)
        advanceUntilIdle()

        assertEquals(1, refresher.calls)
        assertEquals(1, probe.calls)
        assertEquals(LAPSED, controller.state.value.availability)
    }

    @Test
    fun `an unrecognised credential never spends a rotation`() = runTest {
        val probe = ScriptedProbe(NO_CREDENTIAL)
        val refresher = CountingRefresher(rotates = true)
        val controller = controller(probe, MutableStateFlow(connected()), refresher = refresher)
        advanceUntilIdle()

        assertEquals(0, refresher.calls)
        assertEquals(1, probe.calls)
        assertEquals(NO_CREDENTIAL, controller.state.value.availability)
    }

    @Test
    fun `losing the connection settles instead of leaving a stale answer on screen`() = runTest {
        val connection = MutableStateFlow(connected())
        val controller = controller(ScriptedProbe(READY), connection)
        advanceUntilIdle()
        assertEquals(READY, controller.state.value.availability)

        connection.value = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, "check the network")
        advanceUntilIdle()

        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `a reconnect attempt keeps the last honest answer rather than blanking it`() = runTest {
        val connection = MutableStateFlow(connected())
        val controller = controller(ScriptedProbe(RelayAvailability.Missing), connection)
        advanceUntilIdle()

        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connecting)
        advanceUntilIdle()

        assertEquals(RelayAvailability.Missing, controller.state.value.availability)
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `an explicit refresh replaces an in-flight cycle rather than stacking one`() = runTest {
        val waits = mutableListOf<Long>()
        val probe = ScriptedProbe(
            RelayAvailability.GatewayUnreachable,
            RelayAvailability.GatewayUnreachable,
            READY,
        )
        val controller = controller(probe, MutableStateFlow(connected()), waits = waits)

        // Park the first cycle inside its backoff, then ask again.
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, probe.calls)
        assertTrue(controller.state.value.probing)

        controller.refresh()
        advanceUntilIdle()

        // Three probes total, not a doubled-up pair of overlapping cycles, and
        // the second cycle's own budget starts fresh.
        assertEquals(3, probe.calls)
        assertEquals(READY, controller.state.value.availability)
    }

    @Test
    fun `a probe that fails outright still settles rather than pending forever`() = runTest {
        val controller = controller(
            { error("the transport blew up in a way nobody predicted") },
            MutableStateFlow(connected()),
        )
        advanceUntilIdle()

        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `the only spinner is the one before any answer has ever arrived`() = runTest {
        val probe = ScriptedProbe(RelayAvailability.GatewayUnreachable, READY)
        val controller = controller(probe, MutableStateFlow(connected()))

        advanceTimeBy(1)
        runCurrent()
        assertNull(controller.state.value.availability)
        assertTrue(controller.state.value.awaitingFirstAnswer)

        advanceUntilIdle()
        assertFalse(controller.state.value.awaitingFirstAnswer)
        assertEquals(READY, controller.state.value.availability)
    }

    private fun TestScope.controller(
        probe: RelayAvailabilityProbe,
        connection: MutableStateFlow<GatewayConnectionState>,
        refresher: RelayCredentialRefresher = CountingRefresher(rotates = false),
        waits: MutableList<Long> = mutableListOf(),
    ) = RelayAvailabilityController(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also(scopes::add),
        probe = probe,
        connection = connection,
        credentials = refresher,
        wait = { millis ->
            waits += millis
            delay(millis)
        },
    )

    private companion object {
        val READY = RelayAvailability.Available(
            RelayChannelsStatus(RelayLaneState.READY, message = null, guidance = null),
        )
        val LAPSED = RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired)
        val NO_CREDENTIAL = RelayAvailability.SignInRequired(RelaySignInReason.NoCredential)

        fun connected() = GatewayConnectionState(GatewayConnectionStatus.Connected)
    }
}

/** Answers a fixed script. An unscripted probe is a bug in the test's premise. */
private class ScriptedProbe(vararg answers: RelayAvailability) : RelayAvailabilityProbe {
    private val queue = ArrayDeque(answers.toList())
    var calls = 0
        private set

    override suspend fun availability(): RelayAvailability {
        calls++
        return queue.pollFirst() ?: error("the controller probed more often than the test allows")
    }
}

private class CountingRefresher(private val rotates: Boolean) : RelayCredentialRefresher {
    var calls = 0
        private set

    override suspend fun refreshOnce(): Boolean {
        calls++
        return rotates
    }
}
