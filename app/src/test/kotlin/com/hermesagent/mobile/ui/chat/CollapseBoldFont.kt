package com.hermesagent.mobile.ui.chat

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The shipped wordmark face, read straight off disk.
 *
 * `res/font/collapse_bold.otf` is an artifact this repo carries rather than
 * generates at build time, so the numbers the wordmark's fit is argued from —
 * how many ems `HERMES` spans, which letters the face even maps — are
 * properties of a file, and a file can be inspected. Parsed by hand rather than
 * pulled from a dependency, for the same reason `HermesIconFontTest` parses the
 * codicon cmap: the font is the thing under test, and a parser is cheaper than
 * adding a font toolkit to the build.
 *
 * Read on the JVM, which is why this is not a Robolectric fixture. That the
 * *platform* can load it is `WordmarkFitDeviceTest`'s claim.
 */
internal object CollapseBoldFont {

    const val PATH = "src/main/res/font/collapse_bold.otf"

    /** The sfnt tag of a CFF-outline font. Collapse ships CFF, not glyf. */
    const val CFF_SFNT = "OTTO"

    val bytes: ByteArray by lazy { file().readBytes() }

    private val font: ByteBuffer by lazy { ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN) }

    val sha256: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    val sfntVersion: String by lazy { String(bytes, 0, 4, Charsets.US_ASCII) }

    val unitsPerEm: Int by lazy { u16(table("head") + 18) }

    /** `OS/2.usWeightClass`. Only Bold is shipped, so this must be 700. */
    val weightClass: Int by lazy { u16(table("OS/2") + 4) }

    /** `name` ID 1, the family. */
    val family: String by lazy { name(1) }

    /** `name` ID 2, the subfamily. */
    val subfamily: String by lazy { name(2) }

    /** Every character the Windows BMP cmap maps to a real glyph. */
    val mapped: Set<Char> by lazy { cmap().keys.mapNotNull { it.toChar() }.toSet() }

    /**
     * How many ems [text] advances in this face, glyph advances only — no
     * tracking, because tracking is the type scale's and not the font's.
     *
     * A character the face does not map is an error rather than a zero: a
     * silent zero would understate the run and the fit would overflow.
     */
    fun emRun(text: String): Float {
        val cmap = cmap()
        val hhea = table("hhea")
        val hmtx = table("hmtx")
        val metrics = u16(hhea + 34)
        var advance = 0
        for (character in text) {
            val glyph = cmap[character.code]
                ?: throw AssertionError("$PATH does not map '$character' (U+%04X)".format(character.code))
            val index = if (glyph < metrics) glyph else metrics - 1
            advance += u16(hmtx + index * 4)
        }
        return advance.toFloat() / unitsPerEm
    }

    private fun name(id: Int): String {
        val name = table("name")
        val count = u16(name + 2)
        val storage = name + u16(name + 4)
        for (record in 0 until count) {
            val at = name + 6 + record * 12
            if (u16(at + 6) != id) continue
            val platform = u16(at)
            val length = u16(at + 8)
            val offset = storage + u16(at + 10)
            val raw = bytes.copyOfRange(offset, offset + length)
            return when (platform) {
                3 -> String(raw, Charsets.UTF_16BE)
                else -> String(raw, Charsets.US_ASCII)
            }
        }
        throw AssertionError("$PATH has no name record $id")
    }

    /** The Windows BMP (3, 1) format 4 subtable — the one Android's text stack reads. */
    private fun cmap(): Map<Int, Int> {
        val cmap = table("cmap")
        var subtable = -1
        for (record in 0 until u16(cmap + 2)) {
            val at = cmap + 4 + record * 8
            val offset = cmap + font.getInt(at + 4)
            if (u16(at) == 3 && u16(at + 2) == 1 && u16(offset) == 4) subtable = offset
        }
        if (subtable < 0) throw AssertionError("$PATH has no Windows BMP format 4 cmap")

        val segCountX2 = u16(subtable + 6)
        val ends = subtable + 14
        val starts = ends + segCountX2 + 2
        val deltas = starts + segCountX2
        val ranges = deltas + segCountX2

        val mapped = mutableMapOf<Int, Int>()
        for (segment in 0 until segCountX2 / 2) {
            val start = u16(starts + segment * 2)
            if (start == LAST_SEGMENT) continue
            val end = u16(ends + segment * 2)
            val delta = font.getShort(deltas + segment * 2).toInt()
            val rangeOffset = u16(ranges + segment * 2)
            for (codePoint in start..end) {
                val glyph = if (rangeOffset == 0) {
                    (codePoint + delta) and 0xFFFF
                } else {
                    val index = ranges + segment * 2 + rangeOffset + (codePoint - start) * 2
                    val raw = u16(index)
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                if (glyph != 0) mapped[codePoint] = glyph
            }
        }
        return mapped
    }

    private fun table(tag: String): Int {
        for (record in 0 until u16(4)) {
            val at = 12 + record * 16
            if (String(bytes, at, 4, Charsets.US_ASCII) == tag) return font.getInt(at + 8)
        }
        throw AssertionError("$PATH has no $tag table")
    }

    private fun u16(at: Int): Int = font.getShort(at).toInt() and 0xFFFF

    /** Unit tests run from the module directory; walk up if that ever changes. */
    private fun file(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            listOf(File(directory, PATH), File(directory, "app/$PATH"))
                .firstOrNull { it.isFile }
                ?.let { return it }
            directory = directory.parentFile
        }
        throw AssertionError("could not find $PATH from ${File("").absolutePath}")
    }

    /** Format 4's mandatory terminating segment. */
    private const val LAST_SEGMENT = 0xFFFF
}
