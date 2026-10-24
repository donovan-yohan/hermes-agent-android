package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.connections.StorageEndpoint
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.prefs.*
import com.hermesagent.mobile.data.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectActionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val auto = ProjectSummary("auto", "Auto repository", "/workspace/auto", isAuto = true)
    private val explicit = ProjectSummary("explicit", "Project", "/workspace/project")
    private class Store : HiddenProjectsStore {
        val disk = mutableMapOf<HiddenProjectsScope, Set<String>>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        var failRead = false
        var writeGate: CompletableDeferred<Unit>? = null
        var writeCount = 0
        override suspend fun readHiddenProjects(scope: HiddenProjectsScope): Set<String> { gate?.await(); check(!failRead); return disk[scope].orEmpty() }
        override suspend fun writeHiddenProjects(scope: HiddenProjectsScope, ids: Set<String>) { writeCount++; writeGate?.await(); gate?.await(); check(!fail); disk[scope] = ids }
    }
    private inner class Fixture(val store: Store = Store()) {
        val endpoint = MutableStateFlow<StorageEndpoint?>(StorageEndpoint("row-address", 0))
        val cache = SessionCache().apply { activateProjectScope("default"); replaceProjectOverview(listOf(auto, explicit), null) }
        val writes = mutableListOf<ProjectOperation>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        val repo = object : GatewaySessionRepository {
            override val connectionState = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
            override val pendingInputs = MutableStateFlow<Map<PendingInputKey, PendingInputRequest>>(emptyMap())
            override fun setProfileRouting(routing: ProfileRouting) { cache.activateProjectScope(routing.activeProfile) }
            override fun projectEditor(project: ProjectSummary): ProjectEditor = object : ProjectEditor {
                val generation = cache.endpointGeneration.value
                override fun isCurrent() = cache.endpointGeneration.value == generation
                override suspend fun execute(operation: ProjectOperation): ProjectEditOutcome {
                    writes += operation; gate?.await(); check(!fail)
                    return ProjectEditOutcome(project.id, true, isCurrent())
                }
            }
            override suspend fun refreshSessions() = Unit
            override suspend fun openSession(durableId: String) = durableId
            override suspend fun openSession(durableId: String, profile: String) = durableId
            override suspend fun createSession(workspacePath: String?): String = error("unused")
            override suspend fun submit(durableId: String, text: String): GatewaySubmitOutcome = error("unused")
            override suspend fun interrupt(durableId: String) = Unit
        }
        val model = ChatViewModel(cache, repo, TransientSidebarViewStore(SidebarGrouping.Project), hiddenProjectsStore = store,
            storageEndpoint = endpoint, clock = { 0 }, bucketLabel = SessionBucketLabel { "Synthetic" })
    }
    private fun TestScope.collect(f: Fixture) { backgroundScope.launch { f.model.uiState.collect {} }; runCurrent() }
    private fun token(f: Fixture) = f.model.uiState.value.projectManagement.dialog!!.token

    @Test fun `held hide reconciles returning owner before sibling write`() = hiddenWriteAba(restore = false)
    @Test fun `held restore reconciles returning owner before sibling write`() = hiddenWriteAba(restore = true)

    private fun hiddenWriteAba(restore: Boolean) = runTest(dispatcher) {
        val owner = HiddenProjectsScope("row-address", "default")
        val other = HiddenProjectsScope("row-address", "other")
        val sibling = auto.copy(id = "sibling", label = "Sibling")
        val f = Fixture()
        if (restore) f.store.disk[owner] = setOf(auto.id)
        f.store.disk[other] = setOf("other-only")
        f.cache.replaceProjectOverview(listOf(auto, sibling, explicit), null)
        collect(f)
        f.store.writeGate = CompletableDeferred()
        if (restore) f.model.restoreHiddenProject(auto.id) else {
            f.model.openProjectActions(auto); runCurrent(); f.model.hideProject(token(f))
        }
        runCurrent(); assertEquals(1, f.store.writeCount)
        f.model.selectProfile("other"); runCurrent()
        f.model.selectProfile("default"); runCurrent()
        f.store.writeGate!!.complete(Unit); runCurrent()
        assertEquals(!restore, auto.id in f.store.disk[owner].orEmpty())
        assertEquals("Returning projection must reflect committed preference", restore, f.model.uiState.value.projects.any { it.id == auto.id })
        assertEquals(!restore, f.model.uiState.value.projectManagement.hiddenProjects.any { it.id == auto.id })
        f.model.refreshSessionNavigation(); runCurrent()
        assertEquals(restore, f.model.uiState.value.projects.any { it.id == auto.id })
        f.model.openProjectActions(sibling); runCurrent(); f.model.hideProject(token(f)); runCurrent()
        assertEquals((if (restore) emptySet() else setOf(auto.id)) + sibling.id, f.store.disk[owner])
        assertEquals(setOf("other-only"), f.store.disk[other])
        assertEquals(2, f.store.writeCount)
    }

    @Test fun `hide persists before success keeps raw catalog and restores after undo expiry and restart`() = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        f.model.openProjectActions(auto); runCurrent()
        f.store.gate = CompletableDeferred()
        f.model.hideProject(token(f)); runCurrent()
        assertTrue(f.model.uiState.value.projects.any { it.id == auto.id })
        f.store.gate!!.complete(Unit); runCurrent()
        assertFalse(f.model.uiState.value.projects.any { it.id == auto.id })
        assertTrue(auto.id in f.cache.state.value.projects.projects)
        assertEquals(setOf(auto.id), f.store.disk[HiddenProjectsScope("row-address", "default")])
        advanceTimeBy(8_001); runCurrent()
        assertNull(f.model.uiState.value.projectManagement.undo)
        val restarted = Fixture(f.store); collect(restarted)
        assertEquals(auto, restarted.model.uiState.value.projectManagement.hiddenProjects.single())
        restarted.model.restoreHiddenProject(auto.id); runCurrent()
        assertTrue(restarted.model.uiState.value.projects.any { it.id == auto.id })
        assertTrue(f.store.disk[HiddenProjectsScope("row-address", "default")].orEmpty().isEmpty())
    }

    @Test fun `hidden preference loading does not flash autos and owners isolate exact routes and address`() = runTest(dispatcher) {
        val store = Store().apply { gate = CompletableDeferred(); disk[HiddenProjectsScope("row-address", "default")] = setOf("auto") }
        val f = Fixture(store); collect(f)
        assertFalse(f.model.uiState.value.projects.any { it.isAuto })
        assertTrue(f.model.uiState.value.projectManagement.hiddenLoading)
        store.gate!!.complete(Unit); runCurrent()
        assertEquals(listOf(auto), f.model.uiState.value.projectManagement.hiddenProjects)
        f.cache.resetForEndpointSwitch(); f.endpoint.value = StorageEndpoint("row-edited-address", 1)
        f.cache.activateProjectScope("default"); f.cache.replaceProjectOverview(listOf(auto, explicit), null); runCurrent()
        assertTrue(f.model.uiState.value.projects.any { it.id == auto.id })
        assertNotEquals(HiddenProjectsScope("row-address", null), HiddenProjectsScope("row-address", "default"))
    }

    @Test fun `failed hide preserves visibility and failed restore keeps recovery`() = runTest(dispatcher) {
        val f = Fixture(); collect(f); f.store.fail = true
        f.model.openProjectActions(auto); runCurrent(); f.model.hideProject(token(f)); runCurrent()
        assertTrue(f.model.uiState.value.projects.any { it.id == auto.id })
        assertNotNull(f.model.uiState.value.projectManagement.dialog?.error)
        f.store.fail = false; f.model.hideProject(token(f)); runCurrent()
        f.store.fail = true; f.model.restoreHiddenProject(auto.id); runCurrent()
        assertEquals(listOf(auto), f.model.uiState.value.projectManagement.hiddenProjects)
        assertNotNull(f.model.uiState.value.projectManagement.hiddenError)
    }

    @Test fun `busy prevents duplicate writes and set active preserves foreground transcript`() = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        f.cache.upsertSession(SessionSummary("chat", "Chat", "", 0)); f.cache.appendEntry("chat", UserTurn("turn", "Keep", 0))
        f.model.selectSession("chat"); runCurrent()
        val before = f.model.uiState.value.activeSessionId
        f.model.openProjectActions(explicit); runCurrent(); val token = token(f)
        f.gate = CompletableDeferred(); f.model.executeProjectAction(token, ProjectOperation.SetActive); f.model.executeProjectAction(token, ProjectOperation.SetActive)
        runCurrent(); assertEquals(listOf(ProjectOperation.SetActive), f.writes)
        f.gate!!.complete(Unit); runCurrent()
        assertEquals(before, f.model.uiState.value.activeSessionId)
        assertEquals("Keep", (f.cache.state.value.transcripts["chat"]!!.single() as UserTurn).text)
    }

    @Test fun `failed hidden load can retry by reopening navigation without flashing autos`() = runTest(dispatcher) {
        val store = Store().apply { failRead = true }
        val f = Fixture(store); collect(f)
        assertNotNull(f.model.uiState.value.projectManagement.hiddenError)
        assertFalse(f.model.uiState.value.projects.any { it.isAuto })
        store.failRead = false; f.model.refreshSessionNavigation(); runCurrent()
        assertNull(f.model.uiState.value.projectManagement.hiddenError)
        assertTrue(f.model.uiState.value.projects.any { it.isAuto })
    }

    @Test fun `dismiss callback from old dialog cannot close a replacement`() = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        f.model.openProjectActions(auto); runCurrent(); val old = token(f)
        f.model.openProjectActions(explicit); runCurrent(); val current = token(f)
        f.model.dismissProjectActions(old)
        f.model.projectActionPage(old, ProjectActionPage.Delete)
        f.model.executeProjectAction(old, ProjectOperation.Delete)
        runCurrent(); assertEquals(current, token(f))
    }

    @Test fun `restore rejects retained token after synchronous profile ABA`() = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        f.model.openProjectActions(auto); runCurrent(); f.model.hideProject(token(f)); runCurrent()
        val owner = f.model.uiState.value.projectManagement.hiddenOwnerToken
        f.model.selectProfile("other"); f.model.selectProfile("default")
        f.model.restoreHiddenProject(auto.id, owner); runCurrent()
        assertEquals(setOf(auto.id), f.store.disk[HiddenProjectsScope("row-address", "default")])
    }

    @Test fun `disposed rename success releases its pending dialog`() = disposedRename(fail = false, beforeDispatch = false)
    @Test fun `disposed rename failure releases its pending dialog`() = disposedRename(fail = true, beforeDispatch = false)
    @Test fun `rename revoked before coroutine dispatch releases its pending dialog`() = disposedRename(fail = false, beforeDispatch = true)

    private fun disposedRename(fail: Boolean, beforeDispatch: Boolean) = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        var mounted = true
        f.model.openProjectActions(explicit, { mounted }); runCurrent(); val old = token(f)
        f.gate = CompletableDeferred(); f.fail = fail
        f.model.executeProjectAction(old, ProjectOperation.Rename("Updated"))
        if (!beforeDispatch) { runCurrent(); assertEquals(1, f.writes.size); assertTrue(f.model.uiState.value.projectManagement.dialog!!.busy) }
        mounted = false
        f.gate!!.complete(Unit); runCurrent()
        f.model.openProjectActions(explicit, { true }); runCurrent()
        val fresh = f.model.uiState.value.projectManagement.dialog!!
        assertNotEquals("Settled retired dialog must not block a fresh surface", old, fresh.token)
        assertFalse(fresh.busy)
        f.fail = false
        f.model.executeProjectAction(fresh.token, ProjectOperation.Rename("Fresh")); runCurrent()
        assertNull(f.model.uiState.value.projectManagement.dialog)
        assertEquals(if (beforeDispatch) 1 else 2, f.writes.size)
    }

    @Test fun `old completion cannot dismiss or unlock a newer busy dialog`() = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        f.model.openProjectActions(explicit); runCurrent(); val old = token(f)
        val oldGate = CompletableDeferred<Unit>(); f.gate = oldGate
        f.model.executeProjectAction(old, ProjectOperation.Rename("Old")); runCurrent()
        f.model.dismissProjectActions(old)
        f.model.openProjectActions(explicit); runCurrent(); val fresh = token(f)
        val freshGate = CompletableDeferred<Unit>(); f.gate = freshGate
        f.model.executeProjectAction(fresh, ProjectOperation.Rename("Fresh")); runCurrent()
        oldGate.complete(Unit); runCurrent()
        assertEquals(fresh, token(f)); assertTrue(f.model.uiState.value.projectManagement.dialog!!.busy)
        freshGate.complete(Unit); runCurrent()
        assertNull(f.model.uiState.value.projectManagement.dialog); assertEquals(2, f.writes.size)
    }

    @Test fun `held hide completion preserves another owners load error`() = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        f.model.openProjectActions(auto); runCurrent()
        f.store.writeGate = CompletableDeferred(); f.model.hideProject(token(f)); runCurrent()
        f.store.failRead = true
        f.model.selectProfile("other"); runCurrent()
        val before = f.model.uiState.value.projectManagement
        assertNotNull(before.hiddenError)
        f.store.writeGate!!.complete(Unit); runCurrent()
        assertEquals(before, f.model.uiState.value.projectManagement)
        assertEquals(setOf(auto.id), f.store.disk[HiddenProjectsScope("row-address", "default")])
        assertFalse(f.store.disk.containsKey(HiddenProjectsScope("row-address", "other")))
    }

    @Test fun `held restore completion preserves returning owners load error`() = runTest(dispatcher) {
        val f = Fixture(); f.store.disk[HiddenProjectsScope("row-address", "default")] = setOf(auto.id); collect(f)
        f.store.writeGate = CompletableDeferred(); f.model.restoreHiddenProject(auto.id); runCurrent()
        f.model.selectProfile("other"); runCurrent()
        f.store.failRead = true
        f.model.selectProfile("default"); runCurrent()
        f.store.writeGate!!.complete(Unit); runCurrent()
        assertNotNull(f.model.uiState.value.projectManagement.hiddenError)
        assertFalse(f.model.uiState.value.projects.any { it.id == auto.id })
        assertTrue(f.store.disk[HiddenProjectsScope("row-address", "default")].orEmpty().isEmpty())
        f.store.failRead = false; f.model.refreshSessionNavigation(); runCurrent()
        assertNull(f.model.uiState.value.projectManagement.hiddenError)
        assertTrue(f.model.uiState.value.projects.any { it.id == auto.id })
    }

    @Test fun `disposed hide success releases its pending dialog`() = disposedHide(fail = false, beforeDispatch = false)
    @Test fun `disposed hide failure releases its pending dialog`() = disposedHide(fail = true, beforeDispatch = false)
    @Test fun `hide revoked before coroutine dispatch releases its pending dialog`() = disposedHide(fail = false, beforeDispatch = true)

    private fun disposedHide(fail: Boolean, beforeDispatch: Boolean) = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        var mounted = true
        f.model.openProjectActions(auto, { mounted }); runCurrent(); val old = token(f)
        f.store.writeGate = CompletableDeferred(); f.store.fail = fail
        f.model.hideProject(old)
        if (!beforeDispatch) { runCurrent(); assertEquals(1, f.store.writeCount) }
        mounted = false
        f.store.writeGate!!.complete(Unit); runCurrent()
        f.model.openProjectActions(explicit); runCurrent()
        assertNotEquals(old, token(f)); assertFalse(f.model.uiState.value.projectManagement.dialog!!.busy)
        assertEquals(if (beforeDispatch) 0 else 1, f.store.writeCount)
    }

    @Test fun `home and retained disposed surface callbacks cannot dispatch`() = runTest(dispatcher) {
        val f = Fixture(); collect(f)
        f.model.openProjectActions(explicit.copy(isHome = true)); runCurrent(); assertNull(f.model.uiState.value.projectManagement.dialog)
        var mounted = true
        f.model.openProjectActions(explicit, { mounted }); runCurrent(); val token = token(f)
        mounted = false; f.model.executeProjectAction(token, ProjectOperation.Delete); runCurrent()
        assertTrue(f.writes.isEmpty()); assertNull(f.model.uiState.value.projectManagement.dialog)
    }
}
