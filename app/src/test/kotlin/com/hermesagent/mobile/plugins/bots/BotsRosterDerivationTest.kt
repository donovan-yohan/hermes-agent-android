package com.hermesagent.mobile.plugins.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster's pure derivation: hidden/pinned treatment, filter composition
 * and user-section filing.
 *
 * Desktop sources at `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`:
 * `roster-pane-derivation.ts` (rows + presentation), `user-sections.ts`
 * (`groupRowsBySection`, `normalizeBotSections`) and `hidden-bots.ts:23-31`
 * (the pin/hide predicates).
 */
class BotsRosterDerivationTest {

    private val now = 1_800_000_000_000L

    private fun bot(
        name: String,
        displayName: String = "",
        description: String = "",
        lastActiveSeconds: Long? = null,
        preview: String? = null,
        /** The worker session's own stamp, as `profiles.list` sends it. */
        workerActiveSeconds: Long? = null,
    ) = BotRosterRow(
        name = name,
        displayName = displayName,
        description = description,
        lastSession = if (lastActiveSeconds != null || preview != null) {
            BotSessionPreview(lastActiveSeconds = lastActiveSeconds ?: 0L, preview = preview)
        } else {
            null
        },
        workerSession = workerActiveSeconds?.let { BotSessionPreview(lastActiveSeconds = it) },
    )

    private fun secondsAgo(seconds: Long): Long = (now / 1000L) - seconds

    /** The presentation state with the two filter axes at their defaults. */
    private fun presentation(userSectionCount: Int = 0) = deriveRosterPresentation(
        rosterSize = 3,
        visibleRosterSize = 3,
        hiddenRowsSize = 0,
        filteredHiddenRows = emptyList(),
        query = "",
        kindFilter = RosterKindFilter.All,
        activityFilter = RosterActivityFilter.All,
        hiddenExpanded = false,
        userSectionCount = userSectionCount,
    )

    // ── roster key ────────────────────────────────────────────────────────────

    @Test
    fun `a single-connection row keeps Desktop's legacy key shape`() {
        assertEquals("legacy::default", bot("default").rosterKey)
        assertEquals("legacy::researcher", bot("researcher").rosterKey)
    }

    @Test
    fun `a named connection qualifies the key`() {
        assertEquals(
            "homelab::researcher",
            bot("researcher").copy(connectionId = "homelab").rosterKey,
        )
    }

    // ── pin and hide ──────────────────────────────────────────────────────────

    @Test
    fun `hidden and pinned read the local metadata`() {
        val row = bot("researcher")
        val meta = mapOf(row.rosterKey to BotMeta(pinned = true, hidden = true))

        assertTrue(isBotHidden(row, meta))
        assertTrue(isBotPinned(row, meta))
    }

    @Test
    fun `a row with no metadata is neither pinned nor hidden`() {
        val row = bot("researcher")

        assertFalse(isBotHidden(row, emptyMap()))
        assertFalse(isBotPinned(row, emptyMap()))
    }

    @Test
    fun `metadata on another row never leaks`() {
        val row = bot("researcher")
        val meta = mapOf("legacy::writer" to BotMeta(pinned = true, hidden = true))

        assertFalse(isBotHidden(row, meta))
        assertFalse(isBotPinned(row, meta))
    }

    // ── query filter ──────────────────────────────────────────────────────────

    @Test
    fun `a blank query keeps every row`() {
        val rows = listOf(bot("a"), bot("b"))

        assertEquals(rows, filterBots(rows, ""))
        assertEquals(rows, filterBots(rows, "   "))
    }

    @Test
    fun `search matches the display name, the profile name, the handle, the description and the preview`() {
        val rows = listOf(
            bot("researcher", displayName = "Research Buddy", description = "reads papers"),
            bot("writer", preview = "drafting the post"),
            bot("code-helper"),
        )

        assertEquals(listOf("researcher"), filterBots(rows, "buddy").map { it.name })
        assertEquals(listOf("researcher"), filterBots(rows, "RESEARCH").map { it.name })
        assertEquals(listOf("researcher"), filterBots(rows, "papers").map { it.name })
        assertEquals(listOf("writer"), filterBots(rows, "drafting").map { it.name })
        assertEquals(listOf("code-helper"), filterBots(rows, "helper").map { it.name })
    }

    @Test
    fun `a leading at-sign is stripped from the query`() {
        val rows = listOf(bot("researcher"), bot("writer"))

        assertEquals(listOf("researcher"), filterBots(rows, "@researcher").map { it.name })
    }

    @Test
    fun `a multi-source row is searchable by its device label`() {
        val homelab = bot("researcher").copy(connectionId = "homelab", connectionLabel = "Homelab")
        val rows = listOf(homelab, bot("writer"))

        assertEquals(listOf("researcher"), filterBots(rows, "homelab").map { it.name })
    }

    @Test
    fun `the primary profile is searchable by its hermes handle`() {
        val rows = listOf(bot("default"), bot("writer"))

        assertEquals(listOf("default"), filterBots(rows, "@hermes").map { it.name })
    }

    // ── activity filter ───────────────────────────────────────────────────────

    @Test
    fun `the activity filter's windows are Desktop's`() {
        val live = bot("live", lastActiveSeconds = secondsAgo(30))
        val recent = bot("recent", lastActiveSeconds = secondsAgo(3 * 24 * 3600))
        val old = bot("old", lastActiveSeconds = secondsAgo(30L * 24 * 3600))
        val never = bot("never")

        assertTrue(rosterActivityMatches(live, RosterActivityFilter.All, now))

        assertTrue(rosterActivityMatches(live, RosterActivityFilter.Active, now))
        assertFalse(rosterActivityMatches(recent, RosterActivityFilter.Active, now))
        assertFalse(rosterActivityMatches(never, RosterActivityFilter.Active, now))

        assertTrue(rosterActivityMatches(recent, RosterActivityFilter.Recent, now))
        assertFalse(rosterActivityMatches(old, RosterActivityFilter.Recent, now))
        assertFalse(rosterActivityMatches(never, RosterActivityFilter.Recent, now))

        assertTrue(rosterActivityMatches(old, RosterActivityFilter.Older, now))
        assertTrue(rosterActivityMatches(never, RosterActivityFilter.Older, now))
        assertFalse(rosterActivityMatches(recent, RosterActivityFilter.Older, now))
    }

    @Test
    fun `the active window is ninety seconds`() {
        assertTrue(isActiveNow(bot("a", lastActiveSeconds = secondsAgo(89)), now))
        assertTrue(isActiveNow(bot("a", lastActiveSeconds = secondsAgo(90)), now))
        assertFalse(isActiveNow(bot("a", lastActiveSeconds = secondsAgo(91)), now))
    }

    // ── ordering ──────────────────────────────────────────────────────────────

    @Test
    fun `pinned rows come first, then recency`() {
        val pinnedOld = bot("pinned-old", lastActiveSeconds = secondsAgo(90_000))
        val fresh = bot("fresh", lastActiveSeconds = secondsAgo(10))
        val older = bot("older", lastActiveSeconds = secondsAgo(5_000))
        val meta = mapOf(pinnedOld.rosterKey to BotMeta(pinned = true))

        val sorted = sortBotsForRoster(listOf(fresh, older, pinnedOld), meta)

        assertEquals(listOf("pinned-old", "fresh", "older"), sorted.map { it.name })
    }

    @Test
    fun `a bot that has never run sorts last`() {
        val fresh = bot("fresh", lastActiveSeconds = secondsAgo(10))
        val never = bot("never")

        assertEquals(
            listOf("fresh", "never"),
            sortBotsForRoster(listOf(never, fresh), emptyMap()).map { it.name },
        )
    }

    // ── the hidden split ──────────────────────────────────────────────────────

    @Test
    fun `hidden rows leave the visible side and stay recoverable`() {
        val visible = bot("researcher")
        val hidden = bot("writer")
        val meta = mapOf(hidden.rosterKey to BotMeta(hidden = true))

        val derived = deriveRosterRows(
            roster = listOf(visible, hidden),
            metaByKey = meta,
            query = "",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            nowMillis = now,
        )

        assertEquals(listOf("researcher"), derived.visibleRows.map { it.name })
        assertEquals(listOf("writer"), derived.hiddenRows.map { it.name })
        assertEquals(listOf("researcher"), derived.filteredVisible.map { it.name })
        assertEquals(listOf("writer"), derived.filteredHidden.map { it.name })
    }

    @Test
    fun `the filters compose over both sides`() {
        val visible = bot("researcher", lastActiveSeconds = secondsAgo(10))
        val hidden = bot("writer")
        val meta = mapOf(hidden.rosterKey to BotMeta(hidden = true))

        // The query excludes the visible row, so only the hidden row survives.
        val queried = deriveRosterRows(
            roster = listOf(visible, hidden),
            metaByKey = meta,
            query = "writer",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            nowMillis = now,
        )
        assertEquals(emptyList<String>(), queried.filteredVisible.map { it.name })
        assertEquals(listOf("writer"), queried.filteredHidden.map { it.name })

        // The activity filter excludes the same row the other way round.
        val activeOnly = deriveRosterRows(
            roster = listOf(visible, hidden),
            metaByKey = meta,
            query = "",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.Active,
            nowMillis = now,
        )
        assertEquals(listOf("researcher"), activeOnly.filteredVisible.map { it.name })
        assertEquals(emptyList<String>(), activeOnly.filteredHidden.map { it.name })
    }

    @Test
    fun `groups only selects nothing until group rows exist`() {
        val rows = listOf(bot("researcher"), bot("writer"))

        val derived = deriveRosterRows(
            roster = rows,
            metaByKey = emptyMap(),
            query = "",
            kindFilter = RosterKindFilter.Groups,
            activityFilter = RosterActivityFilter.All,
            nowMillis = now,
        )

        assertEquals(emptyList<String>(), derived.filteredVisible.map { it.name })
        assertEquals(emptyList<String>(), derived.filteredHidden.map { it.name })
    }

    @Test
    fun `bots only keeps every bot row`() {
        val rows = listOf(bot("researcher"), bot("writer"))

        val derived = deriveRosterRows(
            roster = rows,
            metaByKey = emptyMap(),
            query = "",
            kindFilter = RosterKindFilter.Bots,
            activityFilter = RosterActivityFilter.All,
            nowMillis = now,
        )

        assertEquals(listOf("researcher", "writer"), derived.filteredVisible.map { it.name })
    }

    // ── user sections ─────────────────────────────────────────────────────────

    @Test
    fun `section normalisation drops blank names, blank ids and duplicate ids`() {
        val sections = listOf(
            BotSection("s1", " Clients "),
            BotSection("", "No id"),
            BotSection("s2", "   "),
            BotSection("s1", "Duplicate"),
        )

        assertEquals(listOf(BotSection("s1", "Clients")), normalizeBotSections(sections))
    }

    @Test
    fun `rows are filed into their section and Unassigned comes last`() {
        val clients = bot("researcher")
        val loose = bot("writer")
        val meta = mapOf(clients.rosterKey to BotMeta(sectionId = "s1"))
        val sections = listOf(BotSection("s1", "Clients"), BotSection("s2", "Personal"))

        val blocks = groupRowsBySection(listOf(loose, clients), sections, meta)

        assertEquals(listOf("Clients", "Unassigned"), blocks.map { it.name })
        assertEquals(listOf("researcher"), blocks[0].rows.map { it.name })
        assertEquals("s1", blocks[0].id)
        assertEquals("section:s1", blocks[0].key)
        assertEquals(listOf("writer"), blocks[1].rows.map { it.name })
        assertNull(blocks[1].id)
        assertEquals(BotsRosterLimits.UNASSIGNED_SECTION_KEY, blocks[1].key)
    }

    @Test
    fun `a row whose section is gone lands in Unassigned rather than vanishing`() {
        val orphan = bot("researcher")
        val meta = mapOf(orphan.rosterKey to BotMeta(sectionId = "deleted-section"))
        val sections = listOf(BotSection("s1", "Clients"))

        val blocks = groupRowsBySection(listOf(orphan), sections, meta)

        assertEquals(1, blocks.size)
        assertEquals(BotsRosterCopy.UNASSIGNED, blocks.single().name)
        assertEquals(listOf("researcher"), blocks.single().rows.map { it.name })
    }

    @Test
    fun `every row is returned exactly once across all blocks`() {
        val a = bot("a")
        val b = bot("b")
        val c = bot("c")
        val meta = mapOf(
            a.rosterKey to BotMeta(sectionId = "s1"),
            b.rosterKey to BotMeta(sectionId = "missing"),
        )
        val sections = listOf(BotSection("s1", "Clients"), BotSection("s2", "Empty"))

        val blocks = groupRowsBySection(listOf(a, b, c), sections, meta)
        val names = blocks.flatMap { block -> block.rows.map { it.name } }

        assertEquals(listOf("a", "b", "c"), names)
        assertEquals(3, names.size)
    }

    @Test
    fun `an empty Unassigned bucket is not drawn`() {
        val filed = bot("researcher")
        val meta = mapOf(filed.rosterKey to BotMeta(sectionId = "s1"))

        val blocks = groupRowsBySection(listOf(filed), listOf(BotSection("s1", "Clients")), meta)

        assertEquals(listOf("Clients"), blocks.map { it.name })
    }

    @Test
    fun `with no user sections every row is Unassigned`() {
        val blocks = groupRowsBySection(listOf(bot("a"), bot("b")), emptyList(), emptyMap())

        assertEquals(listOf(BotsRosterCopy.UNASSIGNED), blocks.map { it.name })
        // The bucket exists and every row is in it, but it is the *loose* one:
        // a null id is what tells the surface to draw no heading, so with no
        // sections made the roster is the plain flat list Desktop renders
        // (`roster-pane-sections.tsx`: "No sections made: the plain list,
        // exactly as before this feature").
        assertNull(blocks.single().id)
        assertEquals(listOf("a", "b"), blocks.single().rows.map { it.name })
        assertFalse(presentation(userSectionCount = 0).hasUserSections)
    }

    // ── presentation ──────────────────────────────────────────────────────────

    @Test
    fun `the active filter count ignores the all-values`() {
        val none = deriveRosterPresentation(
            rosterSize = 3,
            visibleRosterSize = 3,
            hiddenRowsSize = 0,
            filteredHiddenRows = emptyList(),
            query = "",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            hiddenExpanded = false,
            userSectionCount = 0,
        )
        assertEquals(0, none.activeFilterCount)
        assertFalse(none.hasRosterConstraint)

        val both = deriveRosterPresentation(
            rosterSize = 3,
            visibleRosterSize = 3,
            hiddenRowsSize = 0,
            filteredHiddenRows = emptyList(),
            query = "",
            kindFilter = RosterKindFilter.Bots,
            activityFilter = RosterActivityFilter.Active,
            hiddenExpanded = false,
            userSectionCount = 0,
        )
        assertEquals(2, both.activeFilterCount)
        assertTrue(both.hasRosterConstraint)
    }

    @Test
    fun `a query is itself a roster constraint`() {
        val state = deriveRosterPresentation(
            rosterSize = 3,
            visibleRosterSize = 3,
            hiddenRowsSize = 0,
            filteredHiddenRows = emptyList(),
            query = "writer",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            hiddenExpanded = false,
            userSectionCount = 0,
        )

        assertTrue(state.hasRosterConstraint)
    }

    @Test
    fun `every bot hidden is its own state, but a constraint suspends it`() {
        val unconstrained = deriveRosterPresentation(
            rosterSize = 2,
            visibleRosterSize = 0,
            hiddenRowsSize = 2,
            filteredHiddenRows = listOf(bot("a")),
            query = "",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            hiddenExpanded = false,
            userSectionCount = 0,
        )
        assertTrue(unconstrained.allBotsHidden)
        assertTrue(unconstrained.showHiddenSection)
        assertFalse(unconstrained.showHiddenRows)

        val constrained = deriveRosterPresentation(
            rosterSize = 2,
            visibleRosterSize = 0,
            hiddenRowsSize = 2,
            filteredHiddenRows = listOf(bot("a")),
            query = "a",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            hiddenExpanded = false,
            userSectionCount = 0,
        )
        assertFalse(constrained.allBotsHidden)
        assertTrue(constrained.showHiddenRows)
    }

    @Test
    fun `the search and filter chrome appear at Desktop's threshold`() {
        val below = deriveRosterPresentation(
            rosterSize = BotsRosterLimits.BOT_ROSTER_SEARCH_THRESHOLD - 1,
            visibleRosterSize = 7,
            hiddenRowsSize = 0,
            filteredHiddenRows = emptyList(),
            query = "",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            hiddenExpanded = false,
            userSectionCount = 0,
        )
        assertFalse(below.showRosterSearch)
        assertFalse(below.showRosterFilters)
        assertFalse(below.showRosterTools)

        val at = deriveRosterPresentation(
            rosterSize = BotsRosterLimits.BOT_ROSTER_SEARCH_THRESHOLD,
            visibleRosterSize = 8,
            hiddenRowsSize = 0,
            filteredHiddenRows = emptyList(),
            query = "",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            hiddenExpanded = false,
            userSectionCount = 0,
        )
        assertTrue(at.showRosterSearch)
        assertTrue(at.showRosterFilters)
        assertTrue(at.showRosterTools)
    }

    @Test
    fun `a query reveals the search box on a roster below the threshold`() {
        val state = deriveRosterPresentation(
            rosterSize = 1,
            visibleRosterSize = 1,
            hiddenRowsSize = 0,
            filteredHiddenRows = emptyList(),
            query = "x",
            kindFilter = RosterKindFilter.All,
            activityFilter = RosterActivityFilter.All,
            hiddenExpanded = false,
            userSectionCount = 0,
        )

        assertTrue(state.showRosterSearch)
        assertFalse(state.showRosterFilters)
    }

    // ── activity session selection ────────────────────────────────────────────

    @Test
    fun `the fresher of the canonical chat and the last session wins`() {
        val canonicalOlder = bot("a", lastActiveSeconds = 100)
            .copy(canonicalSession = BotSessionPreview(lastActiveSeconds = 100, preview = "canonical"))
        val canonicalNewer = canonicalOlder.copy(
            lastSession = BotSessionPreview(lastActiveSeconds = 50, preview = "last"),
        )

        assertEquals("canonical", botActivitySession(canonicalOlder)?.preview)
        assertEquals("canonical", botActivitySession(canonicalNewer)?.preview)
    }

    @Test
    fun `the last session wins when it is the fresher one`() {
        val row = BotRosterRow(
            name = "a",
            canonicalSession = BotSessionPreview(lastActiveSeconds = 10, preview = "canonical"),
            lastSession = BotSessionPreview(lastActiveSeconds = 90, preview = "last"),
        )

        assertEquals("last", botActivitySession(row)?.preview)
        assertEquals(90_000L, row.lastActiveMillis)
    }

    @Test
    fun `a bot with no sessions has no activity`() {
        val row = bot("a")

        assertNull(botActivitySession(row))
        assertNull(row.lastActiveMillis)
    }

    // ── worker liveness ───────────────────────────────────────────────────────

    @Test
    fun `a live worker makes a silent bot active now`() {
        // The regression Desktop fixed in hermes-agent#90268: a profile
        // grinding through a long task reads idle without this.
        val row = bot("a", lastActiveSeconds = secondsAgo(3 * 60 * 60), workerActiveSeconds = secondsAgo(30))

        assertTrue(workerActiveAt(row, now))
        assertTrue(rosterActivityMatches(row, RosterActivityFilter.Active, now))
        assertFalse(isActiveNow(row, now))
    }

    @Test
    fun `the age label follows the worker while it is alive and the chat once it stops`() {
        val chatting = bot("a", lastActiveSeconds = secondsAgo(20), workerActiveSeconds = secondsAgo(40))
        val silent = bot("a", lastActiveSeconds = secondsAgo(3 * 60 * 60), workerActiveSeconds = secondsAgo(30))
        val finished = bot("a", lastActiveSeconds = secondsAgo(3 * 60 * 60), workerActiveSeconds = secondsAgo(600))

        // Desktop's `rowAgeTs`: the fresher of the two while the worker lives.
        assertEquals(secondsAgo(20) * 1000L, botRowAgeMillis(chatting, now))
        assertEquals(secondsAgo(30) * 1000L, botRowAgeMillis(silent, now))
        // And chat activity alone once the worker is gone.
        assertEquals(secondsAgo(3 * 60 * 60) * 1000L, botRowAgeMillis(finished, now))
    }

    @Test
    fun `the worker window is Desktop's hundred and fifty seconds`() {
        val insideWindow = bot("a", workerActiveSeconds = secondsAgo(149))
        val atWindow = bot("a", workerActiveSeconds = secondsAgo(150))
        val outsideWindow = bot("a", workerActiveSeconds = secondsAgo(151))

        assertTrue(workerActiveAt(insideWindow, now))
        assertFalse(workerActiveAt(atWindow, now))
        assertFalse(workerActiveAt(outsideWindow, now))
    }

    @Test
    fun `a gateway that omits worker_session reads as not working`() {
        val row = bot("a", lastActiveSeconds = secondsAgo(30))

        assertNull(row.workerSession)
        assertFalse(workerActiveAt(row, now))
        assertEquals(secondsAgo(30) * 1000L, botRowAgeMillis(row, now))
    }

    // ── section labelling ─────────────────────────────────────────────────────

    @Test
    fun `a block is labelled only once the user has made sections`() {
        assertFalse(presentation(userSectionCount = 0).hasUserSections)
        assertTrue(presentation(userSectionCount = 2).hasUserSections)
    }
}
