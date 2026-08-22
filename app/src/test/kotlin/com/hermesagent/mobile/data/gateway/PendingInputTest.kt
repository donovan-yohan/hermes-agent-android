package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingInputTest {
    @Test
    fun `clarify request parks its session with parsed choices`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")

        env.rpc.emit("status.update", "runtime-a", """{"kind":"process","text":"x"}""")
        advanceUntilIdle()
                env.rpc.emit("clarify.request", "runtime-a", CLARIFY_SINGLE)
        advanceUntilIdle()

        val pending = singlePending(env)
        assertTrue(pending is ClarifyPending)
        assertEquals(listOf("Yes", "No"), (pending as ClarifyPending).choices)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `malformed prompt without a request id is discarded`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")

        env.rpc.emit("clarify.request", "runtime-a", """{"question":"no id here"}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `tool progress cannot overwrite a NeedsInput session`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("clarify.request", "runtime-a", CLARIFY_SINGLE)
        runCurrent()

        env.rpc.emit(
            "tool.start",
            "runtime-a",
            """{"tool_id":"t1","name":"terminal","label":"Terminal","context":"running"}""",
        )
        advanceUntilIdle()

        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `message complete clears the parked request`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("clarify.request", "runtime-a", CLARIFY_SINGLE)
        runCurrent()

        env.rpc.emit("message.complete", "runtime-a", """{"text":"done"}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
    }

    @Test
    fun `approval respond sends received then respond with the offered choice`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("approval.request", "runtime-a", APPROVAL_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val responses = launch { env.repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice("Run once")) }
        runCurrent()
        env.rpc.respondResponse?.complete(json("""{"status":"ok"}"""))
        responses.join()

        val received = env.rpc.calls.last { it.method == "approval.received" }
        assertEquals("runtime-a", received.params.string("session_id"))
        val respond = env.rpc.calls.last { it.method == "approval.respond" }
        assertEquals("runtime-a", respond.params.string("session_id"))
        assertEquals("req-approve-1", respond.params.string("request_id"))
        assertEquals("Run once", respond.params.string("choice"))
        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a choice the gateway did not offer is refused locally`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("approval.request", "runtime-a", APPROVAL_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val result = env.repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice("Not offered"))

        assertEquals(PendingInputResponse.Retryable, result)
        assertTrue(env.rpc.calls.none { it.method == "approval.respond" })
        assertNotNull(env.repository.pendingInputs.value[key])
    }

    @Test
    fun `sudo password reaches the wire and the array is wiped afterwards`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("sudo.request", "runtime-a", SUDO_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val password = CharArray(3) { 'a' + it }
        val responses = launch { env.repository.respondToPendingInput(key, PendingInputAction.SudoPassword(password)) }
        runCurrent()
        env.rpc.respondResponse?.complete(json("""{"status":"ok"}"""))
        responses.join()

        assertTrue("password must be zeroed after use", password.all { it.code == 0 })
        val call = env.rpc.calls.last { it.method == "sudo.respond" }
        assertEquals("abc", call.params.string("password"))
        assertTrue(env.repository.pendingInputs.value.isEmpty())
    }

    @Test
    fun `transport failure keeps the request pending for retry`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("clarify.request", "runtime-a", CLARIFY_SINGLE)
        runCurrent()
        val key = singlePending(env).key

        val failure = GatewayRpcException("socket closed")
        env.rpc.respondResponse = CompletableDeferred()
        val responses = launch {
            env.repository.respondToPendingInput(key, PendingInputAction.ClarifyAnswer(mapOf("" to "yes")))
        }
        runCurrent()
        env.rpc.respondResponse?.completeExceptionally(failure)
        responses.join()

        assertNotNull(env.repository.pendingInputs.value[key])
    }

    @Test
    fun `connection replacement clears all pending requests`() = runTest {
        val cache = SessionCache()
        cache.upsertSessions(listOf(summary("durable-a")))
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, state, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        runCurrent()
        rpc.emit("clarify.request", "runtime-a", CLARIFY_SINGLE)
        runCurrent()
        assertTrue(repository.pendingInputs.value.isNotEmpty())

        clients.value = null
        state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()

        assertTrue(repository.pendingInputs.value.isEmpty())
    }

    private fun singlePending(env: Environment): PendingInputRequest {
        val requests = env.repository.pendingInputs.value.values.toList()
                assertEquals(1, requests.size)
        return requests.single()
    }

    private data class Environment(
        val cache: SessionCache,
        val rpc: FakeRpc,
        val repository: LiveGatewaySessionRepository,
    )

    private fun environment(scopeDispatcher: kotlinx.coroutines.CoroutineDispatcher): Environment {
        val scope = CoroutineScope(scopeDispatcher + Job())
        val cache = SessionCache()
        cache.upsertSessions(listOf(summary("durable-a")))
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            scope,
        ) { CLOCK }
        return Environment(cache, rpc, repository)
    }

    private fun summary(id: String) = SessionSummary(
        id = id,
        title = "A",
        preview = "",
        lastActiveAtMillis = CLOCK,
    )

    private class RpcCall(val method: String, val params: JsonObject)

    private class FakeRpc : GatewayRpcClient {
        private val eventFlow = MutableSharedFlow<GatewayEvent>(replay = 64, extraBufferCapacity = 64)
        override val events = eventFlow
        val calls = mutableListOf<RpcCall>()
        var resumeA =
            """{"session_id":"runtime-a","resumed":"durable-a","message_count":0,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":null,"running":false,"session_key":"durable-a","started_at":1700001000.125,"status":"idle"}"""
        var historyResult = """{"messages":[],"count":0}"""
        var respondResponse: CompletableDeferred<JsonElement>? = null

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += RpcCall(method, params)
            return when (method) {
                "session.list" -> json("""{"sessions":[]}""")
                "session.resume" -> json(resumeA)
                "session.history" -> json(historyResult)
                "session.activate" -> json("{}")
                "clarify.respond", "approval.received", "approval.respond", "sudo.respond", "secret.respond" ->
                    respondResponse?.await() ?: json("""{"status":"ok"}""")
                else -> json("{}")
            }
        }

        fun emit(type: String, runtimeId: String?, payload: JsonElement = JsonNull) {
                        check(eventFlow.tryEmit(GatewayEvent(type, runtimeId, payload)))
        }

        fun emit(type: String, runtimeId: String?, payload: String) = emit(type, runtimeId, json(payload))

        override fun close() = Unit
    }

    companion object {
        const val CLOCK = 1_800_000_000_000L
        const val CLARIFY_SINGLE =
            """{"request_id":"req-1","question":"Proceed?","choices":["Yes","No"],"multi_select":false}"""
        const val APPROVAL_REQUEST =
            """{"request_id":"req-approve-1","command":"rm -rf build","choices":["Run once","Reject"]}"""
        const val SUDO_REQUEST = """{"request_id":"req-sudo-1"}"""
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
