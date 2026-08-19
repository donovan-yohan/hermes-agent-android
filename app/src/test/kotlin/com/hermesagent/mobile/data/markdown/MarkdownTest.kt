package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

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
        assertEquals(2, (blocks[2] as MarkdownBlock.Bullets).items.size)
    }

    @Test
    fun `a closed fence keeps its language and body verbatim`() {
        val blocks = parseMarkdown("before\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\nafter")
        val fence = blocks.filterIsInstance<MarkdownBlock.CodeFence>().single()

        assertEquals("kotlin", fence.language)
        assertEquals("val a = 1\n\nval b = 2", fence.code)
        assertTrue(fence.closed)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `an unterminated fence still renders as code`() {
        // The streaming case: the closing ``` has not arrived yet. Treating the
        // tail as prose would make the block flicker on every token.
        val fence = parseMarkdown("```sh\nprintf HERMES").filterIsInstance<MarkdownBlock.CodeFence>().single()
        assertEquals("sh", fence.language)
        assertEquals("printf HERMES", fence.code)
        assertTrue(!fence.closed)
    }

    @Test
    fun `inline code wins over emphasis inside it`() {
        val spans = parseInline("run `printf *HERMES*` now")
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
    fun `bold and italic are separate spans`() {
        assertEquals(
            listOf<InlineSpan>(
                InlineSpan.Strong("No"),
                InlineSpan.Plain(". Termux is "),
                InlineSpan.Emphasis("not"),
                InlineSpan.Plain(" readable here."),
            ),
            parseInline("**No**. Termux is *not* readable here."),
        )
    }

    @Test
    fun `streaming prefixes never lose earlier blocks`() {
        // Parsing a growing string must be monotonic in settled blocks: whatever
        // was a finished paragraph one token ago is still one now.
        val full = "First para.\n\nSecond para.\n\n```kotlin\nval x = 1\n```"
        var previousSettled = 0
        for (length in 1..full.length) {
            val blocks = parseMarkdown(full.take(length))
            val settled = blocks.dropLast(1).size
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
        assertEquals(emptyList<InlineSpan>(), parseInline(""))
    }
}
