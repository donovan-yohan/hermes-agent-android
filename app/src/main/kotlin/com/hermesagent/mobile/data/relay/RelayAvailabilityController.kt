package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What the Relay surface may honestly show right now.
 *
 * [availability] is the last answer the Gateway actually gave, kept across a
 * re-probe so a refresh never blanks a screen that already told the truth.
 */
data class RelayAvailabilityState(
    val availability: RelayAvailability? = null,
    val probing: Boolean = false,
) {
    /** The only legitimate spinner: nothing has answered yet and a probe is out. */
    val awaitingFirstAnswer: Boolean get() = availability == null && probing
}

/**
 * The app's existing, non-interactive credential rotation for the live Gateway
 * leg. The Relay layer never sees, holds, or supplies credential material — it
 * only asks the connection owner to rotate and is told whether that worked.
 */
fun interface RelayCredentialRefresher {
    /** Rotate once without user interaction. True when a fresh credential is installed. */
    suspend fun refreshOnce(): Boolean
}

/**
 * The single question the state machine asks the network. [RelayPluginRepository]
 * is the live implementation; keeping the seam this narrow is what lets the
 * state machine be driven entirely on virtual time.
 */
fun interface RelayAvailabilityProbe {
    suspend fun availability(): RelayAvailability
}

/**
 * Lifecycle-aware availability state for the Relay surface.
 *
 * Three rules make this honest rather than a spinner:
 *
 * 1. **Every cycle terminates.** Transient unreachability is retried a bounded
 *    number of times with injected waits; nothing here polls on a timer, so a
 *    Gateway that never answers settles on [RelayAvailability.GatewayUnreachable]
 *    instead of spinning forever.
 * 2. **Refresh rides connection liveness.** A re-probe happens on the edge into
 *    [GatewayConnectionStatus.Connected] and on an explicit [refresh]; losing
 *    the connection settles the state immediately rather than leaving a stale
 *    "available" on screen.
 * 3. **One rotation, one retry.** A lapsed credential — as the Gateway's own
 *    refusal envelope reports it, not as a status code implies it — spends
 *    exactly one rotation through the app's existing flow and exactly one
 *    re-probe before the surface asks the person to sign in.
 *
 * [RelayLaneState.AUTH_REQUIRED] is deliberately *not* acted on here: redeeming
 * the host's grant through `POST /connection/authorize` consumes a one-time
 * grant, so it stays an explicit action on the surface, never a side effect of
 * looking at it.
 */
class RelayAvailabilityController(
    private val scope: CoroutineScope,
    private val probe: RelayAvailabilityProbe,
    connection: Flow<GatewayConnectionState>,
    private val credentials: RelayCredentialRefresher,
    private val wait: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    private val _state = MutableStateFlow(RelayAvailabilityState())
    val state: StateFlow<RelayAvailabilityState> = _state.asStateFlow()

    private var cycle: Job? = null
    private var observedStatus: GatewayConnectionStatus? = null

    init {
        scope.launch {
            connection.collect { onConnectionStatus(it.status) }
        }
    }

    /**
     * Probe now — the surface became visible, or someone asked again. Safe to
     * call repeatedly: a cycle already in flight is replaced, never stacked.
     */
    fun refresh() {
        startProbe()
    }

    /**
     * Only a *transition* is a signal. Re-emitting the same status is not a new
     * fact about the Gateway and must not cost a probe.
     */
    private fun onConnectionStatus(status: GatewayConnectionStatus) {
        if (status == observedStatus) return
        observedStatus = status
        when (status) {
            GatewayConnectionStatus.Connected -> startProbe()

            // A connection attempt is not yet an answer. Keep the last honest
            // one on screen and let the Connected edge re-probe.
            GatewayConnectionStatus.Connecting -> stopProbe()

            GatewayConnectionStatus.Disconnected,
            GatewayConnectionStatus.NeedsAttention,
            -> {
                stopProbe()
                _state.value = RelayAvailabilityState(RelayAvailability.GatewayUnreachable)
            }
        }
    }

    private fun startProbe() {
        cycle?.cancel()
        cycle = scope.launch { runProbeCycle() }
    }

    private fun stopProbe() {
        cycle?.cancel()
        cycle = null
        _state.update { it.copy(probing = false) }
    }

    /**
     * A cycle that dies on the way to an answer would leave the surface
     * pending forever, which is the one outcome this controller exists to
     * prevent. Anything unexpected settles as honestly as it can: nothing
     * usable came back.
     */
    private suspend fun runProbeCycle() {
        try {
            probeUntilSettled()
        } catch (cancelled: CancellationException) {
            // A replaced or torn-down cycle is not a Gateway fault, and the
            // cycle replacing it owns the state from here.
            throw cancelled
        } catch (_: Throwable) {
            settle(RelayAvailability.GatewayUnreachable)
        }
    }

    private suspend fun probeUntilSettled() {
        var rotated = false
        var unreachable = 0
        while (currentCoroutineContext().isActive) {
            _state.update { it.copy(probing = true) }
            when (val answer = probe.availability()) {
                is RelayAvailability.SignInRequired -> {
                    // Only a credential the gate says lapsed is worth rotating;
                    // "nothing recognised" has nothing to rotate. Either way the
                    // budget is one rotation and one re-probe per cycle.
                    if (answer.reason == RelaySignInReason.SessionExpired && !rotated) {
                        rotated = true
                        if (credentials.refreshOnce()) continue
                    }
                    settle(answer)
                    return
                }

                RelayAvailability.GatewayUnreachable -> {
                    unreachable++
                    if (unreachable >= MAX_UNREACHABLE_ATTEMPTS) {
                        settle(RelayAvailability.GatewayUnreachable)
                        return
                    }
                    wait(RETRY_BACKOFF_MILLIS * unreachable)
                }

                else -> {
                    settle(answer)
                    return
                }
            }
        }
    }

    private fun settle(answer: RelayAvailability) {
        _state.value = RelayAvailabilityState(availability = answer, probing = false)
    }

    private companion object {
        /** Bounded on purpose: waiting forever is not a state a person can act on. */
        const val MAX_UNREACHABLE_ATTEMPTS = 3
        const val RETRY_BACKOFF_MILLIS = 1_000L
    }
}

/**
 * The one line a surface shows for a settled availability state.
 *
 * Every value here describes a *state*, never an error: a Gateway without the
 * plugin is a fact about that Gateway, so it belongs beside where Relay would
 * live and never in an error or toast channel. Null means the state carries no
 * line of its own — the lane's own message, when it has one, speaks instead.
 */
fun RelayAvailability.statusMessage(): String? = when (this) {
    is RelayAvailability.Available -> channels.message
    RelayAvailability.Missing -> RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
    is RelayAvailability.SignInRequired -> RELAY_SIGN_IN_MESSAGE
    RelayAvailability.Incompatible -> RELAY_INCOMPATIBLE_MESSAGE
    RelayAvailability.GatewayUnreachable -> TRANSPORT_DOWN_MESSAGE
}

internal const val RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE = "Relay is unavailable on this Gateway."
internal const val RELAY_SIGN_IN_MESSAGE = "Sign in to this Gateway to open Relay."
internal const val RELAY_INCOMPATIBLE_MESSAGE =
    "Relay answered in a form this app does not support. Update Hermes and try again."
