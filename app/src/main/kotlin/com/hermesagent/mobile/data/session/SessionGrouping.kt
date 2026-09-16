package com.hermesagent.mobile.data.session

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar buckets for the session list, ported from Desktop's `calendarBucket`
 * (`apps/desktop/src/lib/time.ts:125-165` @ `72a3277cd7`).
 *
 * Desktop additionally leaves the newest *run* of sessions unlabelled above the
 * first divider (`session-date-groups.ts`, `headRunCutoffMs`) — a gap-scoring
 * heuristic that needs a long list to mean anything. This slice does not ship
 * it; the workflow doc records the gap. What the app does ship is the other half of
 * that rule, which needs no heuristic: **whatever group renders first is never
 * labelled** (`session-date-groups.ts:136-140`). A divider separates two
 * groups; there is nothing above the first one to separate it from.
 *
 * That unlabelled head is also what makes Desktop's `Earlier …` wording true
 * (`apps/desktop/src/lib/time.ts:118-124` @ `437116f9497c80d242ce034ff7f5d81dc277a337`):
 * the newest run never reaches the divider path, so a bucket labelled "Earlier
 * today" always sits *below* something newer. The five relative labels are
 * Desktop's, byte for byte
 * (`apps/desktop/src/i18n/en.ts:2794-2800` @ the same SHA).
 */
enum class SessionBucket { Today, Yesterday, ThisWeek, LastWeek, ThisMonth, Older }

fun SessionBucket.label(): String = when (this) {
    SessionBucket.Today -> "Earlier today"
    SessionBucket.Yesterday -> "Yesterday"
    SessionBucket.ThisWeek -> "Earlier this week"
    SessionBucket.LastWeek -> "Last week"
    SessionBucket.ThisMonth -> "Earlier this month"
    // Desktop's tail is not one bucket: past `thisMonth` it emits one per
    // calendar month, keyed `m-<year>-<month>` / `my-<year>-<month>`, labelled
    // from `Intl` with a month name or month + year
    // (`apps/desktop/src/lib/time.ts:155-165,30-31,169-190` @ the pin). Porting
    // that word here would be a false claim: one terminal bucket captions rows
    // belonging to several different months. It needs per-month divider identity
    // first, which is #299.
    SessionBucket.Older -> "Older"
}

/**
 * The label one divider renders, given whether a first-group rule applies to it.
 *
 * Desktop's five relative strings are relational: `Earlier today` means *earlier
 * than the head of this list* (`apps/desktop/src/lib/time.ts:118-124` @ the pin).
 * That is only true while something newer sits above the group it labels.
 *
 * `buildSessionRows` renders a Pinned section above the recents, and when it does
 * the first recents bucket is labelled on purpose — an unlabelled group under a
 * pinned list would read as more pinned rows. But in that one slot the newest
 * session in the app can sit directly *below* the divider, and then "Earlier
 * today" is a claim about rows that are not earlier than anything above them.
 * Desktop cannot reach that state: its Recents section leaves its own first group
 * unlabelled even under `PINNED` (`session-date-groups.ts:136-140` @ the pin), so
 * its first divider under a pinned section is normally `Yesterday` or older.
 *
 * So the first recents bucket falls back to the plain word when a pinned section
 * is what forced its label.
 *
 * **`Earlier today` is unreachable here, and that is deliberate.** This list
 * sorts newest-first and its buckets are contiguous and monotonic, so `Today`
 * can only ever be the *first* recents bucket — which is unlabelled without pins
 * and is exactly the forced slot above, taking the plain word. Desktop reaches
 * `Earlier today` only because it splits *within* a day at a head-run cutoff
 * (`session-date-groups.ts`, `headRunCutoffMs`), and that heuristic is not ported
 * (`SessionGrouping.kt:11-14`). The word is therefore kept out rather than
 * rendered as a claim the list cannot support; the other four relative strings,
 * `Yesterday` included, are reachable and are Desktop's byte-for-byte.
 */
fun SessionBucket.label(leadsLabelledList: Boolean): String = when {
    leadsLabelledList && this == SessionBucket.Today -> "Today"
    leadsLabelledList && this == SessionBucket.ThisWeek -> "This week"
    leadsLabelledList && this == SessionBucket.ThisMonth -> "This month"
    else -> label()
}

/** A divider, a section label, a note or a session row. One list renders them all. */
sealed interface SessionListRow {
    /**
     * A date-bucket caption.
     *
     * [leadsLabelledList] marks the one divider whose label was forced by a
     * Pinned section above it rather than earned by having something newer
     * above it. Desktop's relational copy (`Earlier today`) is a claim about
     * the rows below, and in that slot the claim can be false, so those
     * dividers take the plain word instead. See
     * [SessionBucket.label] for the full reason.
     */
    data class Divider(
        val bucket: SessionBucket,
        val leadsLabelledList: Boolean = false,
    ) : SessionListRow
    data class Row(val session: SessionSummary) : SessionListRow

    /**
     * Desktop's leading `Pinned` section, label `Pinned`
     * (`apps/desktop/src/i18n/en.ts:2398`, rendered at
     * `apps/desktop/src/app/chat/sidebar/index.tsx:1632-1653` @ `72a3277cd7`).
     */
    data object PinnedLabel : SessionListRow

    /**
     * Desktop's empty-recents line when everything loaded is pinned
     * (`en.ts:2407`, chosen at `sidebar/index.tsx:1690-1692` @ `72a3277cd7`). It
     * is the honest explanation of an otherwise confusing empty list, so it
     * ships with the feature rather than after it.
     *
     * The live list renders Desktop's sentence verbatim. The archived view is a
     * deliberate divergence: Desktop cannot reach `s.allPinned` there, because
     * `$sidebarShowArchived` itself makes `$sidebarFiltersActive` true and
     * `s.noFilterMatches` wins (`store/layout.ts:392-396`,
     * `sidebar/index.tsx:1706-1712` @ `437116f9`) — and that sentence names a
     * filter surface this rail does not have. Ledgered in
     * `docs/parity/session-list-sections.md`.
     */
    data object AllPinnedNote : SessionListRow

    /**
     * The one section a live query renders, label `Results`
     * (`apps/desktop/src/i18n/en.ts:2397`, rendered at
     * `apps/desktop/src/app/chat/sidebar/index.tsx:1603-1630` @ `72a3277cd7`).
     * It replaces Pinned and Recents rather than joining them — both are gated
     * on `!trimmedQuery` (`:1632,1657`).
     */
    data object ResultsLabel : SessionListRow

    /**
     * Desktop's settled-empty sentence for a search, `No sessions match
     * “{query}”.` (`en.ts:2396`, chosen at `sidebar/index.tsx:1610-1614` @
     * `72a3277cd7`). It carries the query as the reader typed it — trimmed, not
     * lower-cased — because the sentence quotes them back.
     */
    data class NoResultsNote(val query: String) : SessionListRow

    /**
     * Desktop's `SidebarSessionSkeletons`: five placeholder rows borrowing the
     * session row's own chrome (`sidebar/section-states.tsx:12-24` @
     * `72a3277cd7`). It stands in for the *server* answer only, so it renders
     * exactly where Desktop puts it — as the section's empty state, while the
     * debounced query is still in flight and nothing loaded matched.
     */
    data object SearchSkeletons : SessionListRow
}

/** `Everything here is pinned…` (`apps/desktop/src/i18n/en.ts:2407` @ `72a3277cd7`). */
const val ALL_PINNED_NOTE = "Everything here is pinned. Unpin a chat to show it in recents."

/** `Pinned` (`apps/desktop/src/i18n/en.ts:2398` @ `72a3277cd7`). */
const val PINNED_SECTION_LABEL = "Pinned"

/** `Results` (`apps/desktop/src/i18n/en.ts:2397` @ `72a3277cd7`). */
const val RESULTS_SECTION_LABEL = "Results"

/**
 * `No sessions match “{query}”.` (`apps/desktop/src/i18n/en.ts:2396` @
 * `72a3277cd7`), with Desktop's own typographic quotes.
 */
fun noSessionsMatch(query: String): String = "No sessions match “$query”."

fun calendarBucket(
    atMillis: Long,
    nowMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
): SessionBucket {
    val at = nominalDayStart(atMillis, timeZone, locale)
    val today = nominalDayStart(nowMillis, timeZone, locale)
    val dayDiff = Math.round((today.timeInMillis - at.timeInMillis).toDouble() / DAY_MILLIS)

    if (dayDiff <= 0L) return SessionBucket.Today
    if (dayDiff == 1L) return SessionBucket.Yesterday

    val weekStart = startOfWeek(today)
    if (at.timeInMillis >= weekStart.timeInMillis) return SessionBucket.ThisWeek

    // A week is seven local calendar dates, not always 7 * 24 hours. The
    // fall-back week has an extra hour, so subtracting milliseconds makes the
    // previous Monday begin at 01:00 and wrongly excludes its midnight.
    val previousWeekStart = (weekStart.clone() as Calendar).apply {
        add(Calendar.WEEK_OF_YEAR, -1)
    }
    if (at.timeInMillis >= previousWeekStart.timeInMillis) return SessionBucket.LastWeek

    val sameYear = at.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    if (sameYear && at.get(Calendar.MONTH) == today.get(Calendar.MONTH)) return SessionBucket.ThisMonth

    return SessionBucket.Older
}

/**
 * Newest first, grouped by calendar bucket with a divider *between* groups —
 * never above the first one — under a leading `Pinned` section.
 *
 * A live query is a different list, not a filtered one. Desktop answers a
 * search in a single `Results` section and hides Pinned and Recents while it
 * stands (`apps/desktop/src/app/chat/sidebar/index.tsx:1603-1630,1632,1657` @
 * `72a3277cd7`) — there is no calendar bucket to read once the order is the
 * search's rather than the day's.
 *
 * @param query what the reader typed. Trimmed and lower-cased into the needle;
 *   the untrimmed original never reaches a row.
 * @param searchPending whether the debounced backend search is still in
 *   flight. It only ever chooses between two *empty* states, so a local match
 *   already on screen is never replaced by placeholders.
 * @param serverMatches what the Gateway's own index answered, or null when it
 *   was not asked, could not be asked, or refused. Null and empty are
 *   different facts and only one of them means "nothing matched".
 * @param archivedView Desktop's `Archived` toggle. Archived is a view of its
 *   own set rather than a filter over the live one
 *   (`apps/desktop/src/app/chat/sidebar/index.tsx:516-520` @
 *   `437116f9497c80d242ce034ff7f5d81dc277a337`), so it swaps the pool
 *   wholesale. What the toggle settles is the *divider* question only —
 *   `grouping={showArchived || rankedGlobally ? 'none' : …}` (`:1736`) — the
 *   `Pinned` section above the rows is gated on `!trimmedQuery` alone
 *   (`:1652-1674`), never on `showArchived`. A pinned chat that is then
 *   archived therefore still files under `PINNED`, above the archived rows it
 *   is one of. Those rows take no date dividers.
 *
 *   An archived account whose every row is pinned draws [AllPinnedNote] here —
 *   a deliberate adaptation, not Desktop's sentence. Desktop reaches
 *   `s.allPinned` only after `filtersActive` (`:1706-1712`), and
 *   `$sidebarFiltersActive` counts `$sidebarShowArchived` itself
 *   (`apps/desktop/src/store/layout.ts:392-396` @ `437116f9`), so with the
 *   toggle on Desktop takes `s.noFilterMatches` — `No sessions match these
 *   filters` (`apps/desktop/src/i18n/en.ts:2666`) — and `allPinned` is
 *   unreachable in that view. That sentence would point at a `Status` /
 *   `Project` / `Profile` / `PR` filter surface this rail does not have (an
 *   omission owed to
 *   [#142](https://github.com/donovanyohan/hermes-agent-android/issues/142)),
 *   so the note states the real reason the recents area is empty instead.
 *   Ledgered as `mobile-adaptation` in `docs/parity/session-list-sections.md`.
 *
 *   A query inside that view stays a local filter over the archived pool, with
 *   no Pinned section above it either — see `docs/parity/session-search.md`.
 */
fun buildSessionRows(
    sessions: Collection<SessionSummary>,
    nowMillis: Long,
    query: String = "",
    searchPending: Boolean = false,
    serverMatches: List<SessionSummary>? = null,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
    archivedView: Boolean = false,
): List<SessionListRow> {
    val needle = query.trim().lowercase(locale)
    val localMatches = sessions
        .asSequence()
        // `archived == null` is a Gateway that never said, and an unsaid flag
        // is not an archive: a row only leaves the live list when a contract
        // that can answer says `true`.
        .filter { (it.archived == true) == archivedView }
        .filter { needle.isEmpty() || it.matches(needle, locale) }
        .sortedByDescending { it.lastActiveAtMillis }
        .toList()

    // Desktop hides the Pinned section while a query is live, and the archived
    // view is no exception: the section's own gate is `!trimmedQuery`
    // (`sidebar/index.tsx:1652` @ `437116f9`), with no `showArchived` term in
    // it. The two views answer differently below that gate — the live list
    // renders the one `Results` section (`:1623-1650`), while an archived query
    // stays a local filter over its own pool, because that pool is its own
    // capped read and the search contract carries no `archived` field (ledgered
    // in `docs/parity/session-search.md`) — but neither draws a Pinned section.
    if (needle.isNotEmpty()) {
        return if (archivedView) {
            localMatches.map(SessionListRow::Row)
        } else {
            searchRows(localMatches, query.trim(), searchPending, serverMatches)
        }
    }

    // Membership is the backend flag — Desktop's own authority note is
    // `session-index.ts:41-49`; ordering is the list's own, because Android has
    // no drag reorder to hint with.
    val pinned = localMatches.filter { it.pinned == true }
    val pinnedIds = pinned.mapTo(HashSet(pinned.size), SessionSummary::id)
    val recents = if (pinned.isEmpty()) localMatches else localMatches.filterNot { it.id in pinnedIds }

    val rows = ArrayList<SessionListRow>(localMatches.size + SessionBucket.entries.size + 2)
    if (pinned.isNotEmpty()) {
        rows += SessionListRow.PinnedLabel
        pinned.forEach { rows += SessionListRow.Row(it) }
    }

    // The archived view keeps that Pinned section — it holds the rows of the
    // archived pool like any other, which is the pin's only visible effect once
    // a chat is filed — but takes no dividers, because `grouping='none'` while
    // archived settles the divider question alone
    // (`sidebar/index.tsx:1736` @ `437116f9`). Its all-pinned case is this
    // app's own sentence rather than Desktop's: see [AllPinnedNote].
    if (archivedView) {
        recents.forEach { rows += SessionListRow.Row(it) }
    } else {
        var currentBucket: SessionBucket? = null
        var emittedRecent = false
        for (session in recents) {
            val bucket = calendarBucket(session.lastActiveAtMillis, nowMillis, timeZone, locale)
            // The first-group rule: the top of the list is already "the newest", so
            // labelling it says nothing. A Pinned section above it changes that —
            // there is now something to separate the newest bucket *from*, and an
            // unlabelled first bucket would read as more pinned rows.
            //
            // That forced label is also the one slot where Desktop's relational copy
            // would be false — a newer session can sit below it — so the first
            // recents bucket is marked as leading a labelled list and takes the plain
            // word. See `SessionBucket.label(leadsLabelledList)`.
            if (bucket != currentBucket) {
                val forcedFirst = !emittedRecent && pinned.isNotEmpty()
                if (emittedRecent || pinned.isNotEmpty()) {
                    rows += SessionListRow.Divider(bucket, leadsLabelledList = forcedFirst)
                }
                currentBucket = bucket
            }
            rows += SessionListRow.Row(session)
            emittedRecent = true
        }
    }
    if (pinned.isNotEmpty() && recents.isEmpty()) rows += SessionListRow.AllPinnedNote
    return rows
}

/**
 * The one section a live query renders.
 *
 * Local matches first, in the list's own newest-first order, then the server's
 * hits in the order the Gateway ranked them — Desktop's exact merge
 * (`apps/desktop/src/app/chat/sidebar/index.tsx:679-702` @ `72a3277cd7`): the
 * loaded row object always wins, because a stub knows only what the search
 * contract carries while the loaded row knows its title, its flags and its
 * real activity.
 *
 * Desktop keys that merge on the session id and resolves a server hit through
 * an index built under *both* the live id and the compression lineage root
 * (`sidebar/session-index.ts:17-33`). This does the same, and then also reads
 * the stub's own `lineage_root`. The route already dedupes by lineage
 * (`hermes_cli/web_routers/sessions.py:306-321`), so the extra key costs
 * nothing and closes the case Desktop's map leaves open — a hit that arrives
 * under a loaded row's root is one conversation, not two rows.
 *
 * The skeletons and the empty sentence are the *section's* empty state on
 * Desktop (`sidebar/index.tsx:1615-1623`): neither is reachable while a single
 * row is on screen, so a local match answering instantly is never followed by
 * placeholders for the server's slower answer.
 */
private fun searchRows(
    localMatches: List<SessionSummary>,
    query: String,
    searchPending: Boolean,
    serverMatches: List<SessionSummary>?,
): List<SessionListRow> {
    val rows = ArrayList<SessionListRow>(localMatches.size + serverMatches.orEmpty().size + 2)
    rows += SessionListRow.ResultsLabel

    val emittedIds = HashSet<String>(localMatches.size)
    val knownKeys = HashSet<String>(localMatches.size * 2)
    for (session in localMatches) {
        if (!emittedIds.add(session.id)) continue
        rows += SessionListRow.Row(session)
        knownKeys += session.id
        session.lineageRootId?.let { knownKeys += it }
    }
    for (session in serverMatches.orEmpty()) {
        if (session.id in knownKeys) continue
        if (session.lineageRootId?.let { it in knownKeys } == true) continue
        rows += SessionListRow.Row(session)
        knownKeys += session.id
        session.lineageRootId?.let { knownKeys += it }
    }

    if (rows.size == 1) {
        rows += if (searchPending) SessionListRow.SearchSkeletons else SessionListRow.NoResultsNote(query)
    }
    return rows
}

/**
 * Desktop's `sessionMatchesSearch` (`apps/desktop/src/lib/session-search.ts:7-23`
 * @ `72a3277cd7`): the durable id, the compression lineage root, the title, the
 * preview, the working directory and the git branch, plus every term the row's
 * source answers to. Substring, case-insensitive, on the same normalised needle
 * — `trim().toLowerCase()` (`apps/desktop/src/lib/text.ts:11`).
 *
 * One field is read differently, and it is ledgered
 * (`docs/parity/session-search.md`): Desktop reads `sessionTitle(session)`,
 * which is `title || preview || 'Untitled session'`
 * (`apps/desktop/src/lib/chat-runtime.ts:51-53`), so typing `untitled` there
 * finds every nameless row. This reads the raw title, because this rail draws
 * the raw title too — matching a row on a label it does not show would be a
 * hit the reader cannot see.
 *
 * Widening this is what makes the local half of search worth having: pasting a
 * session id, or typing a branch name, finds the conversation without a round
 * trip.
 */
private fun SessionSummary.matches(needle: String, locale: Locale): Boolean {
    if (needle.isEmpty()) return true
    val fields = sequenceOf(id, lineageRootId, title, preview, worktreePath, gitBranch) +
        sessionSourceSearchTerms(source).asSequence()
    return fields.any { it != null && it.lowercase(locale).contains(needle) }
}

/**
 * Desktop's source labels, keyed by the id the Gateway reports
 * (`apps/desktop/src/lib/session-source.ts:3-27` @ `72a3277cd7`).
 */
private val SOURCE_LABELS: Map<String, String> = mapOf(
    "api_server" to "API",
    "bluebubbles" to "iMessage",
    "cli" to "CLI",
    "codex" to "Codex",
    "desktop" to "Desktop",
    "discord" to "Discord",
    "email" to "Email",
    "gateway" to "Gateway",
    "kanban" to "Kanban",
    "local" to "Local",
    "matrix" to "Matrix",
    "mattermost" to "Mattermost",
    "photon" to "Photon",
    "qqbot" to "QQ",
    "signal" to "Signal",
    "slack" to "Slack",
    "sms" to "SMS",
    "telegram" to "Telegram",
    "tui" to "TUI",
    "webhook" to "Webhook",
    "weixin" to "WeChat",
    "whatsapp" to "WhatsApp",
    "yuanbao" to "Yuanbao",
)

/**
 * The other names a reader might type for a source
 * (`apps/desktop/src/lib/session-source.ts:29-40` @ `72a3277cd7`). Searching
 * `imessage` has to find a BlueBubbles chat, because that is what the person
 * on the other end calls it.
 */
private val SOURCE_ALIASES: Map<String, List<String>> = mapOf(
    "bluebubbles" to listOf("apple messages", "imessage"),
    "photon" to listOf("imessage", "messages"),
    "cli" to listOf("terminal"),
    "desktop" to listOf("app", "gui"),
    "local" to listOf("machine"),
    "qqbot" to listOf("qq"),
    "telegram" to listOf("tg"),
    "tui" to listOf("terminal"),
    "weixin" to listOf("wechat"),
    "whatsapp" to listOf("wa"),
)

private val SOURCE_WORD_BREAK = Regex("[_-]+")

/**
 * Desktop's own `\b\w`: the first word character after any non-word one. Both
 * JavaScript's and Java's `\w` are ASCII `[A-Za-z0-9_]` without a Unicode
 * flag, and both define `\b` from it, so this is the same boundary Desktop
 * uppercases at — including the separators the `[_-]` pass leaves behind, such
 * as `.` or `/`.
 */
private val SOURCE_WORD_START = Regex("\\b\\w")

/**
 * Desktop's `sessionSourceLabel` fallback for a source it has no label for:
 * word-break on `_`/`-`, then uppercase every word start
 * (`session-source.ts:111-119`, `lib/text.ts:7` @ `72a3277cd7`). An unknown
 * source is still searchable under the name the row shows.
 *
 * The casing itself cannot change a match — [sessionSourceSearchTerms] feeds a
 * case-insensitive comparison — so there is no test that can tell the two
 * boundaries apart. It is Desktop's boundary anyway, because the day this term
 * is *rendered* rather than only matched, a narrower rule would be a different
 * label.
 */
private fun prettySourceName(id: String): String =
    id.replace(SOURCE_WORD_BREAK, " ")
        .replace(SOURCE_WORD_START) { match -> match.value.uppercase(Locale.ROOT) }

/**
 * Desktop's `sessionSourceSearchTerms` (`session-source.ts:121-130` @
 * `72a3277cd7`): the normalised id, its label, and its aliases, with empties
 * dropped. Normalisation is Desktop's `normalize` — `trim().toLowerCase()`
 * (`apps/desktop/src/lib/text.ts:11`) — which is root-locale by construction in JavaScript, so
 * it is root-locale here: these are wire ids, not the reader's prose.
 */
private fun sessionSourceSearchTerms(source: String?): List<String> {
    val id = source?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (id.isEmpty()) return emptyList()
    val label = SOURCE_LABELS[id] ?: prettySourceName(id)
    return (listOf(id, label) + SOURCE_ALIASES[id].orEmpty()).filter(String::isNotEmpty)
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * The human day does not end at midnight — it ends when you sleep, so Desktop
 * puts the boundary at 04:00 local (`time.ts:87-95`, `DAY_ROLLOVER_HOUR`). A
 * 00:30 session groups with the 23:50 one before it instead of splitting off
 * into its own "Today".
 */
private const val DAY_ROLLOVER_HOUR = 4

/** Desktop's `nominalDayStart`: `startOfLocalDay(ms - 4h)` (`time.ts:95`). */
private fun nominalDayStart(millis: Long, timeZone: TimeZone, locale: Locale): Calendar =
    Calendar.getInstance(timeZone, locale).apply {
        timeInMillis = millis - DAY_ROLLOVER_HOUR * 60L * 60 * 1000
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

private fun startOfWeek(dayStart: Calendar): Calendar {
    val calendar = dayStart.clone() as Calendar
    val firstDay = calendar.firstDayOfWeek
    var guard = 0
    while (calendar.get(Calendar.DAY_OF_WEEK) != firstDay && guard < 7) {
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        guard++
    }
    return calendar
}
