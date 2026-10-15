package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Canonical serializers/dispatch: gateway/session_controls.py:345-356,420-473 and tui_gateway/contracts/canonical_sessions.py:17-51,120-168,219-269 @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalGatewayRpcTest {
    @Test
    fun `authority advertisement selects gui create with retained identity and frozen policy options`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler))
        peer.ready()
        val result = peer.rpc.request("session.create", buildJsonObject {
            put("source", "desktop"); put("profile", "work"); put("cwd", "/workspace/public")
            put("model", "public-model"); put("provider", "public-provider")
            put("reasoning_effort", "high"); put("fast", false)
        }).jsonObject
        val create = peer.frames.single { it["method"] == JsonPrimitive("session.create") }.getValue("params").jsonObject
        assertEquals(JsonPrimitive("gui"), create["source"])
        assertTrue(create.getValue("request_id").jsonPrimitive.content.isNotEmpty())
        assertEquals(JsonPrimitive("high"), create["reasoning"])
        assertEquals(JsonPrimitive("normal"), create["service_tier"])
        assertFalse("reasoning_effort" in create)
        assertFalse("fast" in create)
        val storedKey = result.getValue("stored_session_id").jsonPrimitive.content
        peer.rpc.request("session.resume", buildJsonObject { put("session_id", storedKey) })
        assertEquals(JsonPrimitive("canonical-public"), peer.frames.last().getValue("params").jsonObject["session_id"])
        assertEquals(JsonPrimitive("work"), peer.frames.last().getValue("params").jsonObject["profile"])
        peer.rpc.close()
    }

    @Test
    fun `warm activate and legacy history use canonical display snapshot without raw history confusion`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler))
        peer.ready()
        val params = buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") }
        peer.rpc.request("session.activate", params)
        val history = peer.rpc.request("session.history", params).jsonObject
        assertFalse(peer.frames.any { it["method"] in listOf(JsonPrimitive("session.activate"), JsonPrimitive("session.history")) })
        val resumes = peer.frames.filter { it["method"] == JsonPrimitive("session.resume") }
        assertTrue(resumes.isNotEmpty())
        assertTrue(resumes.all { it.getValue("params").jsonObject["display_projection"] == JsonPrimitive(true) })
        assertEquals("Display public answer", history.getValue("messages").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        peer.rpc.close()
    }

    @Test
    fun `canonical stored list and exact generation control preserve owning profile`() = runTest {
        val peer = CanonicalPeer(UnconfinedTestDispatcher(testScheduler))
        peer.ready()
        peer.rpc.request("session.list", buildJsonObject { put("profile", "work"); put("limit", 100) })
        assertEquals(JsonPrimitive("stored"), peer.frames.last().getValue("params").jsonObject["scope"])
        peer.rpc.request("session.resume", buildJsonObject { put("profile", "work"); put("session_id", "canonical-public") })
        peer.rpc.request("session.interrupt", buildJsonObject { put("profile", "work"); put("session_id", "canonical-public") })
        assertEquals(JsonPrimitive(3), peer.frames.last().getValue("params").jsonObject["execution_generation"])
        assertEquals(JsonPrimitive("work"), peer.frames.last().getValue("params").jsonObject["profile"])
        peer.rpc.close()
    }

    @Test
    fun `owner key survives reconnect only after exact endpoint and owner are reverified`() = runTest {
        val first = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val key = first.rpc.request("session.resume", buildJsonObject { put("session_id", "canonical-public"); put("profile", "work") })
            .jsonObject.getValue("session_id").jsonPrimitive.content
        first.rpc.close()
        val second = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        val resumed = second.rpc.request("session.resume", buildJsonObject { put("session_id", key); put("profile", "work") })
        assertEquals(JsonPrimitive(key), resumed.jsonObject["session_id"])
        assertEquals(JsonPrimitive("canonical-public"), second.frames.last().getValue("params").jsonObject["session_id"])
        second.rpc.close()
        val foreign = CanonicalPeer(UnconfinedTestDispatcher(testScheduler)).also { it.ready() }
        foreign.rpc.bindSessionEndpoint("another public endpoint")
        assertTrue(runCatching { foreign.rpc.request("session.resume", buildJsonObject { put("session_id", key); put("profile", "work") }) }.exceptionOrNull() is GatewayRpcException)
        assertTrue(foreign.frames.none { it["method"] == JsonPrimitive("session.resume") })
        foreign.rpc.close()
    }

    private class CanonicalPeer(dispatcher: kotlinx.coroutines.CoroutineDispatcher) {
        val frames = mutableListOf<JsonObject>()
        val rpc: CorrelatedGatewayRpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val frame = Json.parseToJsonElement(text).jsonObject
                frames += frame
                val method = frame.getValue("method").jsonPrimitive.content
                val params = frame.getValue("params").jsonObject
                val result = when (method) {
                    "runtime.describe" -> """{"instance_id":"public-instance","profile_id":"public-owner","authority_epoch":2,"capabilities":["durable-admission-v1","event-replay-v1","local-cli-create-v1"],"session_create":{"sources":["gui"],"parameters":[]},"skin":{}}"""
                    "session.create" -> {
                        if (params["source"] != JsonPrimitive("gui") || "request_id" !in params || "fast" in params || "reasoning_effort" in params) {
                            replyError(frame)
                            return true
                        }
                        snapshot()
                    }
                    "session.resume" -> snapshot()
                    "session.list" -> """{"sessions":[],"scope":"${params["scope"]?.jsonPrimitive?.content ?: "live"}"}"""
                    "session.interrupt" -> {
                        if (params["execution_generation"] != JsonPrimitive(3)) {
                            replyError(frame)
                            return true
                        }
                        """{"ref":{"profile_id":"public-owner","session_id":"canonical-public"},"instance_id":"public-instance","authority_epoch":2,"revision":7,"execution_generation":3,"execution_state":"idle"}"""
                    }
                    else -> {
                        replyError(frame, -32601)
                        return true
                    }
                }
                rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"result":$result}""")
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = dispatcher)

        private fun replyError(frame: JsonObject, code: Int = 4001) {
            rpc.receive("""{"jsonrpc":"2.0","id":${frame.getValue("id")},"error":{"code":$code,"message":"invalid_params","data":{"reason":"invalid_params"}}}""")
        }
        fun ready() = rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true,"change_events":true,"heartbeat":true,"replay_epoch":"public-global"}}}""")
        private fun snapshot() = """{"session_id":"canonical-public","stored_session_id":"canonical-public","messages":[{"role":"assistant","content":"Raw public answer"}],"display_messages":[{"role":"assistant","text":"Display public answer"}],"message_count":1,"running":false,"authority_epoch":2,"replay_epoch":"public-session-epoch","last_sequence":0,"subscription_id":"public-subscription","revision":7,"execution_generation":3,"pending":[],"prompts":[],"info":{"desktop_protocol":"hermes-gateway-v1","profile_name":"work","profile_id":"public-owner","model":"public-model","cwd":"/workspace/public"}}"""
    }
}
