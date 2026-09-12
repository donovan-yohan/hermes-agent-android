package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * What the Gateway sends for a file edit, and what a phone is allowed to paint.
 *
 * Two fixture families. The first is Desktop's own, ported case for case:
 * `assistant-ui/tool/fallback-model.test.ts:182,184-187,451-454` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`, plus header-strip cases derived
 * from `chat/diff-lines.tsx:96-134` at the same SHA (its own
 * `diff-lines.test.tsx` covers only the lazy-chunk path).
 *
 * The second is the shape that actually arrives on the wire — built here exactly
 * as `agent/display.py` renders it at the pin, escapes and all — because that is
 * the payload the bug was about: the ESC byte is invisible in Compose, so
 * `ESC[38;2;125;187;255m` reached the screen as `[38;2;125;187;255m`.
 *
 * No control byte is written literally in this source. `AGENTS.md` bars a NUL
 * because Git's binary heuristic then hides the diff in review; an ESC is the
 * same argument, and a reviewer cannot see one either.
 */
class InlineDiffTest {

    /** The Kotlin escape for `ESC`, so no control byte sits in this source. */
    private val esc = "\u001B"

    /** `agent/display.py:75-77` @ `72a3277cd7` — `_fg`. */
    private fun fg(r: Int, g: Int, b: Int) = "$esc[38;2;$r;$g;${b}m"

    /**
     * `agent/display.py:79-81` @ `72a3277cd7` — `_tinted_bg`: white text over a
     * dark tint of the skin's `ui_error` / `ui_ok`.
     */
    private fun tintedBg(r: Int, g: Int, b: Int) = "$esc[38;2;255;255;255;48;2;$r;$g;${b}m"

    /** `agent/display.py:24` @ `72a3277cd7`. */
    private val reset = "$esc[0m"

    // -- Desktop's fixtures ---------------------------------------------------

    /** `fallback-model.test.ts:182` @ `72a3277cd7`. */
    private val patchDiff = "--- a/src/demo.ts\n+++ b/src/demo.ts\n@@ -1 +1 @@\n-old\n+new"

    @Test
    fun `a clean unified diff passes through the chrome strip unchanged`() {
        // `fallback-model.test.ts:184-187` — `inlineDiffFromResult` reads the
        // field and returns `stripInlineDiffChrome` of it; the Android caller
        // reads the field off `ToolActivity`, so this pins the strip itself.
        assertEquals(patchDiff, stripInlineDiffChrome(patchDiff))
    }

    @Test
    fun `counts added and removed lines`() {
        // `fallback-model.test.ts:451-454`, verbatim.
        assertEquals(
            DiffLineStats(added = 2, removed = 1),
            countDiffLineStats("--- a/x\n+++ b/x\n@@\n-old\n+new\n context\n+another"),
        )
    }

    @Test
    fun `an empty payload has no stats and no chrome`() {
        assertEquals("", stripInlineDiffChrome(""))
        assertEquals(DiffLineStats(added = 0, removed = 0), countDiffLineStats(""))
    }

    // -- The header zone (diff-lines.tsx:96-134) ------------------------------

    @Test
    fun `a git preamble is skipped up to the first hunk`() {
        val diff = """
            diff --git a/src/demo.ts b/src/demo.ts
            index 1a2b3c4..5d6e7f8 100644
            --- a/src/demo.ts
            +++ b/src/demo.ts
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()

        assertEquals("@@ -1 +1 @@\n-old\n+new", stripDiffFileHeaders(diff))
    }

    @Test
    fun `the arrow line the gateway emits instead of a header pair is skipped`() {
        // `agent/display.py:672-690` @ `72a3277cd7` collapses `---`/`+++` into
        // one arrow line, and an absolute path makes it `a//Users/...`, which is
        // the case `diff-lines.tsx:91-95` names as reading especially badly.
        val diff = """
            a//Users/x/y → b//Users/x/y

            @@ -1 +1 @@
            -old
        """.trimIndent()

        assertEquals("@@ -1 +1 @@\n-old", stripDiffFileHeaders(diff))
        assertTrue(isArrowHeaderLine("a//Users/x/y → b//Users/x/y"))
        assertTrue(isArrowHeaderLine("  a/x → b/x  "))
    }

    @Test
    fun `a diff line that happens to contain an arrow is not a header`() {
        // `diff-lines.tsx:110` — a line opening with a diff marker is content.
        assertFalse(isArrowHeaderLine("+const arrow = a → b"))
        assertFalse(isArrowHeaderLine("-const arrow = a → b"))
        assertFalse(isArrowHeaderLine("@@ a → b @@"))
    }

    @Test
    fun `the header zone stops at the first line that is not a header`() {
        val diff = "--- a/x\nhello → there is content here\n+++ b/x"

        // The second line is content, so it and everything after it survives —
        // including the `+++` line, which is only a header while it is still in
        // the leading zone.
        assertEquals("hello → there is content here\n+++ b/x", stripDiffFileHeaders(diff))
    }

    @Test
    fun `a payload with no header zone is untouched`() {
        assertEquals("@@ -1 +1 @@\n-old", stripDiffFileHeaders("@@ -1 +1 @@\n-old"))
    }

    // -- The gateway's real shape ---------------------------------------------

    /**
     * What `tool.complete` carries for one file edit, assembled the way
     * `agent/display.py` @ `72a3277cd7` assembles it: the banner from `:656-664`,
     * the arrow line and the classified lines from `:669-690` in `_diff_ansi()`'s
     * colours (`:63-81,84-105`), and the cap trailer from `:703-731`. The RGB
     * values are that module's own dark-terminal fallbacks.
     */
    private val gatewayDiff = listOf(
        "  ┊ review diff",
        "${fg(180, 160, 255)}a/notes.md → b/notes.md$reset",
        "${fg(120, 120, 140)}@@ -1,3 +1,4 @@$reset",
        "${tintedBg(60, 10, 10)}-old line$reset",
        "${tintedBg(10, 45, 10)}+new line$reset",
        "${fg(150, 150, 150)} context line$reset",
        "${fg(120, 120, 140)}… omitted 3 diff line(s) across 2 additional file(s)/section(s)$reset",
    ).joinToString("\n")

    @Test
    fun `no escape byte and no sgr payload survives the gateway diff`() {
        val cleaned = stripInlineDiffChrome(gatewayDiff)

        assertFalse("an ESC byte reached the panel: $cleaned", cleaned.contains(esc))
        assertFalse("a truecolour foreground leaked as text: $cleaned", cleaned.contains("[38;2"))
        assertFalse("a tinted background leaked as text: $cleaned", cleaned.contains("[48;2"))
        assertFalse("the TTY banner reached the panel: $cleaned", cleaned.contains("┊ review diff"))
    }

    @Test
    fun `a banner the tui wrapped in yellow is still recognised`() {
        // `ui-tui/src/__tests__/createGatewayEventHandler.test.ts:758` @
        // `72a3277cd7` shows the banner arriving inside `ESC[33m ... ESC[0m`;
        // `stripAnsi` runs first, so the pattern only has to meet the spacing.
        val wrapped = "$esc[33m  ┊ review diff$reset\n@@ -1 +1 @@\n-old"

        assertEquals("@@ -1 +1 @@\n-old", stripInlineDiffChrome(wrapped))
    }

    @Test
    fun `the gateway diff counts one addition and one removal`() {
        assertEquals(
            DiffLineStats(added = 1, removed = 1),
            countDiffLineStats(stripInlineDiffChrome(gatewayDiff)),
        )
    }

    @Test
    fun `the gateway diff parses to its body with the markers and the noise gone`() {
        val lines = parseDiff(stripInlineDiffChrome(gatewayDiff))

        assertEquals(
            listOf(
                DiffLine(DiffKind.Remove, "old line"),
                DiffLine(DiffKind.Add, "new line"),
                DiffLine(DiffKind.Context, "context line"),
                // `agent/display.py:727` — the cap trailer is not a diff line:
                // it opens with an ellipsis, so it classifies as context and
                // keeps every character (`diff-lines.tsx:83-89`).
                DiffLine(DiffKind.Context, "… omitted 3 diff line(s) across 2 additional file(s)/section(s)"),
            ),
            lines,
        )

        val painted = lines.joinToString("\n") { it.text }
        assertFalse("the hunk header must not be painted: $painted", painted.contains("@@"))
        assertFalse("the arrow header must not be painted: $painted", painted.contains("→"))
    }

    // -- Hunks (diff-lines.tsx:135-215) ---------------------------------------

    @Test
    fun `two hunks are separated by one blank context line`() {
        val diff = """
            @@ -1,2 +1,2 @@
            -a
            +b
            @@ -9,2 +9,2 @@
            -c
            +d
        """.trimIndent()

        assertEquals(
            listOf(
                DiffLine(DiffKind.Remove, "a"),
                DiffLine(DiffKind.Add, "b"),
                DiffLine(DiffKind.Context, ""),
                DiffLine(DiffKind.Remove, "c"),
                DiffLine(DiffKind.Add, "d"),
            ),
            parseDiff(diff),
        )
    }

    @Test
    fun `git's no-newline marker is dropped`() {
        // `diff-lines.tsx:155` — a backslash line is skipped rather than drawn.
        assertEquals(
            listOf(DiffLine(DiffKind.Remove, "a"), DiffLine(DiffKind.Add, "b")),
            parseDiff("@@ -1 +1 @@\n-a\n\\ No newline at end of file\n+b"),
        )
    }

    @Test
    fun `a payload with no hunk header falls back to classifying every line`() {
        // `diff-lines.tsx:172-177` — what a brand-new file's write looks like.
        assertEquals(
            listOf(
                DiffLine(DiffKind.Add, "first"),
                DiffLine(DiffKind.Add, "second"),
                DiffLine(DiffKind.Context, "trailing note"),
            ),
            parseDiff("+++ b/new.md\n+first\n+second\ntrailing note"),
        )
    }

    @Test
    fun `an unparseable hunk header closes the hunk rather than opening one`() {
        // `diff-lines.tsx:140-147` — the body of a malformed `@@` is dropped,
        // and with no hunk at all the fallback classifies what is left.
        assertEquals(
            listOf(DiffLine(DiffKind.Context, "@@ nonsense @@"), DiffLine(DiffKind.Add, "x")),
            parseDiff("@@ nonsense @@\n+x"),
        )
    }

    // -- What is chrome, and what only looks like it --------------------------

    @Test
    fun `a review diff banner below the first line is content, not chrome`() {
        // `index.ts:778` @ `72a3277cd7` is neither global nor multiline: it is
        // anchored at the start of the input and replaces once. A `┊ review
        // diff` further down is a line of the file being edited, and deleting
        // it would be deleting the reader's own text.
        val diff = "@@ -1 +1 @@\n+a\n  ┊ review diff\n+b"

        assertEquals(diff, stripInlineDiffChrome(diff))
        assertEquals(
            listOf(
                DiffLine(DiffKind.Add, "a"),
                DiffLine(DiffKind.Context, " ┊ review diff"),
                DiffLine(DiffKind.Add, "b"),
            ),
            parseDiff(stripInlineDiffChrome(diff)),
        )
    }

    @Test
    fun `sgr parameter bytes with no escape in front of them are content`() {
        // The exact bytes the bug put on the screen — but this time they really
        // are in the file. Without the ESC there is no escape sequence, so the
        // strip must leave every character where it is; a broader "looks like
        // ANSI" pattern would eat a line of the reader's own source.
        val diff = "@@ -1 +1 @@\n+[38;2;1;2;3m\n-x"

        assertEquals(diff, stripInlineDiffChrome(diff))
        assertEquals(DiffLineStats(added = 1, removed = 1), countDiffLineStats(diff))
        assertEquals(
            listOf(DiffLine(DiffKind.Add, "[38;2;1;2;3m"), DiffLine(DiffKind.Remove, "x")),
            parseDiff(diff),
        )
    }

    @Test
    fun `an unterminated osc swallows the rest of the diff, and both halves agree`() {
        // A ledgered divergence, pinned so it cannot change silently.
        // `index.ts:771-773` @ `72a3277cd7` strips SGR only, so Desktop would
        // leave `ESC]junk` and the two lines behind it in place. This port
        // shares `parseAnsi`'s scanner (`Ansi.kt`), which consumes an OSC
        // string until its terminator — and an unterminated one runs to the end
        // of the input. The point of the test is that *one* thing reads the
        // payload: the count and the body are both taken from `cleaned`, so the
        // header can never disagree with what is under it.
        val diff = "@@ -1 +1 @@\n+a\n$esc]junk\n+b\n+c"
        val cleaned = stripInlineDiffChrome(diff)

        assertEquals("@@ -1 +1 @@\n+a", cleaned)
        assertEquals(DiffLineStats(added = 1, removed = 0), countDiffLineStats(cleaned))
        assertEquals(listOf(DiffLine(DiffKind.Add, "a")), parseDiff(cleaned))
    }

    // -- Bounded work ---------------------------------------------------------

    @Test
    fun `a megabyte of rendered diff is cleaned and parsed well inside a frame budget`() {
        // Every line carries the wire's full chrome, so this times the strip, the
        // header scan and the hunk walk together over about a megabyte.
        val body = buildString {
            append("  ┊ review diff\n")
            append("${fg(180, 160, 255)}a/big.md → b/big.md$reset\n")
            append("${fg(120, 120, 140)}@@ -1,20000 +1,20000 @@$reset\n")
            repeat(20_000) { index ->
                append("${tintedBg(10, 45, 10)}+line $index of a long generated file$reset\n")
            }
        }
        assertTrue("the fixture must actually be about a megabyte", body.length > 1_000_000)

        var parsed = 0
        val elapsed = measureTimeMillis {
            parsed = parseDiff(stripInlineDiffChrome(body)).size
        }

        assertEquals(20_000, parsed)
        assertTrue("cleaning and parsing 1 MB took ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun `a payload-sized line of arrows costs nothing to reject as a header`() {
        // `^\S.*→\s*\S+$` backtracks quadratically: the greedy `.*` walks back
        // over every `→` and re-scans the tail from each. Tool output is
        // untrusted (`AGENTS.md`) and bounded only by the ingest cap
        // (`GatewaySessionRepository.MAX_TOOL_PAYLOAD`, 32,768), and this runs
        // inside a `remember {}` on the composition thread — an unguarded
        // regex costs seconds there. A real header is two paths, so a line this
        // long is refused on length before the regex ever sees it.
        val hostile = "\u2192".repeat(16_000) + "a".repeat(16_000) + " b"
        assertEquals(32_002, hostile.length)

        var arrow = true
        var stripped = ""
        val elapsed = measureTimeMillis {
            arrow = isArrowHeaderLine(hostile)
            stripped = stripDiffFileHeaders("$hostile\n@@ -1 +1 @@\n+x")
        }

        assertFalse("a 32 KB line is not a file header", arrow)
        assertTrue("so it ends the header zone and survives", stripped.startsWith(hostile))
        assertTrue("rejecting it took ${elapsed}ms", elapsed < 250)
    }

    @Test
    fun `a real arrow header is still recognised at the length cap`() {
        // The cap refuses nothing a header could carry: `PATH_MAX` is 4096, and
        // 1,024 is the longest line still tested. Right at it, the answer is
        // unchanged.
        val path = "d".repeat(500)
        val header = "a/$path → b/$path"
        assertEquals(1_007, header.length)

        assertTrue(isArrowHeaderLine(header))
        assertFalse("one character past the cap is refused", isArrowHeaderLine(header + "e".repeat(20)))
    }
}
