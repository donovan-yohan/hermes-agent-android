package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.UserTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySessionRepositoryTest {

    @Test
    fun `list and history map representative gateway payloads`() {
        val sessions = parseSessionList(json(SESSION_LIST), nowMillis = 99)
        assertEquals(2, sessions.size)
        assertEquals("durable-a", sessions[0].id)
        assertEquals("Remote work", sessions[0].title)
        assertEquals(7, sessions[0].messageCount)
        assertEquals("desktop", sessions[0].source)
        assertEquals(1_700_000_123_456, sessions[0].lastActiveAtMillis)
        assertEquals(1_700_000_456_789, sessions[1].lastActiveAtMillis)

        val history = parseHistory(json(HISTORY), "runtime-a", 99)
        assertEquals(listOf("row-u", "row-a", "runtime-a-history-2"), history.map { it.id })
        assertEquals("hello", (history[0] as UserTurn).text)
        assertEquals("hi", (history[1] as AssistantTurn).markdown)
        assertEquals("Read", (history[2] as ToolActivity).label)
        assertEquals("Read file.txt", (history[2] as ToolActivity).detail)
    }

    @Test
    fun `history preserves reasoning terminal payload and inline diff structure`() {
        val history = parseHistory(
            json(
                """{
                    "messages": [
                      {
                        "id": "assistant-rich",
                        "role": "assistant",
                        "duration_s": 2.4,
                        "content": [
                          {"type": "reasoning", "text": "inspect the build first"},
                          {"type": "text", "text": "done"}
                        ]
                      },
                      {
                        "id": "tool-rich",
                        "role": "tool",
                        "name": "terminal",
                        "context": "./gradlew check",
                        "args": {"command": "./gradlew check"},
                        "result": {"output": "BUILD SUCCESSFUL", "exit_code": 0},
                        "inline_diff": "--- a/demo.kt\n+++ b/demo.kt\n-old\n+new",
                        "duration_s": 4.2
                      }
                    ]
                }""".trimIndent(),
            ),
            runtimeId = "runtime-rich",
            nowMillis = 99,
        )

        assertEquals(listOf("assistant-rich-reasoning", "assistant-rich", "tool-rich"), history.map { it.id })
        val reasoning = history[0] as ReasoningActivity
        assertEquals("inspect the build first", reasoning.text)
        assertEquals(2.4, reasoning.elapsedSeconds, 0.0)
        assertEquals("done", (history[1] as AssistantTurn).markdown)
        val tool = history[2] as ToolActivity
        assertEquals("terminal", tool.toolName)
        assertTrue(tool.argsText.orEmpty().contains("./gradlew check"))
        assertTrue(tool.resultText.orEmpty().contains("BUILD SUCCESSFUL"))
        assertTrue(tool.inlineDiff.orEmpty().contains("+++ b/demo.kt"))
        assertEquals(4.2, tool.elapsedSeconds, 0.0)
    }

    @Test
    fun `live reasoning and tool lifecycle retain measured payloads`() = runTest {
        var now = CLOCK
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { now }
        runCurrent()
        repository.openSession("durable-a")

        rpc.emit("reasoning.delta", "runtime-a", """{"delta":"checking "}""")
        runCurrent()
        now += 2_000
        rpc.emit("reasoning.available", "runtime-a", """{"text":"checking the build"}""")
        rpc.emit(
            "tool.start",
            "runtime-a",
            """{"tool_id":"tool-1","name":"terminal","arguments":{"command":"./gradlew check"},"context":"./gradlew check"}""",
        )
        runCurrent()
        now += 3_000
        rpc.emit(
            "tool.complete",
            "runtime-a",
            """{"tool_id":"tool-1","name":"terminal","summary":"Authorization: Bearer fake-live-token","result":{"output":"ok","exit_code":0},"inline_diff":"--- a/A.kt\n+++ b/A.kt\n-old\n+new"}""",
        )
        runCurrent()

        val reasoning = cache.transcript("durable-a").filterIsInstance<ReasoningActivity>().single()
        assertEquals(ToolState.Done, reasoning.state)
        assertEquals("checking the build", reasoning.text)
        assertEquals(2.0, reasoning.elapsedSeconds, 0.0)
        val tool = cache.transcript("durable-a").filterIsInstance<ToolActivity>().single()
        assertEquals(ToolState.Done, tool.state)
        assertEquals(3.0, tool.elapsedSeconds, 0.0)
        assertTrue(tool.argsText.orEmpty().contains("./gradlew check"))
        assertTrue(tool.detail.contains("<redacted>"))
        assertFalse(tool.detail.contains("fake-live-token"))
        assertTrue(tool.resultText.orEmpty().contains("\"output\":\"ok\""))
        assertTrue(tool.inlineDiff.orEmpty().contains("+++ b/A.kt"))
    }

    @Test
    fun `live tool detail redacts a long private key before truncating it`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        val privateKey = "-----BEGIN PRIVATE KEY-----\n" +
            "A".repeat(5_000) +
            "\n-----END PRIVATE KEY-----"

        rpc.emit(
            "tool.complete",
            "runtime-a",
            buildJsonObject {
                put("tool_id", JsonPrimitive("tool-private-key"))
                put("name", JsonPrimitive("terminal"))
                put("summary", JsonPrimitive(privateKey))
            }.toString(),
        )
        runCurrent()

        val detail = cache.transcript("durable-a").filterIsInstance<ToolActivity>().single().detail
        assertTrue("private-key redaction marker missing", detail.contains("<redacted>"))
        assertTrue("redacted detail remained unexpectedly long", detail.length < 100)
        assertFalse("private-key body survived", detail.contains("A".repeat(100)))
    }

    @Test
    fun `historical tool detail is redacted before display`() {
        val history = parseHistory(
            json(
                """{"messages":[{
                    "id":"tool-sensitive",
                    "role":"tool",
                    "name":"terminal",
                    "context":"Authorization: Bearer fake-history-token"
                }]}""".trimIndent(),
            ),
            runtimeId = "runtime-sensitive",
            nowMillis = CLOCK,
        )

        val detail = history.filterIsInstance<ToolActivity>().single().detail
        assertTrue(detail.contains("<redacted>"))
        assertFalse(detail.contains("fake-history-token"))
    }

    @Test
    fun `late unscoped terminal event cannot settle another active runtime`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "local turn")
        repository.openSession("durable-b")
        rpc.emit("session.info", "runtime-b", """{"running":true}""")
        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()

        rpc.emit("error", null, """{"message":"late failure from A"}""")
        runCurrent()

        assertEquals(SessionStatus.Working, cache.session("durable-b")?.status)
        assertTrue(cache.transcript("durable-b").filterIsInstance<AssistantTurn>().none { it.error != null })
    }

    @Test
    fun `session refresh preserves active timer origin`() = runTest {
        var now = CLOCK
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { now }
        runCurrent()
        repository.openSession("durable-a")
        rpc.emit("session.info", "runtime-a", """{"running":true}""")
        runCurrent()
        val startedAt = cache.session("durable-a")?.activityStartedAtMillis

        now += 9_000
        repository.refreshSessions()

        assertEquals(startedAt, cache.session("durable-a")?.activityStartedAtMillis)
    }

    @Test
    fun `terminal turn completion measures a tool missing its complete event`() = runTest {
        var now = CLOCK
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { now }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "run it")
        rpc.emit("tool.start", "runtime-a", """{"tool_id":"tool-sealed","name":"terminal"}""")
        runCurrent()
        now += 2_500

        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()

        val tool = cache.transcript("durable-a").filterIsInstance<ToolActivity>().single()
        assertEquals(ToolState.Done, tool.state)
        assertEquals(2.5, tool.elapsedSeconds, 0.0)
    }

    @Test
    fun `nullable gateway strings stay absent instead of rendering null`() {
        val session = parseSession(
            json("""{"id":"durable-null","title":null,"preview":null,"source":null}""") as JsonObject,
            nowMillis = 99,
        )

        assertEquals("New session", session.title)
        assertEquals("", session.preview)
        assertEquals(null, session.source)
    }

    @Test
    fun `project overview and drill in keep backend identity and membership`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.refreshProjects()
        val catalog = cache.state.value.projects
        assertTrue(catalog.available == true)
        assertEquals("project-mobile", catalog.activeProjectId)
        assertEquals(listOf("__no_project__", "project-mobile"), catalog.projects.keys.toList())
        assertEquals("Project preview", catalog.projects.getValue("project-mobile").previewSessions.single().title)
        assertEquals(3, rpc.call("projects.tree").params["preview_limit"]?.toString()?.toInt())

        repository.openProject("project-mobile")

        assertEquals(listOf("durable-a", "durable-b"), cache.state.value.projects.memberships["project-mobile"])
        assertEquals("Project detail A", cache.session("durable-a")?.title)
        assertEquals("project-mobile", rpc.call("projects.project_sessions").params.string("project_id"))
    }

    @Test
    fun `project creation sends the Desktop contract then refreshes backend truth`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        rpc.calls.clear()

        val outcome = repository.createProject("  Demo  ", "  /srv/demo  ")

        assertEquals("project-created", outcome.projectId)
        assertTrue(outcome.catalogRefreshed)
        val create = rpc.call("projects.create")
        assertEquals("Demo", create.params.string("name"))
        assertEquals("/srv/demo", create.params.string("primary_path"))
        assertEquals("[\"/srv/demo\"]", create.params["folders"].toString())
        assertEquals("true", create.params["use"].toString())
        assertTrue(rpc.calls.any { it.method == "projects.tree" })
    }

    @Test
    fun `project creation stays successful when catalog reconciliation fails`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        rpc.projectTreeFailure = GatewayRpcException("refresh failed")

        val outcome = repository.createProject("Demo", "/srv/demo")

        assertEquals("project-created", outcome.projectId)
        assertFalse(outcome.catalogRefreshed)
        assertEquals(1, rpc.calls.count { it.method == "projects.create" })
    }

    @Test
    fun `missing project RPC keeps legacy session navigation available`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            projectTreeFailure = GatewayRpcError(-32601, "Method not found")
        }
        LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }

        runCurrent()

        assertTrue(cache.state.value.projects.available == false)
        assertEquals(listOf("durable-a", "durable-b"), cache.state.value.sessions.keys.toList())
    }

    @Test
    fun `stale project snapshot cannot cross a reconnect generation`() = runTest {
        val cache = SessionCache()
        val staleProjectTree = CompletableDeferred<JsonElement>()
        val oldRpc = FakeRpc().apply { projectTreeResponse = staleProjectTree }
        val clients = MutableStateFlow<GatewayRpcClient?>(oldRpc)
        LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        val currentRpc = FakeRpc().apply { projectTreeResult = PROJECT_TREE_RECONNECTED }
        clients.value = currentRpc
        runCurrent()
        staleProjectTree.complete(json(PROJECT_TREE))
        runCurrent()

        assertEquals(listOf("project-reconnected"), cache.state.value.projects.projects.keys.toList())
        assertTrue(currentRpc.calls.any { it.method == "projects.tree" })
    }

    @Test
    fun `newer catalog refresh wins over an in flight project detail snapshot`() = runTest {
        val cache = SessionCache()
        val projectDetails = CompletableDeferred<JsonElement>()
        val rpc = FakeRpc().apply { projectDetailsResponse = projectDetails }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        backgroundScope.launch { repository.openProject("project-mobile") }
        runCurrent()
        rpc.projectTreeResult = PROJECT_TREE_RECONNECTED
        backgroundScope.launch { repository.refreshProjects() }
        runCurrent()
        projectDetails.complete(json(PROJECT_DETAILS))
        runCurrent()

        assertEquals(listOf("project-reconnected"), cache.state.value.projects.projects.keys.toList())
        assertTrue("project-mobile" !in cache.state.value.projects.memberships)
    }

    @Test
    fun `durable ids resume once and runtime ids drive activate history submit and interrupt`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()

        repository.refreshSessions()
        repository.openSession("durable-a")
        assertEquals("Remote work", cache.session("durable-a")?.title)
        assertEquals("latest", cache.session("durable-a")?.preview)
        assertEquals(7, cache.session("durable-a")?.messageCount)
        repository.openSession("durable-a")
        repository.submit("durable-a", "ship it")
        repository.interrupt("durable-a")

        assertEquals(1, rpc.calls.count { it.method == "session.resume" })
        assertEquals("durable-a", rpc.call("session.resume").params.string("session_id"))
        assertEquals("runtime-a", rpc.call("session.activate").params.string("session_id"))
        assertEquals("runtime-a", rpc.call("session.history").params.string("session_id"))
        assertEquals("runtime-a", rpc.call("prompt.submit").params.string("session_id"))
        assertEquals("ship it", rpc.call("prompt.submit").params.string("text"))
        assertEquals("runtime-a", rpc.call("session.interrupt").params.string("session_id"))
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertEquals("ship it", (cache.transcript("durable-a").last() as UserTurn).text)
    }

    @Test
    fun `unscoped stream remains pinned when another session is opened`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")
        repository.submit("durable-a", "keep ownership")
        repository.openSession("durable-b")
        rpc.emit("message.start", null, """{"id":"reply-a","role":"assistant"}""")
        rpc.emit("message.delta", null, """{"delta":"owned by A"}""")
        rpc.emit("message.complete", null, "{}")
        runCurrent()

        val reply = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().single()
        assertEquals("owned by A", reply.markdown)
        assertFalse(reply.streaming)
        assertTrue(cache.transcript("durable-b").isEmpty())
    }

    @Test
    fun `a second submit is refused while unscoped events belong to the first turn`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")
        repository.submit("durable-a", "first")
        repository.openSession("durable-b")
        val failure = runCatching { repository.submit("durable-b", "second") }.exceptionOrNull()

        assertTrue(failure is GatewayRpcException)
        assertEquals(1, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `a rejected submit releases unscoped event ownership for retry`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { promptFailures = 1 }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        assertTrue(runCatching { repository.submit("durable-a", "rejected") }.isFailure)
        repository.submit("durable-a", "retry")

        assertEquals(2, rpc.calls.count { it.method == "prompt.submit" })
        assertEquals("retry", (cache.transcript("durable-a").last() as UserTurn).text)
    }

    @Test
    fun `events emitted before submit response stay ordered after the user turn`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { completeDuringSubmit = true }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        repository.submit("durable-a", "ordered")
        runCurrent()

        val transcript = cache.transcript("durable-a")
        assertTrue(transcript[0] is UserTurn)
        assertEquals("reply", (transcript[1] as AssistantTurn).markdown)
        assertFalse((transcript[1] as AssistantTurn).streaming)
    }

    @Test
    fun `terminal session info releases the submit guard and settles a partial assistant`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first")
        rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"partial"}""")
        rpc.emit("session.info", "runtime-a", """{"id":"durable-a","running":false}""")
        runCurrent()

        val assistant = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().single()
        assertEquals("partial", assistant.markdown)
        assertFalse(assistant.streaming)
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)

        repository.submit("durable-a", "second")
        assertEquals(2, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `partial session info preserves a submitted turn's working state`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first")
        rpc.emit("session.info", "runtime-a", """{"stored_session_id":"durable-a"}""")
        runCurrent()

        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertEquals("Remote work", cache.session("durable-a")?.title)
        assertEquals("first", cache.session("durable-a")?.preview)
        assertEquals(8, cache.session("durable-a")?.messageCount)
        assertTrue(runCatching { repository.submit("durable-a", "second") }.exceptionOrNull() is GatewayRpcException)
    }

    @Test
    fun `session info heartbeat retains cached metadata and binds its stored id`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        rpc.emit("session.info", "runtime-a", """{"stored_session_id":"durable-a","running":true}""")
        runCurrent()

        assertEquals("Remote work", cache.session("durable-a")?.title)
        assertEquals("latest", cache.session("durable-a")?.preview)
        assertEquals(7, cache.session("durable-a")?.messageCount)
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
    }

    @Test
    fun `pre-start terminal session info waits before releasing the submit guard`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache = cache,
            connectionStateFlow = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clientFlow = MutableStateFlow<GatewayRpcClient?>(rpc),
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
        )
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first")
        rpc.emit("session.info", "runtime-a", """{"id":"durable-a","running":false}""")
        runCurrent()

        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertTrue(runCatching { repository.submit("durable-a", "second") }.exceptionOrNull() is GatewayRpcException)

        advanceTimeBy(14_999)
        runCurrent()
        assertTrue(runCatching { repository.submit("durable-a", "second") }.exceptionOrNull() is GatewayRpcException)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(runCatching { repository.submit("durable-a", "second") }.exceptionOrNull() is GatewayRpcException)

        rpc.emit("session.info", "runtime-a", """{"stored_session_id":"durable-a","running":false}""")
        runCurrent()
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        repository.submit("durable-a", "second")
        assertEquals(2, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `disconnect clears runtime mapping and reconnect resumes durable identity`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, state, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        clients.value = null
        state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        assertEquals(GatewayConnectionStatus.Disconnected, repository.connectionState.value.status)

        val second = FakeRpc()
        clients.value = second
        state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()
        repository.openSession("durable-a")

        assertEquals(1, second.calls.count { it.method == "session.resume" })
        assertEquals(0, second.calls.count { it.method == "session.activate" })
    }

    @Test
    fun `disconnect settles a local stream then reconnect resume restores sending without interrupting`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "keep going")
        first.emit("message.start", "runtime-a")
        first.emit("message.delta", "runtime-a", """{"text":"useful partial"}""")
        first.emit("tool.start", "runtime-a", """{"tool_id":"live-tool","name":"read_file","context":"README.md"}""")
        runCurrent()

        clients.value = null
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()

        val uncertain = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().single()
        assertEquals("useful partial", uncertain.markdown)
        assertFalse(uncertain.streaming)
        assertEquals(ToolState.Stopped, cache.transcript("durable-a").filterIsInstance<ToolActivity>().single().state)
        assertEquals(SessionStatus.Stalled, cache.session("durable-a")?.status)
        assertEquals(0, first.calls.count { it.method == "session.interrupt" })
        assertTrue(runCatching { repository.interrupt("durable-a") }.exceptionOrNull() is GatewayRpcException)
        assertEquals(0, first.calls.count { it.method == "session.interrupt" })

        val second = FakeRpc()
        clients.value = second
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        assertEquals(1, second.calls.count { it.method == "session.resume" })
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        assertTrue(cache.transcript("durable-a").isEmpty())
        repository.submit("durable-a", "send after reconcile")
        assertEquals("send after reconcile", second.call("prompt.submit").params.string("text"))
        assertEquals(0, second.calls.count { it.method == "session.interrupt" })
    }

    @Test
    fun `failed reconnect resume releases global guard and preserves sealed partial evidence`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "keep going")
        first.emit("message.start", "runtime-a")
        first.emit("message.delta", "runtime-a", """{"text":"useful partial"}""")
        first.emit("tool.start", "runtime-a", """{"tool_id":"live-tool","name":"read_file","context":"README.md"}""")
        runCurrent()

        clients.value = null
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        val second = FakeRpc().apply { resumeFailures = 1 }
        clients.value = second
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        val retained = cache.transcript("durable-a")
        assertEquals("useful partial", retained.filterIsInstance<AssistantTurn>().single().markdown)
        assertFalse(retained.filterIsInstance<AssistantTurn>().single().streaming)
        assertEquals(ToolState.Stopped, retained.filterIsInstance<ToolActivity>().single().state)
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        assertEquals(
            "This turn could not be checked. Reconnect to the Gateway, then reopen the session.",
            cache.session("durable-a")?.progress?.text,
        )
        assertTrue(cache.state.value.sessions.values.none { it.status in setOf(SessionStatus.Working, SessionStatus.Stalled) })

        repository.openSession("durable-b")
        repository.submit("durable-b", "other session remains usable")
        assertEquals("other session remains usable", second.call("prompt.submit").params.string("text"))
    }

    @Test
    fun `remote running session info is settled and reconciled across disconnect`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        first.emit("session.info", "runtime-a", """{"running":true}""")
        runCurrent()
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)

        clients.value = null
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        assertEquals(SessionStatus.Stalled, cache.session("durable-a")?.status)

        val second = FakeRpc()
        clients.value = second
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        assertEquals(1, second.calls.count { it.method == "session.resume" })
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        repository.openSession("durable-b")
        repository.submit("durable-b", "send after remote reconcile")
        assertEquals("send after remote reconcile", second.call("prompt.submit").params.string("text"))
    }

    @Test
    fun `multiple reported live runtimes keep one turn policy without guessing unscoped ownership`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "local turn")
        repository.openSession("durable-b")
        rpc.emit("session.info", "runtime-b", """{"running":true}""")
        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()

        assertEquals(SessionStatus.Working, cache.session("durable-b")?.status)
        assertTrue(runCatching { repository.submit("durable-a", "must remain blocked") }.isFailure)
        assertEquals(1, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `create binds returned durable and runtime ids`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        val id = repository.createSession("/work/hermes-mobile")
        repository.submit(id, "first prompt")

        assertEquals("durable-created", id)
        assertEquals("runtime-created", rpc.call("prompt.submit").params.string("session_id"))
        assertEquals("/work/hermes-mobile", rpc.call("session.create").params.string("cwd"))
        assertEquals("New session", cache.session(id)?.title)
    }

    @Test
    fun `stored session id remains authoritative over conflicting create info`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { createResult = CREATE_WITH_CONFLICTING_INFO_ID }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }

        val id = repository.createSession()

        assertEquals("durable-created", id)
        assertEquals("durable-created", cache.session("durable-created")?.id)
        assertTrue(cache.session("conflicting-info-id") == null)
    }

    @Test
    fun `resume seeds a minimal summary only when the list cache is empty`() = runTest {
        val cache = SessionCache()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(FakeRpc()),
            backgroundScope,
        ) { CLOCK }

        repository.openSession("durable-a")

        assertEquals("durable-a", cache.session("durable-a")?.id)
        assertEquals("New session", cache.session("durable-a")?.title)
        assertEquals(7, cache.session("durable-a")?.messageCount)
        assertEquals(1_700_001_000_125, cache.session("durable-a")?.lastActiveAtMillis)
    }

    @Test
    fun `resume replays a running prompt and partial assistant and initializes the global send guard`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            resumeA = RESUME_RUNNING
            historyResult = """{"messages":[{"row_id":"stored-user","role":"user","text":"current prompt"}],"count":1}"""
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")

        val transcript = cache.transcript("durable-a")
        assertEquals(1, transcript.filterIsInstance<UserTurn>().size)
        assertEquals("current prompt", transcript.filterIsInstance<UserTurn>().single().text)
        val partial = transcript.filterIsInstance<AssistantTurn>().single()
        assertEquals("partial answer", partial.markdown)
        assertTrue(partial.streaming)
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertTrue(runCatching { repository.submit("durable-a", "must wait") }.exceptionOrNull() is GatewayRpcException)
        assertEquals(0, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `resume preserves the upstream waiting status while keeping its send guard`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { resumeA = RESUME_WAITING }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")

        assertEquals(SessionStatus.NeedsInput, cache.session("durable-a")?.status)
        assertTrue(runCatching { repository.submit("durable-a", "must wait") }.exceptionOrNull() is GatewayRpcException)
    }

    @Test
    fun `resume replays retained failure with partial text but releases sending`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            resumeA = RESUME_RETAINED_FAILURE
            historyResult = """{"messages":[{"row_id":"stored-user","role":"user","text":"long job"}],"count":1}"""
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")

        assertEquals(1, cache.transcript("durable-a").filterIsInstance<UserTurn>().size)
        val failed = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().single()
        assertEquals("half an answer", failed.markdown)
        assertFalse(failed.streaming)
        assertEquals("Hermes ended this turn unexpectedly. Check the Gateway, then try again.", failed.error)
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        repository.submit("durable-a", "retry safely")
        assertEquals(1, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `resume null and empty inflight shapes add no synthetic transcript rows`() = runTest {
        listOf(RESUME_A, RESUME_EMPTY_INFLIGHT).forEach { resumePayload ->
            val cache = SessionCache()
            val rpc = FakeRpc().apply { resumeA = resumePayload }
            val repository = LiveGatewaySessionRepository(
                cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc),
                backgroundScope,
            ) { CLOCK }
            runCurrent()

            repository.openSession("durable-a")

            assertTrue(cache.transcript("durable-a").isEmpty())
            assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        }
    }

    @Test
    fun `authoritative reopen replaces optimistic and identifier-less live user assistant and tool rows`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "ship it")
        rpc.emit("message.start", "runtime-a")
        rpc.emit("message.delta", "runtime-a", """{"text":"done"}""")
        rpc.emit("tool.start", "runtime-a", """{"tool_id":"live-tool","name":"read_file","context":"README.md"}""")
        rpc.emit("tool.complete", "runtime-a", """{"tool_id":"live-tool","name":"read_file","summary":"Read README.md"}""")
        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()
        rpc.activateResult = ACTIVATE_IDLE
        rpc.historyResult = AUTHORITATIVE_COMPLETED_HISTORY

        repository.openSession("durable-a")

        val transcript = cache.transcript("durable-a")
        assertEquals(3, transcript.size)
        assertEquals(1, transcript.filterIsInstance<UserTurn>().size)
        assertEquals(1, transcript.filterIsInstance<AssistantTurn>().size)
        assertEquals(1, transcript.filterIsInstance<ToolActivity>().size)
        assertEquals("stored-user", transcript.filterIsInstance<UserTurn>().single().id)
        assertEquals("stored-assistant", transcript.filterIsInstance<AssistantTurn>().single().id)
        assertEquals("runtime-a-history-2", transcript.filterIsInstance<ToolActivity>().single().id)
        assertTrue(transcript.none { it.id.startsWith("local-") || it.id.startsWith("gateway-") })
    }

    @Test
    fun `authoritative same connection terminal snapshot replaces stale local submit guard`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "stale local prompt")
        rpc.activateResult = ACTIVATE_IDLE
        rpc.historyResult = """{"messages":[{"row_id":"stored-user","role":"user","text":"server truth"}],"count":1}"""

        repository.openSession("durable-a")

        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        assertEquals(listOf("server truth"), cache.transcript("durable-a").filterIsInstance<UserTurn>().map(UserTurn::text))
        repository.submit("durable-a", "send after terminal snapshot")
        assertEquals(2, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `compression resume rehomes navigation and replaces stale transcript before routing later events`() = runTest {
        val cache = SessionCache().apply {
            upsertSession(SessionSummary("parent-root", "Long chat", "before", CLOCK - 1, messageCount = 4))
            appendEntry("parent-root", UserTurn("old-user", "before compression", CLOCK - 1))
        }
        val rpc = FakeRpc().apply {
            resumeA = RESUME_COMPRESSION_TIP
            historyResult = """{"messages":[{"row_id":"tip-reply","role":"assistant","text":"after compression"}],"count":1}"""
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        val rehome = async { repository.sessionRehomes.first() }
        runCurrent()

        val canonical = repository.openSession("parent-root")
        val moved = rehome.await()

        assertEquals("continuation-tip", canonical)
        assertEquals(SessionRehome("parent-root", "continuation-tip"), moved)
        assertEquals(null, cache.session("parent-root"))
        assertEquals("Long chat", cache.session("continuation-tip")?.title)
        // Completed rows come from authoritative history; this resume reports no inflight row to preserve.
        assertEquals(listOf("tip-reply"), cache.transcript("continuation-tip").map { it.id })

        rpc.emit("message.start", "runtime-a", """{"id":"live-tip","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"routed to tip"}""")
        runCurrent()
        assertEquals(
            "routed to tip",
            cache.transcript("continuation-tip").filterIsInstance<AssistantTurn>().last().markdown,
        )
        assertTrue(cache.transcript("parent-root").isEmpty())
    }

    @Test
    fun `session reclaimed drops only the dead runtime mapping and resumes the durable row`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        rpc.emit(
            "session.reclaimed",
            null,
            """{"session_id":"runtime-a","stored_session_id":"durable-a","reason":"idle_timeout"}""",
        )
        runCurrent()
        repository.openSession("durable-a")

        assertEquals(2, rpc.calls.count { it.method == "session.resume" })
        assertEquals(0, rpc.calls.count { it.method == "session.activate" })
        assertEquals("Remote work", cache.session("durable-a")?.title)
    }

    @Test
    fun `unsent created session stays useful offline then disappears before reconnect authority merges`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        val created = repository.createSession()
        clients.value = null
        runCurrent()
        assertEquals("New session", cache.session(created)?.title)

        clients.value = FakeRpc()
        runCurrent()
        assertEquals(null, cache.session(created))
        assertTrue(cache.transcript(created).isEmpty())
    }

    @Test
    fun `message completion maps interrupted and error metadata and seals every unfinished tool`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "stop this")
        rpc.emit("message.start", "runtime-a", """{"id":"stopped-reply","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"partial"}""")
        rpc.emit("tool.start", "runtime-a", """{"tool_id":"tool-a","name":"terminal","context":"work a"}""")
        rpc.emit("tool.start", "runtime-a", """{"tool_id":"tool-b","name":"read_file","context":"work b"}""")
        rpc.emit("message.complete", "runtime-a", """{"text":"partial","status":"interrupted"}""")
        runCurrent()

        val stopped = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last()
        assertTrue(stopped.stopped)
        assertEquals("partial", stopped.markdown)
        assertEquals(
            setOf(ToolState.Stopped),
            cache.transcript("durable-a").filterIsInstance<ToolActivity>()
                .filter { it.id in setOf("tool-a", "tool-b") }.map { it.state }.toSet(),
        )

        repository.submit("durable-a", "fail next")
        rpc.emit("message.start", "runtime-a", """{"id":"failed-reply","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"kept partial"}""")
        rpc.emit("tool.start", "runtime-a", """{"tool_id":"tool-c","name":"terminal","context":"work c"}""")
        rpc.emit(
            "message.complete",
            "runtime-a",
            """{"text":"kept partial","status":"error","error":"connection reset","partial":true}""",
        )
        runCurrent()

        val failed = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last()
        assertEquals("Hermes ended this turn unexpectedly. Check the Gateway, then try again.", failed.error)
        assertEquals("kept partial", failed.markdown)
        assertEquals(ToolState.Failed, cache.transcript("durable-a").filterIsInstance<ToolActivity>().last().state)
    }

    @Test
    fun `terminal error mapper removes raw sensitive and exception payloads and keeps an action`() {
        val cases = listOf(
            "Authorization: Bearer sentinel-bearer-value" to "sentinel-bearer-value",
            "https://example.invalid/api/ws?token=sentinel-query-value" to "sentinel-query-value",
            "password=sentinel-password-value" to "sentinel-password-value",
            "-----BEGIN PRIVATE KEY-----\nsentinel-private-value\n-----END PRIVATE KEY-----" to "sentinel-private-value",
            "dial tcp private-endpoint.example.invalid:9443: connection refused" to "private-endpoint.example.invalid",
            "java.lang.IllegalStateException: sentinel-exception-payload" to "sentinel-exception-payload",
        )

        cases.forEach { (raw, sensitive) ->
            val safe = safeGatewayTerminalError(raw)
            assertFalse("raw payload must not render: $sensitive", safe.contains(sensitive))
            assertTrue("safe copy must provide a next action", safe.contains("try again", ignoreCase = true))
        }
        assertEquals(
            "The remote host is out of storage. Free space there, then try again.",
            safeGatewayTerminalError("OSError: [Errno 28] No space left on device"),
        )
    }

    @Test
    fun `error event and error completion cache only mapped safe copy`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        rpc.emit(
            "error",
            "runtime-a",
            """{"error":"Authorization: Bearer sentinel-event-value"}""",
        )
        runCurrent()
        val eventFailure = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last()
        assertEquals("Hermes ended this turn unexpectedly. Check the Gateway, then try again.", eventFailure.error)
        assertFalse(eventFailure.error.orEmpty().contains("sentinel-event-value"))

        repository.submit("durable-a", "continue")
        rpc.emit("message.start", "runtime-a", """{"id":"safe-partial","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"Useful partial answer"}""")
        rpc.emit(
            "message.complete",
            "runtime-a",
            """{"status":"error","error":"https://example.invalid/api?token=sentinel-complete-value","text":"sentinel-complete-value","partial":true}""",
        )
        runCurrent()

        val completionFailure = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last()
        assertEquals("Hermes ended this turn unexpectedly. Check the Gateway, then try again.", completionFailure.error)
        assertEquals("Useful partial answer", completionFailure.markdown)
        assertFalse(cache.transcript("durable-a").joinToString().contains("sentinel-complete-value"))
    }

    @Test
    fun `live tool fixtures use context summary result duration and structured outcome signals`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        rpc.emit("tool.start", "runtime-a", """{"tool_id":"ok","name":"read_file","context":"README.md"}""")
        runCurrent()
        assertEquals("README.md", cache.transcript("durable-a").filterIsInstance<ToolActivity>().single().detail)
        rpc.emit(
            "tool.complete",
            "runtime-a",
            """{"tool_id":"ok","name":"read_file","result":{"content":"ok"},"summary":"Read 1 file","duration_s":1.25}""",
        )
        rpc.emit(
            "tool.complete",
            "runtime-a",
            """{"tool_id":"bad","name":"delegate_task","result":{"status":"failed","summary":"worker failed"},"duration_s":2.5}""",
        )
        runCurrent()

        val tools = cache.transcript("durable-a").filterIsInstance<ToolActivity>().associateBy { it.id }
        assertEquals("Read 1 file", tools.getValue("ok").detail)
        assertEquals(1.25, tools.getValue("ok").elapsedSeconds, 0.0)
        assertEquals(ToolState.Done, tools.getValue("ok").state)
        assertEquals(ToolState.Failed, tools.getValue("bad").state)
        assertTrue(tools.getValue("bad").detail.contains("failed"))
        assertEquals(2.5, tools.getValue("bad").elapsedSeconds, 0.0)
    }

    @Test
    fun `status updates coalesce exact kind text progress and ignore malformed shapes`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        rpc.emit("status.update", "runtime-a", """{"kind":"compacting","text":"Summarizing context…"}""")
        runCurrent()
        assertEquals("compacting", cache.session("durable-a")?.progress?.kind)
        assertEquals("Summarizing context…", cache.session("durable-a")?.progress?.text)

        val afterFirst = cache.state.value
        rpc.emit("status.update", "runtime-a", """{"kind":"compacting","text":"Summarizing context…"}""")
        runCurrent()
        assertSame("an identical update must be a cache no-op", afterFirst, cache.state.value)

        rpc.emit("status.update", "runtime-a", """{"kind":"process","text":"Resuming interrupted turn…"}""")
        rpc.emit("status.update", "runtime-a", """{"kind":"progress","text":"Checking the result"}""")
        runCurrent()
        assertEquals("progress", cache.session("durable-a")?.progress?.kind)
        assertEquals("Checking the result", cache.session("durable-a")?.progress?.text)

        val beforeMalformed = cache.state.value
        listOf(
            "{}",
            """{"kind":"progress"}""",
            """{"text":"missing kind"}""",
            """{"kind":"","text":"blank kind"}""",
            """{"kind":7,"text":"wrong kind type"}""",
            """{"kind":"progress","text":{"nested":true}}""",
        ).forEach { malformed -> rpc.emit("status.update", "runtime-a", malformed) }
        runCurrent()
        assertSame("malformed status payloads must not erase useful progress", beforeMalformed, cache.state.value)

        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()
        assertEquals(null, cache.session("durable-a")?.progress)
    }

    @Test
    fun `a suspended prompt request cannot starve more than one event buffer of relevant deltas`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { promptResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        val submit = async { repository.submit("durable-a", "long request") }
        runCurrent()
        repeat(1_050) {
            rpc.emit("message.delta", "runtime-a", """{"id":"long-reply","delta":"x"}""")
            runCurrent()
        }

        assertFalse("the event collector must keep draining while request awaits", rpc.eventOverflowed)
        assertFalse(submit.isCompleted)
        assertEquals(
            1_050,
            cache.transcript("durable-a").filterIsInstance<AssistantTurn>().single().markdown.length,
        )

        rpc.promptResponse?.complete(json("""{"status":"streaming"}"""))
        submit.await()
    }

    @Test
    fun `an ambiguous delayed prompt acknowledgement keeps the optimistic turn and send guard`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { promptResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        val submit = async { repository.submit("durable-a", "accepted") }
        runCurrent()
        rpc.emit("session.info", "runtime-a", """{"stored_session_id":"durable-a","running":true}""")
        runCurrent()
        rpc.promptResponse?.completeExceptionally(
            GatewayRpcException("acknowledgement lost", requestMayHaveBeenAccepted = true),
        )

        assertEquals(GatewaySubmitOutcome.Ambiguous, submit.await())
        assertEquals("accepted", (cache.transcript("durable-a").last() as UserTurn).text)
        assertTrue(runCatching { repository.submit("durable-a", "duplicate") }.exceptionOrNull() is GatewayRpcException)
        assertEquals(1, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `request local cancellation is ambiguous but parent cancellation still propagates`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { promptResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        val requestCancelled = async { repository.submit("durable-a", "possibly accepted") }
        runCurrent()
        rpc.promptResponse?.cancel(CancellationException("request deadline"))

        assertEquals(GatewaySubmitOutcome.Ambiguous, requestCancelled.await())
        assertEquals("possibly accepted", (cache.transcript("durable-a").last() as UserTurn).text)

        rpc.emit("message.complete", "runtime-a", "{}")
        runCurrent()
        rpc.promptResponse = CompletableDeferred()
        val parentCancelled = async { repository.submit("durable-a", "cancel parent") }
        runCurrent()
        parentCancelled.cancelAndJoin()

        assertTrue(parentCancelled.isCancelled)
        assertEquals("cancel parent", (cache.transcript("durable-a").last() as UserTurn).text)
    }

    @Test
    fun `disconnect clears progress even before a status update starts a turn`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        first.emit("status.update", "runtime-a", """{"kind":"process","text":"Starting queued work"}""")
        runCurrent()
        assertEquals("Starting queued work", cache.session("durable-a")?.progress?.text)

        clients.value = null
        runCurrent()

        assertEquals(null, cache.session("durable-a")?.progress)

        val second = FakeRpc().apply { historyResponse = CompletableDeferred() }
        clients.value = second
        runCurrent()
        second.emit("status.update", "runtime-a", """{"kind":"process","text":"New connection work"}""")
        runCurrent()
        second.historyResponse?.complete(json("""{"messages":[],"count":0}"""))
        runCurrent()

        assertEquals("New connection work", cache.session("durable-a")?.progress?.text)
    }

    @Test
    fun `reopening a running turn preserves completed live tool cards`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "ship it")
        rpc.emit("message.start", "runtime-a")
        rpc.emit("message.delta", "runtime-a", """{"text":"partial"}""")
        rpc.emit("tool.start", "runtime-a", """{"tool_id":"live-tool","name":"read_file","context":"README.md"}""")
        rpc.emit("tool.complete", "runtime-a", """{"tool_id":"live-tool","name":"read_file","summary":"Read README.md"}""")
        runCurrent()
        assertEquals(1, cache.transcript("durable-a").filterIsInstance<ToolActivity>().size)

        rpc.activateResult = """{"session_id":"runtime-a","session_key":"durable-a","inflight":{"user":"ship it","assistant":"partial","streaming":true},"running":true,"status":"working"}"""
        rpc.historyResult = """{"messages":[],"count":0}"""
        repository.openSession("durable-a")

        assertEquals(1, cache.transcript("durable-a").filterIsInstance<ToolActivity>().size)

        rpc.activateResult = ACTIVATE_IDLE
        rpc.historyResult = AUTHORITATIVE_COMPLETED_HISTORY
        repository.openSession("durable-a")

        val durableTools = cache.transcript("durable-a").filterIsInstance<ToolActivity>()
        assertEquals(1, durableTools.size)
        assertEquals("runtime-a-history-2", durableTools.single().id)
    }

    @Test
    fun `terminal events that overtake history cannot be overwritten by an older live snapshot`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "ship it")
        rpc.emit("message.start", "runtime-a")
        rpc.emit("message.delta", "runtime-a", """{"text":"done"}""")
        runCurrent()

        rpc.activateResult = RESUME_RUNNING
        rpc.historyResponse = CompletableDeferred()
        val reopen = async { repository.openSession("durable-a") }
        runCurrent()

        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        rpc.emit("session.info", "runtime-a", """{"running":false}""")
        runCurrent()
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)

        rpc.historyResponse?.complete(json(AUTHORITATIVE_COMPLETED_HISTORY))
        reopen.await()

        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
        assertEquals(1, cache.transcript("durable-a").filterIsInstance<UserTurn>().size)
        assertEquals(1, cache.transcript("durable-a").filterIsInstance<AssistantTurn>().size)
        repository.submit("durable-a", "send after completion")
    }

    @Test
    fun `terminal events coalesce one authoritative metadata refresh`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.openSession("durable-b")
        val before = rpc.calls.count { it.method == "session.list" }
        rpc.sessionListResult = SESSION_LIST_RENAMED

        rpc.emit("message.complete", "runtime-a", """{"text":"done a"}""")
        rpc.emit("message.complete", "runtime-b", """{"text":"done b"}""")
        runCurrent()

        assertEquals(before + 1, rpc.calls.count { it.method == "session.list" })
        assertEquals("Renamed A", cache.session("durable-a")?.title)
        assertEquals("Renamed B", cache.session("durable-b")?.title)
    }

    private data class RpcCall(val method: String, val params: JsonObject)

    private class FakeRpc : GatewayRpcClient {
        private val eventChannel = Channel<GatewayEvent>(capacity = 1_024)
        override val events = eventChannel.receiveAsFlow()
        val calls = mutableListOf<RpcCall>()
        var promptFailures = 0
        var resumeFailures = 0
        var completeDuringSubmit = false
        var createResult = CREATE
        var resumeA = RESUME_A
        var activateResult = "{}"
        var historyResult = """{"messages":[],"count":0}"""
        var historyResponse: CompletableDeferred<JsonElement>? = null
        var sessionListResult = SESSION_LIST
        var projectTreeResult = PROJECT_TREE
        var projectDetailsResult = PROJECT_DETAILS
        var projectCreateResult = PROJECT_CREATE
        var projectTreeFailure: Throwable? = null
        var projectTreeResponse: CompletableDeferred<JsonElement>? = null
        var projectDetailsResponse: CompletableDeferred<JsonElement>? = null
        var promptResponse: CompletableDeferred<JsonElement>? = null
        var eventOverflowed = false

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += RpcCall(method, params)
            return when (method) {
                "session.list" -> json(sessionListResult)
                "projects.tree" -> {
                    projectTreeFailure?.let { throw it }
                    projectTreeResponse?.await() ?: json(projectTreeResult)
                }
                "projects.project_sessions" -> projectDetailsResponse?.await() ?: json(projectDetailsResult)
                "projects.create" -> json(projectCreateResult)
                "session.resume" -> {
                    if (resumeFailures > 0) {
                        resumeFailures--
                        throw GatewayRpcException("resume unavailable")
                    }
                    when (params.string("session_id")) {
                        "durable-b" -> json(RESUME_B)
                        else -> json(resumeA)
                    }
                }
                "session.history" -> historyResponse?.await() ?: json(historyResult)
                "session.create" -> json(createResult)
                "prompt.submit" -> {
                    if (promptFailures > 0) {
                        promptFailures--
                        throw GatewayRpcException("rejected")
                    }
                    if (completeDuringSubmit) {
                        emit("message.start", null, """{"id":"early","role":"assistant"}""")
                        emit("message.delta", null, """{"delta":"reply"}""")
                        emit("message.complete", null, "{}")
                    }
                    promptResponse?.await() ?: json("{}")
                }
                "session.activate" -> json(activateResult)
                "session.interrupt" -> json("{}")
                else -> error("unexpected method $method")
            }
        }

        fun call(method: String): RpcCall = calls.last { it.method == method }

        fun emit(type: String, runtimeId: String?, payload: JsonElement = JsonNull) {
            if (eventChannel.trySend(GatewayEvent(type, runtimeId, payload)).isFailure) {
                eventOverflowed = true
            }
        }

        fun emit(type: String, runtimeId: String?, payload: String) = emit(type, runtimeId, json(payload))

        override fun close() = Unit
    }

    private companion object {
        const val CLOCK = 1_800_000_000_000L
        const val SESSION_LIST = """{"sessions":[
            {"id":"durable-a","title":"Remote work","preview":"latest","started_at":1700000123.456,"message_count":7,"source":"desktop"},
            {"id":"durable-b","title":"Other","preview":"","started_at":1700000456.789,"message_count":0,"source":""}
        ]}"""
        const val PROJECT_TREE = """{"projects":[
            {"id":"__no_project__","label":"Home","path":null,"isAuto":false,"isNoProject":true,"sessionCount":1,"lastActive":1700000200,"repos":[],"previewSessions":[{"id":"home-a","title":"Home preview","preview":"","last_active":1700000200}]},
            {"id":"project-mobile","label":"Hermes mobile","path":"/work/hermes-mobile","isAuto":false,"isNoProject":false,"sessionCount":2,"lastActive":1700000300,"repos":[],"previewSessions":[{"id":"durable-a","title":"Project preview","preview":"latest","last_active":1700000300}]}
        ],"active_id":"project-mobile","scoped_session_ids":["home-a","durable-a","durable-b"]}"""
        const val PROJECT_DETAILS = """{"project":{"id":"project-mobile","label":"Hermes mobile","path":"/work/hermes-mobile","isAuto":false,"isNoProject":false,"sessionCount":2,"lastActive":1700000400,"previewSessions":[],"repos":[{"id":"/work/hermes-mobile","label":"hermes-mobile","path":"/work/hermes-mobile","sessionCount":2,"groups":[{"id":"main","label":"main","path":"/work/hermes-mobile","sessions":[
            {"id":"durable-a","title":"Project detail A","preview":"a","last_active":1700000400,"message_count":4},
            {"id":"durable-b","title":"Project detail B","preview":"b","last_active":1700000300,"message_count":3}
        ]}]}]}}"""
        const val PROJECT_CREATE = """{"project":{"id":"project-created","name":"Demo","primary_path":"/srv/demo"}}"""
        const val PROJECT_TREE_RECONNECTED = """{"projects":[
            {"id":"project-reconnected","label":"Reconnected","path":"/work/current","isAuto":false,"isNoProject":false,"sessionCount":0,"lastActive":1700000500,"repos":[],"previewSessions":[]}
        ],"active_id":"project-reconnected","scoped_session_ids":[]}"""
        const val RESUME_A = """{"session_id":"runtime-a","resumed":"durable-a","message_count":7,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":null,"running":false,"session_key":"durable-a","started_at":1700001000.125,"status":"idle"}"""
        const val RESUME_EMPTY_INFLIGHT = """{"session_id":"runtime-a","resumed":"durable-a","message_count":7,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":{},"running":false,"session_key":"durable-a","started_at":1700001000.125,"status":"idle"}"""
        const val RESUME_RUNNING = """{"session_id":"runtime-a","resumed":"durable-a","message_count":8,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":{"user":"current prompt","assistant":"partial answer","streaming":true},"running":true,"turn_started_at":1700001001.5,"session_key":"durable-a","started_at":1700001000.125,"status":"streaming"}"""
        const val RESUME_WAITING = """{"session_id":"runtime-a","resumed":"durable-a","message_count":8,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":{"user":"waiting prompt","assistant":"","streaming":false},"running":true,"turn_started_at":1700001001.5,"session_key":"durable-a","started_at":1700001000.125,"status":"waiting"}"""
        const val RESUME_RETAINED_FAILURE = """{"session_id":"runtime-a","resumed":"durable-a","message_count":8,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":{"user":"long job","assistant":"half an answer","streaming":false,"status":"error","error":"budget exhausted","recoverable":true},"running":false,"session_key":"durable-a","started_at":1700001000.125,"status":"idle"}"""
        const val RESUME_B = """{"session_id":"runtime-b","resumed":"durable-b","message_count":0,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":null,"running":false,"session_key":"durable-b","started_at":1700001000.125,"status":"idle"}"""
        const val RESUME_COMPRESSION_TIP = """{"session_id":"runtime-a","resumed":"continuation-tip","message_count":5,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":null,"running":false,"session_key":"continuation-tip","started_at":1700001000.125,"status":"idle"}"""
        const val CREATE = """{"session_id":"runtime-created","stored_session_id":"durable-created","message_count":0,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true}}"""
        const val CREATE_WITH_CONFLICTING_INFO_ID = """{"session_id":"runtime-created","stored_session_id":"durable-created","message_count":0,"messages":[],"info":{"id":"conflicting-info-id","model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true}}"""
        const val SESSION_LIST_RENAMED = """{"sessions":[
            {"id":"durable-a","title":"Renamed A","preview":"new a","started_at":1700000124,"message_count":8,"source":"desktop"},
            {"id":"durable-b","title":"Renamed B","preview":"new b","started_at":1700000457,"message_count":1,"source":"desktop"}
        ]}"""
        const val HISTORY = """{"messages":[
            {"row_id":"row-u","role":"user","text":"hello","timestamp":1700000000},
            {"row_id":"row-a","role":"assistant","text":"hi"},
            {"role":"tool","name":"Read","context":"Read file.txt","args":{"path":"file.txt"}}
        ],"count":3}"""
        const val ACTIVATE_IDLE = """{"session_id":"runtime-a","session_key":"durable-a","message_count":3,"messages":[],"inflight":null,"running":false,"started_at":1700001000.125,"status":"idle"}"""
        const val AUTHORITATIVE_COMPLETED_HISTORY = """{"messages":[
            {"row_id":"stored-user","role":"user","text":"ship it","timestamp":1700001001},
            {"row_id":"stored-assistant","role":"assistant","text":"done","timestamp":1700001002},
            {"role":"tool","name":"read_file","context":"README.md"}
        ],"count":3}"""
    }
}

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
