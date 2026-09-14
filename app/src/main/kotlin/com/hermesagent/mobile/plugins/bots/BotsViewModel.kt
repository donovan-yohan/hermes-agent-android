package com.hermesagent.mobile.plugins.bots

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the roster's honest states the surface is in. */
enum class BotsRosterPhase {
    /**
     * No roster held, and nothing to report yet: either no answer has arrived,
     * or the Gateway is not up to ask. This is where a cold start begins, and
     * where it waits rather than reporting a failure.
     */
    Loading,

    /** A roster is held. */
    Ready,

    /** The Gateway answered, and there are no bots at all. */
    Empty,

    /** The roster could not be read while the connection was up. */
    Refused,

    /** This Gateway build does not serve `profiles.list`. */
    UnavailableOnGateway,
}

/** Everything the roster surface renders from. */
data class BotsRosterUiState(
    val phase: BotsRosterPhase = BotsRosterPhase.Loading,
    /** Visible rows, filed into user sections (Unassigned last). */
    val sections: List<BotSectionBlock> = emptyList(),
    /** The hidden rows, filed the same way — drawn only when revealed. */
    val hiddenSections: List<BotSectionBlock> = emptyList(),
    val presentation: RosterPresentationState = RosterPresentationState(),
    val searchQuery: String = "",
    val kindFilter: RosterKindFilter = RosterKindFilter.All,
    val activityFilter: RosterActivityFilter = RosterActivityFilter.All,
    val hiddenExpanded: Boolean = false,
    /** Roster keys the user pinned — the row draws Desktop's pin glyph. */
    val pinnedKeys: Set<String> = emptySet(),
    /** Attention badges by roster key; only classified failures appear here. */
    val attentionByKey: Map<String, BotAttention> = emptyMap(),
    /** This app's own sentence for a refused read, never the backend's. */
    val safeMessage: String? = null,
    /** Whether a live Gateway connection exists behind the plugin host door. */
    val connectionUp: Boolean = false,
    /** Product-safe result of the most recent read-only Bot Chat open attempt. */
    val botChatMessage: String? = null,
    /** The roster row currently resolving; independent of roster refresh state. */
    val openingBotKey: String? = null,
) {
    /**
     * A roster exists but the current query/filters match none of it.
     *
     * The hidden rows count as matches: Desktop draws its no-match card only
     * when neither the visible rows nor the *matching hidden* ones are left
     * (`rosterRows.length === 0 && matchingHiddenBots.length === 0`,
     * `roster-pane-content.tsx:106-120` @ the pin) — with hidden matches it
     * draws the hidden section instead, which is the whole point of expanding
     * it.
     */
    val filteredToNothing: Boolean
        get() = phase == BotsRosterPhase.Ready && sections.isEmpty() && hiddenSections.isEmpty()

    /**
     * A roster is held and the last refresh failed: Desktop keeps the last good
     * list and says so (`roster-pane.tsx`, `staleNotice`), rather than blanking
     * a roster the person already had. Only `Ready` can be stale — a failed
     * read with nothing held is a state message, not a banner.
     */
    val stale: Boolean get() = safeMessage != null && phase == BotsRosterPhase.Ready
}

/**
 * The roster's state holder.
 *
 * It owns no Gateway knowledge: [BotsPluginRepository] does the call, and every
 * ordering, filtering and grouping decision is a pure function in
 * `BotsRosterDerivation`. That split is what makes the derivation testable
 * without a connection.
 *
 * [metaByKey] and [sections] are local presentation state (Desktop's `$botMeta`
 * and `$botSections`). They are constructor inputs rather than mutable globals
 * so the roster's pin/hide/section behaviour is exercised by tests without
 * reaching for a singleton.
 */
class BotsViewModel(
    private val repository: BotsPluginRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sections: List<BotSection> = emptyList(),
    private val metaByKey: Map<String, BotMeta> = emptyMap(),
    private val attention: BotAttentionStore = BotAttentionStore(clock),
    /**
     * The live connection's readiness — `ctx.host.connected`.
     *
     * Every `true` is a read. The host refuses while no client exists, so a
     * cold start's first read lands in a refusal it could never leave; the
     * edge is what carries it into `Ready`, and what makes a bot created on
     * the Gateway since the last read appear at all.
     *
     * Desktop does the same thing at the same moment — "The socket opening
     * (boot, SSH reconnect, sleep/wake) is the signal to retry immediately
     * instead of waiting out the poll interval" (`roster-pane.tsx:274-279` @
     * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
     *
     * It is a `StateFlow` rather than any other `Flow` because a read can
     * arrive before the collector has had its first turn, and the refusal that
     * read produces has to be classified against the connection *now* — see
     * [refreshNow].
     */
    private val connected: StateFlow<Boolean> = MutableStateFlow(false),
    /**
     * Which endpoint the rows belong to — `ctx.host.endpointGeneration`, the
     * same generation `SessionCache` bumps on the app's one wholesale clear.
     *
     * The roster is an endpoint-scoped copy of backend truth: it deliberately
     * holds the last good list across a failed refresh
     * ([BotsRosterUiState.stale]). That rule is right for a transport redial and
     * wrong for a leave — the next endpoint is a different machine that can
     * recycle the same durable ids, so a row read from the machine this device
     * has left is not the new one's to draw, banner or no banner. `connected`
     * cannot tell the two apart: both drop the leg and bring one back.
     *
     * Which of the app's paths count as leaving is `SessionCache`'s own rule
     * and not this plugin's to invent: the generation this reads is the app's
     * own wholesale clear, so the roster drops exactly where the session cache
     * does — a switch, a re-address, a disconnect, a removal — and survives
     * exactly what the cache survives.
     */
    private val endpointGeneration: StateFlow<Long> = MutableStateFlow(0L),
) {
    private val _uiState = MutableStateFlow(BotsRosterUiState())
    val uiState: StateFlow<BotsRosterUiState> = _uiState.asStateFlow()

    /** The last roster the Gateway served. A failed refresh never clears it. */
    private var roster: List<BotRosterRow> = emptyList()

    /**
     * The endpoint [roster] belongs to — the generation a read was made under.
     *
     * It is what makes the drop idempotent and race-free: a switch landing
     * after a read of the *new* endpoint must not clear that read's rows, so
     * the clear happens only for a generation the held roster does not belong
     * to, however late the collector wakes up.
     */
    private var rosterEndpoint: Long = endpointGeneration.value

    /**
     * The generation the Gateway last *answered* a read in — `null` until it
     * answers one, and again after a drop.
     *
     * An empty roster has two meanings that must not be confused: "this
     * Gateway answered, and it has no bots" ([BotsRosterPhase.Empty]) and
     * "nothing has been asked of this endpoint yet"
     * ([BotsRosterPhase.Loading]). The roster alone cannot tell them apart, and
     * a search box or filter row survives a switch — so a keystroke over a
     * dropped roster would otherwise claim the first about the second.
     *
     * It is compared against the *current* generation rather than null-checked,
     * so it also answers correctly in the window before the collector below has
     * dropped anything: a generation that has moved is an endpoint this
     * Gateway's answer no longer belongs to, and the surface waits.
     */
    private var answeredEndpoint: Long? = null

    private var inFlight: Job? = null

    /** Set when a read arrives while one is already on the wire. */
    private var pending = false

    init {
        _uiState.update {
            it.copy(
                pinnedKeys = metaByKey.filterValues { meta -> meta.pinned }.keys,
                attentionByKey = attention.entries.value,
                connectionUp = connected.value,
            )
        }
        scope.launch {
            // The connection's edge and the endpoint's identity are one
            // collector because they are one question: *may I read, and is what
            // I am holding still from here?* A switch moves the endpoint while
            // the leg is down, so the drop cannot wait for the read that never
            // came — and the reconnect that follows must not bring the previous
            // machine's rows back with it.
            //
            // The drop answers to the generation's *current* value rather than
            // to the one this emission carries: a read that has already adopted
            // the new endpoint's rows bumps [rosterEndpoint] first, and a
            // collector waking late behind it must not clear them again.
            combine(connected, endpointGeneration) { up, _ -> up }
                .collect { up ->
                    dropRosterIfEndpointChanged()
                    _uiState.update { state ->
                        val rosterlessFailure =
                            state.phase == BotsRosterPhase.Refused ||
                                state.phase == BotsRosterPhase.UnavailableOnGateway
                        if (!up && roster.isEmpty() && rosterlessFailure) {
                            // With no cached rows and no live leg there is no
                            // actionable Gateway failure to report. Desktop
                            // gives its waiting state precedence here too.
                            state.copy(
                                phase = BotsRosterPhase.Loading,
                                safeMessage = null,
                                connectionUp = false,
                            )
                        } else {
                            state.copy(connectionUp = up)
                        }
                    }
                    if (up) refresh()
                }
        }
    }

    /**
     * Record a failed turn or delivery against a roster key.
     *
     * The badge's only producer: the classification decides whether anything is
     * stored at all, so a rate limit, a 5xx or a timeout leaves the roster
     * untouched. The turn/delivery pipeline that calls this lands with the chat
     * slice; the store and its rendering are wired here so the rule is live
     * rather than asserted only in a test.
     *
     * The state is re-read synchronously rather than mirrored through a
     * collector: a note and the badge it draws must land in the same frame, and
     * an async hop between them is a bug waiting for a slow dispatcher.
     */
    fun noteAttention(rosterKey: String, errorTextOrReason: String?) {
        attention.note(rosterKey, errorTextOrReason)
        syncAttention()
    }

    /** A good turn clears the badge. */
    fun clearAttention(rosterKey: String) {
        attention.clear(rosterKey)
        syncAttention()
    }

    private fun syncAttention() {
        val entries = attention.entries.value
        _uiState.update { it.copy(attentionByKey = entries) }
    }

    /**
     * Read the roster, one request at a time.
     *
     * A read that arrives while another is on the wire is remembered and runs
     * straight after it rather than being dropped: the connection edge lands
     * mid-read often enough (the cold start's own read is in flight when the
     * client is published) that dropping it would leave the roster stuck in
     * the state the edge exists to leave.
     */
    fun refresh() {
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
     * The surface became visible. Desktop refetches on its socket opening and
     * then on its poll; this app's roster is a destination that is entered and
     * left rather than a pane left mounted, so entering it is what asks the
     * Gateway again — a bot created since the last look appears on the next
     * one instead of never.
     */
    fun surfaceResumed() {
        refresh()
    }

    /**
     * Resolve the tapped bot's canonical chat and hand it to the chat screen.
     *
     * Since Phase B this is an open-or-create: a registry that confirms no chat
     * exists is what licenses `session.create` (in [BotsPluginRepository]),
     * and every other answer — ambiguous, refused, unavailable, unreadable — is
     * still just a report. Nothing is created, opened or navigated from a read
     * this app could not fully stand behind.
     *
     * The endpoint captured here is the fence for the whole attempt: the
     * repository consults it before each wire call after its first, and this
     * coroutine drops a late answer that belongs to a machine the app has left.
     */
    fun openBotChat(
        row: BotRosterRow,
        onOpen: (profile: String, durableId: String, onFinished: (Boolean) -> Unit) -> Unit,
    ) {
        if (_uiState.value.openingBotKey != null) return
        val endpoint = endpointGeneration.value
        _uiState.update { it.copy(openingBotKey = row.rosterKey, botChatMessage = null) }
        scope.launch {
            val outcome = repository.openCanonicalChat(
                profile = row.name,
                rosterCanonicalId = row.canonicalSession?.id,
            ) { endpoint == endpointGeneration.value }
            when (outcome) {
                is BotChatOpen.Opened -> if (endpoint == endpointGeneration.value) {
                    // Discovery and resume are one roster operation.  In
                    // particular, do not clear the row spinner just because
                    // the resume coroutine was launched.
                    onOpen(row.name, outcome.durableId) { opened ->
                        if (endpoint != endpointGeneration.value) return@onOpen
                        _uiState.update {
                            it.copy(
                                openingBotKey = null,
                                botChatMessage = if (opened) null else BOT_CHAT_OPEN_FAILED,
                            )
                        }
                    }
                    return@launch
                }

                BotChatOpen.Unsafe -> if (endpoint == endpointGeneration.value) {
                    _uiState.update { it.copy(botChatMessage = BOT_CHAT_OPEN_FAILED) }
                }
            }
            if (endpoint == endpointGeneration.value) _uiState.update { it.copy(openingBotKey = null) }
        }
    }

    /** [refresh] without the scope, so a test can await it deterministically. */
    suspend fun refreshNow() {
        // The endpoint flow can move before its collector gets a dispatcher
        // turn. Public entry points enforce the same boundary synchronously so
        // no presentation or direct refresh can expose the old machine's rows
        // in that window.
        dropRosterIfEndpointChanged()
        // The endpoint this read is being made against. A switch that lands
        // while the read is on the wire answers about a machine this device has
        // left, and that answer says nothing about the one it is on now —
        // whether it is a roster, a refusal or an absent method. Dropping the
        // answer is what keeps a stale roster from being re-adopted *after* the
        // switch already cleared it.
        val endpoint = endpointGeneration.value
        if (roster.isEmpty()) {
            _uiState.update { it.copy(phase = BotsRosterPhase.Loading) }
        }
        val load = repository.loadRoster()
        if (endpoint != endpointGeneration.value) return
        // A generation can also move after the request starts but before this
        // outcome is reduced. The equality above fences the answer; this call
        // fences any rows held before that answer.
        if (dropRosterIfEndpointChanged()) return
        when (load) {
            is BotsRosterLoad.Loaded -> {
                roster = load.rows
                rosterEndpoint = endpoint
                answeredEndpoint = endpoint
                recompute(rosterAnswered = true)
            }

            BotsRosterLoad.UnavailableOnGateway -> {
                // This endpoint has answered that it cannot supply a roster.
                // Rows from an earlier successful read on the same endpoint are
                // no longer actionable and must not reappear on the next
                // presentation-only recomputation.
                roster = emptyList()
                answeredEndpoint = null
                val now = clock()
                _uiState.update { state ->
                    derivedState(
                        from = state.copy(safeMessage = null),
                        whenEmpty = BotsRosterPhase.UnavailableOnGateway,
                        now = now,
                    )
                }
            }

            is BotsRosterLoad.Refused -> when {
                // A failed refresh keeps the last good roster; only a
                // roster-less failure is a full error state.
                roster.isNotEmpty() ->
                    _uiState.update { it.copy(safeMessage = load.safeMessage) }

                // No connection: nothing was asked of a Gateway, so this is
                // "waiting for the gateway connection…", not a failure. Desktop
                // picks its sentence the same way — the error card reads
                // `gatewayUp ? rosterUnavailable(…) : waitingForGateway`
                // (`roster-pane-content.tsx:84-90` @ the pin).
                !connected.value ->
                    _uiState.update {
                        it.copy(phase = BotsRosterPhase.Loading, safeMessage = null)
                    }

                else -> _uiState.update {
                    it.copy(phase = BotsRosterPhase.Refused, safeMessage = load.safeMessage)
                }
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        recompute()
    }

    fun setKindFilter(filter: RosterKindFilter) {
        _uiState.update { it.copy(kindFilter = filter) }
        recompute()
    }

    fun setActivityFilter(filter: RosterActivityFilter) {
        _uiState.update { it.copy(activityFilter = filter) }
        recompute()
    }

    fun setHiddenExpanded(expanded: Boolean) {
        _uiState.update { it.copy(hiddenExpanded = expanded) }
        recompute()
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                kindFilter = RosterKindFilter.All,
                activityFilter = RosterActivityFilter.All,
            )
        }
        recompute()
    }

    private fun recompute(rosterAnswered: Boolean = false) {
        // The drop already derives the current presentation fields from the
        // updated state, so a caller must not continue and overwrite Loading
        // with a stale answer's phase.
        if (dropRosterIfEndpointChanged()) return
        // One clock read per derivation: `update`'s block may be evaluated more
        // than once under a concurrent writer, and the rows' activity bands
        // must not move between two attempts at the same list.
        val now = clock()
        _uiState.update { state ->
            val from = if (rosterAnswered) state.copy(safeMessage = null) else state
            val whenEmpty =
                if (rosterAnswered) BotsRosterPhase.Empty else emptyRosterPhase(state.phase)
            derivedState(
                from = from,
                // Only an answer may replace a terminal or waiting state with
                // Empty. Presentation changes merely re-derive what is held.
                whenEmpty = whenEmpty,
                now = now,
            )
        }
    }

    /**
     * What a roster-less surface is claiming during presentation-only changes.
     * A terminal response or an explicit wait remains in force; only a real
     * roster answer may replace either with [BotsRosterPhase.Empty].
     *
     * Only [answeredEndpoint] can tell them apart — see its KDoc. The endpoint
     * it was answered in has to be *this* one, not merely answered at some
     * point in the past.
     */
    private fun emptyRosterPhase(previous: BotsRosterPhase): BotsRosterPhase =
        when (previous) {
            BotsRosterPhase.Loading -> BotsRosterPhase.Loading
            BotsRosterPhase.Refused,
            BotsRosterPhase.UnavailableOnGateway,
            -> if (rosterEndpoint == endpointGeneration.value) {
                previous
            } else {
                BotsRosterPhase.Loading
            }
            BotsRosterPhase.Ready,
            BotsRosterPhase.Empty,
            -> if (answeredEndpoint == endpointGeneration.value) {
                BotsRosterPhase.Empty
            } else {
                BotsRosterPhase.Loading
            }
        }

    /** Returns true when this call performed the endpoint-boundary drop. */
    private fun dropRosterIfEndpointChanged(): Boolean {
        if (endpointGeneration.value == rosterEndpoint) return false
        dropRosterForEndpointSwitch()
        return true
    }

    /**
     * Forget the roster, because this device has changed endpoint.
     *
     * Every row on screen was the previous machine's, and the endpoint this
     * app just left is the one thing a merge cannot reconcile: a different
     * Gateway recycles the same durable ids
     * (`SessionCache.resetForEndpointSwitch` is the app's one wholesale clear),
     * so the rows are dropped rather than re-pointed. The stale notice goes
     * with them — it said these rows were old, and there are no longer any rows
     * of *this* endpoint's to be old.
     *
     * The attention badges go too: [attention] is keyed by roster key alone,
     * so the previous machine's failure badges would otherwise paint on the new
     * one's same-named bot. Every row on screen was the previous machine's, and
     * everything drawn *on* those rows is dropped with them.
     *
     * What is left is [BotsRosterPhase.Loading], deliberately not
     * [BotsRosterPhase.Empty]: nothing has been asked of the new endpoint yet,
     * and those are not the same claim to the person — which is also why
     * [answeredEndpoint] is cleared, so re-deriving an empty roster from a
     * keystroke cannot make that claim either. The connection's own edge is
     * what asks — see the collector in `init`.
     */
    private fun dropRosterForEndpointSwitch() {
        roster = emptyList()
        rosterEndpoint = endpointGeneration.value
        answeredEndpoint = null
        attention.clearAll()
        val now = clock()
        _uiState.update { state ->
            derivedState(
                // An endpoint switch invalidates the operation as well as its
                // rows. Do not carry a prior Gateway's spinner or result into
                // the new endpoint through derivedState's otherwise useful
                // presentation copy.
                from = state.copy(
                    safeMessage = null,
                    attentionByKey = emptyMap(),
                    openingBotKey = null,
                    botChatMessage = null,
                ),
                // A switch invalidates even a terminal answer from the old
                // endpoint, so start the new one from Loading explicitly.
                whenEmpty = BotsRosterPhase.Loading,
                now = now,
            )
        }
    }

    /**
     * The surface's own fields, re-derived from [roster].
     *
     * [whenEmpty] is the phase a roster-less surface is in. [now] is passed in
     * rather than read here for the same reason: one derivation, one instant.
     */
    private fun derivedState(
        from: BotsRosterUiState,
        whenEmpty: BotsRosterPhase,
        now: Long,
    ): BotsRosterUiState {
        val derived = deriveRosterRows(
            roster = roster,
            metaByKey = metaByKey,
            query = from.searchQuery,
            kindFilter = from.kindFilter,
            activityFilter = from.activityFilter,
            nowMillis = now,
        )
        // Normalized once: the same list feeds the block count and both
        // groupings, and `normalizeBotSections` is the one place that decides
        // what a section is.
        val normalizedSections = normalizeBotSections(sections)
        val presentation = deriveRosterPresentation(
            rosterSize = roster.size,
            visibleRosterSize = derived.visibleRows.size,
            hiddenRowsSize = derived.hiddenRows.size,
            filteredHiddenRows = derived.filteredHidden,
            query = from.searchQuery,
            kindFilter = from.kindFilter,
            activityFilter = from.activityFilter,
            hiddenExpanded = from.hiddenExpanded,
            userSectionCount = normalizedSections.size,
        )
        return from.copy(
            phase = if (roster.isEmpty()) whenEmpty else BotsRosterPhase.Ready,
            sections = groupRowsBySection(derived.filteredVisible, normalizedSections, metaByKey),
            hiddenSections = groupRowsBySection(derived.filteredHidden, normalizedSections, metaByKey),
            presentation = presentation,
        )
    }
}

/**
 * What a roster row says when its canonical chat could not be resolved.
 *
 * One sentence for both a read that failed and a creation that could not be
 * confirmed: from here they are the same outcome — nothing was opened — and
 * the row stays actionable for a retry.
 */
private const val BOT_CHAT_OPEN_FAILED = "Bot Chat could not be opened. Check the Gateway and try again."
