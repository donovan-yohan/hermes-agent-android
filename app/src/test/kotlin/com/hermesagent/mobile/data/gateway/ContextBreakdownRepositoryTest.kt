package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.ContextBreakdown
import com.hermesagent.mobile.data.session.ContextUsageCategory
import com.hermesagent.mobile.data.session.SessionCache
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.SessionUsage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextBreakdownRepositoryTest {

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `parseSessionUsage parses all fields and sanitizes negative values`() {
        val payload = json(
            """{
                "context_used": 128200,
                "context_max": 272000,
                "context_percent": 47.1,
                "total": 350000,
                "input": 200000,
                "output": 150000,
                "calls": 12,
                "model": "claude-3-5-sonnet"
            }"""
        )

        val usage = parseSessionUsage(payload)
        assertEquals(128200L, usage.contextUsed)
        assertEquals(272000L, usage.contextMax)
        assertEquals(47, usage.contextPercent)
        assertEquals(350000L, usage.total)
        assertEquals(200000L, usage.input)
        assertEquals(150000L, usage.output)
        assertEquals(12, usage.calls)
        assertEquals("claude-3-5-sonnet", usage.model)
    }

    @Test
    fun `parseSessionUsage merges with previous usage preserving context_max`() {
        val initial = SessionUsage(
            contextUsed = 100000L,
            contextMax = 200000L,
            contextPercent = 50,
            total = 100000L,
            model = "gpt-4",
        )

        val update = json(
            """{
                "context_used": 120000,
                "context_percent": 60,
                "total": 120000
            }"""
        )

        val merged = parseSessionUsage(update, initial)
        assertEquals(120000L, merged.contextUsed)
        assertEquals(200000L, merged.contextMax)
        assertEquals(60, merged.contextPercent)
        assertEquals(120000L, merged.total)
        assertEquals("gpt-4", merged.model)
    }

    @Test
    fun `parseContextBreakdown parses categories with caps and sanitization`() {
        val payload = json(
            """{
                "categories": [
                    {"id": "system_prompt", "label": "System prompt", "tokens": 4200, "color": "#26c6da"},
                    {"id": "skills", "label": "Skills", "tokens": 8500, "color": "#ab47bc"},
                    {"id": "conversation", "label": "Conversation", "tokens": 25000, "color": "#42a5f5"},
                    {"id": "invalid_tokens", "label": "Bad", "tokens": -100, "color": "var(--context-usage-bad)"}
                ],
                "context_max": 200000,
                "context_percent": 19,
                "context_used": 37700,
                "estimated_total": 45000,
                "model": "claude-3-7-sonnet"
            }"""
        )

        val breakdown = parseContextBreakdown(payload)
        assertNotNull(breakdown)
        assertEquals(4, breakdown!!.categories.size)
        assertEquals("system_prompt", breakdown.categories[0].id)
        assertEquals("System prompt", breakdown.categories[0].label)
        assertEquals(4200L, breakdown.categories[0].tokens)
        assertEquals("#26c6da", breakdown.categories[0].color)

        assertEquals("invalid_tokens", breakdown.categories[3].id)
        assertEquals(0L, breakdown.categories[3].tokens)
        assertEquals("var(--context-usage-bad)", breakdown.categories[3].color)

        assertEquals(200000L, breakdown.contextMax)
        assertEquals(19, breakdown.contextPercent)
        assertEquals(37700L, breakdown.contextUsed)
        assertEquals(45000L, breakdown.estimatedTotal)
        assertEquals("claude-3-7-sonnet", breakdown.model)
    }

    @Test
    fun `loadContextBreakdown passes profile routing and caches per session`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { 1_000L }
        runCurrent()

        rpc.contextBreakdownResult = """{
            "categories": [
                {"id": "system_prompt", "label": "System", "tokens": 5000, "color": "#26c6da"}
            ],
            "context_max": 200000,
            "context_percent": 25,
            "context_used": 50000,
            "estimated_total": 60000,
            "model": "hermes-3"
        }"""

        repository.setProfileRouting(ProfileRouting(activeProfile = "research"))
        // The read never opens a session itself, so navigation has to have
        // bound the runtime first — Desktop only ever passes an active one
        // (`use-context-breakdown.ts:41` @ `3ca096de`).
        repository.openSession("session-1")
        runCurrent()

        val breakdown = repository.loadContextBreakdown("session-1")
        assertNotNull(breakdown)
        assertEquals(50000L, breakdown!!.contextUsed)
        assertEquals(200000L, breakdown.contextMax)
        assertEquals(25, breakdown.contextPercent)

        val lastCall = rpc.lastCall("session.context_breakdown")
        assertNotNull(lastCall)
        assertEquals("runtime-1", lastCall!!.params.string("session_id"))
        assertEquals("research", lastCall.params.string("profile"))
    }

    @Test
    fun `applyEvent merges session_info and session_usage into cache`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { 1_000L }
        runCurrent()

        cache.upsertSession(
            SessionSummary(
                id = "session-1",
                title = "Test Session",
                preview = "",
                lastActiveAtMillis = 1000L,
            )
        )

        repository.openSession("session-1")
        runCurrent()

        // Streamed session.info with usage
        rpc.emit(
            "session.info",
            "runtime-1",
            """{
                "stored_session_id": "session-1",
                "running": true,
                "usage": {
                    "context_used": 45000,
                    "context_max": 200000,
                    "context_percent": 22.5,
                    "total": 50000
                }
            }"""
        )
        runCurrent()

        var session = cache.session("session-1")
        val usage1 = session?.usage
        assertNotNull(usage1)
        assertEquals(45000L, usage1?.contextUsed)
        assertEquals(200000L, usage1?.contextMax)
        assertEquals(23, usage1?.contextPercent)

        // Streamed session.usage delta
        rpc.emit(
            "session.usage",
            "runtime-1",
            """{
                "stored_session_id": "session-1",
                "usage": {
                    "context_used": 60000,
                    "context_percent": 30,
                    "total": 65000
                }
            }"""
        )
        runCurrent()

        session = cache.session("session-1")
        val usage2 = session?.usage
        assertNotNull(usage2)
        assertEquals(60000L, usage2?.contextUsed)
        assertEquals(200000L, usage2?.contextMax) // Preserved
        assertEquals(30, usage2?.contextPercent)
        assertEquals(65000L, usage2?.total)
    }


    @Test
    fun `parseContextBreakdown caps the category list at sixteen and clamps negatives`() {
        val many = (1..20).joinToString(",") { index ->
            """{"id":"cat-$index","label":"Category $index","tokens":$index,"color":"var(--context-usage-mcp)"}"""
        }
        val payload = json(
            """{
                "categories": [$many],
                "context_max": -5,
                "context_percent": -12,
                "context_used": -7,
                "estimated_total": -9
            }"""
        )

        val breakdown = parseContextBreakdown(payload)
        assertNotNull(breakdown)
        assertEquals(16, breakdown!!.categories.size)
        assertEquals("cat-16", breakdown.categories.last().id)
        assertEquals(0L, breakdown.contextMax)
        assertEquals(0, breakdown.contextPercent)
        assertEquals(0L, breakdown.contextUsed)
        assertEquals(0L, breakdown.estimatedTotal)
    }

    @Test
    fun `parseContextBreakdown redacts and caps a gateway supplied label`() {
        val payload = json(
            """{
                "categories": [
                    {"id": "vendor_plugin", "label": "Plugin password=redact-me", "tokens": 10, "color": "#26c6da"},
                    {"id": "long_label", "label": "${"L".repeat(80)}", "tokens": 10, "color": "#26c6da"}
                ]
            }"""
        )

        val breakdown = parseContextBreakdown(payload)
        assertNotNull(breakdown)
        assertEquals("Plugin password=<redacted>", breakdown!!.categories[0].label)
        assertEquals(40, breakdown.categories[1].label.length)
    }

    @Test
    fun `parseContextBreakdown fills missing keys rather than failing`() {
        val breakdown = parseContextBreakdown(json("""{"categories": [{"tokens": 3}]}"""))
        assertNotNull(breakdown)
        assertEquals(1, breakdown!!.categories.size)
        assertEquals("", breakdown.categories[0].id)
        assertEquals("", breakdown.categories[0].label)
        assertEquals("var(--ui-text-tertiary)", breakdown.categories[0].color)
        assertEquals(0L, breakdown.contextMax)
        assertEquals(0L, breakdown.contextUsed)
        assertEquals("", breakdown.model)
    }

    @Test
    fun `loadContextBreakdown never opens a session that has no runtime`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { 1_000L }
        runCurrent()

        assertNull(repository.loadContextBreakdown("session-1"))
        // Neither the read itself nor the `session.resume` that opening one
        // would have issued: this call must never take the navigation mutex.
        assertEquals(0, rpc.callCount("session.context_breakdown"))
        assertEquals(0, rpc.callCount("session.resume"))
        assertEquals(false, repository.hasLiveRuntime("session-1"))
    }

    @Test
    fun `a failed breakdown rpc keeps the last one this session had`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { 1_000L }
        runCurrent()

        rpc.contextBreakdownResult = """{"categories":[],"context_max":200000,"context_percent":25,"context_used":50000}"""
        repository.openSession("session-1")
        runCurrent()

        val first = repository.loadContextBreakdown("session-1")
        assertNotNull(first)

        rpc.contextBreakdownFails = true
        val second = repository.loadContextBreakdown("session-1")
        assertEquals(first, second)
    }

    @Test
    fun `an endpoint switch empties the per-session breakdown cache`() = runTest {
        val cache = SessionCache()
        val first = FakeRpc()
        val clients = MutableStateFlow<GatewayRpcClient?>(first)
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            clients,
            backgroundScope,
        ) { 1_000L }
        runCurrent()

        first.contextBreakdownResult = """{"categories":[],"context_max":200000,"context_percent":25,"context_used":50000}"""
        repository.openSession("session-1")
        runCurrent()
        assertNotNull(repository.loadContextBreakdown("session-1"))

        // The next backend is a different machine that can recycle the same
        // durable id, so nothing read from the previous one may survive.
        val second = FakeRpc()
        second.contextBreakdownFails = true
        clients.value = second
        runCurrent()
        repository.openSession("session-1")
        runCurrent()

        assertNull(repository.loadContextBreakdown("session-1"))
    }

    @Test
    fun `message_complete carries the authoritative end of turn usage`() = runTest {
        val cache = SessionCache()
        val rpc = FakeRpc()
        val repository = LiveGatewaySessionRepository(
            cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc),
            backgroundScope,
        ) { 1_000L }
        runCurrent()

        cache.upsertSession(
            SessionSummary(
                id = "session-1",
                title = "Test Session",
                preview = "",
                lastActiveAtMillis = 1000L,
            )
        )
        repository.openSession("session-1")
        runCurrent()

        rpc.emit(
            "session.usage",
            "runtime-1",
            """{"stored_session_id":"session-1","usage":{"context_used":10000,"context_max":200000,"context_percent":5,"total":11000}}""",
        )
        runCurrent()
        assertEquals(10000L, cache.session("session-1")?.usage?.contextUsed)

        // `_start_usage_ticker` is stopped and joined before this event
        // (`tui_gateway/server.py:12820-12822` @ 3ca096de), so the figure it
        // carries (`:13431`) is the one the turn ended on.
        rpc.emit(
            "message.complete",
            "runtime-1",
            """{"stored_session_id":"session-1","text":"done","status":"complete","usage":{"context_used":42000,"context_percent":21,"total":45000}}""",
        )
        runCurrent()

        val usage = cache.session("session-1")?.usage
        assertNotNull(usage)
        assertEquals(42000L, usage?.contextUsed)
        assertEquals(21, usage?.contextPercent)
        assertEquals(45000L, usage?.total)
        // Absent keys keep the last value, exactly as Desktop's spread does.
        assertEquals(200000L, usage?.contextMax)
    }

    private class FakeRpc : GatewayRpcClient {
        private val eventChannel = Channel<GatewayEvent>(capacity = 1024)
        override val events: Flow<GatewayEvent> = eventChannel.receiveAsFlow()

        fun emit(type: String, runtimeId: String?, payload: String) {
            eventChannel.trySend(GatewayEvent(type, runtimeId, json(payload)))
        }

        private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

        var contextBreakdownResult: String = "{}"
        var contextBreakdownFails: Boolean = false
        private val calls = mutableListOf<RpcCall>()

        data class RpcCall(val method: String, val params: JsonObject)

        fun lastCall(method: String): RpcCall? = calls.lastOrNull { it.method == method }

        fun callCount(method: String): Int = calls.count { it.method == method }

        override suspend fun request(method: String, params: JsonObject): JsonElement {
            calls += RpcCall(method, params)
            return when (method) {
                "session.context_breakdown" -> {
                    if (contextBreakdownFails) throw GatewayRpcException("Hermes could not answer.")
                    Json.parseToJsonElement(contextBreakdownResult)
                }
                "session.resume" -> Json.parseToJsonElement(
                    """{"session_id":"runtime-1","resumed":"session-1","message_count":0,"messages":[],"info":{"model":"test/model","tools":{},"skills":{},"cwd":"/workspace","lazy":true},"inflight":null,"running":false,"session_key":"session-1","started_at":1700001000.125,"status":"idle"}"""
                )
                "session.history" -> Json.parseToJsonElement("""{"messages":[],"count":0}""")
                else -> Json.parseToJsonElement("{}")
            }
        }

        override fun close() {}
    }
}
