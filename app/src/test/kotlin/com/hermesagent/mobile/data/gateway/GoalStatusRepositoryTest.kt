package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoalStatusRepositoryTest {
    @Test
    fun `structured no goal clears only cached goal without a legacy control command`() = runTest {
        val cache = SessionCache()
        val rpc = GoalPeer()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        val status = ComposerStatusState(
            goal = ComposerGoalStatus("Old goal", ComposerGoalState.Active, "Old goal"),
            gatewayQueuedPrompts = listOf(ComposerGatewayQueuedPrompt("queued", "Keep queued work")),
            isCompacting = true,
        )
        cache.upsertSession(cache.session("durable-goal")!!.copy(composerStatus = status))

        val result = repository.goalStatus("durable-goal")
        assertTrue("structured no-goal must succeed, got $result", result is GatewayGoalStatusOutcome.Available)
        assertNull((result as GatewayGoalStatusOutcome.Available).goal)
        assertEquals(status.copy(goal = null), cache.session("durable-goal")!!.composerStatus)
        assertEquals(JsonPrimitive("runtime-goal"), rpc.calls.single { it.first == "session.control.read" }.second["session_id"])
        assertFalse(rpc.calls.any { it.first == "slash.exec" })
    }

    @Test
    fun `goal read completed during open history survives the older resume snapshot`() = runTest {
        val cache = SessionCache().apply {
            upsertSession(SessionSummary("durable-goal", "Public", "", 0))
        }
        val rpc = GoalPeer().apply {
            historyGate = kotlinx.coroutines.CompletableDeferred()
            control = """{"control":{"goal":{"title":"Fresh structured goal","status":"active"}}}"""
        }
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        val opening = async { repository.openSession("durable-goal") }
        runCurrent()
        assertTrue(rpc.calls.any { it.first == "session.history" })
        assertFalse(opening.isCompleted)

        val outcome = repository.goalStatus("durable-goal")
        assertTrue(outcome is GatewayGoalStatusOutcome.Available)
        val goal = (outcome as GatewayGoalStatusOutcome.Available).goal!!
        assertEquals("Fresh structured goal", goal.title)
        assertEquals(goal, cache.session("durable-goal")!!.composerStatus?.goal)

        rpc.historyGate!!.complete(Unit)
        assertEquals("durable-goal", opening.await())
        assertEquals(goal, cache.session("durable-goal")!!.composerStatus?.goal)
        assertEquals(1, rpc.calls.count { it.first == "session.resume" })
    }

    @Test
    fun `newer streamed goal wins over a suspended structured or legacy read`() = runTest {
        for (legacy in listOf(false, true)) {
            val cache = SessionCache()
            val rpc = GoalPeer().apply {
                control = """{"control":{"goal":{"title":"Older read goal","status":"active"}}}"""
                if (legacy) {
                    controlFailure = GatewayRpcError(-32601, "Method not found")
                    legacyGate = kotlinx.coroutines.CompletableDeferred()
                    this.legacy = """{"output":"No active goal"}"""
                } else {
                    controlGate = kotlinx.coroutines.CompletableDeferred()
                }
            }
            val repository = LiveGatewaySessionRepository(cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
            runCurrent()
            repository.openSession("durable-goal")
            val read = async { repository.goalStatus("durable-goal") }
            runCurrent()
            assertTrue(rpc.calls.any { it.first == if (legacy) "slash.exec" else "session.control.read" })
            assertFalse(read.isCompleted)

            rpc.events.emit(GatewayEvent("status.update", "runtime-goal",
                Json.parseToJsonElement("""{"kind":"goal","text":"⏸ Goal paused: New streamed goal"}""")))
            runCurrent()
            val streamed = cache.session("durable-goal")!!.composerStatus?.goal!!
            assertEquals(ComposerGoalState.Paused, streamed.state)
            assertEquals("New streamed goal", streamed.title)

            (if (legacy) rpc.legacyGate else rpc.controlGate)!!.complete(Unit)
            assertEquals(GatewayGoalStatusOutcome.Failed, read.await())
            assertEquals("legacy=$legacy", streamed, cache.session("durable-goal")!!.composerStatus?.goal)
        }
    }

    @Test
    fun `producer goal snapshots hydrate active paused waiting and done with safe display text`() = runTest {
        val cache = SessionCache()
        val rpc = GoalPeer()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        val secret = "synthetic-goal-secret"
        val safeDetail = "Authorization: Bearer <redacted>"
        for ((status, expected) in listOf("active" to ComposerGoalState.Active,
            "paused" to ComposerGoalState.Paused, "waiting" to ComposerGoalState.Waiting,
            "done" to ComposerGoalState.Done)) {
            val waiting = if (status == "waiting") ""","wait_barrier":{"type":"session","target":"public-child","reason":"Authorization: Bearer $secret"}""" else ""
            rpc.control = """{"control":{"goal":{"title":"Ship public work","status":"${if (status == "waiting") "active" else status}","turns_used":2,"max_turns":10,"contract":{"outcome":"Ship public work","verification":"Public checks","constraints":"","boundaries":"","stop_when":""},"subgoals":[],"gates":[],"paused_reason":"Authorization: Bearer $secret","last_reason":"Authorization: Bearer $secret"$waiting},"loop":null,"heartbeat":null,"revision":"public-revision","updated_at":1}}"""
            val outcome = repository.goalStatus("durable-goal")
            assertTrue("$status snapshot should succeed: $outcome", outcome is GatewayGoalStatusOutcome.Available)
            val goal = (outcome as GatewayGoalStatusOutcome.Available).goal!!
            assertEquals(expected, goal.state)
            assertEquals("Ship public work", goal.title)
            assertEquals(safeDetail, goal.detail)
            assertEquals("Ship public work — $safeDetail", goal.rawText)
            for (display in listOfNotNull(goal.title, goal.detail, goal.rawText)) {
                assertFalse("$status display must redact the supplied marker", display.contains(secret))
            }
            assertEquals(goal, cache.session("durable-goal")!!.composerStatus?.goal)
        }
        assertFalse(rpc.calls.any { it.first == "slash.exec" })
    }

    @Test
    fun `method not found alone falls back to legacy goal status and exact no goal clears cache`() = runTest {
        val cache = SessionCache()
        val rpc = GoalPeer().apply {
            controlFailure = GatewayRpcError(-32601, "Method not found")
            legacy = """{"output":"No active goal. Set one with /goal <text>."}"""
        }
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        cache.upsertSession(cache.session("durable-goal")!!.copy(composerStatus = ComposerStatusState(
            goal = ComposerGoalStatus("Old goal", ComposerGoalState.Active))))
        assertEquals(GatewayGoalStatusOutcome.Available(null), repository.goalStatus("durable-goal"))
        assertNull(cache.session("durable-goal")!!.composerStatus?.goal)
        val legacyCall = rpc.calls.single { it.first == "slash.exec" }.second
        assertEquals(JsonPrimitive("runtime-goal"), legacyCall["session_id"])
        assertEquals(JsonPrimitive("goal status"), legacyCall["command"])
    }

    @Test
    fun `genuine read refusals remain failures without compatibility fallback or cache loss`() = runTest {
        val cache = SessionCache()
        val rpc = GoalPeer()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        val prior = ComposerGoalStatus("Known goal", ComposerGoalState.Active)
        cache.upsertSession(cache.session("durable-goal")!!.copy(composerStatus = ComposerStatusState(goal = prior)))
        for (failure in listOf(GatewayRpcError(-32000, "permission_denied"),
            GatewayRpcError(-32000, "unsupported_projection"),
            GatewayRpcError(-32000, "Method not found in permitted scope"),
            GatewayRpcException("transport failed"))) {
            rpc.controlFailure = failure
            assertEquals("${failure.message} must stay observable", GatewayGoalStatusOutcome.Failed, repository.goalStatus("durable-goal"))
            assertEquals(prior, cache.session("durable-goal")!!.composerStatus?.goal)
        }
        assertFalse(rpc.calls.any { it.first == "slash.exec" })
    }

    @Test
    fun `goal read cancellation propagates instead of becoming a failure`() = runTest {
        val rpc = GoalPeer().apply { controlFailure = kotlinx.coroutines.CancellationException("cancel goal read") }
        val repository = LiveGatewaySessionRepository(SessionCache(),
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        assertTrue(runCatching { repository.goalStatus("durable-goal") }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(rpc.calls.any { it.first == "slash.exec" })
    }

    @Test
    fun `automatic read lease revocation fences publication and compatibility fallback`() = runTest {
        for (legacy in listOf(false, true)) {
            val cache = SessionCache()
            val rpc = GoalPeer().apply { controlGate = kotlinx.coroutines.CompletableDeferred() }
            val repository = LiveGatewaySessionRepository(cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
            runCurrent()
            repository.openSession("durable-goal")
            val prior = ComposerGoalStatus("Keep current", ComposerGoalState.Active)
            cache.upsertSession(cache.session("durable-goal")!!.copy(composerStatus = ComposerStatusState(goal = prior)))
            var current = true
            val read = async(GoalStatusReadLease { current }) { repository.goalStatus("durable-goal") }
            runCurrent()
            current = false
            if (legacy) rpc.controlFailure = GatewayRpcError(-32601, "Method not found")
            rpc.controlGate!!.complete(Unit)
            runCurrent()
            assertEquals(GatewayGoalStatusOutcome.Failed, read.await())
            assertEquals(prior, cache.session("durable-goal")!!.composerStatus?.goal)
            assertFalse(rpc.calls.any { it.first == "slash.exec" })
        }
    }

    @Test
    fun `malformed structured goal is a failure rather than absence or compatibility`() = runTest {
        val cache = SessionCache()
        val rpc = GoalPeer()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        val prior = ComposerGoalStatus("Known goal", ComposerGoalState.Active)
        cache.upsertSession(cache.session("durable-goal")!!.copy(composerStatus = ComposerStatusState(goal = prior)))
        for (body in listOf("{}", """{"control":null}""", """{"control":{}}""",
            """{"control":{"goal":false}}""", """{"control":{"goal":{}}}""",
            """{"control":{"goal":{"title":42,"status":"active"}}}""",
            """{"control":{"goal":{"title":"Public goal","status":"unknown"}}}""",
            """{"control":{"goal":{"title":"Public goal","status":"active","wait_barrier":"bad"}}}""",
            """{"control":{"goal":{"title":"Public goal","status":"active","wait_barrier":{}}}}""")) {
            rpc.control = body
            assertEquals(body, GatewayGoalStatusOutcome.Failed, repository.goalStatus("durable-goal"))
            assertEquals(prior, cache.session("durable-goal")!!.composerStatus?.goal)
        }
        assertFalse(rpc.calls.any { it.first == "slash.exec" })
    }

    @Test
    fun `late unsupported legacy response cannot poison a replacement automatic read`() = runTest {
        val rpc = GoalPeer().apply {
            controlFailure = GatewayRpcError(-32601, "Method not found")
            legacyGate = kotlinx.coroutines.CompletableDeferred()
            legacyFailure = GatewayRpcError(-32601, "Method not found")
        }
        val repository = LiveGatewaySessionRepository(SessionCache(),
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        var current = true
        val read = async(GoalStatusReadLease { current }) { repository.goalStatus("durable-goal") }
        runCurrent()
        current = false
        rpc.legacyGate!!.complete(Unit)
        assertEquals(GatewayGoalStatusOutcome.Failed, read.await())
        rpc.controlFailure = null
        assertEquals(GatewayGoalStatusOutcome.Available(null), repository.goalStatus("durable-goal"))
    }

    @Test
    fun `late goal responses cannot publish across endpoint or runtime replacement`() = runTest {
        for (replacement in listOf("endpoint", "runtime", "client")) {
            val cache = SessionCache()
            val rpc = GoalPeer().apply { controlGate = kotlinx.coroutines.CompletableDeferred() }
            val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
            val repository = LiveGatewaySessionRepository(cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                clients, backgroundScope) { 1_800_000_000_000 }
            runCurrent()
            repository.openSession("durable-goal")
            val read = async { repository.goalStatus("durable-goal") }
            runCurrent()
            when (replacement) {
                "endpoint" -> cache.resetForEndpointSwitch()
                "client" -> { clients.value = GoalPeer(); runCurrent() }
                else -> {
                    rpc.runtime = "runtime-replaced"
                    repository.openSession("durable-goal", "another-profile")
                }
            }
            val prior = ComposerGoalStatus("Replacement goal", ComposerGoalState.Active)
            if (cache.session("durable-goal") == null) cache.upsertSession(SessionSummary("durable-goal", "Public", "", 0))
            cache.upsertSession(cache.session("durable-goal")!!.copy(composerStatus = ComposerStatusState(goal = prior)))
            rpc.controlGate!!.complete(Unit)
            assertEquals(replacement, GatewayGoalStatusOutcome.Failed, read.await())
            assertEquals(replacement, prior, cache.session("durable-goal")!!.composerStatus?.goal)
        }
    }

    @Test
    fun `transport timeout propagates without invoking legacy control command`() = runTest {
        val rpc = GoalPeer().apply { controlGate = kotlinx.coroutines.CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(SessionCache(),
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        val failure = runCatching {
            kotlinx.coroutines.withTimeout(100) { repository.goalStatus("durable-goal") }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        assertFalse(rpc.calls.any { it.first == "slash.exec" })
    }

    @Test
    fun `valid wait barrier variants and long secret display stay safe and bounded`() = runTest {
        val rpc = GoalPeer()
        val repository = LiveGatewaySessionRepository(SessionCache(),
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1_800_000_000_000 }
        runCurrent()
        repository.openSession("durable-goal")
        val secret = "synthetic-display-secret"
        val safeText = "Authorization: Bearer <redacted>"
        for (barrier in listOf("""{"type":"until","until_at":1800000000,"reason":"Public wait"}""",
            """{"type":"session","target":"public-child","reason":"Public wait"}""",
            """{"type":"pid","target":123,"reason":"Public wait"}""")) {
            rpc.control = """{"control":{"goal":{"title":"Authorization: Bearer $secret","status":"active","wait_barrier":$barrier}}}"""
            val goal = (repository.goalStatus("durable-goal") as GatewayGoalStatusOutcome.Available).goal!!
            assertEquals(ComposerGoalState.Waiting, goal.state)
            assertEquals(safeText, goal.title)
            assertEquals("Public wait", goal.detail)
            assertEquals("$safeText — Public wait", goal.rawText)
            for (display in listOfNotNull(goal.title, goal.detail, goal.rawText)) {
                assertFalse("display must redact the supplied marker", display.contains(secret))
            }
        }
        rpc.control = """{"control":{"goal":{"title":"${"Public ".repeat(500)}","status":"active"}}}"""
        val goal = (repository.goalStatus("durable-goal") as GatewayGoalStatusOutcome.Available).goal!!
        assertEquals(safeGatewayStatusText("Public ".repeat(500)), goal.title)
        assertEquals(goal.title, goal.rawText)
    }

    private class GoalPeer : GatewayRpcClient {
        override val events = MutableSharedFlow<GatewayEvent>()
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var runtime = "runtime-goal"
        var historyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var controlGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var controlFailure: Throwable? = null
        var legacyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var legacyFailure: Throwable? = null
        var legacy = """{"output":"No active goal"}"""
        var control = """{"control":{"goal":null,"loop":null,"heartbeat":null,"revision":"","updated_at":0}}"""
        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += method to params
            return Json.parseToJsonElement(when (method) {
                "session.resume" -> """{"session_id":"$runtime","session_key":"durable-goal","messages":[],"message_count":0,"info":{},"running":false}"""
                "session.history" -> { historyGate?.await(); """{"messages":[],"count":0}""" }
                "session.list", "session.active_list" -> """{"sessions":[]}"""
                "projects.tree" -> """{"projects":[]}"""
                "session.control.read" -> { controlGate?.await(); controlFailure?.let { throw it }; control }
                "slash.exec" -> { legacyGate?.await(); legacyFailure?.let { throw it }; legacy }
                else -> "{}"
            })
        }
        override fun close() = Unit
    }
}
