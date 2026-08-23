package com.hermesagent.mobile.data.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageRefsTest {
    @Test
    fun `split lifts trailing image ref lines out of the body`() {
        val (body, refs) = ImageRefLines.split(
            "what is this\n@image:/home/d/images/upload_2026.png\n@image:/home/d/images/shot 2.jpg",
        )
        assertEquals("what is this", body)
        assertEquals(
            listOf("@image:/home/d/images/upload_2026.png", "@image:/home/d/images/shot 2.jpg"),
            refs,
        )
    }

    @Test
    fun `split leaves ref-free text untouched`() {
        val (body, refs) = ImageRefLines.split("plain message")
        assertEquals("plain message", body)
        assertEquals(emptyList<String>(), refs)
    }

    @Test
    fun `split strips a lone screenshot placeholder when refs are present`() {
        val (body, refs) = ImageRefLines.split(
            "[screenshot]\n@image:/home/d/images/upload_2026.png",
        )
        assertEquals("", body)
        assertEquals(listOf("@image:/home/d/images/upload_2026.png"), refs)
    }

    @Test
    fun `pathOf unquotes all three gateway quote forms`() {
        assertEquals("/home/d/images/plain.png", ImageRefLines.pathOf("@image:/home/d/images/plain.png"))
        assertEquals("/home/d/images/a b.png", ImageRefLines.pathOf("@image:`/home/d/images/a b.png`"))
        assertEquals("/home/d/images/a b.png", ImageRefLines.pathOf("@image:\"/home/d/images/a b.png\""))
        assertEquals("/home/d/images/a b.png", ImageRefLines.pathOf("@image:'/home/d/images/a b.png'"))
    }

    @Test
    fun `pathOf refuses malformed lines`() {
        assertNull(ImageRefLines.pathOf("@image:"))
        assertNull(ImageRefLines.pathOf("just text"))
    }

    @Test
    fun `formatRef mirrors the gateway quoting rules`() {
        assertEquals("@image:/home/d/plain.png", ImageRefLines.formatRef("/home/d/plain.png"))
        assertEquals("@image:`/home/d/a b.png`", ImageRefLines.formatRef("/home/d/a b.png"))
        // A path containing a backtick falls to the next quote char.
        assertEquals("@image:\"/home/d/a`b.png\"", ImageRefLines.formatRef("/home/d/a`b.png"))
    }
}
