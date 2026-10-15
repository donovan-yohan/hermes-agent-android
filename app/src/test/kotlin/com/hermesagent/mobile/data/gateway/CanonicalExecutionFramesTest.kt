package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Verbatim public generated fixture: apps/desktop/src/lib/execution-authority.frames.json;
 * generator tests/gateway/fixtures/execution_frames_capture.py @ 379cf1220fe289b5ecfe8bcf61cd4d5126e140cd.
 * Snapshot responses below are synthetic projections of those captured public owner fields. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalExecutionFramesTest {
    private val fixture = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream(
        "/upstream-379cf122/execution-authority.frames.json")).bufferedReader().use { it.readText() }).jsonObject

    @Test
    fun `captured execution stamps survive delivery and rotated owner drops retired epoch`() = runTest {
        var epochName = "epoch1"
        val sent = mutableListOf<JsonObject>()
        lateinit var rpc: CorrelatedGatewayRpc
        rpc = CorrelatedGatewayRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean {
                val call = Json.parseToJsonElement(text).jsonObject
                sent += call
                val epoch = fixture.getValue(epochName).jsonObject
                val first = epoch.getValue("frames").jsonArray.first().jsonObject.getValue("params").jsonObject
                val params = call.getValue("params").jsonObject
                val result = when (call.getValue("method").jsonPrimitive.content) {
                    "runtime.describe" -> buildJsonObject {
                        put("instance_id", "public-instance"); put("profile_id", "p"); put("authority_epoch", epoch.getValue("authority_epoch"))
                        put("capabilities", JsonArray(listOf(JsonPrimitive("durable-admission-v1"), JsonPrimitive("event-replay-v1"))))
                    }
                    "session.resume" -> buildJsonObject {
                        put("session_id", "shared"); put("stored_session_id", "shared"); put("messages", JsonArray(emptyList()))
                        put("display_messages", JsonArray(emptyList())); put("message_count", 0); put("running", false)
                        put("authority_epoch", epoch.getValue("authority_epoch")); put("replay_epoch", first.getValue("replay_epoch"))
                        put("last_sequence", 0); put("subscription_id", "public-subscription")
                        put("revision", 0); put("execution_generation", first.getValue("payload").jsonObject.getValue("execution_generation"))
                        put("pending", JsonArray(emptyList())); put("prompts", JsonArray(emptyList()))
                        put("info", buildJsonObject { put("desktop_protocol", "hermes-gateway-v1"); put("profile_id", "p") })
                    }
                    "session.mutate" -> buildJsonObject {
                        put("session_id", "shared"); put("operation", params.getValue("operation"))
                        put("revision", params.getValue("expected_revision").jsonPrimitive.long + 1)
                        params.getValue("payload").jsonObject.forEach { (key, value) -> put(key, value) }
                    }
                    else -> buildJsonObject { }
                }
                rpc.receive(buildJsonObject { put("jsonrpc", "2.0"); put("id", call.getValue("id")); put("result", result) }.toString())
                return true
            }
            override fun close() = Unit
        }, eventPumpDispatcher = UnconfinedTestDispatcher(testScheduler))
        val events = mutableListOf<GatewayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { rpc.events.collect { events += it } }
        rpc.receive("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}""")
        val key = rpc.request("session.resume", buildJsonObject { put("session_id", "shared") }).jsonObject.getValue("session_id")
        events.clear()
        val firstFrames = fixture.getValue("epoch1").jsonObject.getValue("frames").jsonArray
        firstFrames.forEach { rpc.receive(it.toString()) }
        runCurrent()
        assertEquals(firstFrames.size, events.size)
        val completed = events.single { it.type == "message.complete" }
        val original = firstFrames.first { it.jsonObject.getValue("params").jsonObject["type"] == JsonPrimitive("message.complete") }
            .jsonObject.getValue("params").jsonObject
        assertEquals(original.getValue("admission_id").jsonPrimitive.content, completed.admissionId)
        assertEquals(original.getValue("execution_generation").jsonPrimitive.long, completed.executionGeneration)
        assertEquals(original.getValue("authority_epoch").jsonPrimitive.long, completed.authorityEpoch)
        assertEquals(original.getValue("replay_epoch").jsonPrimitive.content, completed.replayEpoch)
        assertEquals(key.jsonPrimitive.content, completed.runtimeSessionId)
        firstFrames.forEach { rpc.receive(it.toString()) }
        runCurrent()
        assertEquals(firstFrames.size, events.size)
        epochName = "epoch2"
        rpc.request("session.resume", buildJsonObject { put("session_id", key) })
        events.clear()
        // Public synthetic idle metadata variant: session.info can omit execution stamps
        // (the same fixture's paused_cancel_frames does); retain its real payload/epoch/sequence.
        val oldInfo = firstFrames.last().jsonObject
        val oldParams = oldInfo.getValue("params").jsonObject
        val delayedIdle = JsonObject(oldInfo + ("params" to JsonObject(oldParams - setOf("authority_epoch", "execution_generation", "admission_id"))))
        rpc.receive(delayedIdle.toString())
        runCurrent()
        assertTrue(events.isEmpty())
        val second = fixture.getValue("epoch2").jsonObject
        (second.getValue("frames").jsonArray + second.getValue("idle_mutation_frames").jsonArray + second.getValue("paused_cancel_frames").jsonArray)
            .forEach { rpc.receive(it.toString()) }
        runCurrent()
        assertEquals(9, events.size)
        assertFalse(events.any { it.type == "session.replay_gap" })
        rpc.request("session.title", buildJsonObject { put("session_id", key); put("title", "Public title") })
        val mutation = sent.last().getValue("params").jsonObject
        val lastInfo = second.getValue("paused_cancel_frames").jsonArray[1].jsonObject.getValue("params").jsonObject.getValue("payload").jsonObject
        assertEquals(lastInfo.getValue("revision"), mutation["expected_revision"])
        rpc.close()
    }
}
