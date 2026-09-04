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
 * offers - never a switch the rail would refuse to render, and never nothing
 * while the reader is stranded outside the Gateway's own profile.
 *
 * The rail's four branches live in `ProfileRail`
 * (`ui/sessions/ProfileRail.kt`); this pins the state they share against
 * `apps/desktop/src/app/chat/sidebar/profile-switcher.tsx:415-433,808-824` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
class ProfilePickerDefaultRowTest {

    @Test
    fun `the roster's own default row heads the sheet`() {
        val state = ProfileRailState(profiles = ROSTER, loaded = true)

        val head = requireNotNull(state.pickerDefault)
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

        assertEquals("Home bench", requireNotNull(state.pickerDefault).label)
    }

    @Test
    fun `a named scope with no default row still gets the canonical way back`() {
        // The pinned Gateway always flags one row, but the roster can be a
        // stale-or-never answer while the persisted scope names a profile. The
        // rail renders its canonical pill there; the sheet must agree.
        val state = ProfileRailState(
            profiles = listOf(HermesProfile(name = "work"), HermesProfile(name = "lab")),
            scope = ProfileScope(activeProfile = "work"),
            loaded = true,
        )

        val head = requireNotNull(state.pickerDefault)
        assertEquals(DEFAULT_PROFILE, head.name)
        assertTrue(head.isDefault)
    }

    @Test
    fun `the unified view keeps the way back and marks nothing selected`() {
        val state = ProfileRailState(
            profiles = listOf(HermesProfile(name = "work"), HermesProfile(name = "lab")),
            scope = ProfileScope(activeProfile = "work", showAllProfiles = true),
            loaded = true,
        )

        assertEquals(DEFAULT_PROFILE, requireNotNull(state.pickerDefault).name)
        assertFalse(state.onDefault)
    }

    @Test
    fun `no default row and already on the default scope offers nothing`() {
        // The rail's second branch renders the layers pill alone here. A head
        // row would be a switch to a profile this Gateway has not named.
        val state = ProfileRailState(
            profiles = listOf(HermesProfile(name = "work"), HermesProfile(name = "lab")),
            loaded = true,
        )

        assertNull(state.pickerDefault)
    }

    private companion object {
        val ROSTER = listOf(
            HermesProfile(name = "default", isDefault = true),
            HermesProfile(name = "work"),
            HermesProfile(name = "lab"),
        )
    }
}
