package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.attachments.OutgoingAttachment
import com.hermesagent.mobile.data.composer.ComposerModelSelection
import com.hermesagent.mobile.data.composer.ControlMutationResult
import com.hermesagent.mobile.data.composer.FastMode
import com.hermesagent.mobile.data.composer.NewSessionComposerOverrides
import com.hermesagent.mobile.data.composer.ReasoningEffort
import com.hermesagent.mobile.data.profiles.DEFAULT_PROFILE
import com.hermesagent.mobile.data.profiles.filterSessionsByProfileScope
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionProgress
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.TranscriptRowId
import com.hermesagent.mobile.data.session.TurnTermination
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.data.session.buildSessionRows
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySessionRepositoryTest {

    @Test
    fun `model catalog keeps Gateway provider capabilities and effective selection`() {
        val catalog = parseModelCatalog(
            json(
                """{"model":"reasoner-v3","provider":"acme","providers":[
                  {"slug":"acme","name":"Acme","models":["reasoner-v3"],
                   "capabilities":{"reasoner-v3":{"reasoning":true,"fast":true}}},
                  {"slug":"empty","models":[]}
                ]}""",
            ),
        )

        assertEquals(2, catalog.providers.size)
        assertEquals("reasoner-v3", catalog.effectiveSelection?.model)
        assertEquals("acme", catalog.effectiveSelection?.provider)
        assertTrue(catalog.providers.first().models.single().supportsFast)
    }

    @Test
    fun `live controls resolve durable identity and remain session scoped`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        assertEquals(
            ControlMutationResult.Applied,
            repository.setLiveModel("durable-a", ComposerModelSelection("reasoner-v3", "acme")),
        )
        assertEquals("runtime-a", rpc.call("config.set").params.string("session_id"))
        assertEquals("model", rpc.call("config.set").params.string("key"))
        assertEquals("reasoner-v3 --provider acme --session", rpc.call("config.set").params.string("value"))

        rpc.modelOptionsResult = """{"model":"session-only","provider":"session-provider","providers":[]}"""
        rpc.providerResult = """{"model":"global-default","provider":"global-provider"}"""
        val snapshot = repository.loadComposerControls("durable-a")
        assertEquals("session-only", snapshot.selection?.model)
        assertEquals("session-provider", snapshot.selection?.provider)
        assertEquals(ReasoningEffort.High, snapshot.reasoning)
        assertEquals(FastMode.Fast, snapshot.fast)
        assertEquals(
            listOf("reasoning", "fast"),
            rpc.calls.filter { it.method == "config.get" }.takeLast(2).map { it.params.string("key") },
        )
        assertTrue(rpc.calls.filter { it.method == "config.get" }.takeLast(2).all {
            it.params.string("session_id") == "runtime-a"
        })
        assertEquals("runtime-a", rpc.calls.last { it.method == "model.options" }.params.string("session_id"))
    }

    @Test
    fun `deferred and legacy busy model responses keep requested next turn selection`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { configSetResult = """{"deferred":true}""" }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        assertEquals(
            ControlMutationResult.Deferred,
            repository.setLiveModel("durable-a", ComposerModelSelection("next", "acme")),
        )

        rpc.configSetResult = "{}"
        rpc.configSetFailure = GatewayRpcError(4009, "session busy")
        assertEquals(
            ControlMutationResult.Deferred,
            repository.setLiveModel("durable-a", ComposerModelSelection("later", "acme")),
        )
    }

    @Test
    fun `control rejection is concise and never raw Gateway payload`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { configSetFailure = GatewayRpcError(4002, "token secret-value leaked") }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        val rejected = repository.setLiveFast("durable-a", FastMode.Fast) as ControlMutationResult.Rejected
        assertTrue(rejected.safeMessage.contains("Fast mode"))
        assertFalse(rejected.safeMessage.contains("secret-value"))
    }

    @Test
    fun `new session composer overrides are snapshotted into create payload`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.createSession(
            "/work/hermes-mobile",
            NewSessionComposerOverrides(
                selection = ComposerModelSelection("reasoner-v3", "acme"),
                reasoning = ReasoningEffort.High,
                fast = FastMode.Fast,
            ),
        )

        val params = rpc.call("session.create").params
        assertEquals("reasoner-v3", params.string("model"))
        assertEquals("acme", params.string("provider"))
        assertEquals("high", params.string("reasoning_effort"))
        assertTrue(requireNotNull(params["fast"]).jsonPrimitive.boolean)

        repository.createSession(
            null,
            NewSessionComposerOverrides(fast = FastMode.Normal),
        )
        assertFalse(requireNotNull(rpc.call("session.create").params["fast"]).jsonPrimitive.boolean)

        repository.createSession(
            null,
            NewSessionComposerOverrides(
                reasoning = ReasoningEffort.Unknown("future-effort"),
                fast = FastMode.Unknown("future-tier"),
            ),
        )
        val unknownParams = rpc.call("session.create").params
        assertFalse("reasoning_effort" in unknownParams)
        assertFalse("fast" in unknownParams)
    }

    @Test
    fun `completion methods use documented payloads and mapped items`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            slashResult = """{"items":[{"text":"/help","display":"/help","meta":"help","kind":"command"}],"replace_from":1}"""
            pathResult = """{"items":[{"text":"@file:README.md","display":"README.md","meta":"file"}]}"""
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        assertEquals("/help", repository.completeSlash("/h").items.single().text)
        assertEquals(1, repository.completeSlash("/h").replaceFrom)
        assertEquals("@file:README.md", repository.completePath("durable-a", "@REA", "/workspace").items.single().text)
        assertEquals("@REA", rpc.call("complete.path").params.string("word"))
        assertEquals("/workspace", rpc.call("complete.path").params.string("cwd"))
        assertEquals("runtime-a", rpc.call("complete.path").params.string("session_id"))

        repository.completePath(null, "@REA", "/workspace")
        assertFalse("session_id" in rpc.call("complete.path").params)
    }

    @Test
    fun `session rehome during a catalog query preserves the canonical runtime binding`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { modelOptionsResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        val catalog = async { repository.loadModelOptions("durable-a") }
        runCurrent()
        rpc.emit(
            "session.info",
            "runtime-a",
            """{"stored_session_id":"durable-tip","running":false}""",
        )
        runCurrent()
        rpc.modelOptionsResponse?.complete(json(MODEL_OPTIONS))
        assertEquals("reasoner-v3", catalog.await().effectiveSelection?.model)

        val resumeCount = rpc.calls.count { it.method == "session.resume" }
        repository.completePath("durable-tip", "@REA", "/workspace")
        assertEquals(resumeCount, rpc.calls.count { it.method == "session.resume" })
        assertEquals("runtime-a", rpc.call("complete.path").params.string("session_id"))
    }

    @Test
    fun `reconnect rejects an old catalog result and completions use the new runtime`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc().apply { modelOptionsResponse = CompletableDeferred() }
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, state, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        val stale = async { runCatching { repository.loadModelOptions("durable-a") }.exceptionOrNull() }
        runCurrent()
        clients.value = null
        state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()

        val second = FakeRpc().apply {
            resumeA = RESUME_A.replace("runtime-a", "runtime-reconnected")
        }
        clients.value = second
        state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()
        first.modelOptionsResponse?.complete(json(MODEL_OPTIONS))
        runCurrent()

        assertTrue(stale.await() is GatewayRpcException)
        repository.completePath("durable-a", "@REA", "/workspace")
        assertEquals("runtime-reconnected", second.call("complete.path").params.string("session_id"))
    }

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
        assertEquals(listOf("101", "102", "runtime-a-history-2"), history.map { it.id })
        assertEquals("hello", (history[0] as UserTurn).text)
        assertEquals("hi", (history[1] as AssistantTurn).markdown)
        assertEquals("Read", (history[2] as ToolActivity).label)
        assertEquals("Read file.txt", (history[2] as ToolActivity).detail)
    }

    @Test
    fun `hydration keeps the durable row id of every stamped row and sends the forward hedge`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { historyResult = HISTORY }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")

        // The pinned Gateway stamps its own read and never looks at this param
        // (tui_gateway/methods_session.py:2611-2620 @ 3ca096de). The flag is the
        // hedge for a Gateway that ever makes the stamped read opt-in, and it
        // costs nothing here because this handler reads only `session_id`.
        assertEquals(JsonPrimitive(true), rpc.call("session.history").params["include_row_ids"])
        val transcript = cache.transcript("durable-a")
        assertEquals(
            listOf(TranscriptRowId(101), TranscriptRowId(102), null),
            transcript.map(TranscriptEntry::rowId),
        )
        // The tool row is persisted too, but upstream's projection returns
        // before the stamp, so it arrives unstamped and must not be handed a
        // fabricated address.
        assertNull(transcript.filterIsInstance<ToolActivity>().single().rowId)
    }

    @Test
    fun `a gateway that ignores the row id flag still hydrates and no id is invented`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { historyResult = HISTORY_WITHOUT_ROW_IDS }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")

        val transcript = cache.transcript("durable-a")
        assertEquals("hello", transcript.filterIsInstance<UserTurn>().single().text)
        assertEquals("hi", transcript.filterIsInstance<AssistantTurn>().single().markdown)
        assertEquals("Read", transcript.filterIsInstance<ToolActivity>().single().label)
        assertTrue(
            "an unstamped transcript has no durable ids, and none may be minted from a local key",
            transcript.all { it.rowId == null },
        )
        assertEquals(
            listOf("runtime-a-history-0", "runtime-a-history-1", "runtime-a-history-2"),
            transcript.map(TranscriptEntry::id),
        )
    }

    @Test
    fun `no durable row id is invented from a rendering key or a non-positive stamp`() {
        val history = parseHistory(
            json(
                """{"messages":[
                  {"role":"user","text":"hello","id":"77"},
                  {"role":"user","text":"zero","row_id":0},
                  {"role":"user","text":"negative","row_id":-5},
                  {"role":"user","text":"fractional","row_id":12.5}
                ],"count":4}""",
            ),
            runtimeId = "runtime-a",
            nowMillis = 99,
        )

        assertEquals(listOf(null, null, null, null), history.map(TranscriptEntry::rowId))
        // A numeric rendering key is exactly what a fabricated address would
        // borrow, and a row id of 0 or below is a sentinel, not an address.
        assertEquals(listOf("77", "0", "-5", "12.5"), history.map(TranscriptEntry::id))
        assertEquals(
            listOf("hello", "zero", "negative", "fractional"),
            history.filterIsInstance<UserTurn>().map(UserTurn::text),
        )
    }

    @Test
    fun `a stamped tool row keeps its address and one assistant row stamps both its entries`() {
        val history = parseHistory(
            json(
                """{"messages":[
                  {"role":"tool","name":"Read","context":"Read file.txt","row_id":401},
                  {"role":"assistant","reasoning":"weigh the options","text":"answer","row_id":402}
                ],"count":2}""",
            ),
            runtimeId = "runtime-a",
            nowMillis = 99,
        )

        // Upstream returns from its tool projection before the stamp
        // (server.py:7601-7615, stamp at :7645), so this is null in practice —
        // read rather than hardcoded, so a Gateway that stamps tool rows keeps
        // that address.
        assertEquals(TranscriptRowId(401), history.filterIsInstance<ToolActivity>().single().rowId)
        // One durable row projects to two entries, which share the one address
        // while keeping their own rendering keys.
        assertEquals(TranscriptRowId(402), history.filterIsInstance<ReasoningActivity>().single().rowId)
        assertEquals(TranscriptRowId(402), history.filterIsInstance<AssistantTurn>().single().rowId)
        assertEquals(listOf("402-reasoning", "402"), history.drop(1).map(TranscriptEntry::id))
    }

    @Test
    fun `durable row ids survive a reconnect and the rehome onto canonical identity`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc().apply { historyResult = HISTORY }
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val state = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, state, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        val hydrated = cache.transcript("durable-a").map(TranscriptEntry::rowId)

        clients.value = null
        state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        val second = FakeRpc().apply {
            historyResult = HISTORY
            resumeA = RESUME_COMPRESSION_TIP
        }
        clients.value = second
        state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        val canonical = repository.openSession("durable-a")

        assertEquals("continuation-tip", canonical)
        assertEquals(JsonPrimitive(true), second.call("session.history").params["include_row_ids"])
        assertEquals(listOf(TranscriptRowId(101), TranscriptRowId(102), null), hydrated)
        assertEquals(hydrated, cache.transcript("continuation-tip").map(TranscriptEntry::rowId))
        assertTrue(cache.transcript("durable-a").isEmpty())
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
    fun `history reads Desktop reasoning keys without adding blank prose`() {
        val history = parseHistory(
            json(
                """{"messages":[
                  {"id":"content","role":"assistant","reasoning_content":"content reasoning","text":""},
                  {"id":"details","role":"assistant","reasoning_details":"details reasoning","text":""}
                ]}""",
            ),
            runtimeId = "runtime-reasoning-keys",
            nowMillis = CLOCK,
        )

        assertEquals(listOf("content-reasoning", "details-reasoning"), history.map { it.id })
        assertEquals(listOf("content reasoning", "details reasoning"), history.filterIsInstance<ReasoningActivity>().map { it.text })
        assertTrue(history.none { it is AssistantTurn })
    }

    @Test
    fun `thinking delta updates provider wait progress without entering reasoning`() = runTest {
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

        rpc.emit("thinking.delta", "runtime-a", """{"text":"(ᵔᴥᵔ) Consulting…"}""")
        runCurrent()

        assertEquals("(ᵔᴥᵔ) Consulting…", cache.session("durable-a")?.progress?.text)
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertTrue(cache.transcript("durable-a").none { it is ReasoningActivity })
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
    fun `live todo tool owns the composer task list and never becomes a transcript row`() = runTest {
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
            "tool.start",
            "runtime-a",
            """{"tool_id":"todo-1","name":"todo","arguments":{"todos":[
                {"id":"plan","content":"Implement status row","status":"in_progress"},
                {"id":"tests","content":"Add tests","status":"pending"}
            ]}}""",
        )
        runCurrent()

        val todos = cache.session("durable-a")?.composerStatus?.todos.orEmpty()
        assertEquals(listOf("plan", "tests"), todos.map { it.id })
        assertEquals(ComposerTodoState.InProgress, todos.first().state)
        assertTrue(cache.transcript("durable-a").none { it is ToolActivity && it.toolName == "todo" })

        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()
        assertTrue(cache.session("durable-a")?.composerStatus?.todos.orEmpty().isEmpty())
    }

    @Test
    fun `named identifierless tool cannot inherit an active todo correlation id`() = runTest {
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
            "tool.start",
            "runtime-a",
            """{"tool_id":"todo-1","name":"todo","arguments":{"todos":[
                {"id":"plan","content":"Keep planning","status":"in_progress"}
            ]}}""",
        )
        rpc.emit("tool.start", "runtime-a", """{"name":"terminal","context":"./gradlew check"}""")
        runCurrent()

        assertEquals(listOf("plan"), cache.session("durable-a")?.composerStatus?.todos?.map { it.id })
        assertEquals(listOf("terminal"), cache.transcript("durable-a").filterIsInstance<ToolActivity>().map { it.toolName })
    }

    @Test
    fun `finished todo list lingers for four seconds then clears`() = runTest {
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
            "tool.start",
            "runtime-a",
            """{"tool_id":"todo-1","name":"todo","arguments":{"todos":[
                {"id":"plan","content":"Implement status row","status":"in_progress"}
            ]}}""",
        )
        rpc.emit(
            "tool.complete",
            "runtime-a",
            """{"tool_id":"todo-1","name":"todo","result":{"todos":[
                {"id":"plan","content":"Implement status row","status":"completed"},
                {"id":"cancelled","content":"Discarded detour","status":"cancelled"}
            ]}}""",
        )
        runCurrent()

        assertEquals(2, cache.session("durable-a")?.composerStatus?.todos?.size)
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(2, cache.session("durable-a")?.composerStatus?.todos?.size)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(cache.session("durable-a")?.composerStatus?.todos.orEmpty().isEmpty())
    }

    @Test
    fun `finished todo cleanup preserves a visible Gateway queue`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "queued turn", queued = true)
        rpc.emit(
            "tool.complete",
            "runtime-a",
            """{"tool_id":"todo-1","name":"todo","result":{"todos":[
                {"id":"done","content":"Finished task","status":"completed"}
            ]}}""",
        )
        runCurrent()

        advanceTimeBy(4_000)
        runCurrent()

        assertTrue(cache.session("durable-a")?.composerStatus?.todos.orEmpty().isEmpty())
        assertEquals(
            listOf("queued turn"),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.map { it.text },
        )
    }

    @Test
    fun `disconnect drops a completed todo landing instead of pinning it`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        rpc.emit(
            "tool.complete",
            "runtime-a",
            """{"tool_id":"todo-1","name":"todo","result":{"todos":[
                {"id":"done","content":"Finished task","status":"completed"}
            ]}}""",
        )
        runCurrent()
        assertEquals(1, cache.session("durable-a")?.composerStatus?.todos?.size)

        clients.value = null
        runCurrent()

        assertTrue(cache.session("durable-a")?.composerStatus?.todos.orEmpty().isEmpty())
    }

    @Test
    fun `active historical todo list does not pin above a reopened idle composer`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            historyResult = """{"messages":[{"role":"assistant","content":[
                {"type":"tool-call","toolName":"todo","args":{"todos":[
                    {"id":"stale","content":"Old unfinished plan","status":"pending"}
                ]}}
            ]}],"count":1}"""
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")

        assertTrue(cache.session("durable-a")?.composerStatus?.todos.orEmpty().isEmpty())
        assertTrue(cache.transcript("durable-a").none { it is ToolActivity && it.toolName == "todo" })
    }

    @Test
    fun `latest completed historical todo list lands briefly on reopen`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            historyResult = """{"messages":[{"role":"assistant","content":[
                {"type":"tool-call","toolName":"todo","args":{"todos":[
                    {"id":"older","content":"Older state","status":"pending"}
                ]}},
                {"type":"tool-call","toolName":"todo","result":{"todos":[
                    {"id":"latest","content":"Finished state","status":"completed"}
                ]}}
            ]}],"count":1}"""
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")
        assertEquals(listOf("latest"), cache.session("durable-a")?.composerStatus?.todos?.map { it.id })
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue(cache.session("durable-a")?.composerStatus?.todos.orEmpty().isEmpty())
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
    fun `session info branch and exact worktree path reach the cache and survive refresh`() = runTest {
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
            "session.info",
            "runtime-a",
            """{"running":false,"branch":"feat/project-views","cwd":"/srv/worktrees/project-views","stored_session_id":"durable-a"}""",
        )
        runCurrent()
        assertEquals("feat/project-views", cache.session("durable-a")?.gitBranch)
        assertEquals("/srv/worktrees/project-views", cache.session("durable-a")?.worktreePath)

        // A full refresh must not drop the connection-scoped context the server reported.
        rpc.sessionListResult = SESSION_LIST
        repository.refreshSessions()
        runCurrent()
        assertEquals("feat/project-views", cache.session("durable-a")?.gitBranch)
        assertEquals("/srv/worktrees/project-views", cache.session("durable-a")?.worktreePath)

        rpc.emit("session.info", "runtime-a", """{"running":false,"branch":null,"cwd":null}""")
        runCurrent()
        assertEquals(null, cache.session("durable-a")?.gitBranch)
        assertEquals(null, cache.session("durable-a")?.worktreePath)
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
    fun `session list aliases preserve exact server cwd and git branch`() {
        val exactPath = "/srv/worktrees/repo "
        val session = parseSession(
            json(
                """{"id":"durable-git","title":"Git","git_branch":"feat/list","cwd":"$exactPath"}""",
            ) as JsonObject,
            nowMillis = 99,
        )

        assertEquals("feat/list", session.gitBranch)
        assertEquals(exactPath, session.worktreePath)
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
    fun `attachments stage before submit and a refused stage never sends the prompt`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        repository.submit(
            "durable-a",
            "summarize this",
            attachments = listOf(
                OutgoingAttachment.GenericFile("notes.txt", "data:text/plain;base64,aGVsbG8="),
            ),
        )

        val attach = rpc.calls.indexOfFirst { it.method == "file.attach" }
        val submit = rpc.calls.indexOfFirst { it.method == "prompt.submit" }
        assertTrue(attach in 0 until submit)
        assertEquals("runtime-a", rpc.call("file.attach").params.string("session_id"))
        assertEquals("data:text/plain;base64,aGVsbG8=", rpc.call("file.attach").params.string("data_url"))
        assertEquals("@file:`notes.txt`\n\nsummarize this", rpc.call("prompt.submit").params.string("text"))

        rpc.attachFailure = GatewayRpcException("too large")
        try {
            repository.submit(
                "durable-a",
                "second message",
                attachments = listOf(OutgoingAttachment.Image("pic.png", "AAAA")),
            )
            error("expected refusal")
        } catch (_: GatewayRpcException) {
        }
        assertEquals(1, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `image attachments contribute no placeholder text and the optimistic row carries the ref line`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        repository.submit(
            "durable-a",
            "explain this chart",
            attachments = listOf(OutgoingAttachment.Image("chart.png", "AAAA")),
        )

        val attach = rpc.call("image.attach_bytes")
        assertEquals("runtime-a", attach.params.string("session_id"))
        assertEquals("AAAA", attach.params.string("content_base64"))
        assertEquals("chart.png", attach.params.string("filename"))

        // The wire text is the typed prompt only — never the gateway's
        // `[User attached image: …]` placeholder prose.
        assertEquals("explain this chart", rpc.call("prompt.submit").params.string("text"))

        // The optimistic row previews the ref the gateway will persist.
        val row = cache.transcript("durable-a").filterIsInstance<UserTurn>().single()
        assertEquals("explain this chart\n@image:/gw/img.png", row.text)
    }

    @Test
    fun `busy queued image stages and enters the Gateway queue without stealing live ownership`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")
        val transcriptBeforeQueue = cache.transcript("durable-a")

        val outcome = repository.submit(
            "durable-a",
            "inspect this",
            queued = true,
            attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
        )

        assertEquals(GatewaySubmitOutcome.Accepted, outcome)
        val submits = rpc.calls.filter { it.method == "prompt.submit" }
        assertEquals(2, submits.size)
        assertEquals("inspect this", submits.last().params.string("text"))
        assertTrue(requireNotNull(submits.last().params["queued"]).jsonPrimitive.boolean)
        assertEquals(transcriptBeforeQueue, cache.transcript("durable-a"))
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertEquals(
            "inspect this",
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.single()?.text,
        )

        rpc.emit("message.complete", "runtime-a", """{"text":"first done"}""")
        runCurrent()
        assertEquals(1, cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.size)
        rpc.emit("message.start", "runtime-a", """{"id":"queued-answer","role":"assistant"}""")
        runCurrent()
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `settled session info preserves a queued occurrence until its turn starts`() = runTest {
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
        repository.submit("durable-a", "first turn")
        rpc.emit("message.start", "runtime-a", """{"role":"assistant"}""")
        runCurrent()
        repository.submit("durable-a", "queued turn", queued = true)

        rpc.emit("message.complete", "runtime-a", """{"text":"first done"}""")
        runCurrent()
        rpc.emit(
            "session.info",
            "runtime-a",
            """{"stored_session_id":"durable-a","running":false}""",
        )
        runCurrent()

        assertEquals(
            listOf("queued turn"),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.map { it.text },
        )
        rpc.emit("message.start", "runtime-a", """{"role":"assistant"}""")
        runCurrent()
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `pre-start false heartbeat does not consume the queued occurrence at first start`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "queued turn", queued = true)

        rpc.emit("session.info", "runtime-a", """{"running":false}""")
        runCurrent()
        rpc.emit("message.start", "runtime-a", """{"role":"assistant"}""")
        runCurrent()

        assertEquals(
            listOf("queued turn"),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.map { it.text },
        )
        rpc.emit("message.complete", "runtime-a", """{"text":"first done"}""")
        runCurrent()
        rpc.emit("message.start", "runtime-a", """{"role":"assistant"}""")
        runCurrent()
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `activation keeps duplicate occurrences and an unbounded ordered local tail`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "duplicate", queued = true)
        repository.submit("durable-a", "duplicate", queued = true)
        repository.submit(
            "durable-a",
            "image turn",
            queued = true,
            attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
        )
        val tailTexts = (3..9).map { "queued-$it" }
        tailTexts.forEach { repository.submit("durable-a", it, queued = true) }
        val queuedTexts = listOf("duplicate", "duplicate", "image turn") + tailTexts
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        assertEquals(10, accepted.size)
        assertEquals(10, accepted.map { it.id }.toSet().size)
        assertEquals(queuedTexts, accepted.map { it.text })
        assertEquals(accepted[0].gatewayBatchId, accepted[1].gatewayBatchId)
        assertFalse(accepted[1].gatewayBatchId == accepted[2].gatewayBatchId)
        assertEquals(8, accepted.drop(2).map { it.gatewayBatchId }.toSet().size)

        // A partial activation response cannot prove that the Gateway queue is
        // empty, so it must not erase locally accepted occurrences.
        rpc.activateResult = """{"running":true}"""
        repository.openSession("durable-a")
        assertEquals(accepted, cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts)

        // The Gateway losslessly merges the two text-only occurrences into one
        // head envelope. Both local ids remain visible and ordered.
        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"duplicate\\n\\nduplicate\"}}"
        repository.openSession("durable-a")
        assertEquals(accepted, cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts)

        // One real drain edge consumes the whole merged envelope, not just one
        // equal-text row, while every later server envelope keeps its identity.
        rpc.emit("message.complete", "runtime-a", """{"text":"first done"}""")
        runCurrent()
        rpc.emit("message.start", "runtime-a", """{"role":"assistant"}""")
        runCurrent()
        rpc.activateResult = RESUME_RUNNING.dropLast(1) + ",\"queued\":{\"user\":\"image turn\"}}"
        repository.openSession("durable-a")
        assertEquals(
            accepted.drop(2),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts,
        )

        // A complete snapshot with no queued head is authoritative proof that
        // every locally retained occurrence has left the Gateway queue.
        rpc.activateResult = RESUME_RUNNING
        repository.openSession("durable-a")
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `reconnect preserves accepted queue occurrence ids and tail ordering`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "duplicate", queued = true)
        repository.submit("durable-a", "duplicate", queued = true)
        repository.submit(
            "durable-a",
            "image turn",
            queued = true,
            attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
        )
        repository.submit("durable-a", "tail", queued = true)
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        clients.value = null
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        assertEquals(accepted, cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts)

        val second = FakeRpc().apply {
            resumeA = RESUME_RUNNING.dropLast(1) +
                ",\"queued\":{\"user\":\"duplicate\\n\\nduplicate\"}}"
        }
        clients.value = second
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()

        assertEquals(accepted, cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts)
    }

    @Test
    fun `authoritative head can trim a self-copy from a locally merged queue batch`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "first turn", queued = true)
        repository.submit("durable-a", "later correction", queued = true)
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()
        assertEquals(accepted[0].gatewayBatchId, accepted[1].gatewayBatchId)

        // Gateway rejects the queued self-copy of the inflight user prompt, so
        // its authoritative merged head starts at the second local occurrence.
        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"later correction\"}}"
        repository.openSession("durable-a")

        assertEquals(
            listOf(accepted[1]),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts,
        )
    }

    @Test
    fun `authoritative foreign text around the local head preserves its occurrences`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "ours one", queued = true)
        repository.submit("durable-a", "ours two", queued = true)
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()
        assertEquals(2, accepted.size)
        assertEquals(accepted[0].gatewayBatchId, accepted[1].gatewayBatchId)

        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"theirs\\n\\nours one\\n\\nours two\\n\\nlater\"}}"
        repository.openSession("durable-a")
        val reconciled = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()
        assertEquals(listOf("theirs", "ours one", "ours two", "later"), reconciled.map { it.text })
        assertEquals(accepted, reconciled.slice(1..2))
        assertEquals(1, reconciled.map { it.gatewayBatchId }.toSet().size)

        repository.openSession("durable-a")
        assertEquals(reconciled, cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts)

        rpc.emit("message.complete", "runtime-a", """{"text":"first done"}""")
        runCurrent()
        rpc.emit("message.start", "runtime-a", """{"role":"assistant"}""")
        runCurrent()
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `authoritative exact head wins over an earlier loose text match`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "x", queued = true)
        repository.submit(
            "durable-a",
            "image turn",
            queued = true,
            attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
        )
        repository.submit("durable-a", "x\n\ny", queued = true)
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()
        assertEquals(3, accepted.size)
        assertEquals(3, accepted.map { it.gatewayBatchId }.toSet().size)

        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"x\\n\\ny\"}}"
        repository.openSession("durable-a")

        assertEquals(
            listOf(accepted.last()),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts,
        )
    }

    @Test
    fun `a later loose head match wins only when a reconnect could have hidden the drain`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "tail", queued = true)
        repository.submit(
            "durable-a",
            "image turn",
            queued = true,
            attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
        )
        repository.submit("durable-a", "head", queued = true)
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        // "head\n\ntail" reads two ways: our "tail" batch with someone else's
        // "head" in front, or our later "head" batch with their "tail" behind
        // and the first two envelopes already run. Only a connection loss can
        // hide a drain, so that reading needs the reconnect to license it.
        clients.value = null
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        val second = FakeRpc().apply {
            resumeA = RESUME_RUNNING.dropLast(1) +
                ",\"queued\":{\"user\":\"head\\n\\ntail\"}}"
        }
        clients.value = second
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
        runCurrent()
        val reconciled = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        assertEquals(listOf("head", "tail"), reconciled.map { it.text })
        assertEquals(accepted.last(), reconciled.first())
        assertEquals(1, reconciled.map { it.gatewayBatchId }.toSet().size)
    }

    @Test
    fun `a connected head keeps the earliest matching batch and everything queued behind it`() = runTest {
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
        repository.submit("durable-a", "first turn")
        // Another client queued "theirs" first, so our text merged in behind it.
        repository.submit("durable-a", "ok", queued = true)
        repository.submit(
            "durable-a",
            "image turn",
            queued = true,
            attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
        )
        repository.submit("durable-a", "ok", queued = true)
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()
        assertEquals(3, accepted.size)
        assertEquals(3, accepted.map { it.gatewayBatchId }.toSet().size)

        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"theirs\\n\\nok\"}}"
        repository.openSession("durable-a")
        val reconciled = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        // The trailing "ok" is a look-alike for the head, not the head itself:
        // reading it as the head would drop the image turn queued in front of it.
        assertEquals(listOf("theirs", "ok", "image turn", "ok"), reconciled.map { it.text })
        assertEquals(accepted, reconciled.drop(1))
    }

    @Test
    fun `an authoritative head covering only the front of a local batch keeps its tail`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "one", queued = true)
        repository.submit("durable-a", "two", queued = true)
        repository.submit("durable-a", "three", queued = true)
        val accepted = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()
        assertEquals(3, accepted.size)
        assertEquals(1, accepted.map { it.gatewayBatchId }.toSet().size)

        // Another client's image envelope split the batch server-side, so the
        // authoritative head stops after "two" and "three" waits behind it.
        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"one\\n\\ntwo\"}}"
        repository.openSession("durable-a")
        val reconciled = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        assertEquals(listOf("one", "two", "three"), reconciled.map { it.text })
        assertEquals(accepted, reconciled)
    }

    @Test
    fun `authoritative foreign text before the local head preserves its occurrence`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit("durable-a", "ours", queued = true)
        val accepted = requireNotNull(
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.single(),
        )

        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"theirs\\n\\nours\"}}"
        repository.openSession("durable-a")
        val reconciled = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        assertEquals(listOf("theirs", "ours"), reconciled.map { it.text })
        assertEquals(accepted, reconciled.last())
        assertEquals(1, reconciled.map { it.gatewayBatchId }.toSet().size)
    }

    @Test
    fun `authoritative foreign text never merges across a local image envelope`() = runTest {
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
        repository.submit("durable-a", "first turn")
        repository.submit(
            "durable-a",
            "ours",
            queued = true,
            attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
        )
        val accepted = requireNotNull(
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.single(),
        )
        assertFalse(accepted.gatewayBatchMergeable)

        rpc.activateResult = RESUME_RUNNING.dropLast(1) +
            ",\"queued\":{\"user\":\"theirs\\n\\nours\"}}"
        repository.openSession("durable-a")
        val reconciled = cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty()

        assertEquals(listOf("theirs\n\nours", "ours"), reconciled.map { it.text })
        assertEquals(accepted, reconciled.last())
        assertEquals(2, reconciled.map { it.gatewayBatchId }.toSet().size)
    }

    @Test
    fun `attachment staging and queued submit are one same-session transaction`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { imageAttachResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")

        val attachmentSubmit = async {
            repository.submit(
                "durable-a",
                "attachment turn",
                queued = true,
                attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
            )
        }
        runCurrent()
        val textDrain = async { repository.submit("durable-a", "text drain", queued = true) }
        runCurrent()

        assertEquals(1, rpc.calls.count { it.method == "prompt.submit" })
        rpc.imageAttachResponse?.complete(
            json("""{"attached":true,"path":"/gw/img.png","text":"[User attached image: img.png]"}"""),
        )
        assertEquals(GatewaySubmitOutcome.Accepted, attachmentSubmit.await())
        assertEquals(GatewaySubmitOutcome.Accepted, textDrain.await())
        assertEquals(
            listOf("first turn", "attachment turn", "text drain"),
            rpc.calls.filter { it.method == "prompt.submit" }.map { it.params.string("text") },
        )
    }

    @Test
    fun `stop cancels an attachment that is still staging without waiting for the upload`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { imageAttachResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")

        val attachmentSubmit = async {
            runCatching {
                repository.submit(
                    "durable-a",
                    "attachment turn",
                    queued = true,
                    attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
                )
            }
        }
        runCurrent()
        val interrupt = async { repository.requestInterrupt("durable-a") }
        runCurrent()

        // Stop remains an emergency control: it completes while the upload is
        // still waiting, and invalidates the not-yet-dispatched prompt.
        assertEquals(GatewayInterruptOutcome.Interrupted, interrupt.await())
        // Nothing was on the wire to order against, so Stop paid no wait at all.
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, rpc.calls.count { it.method == "session.interrupt" })
        rpc.imageAttachResponse?.complete(
            json("""{"attached":true,"path":"/gw/img.png","text":"[User attached image: img.png]"}"""),
        )

        val failure = attachmentSubmit.await().exceptionOrNull() as GatewayRpcException
        assertFalse(failure.requestMayHaveBeenAccepted)
        assertEquals(
            listOf("prompt.submit", "image.attach_bytes", "session.interrupt", "image.detach"),
            rpc.calls
                .filter {
                    it.method in setOf("prompt.submit", "image.attach_bytes", "session.interrupt", "image.detach")
                }
                .map(RpcCall::method),
        )
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `stop orders after an attachment prompt that is already on the wire`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            stopDispatchWaitMillis = 2_000L,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")
        rpc.promptResponse = CompletableDeferred()

        val attachmentSubmit = async {
            repository.submit(
                "durable-a",
                "attachment turn",
                queued = true,
                attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
            )
        }
        runCurrent()
        val interrupt = async { repository.requestInterrupt("durable-a") }
        runCurrent()

        assertEquals(0, rpc.calls.count { it.method == "session.interrupt" })
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(0, rpc.calls.count { it.method == "session.interrupt" })

        rpc.promptResponse?.complete(json("{}"))
        runCurrent()

        // The wait is a ceiling, not a schedule: Stop goes out the moment the
        // prompt frame is away, not at the full stopDispatchWaitMillis.
        assertEquals(1, rpc.calls.count { it.method == "session.interrupt" })
        assertEquals(500L, testScheduler.currentTime)

        assertEquals(GatewaySubmitOutcome.Accepted, attachmentSubmit.await())
        assertEquals(GatewayInterruptOutcome.Interrupted, interrupt.await())
        assertEquals(
            listOf("image.attach_bytes", "prompt.submit", "session.interrupt"),
            rpc.calls
                .dropWhile { it.method != "image.attach_bytes" }
                .filter { it.method in setOf("image.attach_bytes", "prompt.submit", "session.interrupt") }
                .map(RpcCall::method),
        )
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `stop waits briefly then interrupts a prompt with a lost acknowledgement`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            stopDispatchWaitMillis = 2_000L,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")
        rpc.promptResponse = CompletableDeferred()

        val attachmentSubmit = async {
            repository.submit(
                "durable-a",
                "attachment turn",
                queued = true,
                attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
            )
        }
        runCurrent()
        val interrupt = async { repository.requestInterrupt("durable-a") }
        runCurrent()

        assertEquals(0, rpc.calls.count { it.method == "session.interrupt" })
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(0, rpc.calls.count { it.method == "session.interrupt" })
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Interrupted, interrupt.await())
        assertEquals(1, rpc.calls.count { it.method == "session.interrupt" })
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
        rpc.promptResponse?.complete(json("{}"))

        assertEquals(GatewaySubmitOutcome.Accepted, attachmentSubmit.await())
        assertEquals(
            listOf("image.attach_bytes", "prompt.submit", "session.interrupt"),
            rpc.calls
                .dropWhile { it.method != "image.attach_bytes" }
                .filter { it.method in setOf("image.attach_bytes", "prompt.submit", "session.interrupt") }
                .map(RpcCall::method),
        )
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
    }

    @Test
    fun `rejected Stop does not hide a queued prompt acknowledged afterward`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { interruptResult = """{"status":"rejected"}""" }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            stopDispatchWaitMillis = 1_000L,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "first turn")
        rpc.promptResponse = CompletableDeferred()

        val queuedSubmit = async { repository.submit("durable-a", "queued turn", queued = true) }
        runCurrent()
        val interrupt = async { repository.requestInterrupt("durable-a") }
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Rejected, interrupt.await())
        rpc.promptResponse?.complete(json("{}"))
        assertEquals(GatewaySubmitOutcome.Accepted, queuedSubmit.await())
        assertEquals(
            listOf("queued turn"),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.map { it.text },
        )
    }

    @Test
    fun `definite prompt rejection detaches a staged image before allowing retry`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { promptFailures = 1 }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        val failure = runCatching {
            repository.submit(
                "durable-a",
                "inspect this",
                attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
            )
        }.exceptionOrNull() as GatewayRpcException

        assertFalse(failure.requestMayHaveBeenAccepted)
        assertEquals(listOf("image.attach_bytes", "prompt.submit", "image.detach"), rpc.calls.takeLast(3).map { it.method })
        assertEquals("/gw/img.png", rpc.call("image.detach").params.string("path"))
    }

    @Test
    fun `failed detach converts a definite rejection into review-required`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            promptFailures = 1
            detachFailure = GatewayRpcException("detach unavailable")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        val failure = runCatching {
            repository.submit(
                "durable-a",
                "inspect this",
                attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
            )
        }.exceptionOrNull() as GatewayRpcException

        assertTrue(failure.requestMayHaveBeenAccepted)
    }

    @Test
    fun `lost stage acknowledgement is review-required and never submits the prompt`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            attachFailure = GatewayRpcException("attachment acknowledgement lost", requestMayHaveBeenAccepted = true)
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        val failure = runCatching {
            repository.submit(
                "durable-a",
                "inspect this",
                attachments = listOf(OutgoingAttachment.Image("shot.png", "AAAA")),
            )
        }.exceptionOrNull() as GatewayRpcException

        assertTrue(failure.requestMayHaveBeenAccepted)
        assertEquals(0, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `later stage rejection detaches earlier images and never submits the prompt`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { failImageAttachAt = 2 }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        val failure = runCatching {
            repository.submit(
                "durable-a",
                "compare these",
                attachments = listOf(
                    OutgoingAttachment.Image("one.png", "AAAA"),
                    OutgoingAttachment.Image("two.png", "BBBB"),
                ),
            )
        }.exceptionOrNull() as GatewayRpcException

        assertFalse(failure.requestMayHaveBeenAccepted)
        assertEquals(0, rpc.calls.count { it.method == "prompt.submit" })
        assertEquals("/gw/img.png", rpc.call("image.detach").params.string("path"))
    }

    @Test
    fun `detach cleanup attempts every staged image after one path reports false`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            failImageAttachAt = 3
            detachResults.addAll(listOf(false, true))
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        val failure = runCatching {
            repository.submit(
                "durable-a",
                "compare these",
                attachments = listOf(
                    OutgoingAttachment.Image("one.png", "AAAA"),
                    OutgoingAttachment.Image("two.png", "BBBB"),
                    OutgoingAttachment.Image("three.png", "CCCC"),
                ),
            )
        }.exceptionOrNull() as GatewayRpcException

        assertTrue(failure.requestMayHaveBeenAccepted)
        assertEquals(2, rpc.calls.count { it.method == "image.detach" })
        assertEquals(0, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `resume projects an accepted Gateway queue entry above the composer`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            resumeA = RESUME_RUNNING.dropLast(1) + ",\"queued\":{\"user\":\"inspect after this\"}}"
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        repository.openSession("durable-a")

        assertEquals(
            "inspect after this",
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.single()?.text,
        )
    }

    @Test
    fun `an image-only send asks the desktop fallback question`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        repository.submit(
            "durable-a",
            "",
            attachments = listOf(OutgoingAttachment.Image("chart.png", "AAAA")),
        )

        assertEquals("What do you see in this image?", rpc.call("prompt.submit").params.string("text"))
        val row = cache.transcript("durable-a").filterIsInstance<UserTurn>().single()
        assertEquals("What do you see in this image?\n@image:/gw/img.png", row.text)
    }

    @Test
    fun `a file ref always reaches the wire even alongside an image with no typed text`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val connection = MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected))
        val repository = LiveGatewaySessionRepository(cache, connection, clients, backgroundScope) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        repository.openSession("durable-a")

        repository.submit(
            "durable-a",
            "",
            attachments = listOf(
                OutgoingAttachment.Image("chart.png", "AAAA"),
                OutgoingAttachment.GenericFile("notes.txt", "data:text/plain;base64,aGVsbG8="),
            ),
        )

        // Desktop parity: file refs compose first; the image-only question is
        // the fallback, never a branch that outranks a staged file.
        assertEquals("@file:`notes.txt`", rpc.call("prompt.submit").params.string("text"))
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
    fun `a second submit to the same session is refused while its turn is active`() = runTest {
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
        val failure = runCatching { repository.submit("durable-a", "second") }.exceptionOrNull()

        assertTrue(failure is GatewayRpcException)
        assertEquals("Hermes is already working in this session.", failure?.message)
        assertEquals(1, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `a second concurrent submit cannot steal attribution of identifier-less events`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        // Two live turns: only the first owns the unstamped pin, so a late
        // unstamped delta still lands on the first session's transcript.
        repository.openSession("durable-a")
        repository.submit("durable-a", "first")
        repository.openSession("durable-b")
        repository.submit("durable-b", "second")
        rpc.emit("message.delta", null, """{"text":"unstamped"}""")
        runCurrent()

        val firstTranscript = cache.transcript("durable-a").filterIsInstance<AssistantTurn>()
        assertTrue(firstTranscript.any { it.markdown.contains("unstamped") })
        assertTrue(cache.transcript("durable-b").filterIsInstance<AssistantTurn>().isEmpty())
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
        assertEquals(TurnTermination.SessionNoLongerRunning, assistant.termination)
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
    fun `session info projects typed authoritative composer controls`() = runTest {
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
        val projected = async { repository.composerControls.first() }
        runCurrent()

        rpc.emit(
            "session.info",
            "runtime-a",
            """{"stored_session_id":"durable-a","running":false,"model":"reasoner-v3","provider":"acme","reasoning_effort":"high","fast":true}""",
        )
        runCurrent()

        val event = projected.await()
        assertEquals("durable-a", event.durableId)
        assertEquals(ComposerModelSelection("reasoner-v3", "acme"), event.selection)
        assertTrue(event.hasSelection)
        assertEquals(ReasoningEffort.High, event.reasoning)
        assertTrue(event.hasReasoning)
        assertEquals(FastMode.Fast, event.fast)
        assertTrue(event.hasFast)
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
        assertNull(uncertain.termination)
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
        repository.submit("durable-a", "queued follow-up", queued = true)

        clients.value = null
        connection.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        runCurrent()
        assertEquals(
            listOf("queued follow-up"),
            cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts?.map { it.text },
        )
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
        assertTrue(cache.session("durable-a")?.composerStatus?.gatewayQueuedPrompts.orEmpty().isEmpty())
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
        // The remote live runtime-b does not block durable-a's own submit:
        // only same-session turns gate a send (Desktop per-target parity).
        repository.submit("durable-a", "must go through")
        assertEquals(2, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `a rejected submit on a non-pinned concurrent session rolls back and stays usable`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()

        // durable-a owns the pin; durable-b's submit is definitely rejected.
        repository.openSession("durable-a")
        repository.submit("durable-a", "first")
        repository.openSession("durable-b")
        rpc.promptFailures = 1
        assertTrue(runCatching { repository.submit("durable-b", "second") }.isFailure)
        runCurrent()

        assertEquals(SessionStatus.Idle, cache.session("durable-b")?.status)
        assertFalse(cache.transcript("durable-b").any { it is UserTurn && it.text == "second" })
        // The rollback frees b's guard: the retry goes out instead of wedging.
        repository.submit("durable-b", "second")
        assertEquals(3, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `stop and steer reach a live non-pinned concurrent session`() = runTest {
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
        rpc.emit("session.info", "runtime-b", """{"running":true}""")
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Interrupted, repository.requestInterrupt("durable-b"))
        assertEquals(1, rpc.calls.count { it.method == "session.interrupt" })
    }

    @Test
    fun `a stale pre-start heartbeat does not settle or release a concurrent submit`() = runTest {
        val cache = SessionCache()
        var now = CLOCK
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { now }
        runCurrent()

        repository.openSession("durable-a")
        repository.submit("durable-a", "first")
        repository.openSession("durable-b")
        repository.submit("durable-b", "second")
        // Heartbeat for b that predates its turn arriving inside the grace.
        rpc.emit("session.info", "runtime-b", """{"running":false}""")
        runCurrent()

        assertEquals(SessionStatus.Working, cache.session("durable-b")?.status)
        assertTrue(runCatching { repository.submit("durable-b", "third") }.exceptionOrNull() is GatewayRpcException)

        // After the grace expires the same heartbeat settles normally.
        now += 15_001
        rpc.emit("session.info", "runtime-b", """{"stored_session_id":"durable-b","running":false}""")
        runCurrent()
        assertEquals(SessionStatus.Idle, cache.session("durable-b")?.status)
        repository.submit("durable-b", "third")
        assertEquals(3, rpc.calls.count { it.method == "prompt.submit" })
    }

    @Test
    fun `a surviving locally submitted turn inherits identifier-less attribution`() = runTest {
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
        rpc.emit("message.start", "runtime-b", """{"id":"b-reply","role":"assistant"}""")
        repository.submit("durable-b", "second")
        // a settles first while b is still live.
        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()

        rpc.emit("message.delta", null, """{"delta":"for-b"}""")
        runCurrent()
        assertTrue(
            cache.transcript("durable-b").filterIsInstance<AssistantTurn>().any { it.markdown.contains("for-b") },
        )
        assertFalse(cache.transcript("durable-a").filterIsInstance<AssistantTurn>().any { it.markdown.contains("for-b") })
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
            historyResult = """{"messages":[{"row_id":201,"role":"user","text":"current prompt"}],"count":1}"""
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
            historyResult = """{"messages":[{"row_id":201,"role":"user","text":"long job"}],"count":1}"""
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
        assertEquals("201", transcript.filterIsInstance<UserTurn>().single().id)
        assertEquals("202", transcript.filterIsInstance<AssistantTurn>().single().id)
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
        rpc.historyResult = """{"messages":[{"row_id":201,"role":"user","text":"server truth"}],"count":1}"""

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
            historyResult = """{"messages":[{"row_id":301,"role":"assistant","text":"after compression"}],"count":1}"""
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
        assertEquals(listOf("301"), cache.transcript("continuation-tip").map { it.id })

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
    fun `session reclaim reasons end a live turn externally`() = runTest {
        listOf(
            "ws_orphan_reap" to TurnTermination.WsOrphanReap,
            "idle_timeout" to TurnTermination.IdleTimeout,
            "lru_evict" to TurnTermination.LruEvict,
        ).forEach { (reason, expectedTermination) ->
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
            repository.submit("durable-a", "keep going")
            rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
            rpc.emit("message.delta", "runtime-a", """{"delta":"partial"}""")
            rpc.emit(
                "session.reclaimed",
                null,
                """{"session_id":"runtime-a","stored_session_id":"durable-a","reason":"$reason"}""",
            )
            runCurrent()

            val turn = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().single()
            assertFalse(turn.streaming)
            assertEquals("partial", turn.markdown)
            assertEquals(expectedTermination, turn.termination)
        }
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
    fun `unattributed interrupted completion stays external and seals every unfinished tool`() = runTest {
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
        assertEquals(TurnTermination.InterruptedExternally, stopped.termination)
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
    fun `locally requested interrupt attributes its interrupted completion to the user`() = runTest {
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
        rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"partial"}""")
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Interrupted, repository.requestInterrupt("durable-a"))
        rpc.emit("message.complete", "runtime-a", """{"text":"partial","status":"interrupted"}""")
        runCurrent()

        val turn = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last()
        assertEquals(TurnTermination.UserRequested, turn.termination)
    }

    @Test
    fun `local interrupt attributes a running false session settle to the user`() = runTest {
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
        rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"partial"}""")
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Interrupted, repository.requestInterrupt("durable-a"))
        rpc.emit("session.info", "runtime-a", """{"running":false}""")
        runCurrent()

        val turn = cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last()
        assertEquals("partial", turn.markdown)
        assertEquals(TurnTermination.UserRequested, turn.termination)
    }

    @Test
    fun `rejected second stop cannot revoke the first confirmed stop attribution`() = runTest {
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
        rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Interrupted, repository.requestInterrupt("durable-a"))
        rpc.interruptResult = """{"status":"rejected"}"""
        assertEquals(GatewayInterruptOutcome.Rejected, repository.requestInterrupt("durable-a"))
        rpc.emit("message.complete", "runtime-a", """{"text":"partial","status":"interrupted"}""")
        runCurrent()

        assertEquals(
            TurnTermination.UserRequested,
            cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last().termination,
        )
    }

    @Test
    fun `new message start clears a stale local interrupt marker`() = runTest {
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
        rpc.emit("message.start", "runtime-a", """{"id":"first-reply","role":"assistant"}""")
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Interrupted, repository.requestInterrupt("durable-a"))
        rpc.emit("message.start", "runtime-a", """{"id":"later-reply","role":"assistant"}""")
        rpc.emit("message.complete", "runtime-a", """{"text":"later","status":"interrupted"}""")
        runCurrent()

        assertEquals(
            TurnTermination.InterruptedExternally,
            cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last().termination,
        )
    }

    @Test
    fun `interrupted completion during a local interrupt acknowledgement stays user attributed`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { interruptResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "stop this")
        rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
        runCurrent()

        val interrupt = async { repository.requestInterrupt("durable-a") }
        runCurrent()
        assertEquals(1, rpc.calls.count { it.method == "session.interrupt" })
        rpc.emit("message.complete", "runtime-a", """{"text":"partial","status":"interrupted"}""")
        runCurrent()
        assertEquals(
            TurnTermination.UserRequested,
            cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last().termination,
        )

        rpc.interruptResponse?.complete(json("""{"status":"interrupted"}"""))
        runCurrent()
        assertEquals(GatewayInterruptOutcome.Interrupted, interrupt.await())
    }

    @Test
    fun `unconfirmed interrupt failures do not attribute a later external completion`() = runTest {
        listOf(
            GatewayRpcException("acknowledgement lost", requestMayHaveBeenAccepted = true) to
                GatewayInterruptOutcome.Ambiguous,
            GatewayRpcException("interruption refused") to GatewayInterruptOutcome.Failed,
        ).forEach { (failure, expectedOutcome) ->
            val cache = SessionCache()
            val rpc = FakeRpc().apply { interruptFailure = failure }
            val repository = LiveGatewaySessionRepository(
                cache,
                MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
                MutableStateFlow<GatewayRpcClient?>(rpc),
                backgroundScope,
            ) { CLOCK }
            runCurrent()
            repository.openSession("durable-a")
            repository.submit("durable-a", "stop this")
            rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
            runCurrent()

            assertEquals(expectedOutcome, repository.requestInterrupt("durable-a"))
            rpc.emit("message.complete", "runtime-a", """{"text":"partial","status":"interrupted"}""")
            runCurrent()

            assertEquals(
                TurnTermination.InterruptedExternally,
                cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last().termination,
            )
        }
    }

    @Test
    fun `rejected interrupt does not attribute a later external completion`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { interruptResult = """{"status":"rejected"}""" }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "stop this")
        rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
        runCurrent()

        assertEquals(GatewayInterruptOutcome.Rejected, repository.requestInterrupt("durable-a"))
        rpc.emit("message.complete", "runtime-a", """{"text":"partial","status":"interrupted"}""")
        runCurrent()

        assertEquals(
            TurnTermination.InterruptedExternally,
            cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last().termination,
        )
    }

    @Test
    fun `cancelled interrupt does not attribute a later external completion`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply { interruptResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")
        repository.submit("durable-a", "stop this")
        rpc.emit("message.start", "runtime-a", """{"id":"reply-a","role":"assistant"}""")
        runCurrent()

        val interrupt = async { repository.requestInterrupt("durable-a") }
        runCurrent()
        interrupt.cancelAndJoin()
        rpc.emit("message.complete", "runtime-a", """{"text":"partial","status":"interrupted"}""")
        runCurrent()

        assertEquals(
            TurnTermination.InterruptedExternally,
            cache.transcript("durable-a").filterIsInstance<AssistantTurn>().last().termination,
        )
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

        rpc.emit("status.update", "runtime-a", """{"kind":"thinking","text":"Contemplating…"}""")
        runCurrent()
        assertEquals("Contemplating…", cache.session("durable-a")?.progress?.text)
        assertEquals(
            "transient turn progress belongs in the transcript, not above the composer",
            null,
            cache.session("durable-a")?.composerStatus,
        )

        rpc.emit("status.update", "runtime-a", """{"kind":"compacting","text":"Summarizing context…"}""")
        runCurrent()
        assertEquals("compacting", cache.session("durable-a")?.progress?.kind)
        assertEquals("Summarizing context…", cache.session("durable-a")?.progress?.text)
        assertTrue(cache.session("durable-a")?.composerStatus?.isCompacting == true)

        val afterFirst = cache.state.value
        rpc.emit("status.update", "runtime-a", """{"kind":"compacting","text":"Summarizing context…"}""")
        runCurrent()
        assertSame("an identical update must be a cache no-op", afterFirst, cache.state.value)

        rpc.emit("status.update", "runtime-a", """{"kind":"process","text":"Resuming interrupted turn…"}""")
        rpc.emit("status.update", "runtime-a", """{"kind":"progress","text":"Checking the result"}""")
        runCurrent()
        assertEquals("runtime-a", rpc.call("process.list").params.string("session_id"))
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
            """{"kind":"new_server_status","text":"must not replace progress"}""",
        ).forEach { malformed -> rpc.emit("status.update", "runtime-a", malformed) }
        runCurrent()
        assertSame("malformed status payloads must not erase useful progress", beforeMalformed, cache.state.value)

        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()
        assertEquals(null, cache.session("durable-a")?.progress)
        assertEquals(null, cache.session("durable-a")?.composerStatus)
    }

    @Test
    fun `queued submit uses runtime id and sends queued only for a drain`() = runTest {
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

        repository.submit("durable-a", "ordinary")
        val ordinary = rpc.call("prompt.submit").params
        assertEquals("runtime-a", ordinary.string("session_id"))
        assertFalse("queued is reserved for queue drains", "queued" in ordinary)

        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()
        assertEquals(GatewaySubmitOutcome.Accepted, repository.submit("durable-a", "drained", queued = true))
        val drained = rpc.call("prompt.submit").params
        assertEquals("runtime-a", drained.string("session_id"))
        assertTrue(requireNotNull(drained["queued"]).jsonPrimitive.boolean)
    }

    @Test
    fun `status event collector redacts and bounds rendered Gateway text`() = runTest {
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
            "status.update",
            "runtime-a",
            """{"kind":"progress","text":"Authorization: Bearer sentinel-status-token ${"x".repeat(1_000)}"}""",
        )
        runCurrent()

        val rendered = requireNotNull(cache.session("durable-a")?.progress?.text)
        assertFalse(rendered.contains("sentinel-status-token"))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.length <= 240)
    }

    @Test
    fun `redirect and steer are separate fenced operations with optimistic user rows`() = runTest {
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
        repository.submit("durable-a", "start")
        rpc.emit("message.start", "runtime-a", """{"id":"partial","role":"assistant"}""")
        rpc.emit("message.delta", "runtime-a", """{"delta":"in progress"}""")
        runCurrent()

        assertEquals(GatewayRedirectOutcome.Redirected, repository.redirect("durable-a", "correct course"))
        val redirect = rpc.call("session.redirect").params
        assertEquals("runtime-a", redirect.string("session_id"))
        assertEquals("correct course", redirect.string("text"))
        assertEquals("correct course", (cache.transcript("durable-a").last() as UserTurn).text)

        assertEquals(GatewaySteerOutcome.QueuedByGateway, repository.steer("durable-a", "at tool boundary"))
        val steer = rpc.call("session.steer").params
        assertEquals("runtime-a", steer.string("session_id"))
        assertEquals("at tool boundary", steer.string("text"))
        assertEquals("at tool boundary", (cache.transcript("durable-a").last() as UserTurn).text)

        val beforeRejected = cache.transcript("durable-a")
        rpc.redirectResult = """{"status":"rejected"}"""
        assertEquals(GatewayRedirectOutcome.Rejected, repository.redirect("durable-a", "do not append"))
        assertEquals(beforeRejected, cache.transcript("durable-a"))
    }

    @Test
    fun `redirect ambiguity and unsupported capability do not append truth or retry`() = runTest {
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
        repository.submit("durable-a", "start")
        rpc.emit("message.start", "runtime-a", """{"role":"assistant"}""")
        runCurrent()

        val beforeAmbiguous = cache.transcript("durable-a")
        rpc.redirectFailure = GatewayRpcException("acknowledgement lost", requestMayHaveBeenAccepted = true)
        assertEquals(GatewayRedirectOutcome.Ambiguous, repository.redirect("durable-a", "maybe delivered"))
        assertEquals(beforeAmbiguous, cache.transcript("durable-a"))

        rpc.redirectFailure = CancellationException("redirect deadline")
        assertEquals(GatewayRedirectOutcome.Ambiguous, repository.redirect("durable-a", "timed out"))
        assertEquals(beforeAmbiguous, cache.transcript("durable-a"))

        rpc.redirectFailure = GatewayRpcError(-32601, "Method not found")
        assertEquals(GatewayRedirectOutcome.Unsupported, repository.redirect("durable-a", "unsupported"))
        val callsAfterProbe = rpc.calls.count { it.method == "session.redirect" }
        assertEquals(GatewayRedirectOutcome.Unsupported, repository.redirect("durable-a", "not retried"))
        assertEquals(callsAfterProbe, rpc.calls.count { it.method == "session.redirect" })
    }

    @Test
    fun `needs input is protected from ordinary interrupt calls`() = runTest {
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
        assertEquals(GatewayInterruptOutcome.NeedsInput, repository.requestInterrupt("durable-a"))
        repository.interrupt("durable-a")
        assertFalse(rpc.calls.any { it.method == "session.interrupt" })
    }

    @Test
    fun `process and goal capabilities use safe runtime scoped results and cache unsupported`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc().apply {
            processListResult = """{"processes":[
                {"session_id":"process-a","command":"watch build\\nignored","status":"running","output_tail":"Authorization: Bearer sentinel-process-token"},
                {"session_id":"process-b","command":"test","status":"exited","exit_code":1}
            ]}"""
            goalStatusResult = """{"output":"⊙ Goal set: Ship the mobile composer"}"""
        }
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        repository.openSession("durable-a")

        val processes = repository.listProcesses("durable-a") as GatewayProcessListOutcome.Available
        assertEquals("runtime-a", rpc.call("process.list").params.string("session_id"))
        assertEquals(ComposerBackgroundProcessState.Running, processes.processes.first().state)
        assertEquals(ComposerBackgroundProcessState.Failed, processes.processes.last().state)
        assertFalse(processes.processes.first().output.orEmpty().contains("sentinel-process-token"))
        repository.refreshSessions()
        assertEquals(2, cache.session("durable-a")?.composerStatus?.backgroundProcesses?.size)
        rpc.processListResult = """{"processes":[{}]}"""
        assertEquals(GatewayProcessListOutcome.Failed, repository.listProcesses("durable-a"))
        assertEquals(2, cache.session("durable-a")?.composerStatus?.backgroundProcesses?.size)
        assertEquals(GatewayProcessKillOutcome.Killed, repository.killProcess("durable-a", "process-a"))
        assertEquals("process-a", rpc.call("process.kill").params.string("process_id"))
        assertEquals("runtime-a", rpc.call("process.kill").params.string("session_id"))

        val goal = repository.goalStatus("durable-a") as GatewayGoalStatusOutcome.Available
        assertEquals(ComposerGoalState.Active, goal.goal.state)
        assertEquals("Ship the mobile composer", goal.goal.title)
        assertEquals("goal status", rpc.call("slash.exec").params.string("command"))
        assertEquals("runtime-a", rpc.call("slash.exec").params.string("session_id"))

        rpc.processListFailure = GatewayRpcError(-32601, "Method not found")
        assertEquals(GatewayProcessListOutcome.Unsupported, repository.listProcesses("durable-a"))
        val processProbeCount = rpc.calls.count { it.method == "process.list" }
        assertEquals(GatewayProcessListOutcome.Unsupported, repository.listProcesses("durable-a"))
        assertEquals(processProbeCount, rpc.calls.count { it.method == "process.list" })

        val reconnected = FakeRpc()
        clients.value = reconnected
        runCurrent()
        assertTrue(repository.listProcesses("durable-a") is GatewayProcessListOutcome.Available)
        assertEquals(1, reconnected.calls.count { it.method == "process.list" })
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

    // -----------------------------------------------------------------------
    // The REST session list (#64 S11). Every fixture below is the shape
    // `GET /api/sessions` actually returns at hermes-agent @
    // `3ca096de5f8183cb2e0ec23673f294d5978656a3`: the `sessions` columns as
    // `list_sessions_rich` projects them (`hermes_state_portability.py:33-43`,
    // `hermes_state.py:9383-9401`) plus what the route stamps
    // (`hermes_cli/web_routers/sessions.py:145-159`).
    // -----------------------------------------------------------------------

    @Test
    fun `the session list reads the REST contract and carries its whole row`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest { restPage(REST_PAGE_RICH) }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        // The connect-time bootstrap refresh is already in flight; this call
        // queues behind it on the refresh mutex, so what it asked for is the
        // last thing asked.
        repository.refreshSessions()
        repository.setProfileRouting(ProfileRouting(listProfiles = listOf("work")))
        repository.refreshSessions()

        val request = http.requests.last()
        assertEquals("api/sessions", request.path)
        assertEquals("GET", request.method)
        // `profile` rides every list request (S02).
        assertEquals("work", request.query["profile"])
        assertEquals("50", request.query["limit"])
        assertEquals("0", request.query["offset"])
        assertEquals("recent", request.query["order"])
        assertEquals("exclude", request.query["archived"])
        // The older contract is not consulted when the route answers.
        assertTrue(rpc.calls.none { it.method == "session.list" })

        val row = cache.session("tip-a")!!
        assertEquals("Ship the pager", row.title)
        assertEquals(false, row.archived)
        assertEquals(true, row.pinned)
        assertEquals(true, row.unread)
        assertEquals("/srv/worktrees/session-list", row.worktreePath)
        assertEquals("feat/session-list-rest", row.gitBranch)
        assertEquals("anthropic/claude-sonnet-4", row.model)
        assertEquals(9, row.toolCallCount)
        assertEquals(12_000L, row.inputTokens)
        assertEquals(3_400L, row.outputTokens)
        assertEquals(0.42, row.actualCostUsd!!, 0.0)
        assertEquals(0.55, row.estimatedCostUsd!!, 0.0)
        // Stamped by the route itself (`sessions.py:145-150`), not inferred
        // from the parameter this leg asked with.
        assertEquals("work", row.remoteProfile)
        assertEquals("root-a", row.lineageRootId)
        // Navigation identity is the live tip the route surfaced, never the
        // durable root it also reports.
        assertEquals("tip-a", row.id)
    }

    @Test
    fun `an unscoped leg keeps the Gateway's launch profile out of the row`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest { restPage(REST_PAGE_LAUNCH_PROFILE) }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        // The scope a fresh install carries: no profile asked for, so the leg
        // sends no `profile` and the answer is whatever the Gateway is.
        repository.refreshSessions()
        runCurrent()
        assertNull(http.requests.last().query["profile"])

        // The route stamped these rows with the profile the Gateway process
        // itself was launched under (`sessions.py:146,152` →
        // `web_server.py:12461-12478`), which is a fact about the Gateway, not
        // about the row. Unstamped is what the RPC lane produces for the same
        // rows, and unstamped is the `default` bucket.
        val row = cache.session("launch-row")!!
        assertEquals("Under a named profile", row.title)
        assertNull(row.remoteProfile)
        // The whole point: the row is in the list a fresh install renders,
        // rather than filtered out of it into an empty sidebar.
        val visible = filterSessionsByProfileScope(cache.state.value.sessions.values.toList(), DEFAULT_PROFILE)
        assertTrue("launch-row" in visible.map(SessionSummary::id))

        // The scoped leg is the opposite case. When a profile *is* asked for,
        // the stamp is the canonicalised name that was asked for
        // (`sessions.py:95-97,146`) — it is the truth for that scope, so it is
        // kept, and these rows belong to `kani-backend` rather than to default.
        repository.setProfileRouting(ProfileRouting(listProfiles = listOf("kani-backend")))
        repository.refreshSessions()
        runCurrent()
        assertEquals("kani-backend", http.requests.last().query["profile"])
        assertEquals("kani-backend", cache.session("launch-row")?.remoteProfile)
        // Where a stamped row then lands is the filter's own rule, covered by
        // `ProfileScopeTest`; what this test owns is which stamp survives.
    }

    @Test
    fun `every page of a named leg carries its profile, not just the first`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest { request ->
            if (request.query["offset"] == "50") restPage(REST_DEEP_PAGE_TWO) else restPage(REST_DEEP_PAGE_ONE)
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        repository.setProfileRouting(ProfileRouting(listProfiles = listOf("work")))
        repository.refreshSessions()
        runCurrent()
        val firstPage = http.requests.last()
        assertEquals("0", firstPage.query["offset"])
        assertEquals("work", firstPage.query["profile"])

        repository.loadMoreSessions()
        runCurrent()

        // A leg is a scope, not a first page. Page two of `work` that forgot to
        // say `work` reads out of whichever profile the Gateway happens to be,
        // and those rows would land in this scope's list under a scope that
        // never asked for them.
        val secondPage = http.requests.last()
        assertEquals("50", secondPage.query["offset"])
        assertEquals("work", secondPage.query["profile"])
        // Both pages' rows are this scope's, on the route's own stamp.
        assertEquals("work", cache.session("durable-a")?.remoteProfile)
        assertEquals("work", cache.session("durable-c")?.remoteProfile)
    }

    @Test
    fun `a fallback page over the older contract cannot unpin what REST already said`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        var routeGone = false
        val http = FakeGatewayRest {
            if (routeGone) {
                GatewayHttpResult.Rejected(404, "Hermes refused that Gateway request.")
            } else {
                restPage(REST_PAGE_PINNED)
            }
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        runCurrent()
        assertEquals(true, cache.session("durable-a")?.pinned)
        assertEquals(true, cache.session("durable-a")?.unread)
        assertEquals(false, cache.session("durable-a")?.archived)

        // The route stops answering mid-connection, so the next list is the
        // older contract's — which has no pin, archive or unread column at all.
        routeGone = true
        repository.refreshSessions()
        runCurrent()

        // The fallback row really did land: this title is `session.list`'s.
        assertTrue(rpc.calls.any { it.method == "session.list" })
        assertEquals("Remote work", cache.session("durable-a")?.title)
        // And it did not get to answer the questions it cannot answer. A
        // contract that omits a field says *unknown*, and unknown must not
        // overwrite what a contract that could say already reported — a
        // half-REST refresh silently unpinning the list is exactly the failure
        // this rule exists for.
        assertEquals(true, cache.session("durable-a")?.pinned)
        assertEquals(true, cache.session("durable-a")?.unread)
        assertEquals(false, cache.session("durable-a")?.archived)
    }

    @Test
    fun `a terminal event re-reads page one without forgetting the pages already loaded`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        // Page one answers with a fresher title once the turn has finished, so
        // the assertions below can tell a real re-read from a skipped one.
        var settled = false
        val http = FakeGatewayRest { request ->
            when {
                request.query["offset"] == "50" -> restPage(REST_DEEP_PAGE_TWO)
                settled -> restPage(REST_DEEP_PAGE_ONE.replace("\"First\"", "\"First, settled\""))
                else -> restPage(REST_DEEP_PAGE_ONE)
            }
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        runCurrent()
        repository.loadMoreSessions()
        runCurrent()
        // Two 50-row windows consumed out of 152 in scope.
        assertEquals(152L, repository.sessionPaging.value.total)
        assertEquals(52L, repository.sessionPaging.value.remaining)
        assertTrue(repository.sessionPaging.value.canLoadMore)

        // A turn finishes. That is news about rows, and it refreshes the list.
        settled = true
        repository.openSession("durable-a")
        rpc.emit("message.complete", "runtime-a", """{"text":"done","status":"complete"}""")
        runCurrent()
        // Unlike every other read here, this one was not started by the test:
        // the REST client hops to `Dispatchers.IO` (`GatewayRestClient.kt:380`),
        // so an event-driven pass lands off this test's virtual clock and there
        // is no suspend call to await. Wait for the row it rewrites.
        cache.state.first { it.sessions["durable-a"]?.title == "First, settled" }
        runCurrent()

        // Page one was re-read — the fresher title is in the cache.
        assertEquals("0", http.requests.last().query["offset"])
        assertEquals("First, settled", cache.session("durable-a")?.title)
        // And the reader is still two pages deep. Resetting the cursor here
        // would leave `Load more` offering pages already on screen and quoting
        // a count for rows the list already has.
        assertEquals(152L, repository.sessionPaging.value.total)
        assertEquals(52L, repository.sessionPaging.value.remaining)
        assertTrue(repository.sessionPaging.value.canLoadMore)
        assertFalse(repository.sessionPaging.value.loading)
        // Page three, not page two again.
        repository.loadMoreSessions()
        runCurrent()
        assertEquals("100", http.requests.last().query["offset"])
    }

    @Test
    fun `a REST row that says nothing leaves those fields absent rather than zero`() {
        val silent = parseRestSession(json("""{"id":"quiet","title":"Quiet"}""") as JsonObject, nowMillis = 99)

        assertNull(silent.archived)
        assertNull(silent.pinned)
        assertNull(silent.unread)
        assertNull(silent.model)
        assertNull(silent.toolCallCount)
        assertNull(silent.inputTokens)
        assertNull(silent.outputTokens)
        assertNull(silent.actualCostUsd)
        assertNull(silent.estimatedCostUsd)
        assertNull(silent.lineageRootId)

        // A reported zero is data, not absence: cost is genuinely 0.0 on
        // subscription auth that never quotes a price, and a row really can
        // have run no tools.
        val zeroed = parseRestSession(
            json(
                """{"id":"zero","tool_call_count":0,"input_tokens":0,"output_tokens":0,""" +
                    """"actual_cost_usd":0.0,"estimated_cost_usd":0.0,"archived":false,"pinned":false,"unread":false}""",
            ) as JsonObject,
            nowMillis = 99,
        )
        assertEquals(0, zeroed.toolCallCount)
        assertEquals(0L, zeroed.inputTokens)
        assertEquals(0.0, zeroed.actualCostUsd!!, 0.0)
        assertEquals(false, zeroed.archived)
        assertEquals(false, zeroed.pinned)
        assertEquals(false, zeroed.unread)

        // And every string this contract adds is read strictly, like the ids:
        // `model` is a label shown verbatim, so a number wearing one is absent
        // rather than coerced into `"42"`.
        val numeric = parseRestSession(json("""{"id":"num","model":42}""") as JsonObject, nowMillis = 99)
        assertNull(numeric.model)
    }

    @Test
    fun `a second page layers over a live turn and a no-op page keeps reference identity`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest { request ->
            if (request.query["offset"] == "50") restPage(REST_PAGE_TWO) else restPage(REST_PAGE_ONE)
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        runCurrent()

        // Session X is running when the next page arrives.
        repository.openSession("durable-a")
        rpc.emit("session.info", "runtime-a", """{"running":true}""")
        runCurrent()
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        val liveRow = cache.session("durable-a")
        val quietRow = cache.session("durable-b")
        assertTrue(repository.sessionPaging.value.canLoadMore)
        assertEquals(52L, repository.sessionPaging.value.total)
        // 52 in scope, one 50-row window consumed.
        assertEquals(2L, repository.sessionPaging.value.remaining)

        repository.loadMoreSessions()
        runCurrent()

        // Page two asked for the window page one consumed, not the rows it kept
        // — the route back-fills pinned rows past its own limit.
        assertEquals("50", http.requests.last().query["offset"])
        // The live turn survives a page it was not on.
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertSame(liveRow, cache.session("durable-a"))
        // A row page two repeated verbatim is not rewritten.
        assertSame(quietRow, cache.session("durable-b"))
        // And the new page's rows are there.
        assertEquals("Third", cache.session("durable-c")?.title)
        assertEquals(0L, repository.sessionPaging.value.remaining)
        assertFalse(repository.sessionPaging.value.canLoadMore)
        assertFalse(repository.sessionPaging.value.loading)

        // A refresh that changes nothing changes nothing. Take the snapshot
        // after one refresh has re-read page one, because that page carries
        // the backend's own `last_active` and the live turn's resume answered
        // a fresher one — settling that is a real change, not a no-op.
        repository.refreshSessions()
        runCurrent()
        val settled = cache.state.value
        repository.refreshSessions()
        runCurrent()
        assertSame(settled, cache.state.value)
        // Still running, after all of it.
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
    }

    @Test
    fun `a 404 list route is remembered for the connection and probed again on the next one`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(rpc)
        val http = FakeGatewayRest {
            GatewayHttpResult.Rejected(404, "Hermes refused that Gateway request.")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
            http = { http },
        ) { CLOCK }
        // The connect-time bootstrap refresh is the one and only probe.
        runCurrent()
        repeat(4) { repository.refreshSessions() }
        runCurrent()

        assertEquals(1, http.requests.size)
        // Every refresh still produced a list, over the older contract. The
        // floor is the four this test asked for; the connect-time bootstrap
        // may or may not have landed by now, and this is not about that.
        assertTrue(rpc.calls.count { it.method == "session.list" } >= 4)
        // The fields that contract does not expose stay absent, so the
        // affordances that read them stay absent rather than inert.
        val row = cache.session("durable-a")!!
        assertEquals("Remote work", row.title)
        assertNull(row.archived)
        assertNull(row.pinned)
        assertNull(row.unread)
        // And there is no second page to offer on a contract without offsets.
        assertFalse(repository.sessionPaging.value.canLoadMore)
        repository.loadMoreSessions()
        assertEquals(1, http.requests.size)

        // A capability is a fact about one backend. The next one gets asked —
        // by its own bootstrap refresh or by this one, whichever lands first.
        clients.value = FakeRpc()
        runCurrent()
        repository.refreshSessions()
        assertTrue(http.requests.size > 1)
    }

    @Test
    fun `a transient list failure never demotes the connection to the older contract`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        var down = true
        val http = FakeGatewayRest {
            if (down) {
                GatewayHttpResult.Rejected(503, "The Gateway could not complete that request. Try again.")
            } else {
                restPage(REST_PAGE_RICH)
            }
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()

        // A 5xx is a condition to report, not evidence about the route: it is
        // raised, it does not silently serve the older contract, and the next
        // refresh asks the route again.
        assertTrue(runCatching { repository.refreshSessions() }.isFailure)
        assertTrue(rpc.calls.none { it.method == "session.list" })

        down = false
        repository.refreshSessions()
        assertEquals("Ship the pager", cache.session("tip-a")?.title)
    }

    @Test
    fun `a row the backend auto-archived off the page is not a tombstone`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        // Auto-archive is the one configured write on this GET path
        // (`sessions.py:99-102`): it can retire rows the app never touched, so
        // a row leaving the page is not the app being told it is gone.
        var vanished = false
        val http = FakeGatewayRest { if (vanished) restPage(REST_PAGE_WITHOUT_A) else restPage(REST_PAGE_ONE) }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        // Settle the connect-time bootstrap first: a page landing after the
        // row below is captured would be this test racing itself, not the
        // behaviour under test.
        repository.refreshSessions()
        runCurrent()
        repository.openSession("durable-a")
        rpc.emit("session.info", "runtime-a", """{"running":true}""")
        runCurrent()
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        val live = cache.session("durable-a")

        vanished = true
        repository.refreshSessions()
        runCurrent()

        // Still there, still running, still the same row. Only an explicit
        // tombstone removes a session (`SessionCache.removeSession`).
        assertSame(live, cache.session("durable-a"))
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertEquals("Second", cache.session("durable-b")?.title)
    }

    @Test
    fun `a listed continuation tip adopts the row already filed under its lineage root`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest { restPage(REST_PAGE_RICH) }
        // The conversation is already known under its compression root, with a
        // transcript and a live turn against that id — before any list runs, so
        // the connect-time bootstrap and the explicit refresh below converge on
        // the same answer instead of racing.
        cache.upsertSession(
            SessionSummary("root-a", "Older title", "", CLOCK, status = SessionStatus.Working),
        )
        cache.appendEntry("root-a", UserTurn("u1", "hello", CLOCK))
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        runCurrent()

        // One conversation, one row — under the live tip the route surfaced.
        assertNull(cache.session("root-a"))
        assertEquals("Ship the pager", cache.session("tip-a")?.title)
        // Navigation identity does not change: a screen holding the root id
        // resolves through the published alias (`ui/chat/ChatViewModel.kt:387`).
        assertEquals("tip-a", cache.state.value.rehomes["root-a"])
        // The conversation moved whole.
        assertEquals(1, cache.transcript("tip-a").size)
        assertTrue(cache.transcript("root-a").isEmpty())
        assertEquals(SessionStatus.Working, cache.session("tip-a")?.status)
    }

    @Test
    fun `renameSession with live runtime id calls session_title RPC and updates cache`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Old Title", "", CLOCK, status = SessionStatus.Working))
        repository.loadComposerControls("durable-a")
        runCurrent()

        val result = repository.renameSession("durable-a", "New Custom Title")

        assertEquals("New Custom Title", result)
        assertEquals("session.title", rpc.calls.last().method)
        assertEquals("runtime-a", rpc.calls.last().params.string("session_id"))
        assertEquals("New Custom Title", rpc.calls.last().params.string("title"))
        assertEquals("New Custom Title", cache.session("durable-a")?.title)
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
    }

    @Test
    fun `renameSession without live runtime id calls REST PATCH and updates cache`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest { request ->
            assertEquals("PATCH", request.method)
            assertEquals("api/sessions/durable-b", request.path)
            restPage("""{"ok":true,"title":"New REST Title"}""")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-b", "Old Title", "", CLOCK, remoteProfile = "work"))

        val result = repository.renameSession("durable-b", "New REST Title")

        assertEquals("New REST Title", result)
        assertEquals("New REST Title", cache.session("durable-b")?.title)
    }

    @Test
    fun `renameSession with empty string clears title via REST PATCH even when runtime bound`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        var patchCalled = false
        val http = FakeGatewayRest { request ->
            patchCalled = true
            assertEquals("PATCH", request.method)
            assertEquals("api/sessions/durable-a", request.path)
            restPage("""{"ok":true,"title":""}""")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Old Title", "", CLOCK))
        repository.loadComposerControls("durable-a")
        runCurrent()

        val result = repository.renameSession("durable-a", "   ")

        assertTrue(patchCalled)
        assertEquals("", result)
        assertEquals("", cache.session("durable-a")?.title)
    }

    /**
     * An RPC refusal is not the end of the rename. Desktop catches it and goes
     * to REST instead
     * (`apps/desktop/src/app/chat/sidebar/session-actions-menu.tsx:86-92` @
     * `3ca096de5f8183cb2e0ec23673f294d5978656a3`): the socket can simply be
     * mid-reconnect, and `PATCH /api/sessions/{id}` still renames any row that
     * already has a persisted one.
     */
    @Test
    fun `renameSession falls back to REST PATCH when the session_title RPC refuses`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val patched = mutableListOf<String>()
        val http = FakeGatewayRest { request ->
            assertEquals("PATCH", request.method)
            assertEquals("api/sessions/durable-a", request.path)
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            patched += buffer.readUtf8()
            restPage("""{"ok":true,"title":"New Title"}""")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Old Title", "", CLOCK))
        repository.loadComposerControls("durable-a")
        runCurrent()

        rpc.titleFailure = GatewayRpcError(5007, "Internal error")
        val result = repository.renameSession("durable-a", "New Title")

        // The live lane was tried first, and the same title went out over REST.
        assertEquals("New Title", rpc.call("session.title").params.string("title"))
        assertEquals(listOf("""{"title":"New Title"}"""), patched)
        assertEquals("New Title", result)
        assertEquals("New Title", cache.session("durable-a")?.title)
    }

    /**
     * And when REST cannot do it either, what the person is told is REST's
     * answer — the last thing that actually failed — rather than the refusal
     * from a lane the app silently moved off.
     */
    @Test
    fun `renameSession reports the REST failure when the RPC and REST both refuse`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        var statusCode = 400
        val http = FakeGatewayRest { GatewayHttpResult.Rejected(statusCode, "error") }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Old Title", "", CLOCK))
        repository.loadComposerControls("durable-a")
        runCurrent()

        rpc.titleFailure = GatewayRpcError(4021, "Title is required")
        try {
            repository.renameSession("durable-a", "New Title")
            org.junit.Assert.fail("expected error")
        } catch (e: GatewayRpcException) {
            assertEquals("Rename failed. Try a different title.", e.message)
        }

        statusCode = 404
        rpc.titleFailure = GatewayRpcError(4001, "Session not found")
        try {
            repository.renameSession("durable-a", "New Title")
            org.junit.Assert.fail("expected error")
        } catch (e: GatewayRpcException) {
            assertEquals("Rename failed. That session is no longer available.", e.message)
        }

        // Nothing was written on the way through, either.
        assertEquals("Old Title", cache.session("durable-a")?.title)
    }

    /**
     * A rename and a `session.list` refresh overlap all the time: the refresh is
     * on a timer and the rename is a tap. What makes the overlap safe is the
     * cache's own rule (`AGENTS.md`, "Backend-authoritative data merges, never
     * clobbers") — a listed row *layers* over what is already known, so it
     * cannot take away the live status and progress the list contract never
     * carried, and the rename's own upsert moves the title and nothing else.
     *
     * The page that lands here is the Gateway as it was *before* the rename:
     * still `First`, and silent about the running turn. It is server truth for
     * as long as it is the newest thing this client has been told, and the
     * rename — which the Gateway has since persisted — lands on top of it
     * rather than under it.
     */
    @Test
    fun `renameSession outlives a session list refresh that lands mid-rename`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest { restPage(REST_PAGE_ONE) }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        repository.refreshSessions()
        runCurrent()
        repository.loadComposerControls("durable-a")
        runCurrent()
        // A live turn on the row being renamed, and one row nothing touches.
        cache.upsertSession(
            cache.session("durable-a")!!.copy(
                status = SessionStatus.Working,
                progress = SessionProgress("thinking", "Reading the diff"),
            ),
        )
        val untouched = cache.session("durable-b")!!

        // The rename is in flight when the refresh's page arrives.
        val held = CompletableDeferred<JsonElement>()
        rpc.titleResponse = held
        val rename = async { repository.renameSession("durable-a", "New Custom Title") }
        runCurrent()
        repository.refreshSessions()
        runCurrent()

        // The page is pre-rename truth, and it carries no live turn.
        assertEquals("First", cache.session("durable-a")?.title)
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)

        held.complete(json("""{"title":"New Custom Title"}"""))
        assertEquals("New Custom Title", rename.await())

        assertEquals("New Custom Title", cache.session("durable-a")?.title)
        assertEquals(SessionStatus.Working, cache.session("durable-a")?.status)
        assertEquals(
            SessionProgress("thinking", "Reading the diff"),
            cache.session("durable-a")?.progress,
        )
        // A row neither of them mentioned is the same instance it was: a no-op
        // upsert does not recompose the list.
        assertSame(untouched, cache.session("durable-b"))
    }

    @Test
    fun `renameSession maps REST 400 and 404 to safe user-facing errors`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        var statusCode = 400
        val http = FakeGatewayRest {
            GatewayHttpResult.Rejected(statusCode, "error")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()

        try {
            repository.renameSession("durable-b", "New Title")
            org.junit.Assert.fail("expected error")
        } catch (e: GatewayRpcException) {
            assertEquals("Rename failed. Try a different title.", e.message)
        }

        statusCode = 404
        try {
            repository.renameSession("durable-b", "New Title")
            org.junit.Assert.fail("expected error")
        } catch (e: GatewayRpcException) {
            assertEquals("Rename failed. That session is no longer available.", e.message)
        }
    }

    @Test
    fun `deleteSession with live runtime id calls session_delete RPC and cleans up cache and runtime maps`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "To delete", "", CLOCK))
        repository.loadComposerControls("durable-a")
        runCurrent()

        repository.deleteSession("durable-a")

        assertEquals("session.delete", rpc.calls.last().method)
        assertEquals("runtime-a", rpc.calls.last().params.string("session_id"))
        assertNull(cache.session("durable-a"))
    }

    @Test
    fun `deleteSession refuses deletion of running session with 4023 safe error`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Running chat", "", CLOCK))
        rpc.resumeA = RESUME_RUNNING
        repository.loadComposerControls("durable-a")
        runCurrent()

        try {
            repository.deleteSession("durable-a")
            org.junit.Assert.fail("expected refusal")
        } catch (e: GatewayRpcException) {
            assertEquals("Cannot delete a running session. Stop the turn first and try again.", e.message)
        }
        assertEquals("Running chat", cache.session("durable-a")?.title)
    }

    @Test
    fun `deleteSession handles RPC 4007 not found as already deleted`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "To delete", "", CLOCK))
        repository.loadComposerControls("durable-a")
        runCurrent()

        rpc.deleteFailure = GatewayRpcError(4007, "Session not found")
        repository.deleteSession("durable-a")

        assertNull(cache.session("durable-a"))
    }

    @Test
    fun `deleteSession without live runtime id calls REST DELETE and removes session from cache`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        var deleteCalled = false
        val http = FakeGatewayRest { request ->
            deleteCalled = true
            assertEquals("DELETE", request.method)
            assertEquals("api/sessions/durable-b", request.path)
            assertEquals("work", request.query["profile"])
            restPage("""{"ok":true,"already_absent":false}""")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-b", "To delete", "", CLOCK, remoteProfile = "work"))

        repository.deleteSession("durable-b")

        assertTrue(deleteCalled)
        assertNull(cache.session("durable-b"))
    }

    @Test
    fun `deleteSession handles REST 404 as success and removes session from cache`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val http = FakeGatewayRest {
            GatewayHttpResult.Rejected(404, "not found")
        }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("durable-b", "To delete", "", CLOCK))

        repository.deleteSession("durable-b")

        assertNull(cache.session("durable-b"))
    }

    // -----------------------------------------------------------------------
    // Pin, archive and the durable unread watermark (#66). All three are the
    // same `PATCH /api/sessions/{id}` contract
    // (`hermes_cli/web_routers/sessions.py:825-832` @
    // `3ca096de5f8183cb2e0ec23673f294d5978656a3`) and the same
    // optimistic-then-honest shape Desktop uses
    // (`apps/desktop/src/store/session-unread-remote.ts:37-63`).
    // -----------------------------------------------------------------------

    @Test
    fun `setSessionPinned writes the pin with the row's own profile and paints it first`() = runTest {
        val cache = SessionCache()
        val bodies = mutableListOf<String>()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") {
                bodies += request.readBody()
                restPage("""{"ok":true,"title":"Pin me","pinned":true}""")
            } else {
                restPage(REST_EMPTY_PAGE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Pin me", "", CLOCK, remoteProfile = "work"))

        repository.setSessionPinned("durable-a", true)

        assertEquals("api/sessions/durable-a", http.requests.last().path)
        assertEquals("PATCH", http.requests.last().method)
        // The route scopes itself through the BODY, not the query
        // (`sessions.py:686,696`), and every mutation carries the profile.
        assertEquals(listOf("""{"pinned":true,"profile":"work"}"""), bodies)
        assertEquals(true, cache.session("durable-a")?.pinned)
    }

    @Test
    fun `a refused pin repaints the row it optimistically pinned`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") GatewayHttpResult.Rejected(500, "nope") else restPage(REST_EMPTY_PAGE)
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Pin me", "", CLOCK, pinned = false))

        val failure = runCatching { repository.setSessionPinned("durable-a", true) }.exceptionOrNull()
        assertTrue(failure is GatewayRpcException)

        assertEquals("Could not update pin. Check the Gateway and try again.", failure?.message)
        assertEquals(false, cache.session("durable-a")?.pinned)
    }

    /**
     * Archiving is reversible, so the flag moves and the row does not.
     * `removeSession` is the cache's one tombstone and it means *gone* — it
     * takes the rehome alias, the transcript and the project membership with it
     * (`SessionCache.kt:183-203`), none of which a rollback could restore. The
     * live list stops showing the row because `buildSessionRows` filters its
     * pool, which is where Desktop draws the same line too.
     */
    @Test
    fun `setSessionArchived files the row in place and off the live list`() = runTest {
        val cache = SessionCache()
        val bodies = mutableListOf<String>()
        var archivedDuringWrite: Boolean? = null
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") {
                bodies += request.readBody()
                archivedDuringWrite = cache.session("durable-a")?.archived
                restPage("""{"ok":true,"title":"File me","archived":true}""")
            } else {
                restPage(REST_EMPTY_PAGE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "File me", "", CLOCK))
        cache.appendEntry("durable-a", UserTurn("u-1", "hello", CLOCK))

        repository.setSessionArchived("durable-a", true)

        assertEquals(listOf("""{"archived":true}"""), bodies)
        // Painted before the write, not after it.
        assertEquals(true, archivedDuringWrite)
        assertEquals(true, cache.session("durable-a")?.archived)
        assertEquals(1, cache.transcript("durable-a").size)
        // Off the live list, on the archived one.
        assertFalse("durable-a" in liveRowIds(cache))
        assertEquals(listOf("durable-a"), archivedRowIds(cache))
    }

    @Test
    fun `a refused archive puts the row and its conversation back`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") GatewayHttpResult.Rejected(500, "nope") else restPage(REST_EMPTY_PAGE)
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "File me", "", CLOCK, status = SessionStatus.Background))
        cache.appendEntry("durable-a", UserTurn("u-1", "hello", CLOCK))

        val failure = runCatching { repository.setSessionArchived("durable-a", true) }.exceptionOrNull()
        assertTrue(failure is GatewayRpcException)

        assertEquals("Could not archive that chat. Check the Gateway and try again.", failure?.message)
        assertEquals(SessionStatus.Background, cache.session("durable-a")?.status)
        assertNull(cache.session("durable-a")?.archived)
        assertEquals(1, cache.transcript("durable-a").size)
    }

    /**
     * And it puts back everything a tombstone would have taken: the compression
     * rehome alias the open screen resolves through
     * (`ui/chat/ChatViewModel.kt:405`) and the project lane the row belongs to.
     * A rollback that restores only the summary is a silent, permanent loss.
     */
    @Test
    fun `a refused archive keeps the rehome alias and the project membership`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") GatewayHttpResult.Rejected(500, "nope") else restPage(REST_EMPTY_PAGE)
        }
        // The connection bootstrap re-reads the project tree and replaces the
        // membership map wholesale, which would restore what this test is
        // watching for. Held open, so the catalog under test is only ever the
        // one seeded here.
        val rpc = FakeRpc().apply { projectTreeResponse = CompletableDeferred() }
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
            http = { http },
        ) { CLOCK }
        runCurrent()
        cache.upsertSession(SessionSummary("root-a", "Long chat", "", CLOCK))
        cache.rehomeSession(
            "root-a",
            SessionSummary("tip-a", "Long chat", "", CLOCK, lineageRootId = "root-a"),
            listOf(UserTurn("u-1", "hello", CLOCK)),
        )
        cache.replaceProjectDetails(
            ProjectSummary("proj-1", "Pager", "/srv/pager"),
            listOf(cache.session("tip-a")!!),
        )

        runCatching { repository.setSessionArchived("tip-a", true) }

        assertEquals("tip-a", cache.state.value.rehomes["root-a"])
        assertEquals(listOf("tip-a"), cache.state.value.projects.memberships["proj-1"])
        assertEquals(1, cache.transcript("tip-a").size)
        assertNull(cache.session("tip-a")?.archived)
    }

    /**
     * The archive flag is fenced like the other two. A page issued before the
     * PATCH answers `archived:false`, and `mergeListedSession` lets a real
     * `false` overwrite — without the fence the row is repainted back into
     * recents for a whole refresh cycle.
     */
    @Test
    fun `a page that predates the archive cannot put the row back in recents`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") {
                restPage("""{"ok":true,"title":"Stale"}""")
            } else {
                restPage(REST_STALE_FLAGS_PAGE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        repository.refreshSessions()
        assertEquals(false, cache.session("durable-a")?.archived)

        repository.setSessionArchived("durable-a", true)
        repository.refreshSessions()

        assertEquals(true, cache.session("durable-a")?.archived)
    }

    @Test
    fun `restoring an archived row clears the flag in place`() = runTest {
        val cache = SessionCache()
        val bodies = mutableListOf<String>()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") {
                bodies += request.readBody()
                restPage("""{"ok":true,"title":"Back","archived":false}""")
            } else {
                restPage(REST_EMPTY_PAGE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Back", "", CLOCK, archived = true))

        repository.setSessionArchived("durable-a", false)

        assertEquals(listOf("""{"archived":false}"""), bodies)
        assertEquals(false, cache.session("durable-a")?.archived)
    }

    @Test
    fun `setSessionUnread arms the durable watermark`() = runTest {
        val cache = SessionCache()
        val bodies = mutableListOf<String>()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") {
                bodies += request.readBody()
                restPage("""{"ok":true,"title":"Mark me","unread":true}""")
            } else {
                restPage(REST_EMPTY_PAGE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("durable-a", "Mark me", "", CLOCK, unread = false))

        repository.setSessionUnread("durable-a", true)

        // `set_session_read(sid, read = not body.unread)` (`sessions.py:831-832`).
        assertEquals(listOf("""{"unread":true}"""), bodies)
        assertEquals(true, cache.session("durable-a")?.unread)
    }

    /**
     * Both unread sources move in one action, in Desktop's order
     * (`app/chat/sidebar/session-actions-menu.tsx:316-332` @ `3ca096de`): the
     * transient finished-turn dot is cleared *with* the watermark, not after
     * it, so nothing in between can repaint what was just dismissed.
     */
    @Test
    fun `marking read clears the watermark and the finished-turn dot together`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") {
                restPage("""{"ok":true,"title":"Mark me","unread":false}""")
            } else {
                restPage(REST_EMPTY_PAGE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(
            SessionSummary("durable-a", "Mark me", "", CLOCK, status = SessionStatus.Unread, unread = true),
        )

        repository.setSessionUnread("durable-a", false)

        assertEquals(false, cache.session("durable-a")?.unread)
        assertEquals(SessionStatus.Idle, cache.session("durable-a")?.status)
    }

    @Test
    fun `a refused unread write repaints both sources`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") GatewayHttpResult.Rejected(500, "nope") else restPage(REST_EMPTY_PAGE)
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(
            SessionSummary("durable-a", "Mark me", "", CLOCK, status = SessionStatus.Unread, unread = true),
        )

        val failure = runCatching { repository.setSessionUnread("durable-a", false) }.exceptionOrNull()
        assertTrue(failure is GatewayRpcException)

        // `unreadFailed` verbatim (`apps/desktop/src/i18n/en.ts:2307`).
        assertEquals("Could not update unread state", failure?.message)
        assertEquals(true, cache.session("durable-a")?.unread)
        assertEquals(SessionStatus.Unread, cache.session("durable-a")?.status)
    }

    /**
     * The failure the epic names: a list page already in flight when the PATCH
     * went answers with the OLD flag, and merging it would visibly undo the
     * write for a refresh cycle. Desktop fences both flags the same way
     * (`store/session-unread-remote.ts:28-31`, `sidebar/session-index.ts:51-56`).
     */
    @Test
    fun `a list page that predates the write cannot resurrect the dot or drop the pin`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") {
                restPage("""{"ok":true,"title":"Stale"}""")
            } else {
                restPage(REST_STALE_FLAGS_PAGE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        repository.refreshSessions()
        assertEquals(true, cache.session("durable-a")?.unread)

        repository.setSessionUnread("durable-a", false)
        repository.setSessionPinned("durable-a", true)
        repository.refreshSessions()

        assertEquals(false, cache.session("durable-a")?.unread)
        assertEquals(true, cache.session("durable-a")?.pinned)
    }

    /**
     * And the fence retires itself. A page that agrees drops it, so the very
     * next disagreeing page is believed — the guard protects one refresh cycle,
     * not this client's opinion forever.
     */
    @Test
    fun `the fence retires the moment a page agrees with it`() = runTest {
        val cache = SessionCache()
        var page = REST_STALE_FLAGS_PAGE
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") restPage("""{"ok":true,"title":"Stale"}""") else restPage(page)
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        repository.refreshSessions()

        repository.setSessionUnread("durable-a", false)
        page = REST_READ_FLAGS_PAGE
        repository.refreshSessions()
        assertEquals(false, cache.session("durable-a")?.unread)

        // The Gateway is authoritative again: a genuine unread from another
        // client now lands.
        page = REST_STALE_FLAGS_PAGE
        repository.refreshSessions()
        assertEquals(true, cache.session("durable-a")?.unread)
    }

    /**
     * A pin fenced under the id the row is filed by is also fenced under the
     * compression lineage root, because the next page can surface the same
     * conversation under a *different* id — the REST list projects a chain
     * forward to its latest continuation and reports the root separately
     * (`hermes_state.py:11596-11603` @ `3ca096de`). A fence that only knew the
     * id it was written against would lose the pin on the first compression.
     */
    @Test
    fun `a fenced pin survives the row arriving under its compression tip`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") restPage("""{"ok":true,"title":"Tip"}""") else restPage(REST_TIP_ID_PAGE)
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("root-a", "Tip", "", CLOCK, pinned = false))

        repository.setSessionPinned("root-a", true)

        // The page names the conversation by its new tip, says the root it came
        // from, and carries the pre-write value.
        repository.refreshSessions()

        assertNull(cache.session("root-a"))
        // One row for the conversation, not one under each id it has worn.
        assertEquals(1, cache.state.value.sessions.values.count { it.title == "Tip" })
        assertEquals(true, cache.session("tip-a")?.pinned)
    }

    /**
     * The guard is not decoration. Past ten seconds the fence retires itself
     * and the Gateway is authoritative again, even though no page ever agreed
     * (`FLAG_WRITE_GUARD_MILLIS`, Desktop's `UNREAD_WRITE_GUARD_MS`
     * `store/session-unread-remote.ts:28` @ `3ca096de`).
     */
    @Test
    fun `a fence past the guard lets the server win`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") restPage("""{"ok":true,"title":"Stale"}""") else restPage(REST_STALE_FLAGS_PAGE)
        }
        var now = CLOCK
        val repository = liveRepository(cache, http) { now }
        runCurrent()
        repository.refreshSessions()

        repository.setSessionUnread("durable-a", false)
        // Inside the window the write still outranks the stale page.
        repository.refreshSessions()
        assertEquals(false, cache.session("durable-a")?.unread)

        now += 10_000L
        repository.refreshSessions()

        assertEquals(true, cache.session("durable-a")?.unread)
    }

    /**
     * And a write the Gateway acknowledged but has never echoed does not sit in
     * the backend-authoritative cache forever as this client's opinion: the
     * fence arms one rescan for when its guard lapses.
     */
    @Test
    fun `an unconfirmed write asks the Gateway again when its guard expires`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.method == "PATCH") restPage("""{"ok":true,"title":"Stale"}""") else restPage(REST_STALE_FLAGS_PAGE)
        }
        val repository = liveRepository(cache, http) { CLOCK + testScheduler.currentTime }
        runCurrent()
        repository.refreshSessions()
        val beforeWrite = http.requests.size

        repository.setSessionUnread("durable-a", false)
        advanceTimeBy(10_001L)
        runCurrent()
        advanceUntilIdle()

        // The PATCH, and at least one list read nobody asked for: the guard
        // lapsed on a write no page had confirmed, so the repository went back
        // to the Gateway rather than leaving its own value in the cache. What
        // the answer then says is the other test's claim (`a fence past the
        // guard lets the server win`).
        assertTrue(http.requests.size >= beforeWrite + 2)
    }

    /**
     * A pin is a property of the conversation, not of the id it currently
     * lives under: a compression re-home carries it whole.
     */
    @Test
    fun `a pin survives an auto-compression re-home`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { restPage(REST_EMPTY_PAGE) }
        val repository = liveRepository(cache, http)
        runCurrent()
        cache.upsertSession(SessionSummary("root-a", "Long chat", "", CLOCK, pinned = true))

        val row = cache.session("root-a")!!
        cache.rehomeSession("root-a", row.copy(id = "tip-a", lineageRootId = "root-a"), emptyList())

        assertNull(cache.session("root-a"))
        assertEquals(true, cache.session("tip-a")?.pinned)
        assertEquals(
            listOf("tip-a"),
            buildSessionRows(cache.state.value.sessions.values, CLOCK)
                .filterIsInstance<SessionListRow.Row>()
                .map { it.session.id },
        )
        assertTrue(buildSessionRows(cache.state.value.sessions.values, CLOCK).first() is SessionListRow.PinnedLabel)
    }

    /**
     * The Archived view is its own pool, read with its own request: Desktop's
     * `listAllProfileSessions(ARCHIVED_FETCH_LIMIT, 0, 'only')`
     * (`store/sidebar-archive.ts:7-30` @ `3ca096de` — "Archived rows are
     * excluded from the sessions query, so the Archived view has to fetch its
     * own set. Capped: it's a lookup surface, not a feed."). The live list
     * keeps asking `exclude`, which is where its pager's offsets live.
     */
    @Test
    fun `the Archived view reads its own archived-only pool`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { restPage(REST_EMPTY_PAGE) }
        val repository = liveRepository(cache, http)
        runCurrent()
        repository.refreshSessions()
        assertEquals("exclude", http.requests.last().query["archived"])
        assertEquals("50", http.requests.last().query["limit"])

        repository.loadArchivedSessions()

        assertEquals("GET", http.requests.last().method)
        assertEquals("only", http.requests.last().query["archived"])
        assertEquals("0", http.requests.last().query["offset"])
        // Desktop asks for 200 through a route that caps at 500; this app reads
        // one profile leg through `/api/sessions`, which caps at 100
        // (`hermes_cli/web_routers/sessions.py:91-94` @ `3ca096de`).
        assertEquals("100", http.requests.last().query["limit"])

        // And the pool it reads never becomes the live list's page: a later
        // refresh is `exclude` again, from offset zero.
        repository.refreshSessions()
        assertEquals("exclude", http.requests.last().query["archived"])
        assertEquals("0", http.requests.last().query["offset"])
    }

    /**
     * The failure the `include` shape had: one 50-row window of the combined
     * set, ordered by recency, shows nothing archived to anyone whose newest
     * page is all live conversations — and `Nothing archived` is then a
     * statement about the account that is simply false.
     */
    @Test
    fun `an archived row outside the live page still reaches the Archived view`() = runTest {
        val cache = SessionCache()
        val http = FakeGatewayRest { request ->
            if (request.query["archived"] == "only") {
                restPage(REST_ARCHIVED_ONLY_PAGE)
            } else {
                restPage(REST_PAGE_ONE)
            }
        }
        val repository = liveRepository(cache, http)
        runCurrent()
        repository.refreshSessions()
        // The live page is full of live rows and never mentions the archived one.
        assertNull(cache.session("durable-z"))

        repository.loadArchivedSessions()

        assertEquals(listOf("durable-z"), archivedRowIds(cache))
        // And the live list is untouched by the lookup.
        assertTrue(liveRowIds(cache).containsAll(listOf("durable-a", "durable-b")))
        assertFalse("durable-z" in liveRowIds(cache))
    }

    private fun liveRowIds(cache: SessionCache) =
        buildSessionRows(cache.state.value.sessions.values, CLOCK)
            .filterIsInstance<SessionListRow.Row>()
            .map { it.session.id }

    private fun archivedRowIds(cache: SessionCache) =
        buildSessionRows(cache.state.value.sessions.values, CLOCK, archivedView = true)
            .filterIsInstance<SessionListRow.Row>()
            .map { it.session.id }

    /**
     * The default clock is the fixed [CLOCK]; a test about the write fence's
     * ten-second guard passes one that actually moves, because a constant clock
     * makes `clock() - write.atMillis` zero forever and the expiry branch
     * unreachable.
     */
    private fun TestScope.liveRepository(
        cache: SessionCache,
        http: GatewayHttp,
        clock: () -> Long = { CLOCK },
    ) = LiveGatewaySessionRepository(
        cache,
        MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
        MutableStateFlow<GatewayRpcClient?>(FakeRpc()),
        backgroundScope,
        http = { http },
        clock = clock,
    )

    private fun GatewayHttpRequest.readBody(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    /**
     * A [GatewayHttp] that answers the session-list route from a script and
     * remembers every attempt.
     *
     * Deliberately not [RecordingGatewayHttp]: these tests are about how many
     * times a route is asked across many refreshes, so the script is a function
     * of the request rather than a queue that runs out. Each answer builds a
     * fresh buffer, because the client wipes the one it decodes.
     */
    private class FakeGatewayRest(
        private val answer: (GatewayHttpRequest) -> GatewayHttpResult,
    ) : GatewayHttp {
        val requests = mutableListOf<GatewayHttpRequest>()

        override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
            requests.add(request)
            return answer(request)
        }
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
        var redirectResult = """{"status":"redirected"}"""
        var redirectFailure: Throwable? = null
        var steerResult = """{"status":"queued"}"""
        var steerFailure: Throwable? = null
        var interruptResult = """{"status":"interrupted"}"""
        var interruptFailure: Throwable? = null
        var interruptResponse: CompletableDeferred<JsonElement>? = null
        var processListResult = """{"processes":[]}"""
        var processListFailure: Throwable? = null
        var processKillResult = "{}"
        var processKillFailure: Throwable? = null
        var goalStatusResult = """{"output":"No active goal"}"""
        var goalStatusFailure: Throwable? = null
        var modelOptionsResult = MODEL_OPTIONS
        var modelOptionsResponse: CompletableDeferred<JsonElement>? = null
        var providerResult = """{"model":"reasoner-v3","provider":"acme"}"""
        var reasoningResult = """{"value":"high"}"""
        var fastResult = """{"value":"fast"}"""
        var configSetResult = "{}"
        var configSetFailure: Throwable? = null
        var slashResult = """{"items":[]}"""
        var pathResult = """{"items":[]}"""
        var attachFailure: Throwable? = null
        var detachFailure: Throwable? = null
        var failImageAttachAt: Int? = null
        var imageAttachCount = 0
        var imageAttachResponse: CompletableDeferred<JsonElement>? = null
        val detachResults = ArrayDeque<Boolean>()
        var eventOverflowed = false
        var titleFailure: Throwable? = null
        var titleResponse: CompletableDeferred<JsonElement>? = null
        var deleteFailure: Throwable? = null

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
                "model.options" -> modelOptionsResponse?.await() ?: json(modelOptionsResult)
                "config.get" -> when (params.string("key")) {
                    "provider" -> json(providerResult)
                    "reasoning" -> json(reasoningResult)
                    "fast" -> json(fastResult)
                    else -> error("unexpected config key")
                }
                "config.set" -> {
                    configSetFailure?.let { failure ->
                        configSetFailure = null
                        throw failure
                    }
                    json(configSetResult)
                }
                "complete.slash" -> json(slashResult)
                "complete.path" -> json(pathResult)
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
                "session.redirect" -> {
                    redirectFailure?.let { failure ->
                        redirectFailure = null
                        throw failure
                    }
                    json(redirectResult)
                }
                "session.steer" -> {
                    steerFailure?.let { failure ->
                        steerFailure = null
                        throw failure
                    }
                    json(steerResult)
                }
                "session.activate" -> json(activateResult)
                "session.interrupt" -> {
                    interruptFailure?.let { failure ->
                        interruptFailure = null
                        throw failure
                    }
                    interruptResponse?.await() ?: json(interruptResult)
                }
                "process.list" -> {
                    processListFailure?.let { throw it }
                    json(processListResult)
                }
                "process.kill" -> {
                    processKillFailure?.let { failure ->
                        processKillFailure = null
                        throw failure
                    }
                    json(processKillResult)
                }
                "slash.exec" -> {
                    goalStatusFailure?.let { throw it }
                    json(goalStatusResult)
                }
                "image.attach_bytes" -> {
                    imageAttachCount += 1
                    if (failImageAttachAt == imageAttachCount) throw GatewayRpcException("attachment rejected")
                    attachFailure?.let { failure ->
                        attachFailure = null
                        throw failure
                    }
                    imageAttachResponse?.await()
                        ?: json("""{"attached":true,"path":"/gw/img.png","text":"[User attached image: img.png]"}""")
                }
                "image.detach" -> {
                    detachFailure?.let { failure ->
                        detachFailure = null
                        throw failure
                    }
                    val detached = detachResults.removeFirstOrNull() ?: true
                    json("""{"detached":$detached,"count":0}""")
                }
                "file.attach" -> {
                    attachFailure?.let { failure ->
                        attachFailure = null
                        throw failure
                    }
                    json("""{"attached":true,"name":"notes.txt","path":"/gw/notes.txt","ref_text":"@file:`notes.txt`","uploaded":true}""")
                }
                "session.title" -> {
                    titleFailure?.let { failure ->
                        titleFailure = null
                        throw failure
                    }
                    val t = params.string("title") ?: "Renamed title"
                    titleResponse?.await() ?: json("""{"title":"$t"}""")
                }
                "session.delete" -> {
                    deleteFailure?.let { failure ->
                        deleteFailure = null
                        throw failure
                    }
                    json("{}")
                }
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
        /**
         * `{"sessions": [...], "total": N, "limit": L, "offset": O}` with one
         * fully populated row (`sessions.py:159`; the row's own keys at
         * `hermes_state_portability.py:33-43` plus the route's stamps at
         * `sessions.py:145-156` and the derived `unread` at
         * `hermes_state.py:9400-9401`). `_lineage_root_id` marks this as a
         * compression tip projected forward from `root-a`
         * (`hermes_state.py:9383-9392`).
         */
        const val REST_PAGE_RICH = """{"sessions":[
            {"id":"tip-a","_lineage_root_id":"root-a","parent_session_id":null,"title":"Ship the pager",
             "preview":"latest","started_at":1700000123.456,"last_active":1700000900.5,"ended_at":null,
             "source":"desktop","message_count":31,"tool_call_count":9,"input_tokens":12000,"output_tokens":3400,
             "actual_cost_usd":0.42,"estimated_cost_usd":0.55,"model":"anthropic/claude-sonnet-4",
             "cwd":"/srv/worktrees/session-list","git_branch":"feat/session-list-rest",
             "git_repo_root":"/srv/worktrees/session-list","archived":false,"pinned":true,"unread":true,
             "is_active":false,"profile":"work","is_default_profile":false}
        ],"total":1,"limit":50,"offset":0}"""
        const val REST_EMPTY_PAGE = """{"sessions":[],"total":0,"limit":50,"offset":0}"""

        /** One row whose flags predate this client's own write. */
        const val REST_STALE_FLAGS_PAGE = """{"sessions":[
            {"id":"durable-a","title":"Stale","preview":"","started_at":1700000100,"last_active":1700000100,
             "message_count":1,"source":"desktop","archived":false,"pinned":false,"unread":true,
             "profile":"default","is_default_profile":true}
        ],"total":1,"limit":50,"offset":0}"""

        /** The same row once the Gateway agrees the conversation was read. */
        const val REST_READ_FLAGS_PAGE = """{"sessions":[
            {"id":"durable-a","title":"Stale","preview":"","started_at":1700000100,"last_active":1700000100,
             "message_count":1,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true}
        ],"total":1,"limit":50,"offset":0}"""

        /**
         * The same conversation projected forward onto a fresh compression tip,
         * in the shape the REST list actually emits: the tip's id, with the
         * root reported separately (`hermes_state.py:11596-11603` @ `3ca096de`).
         */
        const val REST_TIP_ID_PAGE = """{"sessions":[
            {"id":"tip-a","_lineage_root_id":"root-a","title":"Tip","preview":"",
             "started_at":1700000100,"last_active":1700000100,
             "message_count":1,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true}
        ],"total":1,"limit":50,"offset":0}"""

        /** What `archived=only` answers: the archived set and nothing else. */
        const val REST_ARCHIVED_ONLY_PAGE = """{"sessions":[
            {"id":"durable-z","title":"Filed","preview":"","started_at":1699000000,"last_active":1699000000,
             "message_count":4,"source":"desktop","archived":true,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true}
        ],"total":1,"limit":100,"offset":0}"""

        const val REST_PAGE_ONE = """{"sessions":[
            {"id":"durable-a","title":"First","preview":"a","started_at":1700000100,"last_active":1700000100,
             "message_count":7,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true},
            {"id":"durable-b","title":"Second","preview":"b","started_at":1700000200,"last_active":1700000200,
             "message_count":3,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true}
        ],"total":52,"limit":50,"offset":0}"""
        /** Page two repeats `durable-b` byte-identically and adds one row. */
        const val REST_PAGE_TWO = """{"sessions":[
            {"id":"durable-b","title":"Second","preview":"b","started_at":1700000200,"last_active":1700000200,
             "message_count":3,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true},
            {"id":"durable-c","title":"Third","preview":"c","started_at":1700000300,"last_active":1700000300,
             "message_count":1,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true}
        ],"total":52,"limit":50,"offset":50}"""
        /**
         * What an **unscoped** request gets back from a Gateway whose process
         * was launched under a named profile: every row stamped with that name,
         * because the route falls back to its own active profile when the
         * request named none (`sessions.py:146,152` →
         * `web_server.py:12461-12478`). The name is arbitrary — what matters is
         * that it is neither absent nor `"default"`.
         */
        const val REST_PAGE_LAUNCH_PROFILE = """{"sessions":[
            {"id":"launch-row","title":"Under a named profile","preview":"a","started_at":1700000100,
             "last_active":1700000100,"message_count":7,"source":"desktop","archived":false,"pinned":false,
             "unread":false,"profile":"kani-backend","is_default_profile":false}
        ],"total":1,"limit":50,"offset":0}"""

        /** A row whose pin, unread and archive state only the REST contract can report. */
        const val REST_PAGE_PINNED = """{"sessions":[
            {"id":"durable-a","title":"First","preview":"a","started_at":1700000100,"last_active":1700000100,
             "message_count":7,"source":"desktop","archived":false,"pinned":true,"unread":true,
             "profile":"default","is_default_profile":true}
        ],"total":1,"limit":50,"offset":0}"""

        /**
         * A scope deep enough that two pages do not reach the end: 152 rows, so
         * a reader two windows in still has 52 to go.
         */
        const val REST_DEEP_PAGE_ONE = """{"sessions":[
            {"id":"durable-a","title":"First","preview":"a","started_at":1700000100,"last_active":1700000100,
             "message_count":7,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"work","is_default_profile":false},
            {"id":"durable-b","title":"Second","preview":"b","started_at":1700000200,"last_active":1700000200,
             "message_count":3,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"work","is_default_profile":false}
        ],"total":152,"limit":50,"offset":0}"""
        const val REST_DEEP_PAGE_TWO = """{"sessions":[
            {"id":"durable-c","title":"Third","preview":"c","started_at":1700000300,"last_active":1700000300,
             "message_count":1,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"work","is_default_profile":false}
        ],"total":152,"limit":50,"offset":50}"""

        /** What the backend answers after auto-archive retires `durable-a`. */
        const val REST_PAGE_WITHOUT_A = """{"sessions":[
            {"id":"durable-b","title":"Second","preview":"b","started_at":1700000200,"last_active":1700000200,
             "message_count":3,"source":"desktop","archived":false,"pinned":false,"unread":false,
             "profile":"default","is_default_profile":true}
        ],"total":1,"limit":50,"offset":0}"""

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
        const val MODEL_OPTIONS = """{"model":"reasoner-v3","provider":"acme","providers":[{"slug":"acme","name":"Acme","models":["reasoner-v3"],"capabilities":{"reasoner-v3":{"reasoning":true,"fast":true}}}]}"""
        const val CREATE_WITH_CONFLICTING_INFO_ID = """{"session_id":"runtime-created","stored_session_id":"durable-created","message_count":0,"messages":[],"info":{"id":"conflicting-info-id","model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true}}"""
        const val SESSION_LIST_RENAMED = """{"sessions":[
            {"id":"durable-a","title":"Renamed A","preview":"new a","started_at":1700000124,"message_count":8,"source":"desktop"},
            {"id":"durable-b","title":"Renamed B","preview":"new b","started_at":1700000457,"message_count":1,"source":"desktop"}
        ]}"""
        const val HISTORY = """{"messages":[
            {"row_id":101,"role":"user","text":"hello","timestamp":1700000000},
            {"row_id":102,"role":"assistant","text":"hi"},
            {"role":"tool","name":"Read","context":"Read file.txt","args":{"path":"file.txt"}}
        ],"count":3}"""
        const val HISTORY_WITHOUT_ROW_IDS = """{"messages":[
            {"role":"user","text":"hello","timestamp":1700000000},
            {"role":"assistant","text":"hi"},
            {"role":"tool","name":"Read","context":"Read file.txt","args":{"path":"file.txt"}}
        ],"count":3}"""
        const val ACTIVATE_IDLE = """{"session_id":"runtime-a","session_key":"durable-a","message_count":3,"messages":[],"inflight":null,"running":false,"started_at":1700001000.125,"status":"idle"}"""
        const val AUTHORITATIVE_COMPLETED_HISTORY = """{"messages":[
            {"row_id":201,"role":"user","text":"ship it","timestamp":1700001001},
            {"row_id":202,"role":"assistant","text":"done","timestamp":1700001002},
            {"role":"tool","name":"read_file","context":"README.md"}
        ],"count":3}"""
    }
}

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** A fresh buffer per answer: the REST client wipes the one it decodes. */
private fun restPage(body: String): GatewayHttpResult =
    GatewayHttpResult.Success(200, body.toByteArray(Charsets.UTF_8))
