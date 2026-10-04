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
    fun `warmed profile scope changes alone expose the correct sessions`() {
        val cache = SessionCache().apply { replaceProjectOverview(listOf(project), "p") }
        val projection = SidebarCatalogProjection()
        fun derive(scope: ProfileScope) = projection.derive(cache.state.value, 0, scope, null, "", Locale.US)
        val default = derive(ProfileScope())
        assertEquals(listOf("a"), default.scopedSessions.map { it.id })
        assertSame(default, derive(ProfileScope()))
        val named = derive(ProfileScope("work"))
        assertEquals(listOf("b"), named.scopedSessions.map { it.id })
        assertSame(named, derive(ProfileScope("work")))
        val unified = derive(ProfileScope(showAllProfiles = true))
        assertEquals(listOf("a", "b"), unified.scopedSessions.map { it.id })
    }

    @Test
    fun `archive alone updates a warmed preview without hiding it`() {
        val cache = SessionCache().apply { replaceProjectOverview(listOf(project), "p") }
        val projection = SidebarCatalogProjection()
        fun derive() = projection.derive(cache.state.value, 0, ProfileScope(), null, "", Locale.US)
        val before = derive()
        assertSame(before, derive())
        assertNotEquals(true, before.projects.single().previewSessions.single().archived)
        cache.upsertSession(a.copy(archived = true))
        val after = derive().projects.single().previewSessions.single()
        assertEquals("a", after.id)
        assertEquals(true, after.archived)
        assertNotEquals(true, after.hidden)
    }

    @Test
    fun `rehome alone replaces a warmed selected membership`() {
        val cache = SessionCache().apply { replaceProjectOverview(listOf(project), "p") }
        val projection = SidebarCatalogProjection()
        fun derive() = projection.derive(cache.state.value, 0, ProfileScope(), "p", "", Locale.US)
        val before = derive()
        assertEquals(listOf("a"), before.scopedSessions.map { it.id })
        assertSame(before, derive())
        cache.rehomeSession("a", a.copy(id = "tip", title = "Compressed tip"), emptyList())
        assertEquals(listOf("tip"), derive().scopedSessions.map { it.id })
        assertEquals("Compressed tip", derive().scopedSessions.single().title)
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
