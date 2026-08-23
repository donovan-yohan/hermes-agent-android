package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the transcript markdown pipeline, written against the
 * public `parseMarkdown` seam so the CommonMark dependency is an
 * implementation detail: if the engine changes again, these contracts hold.
 */
class MarkdownTest {

    /** Join a cell's spans back to the text a reader sees. */
    private fun List<MarkdownBlock.TableCell>.texts(): List<String> =
        map { cell -> cell.spans.joinToString("") { it.text } }

    @Test
    fun `paragraphs, headings and bullets become distinct blocks`() {
        val blocks = parseMarkdown(
            """
            ## What is real

            The transcript renders blocks.

            - themes
            - the probe
            """.trimIndent(),
        )

        assertEquals(3, blocks.size)
        assertEquals(2, (blocks[0] as MarkdownBlock.Heading).level)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
        assertEquals(
            listOf(listOf<InlineSpan>(InlineSpan.Plain("themes")), listOf<InlineSpan>(InlineSpan.Plain("the probe"))),
            (blocks[2] as MarkdownBlock.Bullets).items,
        )
    }

    @Test
    fun `a closed fence keeps its language and body verbatim`() {
        val blocks = parseMarkdown("before\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\nafter")
        val fence = blocks.filterIsInstance<MarkdownBlock.CodeFence>().single()

        assertEquals("kotlin", fence.language)
        assertEquals("val a = 1\n\nval b = 2", fence.code)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `an unterminated streaming fence still renders as code`() {
        // The case that matters mid-turn: the closing ``` has not arrived yet.
        // The tail must parse as code, not as prose that flickers on each delta.
        val fence = parseMarkdown("```sh\nprintf HERMES").filterIsInstance<MarkdownBlock.CodeFence>().single()
        assertEquals("sh", fence.language)
        assertEquals("printf HERMES", fence.code)
    }

    @Test
    fun `emphasis flanking follows the spec, not regex luck`() {
        // Classic hand-rolled failure: `foo*bar*baz` must emphasise; a
        // delimiter-only heuristic leaves literal asterisks behind.
        val spans = parseMarkdown("foo*bar*baz").filterIsInstance<MarkdownBlock.Paragraph>().single().spans
        assertEquals(
            listOf<InlineSpan>(
                InlineSpan.Plain("foo"),
                InlineSpan.Emphasis("bar"),
                InlineSpan.Plain("baz"),
            ),
            spans,
        )
    }

    @Test
    fun `nested emphasis survives inside strong text`() {
        val spans = parseMarkdown("**bold and *slim* end**")
            .filterIsInstance<MarkdownBlock.Paragraph>()
            .single()
            .spans

        assertEquals(1, spans.size)
        assertEquals("bold and slim end", spans.single().text)
        assertTrue(spans.single() is InlineSpan.Strong)
    }

    @Test
    fun `inline code wins over emphasis inside it`() {
        val spans = parseMarkdown("run `printf *HERMES*` now")
            .filterIsInstance<MarkdownBlock.Paragraph>()
            .single()
            .spans

        assertEquals(
            listOf<InlineSpan>(
                InlineSpan.Plain("run "),
                InlineSpan.Code("printf *HERMES*"),
                InlineSpan.Plain(" now"),
            ),
            spans,
        )
    }

    @Test
    fun `a pipe table becomes a table block, not paragraph soup`() {
        val blocks = parseMarkdown(
            """
            | Layer | State |
            |---|---|
            | models | portable |
            | gateway | rewrite |

            after the table
            """.trimIndent(),
        )

        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("Layer", "State"), table.header.texts())
        assertEquals(2, table.columnCount)
        assertEquals(listOf("models", "portable"), table.rows[0].texts())
        assertEquals(listOf("gateway", "rewrite"), table.rows[1].texts())
        assertEquals(2, blocks.size)
        assertTrue(blocks[1] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `header without outer pipes parses identically`() {
        val table = parseMarkdown("`val x` | **bold**\n--- | ---\n| 1 | 2 |")
            .filterIsInstance<MarkdownBlock.Table>()
            .single()

        assertEquals(listOf<InlineSpan>(InlineSpan.Code("val x")), table.header[0].spans)
        assertEquals(listOf<InlineSpan>(InlineSpan.Strong("bold")), table.header[1].spans)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `ragged rows are padded to the column count`() {
        val table = parseMarkdown("| a | b | c |\n|---|---|---|\n| 1 |")
            .filterIsInstance<MarkdownBlock.Table>()
            .single()

        assertEquals(3, table.columnCount)
        assertEquals(listOf("a", "b", "c"), table.header.texts())
        assertEquals(listOf("1", "", ""), table.rows[0].texts())
    }

    @Test
    fun `escaped pipes stay inside their cell`() {
        val table = parseMarkdown("| expr | out |\n|---|---|\n| a\\|b | 1 |")
            .filterIsInstance<MarkdownBlock.Table>()
            .single()

        assertEquals(listOf("a|b", "1"), table.rows[0].texts())
    }

    @Test
    fun `a divider mention in prose is not a table`() {
        val blocks = parseMarkdown("separate sections with |---| below\n\nnext")

        assertTrue(blocks.none { it is MarkdownBlock.Table })
    }

    @Test
    fun `ordered lists render as numbers`() {
        val blocks = parseMarkdown("3. first\n4. second")

        val numbered = blocks.filterIsInstance<MarkdownBlock.Numbered>().single()
        assertEquals(3, numbered.start)
        assertEquals(
            listOf(
                listOf<InlineSpan>(InlineSpan.Plain("first")),
                listOf<InlineSpan>(InlineSpan.Plain("second")),
            ),
            numbered.items,
        )
    }

    @Test
    fun `list items keep their inline formatting`() {
        // Regression: commonmark wraps tight-list item content in a Paragraph,
        // so reading firstChild directly flattened everything to plain text.
        val blocks = parseMarkdown("- **bold** item\n- a `code` one")
        val bullets = blocks.filterIsInstance<MarkdownBlock.Bullets>().single()

        assertEquals(
            listOf<InlineSpan>(InlineSpan.Strong("bold"), InlineSpan.Plain(" item")),
            bullets.items[0],
        )
        assertEquals(
            listOf<InlineSpan>(InlineSpan.Plain("a "), InlineSpan.Code("code"), InlineSpan.Plain(" one")),
            bullets.items[1],
        )
    }

    @Test
    fun `nested list items are separated, not fused`() {
        // Regression: walking block siblings without separators produced
        // "outernested anested b".
        val bullets = parseMarkdown("- outer\n  - nested a\n  - nested b")
            .filterIsInstance<MarkdownBlock.Bullets>()
            .single()

        val joined = bullets.items.single().joinToString("") { it.text }
        assertTrue(joined.startsWith("outer"))
        assertTrue(joined.contains("nested a"))
        assertTrue(joined.contains("nested b"))
        assertTrue("fused words", !joined.contains("anested"))
    }

    @Test
    fun `a tilde fence streams as code without corruption`() {
        // Regression: the deleted fence-repair hack appended ``` into ~~~
        // bodies and fabricated empty fences around complete ones.
        val fence = parseMarkdown("~~~sh\nprintf HI").filterIsInstance<MarkdownBlock.CodeFence>().single()
        assertEquals("sh", fence.language)
        assertEquals("printf HI", fence.code)

        val repaired = parseMarkdown("```\n~~~\n```").filterIsInstance<MarkdownBlock.CodeFence>()
        assertEquals(1, repaired.size)
    }

    @Test
    fun `block quotes flatten into the transcript flow`() {
        val blocks = parseMarkdown("> quoted line\n\nafter")

        assertTrue(blocks.first() is MarkdownBlock.Paragraph)
        assertEquals(
            listOf<InlineSpan>(InlineSpan.Plain("quoted line")),
            (blocks[0] as MarkdownBlock.Paragraph).spans,
        )
        assertEquals(2, blocks.size)
    }

    @Test
    fun `streaming prefixes never lose earlier blocks`() {
        val full = "First para.\n\nSecond para.\n\n```kotlin\nval x = 1\n```"
        var previousSettled = 0
        for (length in 1..full.length) {
            val settled = parseMarkdown(full.take(length)).dropLast(1).size
            assertTrue(
                "settled block count went backwards at length $length",
                settled >= previousSettled - 1,
            )
            previousSettled = settled
        }
        assertEquals(3, parseMarkdown(full).size)
    }

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("   \n  "))
    }
}
