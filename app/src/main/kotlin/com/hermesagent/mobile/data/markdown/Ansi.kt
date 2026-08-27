package com.hermesagent.mobile.data.markdown

/**
 * The ANSI SGR parser behind terminal-shaped tool output.
 *
 * Port of Desktop's `apps/desktop/src/lib/ansi.ts` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` — the same rule set, the same
 * fixture set (`lib/ansi.test.ts` @ the same SHA), the same deliberate
 * omissions: colour, bold and reset are honoured; cursor motion, erase and
 * every other CSI final byte is consumed so it cannot leak into the rendered
 * text; background colours and 256/truecolour parameters are skipped without
 * bleeding their arguments through.
 *
 * Where this deviates from Desktop it is on purpose, and only ever toward
 * *dropping* bytes a phone would otherwise paint as garbage:
 *
 *  - Desktop matches escapes with two global regexes. This is a single-pass
 *    index scanner instead. Escape openers, parameter bytes and final bytes are
 *    disjoint character classes, so the scan never re-reads a character: the
 *    parser is linear in the input and total for every byte sequence, which is
 *    the property [parseAnsi] has to hold against untrusted tool output.
 *  - A truncated escape (a trailing `ESC`, or `ESC [ 3 1` with no final byte)
 *    is dropped rather than emitted. Desktop's regexes fail to match it and the
 *    payload leaks into the text as `[31`; on a streaming transcript the tail of
 *    every delta looks like that, and printing it is exactly the literal garbage
 *    this parser exists to remove.
 *  - Segment count is capped ([MAX_SEGMENTS]). Past the cap the remaining text
 *    still renders — it just stops changing style — so a hostile input that
 *    toggles colour every character cannot turn N bytes into N objects.
 *  - A `48` (background) selector consumes its arguments. Upstream lets them
 *    fall through to the code table, where `ESC[48;5;1m` turns the rest of the
 *    line bold and `ESC[48;2;0;0;255m` resets it outright.
 *
 * Callers clamp before parsing (see `clampForDisplay`), so the UI path never
 * hands this more than a bounded slice. The fuzz fixtures feed it megabytes
 * directly anyway: termination must not depend on the caller being polite.
 */

private const val ESC = '\u001B'

/** `ESC [` — the Control Sequence Introducer. */
private const val CSI = "\u001B["

/**
 * The most SGR parameter characters worth parsing. Beyond this a `m` sequence
 * is consumed and ignored rather than split: no real SGR is longer, and the cap
 * keeps a megabyte of digits from becoming a megabyte of substrings.
 */
private const val MAX_SGR_PARAM_CHARS = 64

/**
 * The most styled runs one payload may produce. Chosen well above any real
 * terminal output (a full-colour 200-line build log lands in the hundreds) and
 * far below what would strain a Compose `AnnotatedString`.
 */
internal const val MAX_SEGMENTS = 4_096

/**
 * The sixteen ANSI foreground colours Desktop renders (`ansi.ts:15-31`).
 *
 * Deliberately not an exhaustive terminal palette: 256-colour and truecolour
 * selectors are parsed only far enough to discard their arguments, because
 * mapping arbitrary RGB onto a themed surface is how terminal output becomes
 * unreadable in half the presets.
 */
enum class AnsiColor {
    Black,
    Red,
    Green,
    Yellow,
    Blue,
    Magenta,
    Cyan,
    White,
    BrightBlack,
    BrightRed,
    BrightGreen,
    BrightYellow,
    BrightBlue,
    BrightMagenta,
    BrightCyan,
    BrightWhite,
}

/** One run of text sharing a style. `color == null` is the default foreground. */
data class AnsiSegment(
    val text: String,
    val bold: Boolean = false,
    val color: AnsiColor? = null,
)

/**
 * The text copying one parse did, in characters.
 *
 * Shared by every run of that parse, and incremented by the one method that can
 * write into a run — so this is the parser's whole copying cost rather than a
 * sample of it, and a run that gets rebuilt and thrown away is still counted.
 * [parseAnsiCounted] hands it to the fixtures, which is how the linearity of
 * the merge path below is asserted as a number instead of a stopwatch reading.
 */
private class CopyCount {
    var characters = 0L
}

/**
 * A run still being built.
 *
 * The text accumulates into a [StringBuilder] rather than into an immutable
 * [AnsiSegment] that gets rebuilt on every flush. That is the difference
 * between linear and quadratic: escape-dense output flushes once per escape,
 * and re-copying the whole run each time made a 1 MB payload cost gigabytes of
 * copying — with the [MAX_SEGMENTS] cap making it *worse*, because past the cap
 * every flush merges.
 *
 * The builder is private and [append] is the only way in, so that difference
 * cannot be reintroduced without [copies] recording it.
 */
private class OpenSegment(val bold: Boolean, val color: AnsiColor?, private val copies: CopyCount) {
    private val builder = StringBuilder()

    fun append(chars: CharSequence) {
        builder.append(chars)
        copies.characters += chars.length
    }

    fun finish(): AnsiSegment = AnsiSegment(text = builder.toString(), bold = bold, color = color)
}

/** `ansi.ts:33-50` — SGR code to colour, normal (30-37) and bright (90-97). */
private val ForegroundByCode: Map<Int, AnsiColor> = mapOf(
    30 to AnsiColor.Black,
    31 to AnsiColor.Red,
    32 to AnsiColor.Green,
    33 to AnsiColor.Yellow,
    34 to AnsiColor.Blue,
    35 to AnsiColor.Magenta,
    36 to AnsiColor.Cyan,
    37 to AnsiColor.White,
    90 to AnsiColor.BrightBlack,
    91 to AnsiColor.BrightRed,
    92 to AnsiColor.BrightGreen,
    93 to AnsiColor.BrightYellow,
    94 to AnsiColor.BrightBlue,
    95 to AnsiColor.BrightMagenta,
    96 to AnsiColor.BrightCyan,
    97 to AnsiColor.BrightWhite,
)

/**
 * True when the input contains a CSI introducer.
 *
 * `ansi.ts:170-175` — the cheap check that lets a caller skip the parser for
 * plain output, which is almost all of it.
 */
fun hasAnsiCodes(input: String): Boolean = input.contains(CSI)

/** [parseAnsi]'s runs, plus the characters it copied to build them. */
internal class AnsiParse(val segments: List<AnsiSegment>, val charactersCopied: Long)

/**
 * [parseAnsi], with the copying counted.
 *
 * Exists for the fixtures. Escape-dense output flushes once per escape, and the
 * merge path has to extend the open run rather than rebuild it; the difference
 * between those is asymptotic, and the only honest way to assert an asymptote
 * on a shared CI runner is to count work rather than to time it. Every
 * character written into a run passes through [OpenSegment.append], so
 * [AnsiParse.charactersCopied] is exactly that work: the input's printable
 * length when the run is extended, its square when the run is rebuilt.
 *
 * [parseAnsi] goes through here rather than around it, so the counted path is
 * the shipped path. It costs one `Long` add per flush and two small objects per
 * parse, against a parse that already walks every byte.
 */
internal fun parseAnsiCounted(input: String): AnsiParse {
    if (input.isEmpty()) return AnsiParse(emptyList(), 0L)

    val copies = CopyCount()
    val segments = ArrayList<OpenSegment>()
    val pending = StringBuilder()
    var bold = false
    var color: AnsiColor? = null

    // `ansi.ts:75-89` — a run that matches the previous run's style extends it
    // rather than starting a new one, so `ESC[31ma ESC[31mb` is one segment.
    // Appending into the open run costs the length of what is appended, so the
    // total across every flush is the length of the input.
    fun flush() {
        if (pending.isEmpty()) return
        val last = segments.lastOrNull()
        if (last != null && (segments.size >= MAX_SEGMENTS || (last.bold == bold && last.color == color))) {
            last.append(pending)
        } else {
            segments.add(OpenSegment(bold, color, copies).apply { append(pending) })
        }
        pending.setLength(0)
    }

    var i = 0
    val n = input.length
    while (i < n) {
        val ch = input[i]
        if (ch != ESC) {
            pending.append(ch)
            i += 1
            continue
        }

        // An escape ends the current run whatever it turns out to be: the style
        // it may set applies to what follows, never to what came before.
        flush()

        val next = if (i + 1 < n) input[i + 1] else null
        when {
            // Truncated: a bare ESC at the end carries no sequence. Dropped.
            next == null -> i = n

            next == '[' -> {
                val scan = scanControlSequence(input, i + 2)
                // Length first, substring second: a megabyte of digits is not a
                // style anyone meant, and materialising it only to discard it
                // would be the one allocation this scanner does not bound.
                if (scan.final == 'm' && scan.paramsEnd - (i + 2) <= MAX_SGR_PARAM_CHARS) {
                    val state = applySgr(input.substring(i + 2, scan.paramsEnd), bold, color)
                    bold = state.bold
                    color = state.color
                }
                i = scan.next
            }

            // OSC (`ESC ]`) and the string-terminated sequences carry a payload
            // — a window title, a hyperlink — that must not print. `ansi.ts:59`
            // handles OSC only; the rest are consumed the same way because a
            // phone has no more use for a device-control string than a title.
            next == ']' || next == 'P' || next == 'X' || next == '^' || next == '_' ->
                i = scanStringSequence(input, i + 2)

            // Two-byte escapes (`ansi.ts:59`, the `\x1b[@-Z\\-_]` arm) and every
            // other opener: drop the pair. A doubled `ESC` drops only the first,
            // so the second still gets to introduce whatever follows it.
            else -> i += if (next == ESC) 1 else 2
        }
    }

    flush()
    return AnsiParse(segments.map { it.finish() }, copies.characters)
}

/** Parse [input] into styled runs. Total: every input returns, none throws. */
fun parseAnsi(input: String): List<AnsiSegment> = parseAnsiCounted(input).segments

/** Where a control sequence ended, and what its final byte was. */
private class ControlSequence(val paramsEnd: Int, val next: Int, val final: Char?)

/**
 * Consume a CSI body starting at [from]: parameter bytes `0x30-0x3F`, then
 * intermediate bytes `0x20-0x2F`, then one final byte `0x40-0x7E`.
 *
 * ECMA-48's own grammar, and the reason no cap is needed for termination: each
 * class is scanned once, forward, so a parameter run of any length costs one
 * pass over itself.
 *
 * A malformed body — no final byte, because the input ended or because a byte
 * belonging to no class turned up — reports no final byte and gives back the
 * position it stopped at. Only the bytes actually scanned are dropped: an
 * `ESC [ 3 1` cut off mid-stream must not swallow the rest of the log behind it.
 */
private fun scanControlSequence(input: String, from: Int): ControlSequence {
    var i = from
    val n = input.length
    while (i < n && input[i] in '0'..'?') i += 1
    val paramsEnd = i
    while (i < n && input[i] in ' '..'/') i += 1
    if (i < n && input[i] in '@'..'~') {
        return ControlSequence(paramsEnd = paramsEnd, next = i + 1, final = input[i])
    }
    return ControlSequence(paramsEnd = paramsEnd, next = i, final = null)
}

/**
 * Consume a string-payload escape (OSC, DCS, SOS, PM, APC) up to its terminator
 * — `BEL`, or `ESC \` — and return the index after it. An unterminated payload
 * runs to the end of the input and is dropped with it.
 *
 * A bare `ESC` inside the payload aborts it, the way a terminal does, and is
 * handed back to the caller *unconsumed* so it can still introduce the sequence
 * it was actually starting.
 */
private fun scanStringSequence(input: String, from: Int): Int {
    var i = from
    val n = input.length
    while (i < n) {
        val ch = input[i]
        if (ch == '\u0007') return i + 1
        if (ch == ESC) return if (i + 1 < n && input[i + 1] == '\\') i + 2 else i
        i += 1
    }
    return n
}

private class SgrState(val bold: Boolean, val color: AnsiColor?)

/**
 * Apply one SGR parameter string to the running style (`ansi.ts:101-131`).
 *
 * Codes honoured: `0` full reset, `1` bold, `22` bold off, `39` default
 * foreground, `30-37`/`90-97` foreground. `38` and `48` consume their
 * 256-colour (`5;n`) or truecolour (`2;r;g;b`) arguments so those cannot be
 * mistaken for codes of their own. Plain backgrounds (`40-47`, `100-107`) and
 * unhandled effects leave the style alone, exactly as upstream: an unknown code
 * must not silently reset the run.
 */
private fun applySgr(params: String, boldIn: Boolean, colorIn: AnsiColor?): SgrState {
    var bold = boldIn
    var color = colorIn

    // `ansi.ts:102-105` — an empty parameter is 0, and a parameter that is not
    // a number at all (or overflows, which is upstream's non-finite case) drops
    // out before the codes are walked.
    val codes = params.split(';').mapNotNull { part ->
        if (part.isEmpty()) 0 else part.toIntOrNull()
    }

    var i = 0
    while (i < codes.size) {
        when (val code = codes[i]) {
            0 -> {
                bold = false
                color = null
            }
            1 -> bold = true
            22 -> bold = false
            39 -> color = null
            // 256-colour and truecolour selectors. `48` is a *background*,
            // which is not painted — but its arguments still have to be
            // consumed, or `ESC[48;5;1m` reads as `1` (bold on) and
            // `ESC[48;2;0;0;255m` reads as `0` (full reset) mid-line. Upstream
            // has no `48` arm and does corrupt the run this way; on a phone
            // that shows up as a build log that suddenly goes bold and grey.
            38, 48 -> when (codes.getOrNull(i + 1)) {
                5 -> i += 2
                2 -> i += 4
            }
            else -> ForegroundByCode[code]?.let { color = it }
        }
        i += 1
    }

    return SgrState(bold = bold, color = color)
}
