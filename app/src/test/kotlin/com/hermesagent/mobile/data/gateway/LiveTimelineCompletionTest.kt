package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Public synthetic variants of gateway/session_automation.py:94-106 and
 * session_authority.py:851-853 @ 046094df28b712f62a5e3c728a9e59da05dcbf1e.
 * Actual builder emits role-less display_kind/display_text/text; row/stamp fields below
 * are explicitly synthetic. Existing timeline history semantics remain authoritative. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTimelineCompletionTest {
    @Test fun `role-less typed completions remain timeline rows before real assistant stream`() = runTest {
        var history: JsonArray = JsonArray(emptyList())
        lateinit var rpc: CorrelatedGatewayRpc
        rpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val call = Json.parseToJsonElement(text).jsonObject
                val result = when (call.getValue("method").jsonPrimitive.content) {
                    "profiles.list" -> Json.parseToJsonElement("""{"profiles":[{"name":"work","path":"synthetic-owner"}],"bot_mode_protocol":true}""")
                    "runtime.describe" -> Json.parseToJsonElement("""{"instance_id":"synthetic-instance","profile_id":"synthetic-owner","authority_epoch":1,"capabilities":["durable-admission-v1","event-replay-v1"]}""")
                    "session.resume" -> buildJsonObject {
                        put("session_id", "synthetic-session"); put("stored_session_id", "synthetic-session")
                        put("messages", history); put("display_messages", history); put("message_count", history.size)
                        put("running", false); put("authority_epoch", 1); put("replay_epoch", "synthetic-epoch")
                        put("last_sequence", 0); put("subscription_id", "synthetic-subscription")
                        put("revision", 0); put("execution_generation", 1)
                        put("pending", JsonArray(emptyList())); put("prompts", JsonArray(emptyList()))
                        put("info", buildJsonObject { put("desktop_protocol", "hermes-gateway-v1"); put("profile_id", "synthetic-owner") })
                    }
                    else -> buildJsonObject { }
                }
                rpc.receive(buildJsonObject { put("jsonrpc", "2.0"); put("id", call.getValue("id")); put("result", result) }.toString())
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = UnconfinedTestDispatcher(testScheduler))
        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""")
        val cache = SessionCache()
        val repository = LiveGatewaySessionRepository(cache,
            MutableStateFlow(GatewayConnectionState(GatewayConnectionStatus.Connected)),
            MutableStateFlow<GatewayRpcClient?>(rpc), backgroundScope) { 1800000000000 }
        runCurrent()
        val key = repository.openSession("synthetic-session")
        var seq = 0
        fun deliver(payload: JsonObject, type: String = "message.start", authorityEpoch: Int = 1,
                    session: String = "synthetic-session", sequenceNumber: Int? = null) {
            rpc.receive(buildJsonObject {
                put("jsonrpc", "2.0"); put("method", "event")
                put("params", buildJsonObject {
                    put("type", type); put("session_id", session); put("payload", payload)
                    put("authority_epoch", authorityEpoch); put("execution_generation", 1)
                    put("admission_id", "synthetic-admission"); put("replay_epoch", "synthetic-epoch")
                    put("seq", sequenceNumber ?: ++seq)
                })
            }.toString())
        }
        val rows = listOf("process_complete", "async_delegation_complete").mapIndexed { index, kind ->
            buildJsonObject {
                put("display_kind", kind); put("display_text", "Synthetic background work finished")
                put("text", "[ASYNC DELEGATION COMPLETE]\n--- RESULT ---\nSynthetic result")
                put("row_id", 11 + index)
            }
        }
        rows.forEach { deliver(it) }
        runCurrent()
        val timelines = cache.transcript(key).filterIsInstance<TimelineEvent>()
        assertEquals("typed machinery text must not become assistant speech", 2, timelines.size)
        assertTrue(cache.transcript(key).filterIsInstance<AssistantTurn>().isEmpty())
        assertEquals(listOf("11", "12"), timelines.map { it.id })
        assertEquals(listOf(11L, 12L), timelines.map { it.rowId?.value })
        assertTrue(timelines.all { it.label == "Synthetic background work finished" && it.report == "Synthetic result" })
        deliver(rows.first(), authorityEpoch = 0, sequenceNumber = 2)
        deliver(rows.first(), session = "foreign-session", sequenceNumber = 1)
        runCurrent()
        assertEquals(2, cache.transcript(key).filterIsInstance<TimelineEvent>().size)
        deliver(buildJsonObject { put("row_id", 13); put("text", "Ordinary role-less reply") })
        deliver(buildJsonObject { put("row_id", 13); put("delta", " continues") }, "message.delta")
        deliver(buildJsonObject { put("row_id", 13); put("text", "Ordinary role-less reply continues") }, "message.complete")
        deliver(buildJsonObject { put("row_id", 14); put("role", "user"); put("text", "[ASYNC DELEGATION COMPLETE] quoted by user") })
        runCurrent()
        assertEquals("Ordinary role-less reply continues", cache.transcript(key).filterIsInstance<AssistantTurn>().single().markdown)
        assertEquals("[ASYNC DELEGATION COMPLETE] quoted by user", cache.transcript(key).filterIsInstance<UserTurn>().single().text)
        history = JsonArray(rows.map { row -> JsonObject(row + mapOf("role" to JsonPrimitive("user"),
            "content" to row.getValue("text"), "display_metadata" to buildJsonObject { put("display_text", row.getValue("display_text")) })) })
        repository.openSession(key)
        runCurrent()
        assertEquals("snapshot hydration must not duplicate typed rows", 2, cache.transcript(key).filterIsInstance<TimelineEvent>().size)
        rpc.close()
    }
}
