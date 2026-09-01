package com.hermesagent.mobile.data.profiles

import com.hermesagent.mobile.data.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scope rules, against Desktop's own cases at
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`:
 * `apps/desktop/src/app/chat/sidebar/profile-scope.test.ts:12-29`.
 */
class ProfileScopeTest {

    private fun row(id: String, profile: String? = null) =
        SessionSummary(id = id, title = id, preview = "", lastActiveAtMillis = 0, remoteProfile = profile)

    @Test
    fun `keeps only rows from the selected profile`() {
        val rows = listOf(row("default-row", "default"), row("work-row", "work"))

        assertEquals(listOf("work-row"), filterSessionsByProfileScope(rows, "work").map(SessionSummary::id))
    }

    @Test
    fun `treats legacy rows without a profile as default`() {
        val rows = listOf(row("legacy-row"), row("work-row", "work"))

        assertEquals(listOf("legacy-row"), filterSessionsByProfileScope(rows, "default").map(SessionSummary::id))
    }

    @Test
    fun `preserves every row in the canonical all-profiles scope`() {
        val rows = listOf(row("default-row", "default"), row("work-row", "work"))

        assertSame(rows, filterSessionsByProfileScope(rows, ALL_PROFILES))
    }

    @Test
    fun `normalizes a blank profile name to default`() {
        assertEquals("default", normalizeProfileKey(null))
        assertEquals("default", normalizeProfileKey("   "))
        assertEquals("work", normalizeProfileKey("  work "))
    }

    @Test
    fun `the default scope sends no profile parameter`() {
        assertNull(ProfileScope().sessionProfileParam)
        assertNull(ProfileScope(activeProfile = "  ").sessionProfileParam)
        assertEquals("work", ProfileScope(activeProfile = "work").sessionProfileParam)
    }

    @Test
    fun `a named scope lists exactly its own profile`() {
        val roster = listOf(profile("default", isDefault = true), profile("work"), profile("lab"))

        assertEquals(listOf("work"), sessionListProfiles(ProfileScope(activeProfile = "work"), roster))
    }

    @Test
    fun `the fan-out asks the launch profile first`() {
        // Load-bearing order: a profile the Gateway cannot resolve falls back
        // to the launch handle (`tui_gateway/server.py:1556-1571,1599-1613`),
        // so the refresh has to know which rows the launch profile claimed
        // before it stamps anything with a named owner.
        val roster = listOf(profile("work"), profile("default", isDefault = true), profile("lab"))

        assertEquals(null, sessionListProfiles(ProfileScope(showAllProfiles = true), roster).first())
    }

    @Test
    fun `the unified scope fans out over the launch profile and every named one`() {
        val roster = listOf(profile("default", isDefault = true), profile("work"), profile("lab"))
        val scope = ProfileScope(activeProfile = "work", showAllProfiles = true)

        assertEquals(listOf(null, "work", "lab"), sessionListProfiles(scope, roster))
        assertEquals(ALL_PROFILES, scope.key)
    }

    @Test
    fun `the unified scope never repeats the launch profile as a named request`() {
        // A roster whose default row is literally named "default" must not
        // produce a second request for it: the null entry already covers the
        // profile the Gateway launched with.
        val roster = listOf(profile("default", isDefault = false))

        assertEquals(listOf(null), sessionListProfiles(ProfileScope(showAllProfiles = true), roster))
    }

    @Test
    fun `the unified view keeps the profile new work belongs to`() {
        // Desktop's setShowAllProfiles leaves $newChatProfile alone
        // (`store/profile.ts:481-483`), so a chat started while browsing still
        // lands in the profile that was active.
        val scope = ProfileScope(activeProfile = "work", showAllProfiles = true)

        assertTrue(scope.isAll)
        assertEquals(ALL_PROFILES, scope.key)
        assertEquals("work", scope.sessionProfileParam)
    }

    private fun profile(name: String, isDefault: Boolean = false) =
        HermesProfile(name = name, isDefault = isDefault)
}
