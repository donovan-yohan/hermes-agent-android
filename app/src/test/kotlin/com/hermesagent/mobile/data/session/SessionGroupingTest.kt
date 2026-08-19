package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Fixed clock, fixed zone, fixed locale — the buckets are calendar arithmetic,
 * and a test that depends on the machine's timezone is not a test.
 */
class SessionGroupingTest {

    private val zone: TimeZone = TimeZone.getTimeZone("UTC")
    private val locale: Locale = Locale.UK // week starts Monday

    /** Wednesday 2026-08-19, 12:00 UTC. */
    private val now: Long = Calendar.getInstance(zone, locale).apply {
        clear()
        set(2026, Calendar.AUGUST, 19, 12, 0, 0)
    }.timeInMillis

    private fun bucketOf(daysAgo: Int, hoursAgo: Int = 0): SessionBucket =
        calendarBucket(now - daysAgo * DAY - hoursAgo * HOUR, now, zone, locale)

    @Test
    fun `same day is today, even hours apart`() {
        assertEquals(SessionBucket.Today, bucketOf(0))
        assertEquals(SessionBucket.Today, bucketOf(0, hoursAgo = 11))
    }

    @Test
    fun `one calendar day back is yesterday`() {
        assertEquals(SessionBucket.Yesterday, bucketOf(1))
    }

    @Test
    fun `earlier in the same week groups as this week`() {
        // Monday of the same week (2026-08-17) is two days before Wednesday.
        assertEquals(SessionBucket.ThisWeek, bucketOf(2))
    }

    @Test
    fun `the previous week groups as last week`() {
        assertEquals(SessionBucket.LastWeek, bucketOf(8))
    }

    @Test
    fun `earlier in the same month groups as this month`() {
        // 2026-08-03 is in August but two weeks back.
        assertEquals(SessionBucket.ThisMonth, bucketOf(16))
    }

    @Test
    fun `anything older falls through to older`() {
        assertEquals(SessionBucket.Older, bucketOf(60))
        assertEquals(SessionBucket.Older, bucketOf(400))
    }

    @Test
    fun `rows carry one divider per bucket, newest first`() {
        val sessions = listOf(
            session("a", now - HOUR),
            session("b", now - 2 * HOUR),
            session("c", now - DAY),
            session("d", now - 8 * DAY),
        )

        val rows = buildSessionRows(sessions, now, timeZone = zone, locale = locale)

        assertEquals(
            listOf(
                "divider:Today", "row:a", "row:b",
                "divider:Yesterday", "row:c",
                "divider:LastWeek", "row:d",
            ),
            rows.map(::describe),
        )
    }

    @Test
    fun `archived sessions are hidden unless asked for`() {
        val sessions = listOf(session("live", now - HOUR), session("old", now - 2 * HOUR, archived = true))

        assertEquals(listOf("divider:Today", "row:live"), buildSessionRows(sessions, now, timeZone = zone, locale = locale).map(::describe))
        assertEquals(
            listOf("divider:Today", "row:live", "row:old"),
            buildSessionRows(sessions, now, includeArchived = true, timeZone = zone, locale = locale).map(::describe),
        )
    }

    @Test
    fun `search matches title and preview, case-insensitively, and keeps grouping`() {
        val sessions = listOf(
            session("tunnel", now - HOUR, title = "SSH tunnel", preview = "probe ok"),
            session("theme", now - 2 * HOUR, title = "Themes", preview = "six presets"),
            session("old", now - 8 * DAY, title = "Old tunnel notes", preview = "n/a"),
        )

        val rows = buildSessionRows(sessions, now, query = "TUNNEL", timeZone = zone, locale = locale)

        assertEquals(listOf("divider:Today", "row:tunnel", "divider:LastWeek", "row:old"), rows.map(::describe))

        val byPreview = buildSessionRows(sessions, now, query = "presets", timeZone = zone, locale = locale)
        assertEquals(listOf("divider:Today", "row:theme"), byPreview.map(::describe))
    }

    @Test
    fun `a query that matches nothing yields no rows and no dividers`() {
        val rows = buildSessionRows(listOf(session("a", now)), now, query = "zzz", timeZone = zone, locale = locale)
        assertTrue(rows.isEmpty())
    }

    private fun session(
        id: String,
        at: Long,
        title: String = "Session $id",
        preview: String = "",
        archived: Boolean = false,
    ) = SessionSummary(id = id, title = title, preview = preview, lastActiveAtMillis = at, archived = archived)

    private fun describe(row: SessionListRow): String = when (row) {
        is SessionListRow.Divider -> "divider:${row.bucket.name}"
        is SessionListRow.Row -> "row:${row.session.id}"
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
