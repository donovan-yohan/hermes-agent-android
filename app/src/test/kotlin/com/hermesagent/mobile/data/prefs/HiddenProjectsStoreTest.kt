package com.hermesagent.mobile.data.prefs

import org.junit.Assert.*
import org.junit.Test

class HiddenProjectsStoreTest {
    @Test fun `scoped keys cannot collide and hidden encoding preserves literal ids`() {
        val scopes = listOf(HiddenProjectsScope("row", null), HiddenProjectsScope("row", "default"),
            HiddenProjectsScope("row", "null"), HiddenProjectsScope("row/other", null), HiddenProjectsScope("row", "a/b"))
        assertEquals(scopes.size, scopes.map { it.preferenceKey() }.toSet().size)
        val ids = setOf("/workspace/a,b", "quoted\"id", "unicode-λ")
        assertEquals(ids, decodeHiddenProjects(encodeHiddenProjects(ids)))
        assertTrue(decodeHiddenProjects(null).isEmpty())
        assertTrue(runCatching { decodeHiddenProjects("corrupt") }.isFailure)
    }

    @Test fun `preferences expose durable scoped hidden projects`() {
        assertTrue("Missing hidden project persistence", HermesPreferences::class.java.methods.any { it.name == "readHiddenProjects" })
    }
}
