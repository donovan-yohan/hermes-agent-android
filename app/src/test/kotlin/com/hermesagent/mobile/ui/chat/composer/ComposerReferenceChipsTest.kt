package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em
import com.hermesagent.mobile.data.composer.composerReferenceSpans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ComposerReferenceChipsTest {

    @Test
    fun testPaintComposerReferences() {
        val text = "see @url:`https://example.dev/a` now"
        val referenceInk = Color.Red
        val pathInk = Color.Blue
        val family = FontFamily.Default
        val painted = paintComposerReferences(text, referenceInk, pathInk, family)

        val expectedText = "see " + "\uEB15" + "\u2005" + "\u200B".repeat(4) + "\u200B".repeat(8) + "example.dev/a" + "\u200B" + " now"
        assertEquals(36, text.length)
        assertEquals(36, expectedText.length)
        assertEquals(expectedText, painted.text)

        val styles = painted.spanStyles
        assertEquals(2, styles.size)

        val inkStyle = styles.find { it.item.color == referenceInk }
        val glyphStyle = styles.find { it.item.fontSize == 0.875.em }

        assertEquals(4, inkStyle!!.start)
        assertEquals(32, inkStyle.end)

        assertEquals(4, glyphStyle!!.start)
        assertEquals(5, glyphStyle.end)
        assertEquals(BaselineShift(-0.1f), glyphStyle.item.baselineShift)
        assertEquals(referenceInk.copy(alpha = referenceInk.alpha * 0.8f), glyphStyle.item.color)

        val transformation = ReferenceChipTransformation(referenceInk, pathInk, family)
        val transformed = transformation.filter(androidx.compose.ui.text.AnnotatedString(text))
        assertSame(OffsetMapping.Identity, transformed.offsetMapping)
    }

    @Test
    fun testPropertySweep() {
        val fragments = listOf(
            "a", " ", "\n", "`", "\"", "'", "@", ":", "/", "\uD83D\uDE00",
            "https://x.y/z", "@url:`https://example.dev/a`", "@file:`src/a.kt`", "@folder:`apps/mobile/`"
        )
        val random = Random(42)
        for (i in 0 until 200) {
            val builder = StringBuilder()
            repeat(random.nextInt(1, 10)) {
                builder.append(fragments.random(random))
            }
            val text = builder.toString()
            val painted = paintComposerReferences(text, Color.Red, Color.Blue, FontFamily.Default)
            assertEquals(text.length, painted.length)

            val spans = composerReferenceSpans(text)
            for (j in text.indices) {
                if (spans.none { it.start <= j && j < it.end }) {
                    assertEquals(text[j], painted[j])
                }
                if (text[j].isHighSurrogate()) {
                    val lowIdx = j + 1
                    if (lowIdx < text.length && text[lowIdx].isLowSurrogate()) {
                        val highMasked = painted[j] == '\u200B'
                        val lowMasked = painted[lowIdx] == '\u200B'
                        assertEquals(highMasked, lowMasked)
                    }
                }
            }
        }
    }

    @Test
    fun testAccessibleComposerText() {
        val painted = "see " + "\uEB15" + "\u2005" + "x"
        val accessible = accessibleComposerText(painted)
        assertEquals("see  \u2005x", accessible)
        assertEquals(painted.length, accessible.length)
    }

    @Test
    fun testSnapSelection() {
        val text = "see @url:`https://example.dev/a` now" // span is [4, 32)
        val spans = composerReferenceSpans(text)
        val v = TextFieldValue(text)

        // jumping from 0 to 20 lands at 32
        var prev = v.copy(selection = TextRange(0))
        var prop = v.copy(selection = TextRange(20))
        var snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(32), snapped.selection)

        // jumping from 33 to 20 lands at 4
        prev = v.copy(selection = TextRange(33))
        prop = v.copy(selection = TextRange(20))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(4), snapped.selection)

        // from 20 to 20 lands at 32
        prev = v.copy(selection = TextRange(20))
        prop = v.copy(selection = TextRange(20))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(32), snapped.selection)

        // from 10 to 10 lands at 4
        prev = v.copy(selection = TextRange(10))
        prop = v.copy(selection = TextRange(10))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(4), snapped.selection)

        // from 18 to 18 lands at 32 (equidistant 14, tie to end)
        prev = v.copy(selection = TextRange(18))
        prop = v.copy(selection = TextRange(18))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(32), snapped.selection)

        // composition on proposed is preserved
        prev = v.copy(selection = TextRange(0))
        prop = TextFieldValue(text, TextRange(20), TextRange(0, 3))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(32), snapped.selection)
        assertEquals(TextRange(0, 3), snapped.composition)

        // unchanged proposal is returned reference-equal
        prev = v.copy(selection = TextRange(0))
        prop = v.copy(selection = TextRange(0))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertSame(prop, snapped)

        // existing non-collapsed tests
        prev = v.copy(selection = TextRange(0))
        prop = v.copy(selection = TextRange(6, 12))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(4, 32), snapped.selection)

        prop = TextFieldValue(text, TextRange(6, 12), TextRange(0, 3))
        snapped = snapSelectionToReferenceEdges(prev, prop, spans)
        assertEquals(TextRange(4, 32), snapped.selection)
        assertEquals(TextRange(0, 3), snapped.composition)
    }

    @Test
    fun testAtomizeReferenceDeletion() {
        var text = "see @url:`https://example.dev/a` next"
        var spans = composerReferenceSpans(text)

        var prev = TextFieldValue(text, TextRange(32))
        var prop = TextFieldValue(text.substring(0, 31) + text.substring(32), TextRange(31))
        var atomized = atomizeReferenceDeletion(prev, prop, spans)
        assertEquals("see next", atomized!!.text)
        assertEquals(TextRange(4), atomized.selection)
        assertNull(atomized.composition)

        text = "see @url:`https://example.dev/a`"
        spans = composerReferenceSpans(text)
        prev = TextFieldValue(text, TextRange(32))
        prop = TextFieldValue(text.substring(0, 31), TextRange(31))
        atomized = atomizeReferenceDeletion(prev, prop, spans)
        assertEquals("see ", atomized!!.text)
        assertEquals(TextRange(4), atomized.selection)

        text = "see @url:`https://example.dev/a` next"
        spans = composerReferenceSpans(text)
        prev = TextFieldValue(text, TextRange(4))
        prop = TextFieldValue(text.substring(0, 4) + text.substring(5), TextRange(4))
        atomized = atomizeReferenceDeletion(prev, prop, spans)
        assertEquals("see  next", atomized!!.text)
        assertEquals(TextRange(4), atomized.selection)

        prev = TextFieldValue(text, TextRange(10, 15))
        prop = TextFieldValue(text.substring(0, 10) + text.substring(15), TextRange(10))
        atomized = atomizeReferenceDeletion(prev, prop, spans)
        assertEquals("see  next", atomized!!.text)

        prev = TextFieldValue(text, TextRange(31, 32))
        prop = TextFieldValue(text.substring(0, 31) + text.substring(32), TextRange(31))
        atomized = atomizeReferenceDeletion(prev, prop, spans)
        assertEquals("see  next", atomized!!.text)

        prev = TextFieldValue(text, TextRange(2))
        prop = TextFieldValue(text.substring(0, 1) + text.substring(2), TextRange(1))
        assertNull(atomizeReferenceDeletion(prev, prop, spans))

        prop = TextFieldValue(text.substring(0, 2) + "x" + text.substring(2))
        assertNull(atomizeReferenceDeletion(prev, prop, spans))

        prop = TextFieldValue(text.substring(0, 1) + "x" + text.substring(2))
        assertNull(atomizeReferenceDeletion(prev, prop, spans))

        val aaText = "aa @url:`a`"
        val aaSpans = composerReferenceSpans(aaText)
        val aaPrev = TextFieldValue(aaText, TextRange(1))
        val aaProp = TextFieldValue("a @url:`a`", TextRange(0))
        val aaAtomized = atomizeReferenceDeletion(aaPrev, aaProp, aaSpans)
        assertNull(aaAtomized)

        val doubleText = "a @url:`x` @url:`y`"
        val doubleSpans = composerReferenceSpans(doubleText)
        val doublePrev = TextFieldValue(doubleText, TextRange(10))
        val doubleProp = TextFieldValue(doubleText.substring(0, 9) + doubleText.substring(10))
        val doubleAtomized = atomizeReferenceDeletion(doublePrev, doubleProp, doubleSpans)
        assertEquals("a @url:`y`", doubleAtomized!!.text)

        val recomposePrev = TextFieldValue(text, TextRange(32))
        val recomposeProp = TextFieldValue(text.substring(0, 31) + text.substring(32), TextRange(31), TextRange(4, 31))
        val recomposeAtomized = atomizeReferenceDeletion(recomposePrev, recomposeProp, spans)
        assertEquals("see next", recomposeAtomized!!.text)
        assertNull(recomposeAtomized.composition)

        val recomposePrev2 = TextFieldValue(text, TextRange(32), TextRange(4, 32))
        val recomposeAtomized2 = atomizeReferenceDeletion(recomposePrev2, recomposeProp, spans)
        assertEquals("see next", recomposeAtomized2!!.text)
        assertNull(recomposeAtomized2.composition)
    }

    @Test
    fun testCanonicalizePastedComposerText() {
        var prev = "see  now"
        var prop = "see https://example.dev/a now"
        var canonicalized = canonicalizePastedComposerText(prev, prop, 4)
        assertEquals("see @url:`https://example.dev/a` now", canonicalized!!.text)
        assertEquals(32, canonicalized.selection.start)

        prev = "read  then"
        prop = "read https://example.dev/a. then"
        canonicalized = canonicalizePastedComposerText(prev, prop, 5)
        assertEquals("read @url:`https://example.dev/a`. then", canonicalized!!.text)

        assertNull(canonicalizePastedComposerText("a", "ab", 1))

        assertNull(canonicalizePastedComposerText("a", "a b", 1))

        assertNull(canonicalizePastedComposerText("a", "a @url:`https://example.dev/a`", 1))

        assertNull(canonicalizePastedComposerText("a", "a http://", 1))

        prev = "see @url:"
        prop = "see @url:https://example.dev/a"
        canonicalized = canonicalizePastedComposerText(prev, prop, 9)
        assertEquals("see @url:`https://example.dev/a`", canonicalized!!.text)
    }

    @Test
    fun testCanonicalizeOnSpaceKeepingCaret() {
        val v1 = TextFieldValue("see https://example.dev/a ", TextRange(26))
        val res1 = canonicalizeOnSpaceKeepingCaret(v1)
        assertEquals("see @url:`https://example.dev/a` ", res1.text)
        assertEquals(33, res1.selection.start)

        val v2 = TextFieldValue("xsee https://example.dev/a ", TextRange(1))
        val res2 = canonicalizeOnSpaceKeepingCaret(v2)
        assertEquals("xsee @url:`https://example.dev/a` ", res2.text)
        assertEquals(1, res2.selection.start)
    }

    @Test
    fun testPadComposerReferenceInsert() {
        val res1 = padComposerReferenceInsert("review", 6, 6, "@file:`src/a.ts`")
        assertEquals("review @file:`src/a.ts` ", res1.first)
        assertEquals(24, res1.second)

        val res2 = padComposerReferenceInsert("review ", 7, 7, "@file:`src/a.ts`")
        assertEquals("review @file:`src/a.ts` ", res2.first)

        val res3 = padComposerReferenceInsert("review", 6, 6, "@file:`src/a.ts`")
        assertEquals("review @file:`src/a.ts` ", res3.first)

        val res4 = padComposerReferenceInsert("a b", 1, 1, "@file:`x`")
        assertEquals("a @file:`x` b", res4.first)
        assertEquals(11, res4.second)

        val res5 = padComposerReferenceInsert("a b", 2, 2, "@file:`x`")
        assertEquals("a @file:`x` b", res5.first)
        assertEquals(12, res5.second)
    }
}
