package com.hermesagent.mobile.data.session

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar buckets for the session list, ported from Desktop's `calendarBucket`
 * (`apps/desktop/src/lib/time.ts:125-165` @ `3ca096de`).
 *
 * Desktop additionally leaves the newest *run* of sessions unlabelled above the
 * first divider (`session-date-groups.ts`, `headRunCutoffMs`) — a gap-scoring
 * heuristic that needs a long list to mean anything. This slice does not ship
 * it; the workflow doc records the gap. What the app does ship is the other half of
 * that rule, which needs no heuristic: **whatever group renders first is never
 * labelled** (`session-date-groups.ts:136-140`). A divider separates two
 * groups; there is nothing above the first one to separate it from.
 */
enum class SessionBucket { Today, Yesterday, ThisWeek, LastWeek, ThisMonth, Older }

fun SessionBucket.label(): String = when (this) {
    SessionBucket.Today -> "Today"
    SessionBucket.Yesterday -> "Yesterday"
    SessionBucket.ThisWeek -> "This week"
    SessionBucket.LastWeek -> "Last week"
    SessionBucket.ThisMonth -> "This month"
    SessionBucket.Older -> "Older"
}

/** A divider, a section label, a note or a session row. One list renders them all. */
sealed interface SessionListRow {
    data class Divider(val bucket: SessionBucket) : SessionListRow
    data class Row(val session: SessionSummary) : SessionListRow

    /**
     * Desktop's leading `Pinned` section, label `Pinned`
     * (`apps/desktop/src/i18n/en.ts:2205`, rendered at
     * `apps/desktop/src/app/chat/sidebar/index.tsx:1640-1661` @ `3ca096de`).
     */
    data object PinnedLabel : SessionListRow

    /**
     * Desktop's empty-recents line when everything loaded is pinned
     * (`en.ts:2214`, chosen at `sidebar/index.tsx:1697-1699` @ `3ca096de`). It
     * is the honest explanation of an otherwise confusing empty list, so it
     * ships with the feature rather than after it.
     */
    data object AllPinnedNote : SessionListRow
}

/** `Everything here is pinned…` (`apps/desktop/src/i18n/en.ts:2214` @ `3ca096de`). */
const val ALL_PINNED_NOTE = "Everything here is pinned. Unpin a chat to show it in recents."

/** `Pinned` (`apps/desktop/src/i18n/en.ts:2205` @ `3ca096de`). */
const val PINNED_SECTION_LABEL = "Pinned"

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
 * Search filters rows but not the grouping rules, so a filtered list still
 * reads as the same list — Desktop's "ranking and grouping are separate
 * concerns" (`sidebar/order.ts:147-159`).
 *
 * @param archivedView Desktop's `Archived` toggle. Archived is a view of its
 *   own set rather than a filter over the live one
 *   (`apps/desktop/src/app/chat/sidebar/index.tsx:488-495` @ `3ca096de`), so it
 *   swaps the pool wholesale and renders it flat: no pinned section and no
 *   dividers (`:1723`, `grouping='none'` while archived).
 */
fun buildSessionRows(
    sessions: Collection<SessionSummary>,
    nowMillis: Long,
    query: String = "",
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
    archivedView: Boolean = false,
): List<SessionListRow> {
    val needle = query.trim().lowercase(locale)
    val visible = sessions
        .asSequence()
        // `archived == null` is a Gateway that never said, and an unsaid flag
        // is not an archive: a row only leaves the live list when a contract
        // that can answer says `true`.
        .filter { (it.archived == true) == archivedView }
        .filter { needle.isEmpty() || it.matches(needle, locale) }
        .sortedByDescending { it.lastActiveAtMillis }
        .toList()

    if (archivedView) return visible.map(SessionListRow::Row)

    // Desktop hides the Pinned section while a query is live — search answers
    // in one Results list (`sidebar/index.tsx:1640,1664`). The backend flag is
    // the authority on membership (`session-index.ts:41-49`); ordering is the
    // list's own, because Android has no drag reorder to hint with.
    val pinned = if (needle.isEmpty()) visible.filter { it.pinned == true } else emptyList()
    val pinnedIds = pinned.mapTo(HashSet(pinned.size), SessionSummary::id)
    val recents = if (pinned.isEmpty()) visible else visible.filterNot { it.id in pinnedIds }

    val rows = ArrayList<SessionListRow>(visible.size + SessionBucket.entries.size + 2)
    if (pinned.isNotEmpty()) {
        rows += SessionListRow.PinnedLabel
        pinned.forEach { rows += SessionListRow.Row(it) }
    }

    var currentBucket: SessionBucket? = null
    var emittedRecent = false
    for (session in recents) {
        val bucket = calendarBucket(session.lastActiveAtMillis, nowMillis, timeZone, locale)
        // The first-group rule: the top of the list is already "the newest", so
        // labelling it says nothing. A Pinned section above it changes that —
        // there is now something to separate the newest bucket *from*, and an
        // unlabelled first bucket would read as more pinned rows.
        if (bucket != currentBucket) {
            if (emittedRecent || pinned.isNotEmpty()) rows += SessionListRow.Divider(bucket)
            currentBucket = bucket
        }
        rows += SessionListRow.Row(session)
        emittedRecent = true
    }
    if (pinned.isNotEmpty() && recents.isEmpty()) rows += SessionListRow.AllPinnedNote
    return rows
}

private fun SessionSummary.matches(needle: String, locale: Locale): Boolean =
    title.lowercase(locale).contains(needle) || preview.lowercase(locale).contains(needle)

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
