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
        val http = RecordingGatewayHttp(
            SUCCESS_BODY(
                """{"channels":{"status":"ready","guidance":"Configure RELAY_IDE_OPERATOR_CLIENT_TOKEN"},""" +
                    """"harnesses":{"status":"ready","loginAvailable":true}}""",
            ),
        )
        val repository = RelayPluginRepository { http }

        val available = repository.availability() as RelayAvailability.Available
        assertEquals(RelayLaneState.READY, available.channels.state)
        assertNull(available.channels.message)
        assertEquals("Configure RELAY_IDE_OPERATOR_CLIENT_TOKEN", available.channels.guidance)
        assertEquals("api/plugins/hermes-plugin-relay/connection/status", http.requests.single().path)
        assertEquals("GET", http.requests.single().method)
        assertTrue(http.bodies.single().isEmpty())

        for ((wire, expected) in LANE_STATES) {
            val lane = RelayPluginRepository {
                RecordingGatewayHttp(SUCCESS_BODY("""{"channels":{"status":"$wire"}}"""))
            }.availability()
            assertEquals(expected, (lane as RelayAvailability.Available).channels.state)
        }
    }

    @Test
    fun `unavailable gateways answer with honest states`() = runTest {
        assertEquals(
            RelayAvailability.Missing,
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(404, "nope")) }
                .availability(),
        )
        assertEquals(
            RelayAvailability.SignInRequired,
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(401, "nope")) }
                .availability(),
        )
        assertEquals(
            RelayAvailability.SignInRequired,
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(403, "nope")) }
                .availability(),
        )
        assertEquals(RelayAvailability.GatewayUnreachable, RelayPluginRepository { null }.availability())
        assertEquals(
            RelayAvailability.GatewayUnreachable,
            RelayPluginRepository { RecordingGatewayHttp(GatewayHttpResult.Rejected(502, "boom")) }
                .availability(),
        )
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

        assertEquals("m-2", result.message?.id)
        assertEquals("ahoy", result.message?.text)
        assertEquals("cmid-1", result.message?.clientMessageId)

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

        assertTrue(result is RelayPostResult.Accepted && result.message?.format == RelayMessageFormat.TEXT)
    }

    @Test
    fun `post failures keep the status code and safe copy`() = runTest {
        val conflict = RelayPluginRepository {
            RecordingGatewayHttp(GatewayHttpResult.Rejected(409, "Relay reported a conflict."))
        }.post("c", "hi", RelayMessageFormat.TEXT, "id-1")
        assertEquals(409, (conflict as RelayPostResult.Failed).statusCode)
        assertEquals("Relay reported a conflict.", conflict.safeMessage)

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
    fun `locally invalid posts are refused before any network call`() = runTest {
        val http = RecordingGatewayHttp()
        val repository = RelayPluginRepository { http }

        assertTrue(repository.post("", "hi", RelayMessageFormat.TEXT, "id") is RelayPostResult.Failed)
        assertTrue(repository.post(" ", "hi", RelayMessageFormat.TEXT, "id") is RelayPostResult.Failed)
        assertTrue(repository.post("c", " ", RelayMessageFormat.TEXT, "id") is RelayPostResult.Failed)
        assertTrue(
            repository.post("c", "x".repeat(MAX_HISTORY_LIMIT * 4096), RelayMessageFormat.TEXT, "id")
                is RelayPostResult.Failed,
        )
        assertTrue(repository.post("c", "hi", RelayMessageFormat.TEXT, "") is RelayPostResult.Failed)
        assertTrue(http.requests.isEmpty())

        // Exactly at the server bound is allowed through.
        val edge = RelayPluginRepository {
            RecordingGatewayHttp(GatewayHttpResult.Rejected(500, "down"))
        }
        edge.post("c", "x".repeat(64 * 1024), RelayMessageFormat.TEXT, "ok-id")
    }

    @Test
    fun `reauthorize posts an empty body and reports readiness honestly`() = runTest {
        val ready = RecordingGatewayHttp(SUCCESS_BODY("""{"status":"ready"}"""))
        assertTrue(RelayPluginRepository { ready }.reauthorize())
        val request = ready.requests.single()
        assertEquals("POST", request.method)
        assertEquals("api/plugins/hermes-plugin-relay/connection/authorize", request.path)
        assertTrue(request.body?.contentLength() == 0L)

        assertFalse(
            RelayPluginRepository {
                RecordingGatewayHttp(SUCCESS_BODY("""{"status":"auth_required"}"""))
            }.reauthorize(),
        )
        assertFalse(RelayPluginRepository { null }.reauthorize())
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

/** Records every request and replays queued results; extra calls get `{}`. */
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
