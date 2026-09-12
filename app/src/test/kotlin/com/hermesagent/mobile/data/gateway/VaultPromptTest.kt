package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three vault prompts at the repository seam: parked, answered, expired.
 *
 * Contract read at `NousResearch/hermes-agent` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`:
 * `apps/desktop/src/app/session/hooks/use-message-stream/gateway-event/
 * input-requests.ts:173-204,370-446` for the payloads and the expiry rule,
 * `tui_gateway/methods_prompt.py:1099-1103` for which parameter each
 * `*.respond` reads, and `tui_gateway/agent_callbacks.py:179-193` for what the
 * agent side does with the answer.
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

        env.rpc.emit("vault.unlock.request", "runtime-a", UNLOCK_REQUEST)
        advanceUntilIdle()

        val pending = singlePending(env)
        // Not a SecretPending. Before #223 the kind mapper read anything that
        // was not clarify/approval/sudo as a secret, which would have sent a
        // master password to `secret.respond`.
        assertTrue(pending is VaultUnlockPending)
        pending as VaultUnlockPending
        assertEquals("1password", pending.backend)
        assertEquals("1Password", pending.displayName)
        assertEquals(PendingInputKind.VaultUnlock, pending.key.kind)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `an unlock without a display name falls back to the backend id`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.emit("vault.unlock.request", "runtime-a", """{"request_id":"req-u2","backend":"bitwarden"}""")
        advanceUntilIdle()

        assertEquals("bitwarden", (singlePending(env) as VaultUnlockPending).displayName)
    }

    @Test
    fun `a save-login request parks with its origin, and site falls back to it`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.emit("vault.save_login.request", "runtime-a", """{"request_id":"req-s1","origin":"https://example.test"}""")
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

        env.rpc.emit("vault.code.request", "runtime-a", CODE_REQUEST)
        advanceUntilIdle()

        val pending = singlePending(env) as VaultCodePending
        assertEquals("Example", pending.site)
        assertEquals("sent to your phone", pending.hint)
    }

    @Test
    fun `a vault request without a request id is discarded`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.emit("vault.code.request", "runtime-a", """{"site":"Example"}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `the master password reaches vault unlock respond and the array is wiped`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.unlock.request", "runtime-a", UNLOCK_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val password = charArrayOf('h', 'u', 'n', 't', 'e', 'r')
        val responses = launch {
            env.repository.respondToPendingInput(key, PendingInputAction.VaultUnlockPassword(password))
        }
        runCurrent()
        env.rpc.respondResponse?.complete(json("""{"status":"ok"}"""))
        responses.join()

        assertTrue("master password must be zeroed after use", password.all { it.code == 0 })
        val call = env.rpc.calls.last { it.method == "vault.unlock.respond" }
        assertEquals("req-u1", call.params.string("request_id"))
        assertEquals("hunter", call.params.string("password"))
        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `keep locked is an empty password, not a dropped request`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.unlock.request", "runtime-a", UNLOCK_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val responses = launch {
            env.repository.respondToPendingInput(key, PendingInputAction.VaultUnlockPassword(CharArray(0)))
        }
        runCurrent()
        env.rpc.respondResponse?.complete(json("""{"status":"ok"}"""))
        responses.join()

        // The turn is blocked until *something* answers; "" is the answer that
        // means keep it locked (`input-requests.ts:421-423` @ the pin).
        assertEquals("", env.rpc.calls.last { it.method == "vault.unlock.respond" }.params.string("password"))
    }

    @Test
    fun `a saved login travels as one JSON login field and both arrays are wiped`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.save_login.request", "runtime-a", SAVE_LOGIN_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val identifier = "ada".toCharArray()
        val password = "s3cret".toCharArray()
        val responses = launch {
            env.repository.respondToPendingInput(key, PendingInputAction.VaultLogin(identifier, password))
        }
        runCurrent()
        env.rpc.respondResponse?.complete(json("""{"status":"ok"}"""))
        responses.join()

        assertTrue("identifier must be zeroed after use", identifier.all { it.code == 0 })
        assertTrue("password must be zeroed after use", password.all { it.code == 0 })
        val call = env.rpc.calls.last { it.method == "vault.save_login.respond" }
        assertEquals("req-s1", call.params.string("request_id"))
        val login = Json.parseToJsonElement(call.params.string("login").orEmpty()) as JsonObject
        assertEquals("ada", login.string("identifier"))
        assertEquals("s3cret", login.string("password"))
    }

    @Test
    fun `declining a save sends the empty string the backend reads as no login`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.save_login.request", "runtime-a", SAVE_LOGIN_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val responses = launch {
            env.repository.respondToPendingInput(
                key,
                PendingInputAction.VaultLogin(CharArray(0), CharArray(0)),
            )
        }
        runCurrent()
        env.rpc.respondResponse?.complete(json("""{"status":"ok"}"""))
        responses.join()

        // `save_login_cb` json-decodes a non-empty answer and refuses anything
        // without a password (`agent_callbacks.py:182-189` @ the pin), so the
        // decline has to be "" rather than a JSON object with empty fields.
        assertEquals("", env.rpc.calls.last { it.method == "vault.save_login.respond" }.params.string("login"))
    }

    @Test
    fun `the one-time code reaches vault code respond and the array is wiped`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.code.request", "runtime-a", CODE_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val code = "123456".toCharArray()
        val responses = launch { env.repository.respondToPendingInput(key, PendingInputAction.VaultCode(code)) }
        runCurrent()
        env.rpc.respondResponse?.complete(json("""{"status":"ok"}"""))
        responses.join()

        assertTrue("code must be zeroed after use", code.all { it.code == 0 })
        val call = env.rpc.calls.last { it.method == "vault.code.respond" }
        assertEquals("req-c1", call.params.string("request_id"))
        assertEquals("123456", call.params.string("code"))
    }

    @Test
    fun `an expired status clears the prompt rather than keeping it answerable`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.code.request", "runtime-a", CODE_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val responses = launch {
            env.repository.respondToPendingInput(key, PendingInputAction.VaultCode("999".toCharArray()))
        }
        runCurrent()
        // `_respond` answers `expired` rather than raising 4009, because every
        // vault respond is registered with `allow_expired=True`
        // (`methods_prompt.py:1096-1103` @ the pin).
        env.rpc.respondResponse?.complete(json("""{"status":"expired"}"""))
        responses.join()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
    }

    @Test
    fun `an expire event tears the card down and settles the session`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.unlock.request", "runtime-a", UNLOCK_REQUEST)
        advanceUntilIdle()
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)

        env.rpc.emit("vault.unlock.expire", "runtime-a", """{"request_id":"req-u1"}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a late expire cannot erase the prompt that replaced it`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.code.request", "runtime-a", CODE_REQUEST)
        advanceUntilIdle()
        env.rpc.emit("vault.code.request", "runtime-a", """{"request_id":"req-c2","site":"Example"}""")
        advanceUntilIdle()

        env.rpc.emit("vault.code.expire", "runtime-a", """{"request_id":"req-c1"}""")
        advanceUntilIdle()

        // Request-correlated, exactly as Desktop is (`input-requests.ts:173-182`).
        assertEquals("req-c2", singlePending(env).key.requestId)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `expiring one kind leaves another parked on the same session`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.emit("vault.code.request", "runtime-a", CODE_REQUEST)
        advanceUntilIdle()
        env.rpc.emit("vault.unlock.request", "runtime-a", UNLOCK_REQUEST)
        advanceUntilIdle()
        assertEquals(2, env.repository.pendingInputs.value.size)

        env.rpc.emit("vault.code.expire", "runtime-a", """{"request_id":"req-c1"}""")
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
        env.rpc.emit("vault.save_login.request", "runtime-a", SAVE_LOGIN_REQUEST)
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

    private class FakeRpc : GatewayRpcClient {
        private val eventFlow = MutableSharedFlow<GatewayEvent>(replay = 64, extraBufferCapacity = 64)
        override val events = eventFlow
        val calls = mutableListOf<RpcCall>()
        var respondResponse: CompletableDeferred<JsonElement>? = null

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += RpcCall(method, params)
            return when (method) {
                "session.list" -> json("""{"sessions":[]}""")
                "session.resume" -> json(RESUME_A)
                "session.history" -> json("""{"messages":[],"count":0}""")
                "session.activate" -> json("{}")
                "vault.unlock.respond", "vault.save_login.respond", "vault.code.respond" ->
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
        const val RESUME_A =
            """{"session_id":"runtime-a","resumed":"durable-a","message_count":0,"messages":[],""" +
                """"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},""" +
                """"inflight":null,"running":false,"session_key":"durable-a","started_at":1700001000.125,"status":"idle"}"""
        const val UNLOCK_REQUEST =
            """{"request_id":"req-u1","backend":"1password","display_name":"1Password"}"""
        const val SAVE_LOGIN_REQUEST =
            """{"request_id":"req-s1","origin":"https://example.test","site":"Example"}"""
        const val CODE_REQUEST =
            """{"request_id":"req-c1","site":"Example","hint":"sent to your phone"}"""
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
