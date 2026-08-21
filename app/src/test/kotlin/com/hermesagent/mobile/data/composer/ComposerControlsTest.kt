package com.hermesagent.mobile.data.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerControlsTest {
    @Test
    fun `references serialize to canonical remote wire text`() {
        assertEquals("@url:`https://example.test/path`", ComposerReference.Url("https://example.test/path").wireText)
        assertEquals("@file:`src/my file.kt`", ComposerReference.File("src/my file.kt").wireText)
        assertEquals("@folder:`src/main/`", ComposerReference.Folder("src/main/").wireText)
        assertEquals("@session:`default/durable-id`", ComposerReference.Session("default/durable-id").wireText)
        assertEquals("@git:`HEAD~1`", ComposerReference.Git("HEAD~1").wireText)
        assertEquals("@diff", ComposerReference.Simple("diff").wireText)
    }

    @Test
    fun `unknown Gateway control values remain representable`() {
        assertEquals(ReasoningEffort.Unknown("ultra"), ReasoningEffort.fromWire("ultra"))
        assertEquals(FastMode.Unknown("turbo"), FastMode.fromWire("turbo"))
        assertTrue(ComposerModelSelection("model").isSpecified)
    }
}
