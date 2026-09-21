package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which field one file-edit row's diff is read from.
 *
 * `fallback.tsx:375-392` and `fallback-model/index.ts:824-835` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337` decide it in two steps —
 * `stripInlineDiffChrome(sideDiff) || inlineDiffFromResult(toolResultRecord(…))`
 * — and the second step is the one that was missing here: a `patch` result
 * carries its diff *only* in the result object
 * (`tools/file_operations.py:1355-1357` @ the same SHA), so reading the live
 * side-channel alone sent a perfectly good patch to the generic collapsed
 * disclosure, where it painted raw JSON.
 *
 * [InlineDiffTest] pins the chrome strip and the parse this resolver feeds;
 * this pins precedence, the fallbacks, and what counts as "no diff".
 */
class EffectiveInlineDiffTest {

    private val patch = "--- a/src/demo.ts\n+++ b/src/demo.ts\n@@ -1 +1 @@\n-old\n+new"

    // ── Precedence ───────────────────────────────────────────────────────────

    @Test
    fun `the live side channel wins when it carries a diff`() {
        // Desktop evaluates `stripInlineDiffChrome(sideDiff)` first, so a live
        // payload that has anything in it is the one a reader sees.
        assertEquals(patch, resolveEffectiveInlineDiff(patch, """{"diff":"from the result"}"""))
    }

    @Test
    fun `a result-only object patch is resolved when the side channel is silent`() {
        // `patch_tool` returns `{"success":true,"diff":"…"}`
        // (`tools/file_operations.py:1355-1357` @ the same SHA): no
        // `inline_diff` anywhere, and the diff is still there to render.
        val result = """{"success":true,"diff":"@@ -1 +1 @@\n-old\n+new"}"""

        assertEquals("@@ -1 +1 @@\n-old\n+new", resolveEffectiveInlineDiff(null, result))
    }

    @Test
    fun `a result that is itself a JSON string is decoded the same way`() {
        // `toolResultRecord` accepts a string-typed result as well as an object
        // (`lib/tool-result-metadata.ts:23-33` @ the same SHA), and a Gateway
        // that stringifies its result is the shape that case exists for.
        val result = """{"success":true,"diff":"@@ -1 +1 @@\n-a\n+b"}"""

        assertEquals("@@ -1 +1 @@\n-a\n+b", resolveEffectiveInlineDiff(null, result))
    }

    @Test
    fun `inline diff in the result is tried before diff`() {
        // `index.ts:827` reads `inline_diff` then `diff`, so a result carrying
        // both renders what the sender considered canonical.
        val result = """{"inline_diff":"@@ -1 +1 @@\n+canonical","diff":"@@ -1 +1 @@\n+fallback"}"""

        assertEquals("@@ -1 +1 @@\n+canonical", resolveEffectiveInlineDiff(null, result))
    }

    // ── What is "no diff" ────────────────────────────────────────────────────

    @Test
    fun `a blank side channel falls through to the result`() {
        // `stripInlineDiffChrome("")` is `""`, and `Boolean("")` is false
        // upstream — so an empty live payload is not a diff.
        assertEquals("@@ -1 +1 @@\n+x", resolveEffectiveInlineDiff("", """{"diff":"@@ -1 +1 @@\n+x"}"""))
    }

    @Test
    fun `a side channel that is only chrome is not a diff`() {
        // The TTY banner alone strips to nothing upstream too, so no panel opens
        // on it.
        assertNull(resolveEffectiveInlineDiff("  ┊ review diff\n", null))
    }

    @Test
    fun `a malformed result is not a diff rather than a crash`() {
        assertNull(resolveEffectiveInlineDiff(null, "{not json"))
        assertNull(resolveEffectiveInlineDiff(null, "a plain string result"))
        assertNull(resolveEffectiveInlineDiff(null, "[1,2,3]"))
    }

    @Test
    fun `empty and non-string diff fields are no diff`() {
        // A `"diff": {}`, a `"diff": ""` and a `"diff": 3` are each "no diff",
        // never an empty panel.
        assertNull(resolveEffectiveInlineDiff(null, """{"diff":{}}"""))
        assertNull(resolveEffectiveInlineDiff(null, """{"diff":""}"""))
        assertNull(resolveEffectiveInlineDiff(null, """{"diff":3}"""))
        assertNull(resolveEffectiveInlineDiff(null, """{"diff":null}"""))
        assertNull(resolveEffectiveInlineDiff(null, """{"success":true}"""))
    }

    @Test
    fun `nothing to read from either field yields nothing`() {
        assertNull(resolveEffectiveInlineDiff(null, null))
        assertNull(resolveEffectiveInlineDiff("", ""))
    }

    // ── One value, chrome-stripped ───────────────────────────────────────────

    @Test
    fun `the returned value is already chrome stripped, whichever field it came from`() {
        // Every consumer — panel, stats, language and Copy — reads this one
        // string, so the strip has to have happened on the way out rather than
        // once per reader.
        val esc = "\u001B"
        val live = "$esc[33m  ┊ review diff$esc[0m\n@@ -1 +1 @@\n-old"
        val fromResult = """{"diff":"$esc[38;2;1;2;3m@@ -1 +1 @@$esc[0m\n+new"}"""

        assertEquals("@@ -1 +1 @@\n-old", resolveEffectiveInlineDiff(live, null))
        assertEquals("@@ -1 +1 @@\n+new", resolveEffectiveInlineDiff(null, fromResult))
    }

    @Test
    fun `every diff key a result can carry resolves, and its order is desktop's`() {
        // The two keys `index.ts:827` names, in an order a payload carrying both
        // can tell apart.
        assertEquals("inline", resolveEffectiveInlineDiff(null, """{"inline_diff":"inline","diff":"d"}"""))
        assertEquals("d", resolveEffectiveInlineDiff(null, """{"diff":"d"}"""))
    }
}
