package com.hermesagent.mobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Desktop's own speech-sanitizer fixtures, ported test-for-test from
 * `apps/desktop/src/lib/speech-text.test.ts:5-152`
 * @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`. Each test keeps its Desktop
 * name so a future Desktop change is a visible diff here, not silent drift.
 *
 * The last four have no Desktop counterpart — Desktop's suite never exercises
 * the emoji, URL or thinking-prefix rules — so their fixtures are derived from
 * the regex definitions at `speech-text.ts:1,11-14` and are labelled as such.
 */
class SpeechTextTest {
    @Test
    fun `summarizes fenced code blocks instead of reading them literally`() {
        assertEquals(
            "Here is code: code block omitted Done.",
            sanitizeTextForSpeech("Here is code:\n```ts\nconst x = 1\n```\nDone."),
        )
    }

    @Test
    fun `still keeps normal prose and inline code readable`() {
        assertEquals(
            "Use git status after the change.",
            sanitizeTextForSpeech("Use `git status` after the change."),
        )
    }

    @Test
    fun `skips markdown table data while preserving surrounding human text`() {
        val text = """
            Here is the quick takeaway: the totals remain unchanged.

            | Item | Value | Notes |
            | --- | ---: | --- |
            | Example A | 10 | first row |
            | Example B | 20 | second row |

            Full detail stays visible on screen.
        """.trimIndent()

        assertEquals(
            "Here is the quick takeaway: the totals remain unchanged. Full detail stays visible on screen.",
            sanitizeTextForSpeech(text),
        )
    }

    @Test
    fun `does not strip prose that merely contains a pipe character`() {
        val text = "Use the summary first | keep the table on screen when it matters."

        assertEquals(text, sanitizeTextForSpeech(text))
    }

    @Test
    fun `does not duplicate punctuation across paragraph breaks`() {
        val text = """
            First sentence.

            Second sentence.
        """.trimIndent()

        assertEquals("First sentence. Second sentence.", sanitizeTextForSpeech(text))
    }

    @Test
    fun `does not duplicate punctuation after markdown emphasis`() {
        assertEquals(
            "First sentence. Second sentence.",
            sanitizeTextForSpeech("**First sentence.**\n\nSecond sentence."),
        )
    }

    @Test
    fun `does not duplicate punctuation after a closing quote`() {
        assertEquals(
            "“First sentence.” Second sentence.",
            sanitizeTextForSpeech("“First sentence.”\n\nSecond sentence."),
        )
    }

    @Test
    fun `does not duplicate punctuation after a closing parenthesis`() {
        assertEquals(
            "(First sentence.) Second sentence.",
            sanitizeTextForSpeech("(First sentence.)\n\nSecond sentence."),
        )
    }

    @Test
    fun `skips markdown tables without leading and trailing pipes`() {
        val text = """
            Main takeaway: total is unchanged.

            Item | Value
            --- | ---:
            Example A | 10
            Example B | 20

            Done.
        """.trimIndent()

        assertEquals("Main takeaway: total is unchanged. Done.", sanitizeTextForSpeech(text))
    }

    @Test
    fun `skips markdown tables nested inside blockquotes`() {
        val text = """
            Before the table.

            > | Item | Value |
            > | --- | ---: |
            > | Example A | 10 |
            > | Example B | 20 |

            After the table.
        """.trimIndent()

        assertEquals("Before the table. After the table.", sanitizeTextForSpeech(text))
    }

    @Test
    fun `allows marker padding plus three spaces in blockquoted tables`() {
        val text = """
            Before the table.

            >    | Item | Value |
            >    | --- | ---: |
            >    | Example A | 10 |

            After the table.
        """.trimIndent()

        assertEquals("Before the table. After the table.", sanitizeTextForSpeech(text))
    }

    @Test
    fun `skips explicit single-column markdown tables`() {
        val text = """
            Before the table.

            | Item |
            | --- |
            | Example A |

            After the table.
        """.trimIndent()

        assertEquals("Before the table. After the table.", sanitizeTextForSpeech(text))
    }

    @Test
    fun `preserves rows outside a table blockquote`() {
        val text = """
            > | Item | Value |
            > | --- | ---: |
            > | Example A | 10 |
            Outside | prose
        """.trimIndent()

        assertEquals("Outside | prose", sanitizeTextForSpeech(text))
    }

    @Test
    fun `preserves malformed tables with mismatched column counts`() {
        val text = """
            Heading | Detail
            --- | --- | ---
            Keep this prose.
        """.trimIndent()

        assertTrue(sanitizeTextForSpeech(text).contains("Heading | Detail"))
    }

    @Test
    fun `skips GFM body rows whose cell counts differ from the header`() {
        val text = """
            Before the table.

            | Item | Value |
            | --- | ---: |
            | Example A |
            | Example B | 20 | ignored |

            After the table.
        """.trimIndent()

        assertEquals("Before the table. After the table.", sanitizeTextForSpeech(text))
    }

    @Test
    fun `skips tables containing escaped pipe characters`() {
        val text = """
            Before the table.

            | Item \| detail | Value |
            | --- | ---: |
            | Example A | 10 |

            After the table.
        """.trimIndent()

        assertEquals("Before the table. After the table.", sanitizeTextForSpeech(text))
    }

    @Test
    fun `preserves indented code that resembles a table`() {
        // Four-space indentation is code, not a table; written without
        // trimIndent so the leading whitespace survives into the fixture.
        val text = "    Item | Value\n    --- | ---\n    Example A | 10"

        assertTrue(sanitizeTextForSpeech(text).contains("Item | Value"))
    }

    // ── No Desktop test counterpart; fixtures derived from the regexes ──

    @Test
    fun `strips emoji from spoken text - derived from EMOJI_RE`() {
        assertEquals(
            "Deploy finished cleanly.",
            sanitizeTextForSpeech("Deploy 🚀 finished ✅ cleanly."),
        )
    }

    @Test
    fun `reads a bare URL as link - derived from URL_RE`() {
        assertEquals(
            "See link for details.",
            sanitizeTextForSpeech("See https://example.com/docs for details."),
        )
    }

    @Test
    fun `a non-breaking space does not swallow the word after a URL`() {
        // Java's \S is ASCII-only, so an unspelled \S+ would eat the NBSP and
        // the word behind it. JavaScript's \s covers NBSP; so must ours.
        assertEquals(
            "See link and more.",
            sanitizeTextForSpeech("See https://example.com\u00A0and more."),
        )
        assertEquals(
            "See link and more.",
            sanitizeTextForSpeech("See https://example.com and more."),
        )
    }

    @Test
    fun `collapses Unicode whitespace runs like JavaScript does`() {
        assertEquals(
            "One two three.",
            sanitizeTextForSpeech("\u00A0One\u2003two\u3000three.\u00A0"),
        )
    }

    @Test
    fun `drops a leading thinking prefix - derived from THINKING_PREFIX_RE`() {
        assertEquals(
            "the tests already cover this.",
            sanitizeTextForSpeech("(considering) Thinking... the tests already cover this."),
        )
    }

    @Test
    fun `spoken summaries keep Desktop copy verbatim`() {
        assertEquals(" code block omitted ", CODE_BLOCK_SUMMARY)
        assertEquals(" link ", URL_SUMMARY)
    }
}
