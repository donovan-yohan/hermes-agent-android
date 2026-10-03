package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SidebarCatalogProjectionTest {
    private val a = SessionSummary("a", "Alpha", "", 2_000)
    private val b = SessionSummary("b", "Beta", "", 1_000, remoteProfile = "work")
    private val project = ProjectSummary("p", "Project", "/synthetic/p", previewSessions = listOf(a, b))

    @Test
    fun `transcript snapshots reuse scoped list and catalog but authoritative inputs invalidate`() {
        val cache = SessionCache().apply { replaceProjectOverview(listOf(project), "p") }
        val projection = SidebarCatalogProjection()
        fun derive(scope: ProfileScope = ProfileScope(), id: String? = null, query: String = "",
            endpoint: Long = 0, locale: Locale = Locale.US) =
            projection.derive(cache.state.value, endpoint, scope, id, query, locale)
        val initial = derive()
        assertEquals(listOf("a"), initial.scopedSessions.map { it.id })
        assertEquals(listOf("a"), initial.projects.single().previewSessions.map { it.id })
        repeat(20) {
            cache.appendEntry("a", com.hermesagent.mobile.data.session.UserTurn("turn-$it", "text", it.toLong()))
            val reused = projection.derive(cache.state.value, 0, ProfileScope(), null, "", Locale.US)
            assertSame(initial, reused)
            assertSame(initial.scopedSessions, reused.scopedSessions)
        }
        assertNotSame(initial, derive(query = "Alpha"))
        assertTrue(derive(query = "missing").projects.isEmpty())

        assertEquals(listOf("b"), derive(ProfileScope("work")).scopedSessions.map { it.id })
        assertEquals(listOf("a", "b"), derive(ProfileScope(showAllProfiles = true)).scopedSessions.map { it.id })
        val selected = derive(id = "p")
        assertEquals("p", selected.selectedProject?.id)
        assertTrue(selected.projects.isEmpty())
        cache.upsertSession(a.copy(hidden = true, archived = true))
        assertTrue(derive().projects.single().previewSessions.isEmpty())
        cache.rehomeSession("a", a.copy(id = "tip"), emptyList())
        assertEquals(listOf("tip"), derive(id = "p").scopedSessions.map { it.id })
        assertEquals("tip", cache.state.value.rehomes["a"])
        cache.removeSession("tip")
        assertTrue(derive(id = "p").scopedSessions.isEmpty())
        val beforeEndpoint = derive()
        assertNotSame(beforeEndpoint, derive(endpoint = 1))
        cache.resetForEndpointSwitch()
        assertTrue(derive(endpoint = cache.endpointGeneration.value).projects.isEmpty())
    }

    @Test
    fun `locale alone invalidates matching and ordering`() {
        val cache = SessionCache().apply {
            replaceProjectOverview(listOf(
                ProjectSummary("i", "I", "/synthetic/i"),
                ProjectSummary("z", "Z", "/synthetic/z"),
            ), null)
        }
        val projection = SidebarCatalogProjection()
        fun derive(query: String, locale: Locale) =
            projection.derive(cache.state.value, 0, ProfileScope(), null, query, locale)
        val english = derive("", Locale.US)
        val turkish = derive("", Locale.forLanguageTag("tr"))
        assertNotSame(english, turkish)
        assertEquals(listOf("i", "z"), english.projects.map { it.id })
        assertEquals(listOf("z", "i"), turkish.projects.map { it.id })
        val englishMatch = derive("i", Locale.US)
        val turkishMatch = derive("i", Locale.forLanguageTag("tr"))
        assertEquals(listOf("i"), englishMatch.projects.map { it.id })
        assertTrue(turkishMatch.projects.isEmpty())
    }
}
