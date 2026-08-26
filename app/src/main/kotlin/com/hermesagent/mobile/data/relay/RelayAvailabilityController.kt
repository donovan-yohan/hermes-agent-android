package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.data.ssh.redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
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
    /**
     * Whether the person holding this device can sign in to the live leg at
     * all, as the connection owner answers it at the moment this state
     * settled. False on managed SSH, and false before anything has been asked
     * — copy that offers a sign-in that does not exist is worse than copy that
     * offers a reconnect that does.
     */
    val signInAvailable: Boolean = false,
) {
    /** The only legitimate spinner: nothing has answered yet and a probe is out. */
    val awaitingFirstAnswer: Boolean get() = availability == null && probing
}

/**
 * What the connection owner can do about the live leg's credential. The Relay
 * layer never sees, holds, or supplies credential material — it only asks and
 * is told whether that worked.
 */
interface RelayCredentialRefresher {
    /** Rotate once without user interaction. True when a fresh credential is installed. */
    suspend fun refreshOnce(): Boolean

    /**
     * Whether a sign-in on this device could supply the live leg's credential.
     *
     * False on managed SSH, whose credential lives exactly as long as the
     * connection: there is no Gateway sign-in on that leg, so a refusal there
     * has to ask for the reconnect that actually is the remedy.
     */
    suspend fun signInAvailable(): Boolean
}

/**
 * The single question the state machine asks the network. [RelayPluginRepository]
 * is the live implementation; keeping the seam this narrow is what lets the
 * state machine be driven entirely on virtual time.
 *
 * An implementation must reach a cancellable suspension point promptly: a cycle
 * is cancelled whenever the connection moves under it, and one that ignores
 * cancellation keeps a socket and a probe budget alive after the answer stopped
 * mattering. Correctness does not depend on it — a superseded cycle's answer is
 * dropped either way — but timeliness does.
 */
fun interface RelayAvailabilityProbe {
    suspend fun availability(): RelayAvailability
}

/**
 * Lifecycle-aware availability state for the Relay surface.
 *
 * Four rules make this honest rather than a spinner:
 *
 * 1. **Every cycle terminates.** Transient unreachability is retried a bounded
 *    number of times with injected waits; nothing here polls on a timer, so a
 *    Gateway that never answers settles on [RelayAvailability.GatewayUnreachable]
 *    instead of spinning forever. A cycle that ends any other way clears its own
 *    spinner, so this holds even for a probe that misbehaves.
 * 2. **Refresh rides connection liveness.** A re-probe happens on the edge into
 *    [GatewayConnectionStatus.Connected] and on an explicit [refresh]; losing
 *    the connection settles the state immediately rather than leaving a stale
 *    "available" on screen.
 * 3. **One rotation, one retry.** A lapsed credential — as the Gateway's own
 *    refusal envelope reports it, not as a status code implies it — spends
 *    exactly one rotation through the app's existing flow and exactly one
 *    re-probe before the surface asks the person to sign in.
 * 4. **One writer.** Every transition — a connection status, a [refresh], a
 *    cycle's answer — is a [Command] drained by the single coroutine started in
 *    `init`, so the mutable machinery (`cycle`, `observedStatus`, `generation`)
 *    is confined to one coroutine and the transitions are totally ordered.
 *    Without that, [refresh] from the main thread and the collector on an IO
 *    thread could interleave into a state rule 2 exists to forbid: a stale
 *    "available" landing on top of the unreachable state a dropped connection
 *    just published. [refresh] is therefore safe to call from any thread.
 *
 * Before the first Connected edge the state is deliberately empty — no
 * availability and no spinner. Nothing has been asked yet, and "unreachable" is
 * an answer rather than the absence of one.
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

    /** One transition. Only the command loop may act on these. */
    private sealed interface Command {
        data class Status(val status: GatewayConnectionStatus) : Command
        data object Refresh : Command
        data class Settle(val generation: Long, val answer: Settled) : Command

        /** A cycle's coroutine ended, however it ended. Rule 1's backstop. */
        data class CycleEnded(val generation: Long) : Command
    }

    /** A cycle's finished answer, with the leg fact the copy for it depends on. */
    private data class Settled(val availability: RelayAvailability, val signInAvailable: Boolean)

    /**
     * Unbounded on purpose: a transition must never be dropped and must never
     * make its sender wait. The traffic is one command per connection edge, per
     * refresh, and per finished cycle.
     */
    private val commands = Channel<Command>(Channel.UNLIMITED)

    // Confined to the command loop below. Nothing else may read or write them.
    private var cycle: Job? = null
    private var generation = 0L

    /**
     * The manager seeds [GatewayConnectionStatus.Disconnected], so the first
     * emission repeats a status the app already knows. Seeding the same value
     * makes it what it is — not an edge — which is what leaves the cold-start
     * path free to reach [RelayAvailabilityState.awaitingFirstAnswer] on the
     * way to Connected instead of settling "unreachable" before anything was
     * ever asked.
     */
    private var observedStatus: GatewayConnectionStatus = GatewayConnectionStatus.Disconnected

    init {
        scope.launch {
            for (command in commands) {
                when (command) {
                    is Command.Status -> onConnectionStatus(command.status)
                    Command.Refresh -> applyStatus(observedStatus)
                    is Command.Settle -> onSettled(command.generation, command.answer)
                    is Command.CycleEnded -> onCycleEnded(command.generation)
                }
            }
            // Nothing drains the queue once this loop is gone, so stop
            // accepting: a later [refresh] fails to send rather than piling up
            // in a buffer nobody will ever read.
        }.invokeOnCompletion { commands.close() }
        scope.launch {
            connection.collect { commands.trySend(Command.Status(it.status)) }
        }
    }

    /**
     * Probe now — the surface became visible, or someone asked again. Safe to
     * call repeatedly and from any thread: this only queues a transition, and
     * a cycle already in flight is replaced, never stacked.
     *
     * "Again" means whatever the live connection status allows, which is why a
     * refresh does exactly what re-entering that status would: only a Connected
     * Gateway is worth probing, and asking a disconnected one costs nothing and
     * answers at once.
     */
    fun refresh() {
        commands.trySend(Command.Refresh)
    }

    /**
     * Only a *transition* is a signal. Re-emitting the same status is not a new
     * fact about the Gateway and must not cost a probe.
     */
    private fun onConnectionStatus(status: GatewayConnectionStatus) {
        if (status == observedStatus) return
        observedStatus = status
        applyStatus(status)
    }

    /**
     * What a given connection status means for the Relay state, whether it just
     * arrived or [refresh] asked what it currently implies.
     */
    private fun applyStatus(status: GatewayConnectionStatus) {
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
        val mine = ++generation
        // The whole cycle is one probe in flight, backoff included, so the
        // spinner belongs to the cycle rather than to each attempt inside it.
        _state.update { it.copy(probing = true) }
        val job = scope.launch { runProbeCycle(mine) }
        cycle = job
        job.invokeOnCompletion { commands.trySend(Command.CycleEnded(mine)) }
    }

    private fun stopProbe() {
        cycle?.cancel()
        cycle = null
        // Anything still in flight is superseded, whether or not its
        // cancellation has taken effect yet.
        generation++
        _state.update { it.copy(probing = false) }
    }

    /**
     * A cycle that already lost ownership must not write: cancellation is
     * cooperative, so an answer can arrive after the transition that replaced
     * it. The generation, not the arrival order, decides.
     */
    private fun onSettled(from: Long, answer: Settled) {
        if (from != generation) return
        cycle = null
        _state.value = RelayAvailabilityState(
            availability = answer.availability,
            probing = false,
            signInAvailable = answer.signInAvailable,
        )
    }

    /**
     * Rule 1 without trusting the probe seam to be well-behaved: a cycle that
     * ended without settling — cancelled from somewhere that is not
     * [startProbe] or [stopProbe], or failed in a way nothing else caught —
     * still clears the spinner it started. A cycle that already settled, or one
     * a newer transition superseded, has nothing left to clear.
     */
    private fun onCycleEnded(from: Long) {
        if (from != generation || cycle == null) return
        cycle = null
        _state.update { it.copy(probing = false) }
    }

    /**
     * A cycle that dies on the way to an answer would leave the surface
     * pending forever, which is the one outcome this controller exists to
     * prevent. Anything unexpected settles as honestly as it can: nothing
     * usable came back.
     */
    private suspend fun runProbeCycle(generation: Long) {
        val answer = try {
            probeUntilSettled()
        } catch (cancelled: CancellationException) {
            // A replaced or torn-down cycle is not a Gateway fault, and the
            // cycle replacing it owns the state from here.
            throw cancelled
        } catch (_: Throwable) {
            RelayAvailability.GatewayUnreachable
        }
        commands.trySend(Command.Settle(generation, Settled(answer, signInAvailableFor(answer))))
    }

    /**
     * Only a sign-in prompt depends on the leg, so only that answer pays the
     * question. A connection owner that cannot answer is treated as one with
     * no sign-in: reconnect copy is true on every leg, sign-in copy is not.
     */
    private suspend fun signInAvailableFor(answer: RelayAvailability): Boolean {
        if (answer !is RelayAvailability.SignInRequired) return false
        return try {
            credentials.signInAvailable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun probeUntilSettled(): RelayAvailability {
        var rotated = false
        var unreachable = 0
        while (true) {
            // A cycle cancelled between attempts stops here rather than
            // spending the next one; the caller treats that as "no answer".
            currentCoroutineContext().ensureActive()
            when (val answer = probe.availability()) {
                is RelayAvailability.SignInRequired -> {
                    // Only a credential the gate says lapsed is worth rotating;
                    // "nothing recognised" has nothing to rotate. Either way the
                    // budget is one rotation and one re-probe per cycle.
                    if (answer.reason == RelaySignInReason.SessionExpired && !rotated) {
                        rotated = true
                        if (credentials.refreshOnce()) continue
                    }
                    return answer
                }

                RelayAvailability.GatewayUnreachable -> {
                    unreachable++
                    if (unreachable >= MAX_UNREACHABLE_ATTEMPTS) {
                        return RelayAvailability.GatewayUnreachable
                    }
                    wait(RETRY_BACKOFF_MILLIS * unreachable)
                }

                else -> return answer
            }
        }
    }

    private companion object {
        /** Bounded on purpose: waiting forever is not a state a person can act on. */
        const val MAX_UNREACHABLE_ATTEMPTS = 3
        const val RETRY_BACKOFF_MILLIS = 1_000L
    }
}

/**
 * The one line a surface shows for a settled availability state — written by
 * this app, about a state this app owns.
 *
 * Every value here describes a *state*, never an error: a Gateway without the
 * plugin is a fact about that Gateway, so it belongs beside where Relay would
 * live and never in an error or toast channel. Null means the state has no
 * app-owned line at all: [RelayAvailability.Available] renders as the lane it
 * is, and whatever the lane itself said belongs in [statusDetail], beside that
 * state and never in place of it. No server-authored text is ever returned
 * here.
 *
 * [signInAvailable] is [RelayAvailabilityState.signInAvailable] — the
 * connection owner's answer to whether a sign-in on this device exists at all.
 * On managed SSH it does not, so a refusal asks for the reconnect that is the
 * real remedy rather than for a sign-in the app cannot offer. It has no default
 * on purpose: a surface must take it from the state that owns it, and
 * [RelayAvailabilityState.statusMessage] is the way to do that without
 * threading it by hand.
 */
fun RelayAvailability.statusMessage(signInAvailable: Boolean): String? = when (this) {
    is RelayAvailability.Available -> null
    RelayAvailability.Missing -> RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE
    is RelayAvailability.SignInRequired ->
        if (signInAvailable) RELAY_SIGN_IN_MESSAGE else TRANSPORT_DOWN_MESSAGE
    RelayAvailability.Incompatible -> RELAY_INCOMPATIBLE_MESSAGE
    RelayAvailability.GatewayUnreachable -> TRANSPORT_DOWN_MESSAGE
}

/** The line for this state, with the leg fact taken from the state itself. */
fun RelayAvailabilityState.statusMessage(): String? = availability?.statusMessage(signInAvailable)

/**
 * The lane's own explanation, shown *beside* [statusMessage] and never in place
 * of it — the contract [RelayChannelsStatus.message] is written under.
 *
 * This is the one string on the Relay surface a remote host authored, so it
 * gets the same treatment every other backend-authored display string gets:
 * redacted, collapsed to one line, and bounded. Null when the lane said nothing
 * usable.
 */
fun RelayAvailability.statusDetail(): String? = when (this) {
    is RelayAvailability.Available -> channels.message
        ?.let(::redact)
        ?.replace(DETAIL_WHITESPACE, " ")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.take(MAX_DETAIL_CHARS)

    RelayAvailability.Missing,
    is RelayAvailability.SignInRequired,
    RelayAvailability.Incompatible,
    RelayAvailability.GatewayUnreachable,
    -> null
}

/**
 * Separators, controls and format characters alike. `\s` is ASCII-only in Java,
 * which would let U+0085, U+00A0 and the U+2028/U+2029 line separators survive
 * the "one line" step, and would leave zero-width and bidi-override characters
 * in a host-authored string on its way to a screen.
 */
private val DETAIL_WHITESPACE = Regex("[\\p{Z}\\p{Cc}\\p{Cf}]+")

/** A lane explanation is a sentence beside a state, not a paragraph under one. */
private const val MAX_DETAIL_CHARS = 160

internal const val RELAY_UNAVAILABLE_ON_GATEWAY_MESSAGE = "Relay is unavailable on this Gateway."
internal const val RELAY_SIGN_IN_MESSAGE = "Sign in to this Gateway to open Relay."
internal const val RELAY_INCOMPATIBLE_MESSAGE =
    "Relay answered in a form this app does not support. Update Hermes and try again."
