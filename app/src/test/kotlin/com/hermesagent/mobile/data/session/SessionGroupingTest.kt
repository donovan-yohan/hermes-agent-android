package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Fixed clock, fixed zone, fixed locale — the buckets are calendar arithmetic,
 * and a test that depends on the machine's timezone is not a test.
 *
 * The divider wording is injected ([stubLabel]) rather than read from ICU, so
 * every expectation here is a plain JVM fact. `IcuSessionBucketLabelTest` is the
 * Robolectric half, where the real formatter runs.
 */
class SessionGroupingTest {

    private val zone: TimeZone = TimeZone.getTimeZone("UTC")
    private val locale: Locale = Locale.UK // week starts Monday

    /** Wednesday 2026-08-19, 12:00 UTC. */
    private val now: Long = at(2026, Calendar.AUGUST, 19, hour = 12)

    /**
     * Desktop's five relative strings as the algorithm sees them, and a
     * deterministic synthetic word for a month bucket — the seam makes the
     * month *form* testable without ICU, and the month *identity* is the
     * bucket's own key.
     */
    private val stubLabel = SessionBucketLabel { bucket ->
        bucket.relativeLabel() ?: "Month ${bucket.key}"
    }

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

    private fun kindOf(daysAgo: Int, hoursAgo: Int = 0): SessionBucketKind =
        calendarBucket(now - daysAgo * DAY - hoursAgo * HOUR, now, zone, locale).kind

    private fun rowsOf(
        sessions: List<SessionSummary>,
        at: Long = now,
        query: String = "",
        archivedView: Boolean = false,
        searchPending: Boolean = false,
        serverMatches: List<SessionSummary>? = null,
    ): List<SessionListRow> = buildSessionRows(
        sessions = sessions,
        nowMillis = at,
        query = query,
        searchPending = searchPending,
        serverMatches = serverMatches,
        timeZone = zone,
        locale = locale,
        archivedView = archivedView,
        bucketLabel = stubLabel,
    )

    // -----------------------------------------------------------------------
    // Calendar arithmetic. Desktop's `calendarBucket`
    // (`apps/desktop/src/lib/time.ts:125-165` @
    // `437116f9497c80d242ce034ff7f5d81dc277a337`).
    // -----------------------------------------------------------------------

    @Test
    fun `same day is today, even hours apart`() {
        assertEquals(SessionBucketKind.Today, kindOf(0))
        assertEquals(SessionBucketKind.Today, kindOf(0, hoursAgo = 7))
    }

    @Test
    fun `one calendar day back is yesterday`() {
        assertEquals(SessionBucketKind.Yesterday, kindOf(1))
    }

    @Test
    fun `earlier in the same week groups as this week`() {
        // Monday of the same week (2026-08-17) is two days before Wednesday.
        assertEquals(SessionBucketKind.ThisWeek, kindOf(2))
    }

    @Test
    fun `the previous week groups as last week`() {
        assertEquals(SessionBucketKind.LastWeek, kindOf(8))
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
            SessionBucketKind.LastWeek,
            calendarBucket(previousMonday, fallbackMonday, newYork, locale).kind,
        )

        // UTC remains the same seven-day boundary; this protects the ordinary
        // case while pinning the local-calendar implementation.
        val utcFallbackMonday = at(zone, 2025, Calendar.NOVEMBER, 3, hour = 4)
        val utcPreviousMonday = at(zone, 2025, Calendar.OCTOBER, 27, hour = 4)
        assertEquals(
            SessionBucketKind.LastWeek,
            calendarBucket(utcPreviousMonday, utcFallbackMonday, zone, locale).kind,
        )
    }

    @Test
    fun `earlier in the same month groups as this month`() {
        // 2026-08-03 is in August but two weeks back.
        assertEquals(SessionBucketKind.ThisMonth, kindOf(16))
    }

    /**
     * The tail is **not** one terminal bucket. Desktop emits a bucket per
     * calendar month past `this month`, keyed `m-<year>-<month>` inside the
     * current year and `my-<year>-<month>` outside it
     * (`apps/desktop/src/lib/time.ts:155-165` @ the pin, with the month taken
     * from JavaScript's zero-based `getMonth()` at `:162`). The key is the
     * bucket's identity, so it is compared verbatim — an off-by-one month would
     * silently caption every July row as August.
     */
    @Test
    fun `past this month each calendar month is its own bucket, keyed Desktop's way`() {
        // 2026-07-10 — same year, a different month.
        val july = now - 40 * DAY
        assertEquals("m-2026-6", calendarBucket(july, now, zone, locale).key)
        assertEquals(SessionBucketKind.Month, calendarBucket(july, now, zone, locale).kind)

        // 2025-07-15 — a different year, so the key is the `my-` form.
        val lastYear = now - 400 * DAY
        assertEquals("my-2025-6", calendarBucket(lastYear, now, zone, locale).key)
        assertEquals(SessionBucketKind.MonthYear, calendarBucket(lastYear, now, zone, locale).kind)

        // Two adjacent months are two distinct identities, which is the whole
        // reason the tail could not be ported as one word.
        assertEquals("m-2026-5", calendarBucket(now - 70 * DAY, now, zone, locale).key)
        assertTrue(
            calendarBucket(now - 70 * DAY, now, zone, locale).key !=
                calendarBucket(now - 40 * DAY, now, zone, locale).key,
        )
    }

    /**
     * The bucket's `atMillis` is the nominal day start the month formatter reads
     * its name from (`time.ts:169-190` @ the pin), not the row's own instant —
     * so a row at 23:50 on the 31st still formats as that month.
     */
    @Test
    fun `a month bucket carries its nominal day start, not the row's instant`() {
        val lateJuly = at(2026, Calendar.JULY, 31, hour = 23, minute = 50)
        val bucket = calendarBucket(lateJuly, now, zone, locale)

        assertEquals("m-2026-6", bucket.key)
        assertEquals(at(2026, Calendar.JULY, 31, hour = 0), bucket.atMillis)
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

        assertEquals(SessionBucketKind.Yesterday, calendarBucket(lateNight, now, zone, locale).kind)
        assertEquals(SessionBucketKind.Today, calendarBucket(earlyMorning, now, zone, locale).kind)

        // The same boundary one day down: 03:59 on Wednesday and 23:50 on
        // Tuesday are the same nominal day, which is the whole point of the rule.
        val tuesdayEvening = at(2026, Calendar.AUGUST, 18, hour = 23, minute = 50)
        assertEquals(
            calendarBucket(tuesdayEvening, now, zone, locale).kind,
            calendarBucket(lateNight, now, zone, locale).kind,
        )
    }

    @Test
    fun `the reference clock rolls over too`() {
        // "Now" at 02:00 Wednesday is still nominally Tuesday, so a Tuesday
        // afternoon session is Today rather than Yesterday.
        val smallHours = at(2026, Calendar.AUGUST, 19, hour = 2)
        val tuesdayAfternoon = at(2026, Calendar.AUGUST, 18, hour = 15)

        assertEquals(SessionBucketKind.Today, calendarBucket(tuesdayAfternoon, smallHours, zone, locale).kind)
        assertEquals(
            SessionBucketKind.Yesterday,
            calendarBucket(at(2026, Calendar.AUGUST, 17, hour = 15), smallHours, zone, locale).kind,
        )
    }

    // -----------------------------------------------------------------------
    // The divider copy. Desktop's five relative strings, byte for byte
    // (`apps/desktop/src/i18n/en.ts:2794-2800` @ the pin), and a month form that
    // comes from a formatter rather than from a string table.
    // -----------------------------------------------------------------------

    @Test
    fun `the five relative labels are Desktop's, and a month bucket has no word of its own`() {
        assertEquals("Earlier today", kindLabel(SessionBucketKind.Today))
        assertEquals("Yesterday", kindLabel(SessionBucketKind.Yesterday))
        assertEquals("Earlier this week", kindLabel(SessionBucketKind.ThisWeek))
        assertEquals("Last week", kindLabel(SessionBucketKind.LastWeek))
        assertEquals("Earlier this month", kindLabel(SessionBucketKind.ThisMonth))

        // A month bucket's word is a locale fact, not a constant — Desktop reads
        // it from `Intl` (`lib/time.ts:30-31,169-190` @ the pin). `Older` was a
        // word this app invented and upstream does not have.
        assertNull(kindLabel(SessionBucketKind.Month))
        assertNull(kindLabel(SessionBucketKind.MonthYear))
    }

    private fun kindLabel(kind: SessionBucketKind): String? =
        SessionBucket(kind, key = "k", atMillis = 0).relativeLabel()

    /**
     * The label seam is what words a divider, and it is asked once per divider
     * with that divider's own bucket — so two month dividers cannot be given one
     * word.
     */
    @Test
    fun `every divider is worded from its own bucket through the injected seam`() {
        val rows = rowsOf(
            listOf(
                session("recent", now - HOUR),
                session("jul", now - 40 * DAY),
                session("jun", now - 70 * DAY),
            ),
        )

        assertEquals(
            listOf("Month m-2026-6", "Month m-2026-5"),
            rows.filterIsInstance<SessionListRow.Divider>().map { it.label },
        )
        assertEquals(
            listOf("m-2026-6", "m-2026-5"),
            rows.filterIsInstance<SessionListRow.Divider>().map { it.bucket.key },
        )
    }

    // -----------------------------------------------------------------------
    // The head run. Desktop's `headRunCutoffMs`
    // (`apps/desktop/src/lib/session-date-groups.ts:44-88` @ the pin) is what
    // makes `Earlier today` true rather than merely available: the newest run
    // never reaches the divider path, so a bucket carrying the word always has
    // something newer above it.
    // -----------------------------------------------------------------------

    /**
     * The whole point of porting the cutoff. Five sessions in one burst, then a
     * real pause: the head is the burst, and the divider below it reads
     * `Earlier today` — a claim about rows that *are* earlier than the head.
     */
    @Test
    fun `Earlier today is reachable, and true, because the head is cut at a real break`() {
        val rows = rowsOf(
            listOf(
                session("r1", now - MINUTE),
                session("r2", now - 2 * MINUTE),
                session("r3", now - 3 * MINUTE),
                session("r4", now - 4 * MINUTE),
                session("r5", now - 5 * MINUTE),
                session("h3", now - 3 * HOUR),
                session("d1", now - DAY),
                session("m40", now - 40 * DAY),
            ),
        )

        assertEquals(
            listOf(
                "row:r1", "row:r2", "row:r3", "row:r4", "row:r5",
                "divider:today", "row:h3",
                "divider:yesterday", "row:d1",
                "divider:m-2026-6", "row:m40",
            ),
            rows.map(::describe),
        )
        // The word is on the divider, and something newer sits above it.
        val first = rows.filterIsInstance<SessionListRow.Divider>().first()
        assertEquals("Earlier today", first.label)
        assertTrue(
            "the labelled bucket must sit below the unlabelled head",
            rows.indexOf(SessionListRow.Row(session("r5", now - 5 * MINUTE))) <
                rows.indexOf(first),
        )
    }

    /**
     * `MIN_RUN_BREAK_MS = 30 min` (`session-date-groups.ts:24` @ the pin): a
     * rapid-fire burst is never sliced, however many rows it has.
     */
    @Test
    fun `a burst under the minimum break is never cut mid-run`() {
        val burst = (1..8).map { session("b$it", now - it * 5 * MINUTE) }
        val rows = rowsOf(burst + session("s6", now - 6 * HOUR))

        assertEquals(
            (1..8).map { "row:b$it" } + listOf("divider:today", "row:s6"),
            rows.map(::describe),
        )
    }

    /**
     * `MAX_RUN_GAP_MS = 8 h` (`:25` @ the pin) always ends the run, so an
     * isolated newest session stands alone above the bucket it belongs to.
     */
    @Test
    fun `a silence longer than the maximum gap always ends the run`() {
        val rows = rowsOf(
            listOf(
                session("n1", now - MINUTE),
                // 03:00 Wednesday, which the 04:00 rollover makes Tuesday.
                session("t9", now - 9 * HOUR),
            ),
        )

        assertEquals(listOf("row:n1", "divider:yesterday", "row:t9"), rows.map(::describe))
    }

    /**
     * The fuzzy-merge rule (`:36-40` @ the pin): when the cut lands at the run's
     * own end and the sessions below it share the head's bucket, the head adds
     * nothing and dissolves — the first-group rule keeps the top unlabelled
     * anyway, and the alternative is a divider labelling rows that are the same
     * bucket as the ones above it.
     */
    @Test
    fun `a head that ends inside its own bucket dissolves`() {
        // 23:00 Wednesday: 22:00 and 05:00 are both inside Wednesday's nominal
        // day (04:00 → 04:00), and 17 h apart, so the gap ends the run.
        val lateNow = at(2026, Calendar.AUGUST, 19, hour = 23)
        val rows = rowsOf(
            listOf(
                session("late", lateNow - HOUR),
                session("early", lateNow - 18 * HOUR),
            ),
            at = lateNow,
        )

        assertEquals(listOf("row:late", "row:early"), rows.map(::describe))
    }

    /**
     * The first-group rule is per *section*: whatever group renders first is
     * never labelled (`session-date-groups.ts:138-140` @ the pin), and the
     * unlabelled head is what the rule spends on the usual case.
     */
    @Test
    fun `the first rendered group is never labelled`() {
        // No recent activity at all: the newest row is itself the head, and the
        // month below it is what gets named.
        val rows = rowsOf(listOf(session("old", now - 40 * DAY), session("older", now - 400 * DAY)))

        assertEquals(listOf("row:old", "divider:my-2025-6", "row:older"), rows.map(::describe))
    }

    /** Two buckets in a row need a divider between them; one bucket does not. */
    @Test
    fun `a divider only ever separates two groups`() {
        val rows = rowsOf(
            listOf(
                session("a", now - HOUR),
                session("b", now - 2 * HOUR),
                session("c", now - DAY),
                session("d", now - 8 * DAY),
            ),
        )

        assertEquals(
            listOf(
                "row:a", "row:b",
                "divider:yesterday", "row:c",
                "divider:last-week", "row:d",
            ),
            rows.map(::describe),
        )
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
            // A row whose own bucket is a month one, so the absence of a month
            // divider under a query is asserted rather than assumed.
            session("jul", now - 40 * DAY, title = "July tunnel plan", preview = "n/a"),
        )

        val rows = rowsOf(sessions, query = "TUNNEL")

        // `old` would carry a `Last week` divider, `jul` a month one.
        assertEquals(listOf("results-label", "row:tunnel", "row:old", "row:jul"), rows.map(::describe))
        assertEquals(
            "a live query carries no date divider and no Sessions caption",
            emptyList<SessionListRow>(),
            rows.filterIsInstance<SessionListRow.Divider>() +
                rows.filterIsInstance<SessionListRow.SessionsLabel>(),
        )

        val byPreview = rowsOf(sessions, query = "presets")
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
                rowsOf(only, query = "needle").map(::describe),
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
                rowsOf(bluebubbles, query = needle).map(::describe),
            )
        }

        // An unknown source keeps Desktop's title-cased fallback label
        // (`session-source.ts:118`), so it is still searchable by name.
        val unknown = listOf(session("x", now, title = "Untitled", source = "new_platform"))
        assertEquals(
            listOf("results-label", "row:x"),
            rowsOf(unknown, query = "New Platform").map(::describe),
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

        val rows = rowsOf(local, query = "matched", serverMatches = server)

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

        val rows = rowsOf(emptyList(), query = "ranked", serverMatches = server)

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
            rowsOf(matched, query = "zzz", searchPending = true).map(::describe),
        )
        assertEquals(
            listOf("results-label", "row:a"),
            rowsOf(matched, query = "tunnel", searchPending = true).map(::describe),
        )
    }

    /**
     * `No sessions match “{query}”.` (`apps/desktop/src/i18n/en.ts:2649` @ the
     * pin), quoting the query as it was typed rather than as it was matched.
     */
    @Test
    fun `a settled query that matches nothing carries Desktop's sentence`() {
        val rows = rowsOf(listOf(session("a", now)), query = "  Nothing Here  ")

        assertEquals(listOf("results-label", "no-results:Nothing Here"), rows.map(::describe))
        assertEquals(
            "No sessions match \u201CNothing Here\u201D.",
            noSessionsMatch("Nothing Here"),
        )
    }

    /** `Results` (`en.ts:2650` @ the pin). */
    @Test
    fun `the section label is Desktop's word`() {
        assertEquals("Results", RESULTS_SECTION_LABEL)
    }

    // -----------------------------------------------------------------------
    // The two captions. Desktop has two distinct ones: `SidebarPanelLabel` for
    // `Pinned` / `Sessions` and `SidebarDateDivider` for the buckets, and the
    // `Sessions` caption heads the *unpinned pool* inside the list
    // (`sidebar/index.tsx:1829`, label at `i18n/en.ts:2652` @ the pin).
    // -----------------------------------------------------------------------

    /**
     * The boundary this issue is about: `PINNED`, then Desktop's `SESSIONS`
     * caption, then the unpinned pool. The caption is what separates the two
     * pools, so the first date divider no longer has to imply the boundary —
     * which is what retired the forced-first label.
     */
    @Test
    fun `the recents pool is captioned below the pinned section`() {
        val rows = rowsOf(
            listOf(
                session("a", now),
                session("b", now - HOUR, pinned = true),
                session("c", now - 2 * HOUR),
            ),
        )

        assertEquals(
            listOf("pinned", "row:b", "sessions", "row:a", "divider:today", "row:c"),
            rows.map(::describe),
        )
        assertEquals("Sessions", SESSIONS_SECTION_LABEL)
    }

    /**
     * With nothing pinned there is one pool, and the pane title above the list
     * is already the word for it — a second copy immediately under it would name
     * the same list twice. Ledgered in `docs/parity/session-list-sections.md`.
     */
    @Test
    fun `the caption is absent when there is no pinned section above it`() {
        val rows = rowsOf(listOf(session("a", now), session("c", now - 2 * HOUR)))

        assertEquals(listOf("row:a", "divider:today", "row:c"), rows.map(::describe))
    }

    /**
     * `Pinned` (`en.ts:2651` @ the pin). Membership is the backend's `pinned`
     * flag alone; ordering is this list's, because a phone has no drag reorder
     * to hint with.
     */
    @Test
    fun `pinned rows lead the list under their own section label`() {
        val rows = rowsOf(
            listOf(
                session("a", now),
                session("b", now - HOUR, pinned = true),
                session("c", now - 2 * HOUR),
            ),
        )

        assertEquals("Pinned", PINNED_SECTION_LABEL)
        assertEquals(SessionListRow.PinnedLabel, rows.first())
    }

    /**
     * A `pinned` the Gateway never reported is not a pin. `null` means the
     * contract said nothing, and a silent contract must not fill the section.
     */
    @Test
    fun `only an explicit backend pin joins the section`() {
        val rows = rowsOf(listOf(session("a", now, pinned = false), session("b", now - HOUR)))

        assertEquals(listOf("row:a", "divider:today", "row:b"), rows.map(::describe))
    }

    /** The pinned section is ordered by activity, newest first, like the rest. */
    @Test
    fun `the pinned section is ordered newest first`() {
        val rows = rowsOf(
            listOf(
                session("old", now - 3 * HOUR, pinned = true),
                session("new", now, pinned = true),
                session("recent", now - HOUR),
            ),
        )

        assertEquals(
            listOf("pinned", "row:new", "row:old", "sessions", "row:recent"),
            rows.map(::describe),
        )
    }

    /**
     * Desktop's empty-recents line, verbatim (`i18n/en.ts:2492` @ the pin),
     * chosen at `sidebar/index.tsx:1690-1692` @ `72a3277cd7`. Without it an
     * all-pinned account reads as a broken list rather than an explained one —
     * and with no recents pool there is no caption to head it.
     */
    @Test
    fun `everything pinned explains the empty recents rather than showing nothing`() {
        val rows = rowsOf(listOf(session("a", now, pinned = true)))

        assertEquals(listOf("pinned", "row:a", "all-pinned"), rows.map(::describe))
        assertEquals(
            "Everything here is pinned. Unpin a chat to show it in recents.",
            ALL_PINNED_NOTE,
        )
    }

    /** Nothing pinned, nothing to explain: the note belongs to that one state. */
    @Test
    fun `an empty list carries no all-pinned note`() {
        assertTrue(rowsOf(emptyList()).isEmpty())
    }

    /** Desktop answers a search in one Results list, with no Pinned section. */
    @Test
    fun `a search answers in one list`() {
        val rows = rowsOf(
            listOf(
                session("a", now, title = "alpha"),
                session("b", now - HOUR, title = "alpha two", pinned = true),
            ),
            query = "alpha",
        )

        assertEquals(listOf("results-label", "row:a", "row:b"), rows.map(::describe))
    }

    /**
     * Archived is a view of its own set, flat: no pinned section, no `Sessions`
     * caption and no dividers (`sidebar/index.tsx:511-518,1716` @ the pin).
     */
    @Test
    fun `the archived view swaps the pool rather than filtering it`() {
        val sessions = listOf(
            session("live", now),
            session("filed", now - HOUR, archived = true),
            session("filed-pinned", now - 2 * HOUR, archived = true, pinned = true),
        )

        assertEquals(listOf("row:live"), rowsOf(sessions).map(::describe))
        assertEquals(
            listOf("row:filed", "row:filed-pinned"),
            rowsOf(sessions, archivedView = true).map(::describe),
        )
    }

    /** A Gateway that never reported `archived` has not archived anything. */
    @Test
    fun `an unsaid archive flag keeps the row in the live list`() {
        val rows = rowsOf(listOf(session("a", now)))

        assertEquals(listOf("row:a"), rows.map(::describe))
        assertTrue(rowsOf(listOf(session("a", now)), archivedView = true).isEmpty())
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

        val rows = rowsOf(
            sessions,
            query = "tunnel",
            archivedView = true,
            searchPending = true,
            serverMatches = server,
        )

        assertEquals(listOf("row:filed-tunnel"), rows.map(::describe))
    }

    /**
     * And with nothing in the archived pool matching, the view renders no rows
     * at all rather than falling through to the server's — the sentence is the
     * surface's own (`SessionList.kt`), not a `Results` section.
     */
    @Test
    fun `an archived query that matches nothing locally renders no rows at all`() {
        val rows = rowsOf(
            listOf(session("filed-other", now, title = "Themes", archived = true)),
            query = "tunnel",
            archivedView = true,
            serverMatches = listOf(session("server-tunnel", now, title = "Server tunnel hit")),
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

    /**
     * Structural, locale-free: a divider is described by its Desktop bucket key
     * (`m-<year>-<month>` for a month), which is exactly the identity that lets
     * two month dividers coexist. The copy is asserted through the seam in its
     * own tests.
     */
    private fun describe(row: SessionListRow): String = when (row) {
        is SessionListRow.Divider -> "divider:${row.bucket.key}"
        is SessionListRow.PinnedLabel -> "pinned"
        is SessionListRow.SessionsLabel -> "sessions"
        is SessionListRow.AllPinnedNote -> "all-pinned"
        is SessionListRow.Row -> "row:${row.session.id}"
        is SessionListRow.ResultsLabel -> "results-label"
        is SessionListRow.NoResultsNote -> "no-results:${row.query}"
        is SessionListRow.SearchSkeletons -> "skeletons"
    }

    private companion object {
        const val MINUTE = 60L * 1000
        const val HOUR = 60L * MINUTE
        const val DAY = 24 * HOUR
    }
}
