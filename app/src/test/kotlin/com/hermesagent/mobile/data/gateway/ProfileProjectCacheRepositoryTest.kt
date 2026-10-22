package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.profiles.ProfileScope
import com.hermesagent.mobile.ui.chat.SidebarCatalogProjection
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileProjectCacheRepositoryTest {
    private class Rpc : GatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        override fun close() = Unit
        val calls = mutableListOf<Pair<String, String?>>()
        var treeDelay = 0L
        var sessionDelay = 0L
        var detailDelay = 0L
        var treeGate: CompletableDeferred<Unit>? = null
        var detailGate: CompletableDeferred<Unit>? = null
        var treeFailure: Throwable? = null
        var responseRevision = 0
        override suspend fun request(method: String, params: JsonObject): JsonElement {
            val profile = params["profile"]?.jsonPrimitive?.contentOrNull
            val route = profile ?: "launch"
            val owner = profile ?: "research"
            calls += method to profile
            return when (method) {
                "session.list" -> { delay(sessionDelay); Json.parseToJsonElement("""{"sessions":[]}""") }
                "projects.tree" -> {
                    delay(treeDelay); treeGate?.await(); treeFailure?.let { throw it }
                    Json.parseToJsonElement("""{"projects":[{"id":"p-$route","label":"$route tree $responseRevision","sessionCount":2,"previewSessions":[{"id":"s-$route","profile":"$owner","title":"$route preview $responseRevision"}],"repos":[]}],"active_id":"p-$route"}""")
                }
                "projects.project_sessions" -> {
                    delay(detailDelay); detailGate?.await()
                    Json.parseToJsonElement("""{"project":{"id":"p-$route","label":"$route detail project $responseRevision","sessionCount":2,"previewSessions":[],"repos":[{"id":"repo-$route","groups":[{"id":"group-$route","sessions":[{"id":"s-$route","profile":"$owner","title":"$route member $responseRevision"},{"id":"detail-$route","profile":"$owner","title":"$route detail $responseRevision"}]}]}]}}""")
                }
                else -> JsonObject(emptyMap())
            }
        }
    }

    @Test fun `project fixtures hydrate producer shaped nonempty catalogs`() = runTest {
        val cache = SessionCache()
        val rpc = Rpc()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        repository.refreshProjects()
        val catalog = cache.state.value.projects
        assertEquals("alpha tree 0", catalog.projects.getValue("p-alpha").label)
        assertEquals("p-alpha", catalog.activeProjectId)
        assertEquals(2, catalog.projects.getValue("p-alpha").sessionCount)
        assertEquals(listOf("s-alpha"), catalog.memberships["p-alpha"])
        assertEquals("alpha preview 0", catalog.projects.getValue("p-alpha").previewSessions.single().title)
        repository.openProject("p-alpha")
        assertEquals(listOf("s-alpha", "detail-alpha"), cache.state.value.projects.memberships["p-alpha"])
        assertEquals("alpha detail 0", cache.session("detail-alpha")?.title)
    }

    @Test fun `omitted launch project ownership survives real REST refresh and inactive rehome`() = runTest {
        for (sessionsFirst in listOf(false, true)) {
            val cache = SessionCache()
            val rpc = Rpc().apply { treeDelay = 1_000 }
            var listHasRows = sessionsFirst
            val requests = mutableListOf<GatewayHttpRequest>()
            val http = object : GatewayHttp {
                override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
                    requests += request
                    val rows = if (listHasRows) """{"id":"s-launch","profile":"research","title":"REST launch","last_active":2}""" else ""
                    return if (request.path == "api/sessions") GatewayHttpResult.Success(200,
                        """{"sessions":[$rows],"total":${if (listHasRows) 1 else 0},"limit":60,"offset":0}""".toByteArray())
                    else GatewayHttpResult.Rejected(404, "unavailable")
                }
            }
            val repository = LiveGatewaySessionRepository(cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope,
                http = { http }, restContext = EmptyCoroutineContext) { 1_000 }
            runCurrent() // The real readSessionPages REST path runs before the held tree.
            if (sessionsFirst) assertEquals("research", cache.session("s-launch")?.remoteProfile)
            advanceTimeBy(1_000); runCurrent()
            val projection = SidebarCatalogProjection()
            val launch = ProfileScope(activeProfile = "")
            fun overview() = projection.derive(cache.state.value, 0, launch, null, "").projects.single()
            fun members() = projection.derive(cache.state.value, 0, launch, "p-launch", "").scopedSessions
            listHasRows = true
            repository.refreshSessions()
            assertEquals(listOf("s-launch"), overview().previewSessions.map { it.id })
            assertEquals(listOf("s-launch"), members().map { it.id })
            repository.openProject("p-launch")
            repository.refreshSessions() // Repeat the real read after detail hydration.
            assertTrue(requests.filter { it.path == "api/sessions" }.all { "profile" !in it.query })
            assertTrue(rpc.calls.none { it.first == "session.list" })
            assertEquals("research", cache.session("s-launch")?.remoteProfile)
            assertEquals("research", cache.session("detail-launch")?.remoteProfile)
            assertEquals(listOf("s-launch"), overview().previewSessions.map { it.id })
            assertEquals("research", overview().previewSessions.single().remoteProfile)
            assertEquals(listOf("s-launch", "detail-launch"), members().map { it.id })
            repository.setProfileRouting(ProfileRouting(activeProfile = "default"))
            repository.refreshProjects()
            val explicitDefault = cache.state.value.projects
            assertEquals(setOf("p-default"), explicitDefault.projects.keys)
            assertTrue(projection.derive(cache.state.value, 0, ProfileScope(), "p-launch", "").selectedProject == null)
            val launchRow = cache.session("s-launch")!!
            cache.upsertSession(launchRow.copy(remoteProfile = "default"))
            assertEquals("research", overview().previewSessions.single().remoteProfile)
            assertEquals(listOf("s-launch", "detail-launch"), members().map { it.id })
            assertEquals("research", members().first().remoteProfile)
            assertEquals("default", cache.session("s-launch")?.remoteProfile)
            cache.upsertSession(launchRow)
            cache.rehomeSession("s-launch", cache.session("s-launch")!!.copy(id = "tip-launch"), emptyList())
            assertSame(explicitDefault, cache.state.value.projectsForScope("default"))
            assertEquals(listOf("tip-launch"), overview().previewSessions.map { it.id })
            assertEquals(listOf("tip-launch", "detail-launch"), members().map { it.id })
            assertEquals("research", cache.session("tip-launch")?.remoteProfile)
            repository.setProfileRouting(ProfileRouting())
            assertEquals(listOf("tip-launch", "detail-launch"), cache.state.value.projects.memberships["p-launch"])
        }
    }

    @Test fun `queued project work retains original scope and stale ABA responses cannot publish`() = runTest {
        for (missing in listOf(false, true)) {
            val cache = SessionCache()
            val rpc = Rpc()
            val repository = LiveGatewaySessionRepository(cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
            repository.refreshProjects()
            val alpha = cache.state.value.projects
            val gate = CompletableDeferred<Unit>()
            rpc.treeGate = gate
            backgroundScope.launch { runCatching { repository.refreshProjects() } }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
            backgroundScope.launch { runCatching { repository.refreshProjects() } }
            backgroundScope.launch { runCatching { repository.openProject("p-beta") } }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
            val calls = rpc.calls.size
            rpc.responseRevision = 1
            if (missing) rpc.treeFailure = GatewayRpcError(-32601, "Method not found")
            gate.complete(Unit)
            runCurrent()
            assertEquals("Queued beta work must not dispatch as alpha", calls, rpc.calls.size)
            assertSame(alpha, cache.state.value.projects)
        }
    }

    @Test fun `endpoint reset fences late project publication even with the same client`() = runTest {
        val cache = SessionCache()
        val rpc = Rpc()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        repository.refreshProjects()
        val gate = CompletableDeferred<Unit>()
        rpc.treeGate = gate
        backgroundScope.launch { runCatching { repository.refreshProjects() } }
        runCurrent()
        cache.resetForEndpointSwitch()
        gate.complete(Unit)
        runCurrent()
        assertTrue(cache.state.value.projects.projects.isEmpty())
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        assertTrue(cache.state.value.projects.projects.isEmpty())
    }

    @Test fun `stale tree detail and unavailable replies cannot overwrite restored snapshot`() = runTest {
        for (kind in listOf("tree", "detail", "unavailable")) {
            val detail = kind == "detail"
            val cache = SessionCache()
            val rpc = Rpc()
            val repository = LiveGatewaySessionRepository(cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
            repository.refreshProjects()
            repository.openProject("p-alpha")
            val alpha = cache.state.value.projects
            assertEquals(listOf("s-alpha", "detail-alpha"), alpha.memberships["p-alpha"])
            val gate = CompletableDeferred<Unit>()
            if (detail) rpc.detailGate = gate else rpc.treeGate = gate
            backgroundScope.launch { runCatching {
                if (detail) repository.openProject("p-alpha") else repository.refreshProjects()
            } }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
            repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
            rpc.responseRevision = 1
            if (kind == "unavailable") rpc.treeFailure = GatewayRpcError(-32601, "Method not found")
            gate.complete(Unit)
            runCurrent()
            assertSame(alpha, cache.state.value.projects)
        }
    }

    @Test fun `transient tree failure preserves restored backend truth and retries on next visit`() = runTest {
        val cache = SessionCache()
        val rpc = Rpc()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        repository.refreshProjects()
        repository.openProject("p-alpha")
        val alpha = cache.state.value.projects
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        rpc.treeFailure = GatewayRpcException("temporary failure")
        assertNotNull(runCatching { repository.refreshProjects() }.exceptionOrNull())
        assertSame(alpha, cache.state.value.projects)
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        rpc.treeFailure = null
        val calls = rpc.calls.count { it.first == "projects.tree" }
        repository.refreshProjects()
        assertEquals(calls + 1, rpc.calls.count { it.first == "projects.tree" })
        assertEquals(true, cache.state.value.projects.available)
    }

    @Test fun `fresh read after endpoint reset retains installed routing provenance`() = runTest {
        val cache = SessionCache()
        val rpc = Rpc()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        repository.refreshProjects()
        cache.resetForEndpointSwitch()
        repository.refreshProjects()
        assertEquals("alpha", cache.state.value.activeProjectScope)
        assertEquals(setOf("p-alpha"), cache.state.value.projectsForScope("alpha").projects.keys)
        assertTrue(cache.state.value.projectsForScope(null).projects.isEmpty())
    }

    @Test fun `cold baseline waits for modeled session tree and detail responses`() = runTest {
        val cache = SessionCache()
        val rpc = Rpc().apply { sessionDelay = 1_000; treeDelay = 2_000; detailDelay = 3_000 }
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
        runCurrent()
        assertTrue(cache.state.value.projects.projects.isEmpty())
        advanceTimeBy(2_999); runCurrent()
        assertTrue(cache.state.value.projects.projects.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(setOf("p-launch"), cache.state.value.projects.projects.keys)
        assertEquals(listOf("s-launch"), cache.state.value.projects.memberships["p-launch"])
        backgroundScope.launch { repository.openProject("p-launch") }
        runCurrent()
        assertTrue(cache.state.value.projects.hydratedProjectIds.isEmpty())
        advanceTimeBy(3_000); runCurrent()
        assertEquals(setOf("p-launch"), cache.state.value.projects.hydratedProjectIds)
        assertEquals(listOf("s-launch", "detail-launch"), cache.state.value.projects.memberships["p-launch"])
    }

    @Test fun `return restores catalog and hydration synchronously without rewinding live rows`() = runTest {
        val cache = SessionCache()
        val rpc = Rpc()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
        runCurrent()
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        repository.refreshProjects()
        repository.openProject("p-alpha")
        val alpha = cache.state.value.projects
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
        assertNull(cache.state.value.projects.available)
        repository.refreshProjects()
        assertEquals(setOf("p-beta"), cache.state.value.projects.projects.keys)
        assertEquals(listOf("s-beta"), cache.state.value.projects.memberships["p-beta"])
        cache.upsertSession(SessionSummary("s-beta", "live beta", "", 2_000, remoteProfile = "beta"))
        cache.appendEntry("s-beta", UserTurn("turn", "live", 2_000))
        val sessions = cache.state.value.sessions
        val transcripts = cache.state.value.transcripts
        val calls = rpc.calls.size
        repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
        assertSame(alpha, cache.state.value.projects)
        assertSame(sessions, cache.state.value.sessions)
        assertSame(transcripts, cache.state.value.transcripts)
        assertEquals(calls, rpc.calls.size)
        rpc.treeDelay = 2_000
        backgroundScope.launch { repository.refreshProjects() }
        runCurrent()
        assertSame(alpha, cache.state.value.projects)
        advanceTimeBy(2_000); runCurrent()
        assertEquals("alpha", rpc.calls.last { it.first == "projects.tree" }.second)
    }
}
