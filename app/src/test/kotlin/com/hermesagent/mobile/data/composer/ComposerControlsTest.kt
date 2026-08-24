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
        assertEquals(ReasoningEffort.Unknown("invented"), ReasoningEffort.fromWire("invented"))
        assertEquals(FastMode.Unknown("turbo"), FastMode.fromWire("turbo"))
        assertTrue(ComposerModelSelection("model").isSpecified)
    }

    @Test
    fun `reasoning scale mirrors the backend VALID_REASONING_EFFORTS`() {
        assertEquals(
            listOf("minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            ReasoningEffort.LEVELS.map { it.wireValue },
        )
        ReasoningEffort.LEVELS.forEach { level ->
            assertEquals(level, ReasoningEffort.fromWire(level.wireValue))
        }
        assertEquals(ReasoningEffort.Ultra, ReasoningEffort.fromWire("ULTRA"))
        assertEquals(ReasoningEffort.Max, ReasoningEffort.fromWire(" max "))
    }

    @Test
    fun `partial session control events overlay without erasing earlier authority`() {
        val selection = ComposerModelSelection("model/session", "provider/session")
        val combined = SessionComposerControls(
            durableId = "session-a",
            selection = selection,
            hasSelection = true,
        ).overlay(
            SessionComposerControls(
                durableId = "session-a",
                reasoning = ReasoningEffort.High,
                hasReasoning = true,
            ),
        )

        assertEquals(
            ModelControlsSnapshot(selection, ReasoningEffort.High, FastMode.Fast),
            combined.applyTo(ModelControlsSnapshot(fast = FastMode.Fast)),
        )
    }
}
