package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        // The real seed: GatewayConnection starts Disconnected, so the first
        // emission repeats what the app already knows rather than reporting a
        // change.
        val connection = MutableStateFlow(GatewayConnectionState())
        val controller = controller(probe, connection)

        // An initial emission is not an edge. Nothing has been claimed about
        // Relay, nothing is spinning, and no probe was spent saying so.
        runCurrent()
        assertNull(controller.state.value.availability)
        assertFalse(controller.state.value.probing)
        assertFalse(controller.state.value.awaitingFirstAnswer)
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
        assertEquals(RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE, settled.statusMessage())
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
        // The managed SSH leg carries a connection-lifetime token with nothing
        // to rotate; it must not re-probe on a refusal.
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
        // Driven from the seed a cold start actually has, so the spinner this
        // asserts is one production can reach: Disconnected, Connecting,
        // Connected, probing.
        val probe = ScriptedProbe(RelayAvailability.GatewayUnreachable, READY)
        val connection = MutableStateFlow(GatewayConnectionState())
        val controller = controller(probe, connection)
        advanceUntilIdle()
        assertNull(controller.state.value.availability)
        assertFalse(controller.state.value.awaitingFirstAnswer)

        // A connection attempt is still not an answer, and still not a spinner
        // on the Relay surface.
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connecting)
        advanceUntilIdle()
        assertNull(controller.state.value.availability)
        assertFalse(controller.state.value.awaitingFirstAnswer)
        assertEquals(0, probe.calls)

        connection.value = connected()
        advanceTimeBy(1)
        runCurrent()
        assertNull(controller.state.value.availability)
        assertTrue(controller.state.value.awaitingFirstAnswer)

        advanceUntilIdle()
        assertFalse(controller.state.value.awaitingFirstAnswer)
        assertEquals(READY, controller.state.value.availability)
    }

    @Test
    fun `a refresh with nothing to ask answers at once instead of spending a cycle`() = runTest {
        // A disconnected Gateway has no authenticated route to probe. Running a
        // cycle anyway would show a spinner for the whole retry budget before
        // reaching the answer the connection status already gave.
        val probe = ScriptedProbe()
        val waits = mutableListOf<Long>()
        val connection = MutableStateFlow(GatewayConnectionState())
        val controller = controller(probe, connection, waits = waits)
        advanceUntilIdle()

        controller.refresh()
        runCurrent()

        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertFalse(controller.state.value.probing)
        assertEquals(0, probe.calls)
        assertEquals(emptyList<Long>(), waits)

        // A reconnect attempt is still not something to ask, and still must not
        // blank the answer already on screen.
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connecting)
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()
        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertEquals(0, probe.calls)
    }

    /**
     * The C1 ordering rule, stated as a test.
     *
     * A cycle that has been superseded may not write. Under
     * `StandardTestDispatcher` this cannot reproduce the *thread* race the rule
     * also closes — everything here runs on one virtual thread — so the probe
     * instead suspends where cancellation cannot promptly reach it, which
     * produces the same shape: an answer arriving after the transition that
     * replaced it. The controller must drop it either way, because
     * cancellation is cooperative and a probe is a seam an implementation
     * outside this file owns.
     */
    @Test
    fun `an answer from a superseded cycle never overwrites the state that replaced it`() = runTest {
        val probe = StubbornProbe()
        val connection = MutableStateFlow(GatewayConnectionState())
        val controller = controller(probe, connection)
        advanceUntilIdle()

        connection.value = connected()
        advanceUntilIdle()
        assertEquals(1, probe.parked.size)
        assertTrue(controller.state.value.awaitingFirstAnswer)

        // Refresh replaces the in-flight cycle; the replaced one is still
        // parked, holding an answer nobody asked for any more.
        controller.refresh()
        advanceUntilIdle()
        assertEquals(2, probe.parked.size)

        // Then the connection drops. This is the state the surface must keep.
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        advanceUntilIdle()
        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertFalse(controller.state.value.probing)

        // Both stale answers land late. Neither may be believed.
        probe.releaseAll(READY)
        advanceUntilIdle()
        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `a leg with no sign-in asks for a reconnect instead of a sign-in`() = runTest {
        val onSsh = controller(
            ScriptedProbe(NO_CREDENTIAL),
            MutableStateFlow(connected()),
            refresher = CountingRefresher(rotates = false, hasSignIn = false),
        )
        advanceUntilIdle()

        // Managed SSH has no Gateway sign-in at all, so the only honest next
        // step is the one that rebuilds the credential.
        assertEquals(NO_CREDENTIAL, onSsh.state.value.availability)
        assertFalse(onSsh.state.value.signInAvailable)
        assertEquals(TRANSPORT_DOWN_MESSAGE, onSsh.state.value.statusMessage())

        val onRemote = controller(
            ScriptedProbe(NO_CREDENTIAL),
            MutableStateFlow(connected()),
            refresher = CountingRefresher(rotates = false, hasSignIn = true),
        )
        advanceUntilIdle()

        assertTrue(onRemote.state.value.signInAvailable)
        assertEquals(RELAY_SIGN_IN_MESSAGE, onRemote.state.value.statusMessage())
    }

    @Test
    fun `the lane's own words are a detail beside the state, redacted and bounded`() = runTest {
        val lane = RelayAvailability.Available(
            RelayChannelsStatus(
                RelayLaneState.ERROR,
                message = "Relay could not reach the\n  host: Authorization: Bearer host-side-token",
                guidance = null,
            ),
        )

        // The app owns the state line, so an Available state has none of its
        // own: the lane is rendered as the lane it is.
        assertNull(lane.statusMessage(signInAvailable = false))

        val detail = lane.statusDetail()
        assertFalse("a host-authored credential reached a surface", detail!!.contains("host-side-token"))
        assertTrue(detail.contains("<redacted>"))
        // One line, beside the state — including the separators `\s` misses.
        assertFalse(detail.contains("\n"))
        assertEquals(detail, detail.trim())
        val exotic = RelayAvailability.Available(
            RelayChannelsStatus(
                RelayLaneState.OFFLINE,
                message = "Relay is\u0085offline\u2028on\u00a0this\u200bhost.",
                guidance = null,
            ),
        ).statusDetail()
        assertEquals("Relay is offline on this host.", exotic)

        // Nothing but the lane has a detail to show.
        assertNull(RelayAvailability.Missing.statusDetail())
        assertNull(NO_CREDENTIAL.statusDetail())
        assertNull(RelayAvailability.GatewayUnreachable.statusDetail())
    }

    @Test
    fun `a lane with too much to say is cut to the room the surface has`() = runTest {
        // A remote host authors this string, so its length is not the app's to
        // trust: the detail line gets one bounded line's worth, however long
        // the lane's message is.
        val long = RelayAvailability.Available(
            RelayChannelsStatus(
                RelayLaneState.ERROR,
                message = "Relay is unhappy. ".repeat(40),
                guidance = null,
            ),
        ).statusDetail()

        assertEquals(160, long!!.length)
        assertTrue(long.startsWith("Relay is unhappy. Relay is unhappy."))

        // The bound is a ceiling, not a pad: a message that already fits is
        // handed over whole.
        val short = RelayAvailability.Available(
            RelayChannelsStatus(RelayLaneState.ERROR, message = "Relay is unhappy.", guidance = null),
        ).statusDetail()
        assertEquals("Relay is unhappy.", short)
    }

    @Test
    fun `a cycle that dies without ever settling still clears the spinner it started`() = runTest {
        // The probe seam is not this controller's code. A client that wraps its
        // own `withTimeout` raises a TimeoutCancellationException, which is a
        // CancellationException the cycle correctly refuses to treat as an
        // answer — so it ends having settled nothing, from somewhere that is
        // neither `startProbe` nor `stopProbe` and therefore never bumped the
        // generation. The completion backstop is the only thing left that can
        // take the spinner down.
        val controller = controller(
            { withTimeout(1L) { delay(1_000L); READY } },
            MutableStateFlow(connected()),
        )
        advanceUntilIdle()

        // Nothing was claimed about Relay — the cycle never reached an answer.
        assertNull(controller.state.value.availability)
        // But the surface is not left pending forever, which is the one outcome
        // this controller exists to prevent.
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `a fresh install with no Gateway saved is never called unreachable`() = runTest {
        // Exactly the device state issue #80 was found in: nothing saved, the
        // seeded Disconnected status, and the Relay surface opening — which
        // calls refresh() through `surfaceResumed`, the step that turned the
        // seed into an answer nobody had asked for.
        val probe = ScriptedProbe()
        val controller = controller(
            probe,
            MutableStateFlow(GatewayConnectionState()),
            configured = MutableStateFlow(false),
        )
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()

        // No Gateway was reached for, so nothing may claim one was unreachable.
        assertNull(controller.state.value.availability)
        assertFalse(controller.state.value.probing)
        assertFalse(controller.state.value.awaitingFirstAnswer)
        assertNull(controller.state.value.statusMessage())
        assertEquals(0, probe.calls)
    }

    @Test
    fun `a saved Gateway that is down is unreachable, not unconfigured`() = runTest {
        val controller = controller(
            ScriptedProbe(),
            MutableStateFlow(GatewayConnectionState()),
            configured = MutableStateFlow(true),
        )
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()

        // There is a Gateway; it did not answer. That is the reconnect state,
        // and it keeps the retry the fresh-install state must not offer.
        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)
        assertEquals(TRANSPORT_DOWN_MESSAGE, controller.state.value.statusMessage())
    }

    @Test
    fun `learning a Gateway is saved is not itself an answer about Relay`() = runTest {
        // The profile store is asynchronous, so its first answer arrives after
        // the controller exists. It must not turn the seed into a claim.
        val configured = MutableStateFlow(false)
        val controller = controller(
            ScriptedProbe(),
            MutableStateFlow(GatewayConnectionState()),
            configured = configured,
        )
        advanceUntilIdle()

        configured.value = true
        advanceUntilIdle()

        assertNull(controller.state.value.availability)
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `a late profile answer revises the sentence it changes, both ways`() = runTest {
        val configured = MutableStateFlow(false)
        val controller = controller(
            ScriptedProbe(),
            MutableStateFlow(GatewayConnectionState()),
            configured = configured,
        )
        advanceUntilIdle()
        controller.refresh()
        advanceUntilIdle()
        assertNull(controller.state.value.availability)

        // The store answers late — or a Gateway is added while the surface is
        // open. The sentence this flag produced is re-derived, not stranded.
        configured.value = true
        advanceUntilIdle()
        assertEquals(RelayAvailability.GatewayUnreachable, controller.state.value.availability)

        // And forgetting it takes the claim back with it: an unreachable
        // Gateway that no longer exists is not a state anyone can act on.
        configured.value = false
        advanceUntilIdle()
        assertNull(controller.state.value.availability)
        assertFalse(controller.state.value.probing)
    }

    @Test
    fun `a live answer is the Gateway's own and survives a profile edit`() = runTest {
        val configured = MutableStateFlow(true)
        val probe = ScriptedProbe(RelayAvailability.Missing)
        val controller = controller(probe, MutableStateFlow(connected()), configured = configured)
        advanceUntilIdle()
        assertEquals(RelayAvailability.Missing, controller.state.value.availability)

        // Editing the saved profile says nothing about the Gateway currently
        // on the other end of the socket, and must not spend a probe asking.
        configured.value = false
        advanceUntilIdle()
        assertEquals(RelayAvailability.Missing, controller.state.value.availability)
        assertEquals(1, probe.calls)
    }

    private fun TestScope.controller(
        probe: RelayAvailabilityProbe,
        connection: MutableStateFlow<GatewayConnectionState>,
        refresher: RelayCredentialRefresher = CountingRefresher(rotates = false, hasSignIn = true),
        waits: MutableList<Long> = mutableListOf(),
        /** Every test that does not say otherwise is about a Gateway that exists. */
        configured: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) = RelayAvailabilityController(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also(scopes::add),
        probe = probe,
        connection = connection,
        configured = configured,
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

private class CountingRefresher(
    private val rotates: Boolean,
    private val hasSignIn: Boolean = true,
) : RelayCredentialRefresher {
    var calls = 0
        private set

    override suspend fun refreshOnce(): Boolean {
        calls++
        return rotates
    }

    override suspend fun signInAvailable(): Boolean = hasSignIn
}

/**
 * Parks every call where cancellation cannot promptly reach it, then answers
 * all of them at once. Stands in for a probe whose suspension is not a
 * cancellation point — the shape the controller's ordering rule has to survive.
 */
private class StubbornProbe : RelayAvailabilityProbe {
    val parked = mutableListOf<CompletableDeferred<RelayAvailability>>()

    override suspend fun availability(): RelayAvailability {
        val gate = CompletableDeferred<RelayAvailability>()
        parked += gate
        return withContext(NonCancellable) { gate.await() }
    }

    fun releaseAll(answer: RelayAvailability) {
        parked.forEach { it.complete(answer) }
    }
}
