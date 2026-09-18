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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clarify / approval / sudo / secret half of the prompt channel.
 *
 * A blocking prompt is a server→client *request* frame
 * (`tui_gateway/server_requests.py:1-13` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`), answered by exactly one response
 * frame carrying the same `srq-…` id, withdrawn by the one event the family
 * still has (`request.cancel`), and re-delivered after a reconnect through
 * `open_requests` (`:15-18` @ the pin).
 *
 * Virtual time throughout; nothing here sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingInputTest {
    /**
     * A method this client has no handler for gets exactly one refusal.
     *
     * Without it the backend waits out its own deadline — 300s for clarify —
     * for a question no phone surface could ever answer. Desktop's own
     * bridges (`terminal.read`, `preview.read`, `window.read`, `tour`) are the
     * real population here: they are declared server requests this app has no
     * card for, so a turn that needs one must fail fast rather than park.
     */
    @Test
    fun `a request method with no handler is refused once, and parks nothing`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-bridge", "terminal.read", """{"range":"all"}""")
        advanceUntilIdle()

        val refusal = env.rpc.refused.single()
        assertEquals("srq-bridge", refusal.id)
        assertEquals(JSON_RPC_METHOD_NOT_FOUND, refusal.code)
        assertTrue(
            "the message names the method and claims no more",
            refusal.message.contains("terminal.read"),
        )
        assertTrue("nothing is parked for a method this client cannot show", env.repository.pendingInputs.value.isEmpty())
        assertTrue("and no answer frame was invented", env.rpc.answered.isEmpty())
        assertEquals(
            "a refused question is not a reason to park the session",
            SessionStatus.Idle,
            env.cache.session("durable-a")?.status,
        )
    }

    /**
     * The refusal is not a catch-all for every request this client drops.
     *
     * A *known* question for a session that is not bound yet is still
     * answerable — the resume that binds it re-delivers it through
     * `open_requests` — so refusing it would throw away a prompt that was about
     * to have a card.
     */
    @Test
    fun `a question for an unbound session is left for its resume, not refused`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-elsewhere", "clarify", CLARIFY_SINGLE, runtimeId = "runtime-not-open")
        advanceUntilIdle()

        assertTrue("an unbound session's question is not this connection's to refuse", env.rpc.refused.isEmpty())
        assertTrue(env.repository.pendingInputs.value.isEmpty())
    }

    /**
     * A reconnect's replay is the only delivery an unanswered question gets, so
     * a method with no handler has to be refused there too.
     *
     * `open_requests` is re-delivered by every resume; a client that drops an
     * unknown one here answers nothing, every time, and the backend's wait
     * settles only on its own deadline. This is the same class as the live
     * frame — and unlike a *known* replayed question, it needs no card, so an
     * unbound session is no reason to withhold the refusal.
     */
    @Test
    fun `an unknown method in open_requests is refused too`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.rpc.resumeOverride = env.rpc.resumeWithOpenRequests(
            "durable-a",
            """{"id":"srq-bridge","method":"terminal.read","params":{"session_id":"runtime-a"}}""",
        )

        env.repository.openSession("durable-a")
        advanceUntilIdle()

        val refusal = env.rpc.refused.single()
        assertEquals("srq-bridge", refusal.id)
        assertEquals(JSON_RPC_METHOD_NOT_FOUND, refusal.code)
        assertTrue("the replayed unknown method is named", refusal.message.contains("terminal.read"))
        assertTrue("and nothing is parked for it", env.repository.pendingInputs.value.isEmpty())
    }

    /**
     * The same replay, with a *known* question in it: that one is the card's,
     * so it is adopted rather than refused, and the unknown beside it is still
     * refused exactly once. Interleaved in one snapshot on purpose — a fix that
     * refused the lot, or ignored the lot, fails on one half or the other.
     */
    @Test
    fun `a replay splits known questions into cards and unknown ones into refusals`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.rpc.resumeOverride = env.rpc.resumeWithOpenRequests(
            "durable-a",
            """{"id":"srq-known","method":"clarify","params":{"session_id":"runtime-a","question":"Proceed?"}}""",
            """{"id":"srq-unknown","method":"preview.read","params":{"session_id":"runtime-a"}}""",
        )

        env.repository.openSession("durable-a")
        advanceUntilIdle()

        assertEquals("the known question is the one card", "srq-known", singlePending(env).key.requestId)
        assertEquals("and the unknown one is answered once", 1, env.rpc.refused.size)
        assertEquals("srq-unknown", env.rpc.refused.single().id)
        assertEquals(JSON_RPC_METHOD_NOT_FOUND, env.rpc.refused.single().code)
    }

    /**
     * A request that arrives only after the advertisement.
     *
     * The fake Gateway here withholds a question from a connection that never
     * advertised, exactly as a current backend does
     * (`tui_gateway/server_requests.py:117-122` @
     * `d177b119e9c56c9ddc0b7379ffce52341ec06584`). Both halves matter and are
     * asserted: asked before the advertisement the card never appears, and
     * asked after it — through the same `advertiseServerRequests()` the
     * connection route uses — it does. Drop the advertisement from production
     * and the second half fails.
     */
    @Test
    fun `a question is withheld until the connection advertises, then delivered`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler), requireAdvertisement = true)
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        assertFalse("an unadvertised connection never said it could answer", env.rpc.advertised)
        env.rpc.ask("srq-too-early", "clarify", CLARIFY_SINGLE)
        advanceUntilIdle()

        assertTrue(
            "a current backend writes no frame for a client that never advertised",
            env.repository.pendingInputs.value.isEmpty(),
        )

        // The one call the connection makes on every dial, before any session
        // is activated.
        env.rpc.advertiseServerRequests()
        assertTrue("the connection advertised before any session was activated", env.rpc.advertised)
        env.rpc.ask("srq-after", "clarify", CLARIFY_SINGLE)
        advanceUntilIdle()

        assertEquals("the parked question is the delivered one", "srq-after", singlePending(env).key.requestId)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a clarify request parks its session with parsed choices`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-1", "clarify", CLARIFY_SINGLE)
        advanceUntilIdle()

        val pending = singlePending(env)
        assertTrue(pending is ClarifyPending)
        assertEquals(listOf("Yes", "No"), (pending as ClarifyPending).choices)
        assertEquals("srq-1", pending.key.requestId)
        assertEquals(PendingInputKind.Clarify, pending.key.kind)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `batch clarify accepts the gateway qid wire key`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask(
            "srq-batch",
            "clarify",
            """{"questions":[{"qid":"q0","question":"Choose a route","choices":["Remote","Local"],"multi_select":false}]}""",
        )
        advanceUntilIdle()

        val pending = singlePending(env) as ClarifyPending
        assertEquals("q0", pending.questions.single().questionId)
        assertEquals("Choose a route", pending.questions.single().question)
        assertEquals(listOf("Remote", "Local"), pending.questions.single().choices)
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `batch clarify accepts the legacy question id wire key`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask(
            "srq-legacy",
            "clarify",
            """{"questions":[{"question_id":"legacy-id","question":"Choose a route"}]}""",
        )
        advanceUntilIdle()

        assertEquals("legacy-id", (singlePending(env) as ClarifyPending).questions.single().questionId)
    }

    @Test
    fun `batch clarify falls back to legacy question id when qid is blank`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask(
            "srq-blank-qid",
            "clarify",
            """{"questions":[{"qid":"","question_id":"legacy-id","question":"Choose a route"}]}""",
        )
        advanceUntilIdle()

        assertEquals("legacy-id", (singlePending(env) as ClarifyPending).questions.single().questionId)
    }

    @Test
    fun `a clarify with no question is discarded`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-empty", "clarify", """{"choices":["Yes"]}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a request method this app has no card for is neither parked nor answered`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        // A desktop bridge: `terminal.read` is a surface this platform does not
        // have, and the honest answer is the backend's own timeout rather than
        // inventing a result the person cannot see.
        env.rpc.ask("srq-read", "terminal.read", """{"start":0,"count":10}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertTrue("an unsupported family takes no frame", env.rpc.answered.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a request for a background session parks there and never on the session on screen`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler), sessions = listOf("durable-a", "durable-b"))
        runCurrent()
        env.repository.openSession("durable-a")
        env.repository.openSession("durable-b")
        advanceUntilIdle()

        env.rpc.ask("srq-bg", "approval", APPROVAL_REQUEST, runtimeId = "runtime-a")
        advanceUntilIdle()

        val pending = singlePending(env)
        assertEquals("durable-a", pending.durableSessionId)
        assertEquals("runtime-a", pending.runtimeSessionId)
        assertEquals("the parked session says so", SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
        assertEquals("the session on screen keeps its own state", SessionStatus.Idle, env.cache.session("durable-b")?.status)
    }

    @Test
    fun `tool progress cannot overwrite a NeedsInput session`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-1", "clarify", CLARIFY_SINGLE)
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
        env.rpc.ask("srq-1", "clarify", CLARIFY_SINGLE)
        runCurrent()

        env.rpc.emit("message.complete", "runtime-a", """{"text":"done"}""")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
    }

    @Test
    fun `a single clarify answer is exactly one response frame carrying the request id`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-1", "clarify", CLARIFY_SINGLE)
        runCurrent()
        val key = singlePending(env).key

        val response = env.repository.respondToPendingInput(
            key,
            PendingInputAction.ClarifyAnswer(mapOf(CLARIFY_SINGLE_QUESTION_ID to "Yes")),
        )

        assertEquals(PendingInputResponse.Resolved, response)
        val frame = env.rpc.answered.single()
        assertEquals("srq-1", frame.id)
        assertEquals("Yes", frame.result.string("answer"))
        assertEquals("no second frame, and no `answers` on a single", 1, frame.result.keys.size)
        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a batch answer locks its question and keeps the card until the last lock`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask(
            "srq-batch",
            "clarify",
            """{"questions":[{"qid":"q0","question":"Route?","choices":["Remote","Local"]},""" +
                """{"qid":"q1","question":"Profile?","choices":["lab","home"]}]}""",
        )
        runCurrent()
        val key = singlePending(env).key

        env.rpc.lockRemaining = listOf("q1")
        val first = env.repository.respondToPendingInput(key, PendingInputAction.ClarifyAnswer(mapOf("q0" to "Remote")))

        assertEquals(PendingInputResponse.PartiallyAnswered, first)
        assertNotNull("the batch still owes q1", env.repository.pendingInputs.value[key])
        assertEquals(SessionStatus.NeedsInput, env.cache.session("durable-a")?.status)
        val lock = env.rpc.calls.last { it.method == "clarify.lock" }
        assertEquals("srq-batch", lock.params.string("request_id"))
        assertEquals("q0", lock.params.string("question_id"))
        assertEquals("Remote", lock.params.string("answer"))
        assertTrue("a batch lock is not a response frame", env.rpc.answered.isEmpty())

        env.rpc.lockRemaining = emptyList()
        val second = env.repository.respondToPendingInput(key, PendingInputAction.ClarifyAnswer(mapOf("q1" to "home")))

        assertEquals(PendingInputResponse.Resolved, second)
        assertTrue("the last lock resolves the whole batch", env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `cancelling a batch is the response frame carrying neither answer nor answers`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask(
            "srq-batch",
            "clarify",
            """{"questions":[{"qid":"q0","question":"Route?","choices":["Remote","Local"]}]}""",
        )
        runCurrent()
        val key = singlePending(env).key

        val response = env.repository.respondToPendingInput(
            key,
            PendingInputAction.ClarifyAnswer(emptyMap(), cancelBatch = true),
        )

        assertEquals(PendingInputResponse.Resolved, response)
        val frame = env.rpc.answered.single()
        assertEquals("srq-batch", frame.id)
        assertTrue("cancel-all carries no result members", frame.result.isEmpty())
        assertTrue(env.repository.pendingInputs.value.isEmpty())
    }

    @Test
    fun `an approval answer is one frame with the offered choice`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-approve-1", "approval", APPROVAL_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val response = env.repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice("Run once"))

        assertEquals(PendingInputResponse.Resolved, response)
        val frame = env.rpc.answered.single()
        assertEquals("srq-approve-1", frame.id)
        assertEquals("Run once", frame.result.string("choice"))
        assertTrue("the queue RPC is not the answer path any more", env.rpc.calls.none { it.method == "approval.respond" })
        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `a choice the gateway did not offer is refused locally`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-approve-1", "approval", APPROVAL_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        val result = env.repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice("Not offered"))

        assertEquals(PendingInputResponse.Retryable, result)
        assertTrue(env.rpc.answered.isEmpty())
        assertNotNull(env.repository.pendingInputs.value[key])
    }

    @Test
    fun `sudo password reaches the wire and the array is wiped afterwards`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-sudo-1", "sudo", "{}")
        runCurrent()
        val key = singlePending(env).key

        val password = CharArray(3) { 'a' + it }
        env.repository.respondToPendingInput(key, PendingInputAction.SudoPassword(password))

        assertTrue("password must be zeroed after use", password.all { it.code == 0 })
        val frame = env.rpc.answered.single()
        assertEquals("srq-sudo-1", frame.id)
        assertEquals("abc", frame.result.string("value"))
        assertTrue(env.repository.pendingInputs.value.isEmpty())
    }

    @Test
    fun `a secret answer carries the value and never the request id alone`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-secret-1", "secret", """{"env_var":"API_KEY","prompt":"Paste it"}""")
        runCurrent()
        val request = singlePending(env)

        assertEquals("API_KEY", (request as SecretPending).envVarLabel)
        val value = "s3cret".toCharArray()
        env.repository.respondToPendingInput(request.key, PendingInputAction.SecretValue(value))

        assertTrue("secret must be zeroed after use", value.all { it.code == 0 })
        assertEquals("s3cret", env.rpc.answered.single().result.string("value"))
    }

    @Test
    fun `a resume re-delivers an unanswered request as a live card`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.rpc.resumeOverride =
            """{"session_id":"runtime-a","resumed":"durable-a","message_count":0,"messages":[],""" +
                """"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},""" +
                """"inflight":null,"running":true,"session_key":"durable-a","started_at":1700001000.125,"status":"working",""" +
                """"open_requests":[{"id":"srq-1","method":"clarify","params":{"session_id":"runtime-a",""" +
                """"question":"Proceed?","choices":["Yes","No"]}}]}"""

        env.repository.openSession("durable-a")
        advanceUntilIdle()

        val pending = singlePending(env)
        assertTrue(pending is ClarifyPending)
        assertEquals("srq-1", pending.key.requestId)
        assertEquals(
            "a restored prompt is what the session is waiting on, even when the snapshot said running",
            SessionStatus.NeedsInput,
            env.cache.session("durable-a")?.status,
        )
    }

    @Test
    fun `a re-delivered request can be answered like a live one`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.rpc.resumeOverride =
            """{"session_id":"runtime-a","resumed":"durable-a","message_count":0,"messages":[],""" +
                """"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},""" +
                """"inflight":null,"running":false,"session_key":"durable-a","started_at":1700001000.125,"status":"idle",""" +
                """"open_requests":[{"id":"srq-sudo","method":"sudo","params":{"session_id":"runtime-a"}}]}"""

        env.repository.openSession("durable-a")
        advanceUntilIdle()
        val key = singlePending(env).key

        env.repository.respondToPendingInput(key, PendingInputAction.SudoPassword("pw".toCharArray()))

        assertEquals("srq-sudo", env.rpc.answered.single().id)
    }

    @Test
    fun `a question for a session this app has never bound is not shown`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()

        env.rpc.ask("srq-orphan", "clarify", CLARIFY_SINGLE, runtimeId = "runtime-nowhere")
        advanceUntilIdle()

        assertTrue(env.repository.pendingInputs.value.isEmpty())
        assertEquals(SessionStatus.Idle, env.cache.session("durable-a")?.status)
    }

    @Test
    fun `transport failure keeps the request pending for retry`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-1", "clarify", CLARIFY_SINGLE)
        runCurrent()
        val key = singlePending(env).key

        env.rpc.sendFailure = GatewayRpcException("The gateway connection could not send the response.")
        val response = env.repository.respondToPendingInput(
            key,
            PendingInputAction.ClarifyAnswer(mapOf(CLARIFY_SINGLE_QUESTION_ID to "yes")),
        )

        assertEquals(PendingInputResponse.Retryable, response)
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
        rpc.ask("srq-1", "clarify", CLARIFY_SINGLE)
        runCurrent()
        assertTrue(repository.pendingInputs.value.isNotEmpty())

        clients.value = null
        state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()

        assertTrue(repository.pendingInputs.value.isEmpty())
    }

    @Test
    fun `answering twice reports resolved, but a request this connection never had cannot be answered`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-approve-1", "approval", APPROVAL_REQUEST)
        runCurrent()
        val key = singlePending(env).key

        env.repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice("Run once"))

        // Retired on this connection: finished business, and a second answer
        // owes the user nothing.
        assertEquals(
            PendingInputResponse.Resolved,
            env.repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice("Run once")),
        )
        assertEquals("a stale answer takes no frame", 1, env.rpc.answered.size)
        // Never seen here. Identical shape, opposite fact.
        assertEquals(
            PendingInputResponse.Unanswerable,
            env.repository.respondToPendingInput(
                key.copy(requestId = "srq-never-seen"),
                PendingInputAction.ApprovalChoice("Run once"),
            ),
        )
    }

    @Test
    fun `a connection change strands its pending requests rather than retiring them`() = runTest {
        val cache = SessionCache()
        cache.upsertSessions(listOf(summary("durable-a")))
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, state, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        runCurrent()
        rpc.ask("srq-approve-1", "approval", APPROVAL_REQUEST)
        runCurrent()
        val key = repository.pendingInputs.value.keys.single()

        clients.value = null
        runCurrent()

        // The Gateway may still have this parked. Reading it as answered is
        // what would let a notification be withdrawn on a lie.
        assertEquals(
            PendingInputResponse.Unanswerable,
            repository.respondToPendingInput(key, PendingInputAction.ApprovalChoice("Run once")),
        )
    }

    @Test
    fun `one response in flight per request, so a second tap sends nothing`() = runTest {
        val env = environment(UnconfinedTestDispatcher(testScheduler))
        runCurrent()
        env.repository.openSession("durable-a")
        advanceUntilIdle()
        env.rpc.ask("srq-1", "clarify", CLARIFY_SINGLE)
        runCurrent()
        val key = singlePending(env).key

        // The frame send is synchronous in the transport, but the guard is what
        // keeps two surfaces — the card and a notification action — from both
        // answering one question. Hold the first answer inside the send.
        env.rpc.blockSend = true
        val first = launch { env.repository.respondToPendingInput(key, PendingInputAction.ClarifyAnswer(mapOf("" to "Yes"))) }
        runCurrent()

        val second = env.repository.respondToPendingInput(key, PendingInputAction.ClarifyAnswer(mapOf("" to "No")))

        assertEquals(PendingInputResponse.Retryable, second)
        env.rpc.releaseSend()
        first.join()
        assertEquals("srq-1", env.rpc.answered.single().id)
        assertEquals("Yes", env.rpc.answered.single().result.string("answer"))
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

    private fun environment(
        scopeDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        sessions: List<String> = listOf("durable-a"),
        /**
         * Make this fake Gateway behave like a current one, which hands a
         * question to a connection only once that connection has advertised
         * (`tui_gateway/server_requests.py:117-122` @
         * `d177b119e9c56c9ddc0b7379ffce52341ec06584`). Off by default so every
         * other test in this file can ask without advertising first.
         */
        requireAdvertisement: Boolean = false,
    ): Environment {
        val scope = CoroutineScope(scopeDispatcher + Job())
        val cache = SessionCache()
        cache.upsertSessions(sessions.map(::summary))
        val rpc = FakeRpc(requireAdvertisement)
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

    private class FakeRpc(
        /** When set, a question is withheld until this connection advertises. */
        private val requireAdvertisement: Boolean = false,
    ) : GatewayRpcClient, GatewayServerRequestResponder {
        private val eventFlow = MutableSharedFlow<GatewayEvent>(replay = 64, extraBufferCapacity = 64)
        private val requestFlow = MutableSharedFlow<GatewayServerRequest>(replay = 64, extraBufferCapacity = 64)
        override val events = eventFlow
        override val serverRequests = requestFlow
        val calls = mutableListOf<RpcCall>()
        val answered = mutableListOf<AnswerFrame>()
        val refused = mutableListOf<RefusalFrame>()

        /** What `clarify.lock` answers with; the last lock empties it. */
        var lockRemaining: List<String>? = null

        /** When set, every answer fails the way a closed or broken leg does. */
        var sendFailure: GatewayRpcException? = null

        /** Holds the answer inside the transport, for the one-at-a-time guard. */
        var blockSend = false
        private var sendGate: CompletableDeferred<Unit>? = null

        /** Whether this connection has said it answers server→client requests. */
        var advertised = false
            private set

        fun releaseSend() {
            sendGate?.complete(Unit)
        }

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += RpcCall(method, params)
            if (method == CLIENT_CAPABILITIES_METHOD) advertised = true
            return when (method) {
                "session.list" -> json("""{"sessions":[]}""")
                "session.resume" -> json(resumeOverride ?: resumeBody(params.string("session_id").orEmpty()))
                "session.history" -> json("""{"messages":[],"count":0}""")
                "session.activate" -> json("{}")
                "clarify.lock" -> {
                    val remaining = lockRemaining
                    json(
                        if (remaining == null) """{"status":"expired"}"""
                        else """{"status":"ok","remaining":[${remaining.joinToString(",") { "\"$it\"" }}]}""",
                    )
                }

                else -> json("{}")
            }
        }

        override suspend fun respondToServerRequest(id: String, result: JsonObject) {
            sendFailure?.let { throw it }
            if (blockSend) {
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                sendGate = gate
                gate.await()
            }
            answered += AnswerFrame(id, result)
        }

        override suspend fun failServerRequest(id: String, code: Int, message: String) {
            sendFailure?.let { throw it }
            refused += RefusalFrame(id, code, message)
        }

        /** Overrides the canned resume snapshot for one test. */
        var resumeOverride: String? = null

        /** The resume snapshot for [durableId], one runtime per durable id. */
        fun resumeBody(durableId: String): String {
            val runtime = "runtime-" + durableId.removePrefix("durable-")
            return """{"session_id":"$runtime","resumed":"$durableId","message_count":0,"messages":[],""" +
                """"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},""" +
                """"inflight":null,"running":false,"session_key":"$durableId","started_at":1700001000.125,"status":"idle"}"""
        }

        /**
         * The same resume snapshot, plus the `open_requests` a reconnect
         * re-delivers — each entry exactly as `snapshot()` writes it
         * (`tui_gateway/server_requests.py:66-71` @ the snapshot).
         */
        fun resumeWithOpenRequests(durableId: String, vararg entries: String): String =
            resumeBody(durableId).dropLast(1) + ""","open_requests":[${entries.joinToString(",")}]}"""

        fun emit(type: String, runtimeId: String?, payload: JsonElement = JsonNull) {
            check(eventFlow.tryEmit(GatewayEvent(type, runtimeId, payload)))
        }

        fun emit(type: String, runtimeId: String?, payload: String) = emit(type, runtimeId, json(payload))

        /** One server→client request, exactly as the socket hands it over. */
        fun ask(id: String, method: String, body: String = "{}", runtimeId: String = "runtime-a") {
            // A current backend does not write the frame at all for a client
            // that never advertised, so a fake that still delivered one would
            // hand this suite a prompt the real Gateway withholds — and every
            // regression test below would pass against the bug.
            if (requireAdvertisement && !advertised) return
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
        const val CLARIFY_SINGLE =
            """{"question":"Proceed?","choices":["Yes","No"],"multi_select":false}"""
        const val APPROVAL_REQUEST = """{"request_id":"queue-1","command":"rm -rf build","choices":["Run once","Reject"]}"""
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
