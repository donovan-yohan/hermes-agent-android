package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the per-reply copy control hands to the clipboard.
 *
 * The contract is *rendered text, not source*: whatever a reader could have
 * dragged a cursor across, plus the list structure a phone cannot practically
 * drag across. Fixed inputs on purpose — the failure mode this pins is a
 * projection that quietly starts leaking markdown syntax, or a gateway
 * persistence artefact, into someone's paste buffer.
 */
class MarkdownPlainTextTest {

    /** The clipboard text for a reply, through the seam the transcript uses. */
    private fun copyTextOf(markdown: String): String =
        parseMarkdown(markdown).replyPlainText()

    @Test
    fun `inline markers are rendered away, not copied through`() {
        assertEquals(
            "Bold and italic and code.",
            copyTextOf("**Bold** and *italic* and `code`."),
        )
    }

    @Test
    fun `headings lose their hashes and blocks keep a blank line between them`() {
        assertEquals(
            "What changed\n\nOne file moved.",
            copyTextOf(
                """
                ## What changed

                One file moved.
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `list markers survive so a pasted list is still a list`() {
        assertEquals(
            "• themes\n• the probe",
            copyTextOf(
                """
                - themes
                - the probe
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an ordered list is numbered from its own start value`() {
        assertEquals(
            "3. third\n4. fourth",
            copyTextOf(
                """
                3. third
                4. fourth
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a fence copies its code without the fence or the language tag`() {
        assertEquals(
            "val x = 1\nval y = 2",
            copyTextOf(
                """
                ```kotlin
                val x = 1
                val y = 2
                ```
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a table copies as tab separated rows, header first`() {
        assertEquals(
            "Name\tRole\nika\tfrontend",
            copyTextOf(
                """
                | Name | Role |
                | --- | --- |
                | ika | frontend |
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a standalone image reference line never reaches the clipboard`() {
        assertEquals(
            "Here is the screen.",
            copyTextOf("Here is the screen.\n\n@image:/staged/shot.png"),
        )
        assertEquals("", copyTextOf("@image:/staged/shot.png"))
    }

    @Test
    fun `a fenced example of the reference format survives verbatim`() {
        // The strip is line-anchored, so running it over the source would gut a
        // reply that is *explaining* the persisted format rather than carrying
        // an attachment. Fences are projected untouched.
        assertEquals(
            "@image:/staged/shot.png",
            copyTextOf(
                """
                ```text
                @image:/staged/shot.png
                ```
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a reference folded into a paragraph by a soft break is left alone`() {
        // Known residue of stripping per block instead of per source line:
        // CommonMark folds a single newline into a space, so the ref stops
        // being a line. Only user turns are written in that shape, and they are
        // split before they are ever parsed.
        assertEquals(
            "Here is the screen. @image:/staged/shot.png",
            copyTextOf("Here is the screen.\n@image:/staged/shot.png"),
        )
    }

    @Test
    fun `a reply with no visible text projects to nothing`() {
        assertEquals("", copyTextOf("   \n\n  "))
    }

    @Test
    fun `a mixed reply keeps every block in transcript order`() {
        val markdown = """
            Summary of the change.

            - one
            - two

            ```sh
            ./gradlew check
            ```

            Done.
        """.trimIndent()

        assertEquals(
            "Summary of the change.\n\n• one\n• two\n\n./gradlew check\n\nDone.",
            copyTextOf(markdown),
        )
    }
}
