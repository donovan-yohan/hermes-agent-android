package com.hermesagent.mobile.ui.chat

import androidx.lifecycle.viewModelScope
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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Production correlation/repository/consumer; synthetic socket failures do not prove host commits. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class ProjectActionsTransportFailureTest(
    private val operation: ProjectOperation,
    private val method: String,
    private val fields: String?,
) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun operations(): List<Array<Any?>> = listOf(
            arrayOf(ProjectOperation.Rename("Renamed"), "projects.update", "\"name\":\"Renamed\""),
            arrayOf(ProjectOperation.Color("blue"), "projects.update", "\"color\":\"blue\""),
            arrayOf(ProjectOperation.Icon("rocket"), "projects.update", "\"icon\":\"rocket\""),
            arrayOf(ProjectOperation.AddFolder("/workspace/added"), "projects.add_folder", "\"path\":\"/workspace/added\",\"is_primary\":false"),
            arrayOf(ProjectOperation.SetActive, "projects.set_active", null),
            arrayOf(ProjectOperation.Delete, "projects.delete", null),
        )
    }

    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class Wire : GatewayRpcWire {
        lateinit var rpc: CorrelatedGatewayRpc
        val frames = mutableListOf<JsonObject>()
        var behavior = "hold"
        var treeBehavior = "success"
        override fun close() = Unit
        override fun send(text: String): Boolean {
            val frame = Json.parseToJsonElement(text).jsonObject
            val tree = frame["method"] == JsonPrimitive("projects.tree")
            if (!tree && behavior == "false") return false
            frames += frame
            if (tree) {
                when (treeBehavior) {
                    "success" -> reply(frame, "\"result\":{\"projects\":[{\"id\":\"saved\",\"label\":\"Project\",\"path\":\"/workspace/project\",\"isAuto\":false}]}")
                    else -> fail(frame, treeBehavior)
                }
            } else fail(frame, behavior)
            return true
        }
        fun fail(frame: JsonObject, failure: String) {
            when (failure) {
                "disconnect" -> rpc.connectionClosed("Synthetic transport failure", GatewayCloseCause.TransportFailure)
                "throw" -> error("Synthetic send threw after accepting frame")
                "malformed" -> reply(frame, "\"unexpected\":true")
                // Pinned projects handlers can commit, then fail their response readback (5061).
                "internal" -> reply(frame, "\"error\":{\"code\":5061,\"message\":\"Synthetic post-commit read failure\"}")
                "missing" -> reply(frame, "\"error\":{\"code\":-32601,\"message\":\"Synthetic missing method\"}")
                "success" -> reply(frame, "\"result\":{}")
            }
        }
        fun reply(frame: JsonObject, body: String) = rpc.receive("""{"jsonrpc":"2.0","id":${frame["id"]},$body}""")
        val mutations get() = frames.filter { it["method"]?.jsonPrimitive?.content in setOf("projects.update", "projects.add_folder", "projects.set_active", "projects.delete") }
    }

    private inner class Fixture(val scope: TestScope) {
        val endpoint = MutableStateFlow<StorageEndpoint?>(StorageEndpoint("synthetic-row/address", 0))
        val cache = SessionCache()
        val wire = Wire().apply { rpc = CorrelatedGatewayRpc(this, { 100 }, dispatcher) }
        val repo = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(wire.rpc), scope.backgroundScope,
            projectStorageEndpoint = endpoint) { 0 }
        val model = ChatViewModel(cache, repo, TransientSidebarViewStore(SidebarGrouping.Project),
            storageEndpoint = endpoint, clock = { 0 }, bucketLabel = SessionBucketLabel { "Synthetic" })
        val state get() = model.uiState.value.projectManagement
        val row get() = cache.state.value.projects.projects.getValue("saved")
        suspend fun start() {
            scope.backgroundScope.launch { model.uiState.collect {} }
            scope.runCurrent(); repo.refreshProjects(); scope.runCurrent()
            assertFalse(row.isAuto)
        }
        fun open(current: () -> Boolean = { true }): Long {
            model.openProjectActions(row, current); scope.runCurrent()
            return requireNotNull(state.dialog).token
        }
        fun assertFrame() {
            assertEquals(1, wire.mutations.size)
            assertEquals(JsonPrimitive(method), wire.mutations.single()["method"])
            assertEquals(Json.parseToJsonElement("""{"id":"saved",${fields?.let { "$it," }.orEmpty()}"profile":"default"}"""), wire.mutations.single()["params"])
        }
    }

    @Test fun sentDisconnectIsUnconfirmedWithoutRetainedReplay() = unconfirmed("disconnect")
    @Test fun throwingSendIsUnconfirmedWithoutRetainedReplay() = unconfirmed("throw")
    @Test fun sentMalformedResponseIsUnconfirmedWithoutRetainedReplay() = unconfirmed("malformed")
    @Test fun sentPostCommitShapedErrorIsUnconfirmedWithoutRetainedReplay() = unconfirmed("internal")

    private fun unconfirmed(failure: String) = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val token = f.open()
            if (failure == "throw") f.wire.behavior = failure
            f.model.executeProjectAction(token, operation); runCurrent()
            f.assertFrame() // Each family positively reaches its exact mutation before the failure assertion.
            if (failure != "throw") {
                assertTrue(f.state.dialog!!.busy)
                f.wire.fail(f.wire.mutations.single(), failure); runCurrent()
            }
            val dialog = f.state.dialog!!
            assertEquals("Project change was not confirmed. Reopen Sessions to check it.", dialog.error)
            assertTrue("Done-only consumer state, not retry feedback", dialog.unconfirmed)
            assertFalse(dialog.busy); assertEquals(token, dialog.token)
            assertTrue(currentCoroutineContext().isActive)
            f.wire.behavior = "hold"
            f.model.executeProjectAction(token, operation)
            f.model.executeProjectAction(token, ProjectOperation.AddFolder("/workspace/retained"))
            f.model.projectActionPage(token, ProjectActionPage.Delete)
            runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(dialog, f.state.dialog); assertEquals(1, f.wire.mutations.size)
            f.wire.reply(f.wire.mutations.single(), "\"result\":{}"); runCurrent()
            assertEquals(dialog, f.state.dialog); assertEquals(1, f.wire.mutations.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun repositoryClassifiesAllSentFailuresWithoutRetiringSavedEditors() = runTest(dispatcher) {
        for (failure in listOf("disconnect", "throw", "malformed", "internal")) {
            val f = Fixture(this)
            try {
                f.start(); val editor = f.repo.projectEditor(f.row)!!
                val sibling = f.repo.projectEditor(f.row)!!
                val pending = async { runCatching { editor.execute(operation) }.exceptionOrNull() }
                f.wire.behavior = failure
                runCurrent(); f.assertFrame()
                assertTrue("$failure must be typed uncertainty", pending.await() is ProjectEditUnconfirmed)
                assertTrue(editor.isCurrent()); assertTrue(sibling.isCurrent())
                assertTrue(currentCoroutineContext().isActive)
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun falseSendAndMissingMethodRemainRetryableForEveryFamily() = runTest(dispatcher) {
        for (failure in listOf("false", "missing")) {
            val f = Fixture(this)
            try {
                f.start(); val token = f.open(); f.wire.behavior = failure
                f.model.executeProjectAction(token, operation); runCurrent()
                val dialog = f.state.dialog!!
                assertEquals("Project could not be updated. Check the Gateway and try again.", dialog.error)
                assertFalse(dialog.unconfirmed); assertFalse(dialog.busy)
                assertEquals(if (failure == "false") 0 else 1, f.wire.mutations.size)
                f.wire.behavior = "hold"
                f.model.executeProjectAction(token, operation); runCurrent()
                assertTrue(f.state.dialog!!.busy)
                assertEquals(if (failure == "false") 1 else 2, f.wire.mutations.size)
                assertEquals(JsonPrimitive(method), f.wire.mutations.last()["method"])
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun parentCancellationWinsRacingDisconnectWithoutUncertaintyFeedback() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val token = f.open()
            val parent = f.model.viewModelScope.coroutineContext[Job]!!
            val existing = parent.children.toSet()
            f.model.executeProjectAction(token, operation); runCurrent(); f.assertFrame()
            val action = (parent.children.toSet() - existing).single()
            action.cancel()
            f.wire.rpc.connectionClosed("Synthetic racing disconnect")
            action.join(); runCurrent()
            assertTrue(action.isCancelled); assertNull(f.state.dialog)
            assertNull(f.model.uiState.value.notice)
            advanceTimeBy(101); runCurrent(); assertEquals(1, f.wire.mutations.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun acknowledgedMutationIsNotUncertainWhenReadbackFails() = runTest(dispatcher) {
        for (failure in listOf("disconnect", "throw", "malformed", "internal")) {
            val f = Fixture(this)
            try {
                f.start(); val sibling = f.repo.projectEditor(f.row)!!
                val token = f.open(); f.wire.behavior = "success"; f.wire.treeBehavior = failure
                f.model.executeProjectAction(token, operation); runCurrent(); f.assertFrame()
                assertEquals(JsonPrimitive("projects.tree"), f.wire.frames.last()["method"])
                assertNull(f.state.dialog)
                assertEquals("Project updated. Projects could not be refreshed; reopen Sessions.", f.model.uiState.value.notice?.text)
                assertEquals(operation != ProjectOperation.Delete, sibling.isCurrent())
                f.model.executeProjectAction(token, operation); runCurrent()
                assertEquals(1, f.wire.mutations.size)
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun lateOldFailureAndCompletionCannotClearNewerBusyDialog() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val old = f.open()
            f.model.executeProjectAction(old, operation); runCurrent(); f.assertFrame()
            f.model.dismissProjectActions(old)
            val fresh = f.open()
            f.model.executeProjectAction(fresh, ProjectOperation.Icon("rocket")); runCurrent()
            // The newer action waits on the real repository mutation lane, not a fabricated parallel RPC.
            assertTrue(f.state.dialog!!.busy); assertEquals(1, f.wire.mutations.size)
            f.wire.fail(f.wire.mutations.first(), "malformed"); runCurrent()
            assertEquals(fresh, f.state.dialog!!.token); assertTrue(f.state.dialog!!.busy)
            assertNull(f.state.dialog!!.error); assertEquals(2, f.wire.mutations.size)
            f.wire.reply(f.wire.mutations.first(), "\"result\":{}"); runCurrent()
            f.model.executeProjectAction(old, operation)
            f.model.projectActionPage(old, ProjectActionPage.Delete)
            f.model.dismissProjectActions(old); runCurrent()
            assertEquals(fresh, f.state.dialog!!.token); assertTrue(f.state.dialog!!.busy)
            assertEquals(2, f.wire.mutations.size)
            f.wire.reply(f.wire.mutations.last(), "\"result\":{}"); runCurrent()
            assertNull(f.state.dialog)
        } finally { f.wire.rpc.close() }
    }

    @Test fun dismissingUnconfirmedFailureRequiresFreshChoiceNotOldPayload() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val old = f.open()
            f.model.executeProjectAction(old, operation); runCurrent(); f.assertFrame()
            f.wire.fail(f.wire.mutations.single(), "internal"); runCurrent()
            assertTrue(f.state.dialog!!.unconfirmed)
            f.model.dismissProjectActions(old)
            val fresh = f.open()
            f.model.executeProjectAction(old, operation)
            f.model.projectActionPage(old, ProjectActionPage.Delete); runCurrent()
            assertEquals(fresh, f.state.dialog!!.token); assertEquals(1, f.wire.mutations.size)
            f.wire.behavior = "success"
            f.model.executeProjectAction(fresh, ProjectOperation.Rename("Fresh deliberate choice")); runCurrent()
            assertNull(f.state.dialog); assertEquals(2, f.wire.mutations.size)
            assertEquals(Json.parseToJsonElement("""{"id":"saved","name":"Fresh deliberate choice","profile":"default"}"""), f.wire.mutations.last()["params"])
        } finally { f.wire.rpc.close() }
    }
}
