package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptLinksTest {
    private fun spans(markdown: String): List<InlineSpan> =
        (parseMarkdown(markdown).first() as MarkdownBlock.Paragraph).spans

    @Test
    fun `formatted link keeps recursive label styles and destination`() {
        val link = spans("[**open** the *sandbox*]($DESTINATION)").single() as InlineSpan.Link
        assertEquals(DESTINATION, link.destination)
        assertEquals("open the sandbox", link.text)
        assertEquals(
            listOf(InlineSpan.Strong("open"), InlineSpan.Plain(" the "), InlineSpan.Emphasis("sandbox")),
            link.children,
        )
        assertEquals(listOf(TranscriptLink(0, 16, DESTINATION)), listOf(link).transcriptLinks())
    }

    @Test
    fun `links nested inside strong and emphasis retain all children`() {
        val strong = spans("**bold *[open]($DESTINATION)* end**").single() as InlineSpan.Strong
        assertEquals("bold open end", strong.text)
        val emphasis = strong.children[1] as InlineSpan.Emphasis
        assertEquals(DESTINATION, (emphasis.children.single() as InlineSpan.Link).destination)
        assertEquals(listOf(TranscriptLink(5, 9, DESTINATION)), listOf(strong).transcriptLinks())
    }

    @Test
    fun `core parser keeps angle and reference links with exact labels`() {
        val inline = spans("<$DESTINATION> and [open][target]\n\n[target]: $DESTINATION")
        assertEquals(listOf(DESTINATION, DESTINATION), inline.filterIsInstance<InlineSpan.Link>().map { it.destination })
        assertEquals("$DESTINATION and open", inline.spansText())
        assertEquals(2, inline.transcriptLinks().size)
    }

    @Test
    fun `bare links use final visible UTF16 offsets and balanced punctuation`() {
        val visible = "🧪 See (https://example.com/a_(b)?x=1&y=two#part), then https://localhost:8782/."
        val inline = listOf(InlineSpan.Plain(visible))
        val links = inline.transcriptLinks()
        assertEquals(listOf("https://example.com/a_(b)?x=1&y=two#part", "https://localhost:8782/"), links.map { it.destination })
        for (link in links) assertEquals(link.destination, visible.substring(link.start, link.end))
    }

    @Test
    fun `bare URL crosses formatting runs without duplicate annotations`() {
        val inline = spans("Visit https://**example.com**/activity?tab=run%20log#latest.")
        assertEquals(listOf("https://example.com/activity?tab=run%20log#latest"), inline.transcriptLinks().map { it.destination })
    }

    @Test
    fun `explicit destinations win over URL looking labels even when unsafe`() {
        val inline = spans("[https://example.org/](https://example.com/) [https://example.net/](javascript:bad)")
        val links = inline.transcriptLinks()
        assertEquals(listOf(TranscriptLink(0, "https://example.org/".length, "https://example.com/")), links)
    }

    @Test
    fun `code is literal but contained web URLs are extracted`() {
        val literal = "[not a label]($DESTINATION) **unchanged**"
        val inline = spans("`$literal`")
        assertEquals(listOf(InlineSpan.Code(literal)), inline)
        assertEquals(listOf(DESTINATION), inline.transcriptLinks().map { it.destination })
        val fence = parseMarkdown("```sh\n$literal\n```").single() as MarkdownBlock.CodeFence
        assertEquals(literal, fence.code)
        assertEquals(listOf(DESTINATION), listOf(InlineSpan.Plain(fence.code)).transcriptLinks().map { it.destination })
    }

    @Test
    fun `HTTP destinations preserve local IP unicode ports queries and fragments`() {
        val valid = listOf(
            DESTINATION,
            "HTTPS://EXAMPLE.COM:443/path?q=%25#section",
            "http://localhost:8782/activity",
            "http://127.0.0.1:8782/",
            "http://[::1]:8782/activity?x=%20#part",
            "https://bücher.example/straße?q=grün#oben",
        )
        for (destination in valid) assertEquals(destination, destination, safeTranscriptUrl(destination))
        val links = listOf(InlineSpan.Plain(valid.joinToString(" "))).transcriptLinks()
        assertEquals(valid, links.map { it.destination })
    }

    @Test
    fun `unsafe malformed and hostless destinations have no action`() {
        val invalid = listOf(
            "javascript:alert(1)", "data:text/plain,hello", "file:///sdcard/test", "intent://open", "mailto:a@example.com",
            "/relative", "//example.com/path", "https:///path", "https://", "https://:8782/path",
            "https://example.com:99999/", "https://example.com:abc/", "https://bad_host.example/",
            "https://example.com/%ZZ", "https://example.com/a b", "https://example.com/\npath", "https://example.com/\u007fpath",
        )
        for (destination in invalid) assertNull(destination, safeTranscriptUrl(destination))
    }

    @Test
    fun `copy projection keeps labels and literal code without adding destinations`() {
        val markdown = "[**open** the *sandbox*]($DESTINATION) and `$DESTINATION`\n\n```sh\ncurl $DESTINATION\n```"
        assertEquals("open the sandbox and $DESTINATION\n\ncurl $DESTINATION", parseMarkdown(markdown).replyPlainText())
    }

    @Test
    fun `headings lists and tables retain link ranges`() {
        val blocks = parseMarkdown("# [heading]($DESTINATION)\n\n- [bullet]($DESTINATION)\n\n1. [number]($DESTINATION)\n\n| [head]($DESTINATION) |\n|---|\n| [cell]($DESTINATION) |")
        val inline = listOf(
            (blocks[0] as MarkdownBlock.Heading).spans,
            (blocks[1] as MarkdownBlock.Bullets).items.single(),
            (blocks[2] as MarkdownBlock.Numbered).items.single(),
            (blocks[3] as MarkdownBlock.Table).header.single().spans,
            (blocks[3] as MarkdownBlock.Table).rows.single().single().spans,
        )
        assertTrue(inline.all { it.transcriptLinks().single().destination == DESTINATION })
    }

    @Test
    fun `quoted and heading list labels cannot autolink a refused destination`() {
        for (prefix in listOf("- > ", "- # ", "- > ## ", "3. > ", "3. ### ")) {
            val blocks = parseMarkdown("${prefix}[https://example.org/](javascript:bad)")
            val inline = when (val block = blocks.single()) {
                is MarkdownBlock.Bullets -> block.items.single()
                is MarkdownBlock.Numbered -> block.items.single()
                else -> error("Expected list for $prefix")
            }
            assertEquals(prefix, emptyList<TranscriptLink>(), inline.transcriptLinks())
            assertEquals(prefix, "https://example.org/", inline.spansText())
            assertEquals(prefix, "${if (prefix.startsWith("3.")) "3." else "•"} https://example.org/", blocks.replyPlainText())
        }
    }

    @Test
    fun `quoted list link has only its authored label and explicit destination`() {
        val blocks = parseMarkdown("- > [https://example.org/](https://example.com/)")
        val inline = (blocks.single() as MarkdownBlock.Bullets).items.single()
        assertEquals(listOf(TranscriptLink(0, "https://example.org/".length, "https://example.com/")), inline.transcriptLinks())
        assertEquals("https://example.org/", inline.spansText())
        assertEquals("• https://example.org/", blocks.replyPlainText())
    }

    @Test
    fun `nested quoted list links retain styles ranges and copy separators`() {
        val blocks = parseMarkdown(
            """
            - outer
              > ## [**open**](https://example.com/)
              >
              > [https://example.org/](javascript:bad)
              >
              > - *nested* [child](https://example.net/)
              > - > [**deep**](https://example.edu/)
            """.trimIndent(),
        )
        val inline = (blocks.single() as MarkdownBlock.Bullets).items.single()
        val visible = "outer — open — https://example.org/ — • nested child\n• deep"
        assertEquals(visible, inline.spansText())
        assertEquals("• $visible", blocks.replyPlainText())
        assertEquals(
            listOf("open" to "https://example.com/", "child" to "https://example.net/", "deep" to "https://example.edu/")
                .map { (label, target) -> TranscriptLink(visible.indexOf(label), visible.indexOf(label) + label.length, target) },
            inline.transcriptLinks(),
        )
        assertEquals(listOf(InlineSpan.Strong("open")), inline.filterIsInstance<InlineSpan.Link>().first().children)
        assertTrue(inline.contains(InlineSpan.Emphasis("nested")))
        assertEquals(listOf(InlineSpan.Strong("deep")), inline.filterIsInstance<InlineSpan.Link>().last().children)
    }

    @Test
    fun `image alt URLs are visible literals excluded from bare extraction`() {
        for (image in listOf(
            "![https://example.com/](https://example.org/image.png)",
            "![https://**example.com**/](https://example.org/image.png)",
            "![https://*example.com*/](https://example.org/image.png)",
            "![`https://example.com/`](https://example.org/image.png)",
            "**![https://example.com/](https://example.org/image.png)**",
            "*![https://example.com/](https://example.org/image.png)*",
        )) {
            val blocks = parseMarkdown("🧪 $image then https://example.net/")
            val inline = (blocks.single() as MarkdownBlock.Paragraph).spans
            val visible = "🧪 https://example.com/ then https://example.net/"
            assertEquals(image, visible, inline.spansText())
            assertEquals(image, visible, blocks.replyPlainText())
            assertEquals(
                image,
                listOf(TranscriptLink(visible.indexOf("https://example.net/"), visible.length, "https://example.net/")),
                inline.transcriptLinks(),
            )
        }
    }

    @Test
    fun `explicit image links own alt labels including styled alt`() {
        for (alt in listOf("https://example.com/", "https://**example.com**/", "https://*example.com*/")) {
            for (target in listOf("https://example.net/", "javascript:bad")) {
                val blocks = parseMarkdown("[![$alt](https://example.org/image.png)]($target)")
                val inline = (blocks.single() as MarkdownBlock.Paragraph).spans
                assertEquals("https://example.com/", inline.spansText())
                assertEquals("https://example.com/", blocks.replyPlainText())
                val expected = if (target.startsWith("https:")) {
                    listOf(TranscriptLink(0, "https://example.com/".length, target))
                } else emptyList()
                assertEquals("$alt -> $target", expected, inline.transcriptLinks())
            }
        }
    }

    @Test
    fun `quoted list heading image alt cannot acquire a bare action`() {
        val blocks = parseMarkdown("- outer\n  > # ![https://**example.com**/](https://example.org/image.png)")
        val inline = (blocks.single() as MarkdownBlock.Bullets).items.single()
        assertEquals("outer — https://example.com/", inline.spansText())
        assertEquals("• outer — https://example.com/", blocks.replyPlainText())
        assertEquals(emptyList<TranscriptLink>(), inline.transcriptLinks())
    }

    private companion object {
        const val DESTINATION = "https://example.com:8782/activity?tab=run%20log#latest"
    }
}
