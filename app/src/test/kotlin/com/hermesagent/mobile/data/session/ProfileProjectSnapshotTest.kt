package com.hermesagent.mobile.data.session

import org.junit.Assert.*
import org.junit.Test

class ProfileProjectSnapshotTest {
    private val row = SessionSummary("s", "Session", "", 1_000, remoteProfile = "alpha")
    private val project = ProjectSummary("p", "Project", "/synthetic/p", previewSessions = listOf(row))

    @Test fun `foreign raw id rehome cannot rewrite another owners cached preview`() {
        val cache = SessionCache()
        cache.activateProjectScope("alpha")
        cache.replaceProjectOverview(listOf(project), "p")
        cache.replaceProjectDetails(project, listOf(row))
        val alpha = cache.state.value.projects
        cache.activateProjectScope("beta")
        cache.upsertSession(row.copy(remoteProfile = "beta"))
        cache.rehomeSession("s", row.copy(id = "tip", remoteProfile = "beta"), emptyList())
        assertSame(alpha, cache.state.value.projectsForScope("alpha"))
    }

    @Test fun `tombstone removes cached membership even when raw slot was already rehomed by another owner`() {
        val cache = SessionCache()
        cache.activateProjectScope("alpha")
        cache.replaceProjectOverview(listOf(project), "p")
        cache.replaceProjectDetails(project, listOf(row))
        cache.activateProjectScope("beta")
        cache.rehomeSession("s", row.copy(id = "tip", remoteProfile = "beta"), emptyList())
        assertNull(cache.session("s"))
        cache.removeSession("s")
        val alpha = cache.state.value.projectsForScope("alpha")
        assertTrue(alpha.memberships.getValue("p").isEmpty())
        assertTrue(alpha.projects.getValue("p").previewSessions.isEmpty())
    }

    @Test fun `omitted launch and explicit default have separate known empty snapshots and no op identity`() {
        val cache = SessionCache()
        cache.replaceProjectOverview(listOf(project), "p")
        val launch = cache.state.value.projects
        cache.activateProjectScope("default")
        assertNull(cache.state.value.projects.available)
        cache.replaceProjectOverview(emptyList(), null)
        val emptyDefault = cache.state.value.projects
        assertEquals(true, emptyDefault.available)
        val before = cache.state.value
        cache.replaceProjectOverview(emptyList(), null)
        cache.activateProjectScope("default")
        assertSame(before, cache.state.value)
        cache.activateProjectScope(null)
        assertSame(launch, cache.state.value.projects)
        cache.activateProjectScope("default")
        assertSame(emptyDefault, cache.state.value.projects)
        cache.resetForEndpointSwitch()
        assertTrue(cache.state.value.projectSnapshots.isEmpty())
        cache.activateProjectScope(null)
        assertNull(cache.state.value.projects.available)
        cache.activateProjectScope("default")
        assertNull(cache.state.value.projects.available)
    }

    @Test fun `rehome and tombstone update inactive authoritative memberships and previews`() {
        val cache = SessionCache()
        cache.activateProjectScope("alpha")
        cache.replaceProjectOverview(listOf(project), "p")
        cache.replaceProjectDetails(project, listOf(row))
        cache.activateProjectScope("beta")
        cache.rehomeSession("s", row.copy(id = "tip"), emptyList())
        cache.activateProjectScope("alpha")
        assertEquals(listOf("tip"), cache.state.value.projects.memberships["p"])
        assertEquals(listOf("tip"), cache.state.value.projects.projects.getValue("p").previewSessions.map { it.id })
        cache.activateProjectScope("beta")
        cache.removeSession("tip")
        cache.activateProjectScope("alpha")
        assertTrue(cache.state.value.projects.memberships.getValue("p").isEmpty())
        assertTrue(cache.state.value.projects.projects.getValue("p").previewSessions.isEmpty())
        assertEquals(setOf("p"), cache.state.value.projects.hydratedProjectIds)
        val before = cache.state.value
        cache.removeSession("tip")
        assertSame(before, cache.state.value)
    }
}
