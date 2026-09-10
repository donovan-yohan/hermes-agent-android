package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.em
import com.hermesagent.mobile.data.composer.ComposerReferenceSpan
import com.hermesagent.mobile.data.composer.hiddenUrlLabelRanges
import com.hermesagent.mobile.data.composer.composerReferenceSpans
import kotlin.math.min

internal const val REFERENCE_SPACER = '\u2005'
internal const val REFERENCE_HIDDEN = '\u200B'

internal fun paintComposerReferences(
    text: String,
    referenceInk: Color,
    pathInk: Color,
    glyphFamily: FontFamily
): AnnotatedString {
    val spans = composerReferenceSpans(text)
    if (spans.isEmpty()) return AnnotatedString(text)

    val builder = AnnotatedString.Builder(text.length)
    var current = 0

    for (span in spans) {
        if (current < span.start) {
            builder.append(text.substring(current, span.start))
        }

        val glyph = when (span.kind) {
            "url" -> '\uEB15'
            "file" -> '\uEA7B'
            "folder" -> '\uEA83'
            else -> null
        }

        val ink = when (span.kind) {
            "url", "session" -> referenceInk
            else -> pathInk
        }

        builder.pushStyle(SpanStyle(color = ink))

        if (glyph != null) {
            builder.pushStyle(SpanStyle(
                fontFamily = glyphFamily,
                fontSize = 0.875.em,
                baselineShift = BaselineShift(-0.1f),
                color = ink.copy(alpha = ink.alpha * 0.8f)
            ))
            builder.append(glyph.toString())
            builder.pop()
            builder.append(REFERENCE_SPACER.toString())

            for (i in span.start + 2 until span.valueStart) {
                builder.append(REFERENCE_HIDDEN.toString())
            }

            val value = span.value
            val hiddenRanges = when (span.kind) {
                "url" -> hiddenUrlLabelRanges(value)
                "file", "folder" -> if (value.startsWith("./")) listOf(0 until 2) else emptyList()
                else -> emptyList()
            }

            for (i in value.indices) {
                if (hiddenRanges.any { it.contains(i) }) {
                    builder.append(REFERENCE_HIDDEN.toString())
                } else {
                    builder.append(value[i].toString())
                }
            }

            for (i in span.valueEnd until span.end) {
                builder.append(REFERENCE_HIDDEN.toString())
            }
        } else {
            builder.append(text.substring(span.start, span.end))
        }

        builder.pop() // ink style
        current = span.end
    }

    if (current < text.length) {
        builder.append(text.substring(current))
    }

    return builder.toAnnotatedString()
}

internal data class ReferenceChipTransformation(
    val referenceInk: Color,
    val pathInk: Color,
    val glyphFamily: FontFamily
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            paintComposerReferences(text.text, referenceInk, pathInk, glyphFamily),
            OffsetMapping.Identity
        )
    }
}

internal fun accessibleComposerText(painted: String): String {
    val builder = java.lang.StringBuilder(painted.length)
    for (i in painted.indices) {
        val c = painted[i]
        if (c == '\uEB15' || c == '\uEA7B' || c == '\uEA83') {
            builder.append(' ')
        } else {
            builder.append(c)
        }
    }
    return builder.toString()
}

internal fun snapSelectionToReferenceEdges(
    previous: TextFieldValue,
    proposed: TextFieldValue,
    spans: List<ComposerReferenceSpan>
): TextFieldValue {
    if (previous.text != proposed.text) return proposed

    val pSelection = previous.selection
    val nSelection = proposed.selection

    if (nSelection.collapsed) {
        val p = nSelection.start
        for (span in spans) {
            if (p > span.start && p < span.end) {
                val newStart = if (p > pSelection.start) {
                    span.end
                } else if (p < pSelection.start) {
                    span.start
                } else {
                    val distToStart = p - span.start
                    val distToEnd = span.end - p
                    if (distToEnd <= distToStart) span.end else span.start
                }
                return proposed.copy(selection = TextRange(newStart))
            }
        }
    } else {
        var newStart = nSelection.start
        var newEnd = nSelection.end
        var reversed = nSelection.reversed

        val actualStart = nSelection.min
        val actualEnd = nSelection.max

        var adjStart = actualStart
        for (span in spans) {
            if (actualStart > span.start && actualStart < span.end) {
                adjStart = span.start
                break
            }
        }

        var adjEnd = actualEnd
        for (span in spans) {
            if (actualEnd > span.start && actualEnd < span.end) {
                adjEnd = span.end
                break
            }
        }

        if (adjStart != actualStart || adjEnd != actualEnd) {
            val range = if (reversed) TextRange(adjEnd, adjStart) else TextRange(adjStart, adjEnd)
            return proposed.copy(selection = range)
        }
    }
    return proposed
}

internal fun atomizeReferenceDeletion(
    previous: TextFieldValue,
    proposed: TextFieldValue,
    spans: List<ComposerReferenceSpan>
): TextFieldValue? {
    val prevText = previous.text
    val propText = proposed.text
    var p = 0
    val minLen = min(prevText.length, propText.length)
    while (p < minLen && prevText[p] == propText[p]) p++

    var s = 0
    while (s < minLen - p && prevText[prevText.length - 1 - s] == propText[propText.length - 1 - s]) s++

    val removed = p until (prevText.length - s)
    val inserted = propText.substring(p, propText.length - s)

    if (inserted.isNotEmpty()) return null
    if (removed.isEmpty()) return null

    // Attempt to align the removed range with the previous cursor if possible
    val pSelection = previous.selection
    var bestP = p
    var bestS = s
    var bestRemoved = removed

    if (pSelection.collapsed) {
        val len = removed.last - removed.first + 1

        // Find alternative placements
        val alternatives = mutableListOf<Int>()
        alternatives.add(p)

        // Try shifting left
        var checkP = p - 1
        var checkS = s + 1
        while (checkP >= 0 && prevText[checkP] == prevText[checkP + len]) {
            alternatives.add(checkP)
            checkP--
            checkS++
        }

        // Try shifting right
        checkP = p + 1
        checkS = s - 1
        while (checkS >= 0 && prevText[prevText.length - 1 - checkS] == prevText[prevText.length - 1 - checkS - len]) {
            alternatives.add(checkP)
            checkP++
            checkS--
        }

        // Prefer ending at previous cursor
        val endingAtCursor = alternatives.find { it + len == pSelection.start }
        if (endingAtCursor != null) {
            bestP = endingAtCursor
            bestS = prevText.length - len - bestP
            bestRemoved = bestP until (bestP + len)
        } else {
            // Then prefer starting at previous cursor
            val startingAtCursor = alternatives.find { it == pSelection.start }
            if (startingAtCursor != null) {
                bestP = startingAtCursor
                bestS = prevText.length - len - bestP
                bestRemoved = bestP until (bestP + len)
            }
        }
    }

    val overlappedSpans = spans.filter { it.start < bestRemoved.last + 1 && it.end > bestRemoved.first }
    if (overlappedSpans.isEmpty()) return null

    var widenedStart = bestRemoved.first
    var widenedEnd = bestRemoved.last + 1

    for (span in overlappedSpans) {
        widenedStart = min(widenedStart, span.start)
        widenedEnd = Math.max(widenedEnd, span.end)
    }

    if (pSelection.collapsed && bestRemoved.first == widenedEnd - 1 && bestRemoved.last == widenedEnd - 1) {
        if (prevText.getOrNull(widenedEnd) == ' ') {
            widenedEnd++
        }
    }

    val newText = prevText.substring(0, widenedStart) + prevText.substring(widenedEnd)
    return TextFieldValue(newText, TextRange(widenedStart), null)
}

internal fun canonicalizePastedComposerText(
    previous: String,
    proposed: String,
    proposedCursor: Int
): TextFieldValue? {
    var p = 0
    val minLen = min(previous.length, proposed.length)
    while (p < minLen && previous[p] == proposed[p]) p++

    var s = 0
    while (s < minLen - p && previous[previous.length - 1 - s] == proposed[proposed.length - 1 - s]) s++

    val removed = p until (previous.length - s)
    val inserted = proposed.substring(p, proposed.length - s)

    if (!removed.isEmpty()) return null
    if (inserted.length < 2 || !inserted.contains("://")) return null

    val canonical = canonicalizeComposerUrls(inserted)
    if (canonical == inserted) return null

    val spans = composerReferenceSpans(previous)
    val prefix = previous.substring(0, p)

    if (prefix.endsWith("@url:")) {
        val starterStart = prefix.length - 5
        val insideSpan = spans.any { it.start <= starterStart && it.end > starterStart }
        if (!insideSpan) {
            val newPrefix = prefix.substring(0, starterStart)
            val newText = newPrefix + canonical + previous.substring(previous.length - s)
            return TextFieldValue(newText, TextRange(starterStart + canonical.length))
        }
    }

    val newText = prefix + canonical + previous.substring(previous.length - s)
    return TextFieldValue(newText, TextRange(p + canonical.length))
}

internal fun canonicalizeOnSpaceKeepingCaret(value: TextFieldValue): TextFieldValue {
    val canonical = canonicalizeComposerTextOnSpace(value.text)
    if (canonical == value.text) return value

    val first = value.text.commonPrefixWith(canonical).length
    val delta = canonical.length - value.text.length
    val caret = value.selection.start

    val newCaret = if (caret > first) caret + delta else caret
    return TextFieldValue(canonical, TextRange(newCaret.coerceIn(0, canonical.length)), value.composition)
}

internal fun padComposerReferenceInsert(text: String, start: Int, end: Int, insert: String): Pair<String, Int> {
    var paddedInsert = insert
    var insertedBefore = false
    if (start > 0 && !text[start - 1].isWhitespace()) {
        paddedInsert = " " + paddedInsert
        insertedBefore = true
    }

    val after = text.substring(end)
    if (after.isEmpty() || !after[0].isWhitespace()) {
        paddedInsert = paddedInsert + " "
    }

    val newText = text.substring(0, start) + paddedInsert + text.substring(end)
    val newCursor = start + paddedInsert.length
    return Pair(newText, newCursor)
}
