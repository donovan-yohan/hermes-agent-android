package com.hermesagent.mobile.data.notifications

import com.hermesagent.mobile.data.gateway.GatewayRpcWire
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationReadRpcTest {
    @Test fun readonlyRawRequestPreservesTitleWithoutCanonicalScopeAndBoundsFrames() = runTest {
        var frame = ""
        var closed = false
        val rpc = NotificationReadRpc(object : GatewayRpcWire {
            override fun send(text: String): Boolean { frame = text; return true }
            override fun close() { closed = true }
        }, { true })
        val response = async { rpc.request("session.list", buildJsonObject { put("title", "Bot Chat"); put("include_hidden", true) }) }
        runCurrent()
        val sent = Json.parseToJsonElement(frame).jsonObject
        assertFalse(sent.getValue("params").jsonObject.containsKey("scope"))
        rpc.receive(buildJsonObject { put("id", sent.getValue("id")); put("result", buildJsonObject { put("sessions", JsonArray(emptyList())) }) }.toString())
        assertNotNull(response.await())
        try { rpc.request("session.resume", buildJsonObject { put("session_id", "x") }); fail() }
        catch (_: IllegalArgumentException) { }
        rpc.receive("x".repeat(524289))
        assertTrue(closed)
    }
}
