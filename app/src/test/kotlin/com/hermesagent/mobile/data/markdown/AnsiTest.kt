package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * The ANSI parser's contract, in two halves.
 *
 * The first half is Desktop's own fixture set — `apps/desktop/src/lib/
 * ansi.test.ts` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` — ported case for
 * case, so a drift in the rule set is a red test here rather than a colour that
 * quietly stops appearing on a phone.
 *
 * The second half is the property Desktop never had to prove: this parser runs
 * over bytes a remote agent chose, on a device the user is holding. It has to
 * be total (no input throws) and bounded (no input makes it do super-linear
 * work), so the fixtures below are deliberately hostile.
 */
class AnsiTest {

    private val esc = "\u001B"

    // ── Desktop's fixtures, case for case (ansi.test.ts:7-96) ────────────────

    @Test
    fun `returns a single default segment for plain text`() {
        assertEquals(listOf(AnsiSegment("hello world")), parseAnsi("hello world"))
    }

    @Test
    fun `returns nothing for an empty string`() {
        assertEquals(emptyList<AnsiSegment>(), parseAnsi(""))
    }

    @Test
    fun `parses a basic foreground colour sequence and resets`() {
        assertEquals(
            listOf(
                AnsiSegment("error", color = AnsiColor.Red),
                AnsiSegment(" ok"),
            ),
            parseAnsi("${esc}[31merror${esc}[0m ok"),
        )
    }

    @Test
    fun `treats bold and bold-off as toggles without affecting the foreground`() {
        assertEquals(
            listOf(
                AnsiSegment("loud", bold = true),
                AnsiSegment(" quiet"),
            ),
            parseAnsi("${esc}[1mloud${esc}[22m quiet"),
        )
    }

    @Test
    fun `treats default-fg as a foreground-only reset that keeps bold`() {
        assertEquals(
            listOf(
                AnsiSegment("both", bold = true, color = AnsiColor.Red),
                AnsiSegment("bold-only", bold = true),
            ),
            parseAnsi("${esc}[1;31mboth${esc}[39mbold-only"),
        )
    }

    @Test
    fun `handles bright colours via the 90-97 range`() {
        assertEquals(
            listOf(AnsiSegment("green", color = AnsiColor.BrightGreen)),
            parseAnsi("${esc}[92mgreen"),
        )
    }

    @Test
    fun `coalesces adjacent runs with the same style`() {
        assertEquals(
            listOf(AnsiSegment("abc", color = AnsiColor.Red)),
            parseAnsi("${esc}[31ma${esc}[31mb${esc}[31mc"),
        )
    }

    @Test
    fun `skips 256-colour trailing args without painting or leaking them`() {
        // The index is deliberately `31`: if the parser stopped consuming the
        // `5;n` arguments, that `31` would paint the run red and this fails.
        assertEquals(listOf(AnsiSegment("orange")), parseAnsi("${esc}[38;5;31morange${esc}[0m"))
    }

    @Test
    fun `skips truecolour trailing args`() {
        assertEquals(listOf(AnsiSegment("rgb")), parseAnsi("${esc}[38;2;10;20;30mrgb${esc}[0m"))
    }

    @Test
    fun `drops non-SGR sequences without consuming the text around them`() {
        assertEquals(
            listOf(AnsiSegment("beforemiddleafter")),
            parseAnsi("before${esc}[2Jmiddle${esc}[10;5Hafter"),
        )
    }

    @Test
    fun `treats an empty SGR parameter as a full reset`() {
        assertEquals(
            listOf(
                AnsiSegment("foo", bold = true, color = AnsiColor.Red),
                AnsiSegment("bar"),
            ),
            parseAnsi("${esc}[1;31mfoo${esc}[mbar"),
        )
    }

    @Test
    fun `hasAnsiCodes is false for plain text and true for any CSI introducer`() {
        assertFalse(hasAnsiCodes("hello world"))
        assertTrue(hasAnsiCodes("${esc}[31mred"))
    }

    @Test
    fun `every colour in the 30-37 and 90-97 ranges maps to a distinct ink`() {
        val painted = (30..37).map { it } + (90..97).map { it }
        val colours = painted.mapNotNull { code -> parseAnsi("${esc}[${code}mx").single().color }
        assertEquals("every code in both ranges must paint", painted.size, colours.size)
        assertEquals("no two codes may share an ink", colours.size, colours.toSet().size)
        assertEquals(AnsiColor.values().toSet(), colours.toSet())
    }

    @Test
    fun `background colours and unknown effects leave the run's style alone`() {
        // ansi.ts:129-130 — 40-47 and 100-107 are ignored on purpose, and an
        // unknown code must not act as a silent reset.
        assertEquals(
            listOf(AnsiSegment("still red", bold = true, color = AnsiColor.Red)),
            parseAnsi("${esc}[1;31m${esc}[44m${esc}[53mstill red"),
        )
    }

    // ── Hostile input: total and bounded ─────────────────────────────────────

    @Test
    fun `a truncated escape at the very end is dropped, not printed`() {
        assertEquals(listOf(AnsiSegment("tail")), parseAnsi("tail$esc"))
    }

    @Test
    fun `an unterminated CSI does not leak its parameters as text`() {
        // A streamed delta can be cut anywhere, including mid-sequence. Desktop's
        // regex leaves `[31` in the text; painting that is the literal garbage
        // this parser exists to remove.
        assertEquals(listOf(AnsiSegment("head")), parseAnsi("head${esc}[31"))
        assertEquals(listOf(AnsiSegment("head")), parseAnsi("head${esc}["))
    }

    @Test
    fun `an unterminated OSC payload is swallowed whole`() {
        assertEquals(listOf(AnsiSegment("before")), parseAnsi("before$esc]0;a window title"))
        assertEquals(
            listOf(AnsiSegment("beforeafter")),
            parseAnsi("before$esc]0;title\u0007after"),
        )
    }

    @Test
    fun `a huge repeat count is a parameter, never a repetition`() {
        val segments = parseAnsi("${esc}[999999999999999m${esc}[31mred")
        assertEquals(listOf(AnsiSegment("red", color = AnsiColor.Red)), segments)
    }

    @Test
    fun `an absurdly long parameter run is consumed rather than parsed`() {
        val params = "1;".repeat(100_000)
        val segments = parseAnsi("${esc}[${params}31mplain")
        // The whole segment, not just its text: without the cap those params
        // parse and the run comes back bold red.
        assertEquals(listOf(AnsiSegment("plain")), segments)
    }

    @Test
    fun `invalid UTF-16 passes through as text instead of throwing`() {
        val loneHighSurrogate = "\uD800"
        val loneLowSurrogate = "\uDC00"
        val input = "$loneHighSurrogate${esc}[31mred$loneLowSurrogate\uFFFD"
        val text = parseAnsi(input).joinToString("") { it.text }
        assertEquals("$loneHighSurrogate" + "red" + loneLowSurrogate + "\uFFFD", text)
    }

    @Test
    fun `a malformed csi gives the bytes it did not scan back as text`() {
        // D2 in docs/parity/tool-output-fidelity.md: the parser consumes only
        // what it scanned. A lone surrogate is neither a parameter, an
        // intermediate nor a final byte, so the scan stops on it and the rest of
        // the line survives as text rather than being swallowed with the escape.
        val text = parseAnsi("${esc}[3\uD8001mx").joinToString("") { it.text }

        assertEquals("\uD800" + "1mx", text)
    }

    @Test
    fun `a megabyte of hostile bytes terminates quickly and renders everything`() {
        val chunk = "${esc}[31m$esc$esc[${esc}]0;x${esc}[38;2;1;2;3mline\n"
        val input = buildString { while (length < 1_000_000) append(chunk) }

        val chunks = input.length / chunk.length
        var segments: List<AnsiSegment> = emptyList()
        val millis = measureTimeMillis { segments = parseAnsi(input) }

        assertTrue("parse of ${input.length} chars took ${millis}ms", millis < 5_000)
        // Exactly what the fixture spells: one red run holding every line, with
        // nothing dropped and nothing leaked. `isNotEmpty` would have passed on
        // a parser that threw away 99% of the payload.
        assertEquals("one coalesced run, saw ${segments.map { it.text.length }}", 1, segments.size)
        assertEquals(AnsiColor.Red, segments.single().color)
        assertEquals("every line must survive", chunks * "line\n".length, segments.single().text.length)
        assertEquals("line\n".repeat(chunks), segments.single().text)
    }

    @Test
    fun `a colour change per character cannot outgrow the segment cap`() {
        val input = buildString {
            repeat(MAX_SEGMENTS * 3) { index -> append("${esc}[3${index % 8}m").append('x') }
        }
        val segments = parseAnsi(input)

        assertTrue("segments must stay capped, saw ${segments.size}", segments.size <= MAX_SEGMENTS)
        assertEquals("no character may be dropped to honour the cap", MAX_SEGMENTS * 3, segments.sumOf { it.text.length })
    }

    @Test
    fun `a megabyte of nothing but escape introducers terminates`() {
        val input = esc.repeat(500_000)
        val millis = measureTimeMillis { assertEquals(emptyList<AnsiSegment>(), parseAnsi(input)) }
        assertTrue("took ${millis}ms", millis < 5_000)
    }

    @Test
    fun `no escape byte survives any hostile fixture`() {
        val fixtures = listOf(
            "",
            "plain",
            "tail$esc",
            "head${esc}[31",
            "${esc}[999999999999999mx",
            "before$esc]0;title\u0007after",
            "${esc}[1;31mfoo${esc}[mbar",
            "a${esc}Pdcs payload${esc}\\b",
            "$esc$esc$esc[",
        )
        for (fixture in fixtures) {
            val text = parseAnsi(fixture).joinToString("") { it.text }
            assertFalse(
                "an escape byte reached the rendered text for <${fixture.replace(esc, "ESC")}>",
                text.contains(esc),
            )
        }
    }

    @Test
    fun `a device-control string is swallowed up to its terminator`() {
        assertEquals(listOf(AnsiSegment("ab")), parseAnsi("a${esc}Pdcs payload${esc}\\b"))
    }

    @Test
    fun `a bare escape aborts an OSC payload and still introduces what follows`() {
        // The aborting escape is handed back unconsumed, so the sequence it was
        // actually starting still gets to run.
        assertEquals(
            listOf(AnsiSegment("a"), AnsiSegment("red", color = AnsiColor.Red)),
            parseAnsi("a${esc}]0;title${esc}[31mred"),
        )
    }

    @Test
    fun `a background selector consumes its arguments instead of restyling the run`() {
        // Upstream has no `48` arm, so `5` then `1` reads as bold-on and
        // `2;0;0;255` reads as a full reset. Neither may happen here.
        assertEquals(
            listOf(AnsiSegment("still bold red", bold = true, color = AnsiColor.Red)),
            parseAnsi("${esc}[1;31m${esc}[48;5;1mstill bold red"),
        )
        assertEquals(
            listOf(AnsiSegment("still bold red", bold = true, color = AnsiColor.Red)),
            parseAnsi("${esc}[1;31m${esc}[48;2;0;0;255mstill bold red"),
        )
    }

    @Test
    fun `merging a long run copies each character once rather than the whole run`() {
        // Every escape here leaves the style unchanged, so every flush merges
        // into the open run — the shape that used to rebuild the run and
        // re-copy everything already in it. That difference is asymptotic, and
        // a wall-clock ratio cannot see an asymptote: it sees whatever else the
        // runner was doing. So the parser counts the characters it writes into
        // runs, and this asserts the count.
        val runLength = 64
        val repeats = 14_500
        fun input(escapes: Int) = buildString {
            repeat(escapes) { append("${esc}[31m").append("x".repeat(runLength)) }
        }

        val small = input(repeats) // ~1 MB, one escape per 64 characters.
        val large = input(repeats * 2) // ~2 MB of the same shape.

        val smallParse = parseAnsiCounted(small)
        val largeParse = parseAnsiCounted(large)

        // The whole payload is one style, so every flush after the first merges
        // — this exercises the merge path rather than a run of fresh segments.
        assertEquals("the fixture must merge, not accumulate segments", 1, smallParse.segments.size)
        assertEquals("the fixture must merge, not accumulate segments", 1, largeParse.segments.size)

        assertEquals(
            "each printable character must be copied into its run exactly once",
            (repeats.toLong() * runLength),
            smallParse.charactersCopied,
        )
        assertEquals(
            "doubling the payload must double the copying",
            smallParse.charactersCopied * 2,
            largeParse.charactersCopied,
        )

        // What the rebuilt-run shape would cost at this size: the run is
        // re-copied once per escape, so the total is the triangular number of
        // run lengths — about 6.7e9 characters against 928,000 here, some
        // 7,200x apart. Asserted so the fixture cannot shrink to a size where
        // the two shapes stop being distinguishable.
        val rebuildCost = runLength.toLong() * repeats * (repeats + 1) / 2
        assertTrue(
            "extending costs ${smallParse.charactersCopied} and rebuilding costs $rebuildCost — " +
                "the fixture no longer separates the two shapes",
            rebuildCost > smallParse.charactersCopied * 1_000,
        )
    }
}
