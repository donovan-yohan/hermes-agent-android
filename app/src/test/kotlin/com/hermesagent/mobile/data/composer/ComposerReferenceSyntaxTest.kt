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
        
        // Adjacent spans
        val adjacent = composerReferenceSpans("@url:`a`@url:`b`")
        assertEquals(2, adjacent.size)
    }

    @Test
    fun testMaskComposerReferences() {
        val text = "see @url:`https://example.dev/a` now"
        val masked = maskComposerReferences(text)
        assertEquals(text.length, masked.length)
        assertEquals("see " + "\uFFFC".repeat(28) + " now", masked)
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
