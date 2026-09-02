package com.hermesagent.mobile.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop's own changelog fixtures, run against this port.
 *
 * Every case below is `apps/desktop/src/lib/commit-changelog.test.ts` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`, translated and nothing else. The
 * point is not that the grouping is reasonable — it is that it is *identical*,
 * because the output is product copy and two implementations of the same
 * release notes are two different products.
 */
class CommitChangelogTest {

    // -- parseCommitHeader (`commit-changelog.test.ts:5-41`) ------------------

    @Test
    fun `extracts type, scope and subject from a conventional header`() {
        assertEquals(
            ParsedCommit(type = "feat", scope = "desktop", breaking = false, subject = "NSIS prereq detection page"),
            parseCommitHeader("feat(desktop): NSIS prereq detection page"),
        )
    }

    @Test
    fun `flags breaking changes via the bang marker`() {
        val parsed = parseCommitHeader("feat(api)!: change endpoint shape")
        assertTrue(parsed.breaking)
        assertEquals("feat", parsed.type)
    }

    @Test
    fun `treats non-conventional commits as untyped with the full header as subject`() {
        assertEquals(
            ParsedCommit(type = null, scope = null, breaking = false, subject = "Update README"),
            parseCommitHeader("Update README"),
        )
    }

    @Test
    fun `ignores body lines and trims whitespace`() {
        val parsed = parseCommitHeader("  fix: handle null input  \n\nMore detail")
        assertEquals("handle null input", parsed.subject)
        assertEquals("fix", parsed.type)
    }

    @Test
    fun `returns an empty subject for blank input`() {
        assertEquals(
            ParsedCommit(type = null, scope = null, breaking = false, subject = ""),
            parseCommitHeader(""),
        )
        assertNull(parseCommitHeader("   ").type)
    }

    // -- buildCommitChangelog (`commit-changelog.test.ts:43-113`) -------------

    @Test
    fun `groups commits into user-friendly buckets and capitalises subjects`() {
        val groups = buildCommitChangelog(
            listOf(
                "feat(desktop): add NSIS prereq detection page",
                "fix(sidebar): jitter when dragging",
                "perf: shave 200ms off cold start",
                "refactor: extract sidebar row component",
            ),
        )

        // Three groups, because `maxGroups` is 3 and `improved` sorts last.
        assertEquals(listOf(CommitGroupId.New, CommitGroupId.Fixed, CommitGroupId.Faster), groups.map { it.id })
        assertEquals("What's new", groups[0].label)
        assertEquals("Add NSIS prereq detection page", groups[0].items[0])
        assertEquals("Jitter when dragging", groups[1].items[0])
    }

    @Test
    fun `hides chore, ci, docs and test commits`() {
        val groups = buildCommitChangelog(
            listOf("chore: bump deps", "ci: tweak workflow", "docs: spelling fix", "feat: real new feature"),
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("Real new feature"), groups[0].items)
    }

    @Test
    fun `routes unparseable commits to the other improvements bucket`() {
        val groups = buildCommitChangelog(listOf("Update sidebar styling"))

        assertEquals(CommitGroupId.Other, groups[0].id)
        assertEquals(listOf("Update sidebar styling"), groups[0].items)
    }

    @Test
    fun `falls back to a neutral placeholder when every commit is filtered or empty`() {
        val groups = buildCommitChangelog(listOf("chore: bump", "ci: stuff"))

        assertEquals(1, groups.size)
        assertEquals(CommitGroupId.Other, groups[0].id)
        assertEquals(FALLBACK_GROUP_LABEL, groups[0].label)
        assertEquals(listOf(FALLBACK_GROUP_ITEM), groups[0].items)
    }

    @Test
    fun `dedupes identical subjects and caps the items per group`() {
        val groups = buildCommitChangelog(
            listOf(
                "fix: thing A",
                "fix: thing A",
                "fix: thing B",
                "fix: thing C",
                "fix: thing D",
                "fix: thing E",
            ),
            maxPerGroup = 3,
            maxTotal = 10,
        )

        assertEquals(listOf("Thing A", "Thing B", "Thing C"), groups[0].items)
    }

    @Test
    fun `caps total entries across buckets`() {
        val groups = buildCommitChangelog(
            listOf("feat: a", "feat: b", "fix: c", "fix: d", "perf: e"),
            maxTotal = 3,
        )

        assertEquals(3, groups.totalItems())
    }

    // -- Details the upstream suite leaves implicit ---------------------------

    @Test
    fun `dedupe is case-insensitive and trailing punctuation is dropped`() {
        val groups = buildCommitChangelog(listOf("fix: Handle null input.", "fix: handle null input"))

        assertEquals(listOf("Handle null input"), groups.single().items)
    }

    @Test
    fun `a full bucket does not consume the dedupe key it was refused for`() {
        // Desktop pushes, stores and marks seen only after the bucket check
        // (`commit-changelog.ts:156-165`). The second `thing B` is therefore
        // still a *new* subject when a later cap would have room; asserting the
        // items directly is what pins the ordering of those three statements.
        val groups = buildCommitChangelog(
            listOf("fix: thing A", "fix: thing B", "fix: thing B"),
            maxPerGroup = 1,
        )

        assertEquals(listOf("Thing A"), groups.single().items)
    }

    @Test
    fun `an empty commit list is still one honest group`() {
        assertEquals(listOf(FALLBACK_GROUP_ITEM), buildCommitChangelog(emptyList()).single().items)
    }
}
