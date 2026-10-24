package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.connections.StorageEndpoint
import com.hermesagent.mobile.data.gateway.*
import com.hermesagent.mobile.data.prefs.*
import com.hermesagent.mobile.data.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectAdoptionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private class Wire : GatewayRpcWire {
        lateinit var rpc: CorrelatedGatewayRpc
        val frames = mutableListOf<JsonObject>()
        var holdList = false
        var failedList = false
        var success = false
        override fun close() = Unit
        override fun send(text: String): Boolean {
            val frame = Json.parseToJsonElement(text).jsonObject
            frames += frame
            when (frame["method"]?.jsonPrimitive?.content) {
                "projects.tree" -> reply(frame, "\"result\":{\"projects\":[{\"id\":\"auto\",\"label\":\"Project\",\"path\":\"/workspace/project\",\"isAuto\":true}]}")
                "projects.create" -> if (success) reply(frame, "\"result\":{\"project\":{\"id\":\"saved\"}}")
                "projects.list" -> if (!holdList) replyList(frame)
            }
            return true
        }
        fun replyList(frame: JsonObject) = reply(frame, if (failedList) "\"error\":{\"code\":-32601,\"message\":\"missing\"}" else
            "\"result\":{\"projects\":[{\"id\":\"saved\",\"name\":\"Actual saved project\",\"primary_path\":\"/workspace/project\",\"archived\":false,\"folders\":[{\"path\":\"/workspace/project\"}]}]}")
        private fun reply(frame: JsonObject, body: String) = rpc.receive("""{"jsonrpc":"2.0","id":${frame["id"]},$body}""")
        val creates get() = frames.filter { it["method"] == JsonPrimitive("projects.create") }
        val lists get() = frames.filter { it["method"] == JsonPrimitive("projects.list") }
    }
    private inner class Fixture(val scope: TestScope, val store: TransientProjectAdoptionStore = TransientProjectAdoptionStore()) {
        val endpoint = MutableStateFlow<StorageEndpoint?>(StorageEndpoint("row/address", 0))
        val cache = SessionCache()
        val wire = Wire().apply { rpc = CorrelatedGatewayRpc(this, { 100 }, dispatcher) }
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(wire.rpc), scope.backgroundScope, projectAdoptionStore = store, projectStorageEndpoint = endpoint) { 0 }
        val model = ChatViewModel(cache, repo, TransientSidebarViewStore(SidebarGrouping.Project), storageEndpoint = endpoint,
            clock = { 0 }, bucketLabel = SessionBucketLabel { "Synthetic" })
        val state get() = model.uiState.value.projectManagement
        val owner = ProjectAdoptionScope("row/address", "default")
        val row get() = cache.state.value.projects.projects.getValue("auto")
        suspend fun start() {
            scope.backgroundScope.launch { model.uiState.collect {} }
            scope.runCurrent(); repo.refreshProjects(); scope.runCurrent()
        }
        suspend fun seed(): ProjectAdoptionAttempt {
            val attempt = ProjectAdoptionAttempt("prepared", "auto", "/workspace/project", "Project")
            store.compareProjectAdoptions(owner, emptyList(), listOf(attempt))
            return attempt
        }
        fun open(surface: () -> Boolean = { true }): Long {
            model.openProjectRecovery(surface, state.hiddenOwnerToken)
            scope.runCurrent()
            return state.recovery!!.token
        }
    }

    @Test fun `actual RPC timeout publishes visible recovery feedback and never repeats appearance`() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start()
            f.model.openProjectActions(f.row); runCurrent()
            val token = f.state.dialog!!.token
            f.model.executeProjectAction(token, ProjectOperation.Icon("rocket"))
            f.model.executeProjectAction(token, ProjectOperation.Color("blue"))
            runCurrent(); assertEquals(1, f.wire.creates.size); assertTrue(f.state.dialog!!.busy)
            advanceTimeBy(101); runCurrent()
            assertEquals(ProjectActionPage.Menu, f.state.dialog!!.page)
            assertFalse(f.state.dialog!!.busy)
            assertEquals("Project changes need review. Open Review project changes before another attempt.", f.state.dialog!!.error)
            assertEquals("/workspace/project", f.model.projectPath(token))
            f.model.dismissProjectActions(token)
            val review = f.open()
            val attempt = f.state.recovery!!.attempts.single()
            f.model.selectProjectRecovery(review, attempt.attemptId)
            f.model.refreshProjectRecovery(review); runCurrent()
            assertEquals("Actual saved project", f.state.recovery!!.projects!!.single().name)
            f.model.allowProjectAttempt(review); runCurrent()
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size) // Explicit review required.
            f.model.reviewProjectRecovery(review)
            val before = f.wire.frames.size
            f.model.allowProjectAttempt(review); f.model.allowProjectAttempt(review); runCurrent()
            assertNull(f.state.recovery)
            assertEquals(before, f.wire.frames.size)
            assertTrue(f.store.readProjectAdoptions(f.owner).isEmpty())
            assertEquals(1, f.wire.creates.size)
            f.wire.success = true
            f.model.openProjectActions(f.row); runCurrent()
            f.model.executeProjectAction(f.state.dialog!!.token, ProjectOperation.Color(null)); runCurrent()
            assertEquals(2, f.wire.creates.size)
            assertFalse("icon" in f.wire.creates.last()["params"]!!.jsonObject)
        } finally { f.wire.rpc.close() }
    }

    @Test fun `restart exposes pending recovery with missing row and held refresh blocks duplicate authorization`() = runTest(dispatcher) {
        val store = TransientProjectAdoptionStore()
        val original = Fixture(this, store)
        val f = Fixture(this, store)
        try {
            original.start(); original.seed()
            f.start(); f.cache.replaceProjectOverview(emptyList(), null); runCurrent()
            val token = f.open()
            assertEquals("prepared", f.state.recovery!!.attempts.single().attemptId)
            f.model.selectProjectRecovery(token, "prepared")
            f.wire.holdList = true
            f.model.refreshProjectRecovery(token); f.model.refreshProjectRecovery(token); runCurrent()
            assertTrue(f.state.recovery!!.busy); assertFalse(f.state.recovery!!.reviewed)
            f.model.reviewProjectRecovery(token); f.model.allowProjectAttempt(token); runCurrent()
            assertEquals(1, f.wire.lists.size); assertEquals(0, f.wire.creates.size)
            f.wire.replyList(f.wire.lists.single()); runCurrent()
            assertFalse(f.state.recovery!!.busy); assertNotNull(f.state.recovery!!.projects)
            f.wire.holdList = false; f.wire.failedList = true
            f.model.refreshProjectRecovery(token); runCurrent()
            assertNull(f.state.recovery!!.projects)
            assertEquals("Projects could not be loaded. Refresh projects to try again.", f.state.recovery!!.error)
            f.model.reviewProjectRecovery(token); f.model.allowProjectAttempt(token); runCurrent()
            assertEquals(1, store.readProjectAdoptions(f.owner).size)
        } finally { original.wire.rpc.close(); f.wire.rpc.close() }
    }

    @Test fun `hidden preference failure cannot strand independent durable adoption recovery`() = runTest(dispatcher) {
        val cache = SessionCache()
        val wire = Wire().apply { rpc = CorrelatedGatewayRpc(this, { 100 }, dispatcher) }
        val store = TransientProjectAdoptionStore()
        val owner = ProjectAdoptionScope("row/address", "default")
        store.compareProjectAdoptions(owner, emptyList(), listOf(ProjectAdoptionAttempt("prepared", "auto", "/workspace/project", "Project")))
        val endpoint = MutableStateFlow<StorageEndpoint?>(StorageEndpoint("row/address", 0))
        val repo = LiveGatewaySessionRepository(cache, MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(wire.rpc), backgroundScope, projectAdoptionStore = store, projectStorageEndpoint = endpoint) { 0 }
        val hidden = object : HiddenProjectsStore {
            override suspend fun readHiddenProjects(scope: HiddenProjectsScope): Set<String> = error("Synthetic preference failure")
            override suspend fun writeHiddenProjects(scope: HiddenProjectsScope, ids: Set<String>) = error("unused")
        }
        val model = ChatViewModel(cache, repo, TransientSidebarViewStore(SidebarGrouping.Project), storageEndpoint = endpoint,
            hiddenProjectsStore = hidden, clock = { 0 })
        try {
            backgroundScope.launch { model.uiState.collect {} }; runCurrent()
            val state = model.uiState.value.projectManagement
            assertNotNull(state.hiddenError)
            model.openProjectRecovery({ true }, state.hiddenOwnerToken); runCurrent()
            assertEquals("prepared", model.uiState.value.projectManagement.recovery?.attempts?.single()?.attemptId)
        } finally { wire.rpc.close() }
    }

    @Test fun `stale surface and dismissed callbacks cannot authorize newer review`() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); f.seed()
            var mounted = true
            val first = f.open { mounted }
            f.model.selectProjectRecovery(first, "prepared"); f.model.refreshProjectRecovery(first); runCurrent()
            f.model.reviewProjectRecovery(first)
            mounted = false
            f.model.allowProjectAttempt(first); runCurrent()
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            f.model.dismissProjectRecovery(first)
            val second = f.open()
            f.model.selectProjectRecovery(second, "prepared"); f.model.refreshProjectRecovery(second); runCurrent()
            f.model.reviewProjectRecovery(second)
            f.model.allowProjectAttempt(first); f.model.dismissProjectRecovery(first); runCurrent()
            assertEquals(second, f.state.recovery!!.token)
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            f.model.allowProjectAttempt(second); runCurrent()
            assertNull(f.state.recovery); assertTrue(f.store.readProjectAdoptions(f.owner).isEmpty())
        } finally { f.wire.rpc.close() }
    }
}
