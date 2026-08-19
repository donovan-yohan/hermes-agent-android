package com.hermesagent.mobile.data.session

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar buckets for the session list, ported from Desktop's `calendarBucket`
 * (`apps/desktop/src/lib/time.ts:125-165` @ `f82f2dba`).
 *
 * Desktop additionally leaves the newest run of sessions unlabelled above the
 * first divider (`session-date-groups.ts`, `headRunCutoffMs`). That heuristic
 * is worth porting when the list is long enough for it to matter; on a phone
 * with a demo dataset it would only hide the grouping being tested, so Phase 1
 * ships the calendar buckets alone and the workflow doc records the gap.
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
    val at = startOfDay(atMillis, timeZone, locale)
    val today = startOfDay(nowMillis, timeZone, locale)
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
 * Newest first, grouped by calendar bucket with a divider before each group.
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
        if (bucket != currentBucket) {
            rows += SessionListRow.Divider(bucket)
            currentBucket = bucket
        }
        rows += SessionListRow.Row(session)
    }
    return rows
}

private fun SessionSummary.matches(needle: String, locale: Locale): Boolean =
    title.lowercase(locale).contains(needle) || preview.lowercase(locale).contains(needle)

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

private fun startOfDay(millis: Long, timeZone: TimeZone, locale: Locale): Calendar =
    Calendar.getInstance(timeZone, locale).apply {
        timeInMillis = millis
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
