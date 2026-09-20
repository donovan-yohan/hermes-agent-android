package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three vault prompts at the repository seam: parked, answered, cancelled.
 *
 * Contract read at `NousResearch/hermes-agent` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`: every vault prompt is one
 * server→client *request* — `vault.unlock_prompt`, `vault.save_login`,
 * `vault.code` (`tui_gateway/contracts/server_requests.py:118-142`) — answered
 * by the response frame carrying the same id with `{value}` (`:25-28`), which
 * the agent side reads at `tui_gateway/agent_callbacks.py:179-193` and
 * `apps/desktop/src/components/prompt-overlays.tsx`.
 *
 * Virtual time throughout; nothing here sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultPromptTest {
    @Test
    fun `an unlock request parks its session and keeps the manager's display name`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-u1", "vault.unlock_prompt", VaultParams.UNLOCK)
        advanceUntilIdle()

        val pending = singlePending(env)
        // Not a SecretPending. Before #223 the kind mapper read anything that
        // was not clarify/approval/sudo as a secret, which would have answered a
        // master password as if it were a named env var.
        assertTrue(pending is VaultUnlockPending)
        pending as VaultUnlockPending
        assertEquals("1password", pending.backend)
        assertEquals("1Password", pending.displayName)
        assertEquals(PendingInputKind.VaultUnlock, pending.key.kind)
        assertEquals("srq-u1", pending.key.requestId)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `an unlock without a display name falls back to the backend id`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-u2", "vault.unlock_prompt", """{"backend":"bitwarden"}""")
        advanceUntilIdle()

        assertEquals("bitwarden", (singlePending(env) as VaultUnlockPending).displayName)
    }

    @Test
    fun `a save-login request parks with its origin, and site falls back to it`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-s1", "vault.save_login", """{"origin":"https://example.test"}""")
        advanceUntilIdle()

        val pending = singlePending(env) as VaultSaveLoginPending
        assertEquals("https://example.test", pending.origin)
        assertEquals("https://example.test", pending.site)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a code request parks with the site it is for`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-c1", "vault.code", VaultParams.CODE)
        advanceUntilIdle()

        val pending = singlePending(env) as VaultCodePending
        assertEquals("Example", pending.site)
        assertEquals("sent to your phone", pending.hint)
    }

    @Test
    fun `a prompt for a session this app never bound is not adopted`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        // Same frame, a runtime nothing here has bound. The resume that binds
        // it re-delivers the request through `open_requests`.
        env.rpc.ask("srq-x1", "vault.code", VaultParams.CODE, runtimeId = "runtime-unknown")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `the master password reaches the unlock response frame and the array is wiped`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-u1", "vault.unlock_prompt", VaultParams.UNLOCK)
        runCurrent()
        val key = singlePending(env).key

        val password = charArrayOf('h', 'u', 'n', 't', 'e', 'r')
        val response = env.repository.respondToPendingInput(key, PendingInputAction.VaultUnlockPassword(password))

        assertEquals(PendingInputResponse.Resolved, response)
        assertTrue("master password must be zeroed after use", password.all { it.code == 0 })
        // Exactly one frame, carrying the request's own id — the whole of what
        // the backend correlates on.
        val frame = env.rpc.answered.single()
        assertEquals("srq-u1", frame.id)
        assertEquals("hunter", frame.result.string("value"))
        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `keep locked is an empty value, not a dropped request`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-u1", "vault.unlock_prompt", VaultParams.UNLOCK)
        runCurrent()
        val key = singlePending(env).key

        env.repository.respondToPendingInput(key, PendingInputAction.VaultUnlockPassword(CharArray(0)))

        // The turn is blocked until *something* answers; "" is the answer that
        // means keep it locked (`prompt-overlays.tsx:283-290` @ the pin).
        assertEquals("", env.rpc.answered.single().result.string("value"))
    }

    @Test
    fun `a saved login travels as one JSON value and both arrays are wiped`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-s1", "vault.save_login", VaultParams.SAVE_LOGIN)
        runCurrent()
        val key = singlePending(env).key

        val identifier = "ada".toCharArray()
        val password = "s3cret".toCharArray()
        env.repository.respondToPendingInput(key, PendingInputAction.VaultLogin(identifier, password))

        assertTrue("identifier must be zeroed after use", identifier.all { it.code == 0 })
        assertTrue("password must be zeroed after use", password.all { it.code == 0 })
        val login = Json.parseToJsonElement(env.rpc.answered.single().result.string("value").orEmpty()) as JsonObject
        assertEquals("ada", login.string("identifier"))
        assertEquals("s3cret", login.string("password"))
    }

    @Test
    fun `declining a save sends the empty string the backend reads as no login`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-s1", "vault.save_login", VaultParams.SAVE_LOGIN)
        runCurrent()
        val key = singlePending(env).key

        env.repository.respondToPendingInput(key, PendingInputAction.VaultLogin(CharArray(0), CharArray(0)))

        // `save_login_cb` json-decodes a non-empty answer and refuses anything
        // without a password (`agent_callbacks.py:182-189` @ the pin), so the
        // decline has to be "" rather than a JSON object with empty fields.
        assertEquals("", env.rpc.answered.single().result.string("value"))
    }

    @Test
    fun `the one-time code reaches the response frame and the array is wiped`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-c1", "vault.code", VaultParams.CODE)
        runCurrent()
        val key = singlePending(env).key

        val code = "123456".toCharArray()
        val response = env.repository.respondToPendingInput(key, PendingInputAction.VaultCode(code))

        assertEquals(PendingInputResponse.Resolved, response)
        assertTrue("code must be zeroed after use", code.all { it.code == 0 })
        val frame = env.rpc.answered.single()
        assertEquals("srq-c1", frame.id)
        assertEquals("123456", frame.result.string("value"))
    }

    @Test
    fun `a send that never left the leg keeps the request answerable`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-c1", "vault.code", VaultParams.CODE)
        runCurrent()
        val key = singlePending(env).key

        env.rpc.sendFailure = GatewayRpcException("The gateway connection could not send the response.")
        val response = env.repository.respondToPendingInput(key, PendingInputAction.VaultCode("123456".toCharArray()))

        assertEquals(PendingInputResponse.Retryable, response)
        assertNotNull("an ambiguous send must leave the card up", env.repository.pendingInputs.value[key])
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a cancel tears the card down and settles the session`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-u1", "vault.unlock_prompt", VaultParams.UNLOCK)
        advanceUntilIdle()
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)

        env.rpc.emit("request.cancel", "runtime-a", """{"id":"srq-u1","method":"vault.unlock_prompt","reason":"timeout"}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `nothing is answered after a cancel`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-c1", "vault.code", VaultParams.CODE)
        advanceUntilIdle()
        val key = singlePending(env).key
        env.rpc.emit("request.cancel", "runtime-a", """{"id":"srq-c1","method":"vault.code","reason":"interrupted"}""")
        advanceUntilIdle()

        // The card is gone, so the only thing a stale tap can reach is the
        // repository's memory of it — and that has to read as finished business
        // rather than as a request this connection cannot answer.
        val response = env.repository.respondToPendingInput(key, PendingInputAction.VaultCode("123456".toCharArray()))

        assertEquals(PendingInputResponse.Resolved, response)
        assertTrue("a cancelled request takes no frame", env.rpc.answered.isEmpty())
    }

    @Test
    fun `a late cancel cannot erase the prompt that replaced it`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-c1", "vault.code", VaultParams.CODE)
        advanceUntilIdle()
        env.rpc.ask("srq-c2", "vault.code", """{"site":"Example"}""")
        advanceUntilIdle()

        env.rpc.emit("request.cancel", "runtime-a", """{"id":"srq-c1","method":"vault.code","reason":"timeout"}""")
        advanceUntilIdle()

        // Request-correlated, exactly as Desktop is (`input-requests.ts:79-96`).
        assertEquals("srq-c2", singlePending(env).key.requestId)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `cancelling one request leaves another parked on the same session`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-c1", "vault.code", VaultParams.CODE)
        advanceUntilIdle()
        env.rpc.ask("srq-u1", "vault.unlock_prompt", VaultParams.UNLOCK)
        advanceUntilIdle()
        assertEquals(2, env.repository.pendingInputs.value.size)

        env.rpc.emit("request.cancel", "runtime-a", """{"id":"srq-c1","method":"vault.code","reason":"timeout"}""")
        advanceUntilIdle()

        assertNotNull(env.repository.pendingInputs.value.values.single() as? VaultUnlockPending)
        // Still owed an answer, so still NeedsInput.
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a vault prompt dies with its turn like every other parked request`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-s1", "vault.save_login", VaultParams.SAVE_LOGIN)
        runCurrent()

        env.rpc.emit("message.complete", "runtime-a", """{"text":"done"}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
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

    /** One response frame this client handed the wire. */
    private class AnswerFrame(val id: String, val result: JsonObject)

    /** One error response frame, for a request method this client has no handler for. */
    private class RefusalFrame(val id: String, val code: Int, val message: String)

    private class FakeRpc : GatewayRpcClient, GatewayServerRequestResponder {
        private val eventFlow = MutableSharedFlow<GatewayEvent>(replay = 64, extraBufferCapacity = 64)
        private val requestFlow = MutableSharedFlow<GatewayServerRequest>(replay = 64, extraBufferCapacity = 64)
        override val events = eventFlow
        override val serverRequests = requestFlow
        val calls = mutableListOf<RpcCall>()
        val answered = mutableListOf<AnswerFrame>()
        val refused = mutableListOf<RefusalFrame>()

        /** When set, every answer fails the way a closed or broken leg does. */
        var sendFailure: GatewayRpcException? = null

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += RpcCall(method, params)
            return when (method) {
                "session.list" -> json("""{"sessions":[]}""")
                "session.resume" -> json(RESUME_A)
                "session.history" -> json("""{"messages":[],"count":0}""")
                "session.activate" -> json("{}")
                else -> json("{}")
            }
        }

        override suspend fun respondToServerRequest(id: String, result: JsonObject) {
            sendFailure?.let { throw it }
            answered += AnswerFrame(id, result)
        }

        override suspend fun failServerRequest(id: String, code: Int, message: String) {
            sendFailure?.let { throw it }
            refused += RefusalFrame(id, code, message)
        }

        fun emit(type: String, runtimeId: String?, payload: JsonElement = JsonNull) {
            check(eventFlow.tryEmit(GatewayEvent(type, runtimeId, payload)))
        }

        fun emit(type: String, runtimeId: String?, payload: String) = emit(type, runtimeId, json(payload))

        /** One server→client request, exactly as the socket hands it over. */
        fun ask(id: String, method: String, body: String = "{}", runtimeId: String = "runtime-a") {
            val params = buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                (json(body) as JsonObject).forEach { (key, value) -> put(key, value) }
            }
            check(requestFlow.tryEmit(GatewayServerRequest(id, method, runtimeId, params)))
        }

        override fun close() = Unit
    }

    private companion object {
        const val CLOCK = 1_800_000_000_000L
        const val RESUME_A =
            """{"session_id":"runtime-a","resumed":"durable-a","message_count":0,"messages":[],""" +
                """"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},""" +
                """"inflight":null,"running":false,"session_key":"durable-a","started_at":1700001000.125,"status":"idle"}"""
    }

    private object VaultParams {
        const val UNLOCK = """{"backend":"1password","display_name":"1Password"}"""
        const val SAVE_LOGIN = """{"origin":"https://example.test","site":"Example"}"""
        const val CODE = """{"site":"Example","hint":"sent to your phone"}"""
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
