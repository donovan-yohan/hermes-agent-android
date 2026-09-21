package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlightTest {
    @Test
    fun `closed block comment preserves following keyword and number highlighting`() {
        val source = "/* note */ val value = 42"
        val tokens = tokenizeSyntaxLine(source, requireNotNull(syntaxLanguageForPath("Example.kt")))
        assertEquals(source, tokens.joinToString("") { it.text })
        assertTrue(tokens.any { it.kind == SyntaxTokenKind.Keyword && it.text == "val" })
        assertTrue(tokens.any { it.kind == SyntaxTokenKind.Number && it.text == "42" })
    }
}
