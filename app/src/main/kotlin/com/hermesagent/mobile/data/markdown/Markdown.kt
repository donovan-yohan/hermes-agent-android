package com.hermesagent.mobile.data.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/**
 * The transcript markdown model.
 *
 * Desktop renders the transcript with `@assistant-ui/react` + a full markdown
 * pipeline; the scope spike's conclusion for Android is that **the list item is
 * a block, not a message** (`docs/spikes/native-kotlin-ssh-client-scope.md`
 * §6.2), because only the live tail block should recompose per token.
 *
 * Parsing is delegated to `org.commonmark` (spec-correct emphasis flanking,
 * nesting, lazy continuation) with the GFM tables extension; this file maps
 * that AST onto [MarkdownBlock]. What stays here is the part a library cannot
 * own: the streaming contract (a half-arrived fence renders as code, not
 * flickering prose) and the flat renderer-facing shape.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val spans: List<InlineSpan>) : MarkdownBlock
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MarkdownBlock

    /** A bullet item's lines, already inline-parsed. */
    data class Bullets(val items: List<List<InlineSpan>>) : MarkdownBlock

    /** An ordered item's lines, already inline-parsed; numbering starts at 1. */
    data class Numbered(val items: List<List<InlineSpan>>) : MarkdownBlock

    /**
     * A GFM pipe table. Rows are normalised to [columnCount]: short rows are
     * padded with empty cells, excess cells are dropped, so the grid never
     * jaggedly changes width mid-stream.
     */
    data class Table(
        /** One cell per column; padded/dropped to [columnCount] by the mapper. */
        val header: List<TableCell>,
        val rows: List<List<TableCell>>,
        val columnCount: Int,
    ) : MarkdownBlock

    /** One table cell: the inline spans between two column boundaries. */
    data class TableCell(val spans: List<InlineSpan>)

    data class CodeFence(val language: String?, val code: String) : MarkdownBlock
}

sealed interface InlineSpan {
    val text: String

    data class Plain(override val text: String) : InlineSpan
    data class Code(override val text: String) : InlineSpan
    data class Strong(override val text: String) : InlineSpan
    data class Emphasis(override val text: String) : InlineSpan
}

/** Shared spec-correct parser; extensions enabled once, stateless afterwards. */
private val PARSER: Parser = Parser.builder()
    .extensions(listOf(TablesExtension.create()))
    .build()

/**
 * Parse markdown into blocks via the CommonMark AST.
 *
 * Streaming note: CommonMark parses a whole document, but re-parsing a growing
 * string is monotonic for settled prefixes — earlier tokens never change what
 * they produced once more text follows them, which is all the transcript needs
 * to avoid per-token flicker.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    if (source.isBlank()) return emptyList()

    val document = PARSER.parse(withClosedStreamingFence(source))
    val blocks = mutableListOf<MarkdownBlock>()
    var child = document.firstChild
    while (child != null) {
        collectBlock(child, blocks)
        child = child.next
    }
    return blocks
}

/**
 * Streaming tail repair: if the source ends inside an unterminated fenced code
 * block, close it, so in-flight code parses as a [MarkdownBlock.CodeFence]
 * instead of vanishing into paragraph text until the real closer arrives.
 * Incomplete paragraphs and table rows are already valid markdown on their own.
 */
private fun withClosedStreamingFence(source: String): String {
    val fenceOpen = Regex("(?m)^(```|~~~)").findAll(source).count() % 2 == 1
    if (!fenceOpen) return source
    return source.trimEnd('\n') + "\n```\n"
}

/** Maps one top-level node into zero or more blocks (quotes flatten). */
private fun collectBlock(node: Node, out: MutableList<MarkdownBlock>) {
    when (node) {
        is Paragraph -> out += MarkdownBlock.Paragraph(parseInline(node.firstChild))

        is Heading -> out += MarkdownBlock.Heading(
            level = node.level.coerceIn(1, 6),
            spans = parseInline(node.firstChild),
        )

        is BulletList -> {
            val items = collectListItems(node)
            if (items.isNotEmpty()) out += MarkdownBlock.Bullets(items)
        }

        is OrderedList -> {
            val items = collectListItems(node)
            if (items.isNotEmpty()) out += MarkdownBlock.Numbered(items)
        }

        // A quote renders as its inner blocks flattened into the transcript;
        // there is no nested-surface grammar to preserve the quote box itself.
        is BlockQuote -> {
            var child = node.firstChild
            while (child != null) {
                collectBlock(child, out)
                child = child.next
            }
        }

        is TableBlock -> node.mapTable()?.let(out::add)

        is FencedCodeBlock -> out += MarkdownBlock.CodeFence(
            language = node.info?.trim()?.takeIf(String::isNotEmpty),
            // CommonMark keeps the closing newline of every line in the
            // literal; the transcript never wants a trailing blank one.
            code = node.literal.trimEnd('\n'),
        )

        is IndentedCodeBlock -> out += MarkdownBlock.CodeFence(
            language = null,
            code = node.literal.trimEnd('\n'),
        )

        else -> Unit
    }
}

private fun collectListItems(list: Node): List<List<InlineSpan>> {
    val items = mutableListOf<List<InlineSpan>>()
    var item: Node? = list.firstChild
    while (item != null) {
        // A list item's first child carries its inline content; loose lists
        // wrap it in a Paragraph, tight ones expose inlines directly.
        val content = item.firstOrNullChild()
        items += parseInline(content)
        item = item.next
    }
    return items
}

private fun Node.firstOrNullChild(): Node? = firstChild

private fun TableBlock.mapTable(): MarkdownBlock.Table? {
    val head = firstChild as? TableHead ?: return null
    val headerCells = rowCells(head)

    val columnCount = headerCells.size
    if (columnCount == 0) return null

    val bodyRows = mutableListOf<List<MarkdownBlock.TableCell>>()
    var section: Node? = head.next
    while (section != null) {
        var row: Node? = section.firstChild
        while (row != null) {
            val cells = rowCells(row)
            bodyRows += (0 until columnCount).map { column ->
                cells.getOrNull(column) ?: MarkdownBlock.TableCell(emptyList())
            }
            row = row.next
        }
        section = section.next
    }
    return MarkdownBlock.Table(headerCells, bodyRows, columnCount)
}

/** Every GFM table cell under a head or row node, inline-parsed in order. */
private fun rowCells(container: Node): List<MarkdownBlock.TableCell> {
    val cells = mutableListOf<MarkdownBlock.TableCell>()
    var descendant: Node? = container.firstChild
    while (descendant != null) {
        if (descendant is TableCell) {
            cells += MarkdownBlock.TableCell(parseInline(descendant.firstChild))
        } else if (descendant.firstChild != null) {
            cells += rowCells(descendant)
        }
        descendant = descendant.next
    }
    return cells
}

/**
 * Inline content to spans. Code wins over emphasis by AST construction, and
 * emphasis flanking/nesting rules are CommonMark's problem now.
 */
internal fun parseInline(node: Node?): List<InlineSpan> {
    if (node == null) return emptyList()
    val spans = mutableListOf<InlineSpan>()
    var child: Node? = node
    while (child != null) {
        when (child) {
            is Text -> spans += InlineSpan.Plain(child.literal)
            is Code -> spans += InlineSpan.Code(child.literal)
            is StrongEmphasis -> spans += InlineSpan.Strong(collectText(child))
            is Emphasis -> spans += InlineSpan.Emphasis(collectText(child))
            is SoftLineBreak -> spans += InlineSpan.Plain(" ")
            is HardLineBreak -> spans += InlineSpan.Plain("\n")
            else -> spans += InlineSpan.Plain(collectText(child))
        }
        child = child.next
    }
    return spans
}

/**
 * The visible text of an inline subtree, recursing through children only —
 * never the node itself, which is what a StackOverflow taught us.
 */
private fun collectText(node: Node): String {
    if (node is Text) return node.literal
    if (node is Code) return node.literal
    val separator = when (node) {
        is SoftLineBreak -> " "
        is HardLineBreak -> "\n"
        else -> null
    }
    if (separator != null) return separator

    return buildString {
        var child: Node? = node.firstChild
        while (child != null) {
            append(collectText(child))
            child = child.next
        }
    }
}
