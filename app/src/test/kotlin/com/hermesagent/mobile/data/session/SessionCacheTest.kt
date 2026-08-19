package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cache rules Desktop depends on, asserted directly. */
class SessionCacheTest {

    private val cache = SessionCache()

    @Test
    fun `a refresh merges rather than replaces`() {
        cache.upsertSessions(listOf(row("a"), row("b")))

        // A partial refresh — the kind a paginated `session.list` produces.
        cache.upsertSessions(listOf(row("a", title = "renamed")))

        assertEquals(setOf("a", "b"), cache.state.value.sessions.keys)
        assertEquals("renamed", cache.session("a")?.title)
        assertEquals("Session b", cache.session("b")?.title)
    }

    @Test
    fun `a row leaves only through an explicit tombstone`() {
        cache.upsertSessions(listOf(row("a"), row("b")))
        cache.appendEntry("a", UserTurn("a-1", "hi", 0))

        cache.removeSession("a")

        assertEquals(setOf("b"), cache.state.value.sessions.keys)
        assertTrue("the transcript must go with the session", cache.transcript("a").isEmpty())
    }

    @Test
    fun `an upsert that changes nothing preserves reference identity`() {
        cache.upsertSessions(listOf(row("a")))
        val before = cache.state.value

        cache.upsertSessions(listOf(row("a")))

        assertSame("a no-op refresh must not hand Compose a fresh state object", before, cache.state.value)
    }

    @Test
    fun `putEntry rewrites a streaming turn in place instead of appending`() {
        cache.upsertSession(row("a"))
        cache.putEntry("a", AssistantTurn("a-turn", "Hel", 0, streaming = true))
        cache.putEntry("a", AssistantTurn("a-turn", "Hello", 0, streaming = true))
        cache.putEntry("a", AssistantTurn("a-turn", "Hello.", 0, streaming = false))

        val transcript = cache.transcript("a")
        assertEquals(1, transcript.size)
        assertEquals("Hello.", (transcript.single() as AssistantTurn).markdown)
        assertTrue(!(transcript.single() as AssistantTurn).streaming)
    }

    @Test
    fun `putEntry with an identical entry preserves reference identity`() {
        cache.upsertSession(row("a"))
        val entry = AssistantTurn("a-turn", "same", 0)
        cache.putEntry("a", entry)
        val before = cache.state.value

        cache.putEntry("a", entry)

        assertSame(before, cache.state.value)
    }

    @Test
    fun `transcripts are keyed per session and never bleed`() {
        cache.upsertSessions(listOf(row("a"), row("b")))
        cache.appendEntry("a", UserTurn("a-1", "for a", 0))
        cache.appendEntry("b", UserTurn("b-1", "for b", 0))

        assertEquals(listOf("a-1"), cache.transcript("a").map { it.id })
        assertEquals(listOf("b-1"), cache.transcript("b").map { it.id })
    }

    @Test
    fun `rehome atomically moves a session and its transcript to canonical identity`() {
        cache.upsertSession(row("parent", title = "Long chat"))
        cache.appendEntry("parent", UserTurn("turn-1", "hello", 0))

        cache.rehomeSession(
            fromId = "parent",
            row = row("tip", title = "Long chat"),
            entries = cache.transcript("parent"),
        )

        assertEquals(setOf("tip"), cache.state.value.sessions.keys)
        assertEquals(listOf("turn-1"), cache.transcript("tip").map { it.id })
        assertTrue(cache.transcript("parent").isEmpty())
        assertEquals("tip", cache.state.value.rehomes["parent"])
    }

    private fun row(id: String, title: String = "Session $id") =
        SessionSummary(id = id, title = title, preview = "", lastActiveAtMillis = 0)
}
