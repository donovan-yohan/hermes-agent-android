package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
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
        override suspend fun request(method: String, params: JsonObject): JsonElement {
            val profile = params["profile"]?.jsonPrimitive?.contentOrNull
            calls += method to profile
            return when (method) {
                "session.list" -> { delay(sessionDelay); Json.parseToJsonElement("""{"sessions":[]}""") }
                "projects.tree" -> {
                    delay(treeDelay); treeGate?.await(); treeFailure?.let { throw it }
                    Json.parseToJsonElement("""{"projects":[{"id":"p","name":"${profile ?: "launch"}","session_count":1,"sessions":[{"id":"s","title":"preview"}]}],"active_project_id":"p"}""")
                }
                "projects.project_sessions" -> {
                    delay(detailDelay); detailGate?.await()
                    Json.parseToJsonElement("""{"project":{"id":"p","name":"${profile ?: "launch"}","session_count":1},"sessions":[{"id":"s","title":"detail"}]}""")
                }
                else -> JsonObject(emptyMap())
            }
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
            backgroundScope.launch { runCatching { repository.openProject("p") } }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
            val calls = rpc.calls.size
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

    @Test fun `stale detail success and tree unavailable cannot overwrite restored snapshot`() = runTest {
        for (detail in listOf(false, true)) {
            val cache = SessionCache()
            val rpc = Rpc()
            val repository = LiveGatewaySessionRepository(cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_000 }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
            repository.refreshProjects()
            repository.openProject("p")
            val alpha = cache.state.value.projects
            val gate = CompletableDeferred<Unit>()
            if (detail) rpc.detailGate = gate else rpc.treeGate = gate
            backgroundScope.launch { runCatching {
                if (detail) repository.openProject("p") else repository.refreshProjects()
            } }
            runCurrent()
            repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
            repository.setProfileRouting(ProfileRouting(activeProfile = "alpha"))
            if (!detail) rpc.treeFailure = GatewayRpcError(-32601, "Method not found")
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
        repository.openProject("p")
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
        assertEquals(setOf("p"), cache.state.value.projectsForScope("alpha").projects.keys)
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
        assertEquals(setOf("p"), cache.state.value.projects.projects.keys)
        backgroundScope.launch { repository.openProject("p") }
        runCurrent()
        assertTrue(cache.state.value.projects.hydratedProjectIds.isEmpty())
        advanceTimeBy(3_000); runCurrent()
        assertEquals(setOf("p"), cache.state.value.projects.hydratedProjectIds)
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
        repository.openProject("p")
        val alpha = cache.state.value.projects
        repository.setProfileRouting(ProfileRouting(activeProfile = "beta"))
        assertNull(cache.state.value.projects.available)
        repository.refreshProjects()
        cache.upsertSession(SessionSummary("s", "live beta", "", 2_000, remoteProfile = "beta"))
        cache.appendEntry("s", UserTurn("turn", "live", 2_000))
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
