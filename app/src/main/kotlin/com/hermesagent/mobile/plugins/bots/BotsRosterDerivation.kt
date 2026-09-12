package com.hermesagent.mobile.plugins.bots

/**
 * The roster's pure derivation: which rows are visible, which are hidden, how
 * they are filtered, ordered and filed into sections.
 *
 * Ports of Desktop at `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`:
 * `apps/desktop/src/plugins/hermes-bots/roster-pane-derivation.ts` (rows and
 * presentation), `user-sections.ts` (`groupRowsBySection`) and
 * `hidden-bots.ts:23-31` (the pin/hide predicates).
 *
 * Two deliberate narrowings, both because this app has one Gateway connection
 * and no group chats yet:
 *
 * - The gateway axis is absent. Desktop's `filterBotsByGateway` and
 *   `rosterGatewaySections` exist to split rows across several registered
 *   connections; with one connection every row is on it, so the axis is the
 *   identity function and is simply not modelled.
 * - Group rows are absent. [RosterKindFilter.Groups] is kept because it is a
 *   user-visible filter Desktop ships, and it correctly selects nothing here
 *   rather than being quietly dropped.
 */

/** Source-qualified identity for a row. `data.ts:1195-1202` @ the pin. */
fun botRosterKey(row: BotRosterRow): String =
    "${row.connectionId ?: "legacy"}::${row.name.ifEmpty { "default" }}"

/**
 * The session whose activity best represents this bot — the FRESHER of the
 * canonical Bot Chat and the profile's newest visible conversation.
 *
 * Canonical Bot Chats are hidden from the session list by design, so keying
 * activity off `last_session` alone makes a bot you just messaged read
 * "6d ago" (`data.ts:1400-1420` @ the pin). Older gateways without a
 * `canonical_session` field degrade to `last_session` unchanged.
 */
fun botActivitySession(row: BotRosterRow): BotSessionPreview? {
    val preferred = row.canonicalSession
    val last = row.lastSession
    if (preferred == null || last == null) {
        return preferred ?: last
    }
    return if (preferred.lastActiveSeconds >= last.lastActiveSeconds) preferred else last
}

// ── pin and hide ─────────────────────────────────────────────────────────────
//
// Hiding is a ROSTER-DISPLAY concern only: a hidden bot keeps working, remains
// mentionable, keeps its memberships, and any open chat stays open.
// `hidden-bots.ts:23-31` @ the pin.

fun isBotHidden(row: BotRosterRow, metaByKey: Map<String, BotMeta>): Boolean =
    metaByKey[row.rosterKey]?.hidden == true

fun isBotPinned(row: BotRosterRow, metaByKey: Map<String, BotMeta>): Boolean =
    metaByKey[row.rosterKey]?.pinned == true

/** The section a bot is filed under, or null for Unassigned. */
fun botSectionId(row: BotRosterRow, metaByKey: Map<String, BotMeta>): String? =
    metaByKey[row.rosterKey]?.sectionId?.takeIf { it.isNotEmpty() }

// ── filters ──────────────────────────────────────────────────────────────────

/**
 * Filter by the two stable identities rendered in every row: the customizable
 * display name and the profile's @handle. Desktop also matches the source
 * label, the role/description text and the session preview
 * (`data.ts:1368-1398` @ the pin); all of them survive here, because this app
 * renders or will render each one. On a single connection the source label is
 * null, which matches nothing — the arm is kept so a second source needs no
 * change to the search.
 *
 * Search narrows the roster; it never re-ranks it.
 */
fun filterBots(rows: List<BotRosterRow>, query: String): List<BotRosterRow> {
    val needle = query.trim().lowercase().removePrefix("@")
    if (needle.isEmpty()) {
        return rows
    }
    return rows.filter { row ->
        displayName(row.name, row.displayName).lowercase().contains(needle) ||
            row.name.lowercase().contains(needle) ||
            row.handle.lowercase().contains(needle) ||
            row.connectionLabel.orEmpty().lowercase().contains(needle) ||
            row.description.lowercase().contains(needle) ||
            displayPreview(row.activity?.preview).lowercase().contains(needle)
    }
}

/** True while the bot's freshest activity is inside the liveness window. */
fun isActiveNow(row: BotRosterRow, nowMillis: Long): Boolean {
    val last = row.lastActiveMillis ?: return false
    return nowMillis - last <= BotsRosterLimits.ACTIVE_WINDOW_SECONDS * 1000L
}

/**
 * Match a row against the activity filter.
 *
 * Desktop ORs live turn/worker liveness into `active`
 * (`roster-pane-derivation.ts`, `row-helpers.ts:170-186` @ the pin); those
 * signals arrive with the live-state slice, so here `active` is the age window
 * alone. `Recent` is a seven-day window and `Older` is its complement, exactly
 * as Desktop defines them.
 */
fun rosterActivityMatches(
    row: BotRosterRow,
    filter: RosterActivityFilter,
    nowMillis: Long,
): Boolean {
    if (filter == RosterActivityFilter.All) {
        return true
    }
    if (filter == RosterActivityFilter.Active) {
        return isActiveNow(row, nowMillis)
    }
    val activity = row.lastActiveMillis ?: 0L
    val recent = activity > 0L &&
        nowMillis - activity <= BotsRosterLimits.RECENT_ACTIVITY_WINDOW_SECONDS * 1000L
    return if (filter == RosterActivityFilter.Recent) recent else !recent
}

/** Messaging-app order: pinned first, then most recent activity first. */
fun sortBotsForRoster(
    rows: List<BotRosterRow>,
    metaByKey: Map<String, BotMeta>,
): List<BotRosterRow> = rows.sortedWith(
    compareByDescending<BotRosterRow> { if (isBotPinned(it, metaByKey)) 1 else 0 }
        .thenByDescending { it.lastActiveMillis ?: 0L },
)

/** The rows one derivation pass produced. */
data class DerivedRosterRows(
    /** Every hidden row, unfiltered — the all-hidden state reads this. */
    val hiddenRows: List<BotRosterRow>,
    /** Every visible row, unfiltered — the all-hidden state reads this too. */
    val visibleRows: List<BotRosterRow>,
    /** Visible rows after the query, kind and activity filters, in roster order. */
    val filteredVisible: List<BotRosterRow>,
    /** Hidden rows after the same filters. */
    val filteredHidden: List<BotRosterRow>,
)

/**
 * Split the roster into hidden and visible, filter both, and order the visible
 * side. Hidden rows remain fully alive and recoverable at the bottom; every
 * non-display consumer continues to receive the complete roster.
 */
fun deriveRosterRows(
    roster: List<BotRosterRow>,
    metaByKey: Map<String, BotMeta>,
    query: String,
    kindFilter: RosterKindFilter,
    activityFilter: RosterActivityFilter,
    nowMillis: Long,
): DerivedRosterRows {
    val hiddenRows = roster.filter { isBotHidden(it, metaByKey) }
    val visibleRows = roster.filterNot { isBotHidden(it, metaByKey) }

    fun narrow(candidates: List<BotRosterRow>): List<BotRosterRow> =
        filterBots(candidates, query).filter { rosterActivityMatches(it, activityFilter, nowMillis) }

    // The kind axis: with no group rows yet, "Groups only" selects nothing and
    // "Bots only" selects every row. Stated rather than special-cased, so the
    // filter keeps Desktop's meaning when groups land.
    val filteredVisible = when (kindFilter) {
        RosterKindFilter.Groups -> emptyList()
        RosterKindFilter.All, RosterKindFilter.Bots -> sortBotsForRoster(narrow(visibleRows), metaByKey)
    }
    val filteredHidden =
        if (kindFilter == RosterKindFilter.Groups) emptyList() else narrow(hiddenRows)

    return DerivedRosterRows(
        hiddenRows = hiddenRows,
        visibleRows = visibleRows,
        filteredVisible = filteredVisible,
        filteredHidden = filteredHidden,
    )
}

// ── user sections ────────────────────────────────────────────────────────────

/**
 * Clean a section list: drop a blank id or name, and drop a duplicate id.
 * `user-sections.ts:39-60` @ the pin.
 */
fun normalizeBotSections(raw: List<BotSection>): List<BotSection> {
    val seen = mutableSetOf<String>()
    val out = mutableListOf<BotSection>()
    for (entry in raw) {
        val id = entry.id.trim()
        val name = entry.name.trim()
        if (id.isEmpty() || name.isEmpty() || !seen.add(id)) {
            continue
        }
        out.add(BotSection(id = id, name = name))
    }
    return out
}

/**
 * Split rows into section blocks, in section order, with Unassigned last.
 *
 * Pure, and returns EVERY row exactly once: a row whose `sectionId` names a
 * section that no longer exists lands in Unassigned rather than vanishing,
 * which is what makes deleting a section safe (`user-sections.ts:124-160` @
 * the pin).
 *
 * Empty section blocks are dropped: Desktop renders a section header for an
 * empty folder because it is a drop target for its drag-and-drop filing, and
 * this app has no drag. The Unassigned bucket is returned only when it holds
 * something.
 */
fun groupRowsBySection(
    rows: List<BotRosterRow>,
    sections: List<BotSection>,
    metaByKey: Map<String, BotMeta>,
): List<BotSectionBlock> {
    val list = normalizeBotSections(sections)
    val known = list.map { it.id }.toSet()
    val byId = list.associate { it.id to mutableListOf<BotRosterRow>() }
    val loose = mutableListOf<BotRosterRow>()

    for (row in rows) {
        val id = botSectionId(row, metaByKey)
        if (id != null && known.contains(id)) {
            byId.getValue(id).add(row)
        } else {
            loose.add(row)
        }
    }

    val blocks = list.map { section ->
        BotSectionBlock(
            id = section.id,
            key = "section:${section.id}",
            name = section.name,
            rows = byId.getValue(section.id),
        )
    }.filter { it.rows.isNotEmpty() }

    if (loose.isEmpty()) {
        return blocks
    }

    return blocks + BotSectionBlock(
        id = null,
        key = BotsRosterLimits.UNASSIGNED_SECTION_KEY,
        name = BotsRosterCopy.UNASSIGNED,
        rows = loose,
    )
}

// ── presentation state ───────────────────────────────────────────────────────

/** Which chrome the roster draws. Port of `deriveRosterPresentation` @ the pin. */
data class RosterPresentationState(
    val activeFilterCount: Int = 0,
    val hasRosterConstraint: Boolean = false,
    val showHiddenSection: Boolean = false,
    val showHiddenRows: Boolean = false,
    val allBotsHidden: Boolean = false,
    val showRosterSearch: Boolean = false,
    val showRosterFilters: Boolean = false,
    val showRosterTools: Boolean = false,
)

/**
 * Decide the roster's chrome from the derived rows.
 *
 * The gateway axis is absent (single connection), so the active-filter count
 * is the kind axis plus the activity axis — Desktop's count includes a gateway
 * filter this app does not have.
 */
fun deriveRosterPresentation(
    rosterSize: Int,
    visibleRosterSize: Int,
    hiddenRowsSize: Int,
    filteredHiddenRows: List<BotRosterRow>,
    query: String,
    kindFilter: RosterKindFilter,
    activityFilter: RosterActivityFilter,
    hiddenExpanded: Boolean,
): RosterPresentationState {
    val activeFilterCount =
        (if (kindFilter == RosterKindFilter.All) 0 else 1) +
            (if (activityFilter == RosterActivityFilter.All) 0 else 1)

    val hasRosterConstraint = query.trim().isNotEmpty() || activeFilterCount > 0
    val matchingHiddenBots =
        if (kindFilter == RosterKindFilter.Groups) emptyList() else filteredHiddenRows
    val showHiddenSection =
        hiddenRowsSize > 0 && (!hasRosterConstraint || matchingHiddenBots.isNotEmpty())
    val showHiddenRows = hiddenExpanded || hasRosterConstraint

    val allBotsHidden =
        !hasRosterConstraint && visibleRosterSize == 0 && hiddenRowsSize > 0

    val showRosterSearch =
        rosterSize >= BotsRosterLimits.BOT_ROSTER_SEARCH_THRESHOLD || query.trim().isNotEmpty()

    val showRosterFilters =
        rosterSize >= BotsRosterLimits.BOT_ROSTER_SEARCH_THRESHOLD || activeFilterCount > 0

    return RosterPresentationState(
        activeFilterCount = activeFilterCount,
        hasRosterConstraint = hasRosterConstraint,
        showHiddenSection = showHiddenSection,
        showHiddenRows = showHiddenRows,
        allBotsHidden = allBotsHidden,
        showRosterSearch = showRosterSearch,
        showRosterFilters = showRosterFilters,
        showRosterTools = showRosterSearch || showRosterFilters,
    )
}
