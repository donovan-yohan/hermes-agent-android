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
    private val now: Long = at(2026, Calendar.AUGUST, 19, hour = 12)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        at(zone, year, month, day, hour, minute)

    private fun at(
        timeZone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long = Calendar.getInstance(timeZone, locale).apply {
        clear()
        set(year, month, day, hour, minute, 0)
    }.timeInMillis

    private fun bucketOf(daysAgo: Int, hoursAgo: Int = 0): SessionBucket =
        calendarBucket(now - daysAgo * DAY - hoursAgo * HOUR, now, zone, locale)

    @Test
    fun `same day is today, even hours apart`() {
        assertEquals(SessionBucket.Today, bucketOf(0))
        assertEquals(SessionBucket.Today, bucketOf(0, hoursAgo = 7))
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
    fun `previous week subtracts a local calendar week across New York fall back`() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        // The source times are 04:00 because the app's nominal-day rollover
        // normalizes both to the exact Monday midnights being compared:
        // 2025-11-03 00:00 EST and 2025-10-27 00:00 EDT.
        val fallbackMonday = at(newYork, 2025, Calendar.NOVEMBER, 3, hour = 4)
        val previousMonday = at(newYork, 2025, Calendar.OCTOBER, 27, hour = 4)

        assertEquals(
            SessionBucket.LastWeek,
            calendarBucket(previousMonday, fallbackMonday, newYork, locale),
        )

        // UTC remains the same seven-day boundary; this protects the ordinary
        // case while pinning the local-calendar implementation.
        val utcFallbackMonday = at(zone, 2025, Calendar.NOVEMBER, 3, hour = 4)
        val utcPreviousMonday = at(zone, 2025, Calendar.OCTOBER, 27, hour = 4)
        assertEquals(
            SessionBucket.LastWeek,
            calendarBucket(utcPreviousMonday, utcFallbackMonday, zone, locale),
        )
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

    /**
     * Desktop's nominal day rolls over at 04:00 local, not midnight
     * (`lib/time.ts:87-95`, `DAY_ROLLOVER_HOUR`): the small hours belong to the
     * previous evening's run. 03:59 on Wednesday is still Tuesday's day; 04:00
     * starts Wednesday's.
     */
    @Test
    fun `the nominal day boundary is 0400 local, not midnight`() {
        val lateNight = at(2026, Calendar.AUGUST, 19, hour = 3, minute = 59)
        val earlyMorning = at(2026, Calendar.AUGUST, 19, hour = 4, minute = 0)

        assertEquals(SessionBucket.Yesterday, calendarBucket(lateNight, now, zone, locale))
        assertEquals(SessionBucket.Today, calendarBucket(earlyMorning, now, zone, locale))

        // The same boundary one day down: 03:59 on Wednesday and 23:50 on
        // Tuesday are the same nominal day, which is the whole point of the rule.
        val tuesdayEvening = at(2026, Calendar.AUGUST, 18, hour = 23, minute = 50)
        assertEquals(
            calendarBucket(tuesdayEvening, now, zone, locale),
            calendarBucket(lateNight, now, zone, locale),
        )
    }

    @Test
    fun `the reference clock rolls over too`() {
        // "Now" at 02:00 Wednesday is still nominally Tuesday, so a Tuesday
        // afternoon session is Today rather than Yesterday.
        val smallHours = at(2026, Calendar.AUGUST, 19, hour = 2)
        val tuesdayAfternoon = at(2026, Calendar.AUGUST, 18, hour = 15)

        assertEquals(SessionBucket.Today, calendarBucket(tuesdayAfternoon, smallHours, zone, locale))
        assertEquals(
            SessionBucket.Yesterday,
            calendarBucket(at(2026, Calendar.AUGUST, 17, hour = 15), smallHours, zone, locale),
        )
    }

    /**
     * `session-date-groups.ts:136-140`: a divider only ever separates two
     * groups, so whatever group renders first is never labelled.
     */
    @Test
    fun `the first group is never labelled, later ones are`() {
        val sessions = listOf(
            session("a", now - HOUR),
            session("b", now - 2 * HOUR),
            session("c", now - DAY),
            session("d", now - 8 * DAY),
        )

        val rows = buildSessionRows(sessions, now, timeZone = zone, locale = locale)

        assertEquals(
            listOf(
                "row:a", "row:b",
                "divider:Yesterday", "row:c",
                "divider:LastWeek", "row:d",
            ),
            rows.map(::describe),
        )
    }

    @Test
    fun `the first-group rule follows the list, not the calendar`() {
        // Nothing recent at all: the oldest bucket is now the head, and it is
        // unlabelled for exactly the same reason.
        val rows = buildSessionRows(
            listOf(session("old", now - 40 * DAY), session("older", now - 400 * DAY)),
            now,
            timeZone = zone,
            locale = locale,
        )

        assertEquals(listOf("row:old", "row:older"), rows.map(::describe))
    }

    // -----------------------------------------------------------------------
    // Search. A live query is a different list, not a filtered one: Desktop
    // answers it in one `Results` section and hides Pinned and the buckets
    // (`apps/desktop/src/app/chat/sidebar/index.tsx:1611-1638,1640,1664` @
    // `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
    // -----------------------------------------------------------------------

    @Test
    fun `a live query answers in one Results section with no buckets`() {
        val sessions = listOf(
            session("tunnel", now - HOUR, title = "SSH tunnel", preview = "probe ok"),
            session("theme", now - 2 * HOUR, title = "Themes", preview = "six presets"),
            session("old", now - 8 * DAY, title = "Old tunnel notes", preview = "n/a"),
        )

        val rows = buildSessionRows(sessions, now, query = "TUNNEL", timeZone = zone, locale = locale)

        // `old` would carry a `Last week` divider in the ordinary list.
        assertEquals(listOf("results-label", "row:tunnel", "row:old"), rows.map(::describe))

        val byPreview = buildSessionRows(sessions, now, query = "presets", timeZone = zone, locale = locale)
        assertEquals(listOf("results-label", "row:theme"), byPreview.map(::describe))
    }

    /**
     * Every field Desktop's `sessionMatchesSearch` reads
     * (`apps/desktop/src/lib/session-search.ts:14-22` @ the pin): the id, the
     * lineage root, the title, the preview, the cwd, the git branch, and the
     * source's own terms.
     */
    @Test
    fun `search reads every field Desktop's client-side match reads`() {
        val sessions = listOf(
            session("id-needle", now, title = "By id"),
            session("root", now, title = "By lineage root", lineageRoot = "root-needle"),
            session("title", now, title = "By title needle"),
            session("preview", now, title = "By preview", preview = "a needle in it"),
            session("cwd", now, title = "By cwd", worktreePath = "/srv/needle-repo"),
            session("branch", now, title = "By branch", gitBranch = "feat/needle"),
        )

        for (id in sessions.map(SessionSummary::id)) {
            val only = sessions.filter { it.id == id }
            assertEquals(
                "matched on the field carried by $id",
                listOf("results-label", "row:$id"),
                buildSessionRows(only, now, query = "needle", timeZone = zone, locale = locale).map(::describe),
            )
        }
    }

    /**
     * The source's search terms are its id, its label and its aliases
     * (`apps/desktop/src/lib/session-source.ts:121-130` @ the pin), so a chat
     * is findable under the name the person on the other end calls it.
     */
    @Test
    fun `search reads a source's id, its label and its aliases`() {
        val bluebubbles = listOf(session("bb", now, title = "Untitled", source = "bluebubbles"))
        for (needle in listOf("bluebubbles", "imessage", "apple messages")) {
            assertEquals(
                "matched on $needle",
                listOf("results-label", "row:bb"),
                buildSessionRows(bluebubbles, now, query = needle, timeZone = zone, locale = locale).map(::describe),
            )
        }

        // An unknown source keeps Desktop's title-cased fallback label
        // (`session-source.ts:118`), so it is still searchable by name.
        val unknown = listOf(session("x", now, title = "Untitled", source = "new_platform"))
        assertEquals(
            listOf("results-label", "row:x"),
            buildSessionRows(unknown, now, query = "New Platform", timeZone = zone, locale = locale).map(::describe),
        )
    }

    /**
     * Loaded rows first, server hits appended, and the loaded row object always
     * wins for the same conversation (`sidebar/index.tsx:655-678`). Reached
     * under the lineage root as well as the id, because the route already
     * collapses a compression chain to one result
     * (`hermes_cli/web_routers/sessions.py:306-321`).
     */
    @Test
    fun `server hits are appended behind local matches and deduped by id and lineage root`() {
        val local = listOf(
            session("local-1", now, title = "Matched local", lineageRoot = "root-a"),
            session("local-2", now - HOUR, title = "Nothing to see", lineageRoot = "root-b"),
        )
        val server = listOf(
            // The same conversation as local-1, named by its root.
            session("root-a", now, title = "Server, same lineage"),
            // The same conversation as local-1, named by its id.
            session("local-1", now, title = "Server, same id"),
            session("server-2", now, title = "Server, new", lineageRoot = "root-c"),
            session("server-3", now, title = "Server, rootless"),
        )

        val rows = buildSessionRows(
            sessions = local,
            nowMillis = now,
            query = "matched",
            serverMatches = server,
            timeZone = zone,
            locale = locale,
        )

        assertEquals(
            listOf("results-label", "row:local-1", "row:server-2", "row:server-3"),
            rows.map(::describe),
        )
        // The loaded row, not the stub that names it.
        assertEquals(
            "Matched local",
            rows.filterIsInstance<SessionListRow.Row>().first().session.title,
        )
    }

    /** Server hits keep the Gateway's ranking; only the local half is re-sorted. */
    @Test
    fun `server hits keep the order the Gateway ranked them in`() {
        val server = listOf(
            session("ranked-first", now - 8 * DAY, title = "Oldest but ranked first"),
            session("ranked-second", now, title = "Newest but ranked second"),
        )

        val rows = buildSessionRows(
            sessions = emptyList(),
            nowMillis = now,
            query = "ranked",
            serverMatches = server,
            timeZone = zone,
            locale = locale,
        )

        assertEquals(listOf("results-label", "row:ranked-first", "row:ranked-second"), rows.map(::describe))
    }

    /**
     * Skeletons and the empty sentence are the *section's* empty state on
     * Desktop (`sidebar/index.tsx:1615-1623`), so neither can appear beside a
     * row.
     */
    @Test
    fun `a pending search shows skeletons only while nothing else is on screen`() {
        val matched = listOf(session("a", now, title = "Tunnel"))

        assertEquals(
            listOf("results-label", "skeletons"),
            buildSessionRows(matched, now, query = "zzz", searchPending = true, timeZone = zone, locale = locale)
                .map(::describe),
        )
        assertEquals(
            listOf("results-label", "row:a"),
            buildSessionRows(matched, now, query = "tunnel", searchPending = true, timeZone = zone, locale = locale)
                .map(::describe),
        )
    }

    /**
     * `No sessions match “{query}”.` (`apps/desktop/src/i18n/en.ts:2203` @ the
     * pin), quoting the query as it was typed rather than as it was matched.
     */
    @Test
    fun `a settled query that matches nothing carries Desktop's sentence`() {
        val rows = buildSessionRows(
            listOf(session("a", now)),
            now,
            query = "  Nothing Here  ",
            timeZone = zone,
            locale = locale,
        )

        assertEquals(listOf("results-label", "no-results:Nothing Here"), rows.map(::describe))
        assertEquals(
            "No sessions match \u201CNothing Here\u201D.",
            noSessionsMatch("Nothing Here"),
        )
    }

    /** `Results` (`en.ts:2204` @ the pin). */
    @Test
    fun `the section label is Desktop's word`() {
        assertEquals("Results", RESULTS_SECTION_LABEL)
    }

    /**
     * The leading Pinned section, ported from Desktop's own
     * (`apps/desktop/src/app/chat/sidebar/index.tsx:1640-1661` @ `3ca096de`).
     * Membership is the backend's `pinned` flag alone; ordering is this list's,
     * because a phone has no drag reorder to hint with.
     */
    @Test
    fun `pinned rows lead the list under their own section label`() {
        val rows = buildSessionRows(
            listOf(
                session("a", now),
                session("b", now - HOUR, pinned = true),
                session("c", now - 2 * HOUR),
            ),
            now,
            timeZone = zone,
            locale = locale,
        )

        assertEquals(
            listOf("pinned", "row:b", "divider:Today", "row:a", "row:c"),
            rows.map(::describe),
        )
    }

    /**
     * A `pinned` the Gateway never reported is not a pin. `null` means the
     * contract said nothing, and a silent contract must not fill the section.
     */
    @Test
    fun `only an explicit backend pin joins the section`() {
        val rows = buildSessionRows(
            listOf(session("a", now, pinned = false), session("b", now - HOUR)),
            now,
            timeZone = zone,
            locale = locale,
        )

        assertEquals(listOf("row:a", "row:b"), rows.map(::describe))
    }

    /** The pinned section is ordered by activity, newest first, like the rest. */
    @Test
    fun `the pinned section is ordered newest first`() {
        val rows = buildSessionRows(
            listOf(
                session("old", now - 3 * HOUR, pinned = true),
                session("new", now, pinned = true),
                session("recent", now - HOUR),
            ),
            now,
            timeZone = zone,
            locale = locale,
        )

        assertEquals(
            listOf("pinned", "row:new", "row:old", "divider:Today", "row:recent"),
            rows.map(::describe),
        )
    }

    /**
     * Desktop's empty-recents line, verbatim (`i18n/en.ts:2214`, chosen at
     * `sidebar/index.tsx:1697-1699` @ `3ca096de`). Without it an all-pinned
     * account reads as a broken list rather than an explained one.
     */
    @Test
    fun `everything pinned explains the empty recents rather than showing nothing`() {
        val rows = buildSessionRows(
            listOf(session("a", now, pinned = true)),
            now,
            timeZone = zone,
            locale = locale,
        )

        assertEquals(listOf("pinned", "row:a", "all-pinned"), rows.map(::describe))
        assertEquals(
            "Everything here is pinned. Unpin a chat to show it in recents.",
            ALL_PINNED_NOTE,
        )
    }

    /** Nothing pinned, nothing to explain: the note belongs to that one state. */
    @Test
    fun `an empty list carries no all-pinned note`() {
        assertTrue(buildSessionRows(emptyList(), now, timeZone = zone, locale = locale).isEmpty())
    }

    /** Desktop answers a search in one Results list, with no Pinned section. */
    @Test
    fun `a search answers in one list`() {
        val rows = buildSessionRows(
            listOf(session("a", now, title = "alpha"), session("b", now - HOUR, title = "alpha two", pinned = true)),
            now,
            query = "alpha",
            timeZone = zone,
            locale = locale,
        )

        assertEquals(listOf("results-label", "row:a", "row:b"), rows.map(::describe))
    }

    /**
     * Archived is a view of its own set, flat: no pinned section and no
     * dividers (`sidebar/index.tsx:488-495,1723` @ `3ca096de`).
     */
    @Test
    fun `the archived view swaps the pool rather than filtering it`() {
        val sessions = listOf(
            session("live", now),
            session("filed", now - HOUR, archived = true),
            session("filed-pinned", now - 2 * HOUR, archived = true, pinned = true),
        )

        assertEquals(listOf("row:live"), buildSessionRows(sessions, now, timeZone = zone, locale = locale).map(::describe))
        assertEquals(
            listOf("row:filed", "row:filed-pinned"),
            buildSessionRows(sessions, now, timeZone = zone, locale = locale, archivedView = true).map(::describe),
        )
    }

    /** A Gateway that never reported `archived` has not archived anything. */
    @Test
    fun `an unsaid archive flag keeps the row in the live list`() {
        val rows = buildSessionRows(listOf(session("a", now)), now, timeZone = zone, locale = locale)

        assertEquals(listOf("row:a"), rows.map(::describe))
        assertTrue(
            buildSessionRows(listOf(session("a", now)), now, timeZone = zone, locale = locale, archivedView = true)
                .isEmpty(),
        )
    }

    private fun session(
        id: String,
        at: Long,
        title: String = "Session $id",
        preview: String = "",
        pinned: Boolean? = null,
        archived: Boolean? = null,
        worktreePath: String? = null,
        gitBranch: String? = null,
        source: String? = null,
        lineageRoot: String? = null,
    ) = SessionSummary(
        id = id,
        title = title,
        preview = preview,
        lastActiveAtMillis = at,
        pinned = pinned,
        archived = archived,
        worktreePath = worktreePath,
        gitBranch = gitBranch,
        source = source,
        lineageRootId = lineageRoot,
    )

    private fun describe(row: SessionListRow): String = when (row) {
        is SessionListRow.Divider -> "divider:${row.bucket.name}"
        is SessionListRow.PinnedLabel -> "pinned"
        is SessionListRow.AllPinnedNote -> "all-pinned"
        is SessionListRow.Row -> "row:${row.session.id}"
        is SessionListRow.ResultsLabel -> "results-label"
        is SessionListRow.NoResultsNote -> "no-results:${row.query}"
        is SessionListRow.SearchSkeletons -> "skeletons"
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
