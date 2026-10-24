package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.connections.StorageEndpoint
import com.hermesagent.mobile.data.prefs.*
import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectAdoptionRecoveryTest {
    private class Wire : GatewayRpcWire {
        lateinit var rpc: CorrelatedGatewayRpc
        val frames = mutableListOf<JsonObject>()
        var behavior = "timeout"
        var inventory = """{"projects":[{"id":"saved","name":"Saved project","primary_path":"/workspace/project","archived":true,"folders":[{"path":"/workspace/project"}]}]}"""
        var holdList = false
        var holdTree = false
        var beforeSend: (() -> Unit)? = null
        var guardAtSend: (() -> Unit)? = null
        override fun send(text: String): Boolean {
            val frame = Json.parseToJsonElement(text).jsonObject
            beforeSend?.invoke()
            if (frame["method"] == JsonPrimitive("projects.create")) guardAtSend?.invoke()
            if (behavior == "false" && frame["method"] == JsonPrimitive("projects.create")) return false
            frames += frame
            when (frame["method"]?.jsonPrimitive?.content) {
                "projects.tree" -> if (!holdTree) reply(frame, "\"result\":{\"projects\":[{\"id\":\"auto\",\"label\":\"Project\",\"path\":\"/workspace/project\",\"isAuto\":true}]}")
                "projects.list" -> if (!holdList) reply(frame, "\"result\":$inventory")
                "projects.create" -> when (behavior) {
                    "throw" -> error("synthetic throwing send")
                    "malformed" -> reply(frame, "\"result\":{}")
                    "envelope" -> reply(frame, "\"unexpected\":true")
                    "disconnect" -> rpc.connectionClosed("Synthetic disconnect")
                    "internal" -> reply(frame, "\"error\":{\"code\":5061,\"message\":\"post-commit failure\"}")
                    "missing" -> reply(frame, "\"error\":{\"code\":-32601,\"message\":\"missing\"}")
                    "success" -> reply(frame, "\"result\":{\"project\":{\"id\":\"saved\"}}")
                }
            }
            return true
        }
        fun reply(frame: JsonObject, body: String) = rpc.receive("""{"jsonrpc":"2.0","id":${frame["id"]},$body}""")
        val creates get() = frames.filter { it["method"] == JsonPrimitive("projects.create") }
        override fun close() = Unit
    }
    private class Store : ProjectAdoptionStore {
        val delegate = TransientProjectAdoptionStore()
        var failPrepare = false
        var failRemove = false
        var prepareGate: CompletableDeferred<Unit>? = null
        var beforeCas: (() -> Unit)? = null
        var cancelRemove = false
        var cancelAfterRemove = false
        override suspend fun readProjectAdoptions(scope: ProjectAdoptionScope) = delegate.readProjectAdoptions(scope)
        override suspend fun compareProjectAdoptions(scope: ProjectAdoptionScope, expected: List<ProjectAdoptionAttempt>, replacement: List<ProjectAdoptionAttempt>, canCommit: () -> Boolean): Boolean {
            if (replacement.isEmpty() && cancelRemove) throw CancellationException("Synthetic parent cancellation during cleanup")
            if (replacement.isEmpty()) check(!failRemove) else { prepareGate?.await(); check(!failPrepare) }
            beforeCas?.invoke()
            val changed = delegate.compareProjectAdoptions(scope, expected, replacement, canCommit)
            if (changed && replacement.isEmpty() && cancelAfterRemove) throw CancellationException("Synthetic cancellation after committed removal")
            return changed
        }
    }
    private inner class Fixture(scope: TestScope, val store: Store = Store()) {
        val cache = SessionCache()
        val endpoint = MutableStateFlow<StorageEndpoint?>(StorageEndpoint("row/address", 0))
        val wire = Wire().apply { rpc = CorrelatedGatewayRpc(this, { 100 }, StandardTestDispatcher(scope.testScheduler)) }
        val client = MutableStateFlow<GatewayRpcClient?>(wire.rpc)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repo = LiveGatewaySessionRepository(cache, connection, client,
            scope.backgroundScope, projectAdoptionStore = store, projectStorageEndpoint = endpoint) { 0 }
        val owner = ProjectAdoptionScope("row/address", null)
        val row get() = cache.state.value.projects.projects.getValue("auto")
        suspend fun prepare() { repo.refreshProjects() }
    }

    @Test fun `guard is durable before actual wire send and denied removal remains safe`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare()
            var witnessed = false
            f.wire.guardAtSend = {
                // Transient delegate exposes an ordinary completed read through runBlocking only here;
                // no scheduler suspension is involved in the backing store.
                val rows = runBlocking { f.store.delegate.readProjectAdoptions(f.owner) }
                assertEquals(1, rows.size)
                assertEquals("/workspace/project", rows.single().path)
                witnessed = true
            }
            f.wire.behavior = "missing"; f.store.failRemove = true
            assertTrue("Failed cleanup requires recovery feedback", runCatching { f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Color(null)) }.exceptionOrNull() is ProjectAdoptionUncertain)
            assertTrue(witnessed)
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            assertTrue(runCatching { f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Icon("rocket")) }.isFailure)
            assertEquals(1, f.wire.creates.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun `sent malformed disconnect throwing dispatch and internal errors remain guarded across restart`() = runTest {
        for (behavior in listOf("malformed", "envelope", "disconnect", "throw", "internal", "timeout")) {
            val f = Fixture(this)
            try {
                runCurrent(); f.prepare(); f.wire.behavior = behavior
                val editor = f.repo.projectEditor(f.row)!!
                val request = async { runCatching { editor.execute(ProjectOperation.Icon("rocket")) } }
                runCurrent(); assertEquals(behavior, 1, f.wire.creates.size)
                assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
                if (behavior == "timeout") { advanceTimeBy(101); runCurrent() }
                assertTrue(behavior, request.await().exceptionOrNull() is ProjectAdoptionUncertain)
                val restarted = Fixture(this, f.store)
                try {
                    runCurrent(); restarted.prepare()
                    val sibling = restarted.repo.projectEditor(restarted.row)!!
                    assertTrue(sibling.isCurrent())
                    assertTrue(runCatching { sibling.execute(ProjectOperation.Color(null)) }.exceptionOrNull() is ProjectAdoptionUncertain)
                    assertEquals(0, restarted.wire.creates.size)
                    assertEquals(1, restarted.repo.projectRecovery()!!.pending().size)
                } finally { restarted.wire.rpc.close() }
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun `prepared before send crash stays guarded but false and missing method are retryable`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare()
            val prepared = ProjectAdoptionAttempt("crashed-before-send", "auto", "/workspace/project", "Project")
            assertTrue(f.store.compareProjectAdoptions(f.owner, emptyList(), listOf(prepared)))
            assertTrue(runCatching { f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Icon("rocket")) }.exceptionOrNull() is ProjectAdoptionUncertain)
            assertEquals(0, f.wire.creates.size)
            assertTrue(f.repo.projectRecovery()!!.refresh(prepared.attemptId) { true }.acknowledge { true })
            for (behavior in listOf("false", "missing")) {
                f.wire.behavior = behavior
                assertTrue(runCatching { f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Color(null)) }.isFailure)
                assertTrue(f.store.readProjectAdoptions(f.owner).isEmpty())
            }
        } finally { f.wire.rpc.close() }
    }

    @Test fun `failed preparation never sends and changed surface after durable preparation refuses dispatch`() = runTest {
        for (change in listOf("storage-failure", "surface", "profile-aba", "endpoint")) {
            val f = Fixture(this)
            try {
                runCurrent(); f.prepare()
                var surface = true
                val editor = f.repo.projectEditor(f.row)!!
                if (change == "storage-failure") f.store.failPrepare = true
                else f.store.beforeCas = {
                    when (change) {
                        "surface" -> surface = false
                        "profile-aba" -> { f.repo.setProfileRouting(ProfileRouting(activeProfile = "other")); f.repo.setProfileRouting(ProfileRouting()) }
                        "endpoint" -> { f.endpoint.value = StorageEndpoint("other/address", 0) }
                    }
                }
                assertTrue(runCatching { editor.execute(ProjectOperation.Icon("rocket")) { surface } }.isFailure)
                assertEquals(change, 0, f.wire.creates.size)
                // Cleanup may leave a conservative guard, never permission from a stale scope.
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun `cancelled acknowledgement after durable commit cannot revive retained appearance handles`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare()
            val retained = f.repo.projectEditor(f.row)!!
            val attempt = ProjectAdoptionAttempt("prepared", "auto", "/workspace/project", "Project")
            f.store.compareProjectAdoptions(f.owner, emptyList(), listOf(attempt))
            val ticket = f.repo.projectRecovery()!!.refresh(attempt.attemptId) { true }
            f.store.cancelAfterRemove = true
            assertTrue(runCatching { ticket.acknowledge { true } }.exceptionOrNull() is CancellationException)
            assertTrue(f.store.readProjectAdoptions(f.owner).isEmpty())
            assertFalse("Retained choices must retire before the fallible durable acknowledgement", retained.isCurrent())
            assertFalse(ticket.isCurrent())
            assertEquals(0, f.wire.creates.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun `outer parent timeout propagates cancellation while local RPC timeout becomes recovery feedback`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare()
            var caught: Throwable? = null
            val job = launch {
                withTimeout(50) {
                    try { f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Icon("rocket")) }
                    catch (failure: Throwable) { caught = failure; throw failure }
                }
            }
            runCurrent(); assertEquals(1, f.wire.creates.size)
            advanceTimeBy(51); runCurrent(); job.join()
            assertTrue(job.isCancelled)
            assertTrue("The repository must propagate genuine parent timeout", caught is CancellationException)
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun `parent cancellation during acknowledged cleanup propagates and keeps guard`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare(); f.wire.behavior = "success"; f.store.cancelRemove = true
            val editor = f.repo.projectEditor(f.row)!!
            val failure = runCatching { editor.execute(ProjectOperation.Icon("rocket")) }.exceptionOrNull()
            assertTrue("Cleanup must not swallow parent cancellation", failure is CancellationException)
            assertFalse(editor.isCurrent())
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            assertEquals(1, f.wire.creates.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun `acknowledged create with RPC local readback timeout reports saved without inviting replay`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare(); f.wire.behavior = "success"; f.wire.holdTree = true
            val editor = f.repo.projectEditor(f.row)!!
            val pending = async { runCatching { editor.execute(ProjectOperation.Icon("rocket")) } }
            runCurrent(); assertEquals(1, f.wire.creates.size)
            assertEquals(JsonPrimitive("projects.tree"), f.wire.frames.last()["method"])
            advanceTimeBy(101); runCurrent()
            val outcome = pending.await().getOrThrow()
            assertEquals("saved", outcome.projectId)
            assertFalse(outcome.catalogRefreshed)
            assertTrue(f.store.readProjectAdoptions(f.owner).isEmpty())
            assertFalse(editor.isCurrent())
            assertEquals(1, f.wire.creates.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun `success cleanup failure is acknowledged and still recoverable without replay`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare(); f.store.failRemove = true; f.wire.behavior = "success"
            val old = f.repo.projectEditor(f.row)!!
            val result = old.execute(ProjectOperation.Icon("rocket"))
            assertEquals("saved", result.projectId); assertTrue(result.recoveryPending)
            assertFalse(old.isCurrent())
            assertEquals(1, f.repo.projectRecovery()!!.pending().size)
            assertTrue(runCatching { f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Color(null)) }.isFailure)
            f.store.failRemove = false
            val owner = f.repo.projectRecovery()!!
            assertTrue(owner.refresh(owner.pending().single().attemptId) { true }.acknowledge { true })
            assertEquals(1, f.wire.creates.size)
            assertFalse(old.isCurrent())
        } finally { f.wire.rpc.close() }
    }

    @Test fun `latest read surface profile ABA and attempt CAS revoke acknowledgement without RPC mutation`() = runTest {
        for (change in listOf("latest", "surface", "profile-aba", "endpoint", "attempt", "storage", "during-cas", "disconnected")) {
            val f = Fixture(this)
            try {
                runCurrent(); f.prepare()
                val attempt = ProjectAdoptionAttempt("prepared", "auto", "/workspace/project", "Project")
                f.store.compareProjectAdoptions(f.owner, emptyList(), listOf(attempt))
                var surface = true
                val recovery = f.repo.projectRecovery()!!
                val ticket = recovery.refresh(attempt.attemptId) { surface }
                when (change) {
                    "latest" -> recovery.refresh(attempt.attemptId) { true }
                    "disconnected" -> f.connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
                    "surface" -> surface = false
                    "profile-aba" -> { f.repo.setProfileRouting(ProfileRouting(activeProfile = "other")); f.repo.setProfileRouting(ProfileRouting()) }
                    "endpoint" -> f.endpoint.value = StorageEndpoint("other/address", 0)
                    "attempt" -> f.store.compareProjectAdoptions(f.owner, listOf(attempt), listOf(attempt.copy(attemptId = "new")))
                    "storage" -> f.store.failRemove = true
                    "during-cas" -> f.store.beforeCas = { surface = false }
                }
                val frames = f.wire.frames.size
                assertFalse(change, runCatching { ticket.acknowledge { true } }.getOrDefault(false))
                assertEquals(frames, f.wire.frames.size)
                assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            } finally { f.wire.rpc.close() }
        }
    }

    @Test fun `same endpoint reconnect keeps guard and revokes prior read ticket`() = runTest {
        val f = Fixture(this)
        var second: Wire? = null
        try {
            runCurrent(); f.prepare()
            val attempt = ProjectAdoptionAttempt("prepared", "auto", "/workspace/project", "Project")
            f.store.compareProjectAdoptions(f.owner, emptyList(), listOf(attempt))
            val old = f.repo.projectRecovery()!!.refresh(attempt.attemptId) { true }
            second = Wire().apply { rpc = CorrelatedGatewayRpc(this, { 100 }, StandardTestDispatcher(testScheduler)) }
            f.client.value = second.rpc
            runCurrent(); f.repo.refreshProjects()
            assertFalse(old.acknowledge { true })
            assertEquals(listOf(attempt), f.repo.projectRecovery()!!.pending())
            assertTrue(runCatching { f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Color(null)) }.isFailure)
            assertEquals(0, second.creates.size)
        } finally { f.wire.rpc.close(); second?.rpc?.close() }
    }

    @Test fun `held inventory refuses revoked surface response and no stale ticket escapes`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare()
            val attempt = ProjectAdoptionAttempt("prepared", "auto", "/workspace/project", "Project")
            f.store.compareProjectAdoptions(f.owner, emptyList(), listOf(attempt))
            var surface = true
            f.wire.holdList = true
            val read = async { runCatching { f.repo.projectRecovery()!!.refresh(attempt.attemptId) { surface } } }
            runCurrent()
            val frame = f.wire.frames.last()
            assertEquals(JsonPrimitive("projects.list"), frame["method"])
            surface = false
            f.wire.reply(frame, "\"result\":${f.wire.inventory}")
            runCurrent(); assertTrue(read.await().isFailure)
            assertEquals(listOf(attempt), f.store.readProjectAdoptions(f.owner))
        } finally { f.wire.rpc.close() }
    }

    @Test fun `failed latest list invalidates prior ticket and never turns absence into authority`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare()
            val attempt = ProjectAdoptionAttempt("prepared", "auto", "/workspace/project", "Project")
            f.store.compareProjectAdoptions(f.owner, emptyList(), listOf(attempt))
            val recovery = f.repo.projectRecovery()!!
            val old = recovery.refresh(attempt.attemptId) { true }
            f.wire.inventory = "{}"
            assertTrue(runCatching { recovery.refresh(attempt.attemptId) { true } }.isFailure)
            assertFalse(old.acknowledge { true })
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            f.wire.inventory = """{"projects":[]}"""
            val empty = recovery.refresh(attempt.attemptId) { true }
            assertTrue(empty.projects.isEmpty())
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            assertEquals(0, f.wire.creates.size)
        } finally { f.wire.rpc.close() }
    }

    @Test fun `fresh archived inventory and explicit review authorize only a new deliberate choice`() = runTest {
        val f = Fixture(this)
        try {
            runCurrent(); f.prepare()
            val original = f.repo.projectEditor(f.row)!!
            val sibling = f.repo.projectEditor(f.row)!!
            val sent = async { runCatching { original.execute(ProjectOperation.Icon("rocket")) } }
            runCurrent(); assertEquals(1, f.wire.creates.size)
            advanceTimeBy(101); runCurrent(); assertTrue(sent.await().isFailure)
            assertTrue(original.isCurrent()) // Copy/Hide/menu stay reachable.
            assertEquals(1, f.store.readProjectAdoptions(f.owner).size)
            val review = f.repo.projectRecovery()!!
            val attempt = review.pending().single()
            val ticket = review.refresh(attempt.attemptId) { true }
            assertEquals("Saved project", ticket.projects.single().name)
            assertTrue(ticket.projects.single().archived)
            assertEquals(listOf("/workspace/project"), ticket.projects.single().paths)
            val before = f.wire.frames.size
            assertTrue(ticket.acknowledge { true })
            assertEquals("Acknowledgement must not dispatch any RPC", before, f.wire.frames.size)
            assertTrue(f.store.readProjectAdoptions(f.owner).isEmpty())
            assertFalse(original.isCurrent()); assertFalse(sibling.isCurrent())
            assertTrue(runCatching { sibling.execute(ProjectOperation.Color(null)) }.isFailure)
            assertFalse(ticket.acknowledge { true })
            f.wire.behavior = "success"
            f.repo.projectEditor(f.row)!!.execute(ProjectOperation.Color("blue"))
            assertEquals(2, f.wire.creates.size)
            val params = f.wire.creates.last()["params"]!!.jsonObject
            assertEquals(JsonPrimitive("blue"), params["color"])
            assertFalse("Old icon choice must not replay", "icon" in params)
        } finally { f.wire.rpc.close() }
    }
}
