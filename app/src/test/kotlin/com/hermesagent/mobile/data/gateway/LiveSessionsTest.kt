package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.session.SessionCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionsTest {

    @Test
    fun `parser maps live statuses and drops non-live rows`() {
        val rows = parse(
            """{"sessions":[
                {"session_key":"one","status":"working"},
                {"session_key":"two","status":"starting"},
                {"session_key":"three","status":"waiting"},
                {"session_key":"idle","status":"idle"},
                {"session_key":"unknown","status":"unknown"}
            ]}""",
        )

        assertEquals(
            listOf(LiveSessionStatus.Working, LiveSessionStatus.Starting, LiveSessionStatus.Waiting),
            rows.map(LiveSession::status),
        )
    }

    @Test
    fun `parser drops rows without durable keys and non-object rows`() {
        val rows = parse("""{"sessions":[{},42,{"session_key":" ","status":"working"},{"session_key":"ok","status":"working"}]}""")

        assertEquals(listOf("ok"), rows.map(LiveSession::durableSessionId))
    }

    @Test
    fun `parser normalizes seconds and milliseconds timestamps`() {
        val rows = parse(
            """{"sessions":[
                {"session_key":"seconds","status":"working","last_active":1700000000},
                {"session_key":"millis","status":"waiting","last_active":1700000000123}
            ]}""",
        )

        assertEquals(listOf(1_700_000_000_000L, 1_700_000_000_123L), rows.map(LiveSession::lastActiveAtMillis))
    }

    @Test
    fun `parser rejects missing or malformed sessions`() {
        assertMalformed("{}")
        assertMalformed("{" + "\"sessions\":{}" + "}")
        assertMalformed("[]")
    }

    @Test
    fun `live sessions is unavailable without a client or on failure`() = runTest {
        val noClient = LiveGatewaySessionRepository(
            SessionCache(),
            MutableStateFlow(GatewayConnectionState()),
            MutableStateFlow<GatewayRpcClient?>(null),
            backgroundScope,
        )
        val failing = LiveGatewaySessionRepository(
            SessionCache(),
            MutableStateFlow(GatewayConnectionState()),
            MutableStateFlow<GatewayRpcClient?>(ThrowingRpc),
            backgroundScope,
        )

        assertTrue(noClient.liveSessions() is LiveSessionSnapshot.Unavailable)
        assertTrue(failing.liveSessions() is LiveSessionSnapshot.Unavailable)
    }

    private fun parse(text: String): List<LiveSession> = parseLiveSessionList(json(text))
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun assertMalformed(text: String) {
        assertTrue(runCatching { parseLiveSessionList(json(text)) }.exceptionOrNull() is GatewayRpcException)
    }

    private object ThrowingRpc : GatewayRpcClient {
        override val events: Flow<GatewayEvent> = emptyFlow()
        override suspend fun request(method: String, params: JsonObject): JsonElement =
            throw GatewayRpcException("unavailable")
        override fun close() = Unit
    }
}
