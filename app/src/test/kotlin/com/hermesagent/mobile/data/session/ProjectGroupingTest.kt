package com.hermesagent.mobile.data.session

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGroupingTest {
    private val locale = Locale.UK

    @Test
    fun `overview keeps Home first then applies Desktop project tiers`() {
        val projects = listOf(
            project("auto-active", "Auto active", auto = true, count = 9, active = 900),
            project("empty-explicit", "Empty explicit", count = 0, active = 0),
            project("active", "Chosen", count = 1, active = 100),
            project("recent", "Recent", count = 1, active = 800),
            project("same-b", "Same", count = 1, active = 700),
            project("same-a", "Same", count = 1, active = 700),
            project("home", "Home", home = true, count = 2, active = 50),
            project("empty-auto", "Empty auto", auto = true, count = 0, active = 1_000),
        )

        assertEquals(
            listOf(
                "home",
                "active",
                "recent",
                "same-a",
                "same-b",
                "empty-explicit",
                "auto-active",
                "empty-auto",
            ),
            sortProjectsForOverview(projects, activeProjectId = "active", locale = locale).map { it.id },
        )
    }

    @Test
    fun `project search matches project labels and authoritative previews`() {
        val project = project("mobile", "Hermes Mobile").copy(
            previewSessions = listOf(
                SessionSummary("s1", "Gateway history", "Project drill-in", 1),
            ),
        )

        assertTrue(project.matchesProjectQuery("MOBILE", locale))
        assertTrue(project.matchesProjectQuery("history", locale))
        assertTrue(project.matchesProjectQuery("drill-IN", locale))
        assertFalse(project.matchesProjectQuery("desktop", locale))
    }

    private fun project(
        id: String,
        label: String,
        auto: Boolean = false,
        home: Boolean = false,
        count: Int = 0,
        active: Long = 0,
    ) = ProjectSummary(
        id = id,
        label = label,
        path = if (home) null else "/work/$id",
        isAuto = auto,
        isHome = home,
        sessionCount = count,
        lastActiveAtMillis = active,
    )
}
