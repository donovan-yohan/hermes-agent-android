package com.hermesagent.mobile.ui.profiles

import com.hermesagent.mobile.data.profiles.HermesProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The roster's presentation rules, against Desktop's at
 * `29112bef099274229cadff79cdff7bf7b99c4b77`:
 * `apps/desktop/src/app/profiles/index.tsx:89-91`, `src/i18n/en.ts:1756`, and
 * the conservative half of `src/lib/display-path.ts`.
 *
 * Every path below is invented.
 */
class ProfileRosterPresentationTest {

    @Test
    fun `a home path collapses to a tilde for display only`() {
        assertEquals("~/.hermes", displayPath("/home/someone/.hermes"))
        assertEquals("~/.hermes-lab", displayPath("/Users/someone/.hermes-lab"))
        assertEquals("~", displayPath("/home/someone"))
        assertEquals("/srv/hermes", displayPath("/srv/hermes/"))
    }

    @Test
    fun `roster search matches name or model, like Desktop's`() {
        val row = HermesProfile(name = "lab", displayName = "Lab bench", model = "a-model")

        assertTrue(row.matches(""))
        assertTrue(row.matches("LA"))
        assertTrue(row.matches("a-mod"))
        // Desktop searches name and model, not the display name.
        assertFalse(row.matches("bench"))
    }

    @Test
    fun `the count line is Desktop's, singular and plural`() {
        assertEquals("1 profile", profileCount(1))
        assertEquals("3 profiles", profileCount(3))
    }
}
