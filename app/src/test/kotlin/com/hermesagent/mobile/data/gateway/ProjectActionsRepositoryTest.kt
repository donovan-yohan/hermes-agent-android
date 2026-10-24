package com.hermesagent.mobile.data.gateway

import kotlinx.serialization.json.*
import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectActionsRepositoryTest {
    private class Rpc : EndpointDispatchingGatewayRpcClient {
        override val events = emptyFlow<GatewayEvent>()
        override fun close() = Unit
        val writes = mutableListOf<Pair<String, JsonObject>>()
        var beforeDispatch: (() -> Unit)? = null
        var responseGate: CompletableDeferred<Unit>? = null
        var failRead = false
        var writeFailure: Throwable? = null
        var auto = false
        override suspend fun request(method: String, params: JsonObject): JsonElement = when (method) {
            "projects.tree" -> {
                check(!failRead)
                Json.parseToJsonElement("""{"projects":[{"id":"p","label":"Project","path":"/workspace/project","isAuto":$auto}]}""")
            }
            else -> JsonObject(emptyMap())
        }
        override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
            beforeDispatch?.invoke()
            check(dispatch { if (method != "projects.tree") writes += method to params; true })
            if (method == "projects.tree") return request(method, params)
            responseGate?.await()
            writeFailure?.let { throw it }
            return Json.parseToJsonElement("""{"project":{"id":"adopted"}}""")
        }
    }

    @Test fun `rediscovered auto has fresh actions while adopted incarnation stays retired`() = runTest {
        val cache = SessionCache()
        var saved = false
        var failTree = false
        val writes = mutableListOf<String>()
        val rpc = object : EndpointDispatchingGatewayRpcClient {
            override val events = emptyFlow<GatewayEvent>()
            override fun close() = Unit
            override suspend fun request(method: String, params: JsonObject): JsonElement {
                check(method == "projects.tree"); check(!failTree)
                return Json.parseToJsonElement(if (saved)
                    """{"projects":[{"id":"p_saved","label":"Project","path":"/workspace/project","isAuto":false}]}"""
                else """{"projects":[{"id":"/workspace/project","label":"Project","path":"/workspace/project","isAuto":true}]}""")
            }
            override suspend fun requestAtEndpointDispatch(method: String, params: JsonObject, dispatch: (() -> Boolean) -> Boolean): JsonElement {
                check(dispatch { true })
                if (method == "projects.tree") return request(method, params)
                writes += method
                if (method == "projects.create") { saved = true; return Json.parseToJsonElement("""{"project":{"id":"p_saved"}}""") }
                if (method == "projects.delete") saved = false
                return JsonObject(emptyMap())
            }
        }
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 0 }
        runCurrent(); repo.refreshProjects()
        val original = cache.state.value.projects.projects.getValue("/workspace/project")
        val old = repo.projectEditor(original)!!
        val sibling = repo.projectEditor(original)!!
        failTree = true
        assertFalse(old.execute(ProjectOperation.Icon("rocket")).catalogRefreshed)
        assertFalse(sibling.isCurrent())
        assertTrue(runCatching { sibling.execute(ProjectOperation.Icon("beaker")) }.isFailure)
        // No authoritative absence yet: even a newly requested handle must not adopt twice.
        assertFalse(repo.projectEditor(original)!!.isCurrent())
        repo.setProfileRouting(ProfileRouting(activeProfile = "other")); repo.setProfileRouting(ProfileRouting())
        failTree = false; repo.refreshProjects()
        repo.projectEditor(cache.state.value.projects.projects.getValue("p_saved"))!!.execute(ProjectOperation.Delete)
        val resurfaced = cache.state.value.projects.projects.getValue("/workspace/project")
        assertEquals(original, resurfaced)
        val fresh = repo.projectEditor(resurfaced)!!
        assertTrue("Fresh rediscovery must be actionable", fresh.isCurrent())
        assertFalse(old.isCurrent()); assertFalse(sibling.isCurrent())
        assertTrue(runCatching { sibling.execute(ProjectOperation.Color(null)) }.isFailure)
        fresh.execute(ProjectOperation.Color("hsl(30 68% 58%)"))
        assertEquals(listOf("projects.create", "projects.delete", "projects.create"), writes)
    }

    @Test fun `rename is scoped exact and accepted read failure never invites replay`() = runTest {
        val cache = SessionCache()
        val rpc = Rpc()
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 0 }
        runCurrent()
        repo.setProfileRouting(ProfileRouting(activeProfile = "alpha")); repo.refreshProjects()
        val editor = repo.projectEditor(cache.state.value.projects.projects.getValue("p"))
        assertNotNull("No live project editor", editor)
        rpc.failRead = true
        val result = editor!!.execute(ProjectOperation.Rename(" New name "))
        assertEquals("projects.update", rpc.writes.single().first)
        assertEquals(Json.parseToJsonElement("""{"id":"p","name":"New name","profile":"alpha"}"""), rpc.writes.single().second)
        assertFalse(result.catalogRefreshed)
        assertTrue(result.scopeCurrent)
    }

    @Test fun `all explicit actions emit only their exact fields including empty clear`() = runTest {
        for ((operation, method, fields) in listOf(
            Triple(ProjectOperation.Color(null), "projects.update", "\"color\":\"\""),
            Triple(ProjectOperation.Icon("rocket"), "projects.update", "\"icon\":\"rocket\""),
            Triple(ProjectOperation.Icon(null), "projects.update", "\"icon\":\"\""),
            Triple(ProjectOperation.AddFolder("/workspace/other", "Other", true), "projects.add_folder", "\"path\":\"/workspace/other\",\"label\":\"Other\",\"is_primary\":true"),
            Triple(ProjectOperation.SetActive, "projects.set_active", ""),
            Triple(ProjectOperation.Delete, "projects.delete", ""),
        )) {
            val cache = SessionCache(); val rpc = Rpc()
            val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 0 }
            runCurrent(); repo.refreshProjects()
            val editor = repo.projectEditor(cache.state.value.projects.projects.getValue("p"))!!
            editor.execute(operation)
            assertEquals(method, rpc.writes.single().first)
            assertEquals(Json.parseToJsonElement("""{"id":"p"${if (fields.isEmpty()) "" else ",$fields"}}"""), rpc.writes.single().second)
        }
    }

    @Test fun `auto appearance adopts without activation and retires original handles even on read failure`() = runTest {
        val cache = SessionCache(); val rpc = Rpc().apply { auto = true }
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 0 }
        runCurrent(); repo.refreshProjects()
        val row = cache.state.value.projects.projects.getValue("p")
        val first = repo.projectEditor(row)!!; val second = repo.projectEditor(row)!!
        rpc.failRead = true
        val result = first.execute(ProjectOperation.Color("hsl(30 68% 58%)"))
        assertEquals("adopted", result.projectId)
        assertEquals("projects.create", rpc.writes.single().first)
        assertEquals(Json.parseToJsonElement("""{"name":"Project","folders":["/workspace/project"],"primary_path":"/workspace/project","use":false,"color":"hsl(30 68% 58%)"}"""), rpc.writes.single().second)
        assertFalse(first.isCurrent()); assertFalse(second.isCurrent())
        assertTrue(runCatching { second.execute(ProjectOperation.Icon("rocket")) }.isFailure)
        assertEquals(1, rpc.writes.size)
    }

    @Test fun `project ownership is checked at actual send including profile ABA and endpoint invalidation`() = runTest {
        for (change in listOf("profile", "endpoint")) {
            val cache = SessionCache(); val rpc = Rpc(); val fence = EndpointDispatchFence()
            val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope, endpointDispatchFence = fence) { 0 }
            runCurrent(); repo.refreshProjects()
            val editor = repo.projectEditor(cache.state.value.projects.projects.getValue("p"))!!
            rpc.beforeDispatch = {
                if (change == "profile") {
                    repo.setProfileRouting(ProfileRouting(activeProfile = "beta")); repo.setProfileRouting(ProfileRouting())
                } else fence.invalidate()
            }
            assertTrue(runCatching { editor.execute(ProjectOperation.Delete) }.isFailure)
            assertTrue("Stale write reached wire: $change", rpc.writes.isEmpty())
        }
    }

    @Test fun `accepted response after profile ABA cannot publish or replay adoption`() = runTest {
        val cache = SessionCache(); val rpc = Rpc().apply { auto = true }
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 0 }
        runCurrent(); repo.refreshProjects()
        val editor = repo.projectEditor(cache.state.value.projects.projects.getValue("p"))!!
        val original = cache.state.value.projects
        rpc.responseGate = CompletableDeferred()
        var outcome: ProjectEditOutcome? = null
        backgroundScope.launch { outcome = editor.execute(ProjectOperation.Icon("rocket")) }
        runCurrent(); assertEquals(1, rpc.writes.size)
        repo.setProfileRouting(ProfileRouting(activeProfile = "beta")); repo.setProfileRouting(ProfileRouting())
        rpc.responseGate!!.complete(Unit); runCurrent()
        assertEquals(false, outcome?.scopeCurrent)
        assertSame(original, cache.state.value.projects)
        assertFalse(editor.isCurrent())
    }

    @Test fun `missing or refused mutations do not refresh or return accepted outcome`() = runTest {
        for (failure in listOf(GatewayRpcError(-32601, "Method not found"), GatewayRpcError(5062, "Missing project"))) {
            val cache = SessionCache(); val rpc = Rpc()
            val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 0 }
            runCurrent(); repo.refreshProjects()
            val catalog = cache.state.value.projects
            rpc.writeFailure = failure
            assertTrue(runCatching { repo.projectEditor(catalog.projects.getValue("p"))!!.execute(ProjectOperation.Delete) }.isFailure)
            assertSame(catalog, cache.state.value.projects)
        }
    }

    @Test fun `surface revocation at actual send refuses even an otherwise current owner`() = runTest {
        val cache = SessionCache(); val rpc = Rpc()
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)), MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 0 }
        runCurrent(); repo.refreshProjects()
        val editor = repo.projectEditor(cache.state.value.projects.projects.getValue("p"))!!
        var mounted = true
        rpc.beforeDispatch = { mounted = false }
        assertTrue(runCatching { editor.execute(ProjectOperation.Delete) { mounted } }.isFailure)
        assertTrue(rpc.writes.isEmpty())
    }

    @Test fun `repository exposes owner bound project editor`() {
        assertTrue("Missing owner-bound project actions", GatewaySessionRepository::class.java.methods.any { it.name == "projectEditor" })
    }

    @Test fun `project appearance survives the authoritative parser`() {
        val project = parseProjectOverview(Json.parseToJsonElement("""{"projects":[{"id":"p","label":"Project","path":"/workspace/project","color":"hsl(30 68% 58%)","icon":"rocket"}]}"""), 0).projects.single()
        assertTrue(project.toString(), project.toString().contains("color=hsl(30 68% 58%)"))
        assertTrue(project.toString(), project.toString().contains("icon=rocket"))
    }
}
