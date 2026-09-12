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
    /** No answer yet, and no roster to show meanwhile. */
    Loading,

    /** A roster is held. */
    Ready,

    /** The Gateway answered, and there are no bots at all. */
    Empty,

    /** The roster could not be read. */
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
) {
    /** A roster exists but the current query/filters match none of it. */
    val filteredToNothing: Boolean get() = phase == BotsRosterPhase.Ready && sections.isEmpty()
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
) {
    private val _uiState = MutableStateFlow(BotsRosterUiState())
    val uiState: StateFlow<BotsRosterUiState> = _uiState.asStateFlow()

    /** The last roster the Gateway served. A failed refresh never clears it. */
    private var roster: List<BotRosterRow> = emptyList()

    private var inFlight: Job? = null

    init {
        _uiState.update {
            it.copy(
                pinnedKeys = metaByKey.filterValues { meta -> meta.pinned }.keys,
                attentionByKey = attention.entries.value,
            )
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

    /** Read the roster, one request at a time. */
    fun refresh() {
        if (inFlight?.isActive == true) {
            return
        }
        inFlight = scope.launch { refreshNow() }
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
            }

            BotsRosterLoad.UnavailableOnGateway -> _uiState.update {
                it.copy(phase = BotsRosterPhase.UnavailableOnGateway, safeMessage = null)
            }

            is BotsRosterLoad.Refused -> {
                // A failed refresh keeps the last good roster; only a roster-less
                // failure is a full error state.
                if (roster.isEmpty()) {
                    _uiState.update {
                        it.copy(phase = BotsRosterPhase.Refused, safeMessage = load.safeMessage)
                    }
                } else {
                    _uiState.update { it.copy(safeMessage = load.safeMessage) }
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
        val presentation = deriveRosterPresentation(
            rosterSize = roster.size,
            visibleRosterSize = derived.visibleRows.size,
            hiddenRowsSize = derived.hiddenRows.size,
            filteredHiddenRows = derived.filteredHidden,
            query = current.searchQuery,
            kindFilter = current.kindFilter,
            activityFilter = current.activityFilter,
            hiddenExpanded = current.hiddenExpanded,
        )
        _uiState.update {
            it.copy(
                phase = if (roster.isEmpty()) BotsRosterPhase.Empty else BotsRosterPhase.Ready,
                sections = groupRowsBySection(derived.filteredVisible, sections, metaByKey),
                hiddenSections = groupRowsBySection(derived.filteredHidden, sections, metaByKey),
                presentation = presentation,
                safeMessage = null,
            )
        }
    }
}
