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
     * The divider copy is Desktop's, byte for byte
     * (`apps/desktop/src/i18n/en.ts:2794-2800` @
     * `437116f9497c80d242ce034ff7f5d81dc277a337`). Three of the five were once
     * re-phrased here; this pins them so the words cannot drift back.
     *
     * The `Earlier …` prefix is not decoration: it is only true because the
     * newest group is never labelled, so a bucket carrying it always sits below
     * something newer (`lib/time.ts:118-124` @ the pin). That rule is pinned by
     * `the first group is never labelled, later ones are` below.
     *
     * `Older` is deliberately *not* Desktop's month / month + year form. One
     * terminal bucket captions rows from several different months, so porting
     * that word here would be a false claim; it needs per-month divider identity
     * first (#299).
     */
    @Test
    fun `divider copy is Desktop's, and the tail stays a documented divergence`() {
        // The map keeps Desktop's own word for this bucket — it is the registry
        // of the pin's strings. The render path provably cannot reach it; see
        // `SessionBucket.label(leadsLabelledList)` and the test below.
        assertEquals("Earlier today", SessionBucket.Today.label())
        assertEquals("Yesterday", SessionBucket.Yesterday.label())
        assertEquals("Earlier this week", SessionBucket.ThisWeek.label())
        assertEquals("Last week", SessionBucket.LastWeek.label())
        assertEquals("Earlier this month", SessionBucket.ThisMonth.label())
        assertEquals("Older", SessionBucket.Older.label())
    }

    /**
     * Desktop's relational copy is a claim about the rows *below* it: `Earlier
     * today` is only true while something newer sits above
     * (`lib/time.ts:118-124` @ the pin). A Pinned section forces the first
     * recents bucket to be labelled — and in that one slot the app's newest
     * session can sit directly below it, so the claim would be false. The plain
     * word is used there instead.
     *
     * `Earlier today` is unreachable through `buildSessionRows` at all, and that
     * is deliberate: the list sorts newest-first with contiguous monotonic
     * buckets, so `Today` is only ever the first recents bucket, which is
     * unlabelled without pins and the forced slot with them. Desktop reaches the
     * word only by splitting *within* a day at a head-run cutoff
     * (`session-date-groups.ts`, `headRunCutoffMs`), which this slice does not
     * port. The reachable relative strings are `Yesterday` and the week/month
     * ones, and those keep Desktop's copy.
     */
    @Test
    fun `a Pinned section's forced first divider takes the plain word, not Desktop's relational copy`() {
        // `b` is pinned and an hour old; `a` is the newest session in the app and
        // sits below the divider the pinned section forced.
        val pinnedRows = buildSessionRows(
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
            pinnedRows.map(::describe),
        )
        val forced = pinnedRows.filterIsInstance<SessionListRow.Divider>().single()
        assertTrue("the forced first divider must be marked as such", forced.leadsLabelledList)
        assertEquals("Today", forced.bucket.label(forced.leadsLabelledList))

        // Without a pinned section the first group is unlabelled, and a *later*
        // group is the one that carries the relational word — below something
        // newer, so the claim holds. `a` is today and `y` is yesterday.
        val splitDay = buildSessionRows(
            listOf(
                session("a", now),
                session("y", now - 24 * HOUR),
            ),
            now,
            timeZone = zone,
            locale = locale,
        )
        assertEquals(listOf("row:a", "divider:Yesterday", "row:y"), splitDay.map(::describe))
        val earned = splitDay.filterIsInstance<SessionListRow.Divider>().single()
        assertTrue("a divider below a newer row is not a forced one", !earned.leadsLabelledList)
        assertEquals("Yesterday", earned.bucket.label(earned.leadsLabelledList))

        // And the word that motivated all of this is unreachable — assert that
        // rather than let a comment claim it is still rendered. `calendarBucket`
        // says `Today` for dayDiff <= 0, the list is sorted newest-first, and
        // buckets are contiguous and monotonic, so `Today` is only ever the
        // first recents group. Whatever renders, no divider in this list can
        // read `Earlier today`; the strings list keeps the word for Desktop's
        // pin, and `docs/parity/session-list-sections.md` records the difference.
        val dividerTexts = listOf(
            listOf(session("a", now), session("y", now - 24 * HOUR)),
            listOf(session("a", now), session("c", now - 2 * HOUR)),
            listOf(session("b", now - HOUR, pinned = true), session("c", now - 2 * HOUR)),
        ).flatMap { sessions ->
            buildSessionRows(sessions, now, timeZone = zone, locale = locale)
                .filterIsInstance<SessionListRow.Divider>()
                .map { it.bucket.label(it.leadsLabelledList) }
        }
        // Non-vacuity: the layouts must actually render a divider, else the
        // "none reads Earlier today" claim below proves nothing.
        assertTrue("expected at least one rendered divider; got none", dividerTexts.isNotEmpty())
        assertTrue(
            "Earlier today must not be renderable; got $dividerTexts",
            dividerTexts.none { it == "Earlier today" },
        )
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
     * `apps/desktop/src/lib/session-date-groups.ts:138-140`: a divider only ever separates two
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
    // (`apps/desktop/src/app/chat/sidebar/index.tsx:1603-1630,1632,1657` @
    // `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`).
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
     * `No sessions match “{query}”.` (`apps/desktop/src/i18n/en.ts:2649` @ the
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
     * (`apps/desktop/src/app/chat/sidebar/index.tsx:1632-1653` @ `72a3277cd7`).
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
     * Desktop's empty-recents line, verbatim (`i18n/en.ts:2407`, chosen at
     * `sidebar/index.tsx:1690-1692` @ `72a3277cd7`). Without it an all-pinned
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
     * Archived is a view of its own set: the pool is swapped wholesale, and the
     * `Pinned` section that leads the live list leads this one too. Its gate is
     * `!trimmedQuery`, with no `showArchived` term in it
     * (`sidebar/index.tsx:1652-1674` @ `437116f9`), so a pinned chat that is
     * then archived keeps the pin's only visible effect.
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
            listOf("pinned", "row:filed-pinned", "row:filed"),
            buildSessionRows(sessions, now, timeZone = zone, locale = locale, archivedView = true).map(::describe),
        )
    }

    /**
     * A pinned archived row is one row, in the Pinned section: the archived rows
     * below it are the pool minus the pins, exactly as the live list splits
     * (`sidebar/index.tsx:595-635` @ `437116f9` — a pinned session "belongs to
     * the Pinned section and nowhere else"). Ordering is activity, newest first,
     * on both sides of the split — so the pinned row can be the oldest row in
     * the list and still lead it.
     */
    @Test
    fun `an archived pinned row appears once, in the pinned section`() {
        val rows = buildSessionRows(
            listOf(
                session("filed-new", now, archived = true),
                session("filed-old-pinned", now - 3 * DAY, archived = true, pinned = true),
            ),
            now,
            timeZone = zone,
            locale = locale,
            archivedView = true,
        )

        assertEquals(listOf("pinned", "row:filed-old-pinned", "row:filed-new"), rows.map(::describe))
    }

    /**
     * And that split is a *split*, not an addition: the row is emitted from the
     * pinned list alone, so the archived run under it never repeats it.
     */
    @Test
    fun `an archived pool with two pins lists each of them once`() {
        val rows = buildSessionRows(
            listOf(
                session("pinned-b", now - HOUR, archived = true, pinned = true),
                session("filed", now - 2 * HOUR, archived = true),
                session("pinned-a", now, archived = true, pinned = true),
            ),
            now,
            timeZone = zone,
            locale = locale,
            archivedView = true,
        )

        assertEquals(
            listOf("pinned", "row:pinned-a", "row:pinned-b", "row:filed"),
            rows.map(::describe),
        )
        assertEquals(3, rows.filterIsInstance<SessionListRow.Row>().size)
    }

    /**
     * The archived rows under the Pinned section take no dividers:
     * `grouping={showArchived || rankedGlobally ? 'none' : …}`
     * (`sidebar/index.tsx:1736` @ `437116f9`) settles the divider question
     * alone, so the view stays flat *below* the section it does keep.
     */
    @Test
    fun `the archived view keeps its rows flat under the pinned section`() {
        val sessions = listOf(
            session("filed-today", now, archived = true),
            session("filed-last-week", now - 8 * DAY, archived = true),
            session("filed-pinned", now - HOUR, archived = true, pinned = true),
        )

        val rows = buildSessionRows(sessions, now, timeZone = zone, locale = locale, archivedView = true)

        assertEquals(listOf("pinned", "row:filed-pinned", "row:filed-today", "row:filed-last-week"), rows.map(::describe))
        assertEquals(0, rows.filterIsInstance<SessionListRow.Divider>().size)
        assertEquals(
            listOf("pinned", "row:filed-pinned", "divider:Today", "row:filed-today", "divider:LastWeek", "row:filed-last-week"),
            buildSessionRows(
                sessions.map { it.copy(archived = false) },
                now,
                timeZone = zone,
                locale = locale,
            ).map(::describe),
        )
    }

    /**
     * An archived pool whose every row is pinned leaves the section below it
     * empty, and this app explains that with the same sentence the live list
     * uses — `Everything here is pinned…` (`apps/desktop/src/i18n/en.ts:2407` @
     * `72a3277c`).
     *
     * This is a documented adaptation, not Desktop parity. Desktop cannot
     * reach `s.allPinned` while the Archived view is on: the recents empty
     * state tests `filtersActive` first (`sidebar/index.tsx:1686-1692` @
     * `72a3277c`), and `$sidebarFiltersActive` counts `$sidebarShowArchived`
     * itself (`store/layout.ts:380-384` @ `72a3277c`), so Desktop renders
     * `No sessions match these filters` (`apps/desktop/src/i18n/en.ts:2413` @
     * `72a3277c`) there. That
     * sentence names a Status/Project/Profile/PR filter surface this rail does
     * not have (#142), so the note states the actual reason recents is empty.
     * Ledgered in `docs/parity/session-list-sections.md`.
     */
    @Test
    fun `an archived pool that is entirely pinned explains its empty recents`() {
        val rows = buildSessionRows(
            listOf(session("filed", now, archived = true, pinned = true)),
            now,
            timeZone = zone,
            locale = locale,
            archivedView = true,
        )

        assertEquals(listOf("pinned", "row:filed", "all-pinned"), rows.map(::describe))
        assertEquals(
            "Everything here is pinned. Unpin a chat to show it in recents.",
            ALL_PINNED_NOTE,
        )
    }

    /** Nothing pinned in the archived pool either: the same two rows, no note. */
    @Test
    fun `an archived pool with no pins carries no all-pinned note`() {
        val rows = buildSessionRows(
            listOf(session("filed", now, archived = true)),
            now,
            timeZone = zone,
            locale = locale,
            archivedView = true,
        )

        assertEquals(listOf("row:filed"), rows.map(::describe))
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

    /**
     * Desktop's `Results` section is not gated on the archived toggle
     * (`sidebar/index.tsx:1603` @ `72a3277cd7`, over the pool swap at
     * `:514-518`), so a query there answers over the archived pool *and* the
     * server's hits. This app's archived pool is its own capped read and the
     * search contract carries no `archived` field
     * (`types/hermes.ts:1193-1208`), so a query inside that view stays a local
     * filter: no `Results` label, and no live server row smuggled into the
     * archived set. Ledgered in `docs/parity/session-search.md`.
     */
    @Test
    fun `a query inside the archived view stays a local filter and ignores server hits`() {
        val sessions = listOf(
            session("live-tunnel", now, title = "Tunnel probe"),
            session("filed-tunnel", now - HOUR, title = "Tunnel notes", archived = true),
            session("filed-other", now - 2 * HOUR, title = "Themes", archived = true),
        )
        val server = listOf(session("server-tunnel", now, title = "Server tunnel hit"))

        val rows = buildSessionRows(
            sessions = sessions,
            nowMillis = now,
            query = "tunnel",
            searchPending = true,
            serverMatches = server,
            timeZone = zone,
            locale = locale,
            archivedView = true,
        )

        assertEquals(listOf("row:filed-tunnel"), rows.map(::describe))
    }

    /**
     * And a query inside the archived view must not become an exception to
     * that: Desktop hides Pinned whenever a query is live — the section's gate
     * is `!trimmedQuery` with no `showArchived` term (`sidebar/index.tsx:1652`
     * @ `437116f9`) — so a pinned row in a searched archived pool renders as an
     * ordinary row, in activity order, with no section above it. The pool
     * stays this view's own; only the Pinned split is suspended.
     */
    @Test
    fun `an archived query renders no pinned section even when a match is pinned`() {
        val rows = buildSessionRows(
            sessions = listOf(
                session("filed-tunnel-pinned", now, title = "Tunnel notes", archived = true, pinned = true),
                session("filed-tunnel", now - HOUR, title = "Tunnel probe", archived = true),
                session("filed-other", now - 2 * HOUR, title = "Themes", archived = true),
            ),
            nowMillis = now,
            query = "tunnel",
            timeZone = zone,
            locale = locale,
            archivedView = true,
        )

        assertEquals(listOf("row:filed-tunnel-pinned", "row:filed-tunnel"), rows.map(::describe))
        assertEquals(0, rows.count { it is SessionListRow.PinnedLabel })
    }

    /**
     * And with nothing in the archived pool matching, the view renders no rows
     * at all rather than falling through to the server's — the sentence is the
     * surface's own (`SessionList.kt`), not a `Results` section.
     */
    @Test
    fun `an archived query that matches nothing locally renders no rows at all`() {
        val rows = buildSessionRows(
            sessions = listOf(session("filed-other", now, title = "Themes", archived = true)),
            nowMillis = now,
            query = "tunnel",
            serverMatches = listOf(session("server-tunnel", now, title = "Server tunnel hit")),
            timeZone = zone,
            locale = locale,
            archivedView = true,
        )

        assertTrue(rows.isEmpty())
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
