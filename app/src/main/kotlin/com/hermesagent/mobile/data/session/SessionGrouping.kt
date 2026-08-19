package com.hermesagent.mobile.data.session

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar buckets for the session list, ported from Desktop's `calendarBucket`
 * (`apps/desktop/src/lib/time.ts:125-165` @ `f82f2dba`).
 *
 * Desktop additionally leaves the newest *run* of sessions unlabelled above the
 * first divider (`session-date-groups.ts`, `headRunCutoffMs`) — a gap-scoring
 * heuristic that needs a long list to mean anything. Phase 1 does not ship it;
 * the workflow doc records the gap. What Phase 1 does ship is the other half of
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

/** A divider or a session row. Interleaved so one list renders both. */
sealed interface SessionListRow {
    data class Divider(val bucket: SessionBucket) : SessionListRow
    data class Row(val session: SessionSummary) : SessionListRow
}

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
    if (at.timeInMillis >= weekStart) return SessionBucket.ThisWeek

    val previousWeekStart = weekStart - 7 * DAY_MILLIS
    if (at.timeInMillis >= previousWeekStart) return SessionBucket.LastWeek

    val sameYear = at.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    if (sameYear && at.get(Calendar.MONTH) == today.get(Calendar.MONTH)) return SessionBucket.ThisMonth

    return SessionBucket.Older
}

/**
 * Newest first, grouped by calendar bucket with a divider *between* groups —
 * never above the first one.
 *
 * Search filters rows but not the grouping rules, so a filtered list still
 * reads as the same list — Desktop's "ranking and grouping are separate
 * concerns" (`sidebar/order.ts:147-159`).
 */
fun buildSessionRows(
    sessions: Collection<SessionSummary>,
    nowMillis: Long,
    query: String = "",
    includeArchived: Boolean = false,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault(),
): List<SessionListRow> {
    val needle = query.trim().lowercase(locale)
    val visible = sessions
        .asSequence()
        .filter { includeArchived || !it.archived }
        .filter { needle.isEmpty() || it.matches(needle, locale) }
        .sortedByDescending { it.lastActiveAtMillis }
        .toList()

    val rows = ArrayList<SessionListRow>(visible.size + SessionBucket.entries.size)
    var currentBucket: SessionBucket? = null
    for (session in visible) {
        val bucket = calendarBucket(session.lastActiveAtMillis, nowMillis, timeZone, locale)
        // `rows.isNotEmpty()` is the first-group rule: the top of the list is
        // already "the newest", so labelling it says nothing.
        if (bucket != currentBucket) {
            if (rows.isNotEmpty()) rows += SessionListRow.Divider(bucket)
            currentBucket = bucket
        }
        rows += SessionListRow.Row(session)
    }
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

private fun startOfWeek(dayStart: Calendar): Long {
    val calendar = dayStart.clone() as Calendar
    val firstDay = calendar.firstDayOfWeek
    var guard = 0
    while (calendar.get(Calendar.DAY_OF_WEEK) != firstDay && guard < 7) {
        calendar.add(Calendar.DAY_OF_MONTH, -1)
        guard++
    }
    return calendar.timeInMillis
}
