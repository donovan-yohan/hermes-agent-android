package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the older-page prepend is made of, asserted directly:
 * Desktop's merge (`apps/desktop/src/app/chat/transcript-backfill.ts:36-64` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) and its tail bookkeeping
 * (`apps/desktop/src/store/transcript-tail.ts:82-96`).
 */
class TranscriptBackfillTest {

    private fun turn(id: String, rowId: Long?) =
        UserTurn(id, "turn $id", 0, rowId = rowId?.let(::TranscriptRowId))

    @Test
    fun `an overlapping row is deduped by its durable address, not by its rendering key`() {
        val held = listOf(turn("101", 101), turn("102", 102))
        // The same persisted row, re-projected under a different rendering key —
        // which is what a page fetched at a shifted offset produces.
        val older = listOf(turn("99", 99), turn("100", 100), turn("tip-rest-2", 101))

        val merged = mergeOlderTranscriptPage(held, older)

        assertEquals(listOf("99", "100", "101", "102"), merged.map(TranscriptEntry::id))
    }

    @Test
    fun `a row with no durable address is deduped by its rendering key`() {
        val held = listOf(turn("tip-rest-4", null), turn("tip-rest-5", null))

        val merged = mergeOlderTranscriptPage(held, listOf(turn("tip-rest-3", null), turn("tip-rest-4", null)))

        assertEquals(listOf("tip-rest-3", "tip-rest-4", "tip-rest-5"), merged.map(TranscriptEntry::id))
    }

    @Test
    fun `prepending into an empty store is refused`() {
        // An empty store means the session was swapped or wiped mid-fetch;
        // prepending would paint the older page as the whole conversation.
        assertEquals(emptyList<TranscriptEntry>(), mergeOlderTranscriptPage(emptyList(), listOf(turn("99", 99))))
    }

    @Test
    fun `a page that adds nothing preserves reference identity`() {
        val held = listOf(turn("101", 101))

        assertSame(held, mergeOlderTranscriptPage(held, listOf(turn("101", 101))))
        assertSame(held, mergeOlderTranscriptPage(held, emptyList()))
    }

    @Test
    fun `a refreshed tail keeps the pages already loaded ahead of it`() {
        val loaded = listOf(turn("99", 99), turn("100", 100), turn("101", 101), turn("102", 102))

        val grafted = graftRefreshedTailOntoBackfill(listOf(turn("101", 101), turn("102", 102)), loaded)

        assertEquals(listOf("99", "100", "101", "102"), grafted.entries.map(TranscriptEntry::id))
        assertTrue(grafted.keptPrefix)
    }

    /**
     * The graft reports only THAT a prefix survived. How far the next page then
     * starts belongs to the window, in the stored rows an offset counts: the
     * projection both splits a row into two entries and drops rows outright, so
     * no count taken from this list is an offset.
     */
    @Test
    fun `the graft counts nothing, because entries are not rows`() {
        val loaded = listOf(
            ReasoningActivity("99-reasoning", "weighing", ToolState.Done, rowId = TranscriptRowId(99)),
            AssistantTurn("99", "answer", 0, rowId = TranscriptRowId(99)),
            turn("101", 101),
        )

        val grafted = graftRefreshedTailOntoBackfill(listOf(turn("101", 101)), loaded)

        assertEquals(3, grafted.entries.size)
        assertTrue(grafted.keptPrefix)
    }

    @Test
    fun `a tail that anchors nowhere is authoritative on its own`() {
        // A compaction rewrite, or a different session entirely.
        val refreshed = listOf(turn("501", 501))

        assertEquals(refreshed, graftRefreshedTailOntoBackfill(refreshed, listOf(turn("99", 99))).entries)
        assertFalse(graftRefreshedTailOntoBackfill(refreshed, listOf(turn("99", 99))).keptPrefix)
    }

    @Test
    fun `a full page means older rows likely exist and the offset advances past it`() {
        val state = transcriptPageState(requestedOffset = 120, echoedOffset = 120, echoedLimit = 120, returned = 120)

        assertEquals(TranscriptPageState(nextOffset = 240, possiblyTruncated = true), state)
    }

    @Test
    fun `a short page exhausts the session`() {
        val state = transcriptPageState(requestedOffset = 120, echoedOffset = 120, echoedLimit = 120, returned = 3)

        assertEquals(TranscriptPageState(nextOffset = 123, possiblyTruncated = false), state)
    }

    @Test
    fun `an answer without pagination is a backend that returned everything`() {
        val state = transcriptPageState(requestedOffset = 0, echoedOffset = null, echoedLimit = null, returned = 40)

        assertEquals(TranscriptPageState(nextOffset = 40, possiblyTruncated = false), state)
    }

    /**
     * The route decides the window, not the caller: it clamps any `limit` to
     * 500 and picks one when none was given (`sessions.py:669-671` @
     * `3ca096de`), so truncation is read off what came back.
     */
    @Test
    fun `the echoed window decides truncation, not the one that was asked for`() {
        val state = transcriptPageState(requestedOffset = 0, echoedOffset = 0, echoedLimit = 500, returned = 120)

        assertEquals(TranscriptPageState(nextOffset = 120, possiblyTruncated = false), state)
    }
}
