package com.hermesagent.mobile.ui.common

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every [HermesIcon] must resolve in the Codicons 0.0.45 font this app ships.
 *
 * A private-use code point that the font does not map renders as a blank box
 * on device and as nothing at all in a screenshot, so a wrong glyph number is
 * invisible until someone looks at a phone. This reads the shipped
 * `codicon.ttf` cmap directly and turns that inspection into a gate.
 */
class HermesIconFontTest {

    @Test
    fun `every glyph is one code point the shipped codicon font maps`() {
        val mapped = shippedCodiconCodePoints()

        // A sanity floor: a truncated or swapped font would otherwise pass by
        // mapping nothing this app happens to use.
        assertTrue("codicon.ttf mapped only ${mapped.size} code points", mapped.size > 400)

        HermesIcon.entries.forEach { icon ->
            assertEquals("${icon.name} is not a single code point", 1, icon.glyph.length)
            val codePoint = icon.glyph.single().code
            assertTrue(
                "${icon.name} (U+%04X) is not in the shipped codicon font".format(codePoint),
                codePoint in mapped,
            )
        }
    }

    /**
     * The negative control the check above needs to mean anything.
     *
     * Every assertion up there is `codePoint in mapped`, so a reader that
     * over-reported — one that spanned the segment list instead of walking it,
     * or ran a segment one code point long — would pass the whole suite while
     * proving nothing. These three are absent from Codicons 0.0.45 by
     * construction, and each catches a different way of being wrong:
     *
     * - `A` is not in the private use area at all.
     * - `U+EA5F` is the code point directly below `add` (`U+EA60`), the font's
     *   lowest glyph — the low edge of the whole cmap.
     * - `U+EB0A` is a hole *between* two mapped segments (`…EB09`, `EB0B…`), so
     *   a reader that treats the cmap as one span rather than ten is caught
     *   even though the point is well inside the font's range.
     */
    @Test
    fun `the cmap reader reports a code point the font does not map as absent`() {
        val mapped = shippedCodiconCodePoints()

        listOf(0x41, UNMAPPED_BELOW_FIRST_GLYPH, UNMAPPED_BETWEEN_SEGMENTS).forEach { codePoint ->
            assertFalse(
                "U+%04X is not in Codicons 0.0.45, so the cmap reader is over-reporting"
                    .format(codePoint),
                codePoint in mapped,
            )
        }

        // …and the reader is not simply under-reporting either: each hole is
        // one code point wide, and both neighbours are real glyphs.
        listOf(
            UNMAPPED_BELOW_FIRST_GLYPH + 1,
            UNMAPPED_BETWEEN_SEGMENTS - 1,
            UNMAPPED_BETWEEN_SEGMENTS + 1,
        ).forEach { codePoint ->
            assertTrue(
                "U+%04X is a real glyph, so the cmap reader is under-reporting".format(codePoint),
                codePoint in mapped,
            )
        }
    }

    /**
     * The Windows BMP (platform 3, encoding 1) format 4 cmap subtable, which
     * is the one Android's text stack uses for these code points. Parsed
     * rather than pulled from a dependency: the font is the artifact under
     * test, and a parser is cheaper than adding a font toolkit to the build.
     */
    private fun shippedCodiconCodePoints(): Set<Int> {
        val bytes = codiconFile().readBytes()
        val font = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        val tableCount = font.getShort(4).toInt() and 0xFFFF
        var cmap = -1
        for (i in 0 until tableCount) {
            val record = 12 + i * 16
            if (String(bytes, record, 4, Charsets.US_ASCII) == "cmap") cmap = font.getInt(record + 8)
        }
        assertTrue("codicon.ttf has no cmap table", cmap >= 0)

        var subtable = -1
        val subtableCount = font.getShort(cmap + 2).toInt() and 0xFFFF
        for (i in 0 until subtableCount) {
            val record = cmap + 4 + i * 8
            val platform = font.getShort(record).toInt() and 0xFFFF
            val encoding = font.getShort(record + 2).toInt() and 0xFFFF
            val offset = cmap + font.getInt(record + 4)
            val format = font.getShort(offset).toInt() and 0xFFFF
            if (platform == 3 && encoding == 1 && format == 4) subtable = offset
        }
        assertTrue("codicon.ttf has no Windows BMP format 4 cmap", subtable >= 0)

        val segCountX2 = font.getShort(subtable + 6).toInt() and 0xFFFF
        val ends = subtable + 14
        val starts = ends + segCountX2 + 2
        val deltas = starts + segCountX2
        val ranges = deltas + segCountX2

        val mapped = mutableSetOf<Int>()
        for (segment in 0 until segCountX2 / 2) {
            val start = font.getShort(starts + segment * 2).toInt() and 0xFFFF
            if (start == LAST_SEGMENT) continue
            val end = font.getShort(ends + segment * 2).toInt() and 0xFFFF
            val delta = font.getShort(deltas + segment * 2).toInt()
            val rangeOffset = font.getShort(ranges + segment * 2).toInt() and 0xFFFF

            for (codePoint in start..end) {
                val glyph = if (rangeOffset == 0) {
                    (codePoint + delta) and 0xFFFF
                } else {
                    val index = ranges + segment * 2 + rangeOffset + (codePoint - start) * 2
                    val raw = font.getShort(index).toInt() and 0xFFFF
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                if (glyph != 0) mapped += codePoint
            }
        }
        return mapped
    }

    /** Unit tests run from the module directory; walk up if that ever changes. */
    private fun codiconFile(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidates = listOf(File(directory, FONT_PATH), File(directory, "app/$FONT_PATH"))
            candidates.firstOrNull { it.isFile }?.let { return it }
            directory = directory.parentFile
        }
        throw AssertionError("could not find $FONT_PATH from ${File("").absolutePath}")
    }

    private companion object {
        const val FONT_PATH = "src/main/res/font/codicon.ttf"

        /** Format 4's mandatory terminating segment. */
        const val LAST_SEGMENT = 0xFFFF

        /** The gap directly below `add` (`U+EA60`), this font's lowest glyph. */
        const val UNMAPPED_BELOW_FIRST_GLYPH = 0xEA5F

        /** The one-point hole between the `U+EB0B` and `…EB09` segments. */
        const val UNMAPPED_BETWEEN_SEGMENTS = 0xEB0A
    }
}
