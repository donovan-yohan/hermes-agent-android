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
 * `assistant-ui/tool/fallback-model.test.ts:176,180-183,451-454` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`, plus header-strip cases derived
 * from `chat/diff-lines.tsx:96-133` at the same SHA (its own
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

    /** `fallback-model.test.ts:176` @ `72a3277cd7`. */
    private val patchDiff = "--- a/src/demo.ts\n+++ b/src/demo.ts\n@@ -1 +1 @@\n-old\n+new"

    @Test
    fun `a clean unified diff passes through the chrome strip unchanged`() {
        // `fallback-model.test.ts:180-183` — `inlineDiffFromResult` reads the
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

    // -- The header zone (diff-lines.tsx:96-133) ------------------------------

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
        // the case `diff-lines.tsx:98-101` names as reading especially badly.
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
        // `diff-lines.tsx:111` — a line opening with a diff marker is content.
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
                // keeps every character (`diff-lines.tsx:83-93`).
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
        // `diff-lines.tsx:154` — a backslash line is skipped rather than drawn.
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
        // `diff-lines.tsx:140-146` — the body of a malformed `@@` is dropped,
        // and with no hunk at all the fallback classifies what is left.
        assertEquals(
            listOf(DiffLine(DiffKind.Context, "@@ nonsense @@"), DiffLine(DiffKind.Add, "x")),
            parseDiff("@@ nonsense @@\n+x"),
        )
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
}
