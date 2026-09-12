package com.hermesagent.mobile.plugins.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The row's presentation leaves.
 *
 * Desktop sources at `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`:
 * `bot-row.tsx:79-88` (age), `lib/time.ts:194-215` (`coarseElapsed`),
 * `labels.ts:14-90` (name, preview), `data.ts:952-963` (@handle) and
 * `row-helpers.ts` (the bot-to-bot delivery prefix).
 */
class BotsRowLabelsTest {

    // ── coarseElapsed ─────────────────────────────────────────────────────────

    @Test
    fun `coarse elapsed floors to the coarsest unit`() {
        assertEquals(Elapsed(ElapsedUnit.Second, 0L), coarseElapsed(0L))
        assertEquals(Elapsed(ElapsedUnit.Second, 59L), coarseElapsed(59_999L))
        assertEquals(Elapsed(ElapsedUnit.Minute, 1L), coarseElapsed(60_000L))
        assertEquals(Elapsed(ElapsedUnit.Minute, 52L), coarseElapsed(52L * 60_000L))
        assertEquals(Elapsed(ElapsedUnit.Hour, 1L), coarseElapsed(60L * 60_000L))
        assertEquals(Elapsed(ElapsedUnit.Day, 18L), coarseElapsed(18L * 24L * 3_600_000L))
    }

    @Test
    fun `a negative delta clamps to zero`() {
        assertEquals(Elapsed(ElapsedUnit.Second, 0L), coarseElapsed(-5_000L))
    }

    // ── age label ─────────────────────────────────────────────────────────────

    @Test
    fun `the age label uses the sidebar's compact suffixes`() {
        val now = 1_000_000_000_000L

        assertEquals("now", rowAgeLabel(now - 5_000L, now))
        assertEquals("52m", rowAgeLabel(now - 52L * 60_000L, now))
        assertEquals("3h", rowAgeLabel(now - 3L * 3_600_000L, now))
        assertEquals("18d", rowAgeLabel(now - 18L * 86_400_000L, now))
    }

    @Test
    fun `the age label's suffixes are overridable`() {
        val labels = BotRowAgeLabels(now = "just now", day = " days", hour = " hours", minute = " min")

        assertEquals("just now", rowAgeLabel(1_000L, 2_000L, labels))
        assertEquals("5 min", rowAgeLabel(0L, 5L * 60_000L, labels))
    }

    // ── handle ────────────────────────────────────────────────────────────────

    @Test
    fun `the primary profile's handle reads hermes`() {
        assertEquals("hermes", botHandle("default"))
        assertEquals("hermes", botHandle("  Default  "))
        assertEquals("researcher", botHandle("researcher"))
    }

    // ── display name ──────────────────────────────────────────────────────────

    @Test
    fun `a core display name wins`() {
        assertEquals("Research Buddy", displayName("researcher", "Research Buddy"))
    }

    @Test
    fun `the primary profile presents as Hermes`() {
        assertEquals("Hermes", displayName("default", ""))
    }

    @Test
    fun `a bare name is title-cased and dashed names are spaced`() {
        assertEquals("Researcher", displayName("researcher", ""))
        assertEquals("Code Helper", displayName("code-helper", ""))
        assertEquals("Night Watch", displayName("night_watch", ""))
    }

    // ── markdown flattening ───────────────────────────────────────────────────

    @Test
    fun `preview markdown is flattened`() {
        assertEquals("bold and code", stripPreviewMarkdown("**bold** and `code`"))
        assertEquals("link text", stripPreviewMarkdown("[link](https://example.invalid) text"))
        assertEquals("alt", stripPreviewMarkdown("![alt](https://example.invalid/i.png)"))
        assertEquals("quote", stripPreviewMarkdown("> quote"))
        assertEquals("Heading", stripPreviewMarkdown("## Heading"))
        assertEquals("gone", stripPreviewMarkdown("~~gone~~"))
        assertEquals("one two", stripPreviewMarkdown("one\n\ntwo"))
        assertEquals("emphasis", stripPreviewMarkdown("*emphasis*"))
    }

    @Test
    fun `a fenced block flattens to nothing`() {
        assertEquals("before after", stripPreviewMarkdown("before ```\nx = 1\n``` after"))
    }

    @Test
    fun `null and blank previews flatten to empty`() {
        assertEquals("", stripPreviewMarkdown(null))
        assertEquals("", stripPreviewMarkdown("   "))
    }

    // ── bot-to-bot delivery prefix ────────────────────────────────────────────

    @Test
    fun `the current delivery prefix names its sender`() {
        assertEquals(
            "researcher",
            previewFromBot("Message from \uD83E\uDD16 researcher (@researcher): hello there"),
        )
    }

    @Test
    fun `the older delivery prefix names its sender`() {
        assertEquals("bot2", previewFromBot("Message from agent 'bot2': hello"))
        assertEquals("hello", displayPreview("Message from agent 'bot2': hello"))
    }

    @Test
    fun `a human preview has no sender`() {
        assertNull(previewFromBot("say something to get started"))
        assertNull(previewFromBot(null))
        assertNull(previewFromBot(""))
    }

    @Test
    fun `a bot-to-bot preview reads as the message`() {
        assertEquals(
            "hello there",
            displayPreview("Message from \uD83E\uDD16 researcher (@researcher): hello there"),
        )
    }

    @Test
    fun `a delivery prefix with no body reads as an ellipsis`() {
        assertEquals("…", displayPreview("Message from \uD83E\uDD16 researcher (@researcher):"))
    }

    @Test
    fun `a human preview keeps its text`() {
        assertEquals("hello there", displayPreview("hello there"))
        assertEquals("", displayPreview(null))
    }
}
