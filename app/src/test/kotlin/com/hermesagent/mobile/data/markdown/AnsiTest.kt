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
        val segments = parseAnsi("${esc}[38;5;208morange${esc}[0m")
        assertEquals(listOf(AnsiSegment("orange")), segments)
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

    @Test
    fun `strips every sequence when asked for plain text`() {
        assertEquals("error ok", stripAnsi("${esc}[31merror${esc}[0m ok"))
        assertEquals("plain", stripAnsi("plain"))
    }

    // ── Hostile input: total and bounded ─────────────────────────────────────

    @Test
    fun `a truncated escape at the very end is dropped, not printed`() {
        assertEquals(listOf(AnsiSegment("tail")), parseAnsi("tail$esc"))
        assertEquals("tail", stripAnsi("tail$esc"))
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
        val text = "${esc}[${params}31mplain"
        val segments = parseAnsi(text)
        // Consumed as one sequence: the text survives, the digits do not, and
        // the style is left alone rather than half-applied.
        assertEquals("plain", segments.joinToString("") { it.text })
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
    fun `a megabyte of hostile bytes terminates quickly and renders everything`() {
        val chunk = "${esc}[31m$esc$esc[${esc}]0;x${esc}[38;2;1;2;3mline\n"
        val input = buildString { while (length < 1_000_000) append(chunk) }

        var segments: List<AnsiSegment> = emptyList()
        val millis = measureTimeMillis { segments = parseAnsi(input) }

        assertTrue("parse of ${input.length} chars took ${millis}ms", millis < 5_000)
        assertTrue("output must not be empty", segments.isNotEmpty())
        assertTrue("the visible text must survive", segments.any { it.text.contains("line") })
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
    fun `stripAnsi and parseAnsi agree on every hostile fixture`() {
        val fixtures = listOf(
            "",
            "plain",
            "tail$esc",
            "head${esc}[31",
            "${esc}[999999999999999mx",
            "before$esc]0;title\u0007after",
            "${esc}[1;31mfoo${esc}[mbar",
            "a${esc}Pdcs payload${esc}\\b",
        )
        for (fixture in fixtures) {
            assertEquals(
                "stripAnsi must equal the concatenated segments for <${fixture.replace(esc, "ESC")}>",
                parseAnsi(fixture).joinToString("") { it.text },
                stripAnsi(fixture),
            )
        }
    }
}
