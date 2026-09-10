package com.hermesagent.mobile.data.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerReferenceSyntaxTest {

    @Test
    fun testSpans() {
        val text = "see @url:`https://example.dev/a`"
        val spans = composerReferenceSpans(text)
        assertEquals(1, spans.size)
        val span = spans[0]
        assertEquals("url", span.kind)
        assertEquals("https://example.dev/a", span.value)
        assertEquals(4, span.start)
        assertEquals(32, span.end)
        assertEquals(10, span.valueStart)
        assertEquals(31, span.valueEnd)

        // Not spans
        assertEquals(0, composerReferenceSpans("@url:").size)
        assertEquals(0, composerReferenceSpans("@url:https://x").size)
        assertEquals(0, composerReferenceSpans("@file:README.md").size)
        assertEquals(0, composerReferenceSpans("@git:`x`").size)
        assertEquals(0, composerReferenceSpans("@url:`a\nb`").size)
        assertEquals(1, composerReferenceSpans("@url:\"a b\"").size)

        // double- and single-quoted fences for file, folder, and session
        val f1 = composerReferenceSpans("a @file:\"f1\" b")[0]
        assertEquals("file", f1.kind)
        assertEquals("f1", f1.value)
        assertEquals(2, f1.start)
        assertEquals(12, f1.end)
        assertEquals(9, f1.valueStart)
        assertEquals(11, f1.valueEnd)

        val f2 = composerReferenceSpans("a @folder:'d1' b")[0]
        assertEquals("folder", f2.kind)
        assertEquals("d1", f2.value)
        assertEquals(2, f2.start)
        assertEquals(14, f2.end)
        assertEquals(11, f2.valueStart)
        assertEquals(13, f2.valueEnd)

        val s1 = composerReferenceSpans("a @session:\"s1\" b")[0]
        assertEquals("session", s1.kind)
        assertEquals("s1", s1.value)
        assertEquals(2, s1.start)
        assertEquals(15, s1.end)
        assertEquals(12, s1.valueStart)
        assertEquals(14, s1.valueEnd)

        // @url:"a b" has value == "a b"
        val u1 = composerReferenceSpans("@url:\"a b\"")[0]
        assertEquals("a b", u1.value)

        // Adjacent spans with exact offsets
        val adjacent = composerReferenceSpans("@url:`a`@url:`b`")
        assertEquals(2, adjacent.size)
        assertEquals(0, adjacent[0].start)
        assertEquals(8, adjacent[0].end)
        assertEquals(8, adjacent[1].start)
        assertEquals(16, adjacent[1].end)
    }

    @Test
    fun testMaskComposerReferences() {
        val text = "see @url:`https://example.dev/a` now"
        val masked = maskComposerReferences(text)
        assertEquals(text.length, masked.length)
        assertEquals("see " + "\uFFFC".repeat(28) + " now", masked)

        val text2 = "a @url:`a` b @url:`c` d"
        val masked2 = maskComposerReferences(text2)
        assertEquals(text2.length, masked2.length)
        assertEquals("a " + "\uFFFC".repeat(8) + " b " + "\uFFFC".repeat(8) + " d", masked2)
    }

    @Test
    fun testHiddenUrlLabelRanges() {
        // "https://"
        assertEquals(emptyList<IntRange>(), hiddenUrlLabelRanges("https://"))
        // "https://host" (label "host")
        assertEquals(listOf(0..7), hiddenUrlLabelRanges("https://host"))
        // "https://user:pw@host/x" -> "host/x"
        assertEquals(listOf(0..7, 8..15), hiddenUrlLabelRanges("https://user:pw@host/x"))
        // "http://[::1]/p" -> "[::1]/p"
        assertEquals(listOf(0..6), hiddenUrlLabelRanges("http://[::1]/p"))
        // "https://example.dev?q=1" -> "example.dev?q=1"
        assertEquals(listOf(0..7), hiddenUrlLabelRanges("https://example.dev?q=1"))
        // "HTTPS://Example.dev/A" -> "Example.dev/A"
        assertEquals(listOf(0..7), hiddenUrlLabelRanges("HTTPS://Example.dev/A"))
        // "https://host/😀" never splits surrogate
        val smileyStr = "https://host/😀"
        val ranges = hiddenUrlLabelRanges(smileyStr)
        val smileyStart = smileyStr.indexOf("😀")
        for (range in ranges) {
            org.junit.Assert.assertFalse(range.first == smileyStart + 1 || range.last == smileyStart)
        }
    }

    @Test
    fun testLabels() {
        assertEquals("github.com/NousResearch/hermes-agent/pull/74533", composerReferenceLabel("url", "https://github.com/NousResearch/hermes-agent/pull/74533"))
        assertEquals("example.dev", composerReferenceLabel("url", "https://www.example.dev/"))
        assertEquals("example.dev/a?b=1", composerReferenceLabel("url", "https://example.dev:8443/a?b=1#frag"))
        assertEquals("example.dev/x", composerReferenceLabel("url", "https://user@example.dev/x"))
        assertEquals("[::1]/p", composerReferenceLabel("url", "http://[::1]:8080/p"))
        assertEquals("not a url", composerReferenceLabel("url", "not a url"))

        assertEquals("src/a.kt", composerReferenceLabel("file", "./src/a.kt"))
        assertEquals("apps/mobile/", composerReferenceLabel("folder", "apps/mobile/"))
    }

    @Test
    fun testFenceCompletionReference() {
        assertEquals("@file:`README.md`", fenceCompletionReference("@file:README.md"))
        assertEquals("@file:`src/main.kt`", fenceCompletionReference("@file:src/main.kt"))
        assertEquals("@url:", fenceCompletionReference("@url:"))
        assertEquals("/second", fenceCompletionReference("/second"))
        assertEquals(":joy:", fenceCompletionReference(":joy:"))
        assertEquals("@file:`x`", fenceCompletionReference("@file:`x`"))
    }
}
