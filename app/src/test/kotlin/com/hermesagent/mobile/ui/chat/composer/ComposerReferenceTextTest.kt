package com.hermesagent.mobile.ui.chat.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerReferenceTextTest {
    @Test
    fun `url references are canonical quoted text and preserve sentence punctuation`() {
        assertEquals(
            "Read @url:`https://example.dev/a`. then continue",
            canonicalizeComposerUrls("Read https://example.dev/a. then continue"),
        )
        assertEquals("@url:`https://example.dev/a`", composerUrlReferenceText("https://example.dev/a"))
        assertEquals(
            "Keep @url:`https://example.dev/a` intact ",
            canonicalizeComposerTextOnSpace("Keep @url:`https://example.dev/a` intact "),
        )
    }

    @Test
    fun `only explicit host urls are references`() {
        assertFalse(validComposerUrl("example.dev/a"))
        assertFalse(validComposerUrl("https://"))
        assertFalse(validComposerUrl("https://?query"))
        assertFalse(validComposerUrl("https:///missing-host"))
        assertFalse(validComposerUrl("ftp://example.dev/a"))
        assertTrue(validComposerUrl("https://example.dev/a"))
        assertTrue(validComposerUrl("HTTP://example.dev/a"))
    }

    @Test
    fun `remote shaped paths become canonical file or folder references at a space boundary`() {
        assertEquals("Read @file:`src/main.kt` ", canonicalizeComposerTextOnSpace("Read @src/main.kt "))
        assertEquals("Read @file:`src/main.kt`, ", canonicalizeComposerTextOnSpace("Read @src/main.kt, "))
        assertEquals("Read @folder:`apps/mobile` ", canonicalizeComposerTextOnSpace("Read @apps/mobile/ "))
        assertEquals("Mention @hermes ", canonicalizeComposerTextOnSpace("Mention @hermes "))
        assertEquals("Open @/sdcard/photo ", canonicalizeComposerTextOnSpace("Open @/sdcard/photo "))
    }

    @Test
    fun `range replacement leaves surrounding text untouched`() {
        assertEquals("try @file:`src/main.kt` now", replaceComposerRange("try @src now", 4, 8, "@file:`src/main.kt`"))
    }

    @Test
    fun `emoji prefix matches rank before loose aliases and insert unicode`() {
        val matches = EmojiIndex.search("joy")
        assertEquals("😂", matches.first().text)
        assertTrue(matches.first().display.contains(":joy:"))
        assertTrue(matches.all { it.kind == "emoji" && it.text.isNotBlank() })
        assertTrue(EmojiIndex.search("not-an-emoji").isEmpty())
    }
}
