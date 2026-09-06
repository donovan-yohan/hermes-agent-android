package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptDirectiveTest {

    data class Case(
        val input: String,
        val expected: ParsedTranscriptDirective?,
    )

    @Test
    fun `parses directives case-for-case`() {
        val cases = listOf(
            Case(
                input = "::task{id=\"BB-12\"}",
                expected = ParsedTranscriptDirective(
                    name = "task",
                    attrs = mapOf("id" to "BB-12"),
                    source = "::task{id=\"BB-12\"}",
                ),
            ),
            Case(
                input = "::preview{file='a.html'}",
                expected = ParsedTranscriptDirective(
                    name = "preview",
                    attrs = mapOf("file" to "a.html"),
                    source = "::preview{file='a.html'}",
                ),
            ),
            Case(
                input = "::name",
                expected = ParsedTranscriptDirective(
                    name = "name",
                    attrs = emptyMap(),
                    source = "::name",
                ),
            ),
            Case(
                input = "::Task{}",
                expected = null,
            ),
            Case(
                input = "see ::task{id=\"1\"}",
                expected = null,
            ),
            Case(
                input = "::task{id=\"1\"}\n::name",
                expected = null,
            ),
            Case(
                input = "::" + "a".repeat(1199), // length 1201
                expected = null,
            ),
            Case(
                input = "::x{k=\"v\" K=\"w\"}",
                expected = ParsedTranscriptDirective(
                    name = "x",
                    attrs = mapOf("k" to "w"),
                    source = "::x{k=\"v\" K=\"w\"}",
                ),
            ),
            Case(
                input = "::x{bad key=\"v\"}",
                expected = ParsedTranscriptDirective(
                    name = "x",
                    attrs = mapOf("key" to "v"),
                    source = "::x{bad key=\"v\"}",
                ),
            ),
            Case(
                input = "  \t ::name \n",
                expected = ParsedTranscriptDirective(
                    name = "name",
                    attrs = emptyMap(),
                    source = "::name",
                ),
            ),
        )

        for (case in cases) {
            val parsed = parseTranscriptDirective(case.input)
            assertEquals("input: <${case.input}>", case.expected, parsed)
            if (parsed != null) {
                assertEquals("source must be the trimmed text", case.input.trim(), parsed.source)
            }
        }
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(parseTranscriptDirective(""))
        assertNull(parseTranscriptDirective("   \n  "))
    }
}

