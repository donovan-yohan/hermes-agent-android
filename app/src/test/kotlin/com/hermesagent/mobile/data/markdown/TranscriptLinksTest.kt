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

    private companion object {
        const val DESTINATION = "https://example.com:8782/activity?tab=run%20log#latest"
    }
}
