package com.hermesagent.mobile.data.voice

/**
 * Speech-text sanitizer, a literal port of Hermes Desktop's
 * `apps/desktop/src/lib/speech-text.ts:1-167`
 * @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`.
 *
 * A reply is written to be read on screen; synthesis reads it aloud. Markdown
 * chrome, fenced code, tables, link targets, emoji and bare URLs are noise once
 * spoken, so they are removed or summarised before the text reaches a voice.
 * The rules live here as pure functions with no logging and no state: the same
 * fixtures that guard Desktop guard this file, so the two clients cannot drift.
 *
 * Nothing here logs. A reply is transcript material and must never be written
 * to logcat, disk, or analytics.
 */

/** What a fenced code block is read as. User-audible copy — keep verbatim. */
internal const val CODE_BLOCK_SUMMARY = " code block omitted "

/** What a bare URL is read as. User-audible copy — keep verbatim. */
internal const val URL_SUMMARY = " link "

private val EMOJI_RE =
    Regex("""(?:[\x{1F000}-\x{1FAFF}\x{2600}-\x{27BF}]|[\x{FE0F}\x{200D}]|[\x{E0020}-\x{E007F}])+""")

// JavaScript's `$` outside multiline mode is end-of-input, which is Java's `\z`
// (Java's own `$` would also match before a trailing newline).
private val FENCED_CODE_RE = Regex("""```[\s\S]*?(?:```|\z)""")
private val INLINE_CODE_RE = Regex("""`([^`]+)`""")
private val MARKDOWN_LINK_RE = Regex("""\[([^\]]+)\]\(([^)]+)\)""")
private val PARAGRAPH_BREAK_RE = Regex("""[ \t]*\n{2,}[ \t]*""")
private val PUNCTUATED_PARAGRAPH_BREAK_RE = Regex("""([.!?])([*_~`>"'’”)}\]]*)[ \t]*\n{2,}[ \t]*""")
private val SOFT_BREAK_RE = Regex("""[ \t]*\n[ \t]*""")

// Verbatim from Desktop's THINKING_PREFIX_RE alternation, kept as a list so the
// two word sets diff line by line.
private val THINKING_VERBS = listOf(
    "processing",
    "thinking",
    "reasoning",
    "analyzing",
    "pondering",
    "contemplating",
    "musing",
    "cogitating",
    "ruminating",
    "deliberating",
    "mulling",
    "reflecting",
    "computing",
    "synthesizing",
    "formulating",
    "brainstorming",
)

private val THINKING_PREFIX_RE =
    Regex(
        """^\s*(?:\([^)\n]{1,48}\)\s*)?(?:""" +
            THINKING_VERBS.joinToString("|") +
            """)\.\.\.\s*""",
        RegexOption.IGNORE_CASE,
    )

private val URL_RE = Regex("""\bhttps?://\S+""", RegexOption.IGNORE_CASE)

// Desktop tests the whole cell against /^:?-{3,}:?$/; `matches` anchors for us.
private val MARKDOWN_TABLE_DELIMITER_CELL_RE = Regex(""":?-{3,}:?""")

private val CARRIAGE_RETURN_RE = Regex("""\r\n?""")
private val HYPHENATED_LINE_BREAK_RE = Regex("""(\p{L})-\n(\p{L})""")
private val LEADING_INDENTATION_RE = Regex("""^[ \t]*""")
private val HEADING_MARKER_RE = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
private val MARKDOWN_PUNCTUATION_RE = Regex("""[*_~>#]""")
private val LIST_MARKER_RE = Regex("""^\s*[-+*]\s+""", RegexOption.MULTILINE)
private val WHITESPACE_RUN_RE = Regex("""\s+""")

private class MarkdownTableRow(
    val blockquoteDepth: Int,
    val cells: List<String>,
)

/** A pipe escaped by an odd run of backslashes is content, not a cell border. */
private fun isUnescapedPipe(row: String, index: Int): Boolean {
    var backslashes = 0
    var cursor = index - 1
    while (cursor >= 0 && row[cursor] == '\\') {
        backslashes += 1
        cursor -= 1
    }
    return backslashes % 2 == 0
}

private fun splitMarkdownTableCells(row: String): List<String> {
    val cells = mutableListOf<String>()
    var cellStart = 0
    for (index in row.indices) {
        if (row[index] == '|' && isUnescapedPipe(row, index)) {
            cells += row.substring(cellStart, index).trim()
            cellStart = index + 1
        }
    }
    cells += row.substring(cellStart).trim()
    return cells
}

private fun parseMarkdownTableRow(line: String): MarkdownTableRow? {
    var row = line
    var blockquoteDepth = 0

    while (true) {
        val indentation = LEADING_INDENTATION_RE.find(row)?.value.orEmpty()
        // A tab or a fourth space makes this indented code, never a table.
        if (indentation.contains('\t') || indentation.length > 3) return null
        row = row.substring(indentation.length)
        if (!row.startsWith(">")) break
        blockquoteDepth += 1
        row = row.drop(1)
        if (row.startsWith(" ")) row = row.drop(1)
    }

    row = row.trimEnd()

    val pipeIndexes = row.indices.filter { row[it] == '|' && isUnescapedPipe(row, it) }
    if (pipeIndexes.isEmpty()) return null

    val hasLeadingPipe = pipeIndexes.first() == 0
    val hasTrailingPipe = pipeIndexes.last() == row.length - 1

    if (hasLeadingPipe) row = row.drop(1)
    if (hasTrailingPipe) row = row.dropLast(1)

    val cells = splitMarkdownTableCells(row)
    if (cells.size < 2 && !(hasLeadingPipe && hasTrailingPipe && cells.size == 1)) return null

    return MarkdownTableRow(blockquoteDepth, cells)
}

/**
 * Drops whole GFM tables — header, delimiter and body rows — while leaving the
 * prose around them. Tabular data read aloud is unusable; the table stays on
 * screen where it is readable.
 */
private fun stripMarkdownTables(text: String): String {
    val lines = CARRIAGE_RETURN_RE.replace(text, "\n").split("\n")
    val tableLines = mutableSetOf<Int>()

    var index = 1
    while (index < lines.size) {
        val delimiterRow = parseMarkdownTableRow(lines[index])
        val headerRow = parseMarkdownTableRow(lines[index - 1])

        if (delimiterRow == null ||
            headerRow == null ||
            !delimiterRow.cells.all { MARKDOWN_TABLE_DELIMITER_CELL_RE.matches(it) } ||
            headerRow.cells.size != delimiterRow.cells.size ||
            headerRow.blockquoteDepth != delimiterRow.blockquoteDepth
        ) {
            index += 1
            continue
        }

        tableLines += index - 1
        tableLines += index

        var rowIndex = index + 1
        while (rowIndex < lines.size) {
            val bodyRow = parseMarkdownTableRow(lines[rowIndex])
            if (bodyRow == null || bodyRow.blockquoteDepth != delimiterRow.blockquoteDepth) break
            tableLines += rowIndex
            rowIndex += 1
        }

        index = rowIndex
    }

    return lines.filterIndexed { position, _ -> position !in tableLines }.joinToString("\n")
}

private fun normalizeLineBreaks(text: String): String =
    CARRIAGE_RETURN_RE.replace(text, "\n")
        .let { HYPHENATED_LINE_BREAK_RE.replace(it, "\$1\$2") }
        // A paragraph that already ends in punctuation keeps it; only an
        // unpunctuated break earns a synthetic full stop.
        .let { PUNCTUATED_PARAGRAPH_BREAK_RE.replace(it, "\$1\$2 ") }
        .let { PARAGRAPH_BREAK_RE.replace(it, ". ") }
        .let { SOFT_BREAK_RE.replace(it, " ") }

/**
 * Turns reply markdown into text worth reading aloud. Pure: same input, same
 * output, no logging, no I/O.
 */
fun sanitizeTextForSpeech(text: String): String =
    normalizeLineBreaks(stripMarkdownTables(text))
        .let { FENCED_CODE_RE.replace(it, CODE_BLOCK_SUMMARY) }
        .let { THINKING_PREFIX_RE.replace(it, " ") }
        .let { MARKDOWN_LINK_RE.replace(it, "\$1") }
        .let { INLINE_CODE_RE.replace(it, "\$1") }
        .let { URL_RE.replace(it, URL_SUMMARY) }
        .let { EMOJI_RE.replace(it, " ") }
        .let { HEADING_MARKER_RE.replace(it, "") }
        .let { MARKDOWN_PUNCTUATION_RE.replace(it, "") }
        .let { LIST_MARKER_RE.replace(it, "") }
        .let { WHITESPACE_RUN_RE.replace(it, " ") }
        .trim()
