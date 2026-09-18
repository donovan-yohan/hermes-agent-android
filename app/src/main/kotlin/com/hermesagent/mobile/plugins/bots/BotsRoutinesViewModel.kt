package com.hermesagent.mobile.plugins.bots

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which of the Routines surface's honest states it is in.
 *
 * The five the slice owes, in Desktop's own order of precedence
 * (`cron.tsx:1282-1309`): waiting, then a read failure with nothing held, then
 * an empty store, then the list — with a stale banner drawn *over* any state
 * that still holds rows.
 */
enum class BotsRoutinesPhase {
    /**
     * No list held, and nothing to report yet: no answer has arrived, or the
     * Gateway is not up to ask. A cold start begins here and waits here rather
     * than reporting a failure.
     */
    Loading,

    /** A list is held. */
    Ready,

    /** The Gateway answered, and this bot has no routines. */
    Empty,

    /** The list could not be read while the connection was up. */
    Refused,

    /** This Gateway build does not serve `cron.manage`. */
    UnavailableOnGateway,

    /**
     * The Gateway answered, and the answer was not this bot's store — its
     * `scoped` echo named a different profile. Its own state rather than
     * `Refused`, because this one is not a failure to retry: the same call gets
     * the same answer, and telling somebody to try again would be a lie.
     */
    MismatchedScope,

    /** The Gateway answered `success:false`: a rejection, not a broken network. */
    Rejected,
}

/** Everything the Routines surface renders from. */
data class BotsRoutinesUiState(
    /** The bot whose store this is, or null before one has been chosen. */
    val owner: String? = null,
    val phase: BotsRoutinesPhase = BotsRoutinesPhase.Loading,
    /** The rows the Gateway served for this bot, as it sent them, unfiltered. */
    val all: List<RoutineRow> = emptyList(),
    /** [all] narrowed to this bot — Desktop's `selectRoutineJobs`. */
    val jobs: List<RoutineRow> = emptyList(),
    /** The Gateway's scope receipt. Compared, never rendered. */
    val scoped: String? = null,
    /** Desktop's explanation for an empty view over a non-empty store. */
    val filterHint: String? = null,
    /** This app's own sentence for a refused read, never the backend's. */
    val safeMessage: String? = null,
    /** Whether a live Gateway connection exists behind the plugin host door. */
    val connectionUp: Boolean = false,
    /** Which Gateway the rows belong to. */
    val endpointGeneration: Long = 0L,
) {
    /**
     * A list is held and the last refresh failed: Desktop keeps the last good
     * list and says so (`cron.tsx:1246`, `:1277-1281`) rather than blanking a
     * list the person already had. Only [BotsRoutinesPhase.Ready] can be stale
     * — a failed read with nothing held is a state message, not a banner.
     */
    val stale: Boolean get() = safeMessage != null && phase == BotsRoutinesPhase.Ready

    /** Nothing is held and there is nothing to show yet. */
    val waits: Boolean get() = phase == BotsRoutinesPhase.Loading && all.isEmpty()

    /** The empty state's explanation: the filter hint when there is one. */
    val emptyHint: String? get() = if (phase == BotsRoutinesPhase.Empty) filterHint else null
}

/**
 * The Routines surface's state holder.
 *
 * It owns no Gateway knowledge — [BotsPluginRepository] makes the call and
 * [selectRoutineJobs] decides what is this bot's — and, like the roster's, it
 * keeps exactly one piece of backend truth: the last list the Gateway actually
 * served, with its own endpoint generation beside it.
 *
 * **The owner is plugin state, not a singleton.** Desktop routes this pane
 * through module-global atoms (`$focusedBotOwner`, `$selectedBot`,
 * `cron.tsx:42`, `:1198-1207`); this app's plugin contract has no module
 * globals — `BotsPluginTest` asserts the plugin declares no mutable statics —
 * so the selected bot lives here, on the instance the route contribution
 * renders, and dies with the plugin. That is the whole reason it is an instance
 * field and not a companion.
 *
 * **Nothing here is persisted.** Which bot's routines are on screen is
 * transient view state: it is forgotten with the view, exactly like the roster
 * search box, and never reaches `PluginStorage`.
 */
class BotsRoutinesViewModel(
    private val repository: BotsPluginRepository,
    private val scope: CoroutineScope,
    /**
     * The live connection's readiness — `ctx.host.connected`. The door refuses
     * while no client exists, so a cold start's read lands in a refusal it
     * could not leave without this edge; Desktop's refetch-on-socket-open is
     * the same signal (`roster-pane.tsx:274-279`).
     */
    private val connected: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * Which endpoint the rows belong to — `ctx.host.endpointGeneration`. The
     * next Gateway is a different machine that can recycle the same job ids, so
     * a list read from the one this device has left is not the new one's to
     * draw (`SessionCache.resetForEndpointSwitch` is the app's one clear).
     */
    private val endpointGeneration: StateFlow<Long> = MutableStateFlow(0L),
) {
    private val _uiState = MutableStateFlow(BotsRoutinesUiState())
    val uiState: StateFlow<BotsRoutinesUiState> = _uiState.asStateFlow()

    /** The last list the Gateway served for [ownerProfile]. A failed refresh never clears it. */
    private var jobs: List<RoutineRow> = emptyList()

    /**
     * The scope receipt that came with [jobs], or null when the Gateway echoed
     * none.
     *
     * Kept *beside* the rows rather than only on the surface state, because it
     * is what those rows mean: a successfully scoped list of untagged jobs is
     * this bot's own store, and re-rendering it after a failed refresh with the
     * receipt forgotten would run the legacy tag filter over it and hide every
     * one of them — turning a stale list into an apparently empty one.
     */
    private var jobsScoped: String? = null

    /** The endpoint [jobs] belongs to. */
    private var jobsEndpoint: Long = endpointGeneration.value

    /** The bot [jobs] belongs to, or null when no bot has been chosen. */
    private var ownerProfile: String? = null

    /**
     * The connection's edge is a *transition*, not a level: `up` becoming true
     * is the event. A model constructed while the connection is already up must
     * not treat its first emission as an edge — a selection made in that same
     * instant started its own read, and the edge would spend a second one.
     */
    private var wasConnected: Boolean = connected.value

    private var inFlight: Job? = null

    /** Set when a read arrives while one is already on the wire. */
    private var pending = false

    init {
        _uiState.update { it.copy(connectionUp = connected.value, endpointGeneration = endpointGeneration.value) }
        scope.launch {
            // One collector for one question: may I read, and is what I am
            // holding still from here? A switch moves the endpoint while the
            // leg is down, so the drop cannot wait for a read that never came —
            // and the reconnect that follows must not bring the previous
            // machine's rows back with it.
            combine(connected, endpointGeneration) { up, _ -> up }
                .collect { up -> collectConnectionState(up) }
        }
    }

    private fun collectConnectionState(up: Boolean) {
        dropIfEndpointChanged()
        val edge = up && !wasConnected
        wasConnected = up
        _uiState.update { state ->
            val rowlessFailure = state.phase != BotsRoutinesPhase.Loading &&
                state.phase != BotsRoutinesPhase.Ready &&
                state.phase != BotsRoutinesPhase.Empty
            if (!up && jobs.isEmpty() && rowlessFailure) {
                // No rows and no live leg: there is no actionable Gateway
                // failure to report, and Desktop gives its waiting state
                // precedence here too.
                state.copy(phase = BotsRoutinesPhase.Loading, safeMessage = null, connectionUp = false)
            } else {
                state.copy(connectionUp = up)
            }
        }
        // The edge exists to leave a state the surface could not leave itself:
        // a bot was selected while the leg was down, so its read was refused
        // against no client and nothing has asked about it since. Asking here
        // is the whole reason this is a `StateFlow` and not an event.
        if (edge) refresh()
    }

    /**
     * Show [profile]'s routines.
     *
     * Re-selecting the bot already on screen is a refresh, not a reset: this
     * destination is entered and left, and blanking the list on the way back in
     * would flash an empty screen over rows that are about to be replaced.
     */
    fun selectOwner(profile: String) {
        val selected = profile.trim()
        if (selected.isEmpty()) return
        // The same boundary the read takes, taken synchronously first: a
        // selection that arrives after the endpoint moved must not be compared
        // against — or reuse the rows of — a bot selected on the machine this
        // device has left.
        dropIfEndpointChanged()
        if (selected == ownerProfile) {
            refresh()
            return
        }
        ownerProfile = selected
        jobs = emptyList()
        jobsScoped = null
        jobsEndpoint = endpointGeneration.value
        _uiState.update { state ->
            state.copy(
                owner = selected,
                phase = BotsRoutinesPhase.Loading,
                all = emptyList(),
                jobs = emptyList(),
                scoped = null,
                filterHint = null,
                safeMessage = null,
            )
        }
        refresh()
    }

    /**
     * Read the selected bot's routines, one request at a time.
     *
     * A read that arrives while another is on the wire is remembered and runs
     * straight after it rather than being dropped: the connection edge lands
     * mid-read often enough that dropping it would leave the surface stuck in
     * the state the edge exists to leave.
     */
    fun refresh() {
        // The endpoint boundary, synchronously, before anything is queued: a
        // refresh that arrives after the endpoint moved must not read the
        // previous machine's bot at the new one.
        dropIfEndpointChanged()
        if (ownerProfile == null) return
        if (inFlight?.isActive == true) {
            pending = true
            return
        }
        inFlight = scope.launch {
            do {
                pending = false
                refreshNow()
            } while (pending)
        }
    }

    /**
     * The surface became visible. This destination is entered and left rather
     * than left mounted, so entering it is what asks the Gateway again — a
     * routine created since the last look appears on the next one.
     */
    fun surfaceResumed() {
        refresh()
    }

    /** [refresh] without the scope, so a test can await it deterministically. */
    suspend fun refreshNow() {
        // The endpoint boundary is applied *before* the owner is read, and not
        // after: a drop clears the selected bot, because a profile name read
        // from the machine this device has left must not be aimed at the new
        // one. Reading the owner first would capture exactly that stale name and
        // send it to the replacement Gateway.
        dropIfEndpointChanged()
        val profile = ownerProfile ?: return
        val endpoint = endpointGeneration.value
        if (jobs.isEmpty()) {
            _uiState.update { it.copy(phase = BotsRoutinesPhase.Loading) }
        }
        val load = repository.loadRoutines(profile, endpoint)
        // A switch that lands while the read is on the wire answers about a
        // machine this device has left, and that answer says nothing about the
        // one it is on now — whether it is a list, a refusal or an absent
        // method. The equality fences the answer; the drop fences any rows held
        // before it. A bot switch is the same rule one level down.
        if (endpoint != endpointGeneration.value) return
        if (dropIfEndpointChanged()) return
        if (profile != ownerProfile) return
        when (load) {
            is BotsRoutinesLoad.Loaded -> {
                jobs = load.jobs
                jobsScoped = load.scoped
                jobsEndpoint = endpoint
                recompute(scoped = load.scoped, safeMessage = null, phase = null)
            }

            BotsRoutinesLoad.UnavailableOnGateway -> {
                // This endpoint has answered that it cannot serve routines.
                // Rows from an earlier successful read on the same endpoint are
                // no longer actionable and must not reappear on the next
                // presentation-only recomputation.
                jobs = emptyList()
                jobsScoped = null
                recompute(scoped = null, safeMessage = null, phase = BotsRoutinesPhase.UnavailableOnGateway)
            }

            is BotsRoutinesLoad.Refused -> when {
                // A failed refresh keeps the last good list — with the scope
                // receipt it arrived under, so a successfully scoped store of
                // untagged jobs does not get tag-filtered away on the way back
                // out and read as an empty bot.
                jobs.isNotEmpty() -> recompute(scoped = jobsScoped, safeMessage = load.safeMessage, phase = null)

                // No connection: nothing was asked of a Gateway, so this is
                // "waiting for the gateway connection…", not a failure.
                !connected.value -> recompute(scoped = null, safeMessage = null, phase = BotsRoutinesPhase.Loading)

                else -> recompute(
                    scoped = null,
                    safeMessage = load.safeMessage,
                    phase = BotsRoutinesPhase.Refused,
                )
            }

            BotsRoutinesLoad.Rejected ->
                recompute(scoped = null, safeMessage = null, phase = BotsRoutinesPhase.Rejected)

            is BotsRoutinesLoad.MismatchedScope -> {
                // Never render any part of another profile's store, and hold no
                // rows either: a held list would be the previous answer's, and
                // a stale banner over it would suggest this bot has routines a
                // retry could show.
                jobs = emptyList()
                jobsScoped = null
                recompute(scoped = null, safeMessage = null, phase = BotsRoutinesPhase.MismatchedScope)
            }
        }
    }

    /**
     * Re-derive the rendered fields from [jobs].
     *
     * [phase] is the outcome's own state, or null when the outcome *was* an
     * answer — in which case the state follows what the surface will actually
     * show: [BotsRoutinesPhase.Ready] when this bot has routines, and
     * [BotsRoutinesPhase.Empty] when the store answered and none of them are
     * this bot's. That is Desktop's own precedence — it renders its list or its
     * empty card from the *filtered* rows (`cron.tsx:1297-1318`), so a bot whose
     * routines are all someone else's sees the empty card and its hint, never
     * another bot's rows.
     */
    private fun recompute(scoped: String?, safeMessage: String?, phase: BotsRoutinesPhase?) {
        val profile = ownerProfile ?: return
        val selected = selectRoutineJobs(jobs, scoped, profile)
        _uiState.update { state ->
            state.copy(
                owner = profile,
                endpointGeneration = endpointGeneration.value,
                phase = phase ?: if (selected.isEmpty()) BotsRoutinesPhase.Empty else BotsRoutinesPhase.Ready,
                all = jobs,
                jobs = selected,
                scoped = scoped,
                filterHint = routineFilterHint(jobs, selected),
                safeMessage = safeMessage,
            )
        }
    }

    /** Returns true when this call performed the endpoint-boundary drop. */
    private fun dropIfEndpointChanged(): Boolean {
        if (endpointGeneration.value == jobsEndpoint) return false
        dropForEndpointSwitch()
        return true
    }

    /**
     * Forget the list, because this device has changed endpoint.
     *
     * Every row on screen was the previous machine's, and a different Gateway
     * recycles the same job ids, so the rows are dropped rather than
     * re-pointed. The selected bot is dropped with them: profile names are the
     * *new* Gateway's roster's to confirm, and holding a name read from the
     * machine this device left would aim the next read at the old one's answer.
     *
     * What is left is [BotsRoutinesPhase.Loading], deliberately not
     * [BotsRoutinesPhase.Empty]: nothing has been asked of the new endpoint yet,
     * and those are not the same claim to the person. The connection's own edge
     * asks — see [collectConnectionState].
     */
    private fun dropForEndpointSwitch() {
        jobs = emptyList()
        jobsScoped = null
        jobsEndpoint = endpointGeneration.value
        ownerProfile = null
        _uiState.update { state ->
            state.copy(
                owner = null,
                phase = BotsRoutinesPhase.Loading,
                all = emptyList(),
                jobs = emptyList(),
                scoped = null,
                filterHint = null,
                safeMessage = null,
                // The surface names the endpoint its content belongs to, and
                // after a drop that is nothing: the field states the reset
                // rather than the generation this model happens to be reading.
                endpointGeneration = 0L,
            )
        }
    }
}
