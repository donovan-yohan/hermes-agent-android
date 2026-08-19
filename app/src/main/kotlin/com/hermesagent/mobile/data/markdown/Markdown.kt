package com.hermesagent.mobile.data.markdown

/**
 * A deliberately small markdown block model.
 *
 * Desktop renders the transcript with `@assistant-ui/react` + a full markdown
 * pipeline; the scope spike's conclusion for Android is that **the list item is
 * a block, not a message** (`docs/spikes/native-kotlin-ssh-client-scope.md`
 * §6.2), because only the live tail block should recompose per token. This
 * parser exists to produce those blocks. It covers exactly what the transcript
 * shows today — paragraphs, fenced code, bullets, headings, inline code and
 * emphasis — and stops there. A real CommonMark dependency is the right answer
 * once the gateway sends real assistant output.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock
    data class Bullets(val items: List<List<InlineSpan>>) : MarkdownBlock
    data class CodeFence(val language: String?, val code: String, val closed: Boolean) : MarkdownBlock
}

sealed interface InlineSpan {
    val text: String

    data class Plain(override val text: String) : InlineSpan
    data class Code(override val text: String) : InlineSpan
    data class Strong(override val text: String) : InlineSpan
    data class Emphasis(override val text: String) : InlineSpan
}

/**
 * Parse markdown into blocks.
 *
 * Streaming-safe by construction: an unterminated fence yields a
 * [MarkdownBlock.CodeFence] with `closed = false` rather than swallowing the
 * rest of the document, so a half-arrived code block renders as a code block
 * instead of flickering between prose and code on every token.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = source.replace("\r\n", "\n").split("\n")

    var index = 0
    val paragraph = mutableListOf<String>()
    val bullets = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(parseInline(paragraph.joinToString(" ").trim()))
            paragraph.clear()
        }
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            blocks += MarkdownBlock.Bullets(bullets.map { parseInline(it) })
            bullets.clear()
        }
    }

    fun flushAll() {
        flushParagraph()
        flushBullets()
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flushAll()
                val language = trimmed.removePrefix("```").trim().takeIf { it.isNotEmpty() }
                val body = mutableListOf<String>()
                index++
                var closed = false
                while (index < lines.size) {
                    if (lines[index].trim() == "```") {
                        closed = true
                        index++
                        break
                    }
                    body += lines[index]
                    index++
                }
                blocks += MarkdownBlock.CodeFence(language, body.joinToString("\n"), closed)
                continue
            }

            trimmed.isEmpty() -> flushAll()

            trimmed.startsWith("#") -> {
                flushAll()
                val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                blocks += MarkdownBlock.Heading(level, parseInline(trimmed.drop(level).trim()))
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                bullets += trimmed.drop(2).trim()
            }

            else -> {
                flushBullets()
                paragraph += trimmed
            }
        }
        index++
    }

    flushAll()
    return blocks
}

private val INLINE_PATTERN = Regex("`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|_([^_]+)_")

/** Inline spans. Code wins over emphasis, as every markdown renderer does. */
fun parseInline(text: String): List<InlineSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = mutableListOf<InlineSpan>()
    var cursor = 0

    for (match in INLINE_PATTERN.findAll(text)) {
        if (match.range.first > cursor) {
            spans += InlineSpan.Plain(text.substring(cursor, match.range.first))
        }
        val groups = match.groupValues
        spans += when {
            groups[1].isNotEmpty() -> InlineSpan.Code(groups[1])
            groups[2].isNotEmpty() -> InlineSpan.Strong(groups[2])
            groups[3].isNotEmpty() -> InlineSpan.Emphasis(groups[3])
            else -> InlineSpan.Emphasis(groups[4])
        }
        cursor = match.range.last + 1
    }

    if (cursor < text.length) spans += InlineSpan.Plain(text.substring(cursor))
    return spans
}
