package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayLogsTest {
    private class FakeHttp(var result: GatewayHttpResult) : GatewayHttp {
        var request: GatewayHttpRequest? = null
        override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
            this.request = request
            return result
        }
    }

    @Test fun `identity withdrawal during credential resolution prevents HTTP dispatch`() = runTest {
        val credentialsReady = CompletableDeferred<Unit>()
        var current = true
        var exchanges = 0
        val http = OkHttpGatewayHttp(
            okhttp3.OkHttpClient.Builder().addInterceptor {
                exchanges++
                error("No network permitted")
            }.build(),
            { "https://gateway.example" },
            { credentialsReady.await(); "Authorization" to "Bearer synthetic" },
        )
        val task = async { readGatewayLogs(http) { current } }
        runCurrent()
        current = false
        credentialsReady.complete(Unit)
        runCurrent()
        assertEquals(GatewayLogsResult.Failed, task.await())
        assertEquals(0, exchanges)
    }

    @Test fun `bounded authenticated request redacts before retaining and wipes bytes`() = runTest {
        val bytes = """{"file":"errors","lines":["password=synthetic-secret","Bearer synthetic-bearer","[2001:db8::1]:443 fe80::abcd%qa0","ordinary failure"]}""".encodeToByteArray()
        val http = FakeHttp(GatewayHttpResult.Success(200, bytes))
        val result = readGatewayLogs(http) { true } as GatewayLogsResult.Content
        val request = http.request!!
        assertEquals("/api/logs", request.path)
        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals(mapOf("file" to "errors", "lines" to "100"), request.query)
        assertEquals(10_000L, request.timeoutMillis)
        assertEquals(262_144L, request.maxResponseBytes)
        assertFalse(request.captureEnvelope)
        assertFalse(result.text.contains("synthetic-secret"))
        assertFalse(result.text.contains("synthetic-bearer"))
        assertFalse(result.text.contains("2001:db8"))
        assertFalse(result.text.contains("fe80"))
        assertFalse(result.text.contains("qa0"))
        assertTrue(result.text.contains("ordinary failure"))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `strict envelope and every row validated including beyond display cap`() = runTest {
        val bad = listOf("[]", "{}", """{"file":"other","lines":[]}""",
            """{"file":"errors","lines":[false]}""", """{"file":"errors","lines":[null]}""",
            """{"file":"errors","lines":[{}]}""", """{"file":"errors","lines":"text"}""",
            """{"file":"errors","lines":[],"extra":true}""", "not json",
            """{"file":"errors","lines":[""" + List(100) { "\"ok\"" }.joinToString(",") + ",42]}")
        for (raw in bad) {
            val bytes = raw.encodeToByteArray()
            assertEquals(GatewayLogsResult.Failed, readGatewayLogs(FakeHttp(GatewayHttpResult.Success(200, bytes))) { true })
            assertTrue(bytes.all { it == 0.toByte() })
        }
        assertEquals(GatewayLogsResult.Empty, readGatewayLogs(FakeHttp(GatewayHttpResult.Success(200,
            """{"file":"errors","lines":[]}""".encodeToByteArray()))) { true })
    }

    @Test fun `oversize status auth unsupported and exceptions never expose raw errors`() = runTest {
        val cases = listOf(
            GatewayHttpResult.Rejected(401, "private") to GatewayLogsResult.Refused,
            GatewayHttpResult.Rejected(403, "private") to GatewayLogsResult.Refused,
            GatewayHttpResult.Rejected(404, "private") to GatewayLogsResult.Unsupported,
            GatewayHttpResult.Rejected(405, "private") to GatewayLogsResult.Unsupported,
            GatewayHttpResult.Rejected(200, OVERSIZE_MESSAGE) to GatewayLogsResult.Oversize,
            GatewayHttpResult.Rejected(0, "private") to GatewayLogsResult.Failed,
            GatewayHttpResult.Success(200, ByteArray(262_145)) to GatewayLogsResult.Oversize,
        )
        for ((response, expected) in cases) assertEquals(expected, readGatewayLogs(FakeHttp(response)) { true })
        val envelope = "private".encodeToByteArray()
        readGatewayLogs(FakeHttp(GatewayHttpResult.Rejected(500, "private", envelope))) { true }
        assertTrue(envelope.all { it == 0.toByte() })
        val throwing = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult = error("private")
        }
        assertEquals(GatewayLogsResult.Failed, readGatewayLogs(throwing) { true })
    }

    @Test fun `row and text caps retain only a bounded redacted tail`() = runTest {
        val rows = listOf("\"discarded first row\"") + List(100) { "\"" + "x".repeat(100) + "\"" }
        val bytes = ("{\"file\":\"errors\",\"lines\":[" + rows.joinToString(",") + "]}").encodeToByteArray()
        val result = readGatewayLogs(FakeHttp(GatewayHttpResult.Success(200, bytes))) { true } as GatewayLogsResult.Content
        assertTrue(result.truncated)
        assertTrue(result.text.length <= GATEWAY_LOG_MAX_TEXT)
        assertFalse(result.text.contains("discarded"))
    }

    @Test fun `late blocking response is discarded and wiped even after caller cancellation`() = runTest {
        val response = CompletableDeferred<GatewayHttpResult>()
        var current = true
        val http = object : GatewayHttp {
            override suspend fun execute(request: GatewayHttpRequest) = response.await()
        }
        val task = async { readGatewayLogs(http) { current } }
        runCurrent()
        current = false
        task.cancel()
        val bytes = """{"file":"errors","lines":["private"]}""".encodeToByteArray()
        response.complete(GatewayHttpResult.Success(200, bytes))
        runCurrent()
        assertTrue(bytes.all { it == 0.toByte() })
        assertTrue(task.isCancelled)
    }
}
