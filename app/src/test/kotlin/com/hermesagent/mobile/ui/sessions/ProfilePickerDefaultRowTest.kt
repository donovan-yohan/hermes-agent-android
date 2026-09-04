package com.hermesagent.mobile.ui.sessions

import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.profiles.HermesProfile
import com.hermesagent.mobile.data.profiles.ProfileScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker sheet's head row must offer exactly what the rail's own home pill
 * offers - never a switch the rail would refuse to render.
 *
 * The rail's four branches live in `ProfileRail`
 * (`ui/sessions/ProfileRail.kt`); this pins the state they share against
 * `apps/desktop/src/app/chat/sidebar/profile-switcher.tsx:407-444,808-824` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
class ProfilePickerDefaultRowTest {

    @Test
    fun `the roster's own default row heads the sheet`() {
        val state = ProfileRailState(profiles = ROSTER, loaded = true)

        val head = requireNotNull(state.defaultProfile)
        assertEquals("default", head.name)
        // The home face belongs to the default profile alone
        // (`profile-glyph.tsx:21-27`), so the head row must carry the flag.
        assertTrue(head.isDefault)
        assertTrue(state.onDefault)
    }

    @Test
    fun `a display name reaches the head row rather than the canonical name`() {
        val state = ProfileRailState(
            profiles = listOf(
                HermesProfile(name = "default", isDefault = true, displayName = "Home bench"),
                HermesProfile(name = "work"),
            ),
            loaded = true,
        )

        assertEquals("Home bench", requireNotNull(state.defaultProfile).label)
    }

    @Test
    fun `the unified view keeps the head row and marks nothing selected`() {
        val state = ProfileRailState(
            profiles = ROSTER,
            scope = ProfileScope(activeProfile = "work", showAllProfiles = true),
            loaded = true,
        )

        assertEquals(DEFAULT_PROFILE, requireNotNull(state.defaultProfile).name)
        assertFalse(state.onDefault)
    }

    @Test
    fun `no flagged row means no head row, whatever the scope`() {
        // The sheet only opens while the strip is condensed, and in that state a
        // roster with no flagged row leaves the rail's own left pill on `layers`
        // alone. A head row here would be a switch to a profile this Gateway has
        // not named, and the rail does not offer it.
        val roster = listOf(HermesProfile(name = "work"), HermesProfile(name = "lab"))

        assertNull(ProfileRailState(profiles = roster, loaded = true).defaultProfile)
        assertNull(
            ProfileRailState(
                profiles = roster,
                scope = ProfileScope(activeProfile = "work"),
                loaded = true,
            ).defaultProfile,
        )
        assertNull(
            ProfileRailState(
                profiles = roster,
                scope = ProfileScope(activeProfile = "work", showAllProfiles = true),
                loaded = true,
            ).defaultProfile,
        )
    }

    @Test
    fun `an unflagged row named default is not one of the named profiles`() {
        // The pinned Gateway skips a named `default` outright
        // (`hermes_cli/profiles.py:1069-1070`), so this roster cannot arrive
        // today. If it ever did, the row would carry the same list key and the
        // same test tag as the head row, and the sheet would render two.
        val state = ProfileRailState(
            profiles = listOf(
                HermesProfile(name = "default", isDefault = true),
                HermesProfile(name = "default"),
                HermesProfile(name = "work"),
            ),
            loaded = true,
        )

        assertEquals(listOf("work"), state.named.map(HermesProfile::name))
        assertEquals(DEFAULT_PROFILE, requireNotNull(state.defaultProfile).name)
    }

    private companion object {
        val ROSTER = listOf(
            HermesProfile(name = "default", isDefault = true),
            HermesProfile(name = "work"),
            HermesProfile(name = "lab"),
        )
    }
}
