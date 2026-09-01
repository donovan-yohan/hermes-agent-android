package com.hermesagent.mobile.data.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity colour is Desktop's, hash and all
 * (`apps/desktop/src/lib/profile-color.ts:6-43` @
 * `29112bef099274229cadff79cdff7bf7b99c4b77`). If these drift, the same profile
 * reads as two different colours on the two clients.
 */
class ProfileColorTest {

    @Test
    fun `the default profile has no colour of its own`() {
        assertNull(profileColorArgb("default"))
        assertNull(profileColorArgb(""))
        assertNull(profileColorArgb(null))
        assertNull(resolveProfileColorArgb(HermesProfile(name = "default", isDefault = true)))
    }

    @Test
    fun `the hash matches Desktop's 32-bit rolling hash`() {
        // Literal, so a re-implementation cannot drift and still pass:
        // (((119 * 31 + 111) * 31 + 114) * 31 + 107) for "work", the value
        // `hash = (hash * 31 + charCode) >>> 0` produces in the renderer.
        assertEquals(3_655_441L, profileColorHash("work"))
        // The mask matters only past 2^32; assert it bites.
        assertEquals(0L, profileColorHash("\u0000".repeat(8)))
        assertTrue(profileColorHash("a-very-long-profile-name-that-overflows") <= 0xFFFFFFFFL)
    }

    @Test
    fun `a name always resolves to the same hue`() {
        assertEquals(profileColorArgb("work"), profileColorArgb("work"))
        assertNotEquals(profileColorArgb("work"), profileColorArgb("lab"))
    }

    @Test
    fun `hsl conversion matches the palette Desktop emits`() {
        // hsl(0 68% 58%) — the first curated swatch (profile-color.ts:47-50).
        assertEquals(0xFFDD4B4B.toInt(), hslToArgb(0f, 68f, 58f))
        // hsl(120 68% 58%), asserted by Desktop's own profile-tag test as
        // rgb(75, 221, 75) (`apps/desktop/src/app/chat/profile-tag.test.tsx:49`).
        assertEquals(0xFF4BDD4B.toInt(), hslToArgb(120f, 68f, 58f))
    }

    @Test
    fun `a server ui_meta colour wins over the deterministic hue`() {
        val tinted = HermesProfile(name = "work", uiMetaColor = "#123456")

        assertEquals(0xFF123456.toInt(), resolveProfileColorArgb(tinted))
        assertEquals(profileColorArgb("work"), resolveProfileColorArgb(HermesProfile(name = "work")))
    }

    @Test
    fun `an unusable ui_meta colour is ignored rather than guessed at`() {
        assertNull(parseHexColor("rebeccapurple"))
        assertNull(parseHexColor("#12345"))
        assertEquals(0xFF112233.toInt(), parseHexColor("#123"))
    }

    @Test
    fun `the initial is the first alphanumeric, uppercased`() {
        assertEquals("W", profileInitial("work"))
        assertEquals("L", profileInitial("_lab-2"))
        assertEquals("?", profileInitial("__"))
    }
}
