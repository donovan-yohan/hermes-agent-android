package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.OkHttpGatewayHttp
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relay rides the connection's existing authenticated transport; it adds no
 * credential plumbing of its own. These tests drive the real
 * [OkHttpGatewayHttp] wired exactly as `GatewayConnection` wires each reachable
 * leg, and check two things per leg: the credential the Gateway expects goes
 * out on a relay request, and nothing credential-shaped comes back into the
 * relay layer.
 *
 * The leg matrix is the one in `docs/spikes/plugin-surface-relay.md` §G1. The
 * loopback/no-auth bind is deliberately absent: it is unreachable off-host by
 * definition, so there is no leg to conform to.
 */
class RelayAuthLegConformanceTest {

    @Test
    fun `every reachable leg presents its own credential and no other`() = runTest {
        for (leg in LEGS) {
            val sent = mutableListOf<Request>()
            val repository = RelayPluginRepository { leg.transport(sent) { ok(it, """{"status":"ready"}""") } }

            val available = repository.availability()
            assertTrue("${leg.name}: expected the plugin to answer", available is RelayAvailability.Available)

            val request = sent.single()
            assertEquals("${leg.name}: wrong credential header", leg.credential, request.header(leg.header))
            // A leg presents one credential. Carrying a second would widen what
            // a compromised hop can replay for free.
            for (other in LEGS.filter { it.header != leg.header }) {
                assertNull("${leg.name}: leaked ${other.header}", request.header(other.header))
            }
            assertNull("${leg.name}: credentials never ride the query string", request.url.query)
            assertEquals(
                "${leg.name}: wrong relay namespace",
                "${leg.origin}/api/plugins/hermes-plugin-relay/connection/status",
                request.url.toString(),
            )
        }
    }

    @Test
    fun `no credential material reaches anything the relay layer hands back`() = runTest {
        for (leg in LEGS) {
            val repository = RelayPluginRepository {
                leg.transport(mutableListOf()) { ok(it, CHANNELS_BODY) }
            }

            // Projected rows are built only from the response body, so a leg's
            // credential has no path into them.
            val channels = repository.channels()
            assertNotNull("${leg.name}: expected projected rows", channels)
            assertFalse(
                "${leg.name}: credential material reached a projected row",
                channels.toString().contains(leg.secret),
            )

            // Nor into the copy a refusal produces.
            val refused = RelayPluginRepository {
                leg.transport(mutableListOf()) { unauthorized(it, GATE_LAPSED_ENVELOPE) }
            }.post("team/general", "ahoy", RelayMessageFormat.MARKDOWN, "cmid-1")
                as RelayPostResult.Failed
            assertFalse(
                "${leg.name}: credential material reached failure copy",
                refused.safeMessage.contains(leg.secret),
            )
            // Nor is the refusing service's own wording shown verbatim.
            assertFalse(refused.safeMessage.contains("session_expired"))
        }
    }

    @Test
    fun `a post carries exactly the contract body on every leg`() = runTest {
        for (leg in LEGS) {
            val sent = mutableListOf<Request>()
            val repository = RelayPluginRepository {
                leg.transport(sent) { ok(it, """{"message":$MESSAGE_ROW}""") }
            }

            repository.post("team/general", "ahoy", RelayMessageFormat.MARKDOWN, "cmid-1")

            val body = okio.Buffer().also { sent.single().body?.writeTo(it) }.readUtf8()
            assertEquals(
                "${leg.name}: post body must be exactly the three contract keys",
                """{"text":"ahoy","format":"markdown","clientMessageId":"cmid-1"}""",
                body,
            )
            assertFalse("${leg.name}: credential smuggled into a post", body.contains(leg.secret))
        }
    }

    @Test
    fun `the refusal envelope decides the remedy on every leg, not the leg itself`() = runTest {
        for (leg in LEGS) {
            // Same status, same leg, different envelope: the answer differs
            // because the Gateway said something different, not because of how
            // this client happens to be authenticated.
            val lapsed = RelayPluginRepository {
                leg.transport(mutableListOf()) { unauthorized(it, GATE_LAPSED_ENVELOPE) }
            }.availability()
            assertEquals(
                "${leg.name}: a lapsed credential must be recoverable by one rotation",
                RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired),
                lapsed,
            )

            val none = RelayPluginRepository {
                leg.transport(mutableListOf()) { unauthorized(it, GATE_NO_CREDENTIAL_ENVELOPE) }
            }.availability()
            assertEquals(
                "${leg.name}: nothing presented means nothing to rotate",
                RelayAvailability.SignInRequired(RelaySignInReason.NoCredential),
                none,
            )

            // And the plugin's own auth_required is the host's credential on
            // every leg alike — never a sign-in for the person holding this
            // device.
            val hostLane = RelayPluginRepository {
                leg.transport(mutableListOf()) { unauthorized(it, PLUGIN_AUTH_REQUIRED_ENVELOPE) }
            }.availability()
            assertEquals(
                "${leg.name}: the host's Relay credential is not this device's sign-in",
                RelayLaneState.AUTH_REQUIRED,
                (hostLane as RelayAvailability.Available).channels.state,
            )
        }
    }

    @Test
    fun `a leg with no live credential never puts a relay request on the wire`() = runTest {
        for (leg in LEGS) {
            var called = false
            val transport = OkHttpGatewayHttp(
                http = clientResponding { request ->
                    called = true
                    ok(request, """{"status":"ready"}""")
                },
                resolveEndpoint = { leg.origin },
                // Exactly what the connection's resolver returns once its leg is
                // gone: no credential, therefore no request.
                resolveAuthorization = { null },
            )

            assertEquals(
                RelayAvailability.GatewayUnreachable,
                RelayPluginRepository { transport }.availability(),
            )
            assertFalse("${leg.name}: sent an unauthenticated relay request", called)
        }
    }

    private companion object {
        /**
         * One reachable leg, wired the way `GatewayConnection` wires it:
         * the remote bearer at `GatewayConnection.kt:521-529`, the SSH-tunneled
         * loopback session token at `:665-671`.
         */
        class Leg(
            val name: String,
            val origin: String,
            val header: String,
            val secret: String,
            val credential: String = secret,
        ) {
            fun transport(sent: MutableList<Request>, respond: (Request) -> Response): GatewayHttp =
                OkHttpGatewayHttp(
                    http = clientResponding { request ->
                        sent += request
                        respond(request)
                    },
                    resolveEndpoint = { origin },
                    resolveAuthorization = { header to credential },
                )
        }

        val LEGS = listOf(
            Leg(
                name = "OAuth-gated remote (native PKCE bearer)",
                origin = "https://gateway.invalid",
                header = "Authorization",
                secret = "native-pkce-access-token",
                credential = "Bearer native-pkce-access-token",
            ),
            Leg(
                name = "SSH-tunneled loopback session token",
                origin = "http://127.0.0.1:41234",
                header = "X-Hermes-Session-Token",
                secret = "loopback-session-token",
            ),
            // Token mode accepts the same session-token header, but over a
            // Gateway the app reaches directly rather than through a forward —
            // a different origin resolution on the same credential shape.
            Leg(
                name = "token-mode gateway",
                origin = "https://gateway.invalid",
                header = "X-Hermes-Session-Token",
                secret = "token-mode-session-token",
            ),
        )

        /** hermes-agent @ 29112bef099274229cadff79cdff7bf7b99c4b77, middleware.py:145-163,356-373. */
        const val GATE_LAPSED_ENVELOPE =
            """{"error":"session_expired","detail":"Unauthorized",""" +
                """"reason":"invalid_or_expired_session","login_url":"/login"}"""

        const val GATE_NO_CREDENTIAL_ENVELOPE =
            """{"error":"unauthenticated","detail":"Unauthorized","reason":"no_cookie","login_url":"/login"}"""

        /** hermes-plugin-relay @ 563a8c846ab997dc965c20080787f46b4f644b29, plugin_api.py:85-106. */
        const val PLUGIN_AUTH_REQUIRED_ENVELOPE =
            """{"error":{"code":"auth_required","message":"Relay authorization is required","retryable":false}}"""

        const val CHANNELS_BODY =
            """{"channels":[{"id":"team/general","title":"General"}]}"""

        const val MESSAGE_ROW =
            """{"id":"m-2","channelId":"team/general","seq":8,"kind":"member_message","status":"sent",""" +
                """"sender":{"kind":"member","id":"u-me"},"body":{"text":"ahoy","format":"markdown"},""" +
                """"createdAt":"2026-08-26T01:00:00Z","updatedAt":"2026-08-26T01:00:00Z"}"""

        fun clientResponding(block: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> block(chain.request()) })
            .build()

        fun ok(request: Request, body: String): Response = respond(request, 200, body)

        fun unauthorized(request: Request, envelope: String): Response = respond(request, 401, envelope)

        fun respond(request: Request, code: Int, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Rejected")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
