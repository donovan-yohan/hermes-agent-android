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

/** Real correlated RPC, repository and ViewModel; only the offline socket boundary is synthetic. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectActionsTimeoutTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    private class Wire : GatewayRpcWire {
        lateinit var rpc: CorrelatedGatewayRpc
        val frames = mutableListOf<JsonObject>()
        var refusal = false
        var sendAccepted = true
        var acknowledge = false
        var holdTree = false
        override fun close() = Unit
        override fun send(text: String): Boolean {
            val frame = Json.parseToJsonElement(text).jsonObject
            val method = frame["method"]?.jsonPrimitive?.content
            if (method != "projects.tree" && !sendAccepted) return false
            frames += frame
            when {
                method == "projects.tree" && !holdTree -> reply(frame, "\"result\":{\"projects\":[{\"id\":\"saved\",\"label\":\"Project\",\"path\":\"/workspace/project\",\"isAuto\":false}]}")
                method != "projects.tree" && refusal -> reply(frame, "\"error\":{\"code\":-32601,\"message\":\"Synthetic missing method\"}")
                method != "projects.tree" && acknowledge -> reply(frame, "\"result\":{}")
            }
            return true
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
            assertFalse("Fixture must enter saved-project branch", row.isAuto)
        }
        fun open(current: () -> Boolean = { true }): Long {
            model.openProjectActions(row, current); scope.runCurrent()
            return requireNotNull(state.dialog).token
        }
    }

    @Test fun savedColorLocalDeadlineKeepsVisibleUncertaintyWithoutReplay() = timeout(ProjectOperation.Color("blue"), "projects.update", "\"color\":\"blue\"")

    @Test fun savedRenameLocalDeadlineKeepsVisibleUncertaintyWithoutReplay() = timeout(ProjectOperation.Rename("Renamed"), "projects.update", "\"name\":\"Renamed\"")
    @Test fun savedIconLocalDeadlineKeepsVisibleUncertaintyWithoutReplay() = timeout(ProjectOperation.Icon("rocket"), "projects.update", "\"icon\":\"rocket\"")
    @Test fun savedFolderLocalDeadlineKeepsVisibleUncertaintyWithoutReplay() = timeout(ProjectOperation.AddFolder("/workspace/added"), "projects.add_folder", "\"path\":\"/workspace/added\",\"is_primary\":false")
    @Test fun savedActiveLocalDeadlineKeepsVisibleUncertaintyWithoutReplay() = timeout(ProjectOperation.SetActive, "projects.set_active")
    @Test fun savedDeleteLocalDeadlineKeepsVisibleUncertaintyWithoutReplay() = timeout(ProjectOperation.Delete, "projects.delete")

    @Test fun repositoryLocalDeadlineIsUnconfirmedWhileCallerAndSiblingRemainCurrent() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start()
            val editor = f.repo.projectEditor(f.row)!!
            val sibling = f.repo.projectEditor(f.row)!!
            val pending = async { runCatching { editor.execute(ProjectOperation.Color("blue")) }.exceptionOrNull() }
            runCurrent(); assertEquals(1, f.wire.mutations.size)
            advanceTimeBy(101); runCurrent()
            val failure = pending.await()!!
            assertFalse(failure is CancellationException)
            assertEquals("ProjectEditUnconfirmed", failure.javaClass.simpleName)
            assertTrue(currentCoroutineContext().isActive)
            assertTrue(editor.isCurrent()); assertTrue(sibling.isCurrent())
            assertEquals(1, f.wire.mutations.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun explicitRefusalIsNormalErrorAndAllowsDeliberateRetry() = retryableFailure(preSend = false)
    @Test fun falseSendIsNormalErrorAndAllowsDeliberateRetry() = retryableFailure(preSend = true)
    private fun retryableFailure(preSend: Boolean) = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); f.wire.refusal = true; f.wire.sendAccepted = !preSend
            val token = f.open()
            f.model.executeProjectAction(token, ProjectOperation.Color("blue")); runCurrent()
            val dialog = f.state.dialog!!
            assertFalse(dialog.busy)
            assertEquals("Project could not be updated. Check the Gateway and try again.", dialog.error)
            assertEquals(if (preSend) 0 else 1, f.wire.mutations.size)
            f.wire.sendAccepted = true
            f.model.executeProjectAction(token, ProjectOperation.Icon("rocket")); runCurrent()
            assertEquals(if (preSend) 1 else 2, f.wire.mutations.size)
            assertEquals(JsonPrimitive("rocket"), f.wire.mutations.last()["params"]!!.jsonObject["icon"])
        } finally { f.wire.rpc.close() }
    }

    @Test fun genuineParentCancelAndParentDeadlinePropagateForEverySavedFamily() = runTest(dispatcher) {
        for (operation in operations) for (deadline in listOf(false, true)) {
            val f = Fixture(this)
            try {
                f.start()
                var failure: Throwable? = null
                var active: Boolean? = null
                val job = launch {
                    try {
                        if (deadline) withTimeout(50) { f.repo.projectEditor(f.row)!!.execute(operation) }
                        else f.repo.projectEditor(f.row)!!.execute(operation)
                    } catch (e: Throwable) {
                        failure = e; active = currentCoroutineContext().isActive; throw e
                    }
                }
                runCurrent(); assertEquals(1, f.wire.mutations.size)
                if (deadline) { advanceTimeBy(51); runCurrent(); job.join() } else job.cancelAndJoin()
                assertTrue(failure is CancellationException)
                if (!deadline) assertEquals(false, active)
                assertTrue(job.isCancelled)
                advanceTimeBy(101); runCurrent()
                assertEquals(1, f.wire.mutations.size)
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun acknowledgedWritesSurviveReadbackDeadlineIncludingDeleteRetirement() = runTest(dispatcher) {
        for (operation in operations) {
            val f = Fixture(this)
            try {
                f.start()
                val sibling = f.repo.projectEditor(f.row)!!
                val token = f.open()
                f.wire.acknowledge = true; f.wire.holdTree = true
                f.model.executeProjectAction(token, operation); runCurrent()
                assertEquals(1, f.wire.mutations.size)
                assertTrue(f.state.dialog!!.busy)
                assertEquals(operation != ProjectOperation.Delete, sibling.isCurrent())
                advanceTimeBy(101); runCurrent()
                assertNull(f.state.dialog)
                assertEquals("Project updated. Projects could not be refreshed; reopen Sessions.", f.model.uiState.value.notice?.text)
                f.model.executeProjectAction(token, operation); runCurrent()
                assertEquals(1, f.wire.mutations.size)
                if (operation == ProjectOperation.Delete) {
                    assertNotNull(runCatching { sibling.execute(ProjectOperation.Delete) }.exceptionOrNull())
                    assertEquals(1, f.wire.mutations.size)
                }
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun disposedSurfaceDeadlineCleansOnlyItsPendingLifetime() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); var mounted = true
            val old = f.open { mounted }
            f.model.executeProjectAction(old, ProjectOperation.Color("blue")); runCurrent()
            assertEquals(1, f.wire.mutations.size); mounted = false
            advanceTimeBy(101); runCurrent()
            assertNull(f.state.dialog)
            val fresh = f.open()
            assertNotEquals(old, fresh)
            f.model.executeProjectAction(old, ProjectOperation.Delete); runCurrent()
            assertEquals(fresh, f.state.dialog!!.token)
            assertFalse(f.state.dialog!!.busy); assertNull(f.state.dialog!!.error)
            assertEquals(1, f.wire.mutations.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun ownerRoundTripRetiresOriginalFeedbackAndCallbacks() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val old = f.open()
            f.model.executeProjectAction(old, ProjectOperation.Color("blue")); runCurrent()
            assertEquals(1, f.wire.mutations.size)
            f.model.selectProfile("other"); runCurrent()
            f.model.selectProfile("default"); runCurrent()
            val fresh = f.open()
            advanceTimeBy(101); runCurrent()
            assertEquals(fresh, f.state.dialog!!.token)
            assertNull(f.state.dialog!!.error); assertFalse(f.state.dialog!!.busy)
            f.model.executeProjectAction(old, ProjectOperation.Delete); runCurrent()
            assertEquals(1, f.wire.mutations.size)
            assertEquals("default", f.cache.state.value.activeProjectScope)
        } finally { f.wire.rpc.close() }
    }

    @Test fun olderDeadlineCannotDismissOrUnlockNewerBusyDialog() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val old = f.open()
            f.model.executeProjectAction(old, ProjectOperation.Color("blue")); runCurrent()
            assertEquals(1, f.wire.mutations.size)
            advanceTimeBy(25); runCurrent()
            f.model.dismissProjectActions(old)
            val fresh = f.open()
            f.model.executeProjectAction(fresh, ProjectOperation.Icon("rocket")); runCurrent()
            assertTrue(f.state.dialog!!.busy)
            advanceTimeBy(76); runCurrent()
            assertEquals(fresh, f.state.dialog!!.token); assertTrue(f.state.dialog!!.busy)
            assertEquals(2, f.wire.mutations.size)
            f.wire.reply(f.wire.mutations.last(), "\"result\":{}"); runCurrent()
            assertNull(f.state.dialog)
        } finally { f.wire.rpc.close() }
    }

    @Test fun dismissingUnconfirmedEditRequiresFreshChoiceAndRejectsOldCallbacks() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val old = f.open()
            f.model.executeProjectAction(old, ProjectOperation.Color("blue")); runCurrent()
            advanceTimeBy(101); runCurrent()
            assertNotNull("Unconfirmed outcome must remain visible before dismissal", f.state.dialog)
            assertNotNull(f.state.dialog!!.error)
            f.model.dismissProjectActions(old)
            f.wire.acknowledge = true
            val fresh = f.open()
            f.model.executeProjectAction(old, ProjectOperation.Color("blue"))
            f.model.projectActionPage(old, ProjectActionPage.Delete)
            f.model.dismissProjectActions(old); runCurrent()
            assertEquals(fresh, f.state.dialog!!.token)
            assertEquals(1, f.wire.mutations.size)
            f.model.executeProjectAction(fresh, ProjectOperation.Icon("rocket")); runCurrent()
            assertNull(f.state.dialog)
            assertEquals(2, f.wire.mutations.size)
            assertFalse("color" in f.wire.mutations.last()["params"]!!.jsonObject)
        } finally { f.wire.rpc.close() }
    }

    @Test fun parentActionJobCancellationCleansEverySavedFamilyWithoutUncertaintyFeedback() = runTest(dispatcher) {
        for (operation in operations) {
            val f = Fixture(this)
            try {
                f.start(); val token = f.open()
                val parent = f.model.viewModelScope.coroutineContext[Job]!!
                val existing = parent.children.toSet()
                f.model.executeProjectAction(token, operation); runCurrent()
                assertEquals(1, f.wire.mutations.size); assertTrue(f.state.dialog!!.busy)
                // Cancel the actual action caller, leaving the UI projection alive to observe finally.
                val action = (parent.children.toSet() - existing).single()
                action.cancelAndJoin(); runCurrent()
                assertTrue(action.isCancelled)
                assertNull(f.state.dialog)
                advanceTimeBy(101); runCurrent()
                assertNull(f.state.dialog); assertEquals(1, f.wire.mutations.size)
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun retiredEndpointDeadlineCannotPublishToReplacementOwner() = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start(); val token = f.open()
            f.model.executeProjectAction(token, ProjectOperation.Color("blue")); runCurrent()
            assertEquals(1, f.wire.mutations.size)
            f.cache.resetForEndpointSwitch()
            f.endpoint.value = StorageEndpoint("synthetic-replacement/address", 1)
            runCurrent(); advanceTimeBy(101); runCurrent()
            assertNull(f.state.dialog)
            f.model.executeProjectAction(token, ProjectOperation.Delete); runCurrent()
            assertEquals(1, f.wire.mutations.size)
        } finally { f.wire.rpc.close() }
    }

    private val operations = listOf(ProjectOperation.Rename("Renamed"), ProjectOperation.Color("blue"),
        ProjectOperation.Icon("rocket"), ProjectOperation.AddFolder("/workspace/added"), ProjectOperation.SetActive, ProjectOperation.Delete)

    private fun timeout(operation: ProjectOperation, method: String, fields: String? = null) = runTest(dispatcher) {
        val f = Fixture(this)
        try {
            f.start()
            val editor = f.repo.projectEditor(f.row)!!
            val owner = f.cache.state.value.activeProjectScope
            val token = f.open()
            f.model.executeProjectAction(token, operation); runCurrent()
            assertEquals(1, f.wire.mutations.size)
            assertEquals(JsonPrimitive(method), f.wire.mutations.single()["method"])
            assertEquals(Json.parseToJsonElement("""{"id":"saved",${fields?.let { "$it," }.orEmpty()}"profile":"default"}"""), f.wire.mutations.single()["params"])
            assertTrue(f.state.dialog!!.busy)
            advanceTimeBy(101); runCurrent()
            val dialog = f.state.dialog
            assertNotNull("A sent saved-project timeout must not silently dismiss feedback", dialog)
            assertFalse(dialog!!.busy)
            assertEquals("Project change was not confirmed. Reopen Sessions to check it.", dialog.error)
            assertTrue(currentCoroutineContext().isActive)
            assertTrue(editor.isCurrent())
            assertEquals(owner, f.cache.state.value.activeProjectScope)
            assertEquals(token, dialog.token)
            // Retained submit, sibling choice and page callbacks cannot turn uncertain feedback into retry.
            f.model.executeProjectAction(token, operation)
            f.model.executeProjectAction(token, ProjectOperation.Icon("rocket"))
            f.model.projectActionPage(token, ProjectActionPage.Rename)
            runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(dialog, f.state.dialog)
            assertEquals(1, f.wire.mutations.size)
            f.wire.reply(f.wire.mutations.single(), "\"result\":{}")
            runCurrent()
            assertEquals(dialog, f.state.dialog)
            assertEquals(1, f.wire.mutations.size)
        } finally { f.wire.rpc.close() }
    }
}
