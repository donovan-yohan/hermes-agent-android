package com.hermesagent.mobile.data.relay

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import java.util.ArrayDeque
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPluginRepositoryTest {
    @Test
    fun `availability probes the plugin namespace and maps every lane state`() = runTest {
        // The pinned envelope is flat and carries no guidance: this is exactly
        // the body `tests/test_plugin_api.py:103` asserts at 563a8c8.
        val http = RecordingGatewayHttp(SUCCESS_BODY("""{"status":"ready"}"""))
        val repository = RelayPluginRepository { http }

        val available = repository.availability() as RelayAvailability.Available
        assertEquals(RelayLaneState.READY, available.channels.state)
        assertNull(available.channels.message)
        assertNull(available.channels.guidance)
        assertEquals("api/plugins/hermes-plugin-relay/connection/status", http.requests.single().path)
        assertEquals("GET", http.requests.single().method)
        assertTrue(http.bodies.single().isEmpty())

        // Every lane state, and the lane's own message, in the pinned shape.
        for ((wire, expected) in LANE_STATES) {
            val lane = RelayPluginRepository {
                RecordingGatewayHttp(SUCCESS_BODY("""{"status":"$wire","message":"Relay is unavailable"}"""))
            }.availability() as RelayAvailability.Available
            assertEquals(expected, lane.channels.state)
            assertEquals("Relay is unavailable", lane.channels.message)
        }
    }

    @Test
    fun `a later nested lane envelope is read without breaking the pinned one`() = runTest {
        // Forward compatibility only: no plugin at the pin sends this, so it
        // must never be the shape the parser requires.
        val nested = RelayPluginRepository {
            RecordingGatewayHttp(
                SUCCESS_BODY(
                    """{"channels":{"status":"auth_required","message":"Channel authorization is required",""" +
                        """"guidance":"Configure the channel operator credential"},""" +
                        """"harnesses":{"status":"ready","loginAvailable":true}}""",
                ),
            )
        }.availability() as RelayAvailability.Available

        assertEquals(RelayLaneState.AUTH_REQUIRED, nested.channels.state)
        assertEquals("Channel authorization is required", nested.channels.message)
        assertEquals("Configure the channel operator credential", nested.channels.guidance)
    }

    @Test
    fun `unavailable gateways answer with honest states`() = runTest {
        assertEquals(
            RelayAvailability.Missing,
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(404, "nope")) }
                .availability(),
        )
        // A refusal that explains nothing is never assumed to be a lapsed
        // credential: an unexplained refusal must not spend a rotation.
        assertEquals(
            RelayAvailability.SignInRequired(RelaySignInReason.NoCredential),
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(401, "nope")) }
                .availability(),
        )
        assertEquals(
            RelayAvailability.SignInRequired(RelaySignInReason.NoCredential),
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(403, "nope")) }
                .availability(),
        )
        assertEquals(RelayAvailability.GatewayUnreachable, RelayPluginRepository { null }.availability())
        // A refusal that explains nothing stays the residual state.
        assertEquals(
            RelayAvailability.GatewayUnreachable,
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(502, "boom")) }
                .availability(),
        )
    }

    @Test
    fun `a refusal the plugin explains is classified from its own envelope`() = runTest {
        // The plugin wrote the envelope, so it is mounted and enabled: the two
        // upstream faults it distinguishes must not collapse into one state.
        assertEquals(
            RelayLaneState.OFFLINE,
            (probeRefusal(503, relayError("relay_unavailable", retryable = true))
                as RelayAvailability.Available).channels.state,
        )
        assertEquals(
            RelayLaneState.ERROR,
            (probeRefusal(502, relayError("relay_invalid_response", retryable = true))
                as RelayAvailability.Available).channels.state,
        )

        // A resource-shaped refusal cannot describe a connection probe, and a
        // 404 the plugin explains is not the runtime gate's missing plugin.
        assertEquals(
            RelayAvailability.Incompatible,
            probeRefusal(404, relayError("not_found", retryable = false)),
        )
        assertEquals(
            RelayAvailability.Incompatible,
            probeRefusal(500, relayError("a_code_this_build_has_never_seen", retryable = true)),
        )

        // Only the classification crosses the boundary; no envelope text does.
        val offline = probeRefusal(503, relayError("relay_unavailable", retryable = true))
        assertNull((offline as RelayAvailability.Available).channels.message)
        assertNull(offline.channels.guidance)
    }

    @Test
    fun `the refusing service decides the remedy, not the status code`() = runTest {
        // The Gateway's own gate, bearer presented and lapsed. Only this shape
        // may cost a rotation.
        assertEquals(
            RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired),
            probeRefusal(401, GATE_SESSION_EXPIRED_ENVELOPE),
        )
        assertEquals(
            RelayAvailability.SignInRequired(RelaySignInReason.SessionExpired),
            probeRefusal(401, """{"error":"unauthenticated","reason":"refresh_expired"}"""),
        )

        // Same gate, same status, nothing presented: nothing to rotate.
        assertEquals(
            RelayAvailability.SignInRequired(RelaySignInReason.NoCredential),
            probeRefusal(401, GATE_UNAUTHENTICATED_ENVELOPE),
        )
        assertEquals(
            RelayAvailability.SignInRequired(RelaySignInReason.NoCredential),
            probeRefusal(401, "not json at all"),
        )

        // The plugin's own auth_required is the *host's* Relay credential. No
        // sign-in on this device can supply it, so it is a lane state and never
        // a sign-in prompt.
        val lane = probeRefusal(401, relayError("auth_required", retryable = false))
        assertEquals(RelayLaneState.AUTH_REQUIRED, (lane as RelayAvailability.Available).channels.state)
    }

    @Test
    fun `unknown wire shapes fail closed as incompatible rather than guessing`() = runTest {
        assertEquals(
            RelayAvailability.Incompatible,
            RelayPluginRepository { RecordingGatewayHttp(SUCCESS_BODY("""{"channels":{"status":"warp"}}""")) }
                .availability(),
        )
        assertEquals(
            RelayAvailability.Incompatible,
            RelayPluginRepository { RecordingGatewayHttp(SUCCESS_BODY("""{"harnesses":{"status":"ready"}}""")) }
                .availability(),
        )
        assertEquals(
            RelayAvailability.Incompatible,
            RelayPluginRepository { RecordingGatewayHttp(SUCCESS_BODY("not json")) }.availability(),
        )
    }

    @Test
    fun `channel inventory parses projected rows and refuses malformed ones`() = runTest {
        val http = RecordingGatewayHttp(
            SUCCESS_BODY(
                """{"channels":[
                    {"id":"team/general","title":"General","kind":"standard","visibility":"public",
                     "archived":false,"latestSeq":41,"messageCount":12,"threadCount":2,
                     "lastMessage":{"id":"m-9","seq":41,"preview":"hello there","senderKind":"member",
                                    "status":"sent","createdAt":"2026-08-26T00:00:00Z",
                                    "senderDisplayName":"Ada"}},
                    {"id":"dm-1","title":"Direct"}
                ]}""",
            ),
        )

        val channels = RelayPluginRepository { http }.channels()
        assertEquals(listOf("team/general", "dm-1"), channels?.map { it.id })
        val general = channels!!.first()
        assertEquals("General", general.title)
        assertEquals("standard", general.kind)
        assertEquals("public", general.visibility)
        assertEquals(false, general.archived)
        assertEquals(41L, general.latestSeq)
        assertEquals(12L, general.messageCount)
        assertEquals(2L, general.threadCount)
        assertEquals("m-9", general.lastMessage?.id)
        assertEquals(41L, general.lastMessage?.seq)
        assertEquals("hello there", general.lastMessage?.preview)
        assertEquals("Ada", general.lastMessage?.senderDisplayName)
        val direct = channels[1]
        assertNull(direct.kind)
        assertNull(direct.latestSeq)
        assertNull(direct.lastMessage)

        // One malformed row poisons the whole snapshot so UI keeps prior data.
        assertNull(
            RelayPluginRepository {
                RecordingGatewayHttp(SUCCESS_BODY("""{"channels":[{"id":"x"}]}"""))
            }.channels(),
        )
        // A malformed preview poisons its row too. Dropping only the preview
        // would render a busy channel as an empty one.
        assertNull(
            RelayPluginRepository {
                RecordingGatewayHttp(
                    SUCCESS_BODY(
                        """{"channels":[{"id":"c","title":"C","lastMessage":{"id":"m","seq":1}}]}""",
                    ),
                )
            }.channels(),
        )
        assertNull(RelayPluginRepository { RecordingGatewayHttp(SUCCESS_BODY("{}")) }.channels())
        assertNull(RelayPluginRepository { RecordingGatewayHttp() }.channels())
    }

    @Test
    fun `history clamps limit encodes the channel segment and parses cursor truth`() = runTest {
        val http = RecordingGatewayHttp(
            SUCCESS_BODY(HISTORY_FIXTURE),
            SUCCESS_BODY(HISTORY_FIXTURE),
            SUCCESS_BODY(HISTORY_FIXTURE),
        )
        val repository = RelayPluginRepository { http }

        val history = repository.history("team/lobby")
        assertEquals(1, history?.messages?.size)
        val message = history!!.messages.first()
        assertEquals("m-1", message.id)
        assertEquals("team/lobby", message.channelId)
        assertEquals(7L, message.seq)
        assertEquals("assistant_turn", message.kind)
        assertEquals("sent", message.status)
        assertEquals("member", message.senderKind)
        assertEquals("u-1", message.senderId)
        assertEquals("Grace", message.senderDisplayName)
        assertEquals("hello world", message.text)
        assertEquals(RelayMessageFormat.MARKDOWN, message.format)
        assertNull(message.threadId)
        assertEquals(true, history.hasMore)
        assertEquals(10L, history.nextCursorBeforeSeq)
        assertEquals(4L, history.nextCursorAfterSeq)

        repository.history("team/lobby", limit = 7)
        repository.history("team/lobby", limit = 500)
        assertEquals(3, http.requests.size)
        assertEquals("50", http.requests[0].query["limit"])
        assertEquals("7", http.requests[1].query["limit"])
        assertEquals("50", http.requests[2].query["limit"])

        val encodedPaths = http.requests.map { it.path }
        assertTrue(encodedPaths.all { it == "api/plugins/hermes-plugin-relay/channels/team%2Flobby/messages" })
    }

    @Test
    fun `history without transport returns nothing instead of throwing`() = runTest {
        assertNull(RelayPluginRepository { null }.history("c"))
        assertNull(
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(500, "down")) }
                .history("c"),
        )
        assertNull(RelayPluginRepository { RecordingGatewayHttp() }.history("c"))
        // One malformed message poisons the whole window rather than handing a
        // transcript back with a silent hole in it.
        assertNull(
            RelayPluginRepository {
                RecordingGatewayHttp(SUCCESS_BODY("""{"messages":[{"id":"x"}]}"""))
            }.history("c"),
        )
        assertNull(
            RelayPluginRepository { RecordingGatewayHttp(SUCCESS_BODY("""{"hasMore":false}""")) }
                .history("c"),
        )
        // Invalid ids never reach the transport.
        val http = RecordingGatewayHttp(SUCCESS_BODY(HISTORY_FIXTURE))
        assertNull(RelayPluginRepository { http }.history(" "))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `post sends exactly the three-key body and parses the acknowledged row`() = runTest {
        val http = RecordingGatewayHttp(
            SUCCESS_BODY("""{"message":$MESSAGE_ROW}"""),
        )
        val result = RelayPluginRepository { http }.post(
            channelId = "team/general",
            text = "ahoy",
            format = RelayMessageFormat.MARKDOWN,
            clientMessageId = "cmid-1",
        ) as RelayPostResult.Accepted

        assertEquals("m-2", result.message.id)
        assertEquals("ahoy", result.message.text)
        assertEquals("cmid-1", result.message.clientMessageId)

        val sent = Json.parseToJsonElement(http.bodies.single()) as JsonObject
        assertEquals(
            buildJsonObject {
                put("text", "ahoy")
                put("format", "markdown")
                put("clientMessageId", "cmid-1")
            },
            sent,
        )
        assertEquals(
            "api/plugins/hermes-plugin-relay/channels/team%2Fgeneral/messages",
            http.requests.single().path,
        )
        assertEquals("POST", http.requests.single().method)
    }

    @Test
    fun `post parses a minimal acknowledged row`() = runTest {
        val result = RelayPluginRepository {
            RecordingGatewayHttp(SUCCESS_BODY("""{"message":{"id":"m","channelId":"c","seq":1,"kind":"k","status":"s","sender":{"kind":"member","id":"u"},"body":{"text":"t","format":"text"},"createdAt":"2026-08-26T00:00:00Z","updatedAt":"2026-08-26T00:00:00Z"}}"""))
        }.post("c", "t", RelayMessageFormat.TEXT, "id-1")

        assertTrue(result is RelayPostResult.Accepted && result.message.format == RelayMessageFormat.TEXT)

        // A 200 whose row is unusable is a contract violation, never an
        // acceptance with nothing in it.
        val partial = RelayPluginRepository {
            RecordingGatewayHttp(SUCCESS_BODY("""{"message":{"id":"m","channelId":"c"}}"""))
        }.post("c", "t", RelayMessageFormat.TEXT, "id-1")
        assertNull((partial as RelayPostResult.Failed).statusCode)
        assertFalse(partial.retryable)
    }

    @Test
    fun `post failures keep the status code and safe copy`() = runTest {
        val conflict = RelayPluginRepository {
            RecordingGatewayHttp(GatewayHttpResult.Rejected(409, "Relay reported a conflict."))
        }.post("c", "hi", RelayMessageFormat.TEXT, "id-1")
        assertEquals(409, (conflict as RelayPostResult.Failed).statusCode)
        assertEquals("Relay reported a conflict.", conflict.safeMessage)
        assertFalse(conflict.retryable)

        val down = RelayPluginRepository { null }.post("c", "hi", RelayMessageFormat.TEXT, "id-1")
        assertEquals(0, (down as RelayPostResult.Failed).statusCode)

        val broken = RelayPluginRepository { RecordingGatewayHttp(SUCCESS_BODY("{}")) }
            .post("c", "hi", RelayMessageFormat.TEXT, "id-1")
        assertNull((broken as RelayPostResult.Failed).statusCode)

        val unauthorized = RelayPluginRepository {
            RecordingGatewayHttp(GatewayHttpResult.Rejected(401, "Hermes did not accept this connection. Reconnect and try again."))
        }.post("c", "hi", RelayMessageFormat.TEXT, "id-1")
        assertEquals(401, (unauthorized as RelayPostResult.Failed).statusCode)
    }

    @Test
    fun `only Relay's own classification may authorise re-sending a post`() = runTest {
        val outage = postRefusal(503, relayError("relay_unavailable", retryable = true))
        assertEquals(503, outage.statusCode)
        assertTrue(outage.retryable)

        // A conflict is exactly-once working. Re-sending it is the one retry the
        // contract forbids, whatever the envelope's own flag claims.
        assertFalse(postRefusal(409, relayError("conflict", retryable = true)).retryable)

        assertFalse(postRefusal(401, relayError("auth_required", retryable = false)).retryable)
        // A refusal with nothing to read is never assumed retryable.
        assertFalse(postRefusal(500, "").retryable)
    }

    @Test
    fun `locally invalid posts are refused before any network call`() = runTest {
        val http = RecordingGatewayHttp()
        val repository = RelayPluginRepository { http }

        // Each local refusal mirrors the status the server answers with for the
        // same input (`dashboard/plugin_api.py:174-198` at 563a8c8), so a
        // caller learns one contract rather than two.
        assertEquals(400, repository.post("", "hi", RelayMessageFormat.TEXT, "id").failure().statusCode)
        assertEquals(400, repository.post(" ", "hi", RelayMessageFormat.TEXT, "id").failure().statusCode)
        assertEquals(400, repository.post("c", " ", RelayMessageFormat.TEXT, "id").failure().statusCode)
        assertEquals(
            413,
            repository.post("c", "x".repeat(MAX_TEXT_BYTES + 1), RelayMessageFormat.TEXT, "id")
                .failure().statusCode,
        )
        assertEquals(400, repository.post("c", "hi", RelayMessageFormat.TEXT, "").failure().statusCode)
        assertEquals(
            413,
            repository.post("c", "hi", RelayMessageFormat.TEXT, "x".repeat(513)).failure().statusCode,
        )
        assertTrue(http.requests.isEmpty())

        // Exactly at the server bound is allowed through — and must actually
        // reach the transport, or this asserts nothing.
        val edgeHttp = RecordingGatewayHttp(GatewayHttpResult.Rejected(500, "down"))
        RelayPluginRepository { edgeHttp }
            .post("c", "x".repeat(MAX_TEXT_BYTES), RelayMessageFormat.TEXT, "ok-id")
        assertEquals(1, edgeHttp.requests.size)
    }

    @Test
    fun `reauthorize posts an empty body and reports the lane state it got back`() = runTest {
        val ready = RecordingGatewayHttp(SUCCESS_BODY("""{"status":"ready"}"""))
        val authorized = RelayPluginRepository { ready }.reauthorize()
        assertEquals(RelayLaneState.READY, (authorized as RelayAvailability.Available).channels.state)
        val request = ready.requests.single()
        assertEquals("POST", request.method)
        assertEquals("api/plugins/hermes-plugin-relay/connection/authorize", request.path)
        assertTrue(request.body?.contentLength() == 0L)

        // Redeeming a grant that leaves the lane unauthorized is a different
        // outcome from never reaching the Gateway, and from the plugin being
        // absent. A bare boolean could not tell a caller which happened.
        val stillUnauthorized = RelayPluginRepository {
            RecordingGatewayHttp(
                SUCCESS_BODY("""{"status":"auth_required","message":"Relay authorization is required"}"""),
            )
        }.reauthorize()
        assertEquals(
            RelayLaneState.AUTH_REQUIRED,
            (stillUnauthorized as RelayAvailability.Available).channels.state,
        )
        assertEquals("Relay authorization is required", stillUnauthorized.channels.message)

        assertEquals(RelayAvailability.GatewayUnreachable, RelayPluginRepository { null }.reauthorize())
        assertEquals(
            RelayAvailability.Missing,
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(404, "gone")) }
                .reauthorize(),
        )
        assertEquals(
            RelayAvailability.Incompatible,
            RelayPluginRepository { RecordingGatewayHttp(SUCCESS_BODY("""{"status":"warp"}""")) }
                .reauthorize(),
        )
    }

    private companion object {
        val MESSAGE_ROW =
            """{"schemaVersion":1,"id":"m-2","channelId":"team/general","seq":8,"kind":"member_message",""" +
                """"status":"sent","sender":{"kind":"member","id":"u-me","displayName":"Me"},""" +
                """"body":{"text":"ahoy","format":"markdown"},"threadId":null,"parentMessageId":null,""" +
                """"createdAt":"2026-08-26T01:00:00Z","updatedAt":"2026-08-26T01:00:00Z",""" +
                """"clientMessageId":"cmid-1"}"""

        const val HISTORY_ROW =
            """{"schemaVersion":1,"id":"m-1","channelId":"team/lobby","seq":7,"kind":"assistant_turn",""" +
                """"status":"sent","sender":{"kind":"member","id":"u-1","displayName":"Grace"},""" +
                """"body":{"text":"hello world","format":"markdown"},"threadId":null,"parentMessageId":null,""" +
                """"createdAt":"2026-08-26T00:30:00Z","updatedAt":"2026-08-26T00:30:00Z","truncated":false}"""

        val HISTORY_FIXTURE =
            """{"messages":[$HISTORY_ROW],"hasMore":true,"nextCursor":{"beforeSeq":10,"afterSeq":4}}"""

        val LANE_STATES = listOf(
            "offline" to RelayLaneState.OFFLINE,
            "auth_required" to RelayLaneState.AUTH_REQUIRED,
            "error" to RelayLaneState.ERROR,
        )

        /** 200 response carrying [body]; mirrors GatewayHttpResult.Success ownership. */
        fun SUCCESS_BODY(body: String): GatewayHttpResult.Success =
            GatewayHttpResult.Success(200, body.toByteArray(Charsets.UTF_8))
    }
}

/**
 * One refusal carrying [envelope], the way the transport hands it over: the
 * status code and the refusing service's own explanation, never its raw text.
 */
private fun refusal(statusCode: Int, envelope: String) = GatewayHttpResult.Rejected(
    statusCode,
    "Hermes refused that Gateway request.",
    envelope.toByteArray(Charsets.UTF_8),
)

/**
 * Verbatim from the Gateway auth gate, hermes-agent @
 * f82f2dbabd9e66b714f2b4f8a40447fe0c13e732,
 * `hermes_cli/dashboard_auth/middleware.py:145-163` — a bearer that was
 * presented and did not verify (`:356-373`).
 */
private const val GATE_SESSION_EXPIRED_ENVELOPE =
    """{"error":"session_expired","detail":"Unauthorized",""" +
        """"reason":"invalid_or_expired_session","login_url":"/login?next=%2F"}"""

/** Same gate, nothing presented (`middleware.py:388`). */
private const val GATE_UNAUTHENTICATED_ENVELOPE =
    """{"error":"unauthenticated","detail":"Unauthorized",""" +
        """"reason":"no_cookie","login_url":"/login"}"""

/** The plugin's structured refusal envelope (`dashboard/plugin_api.py:85-88` at the pin). */
private fun relayError(code: String, retryable: Boolean): String =
    """{"error":{"code":"$code","message":"Relay rejected the request","retryable":$retryable}}"""

private suspend fun probeRefusal(statusCode: Int, envelope: String): RelayAvailability =
    RelayPluginRepository { RecordingGatewayHttp(refusal(statusCode, envelope)) }.availability()

private suspend fun postRefusal(statusCode: Int, envelope: String): RelayPostResult.Failed =
    RelayPluginRepository { RecordingGatewayHttp(refusal(statusCode, envelope)) }
        .post("c", "hi", RelayMessageFormat.TEXT, "id-1")
        .failure()

private fun RelayPostResult.failure(): RelayPostResult.Failed = this as RelayPostResult.Failed

/** Records every request and replays queued results; extra calls get an empty 200. */
private class RecordingGatewayHttp(private vararg val results: GatewayHttpResult) : GatewayHttp {
    val requests = mutableListOf<GatewayHttpRequest>()
    val bodies = mutableListOf<String>()
    private val queue = ArrayDeque(results.toList())

    override suspend fun execute(request: GatewayHttpRequest): GatewayHttpResult {
        requests.add(request)
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        bodies.add(buffer.readUtf8())
        return if (queue.isEmpty()) {
            GatewayHttpResult.Success(200, ByteArray(0))
        } else {
            queue.removeFirst()
        }
    }
}
