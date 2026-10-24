package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectAdoptionDispatchTest {
    private class Wire : GatewayRpcWire {
        lateinit var rpc: CorrelatedGatewayRpc
        val frames = mutableListOf<JsonObject>()
        var catalog = """{"projects":[{"id":"auto","label":"Project","path":"/workspace/project","isAuto":true}]}"""
        var refusal: String? = null
        var sendAccepted = true
        override fun send(text: String): Boolean {
            if (!sendAccepted) return false
            val frame = Json.parseToJsonElement(text).jsonObject
            frames += frame
            if (frame["method"] == JsonPrimitive("projects.tree")) reply(frame, "\"result\":$catalog")
            else refusal?.let { reply(frame, "\"error\":$it") }
            return true
        }
        fun reply(frame: JsonObject, body: String) = rpc.receive("""{"jsonrpc":"2.0","id":${frame["id"]},$body}""")
        val creates get() = frames.filter { it["method"] == JsonPrimitive("projects.create") }
        override fun close() = Unit
    }
    private fun TestScope.fixture(): Triple<LiveGatewaySessionRepository, SessionCache, Wire> {
        val wire = Wire()
        wire.rpc = CorrelatedGatewayRpc(wire, { 100 }, StandardTestDispatcher(testScheduler))
        val cache = SessionCache()
        val repo = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(wire.rpc), backgroundScope) { 0 }
        return Triple(repo, cache, wire)
    }

    @Test fun `real sent adoption timeout blocks old sibling and newly issued handles until authoritative resolution`() = runTest {
        val (repo, cache, wire) = fixture()
        try {
            runCurrent(); repo.refreshProjects()
            val row = cache.state.value.projects.projects.getValue("auto")
            val first = repo.projectEditor(row)!!; val sibling = repo.projectEditor(row)!!
            val pending = async { runCatching { first.execute(ProjectOperation.Icon("rocket")) } }
            runCurrent()
            assertEquals(1, wire.creates.size)
            assertEquals(Json.parseToJsonElement("""{"name":"Project","folders":["/workspace/project"],"primary_path":"/workspace/project","use":false,"icon":"rocket"}"""), wire.creates.single()["params"])
            advanceTimeBy(101); runCurrent()
            val failure = pending.await().exceptionOrNull()
            assertNotNull(failure)
            // A fresh read that still reports the same auto does not prove the timed-out create was refused.
            repo.refreshProjects()
            for (editor in listOf(first, sibling, repo.projectEditor(row)!!)) {
                val refused = runCatching { editor.execute(ProjectOperation.Color(null)) }.exceptionOrNull()
                assertNotNull(refused)
                assertEquals("Never replay uncertain adoption", 1, wire.creates.size)
            }
            // A late ack is not consumed or replayed. Fresh catalog truth resolves it to a saved node.
            wire.reply(wire.creates.single(), "\"result\":{\"project\":{\"id\":\"saved\"}}")
            wire.catalog = """{"projects":[{"id":"saved","label":"Project","path":"/workspace/project"}]}"""
            repo.refreshProjects()
            assertFalse(first.isCurrent()); assertFalse(sibling.isCurrent())
            val saved = repo.projectEditor(cache.state.value.projects.projects.getValue("saved"))!!
            wire.refusal = """{"code":-32601,"message":"method not found"}"""
            assertTrue(runCatching { saved.execute(ProjectOperation.Color(null)) }.exceptionOrNull() is GatewayRpcError)
            assertEquals("projects.update", wire.frames.last()["method"]?.jsonPrimitive?.content)
            assertEquals(1, wire.creates.size)
        } finally { wire.rpc.close() }
    }

    @Test fun `real pre-send failure and proven missing method keep adoption retryable`() = runTest {
        for (preSend in listOf(true, false)) {
            val (repo, cache, wire) = fixture()
            try {
                runCurrent(); repo.refreshProjects()
                val editor = repo.projectEditor(cache.state.value.projects.projects.getValue("auto"))!!
                wire.sendAccepted = !preSend
                wire.refusal = """{"code":-32601,"message":"method not found"}"""
                val first = runCatching { editor.execute(ProjectOperation.Icon("rocket")) }.exceptionOrNull()
                assertTrue(if (preSend) first is GatewayRpcException && !first.requestMayHaveBeenAccepted else first is GatewayRpcError)
                wire.sendAccepted = true
                assertTrue(runCatching { editor.execute(ProjectOperation.Color(null)) }.exceptionOrNull() is GatewayRpcError)
                assertEquals(if (preSend) 1 else 2, wire.creates.size)
            } finally { wire.rpc.close() }
        }
    }

    @Test fun `parent cancellation propagates but never releases sent adoption for replay`() = runTest {
        val (repo, cache, wire) = fixture()
        try {
            runCurrent(); repo.refreshProjects()
            val row = cache.state.value.projects.projects.getValue("auto")
            val editor = repo.projectEditor(row)!!
            var failure: Throwable? = null
            val job = launch { try { editor.execute(ProjectOperation.Color(null)) } catch (e: Throwable) { failure = e; throw e } }
            runCurrent(); assertEquals(1, wire.creates.size)
            job.cancelAndJoin()
            assertTrue(job.isCancelled); assertTrue(failure is CancellationException)
            val blocked = async { runCatching { repo.projectEditor(row)!!.execute(ProjectOperation.Icon("rocket")) } }
            runCurrent()
            assertEquals("Cancellation cannot authorize another create", 1, wire.creates.size)
            assertNotNull(blocked.await().exceptionOrNull())
        } finally { wire.rpc.close() }
    }
}
