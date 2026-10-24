package com.hermesagent.mobile.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ProjectAppearanceTest {
    @Test fun `pinned swatches and icon order match Desktop`() {
        assertEquals((0 until 12).map { "hsl(${it * 30} 68% 58%)" }, ProjectAppearance.swatches)
        assertEquals(listOf("folder-library", "repo", "rocket", "beaker", "flame", "star-full", "heart", "zap", "target", "lightbulb", "tools", "device-desktop", "device-mobile", "terminal", "dashboard", "globe", "broadcast", "cloud", "database", "package", "book", "organization", "bug", "shield", "key", "gift", "telescope", "home"), ProjectAppearance.icons.keys.toList())
        assertEquals(28, ProjectAppearance.icons.size)
    }
}
