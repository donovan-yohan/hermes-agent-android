package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayHttpTest {
    @Test
    fun `builds encoded query under endpoint path and applies connection authorization`() = runTest {
        var captured: Request? = null
        val client = clientResponding { request ->
            captured = request
            response(request, 200, "{\"ok\":true}")
        }
        val transport = OkHttpGatewayHttp(
            http = client,
            resolveEndpoint = { "https://gateway.example/root/" },
            resolveAuthorization = { "Authorization" to "Bearer test-token" },
        )

        val result = transport.execute(
            GatewayHttpRequest(
                path = "/api/git/status",
                method = "GET",
                body = null,
                timeoutMillis = 1_000,
                query = mapOf("path" to "/srv/work tree"),
                maxResponseBytes = 1_024,
            ),
        ) as GatewayHttpResult.Success

        assertEquals(200, result.statusCode)
        assertEquals("/root/api/git/status", captured?.url?.encodedPath)
        assertEquals("/srv/work tree", captured?.url?.queryParameter("path"))
        assertEquals("Bearer test-token", captured?.header("Authorization"))
    }

    @Test
    fun `rejects malformed authority unsupported methods and missing request bodies`() = runTest {
        val never = clientResponding { error("network must not run") }
        val malformed = OkHttpGatewayHttp(never, { "://bad" }, { "Authorization" to "x" })
        assertTrue(
            (malformed.execute(GatewayHttpRequest("api/config", "GET", null, 100)) as GatewayHttpResult.Rejected)
                .safeMessage.contains("form"),
        )

        val valid = OkHttpGatewayHttp(never, { "https://gateway.example" }, { "Authorization" to "x" })
        assertTrue(valid.execute(GatewayHttpRequest("api/config", "DELETE", null, 100)) is GatewayHttpResult.Rejected)
        assertTrue(valid.execute(GatewayHttpRequest("api/config", "POST", null, 100)) is GatewayHttpResult.Rejected)
    }

    @Test
    fun `does not make a request without active connection authorization`() = runTest {
        var called = false
        val transport = OkHttpGatewayHttp(
            clientResponding { request ->
                called = true
                response(request, 200, "ok")
            },
            { "https://gateway.example" },
            { null },
        )

        val result = transport.execute(GatewayHttpRequest("api/config", "GET", null, 100))
        assertTrue(result is GatewayHttpResult.Rejected)
        assertFalse(called)
    }

    @Test
    fun `bounds successful response before handing bytes to a feature`() = runTest {
        val transport = OkHttpGatewayHttp(
            clientResponding { request -> response(request, 200, "x".repeat(65)) },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )

        val result = transport.execute(
            GatewayHttpRequest("api/git/status", "GET", null, 100, maxResponseBytes = 64),
        ) as GatewayHttpResult.Rejected
        assertTrue(result.safeMessage.contains("too much data"))
    }

    @Test
    fun `maps authorization failure without exposing response body`() = runTest {
        val transport = OkHttpGatewayHttp(
            clientResponding { request -> response(request, 401, "secret backend detail") },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )

        val result = transport.execute(
            GatewayHttpRequest(
                "api/config",
                "PUT",
                "{}".toRequestBody("application/json".toMediaType()),
                100,
            ),
        ) as GatewayHttpResult.Rejected
        assertEquals(401, result.statusCode)
        assertFalse(result.safeMessage.contains("secret"))
    }

    @Test
    fun `hands a refusal envelope to the caller without ever showing it`() = runTest {
        val envelope = """{"error":"session_expired","reason":"invalid_or_expired_session"}"""
        val transport = OkHttpGatewayHttp(
            clientResponding { request -> response(request, 401, envelope) },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )

        val result = transport.execute(
            GatewayHttpRequest("api/config", "GET", null, 100, captureEnvelope = true),
        ) as GatewayHttpResult.Rejected

        // The caller can classify the refusal...
        assertEquals(envelope, result.envelopeBytes.toString(Charsets.UTF_8))
        // ...but the only thing a surface may show still says nothing about it.
        assertFalse(result.safeMessage.contains("session_expired"))
        assertFalse(result.safeMessage.contains("reason"))

        // Consuming wipes the buffer: a refusal body outlives nothing.
        result.consumeEnvelope { }
        assertTrue(result.envelopeBytes.all { it == 0.toByte() })
    }

    @Test
    fun `an oversized refusal body yields no envelope rather than an unbounded read`() = runTest {
        val transport = OkHttpGatewayHttp(
            clientResponding { request -> response(request, 500, "x".repeat(64 * 1024)) },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )

        val result = transport.execute(
            GatewayHttpRequest("api/config", "GET", null, 100, captureEnvelope = true),
        ) as GatewayHttpResult.Rejected
        assertEquals(500, result.statusCode)
        assertTrue(result.envelopeBytes.isEmpty())
    }

    @Test
    fun `a caller that does not classify refusals is handed no envelope to wipe`() = runTest {
        // The default. Every pre-existing REST caller reads only safeMessage,
        // so a refusal body it never asked for would be an extra read and an
        // un-wiped backend buffer it has no reason to hold.
        val transport = OkHttpGatewayHttp(
            clientResponding { request ->
                response(request, 401, """{"error":"session_expired","detail":"secret backend detail"}""")
            },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )

        val result = transport.execute(GatewayHttpRequest("api/config", "GET", null, 100))
            as GatewayHttpResult.Rejected
        assertEquals(401, result.statusCode)
        assertTrue(result.envelopeBytes.isEmpty())
        assertFalse(result.safeMessage.contains("secret"))
    }
}

private fun clientResponding(block: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(Interceptor { chain -> block(chain.request()) })
    .build()

private fun response(request: Request, code: Int, body: String): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message(if (code in 200..299) "OK" else "Rejected")
    .body(body.toResponseBody("application/json".toMediaType()))
    .build()
