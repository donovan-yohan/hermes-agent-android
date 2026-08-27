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

        // Two different refusals that a bare `is Rejected` would conflate.
        //
        // A verb this transport does not serve stays refused: widening the set
        // to DELETE and PATCH did not turn the `when` into a passthrough.
        val unsupported = valid.execute(GatewayHttpRequest("api/config", "HEAD", null, 100))
            as GatewayHttpResult.Rejected
        assertEquals("Hermes got an unsupported Gateway request.", unsupported.safeMessage)

        // A verb this transport *does* serve, asked for without the body it
        // requires, is a caller that built the request wrong — not an unknown
        // verb. Asserting only the type would let the body guard disappear.
        for (verb in listOf("POST", "PUT", "PATCH")) {
            val incomplete = valid.execute(GatewayHttpRequest("api/config", verb, null, 100))
                as GatewayHttpResult.Rejected
            assertEquals("Hermes got an incomplete Gateway request.", incomplete.safeMessage)
        }

        // And the inverse for the one body-less destructive verb.
        val bodiedDelete = valid.execute(
            GatewayHttpRequest("api/config", "DELETE", "{}".toRequestBody("application/json".toMediaType()), 100),
        ) as GatewayHttpResult.Rejected
        assertEquals("Hermes got an unsupported Gateway request.", bodiedDelete.safeMessage)

        // None of these are a hop: nothing reached the Gateway, and `0` is how
        // that is spelled.
        assertEquals(0, unsupported.statusCode)
        assertEquals(0, bodiedDelete.statusCode)
    }

    @Test
    fun `sends DELETE with no body and refuses one that carries a body`() = runTest {
        var captured: Request? = null
        val transport = OkHttpGatewayHttp(
            clientResponding { request ->
                captured = request
                response(request, 200, """{"ok":true}""")
            },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )

        val deleted = transport.execute(
            GatewayHttpRequest(
                path = "api/sessions/abc",
                method = "DELETE",
                body = null,
                timeoutMillis = 100,
                query = mapOf("profile" to "work"),
            ),
        ) as GatewayHttpResult.Success
        assertEquals(200, deleted.statusCode)
        assertEquals("DELETE", captured?.method)
        assertEquals("/api/sessions/abc", captured?.url?.encodedPath)
        assertEquals("work", captured?.url?.queryParameter("profile"))
        // No body at all, the way GET carries none — not an empty one.
        assertTrue(captured?.body == null)

        // A DELETE that carries a body is a caller sending scope this route
        // would ignore; it never reaches the wire.
        var reached = false
        val refusing = OkHttpGatewayHttp(
            clientResponding { request ->
                reached = true
                response(request, 200, """{"ok":true}""")
            },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )
        val refused = refusing.execute(
            GatewayHttpRequest(
                path = "api/sessions/abc",
                method = "DELETE",
                body = "{}".toRequestBody("application/json".toMediaType()),
                timeoutMillis = 100,
            ),
        ) as GatewayHttpResult.Rejected
        assertTrue(refused.safeMessage.contains("unsupported"))
        assertFalse(reached)
    }

    @Test
    fun `sends PATCH with the caller's body`() = runTest {
        var captured: Request? = null
        val transport = OkHttpGatewayHttp(
            clientResponding { request ->
                captured = request
                response(request, 200, """{"ok":true,"title":"Renamed"}""")
            },
            { "https://gateway.example" },
            { "Authorization" to "x" },
        )

        val result = transport.execute(
            GatewayHttpRequest(
                path = "api/sessions/abc",
                method = "PATCH",
                body = """{"title":"Renamed"}""".toRequestBody("application/json".toMediaType()),
                timeoutMillis = 100,
            ),
        ) as GatewayHttpResult.Success
        assertEquals(200, result.statusCode)
        assertEquals("PATCH", captured?.method)
        assertEquals(19L, captured?.body?.contentLength())
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
        // The route answered, and the request ran on the host. Reporting `0`
        // here would make an oversized page indistinguishable from a dead route
        // or a connection that never completed — and a caller that remembers
        // "this backend does not serve that" off the wrong signal degrades a
        // working Gateway for the rest of the session.
        assertEquals(200, result.statusCode)
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
