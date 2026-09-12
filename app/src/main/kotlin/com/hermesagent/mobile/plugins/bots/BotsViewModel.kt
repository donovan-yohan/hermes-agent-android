package com.hermesagent.mobile.plugins.bots

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    private val _uiState = MutableStateFlow(BotsRosterUiState())
    val uiState: StateFlow<BotsRosterUiState> = _uiState.asStateFlow()

    /** The last roster the Gateway served. A failed refresh never clears it. */
    private var roster: List<BotRosterRow> = emptyList()

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
            connected.collect { up ->
                _uiState.update { it.copy(connectionUp = up) }
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

    /** [refresh] without the scope, so a test can await it deterministically. */
    suspend fun refreshNow() {
        if (roster.isEmpty()) {
            _uiState.update { it.copy(phase = BotsRosterPhase.Loading) }
        }
        when (val load = repository.loadRoster()) {
            is BotsRosterLoad.Loaded -> {
                roster = load.rows
                recompute()
                // Only an answer clears the notice: a filter change or a
                // keystroke in the search box re-derives the same list, and
                // must not dismiss a banner that is still true.
                _uiState.update { it.copy(safeMessage = null) }
            }

            BotsRosterLoad.UnavailableOnGateway -> _uiState.update {
                it.copy(phase = BotsRosterPhase.UnavailableOnGateway, safeMessage = null)
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
                !_uiState.value.connectionUp ->
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

    private fun recompute() {
        val current = _uiState.value
        val now = clock()
        val derived = deriveRosterRows(
            roster = roster,
            metaByKey = metaByKey,
            query = current.searchQuery,
            kindFilter = current.kindFilter,
            activityFilter = current.activityFilter,
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
            query = current.searchQuery,
            kindFilter = current.kindFilter,
            activityFilter = current.activityFilter,
            hiddenExpanded = current.hiddenExpanded,
            userSectionCount = normalizedSections.size,
        )
        _uiState.update {
            it.copy(
                phase = if (roster.isEmpty()) BotsRosterPhase.Empty else BotsRosterPhase.Ready,
                sections = groupRowsBySection(derived.filteredVisible, normalizedSections, metaByKey),
                hiddenSections = groupRowsBySection(derived.filteredHidden, normalizedSections, metaByKey),
                presentation = presentation,
            )
        }
    }
}
