package com.hermesagent.mobile.data.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell as CmTableCell
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
import org.commonmark.renderer.text.TextContentRenderer

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

    /** An ordered item's lines, already inline-parsed. */
    data class Numbered(
        val items: List<List<InlineSpan>>,
        /** The value the list's first item displays; GFM start attr, default 1. */
        val start: Int = 1,
    ) : MarkdownBlock

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

/** Renders inline subtrees to their visible text, spec-correctly. */
private val PLAIN_TEXT: TextContentRenderer = TextContentRenderer.builder()
    .extensions(listOf(TablesExtension.create()))
    .build()

/**
 * Parse markdown into blocks via the CommonMark AST.
 *
 * Streaming safety comes from CommonMark itself: an unterminated fenced code
 * block extends to end-of-input, so a half-arrived fence parses as
 * [MarkdownBlock.CodeFence] rather than flickering prose, and re-parsing a
 * growing string is monotonic for settled prefixes — earlier tokens never
 * change what they produced once more text follows them.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    if (source.isBlank()) return emptyList()

    val document = PARSER.parse(source)
    val blocks = mutableListOf<MarkdownBlock>()
    var child = document.firstChild
    while (child != null) {
        collectBlock(child, blocks)
        child = child.next
    }
    return blocks
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
            if (items.isNotEmpty()) out += MarkdownBlock.Numbered(items, node.startNumber.coerceAtLeast(1))
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
        items += collectItemLines(item)
        item = item.next
    }
    return items
}

/**
 * One list item to inline lines.
 *
 * commonmark-java wraps item content in a Paragraph for tight and loose lists
 * alike, so the content is reached through the block children, not
 * `firstChild` directly. A multi-paragraph item becomes separate lines joined
 * by an em-space break; a nested sub-list recurses into its own flattened
 * lines rather than being glued onto the parent text.
 */
private fun collectItemLines(item: Node): List<InlineSpan> {
    val lines = mutableListOf<InlineSpan>()
    var child: Node? = item.firstChild
    while (child != null) {
        when (child) {
            is Paragraph, is IndentedCodeBlock -> {
                if (lines.isNotEmpty()) lines += InlineSpan.Plain(" — ")
                if (child is Paragraph) {
                    lines += parseInline(child.firstChild)
                } else {
                    lines += InlineSpan.Code((child as IndentedCodeBlock).literal.trimEnd('\n'))
                }
            }

            is BulletList, is OrderedList -> {
                for (nested in collectListItems(child)) {
                    if (lines.isNotEmpty()) lines += InlineSpan.Plain(" — ")
                    lines += InlineSpan.Plain("• ")
                    lines += nested
                }
            }

            else -> lines += InlineSpan.Plain(PLAIN_TEXT.render(child).trim())
        }
        child = child.next
    }
    return lines
}

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
        if (descendant is CmTableCell) {
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
