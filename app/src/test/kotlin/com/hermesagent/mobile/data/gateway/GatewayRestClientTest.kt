package com.hermesagent.mobile.data.gateway

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every wire fixture here is a shape the pinned Gateway actually produces:
 * hermes-agent @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`, with the
 * `path:line` that builds it named where it is used. A fixture without a
 * citation is a guess about the wire, and a parser tested against a guess
 * proves nothing.
 */
class GatewayRestClientTest {

    // -----------------------------------------------------------------------
    // Request shaping: profile scope, paging window, verbs, bounds.
    // -----------------------------------------------------------------------

    @Test
    fun `lists sessions on the route's own paging window with the profile in the query`() = runTest {
        // `{"sessions": [...], "total": N, "limit": L, "offset": O}` is exactly
        // what the route returns (hermes_cli/web_routers/sessions.py:159).
        val http = RecordingGatewayHttp(
            success(
                """{"sessions":[{"id":"a1","title":"Ship it","archived":false,"pinned":true},""" +
                    """{"id":"b2","title":"Later","archived":true,"pinned":false}],""" +
                    """"total":42,"limit":20,"offset":20}""",
            ),
        )

        val page = GatewayRestClient { http }.listSessions(
            limit = 20,
            offset = 20,
            minMessages = 1,
            archived = GatewaySessionArchivedFilter.Include,
            order = GatewaySessionOrder.Recent,
            profile = "work",
        ).valueOrFail()

        val request = http.requests.single()
        assertEquals("api/sessions", request.path)
        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals(
            mapOf(
                "limit" to "20",
                "offset" to "20",
                // `sessions.py:60,108`; Desktop's sidebar always asks for 1
                // (`apps/desktop/src/hermes.ts:501,514`).
                "min_messages" to "1",
                "archived" to "include",
                "order" to "recent",
                "profile" to "work",
            ),
            request.query,
        )
        // A refusal body this client would never read is a backend buffer it
        // would never wipe, so it does not ask for one.
        assertFalse(request.captureEnvelope)
        // Per-request, and bounded well under the transport's own default.
        assertEquals(15_000L, request.timeoutMillis)
        assertEquals(1024L * 1024L, request.maxResponseBytes)

        assertEquals(2, page.rows.size)
        assertEquals("a1", page.rows.first().string("id"))
        assertEquals(42L, page.total)
        assertEquals(20L, page.limit)
        assertEquals(20L, page.offset)
    }

    @Test
    fun `an unscoped list asks for no profile at all and defaults to Desktop's own page`() = runTest {
        val http = RecordingGatewayHttp(success("""{"sessions":[],"total":0,"limit":20,"offset":0}"""))

        GatewayRestClient { http }.listSessions().valueOrFail()

        val query = http.requests.single().query
        // Omitted, not empty: the route reads a blank `profile` as a name.
        assertFalse(query.containsKey("profile"))
        assertEquals("20", query["limit"])
        // Desktop's default, not the route's. `created` (`sessions.py:62`)
        // buries a long conversation the moment it auto-compresses onto a new
        // id; `recent` orders by activity across the chain, which is what
        // Desktop's own listSessions asks for (`hermes.ts:504`).
        assertEquals("recent", query["order"])
        // The route's own default for the filter Desktop overrides per surface.
        assertEquals("0", query["min_messages"])
        assertEquals("exclude", query["archived"])
    }

    @Test
    fun `refuses a negative message floor rather than letting the route clamp it`() = runTest {
        val http = RecordingGatewayHttp()

        GatewayRestClient { http }.listSessions(minMessages = -1).assertMalformed()

        assertEquals(0, http.requests.size)
    }

    @Test
    fun `reads a transcript page and the pagination the route reports`() = runTest {
        // `sessions.py:645-654`; `session_id` is the resolved id, which can
        // differ from the prefix the caller asked with (`:619-622`).
        val http = RecordingGatewayHttp(
            success(
                """{"session_id":"a1b2c3d4","messages":[{"role":"user","content":"hi"}],""" +
                    """"pagination":{"limit":250,"offset":0,"order":"latest","returned":1}}""",
            ),
        )

        val page = GatewayRestClient { http }.sessionMessages(
            sessionId = "a1b2",
            limit = 250,
            order = GatewayMessageOrder.Latest,
            includeCompacted = true,
            profile = "work",
        ).valueOrFail()

        val request = http.requests.single()
        assertEquals("api/sessions/a1b2/messages", request.path)
        assertEquals("GET", request.method)
        assertEquals(
            mapOf(
                "limit" to "250",
                "offset" to "0",
                "order" to "latest",
                "include_compacted" to "true",
                "profile" to "work",
            ),
            request.query,
        )
        // Sized to the 250 messages asked for, not to a flat worst case — and
        // 250 rows each able to carry a ceiling-sized `read_file` result runs
        // past what the transport reads at all, so it lands on that ceiling
        // (`GatewayHttp.kt` DEFAULT_MAX_RESPONSE_BYTES) rather than above it.
        assertEquals(DEFAULT_MAX_RESPONSE_BYTES, request.maxResponseBytes)

        assertEquals("a1b2c3d4", page.sessionId)
        assertEquals(1, page.messages.size)
        assertEquals(250L, page.limit)
        assertEquals("latest", page.order)
        // `returned` is not carried out: it is `len(messages)` by construction
        // (`sessions.py:652`), which the page already says.
        assertEquals(1, page.messages.size)
    }

    @Test
    fun `scopes a session update through the body because that route reads it there`() = runTest {
        // The PATCH handler takes no `profile` parameter and reads
        // `body.profile` (`sessions.py:686,696`, model `web_models.py:342`), so
        // a query-scoped update would silently edit the default profile's row.
        val http = RecordingGatewayHttp(success("""{"ok":true,"title":"Renamed","archived":true}"""))

        val updated = GatewayRestClient { http }.updateSession(
            sessionId = "a1b2",
            title = "Renamed",
            archived = true,
            profile = "work",
        ).valueOrFail()

        val request = http.requests.single()
        assertEquals("api/sessions/a1b2", request.path)
        assertEquals("PATCH", request.method)
        assertTrue(request.query.isEmpty())
        assertEquals(
            """{"title":"Renamed","archived":true,"profile":"work"}""",
            http.bodies.single(),
        )
        assertEquals("Renamed", updated.title)
        assertEquals(true, updated.archived)
        // A flag the request never asked about stays absent rather than false.
        assertNull(updated.pinned)
        assertNull(updated.unread)
    }

    @Test
    fun `deletes with no body, scoped by query, and reads the route's idempotent answer`() = runTest {
        // `sessions.py:657-658` scopes DELETE by query parameter, and an id it
        // cannot resolve is a success, not a 404 (`:674-676`).
        val http = RecordingGatewayHttp(success("""{"ok":true,"already_absent":true}"""))

        val deleted = GatewayRestClient { http }
            .deleteSession(sessionId = "a1b2", profile = "work")
            .valueOrFail()

        val request = http.requests.single()
        assertEquals("api/sessions/a1b2", request.path)
        assertEquals("DELETE", request.method)
        assertNull(request.body)
        assertEquals(mapOf("profile" to "work"), request.query)
        assertEquals(64L * 1024L, request.maxResponseBytes)
        assertTrue(deleted.alreadyAbsent)
    }

    // -----------------------------------------------------------------------
    // Refused before anything goes out.
    // -----------------------------------------------------------------------

    @Test
    fun `refuses a paging window before any request goes out`() = runTest {
        val http = RecordingGatewayHttp()
        val client = GatewayRestClient { http }

        // The ceiling is the route's: `limit` is capped `le=100` at
        // `sessions.py:58`, and the transcript route truncates to 500 (`:630`).
        client.listSessions(limit = 101).assertMalformed()
        client.sessionMessages("a1b2", limit = 501).assertMalformed()
        // The floor is this client's own. `limit=0` is accepted by both routes
        // (`:58` is `ge=0`, `:605` is `ge=0`) and answers with no rows, which no
        // surface can tell from an empty list — so a paging bug that computes
        // zero is refused here instead of rendering as "nothing to show".
        client.listSessions(limit = 0).assertMalformed()
        client.sessionMessages("a1b2", limit = 0).assertMalformed()
        client.listSessions(offset = -1).assertMalformed()
        client.sessionMessages("a1b2", offset = -1).assertMalformed()

        assertEquals(0, http.requests.size)
    }

    @Test
    fun `refuses an update body larger than this client will put on the wire`() = runTest {
        val http = RecordingGatewayHttp()

        // The Gateway owns what a valid title is (`sessions.py:711-716`); this
        // bound is the client's own, and it is measured on the encoded body
        // that would have been sent.
        GatewayRestClient { http }
            .updateSession("a1b2", title = "x".repeat(64 * 1024))
            .assertMalformed()

        assertEquals(0, http.requests.size)
    }

    @Test
    fun `refuses a session id that could address something other than a session`() = runTest {
        val http = RecordingGatewayHttp()
        val client = GatewayRestClient { http }

        // A URL builder resolves dot segments, so `..` on the destructive route
        // would address the collection instead of a member of it.
        val overlong = "a".repeat(201)
        for (id in listOf("..", ".", "", "   ", "a/b", "a b", "../../etc", "a?b=c", "a#b", overlong)) {
            client.deleteSession(id).assertMalformed()
            client.sessionMessages(id).assertMalformed()
            client.updateSession(id, title = "x").assertMalformed()
        }

        assertEquals(0, http.requests.size)
    }

    @Test
    fun `refuses a profile name that could address another database`() = runTest {
        val http = RecordingGatewayHttp()
        val client = GatewayRestClient { http }

        // A profile names a directory and a `state.db` on the host
        // (`sessions.py:96-97,103`), and what may name one is already decided
        // by `requireProfile` in this package — this client asks that question
        // rather than inventing a second answer to it.
        val overlong = "w".repeat(65)
        for (profile in listOf("", "  ", "../other", "work/../other", "work profile", overlong)) {
            client.listSessions(profile = profile).assertMalformed()
            client.deleteSession("a1b2", profile = profile).assertMalformed()
            client.updateSession("a1b2", archived = true, profile = profile).assertMalformed()
        }

        assertEquals(0, http.requests.size)
    }

    @Test
    fun `refuses an update that asks for nothing, under the status the route answers`() = runTest {
        val http = RecordingGatewayHttp()

        val failed = GatewayRestClient { http }.updateSession("a1b2")
            as GatewayRestResult.Failed

        // `sessions.py:701-710` answers 400 for exactly this request.
        assertEquals(400, failed.statusCode)
        assertEquals(MALFORMED_REQUEST_MESSAGE, failed.safeMessage)
        assertEquals(0, http.requests.size)
    }

    @Test
    fun `does not reach for a transport it does not have`() = runTest {
        val failed = GatewayRestClient { null }.deleteSession("a1b2") as GatewayRestResult.Failed

        assertEquals(0, failed.statusCode)
        assertEquals(RECONNECT_MESSAGE, failed.safeMessage)
    }

    // -----------------------------------------------------------------------
    // One hop, whatever happens.
    // -----------------------------------------------------------------------

    @Test
    fun `never retries a delete that failed in transit`() = runTest {
        // The transport's own sentence for a hop that never arrived — the exact
        // failure a retry loop would be tempted by, and the one case where
        // retrying a destructive verb on the caller's behalf is this client
        // deciding something it was not asked to decide.
        val http = RecordingGatewayHttp(
            GatewayHttpResult.Rejected(0, "The Gateway route could not be reached. Check the connection and try again."),
            success("""{"ok":true}"""),
        )

        val failed = GatewayRestClient { http }.deleteSession("a1b2") as GatewayRestResult.Failed

        assertEquals(1, http.requests.size)
        assertEquals(0, failed.statusCode)
        assertTrue(failed.safeMessage.contains("could not be reached"))
    }

    @Test
    fun `never retries a server-side delete failure either`() = runTest {
        val http = RecordingGatewayHttp(
            GatewayHttpResult.Rejected(503, "The Gateway could not complete that request. Try again."),
            success("""{"ok":true}"""),
        )

        val failed = GatewayRestClient { http }.deleteSession("a1b2") as GatewayRestResult.Failed

        assertEquals(1, http.requests.size)
        assertEquals(503, failed.statusCode)
    }

    // -----------------------------------------------------------------------
    // What comes back.
    // -----------------------------------------------------------------------

    @Test
    fun `passes a refusal's own status through so a caller can remember a missing route`() = runTest {
        val missing = GatewayRestClient {
            RecordingGatewayHttp(GatewayHttpResult.Rejected(404, "Hermes refused that Gateway request."))
        }.listSessions() as GatewayRestResult.Failed

        // An older Gateway that does not serve this route says so with a 404;
        // that is a capability to remember, not a failure to retry, so the code
        // survives the trip out.
        assertEquals(404, missing.statusCode)
        // The transport's existing vocabulary, unchanged.
        assertEquals("Hermes refused that Gateway request.", missing.safeMessage)
    }

    @Test
    fun `fails closed on a 2xx that is not the route's envelope`() = runTest {
        val bodies = listOf(
            "",
            "not json",
            "[]",
            """{"total":42}""",
            """{"sessions":"nope"}""",
            // One unreadable row poisons the page: a list that drops what it
            // could not read is a list that lies about what exists.
            """{"sessions":[{"session_id":"a1"},"nope"]}""",
        )
        for (body in bodies) {
            val failed = GatewayRestClient { RecordingGatewayHttp(success(body)) }
                .listSessions() as GatewayRestResult.Failed
            assertNull(failed.statusCode)
            assertEquals(UNUSABLE_RESPONSE_MESSAGE, failed.safeMessage)
        }

        // A write that did not say it worked did not work.
        val notOk = GatewayRestClient { RecordingGatewayHttp(success("""{"ok":false}""")) }
            .deleteSession("a1b2") as GatewayRestResult.Failed
        assertNull(notOk.statusCode)

        // The rename route always echoes the stored title (`sessions.py:723`).
        val noTitle = GatewayRestClient { RecordingGatewayHttp(success("""{"ok":true}""")) }
            .updateSession("a1b2", title = "x") as GatewayRestResult.Failed
        assertNull(noTitle.statusCode)
    }

    @Test
    fun `wipes the response buffer once the parse is done`() = runTest {
        val bytes = """{"sessions":[],"total":0,"limit":20,"offset":0}""".toByteArray(Charsets.UTF_8)
        val http = RecordingGatewayHttp(GatewayHttpResult.Success(200, bytes))

        val page = GatewayRestClient { http }.listSessions().valueOrFail()

        assertEquals(0L, page.total)
        // Ownership transferred, and this client does not leave a decoded
        // Gateway body lying in memory after it has read it.
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `an unparseable body is wiped too`() = runTest {
        val bytes = "not json".toByteArray(Charsets.UTF_8)
        val http = RecordingGatewayHttp(GatewayHttpResult.Success(200, bytes))

        GatewayRestClient { http }.listSessions() as GatewayRestResult.Failed

        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `carries no credential, host or backend text out of a real refusal`() = runTest {
        // The whole stack, not a fake: a 401 whose body is exactly the kind of
        // thing a backend writes and this app must never show.
        val transport = OkHttpGatewayHttp(
            http = clientResponding { request ->
                httpResponse(
                    request,
                    401,
                    """{"error":"session_expired","detail":"bearer sk-live-4242 rejected by """ +
                        """gateway.example (fingerprint SHA256:AAAA)"}""",
                )
            },
            resolveEndpoint = { "https://gateway.example/root/" },
            resolveAuthorization = { "Authorization" to "Bearer sk-live-4242" },
        )

        val failed = GatewayRestClient { transport }.deleteSession("a1b2") as GatewayRestResult.Failed

        assertEquals(401, failed.statusCode)
        for (secret in listOf("sk-live-4242", "gateway.example", "SHA256", "session_expired", "Bearer")) {
            assertFalse(failed.safeMessage.contains(secret))
        }
        // The transport's existing refusal vocabulary, unchanged.
        assertEquals("Hermes did not accept this connection. Reconnect and try again.", failed.safeMessage)
    }

    @Test
    fun `a one-message page can hold the largest row the host will emit`() = runTest {
        // The widest single tool result the pinned host produces is a
        // `read_file` at `file_read_max_chars` = 100,000 characters
        // (`hermes_cli/config_defaults.py:592`, `tools/file_tools.py:65`).
        // Characters are not bytes — four per character in UTF-8, six under
        // JSON escaping — so a bound sized to the character count refuses a
        // legitimate page *after* the host has already run the query.
        val ceilingRow = """{"role":"tool","name":"read_file","content":"${"y".repeat(READ_FILE_MAX_CHARS.toInt())}"}"""
        val body = """{"session_id":"a1b2","messages":[$ceilingRow],"pagination":{"limit":1,"offset":0,"order":"latest"}}"""
        val http = RecordingGatewayHttp(success(body))

        val page = GatewayRestClient { http }.sessionMessages("a1b2", limit = 1).valueOrFail()

        assertEquals(1, page.messages.size)
        val bound = http.requests.single().maxResponseBytes
        // The floor is a whole row, and it is *derived*, not fitted: the host's
        // own character cap times the worst a character can cost on the wire.
        // Asserting the product rather than a number someone chose is what
        // keeps the two honest — a host that raises `file_read_max_chars` moves
        // this bound with it instead of quietly outgrowing it.
        assertEquals(READ_FILE_MAX_CHARS * MAX_BYTES_PER_CHAR, bound)
        // Which lands at ~586 KiB, above the 512 KiB those 100,000 characters
        // already need before JSON escaping is counted at all — and above this
        // body, measured.
        assertTrue(bound >= 512L * 1024L)
        assertTrue(bound >= body.toByteArray(Charsets.UTF_8).size.toLong())

        // The per-page ceiling stays bounded — limit × per-row — until the
        // transport's own default takes over. At 24 MiB over ~586 KiB a row
        // that happens from the 42nd: 41 is the largest page still sized to
        // what it asked for, and every larger one, `MAX_MESSAGE_PAGE` included,
        // shares that single ceiling.
        suspend fun boundFor(limit: Int): Long {
            val probe = RecordingGatewayHttp(success("""{"session_id":"a1b2","messages":[]}"""))
            GatewayRestClient { probe }.sessionMessages("a1b2", limit = limit).valueOrFail()
            return probe.requests.single().maxResponseBytes
        }
        assertEquals(41L * bound, boundFor(41))
        assertEquals(DEFAULT_MAX_RESPONSE_BYTES, boundFor(42))
    }

    @Test
    fun `reads a string field as a string, never as a coerced number`() = runTest {
        // The shared accessor in this package (`GatewayRpc.kt:310`) hands back
        // any primitive's content, so `42` would arrive as `"42"` — and for
        // `session_id` that is an id this client would then put in a URL path.
        val numericId = GatewayRestClient {
            RecordingGatewayHttp(success("""{"session_id":7,"messages":[]}"""))
        }.sessionMessages("a1b2") as GatewayRestResult.Failed
        assertNull(numericId.statusCode)
        assertEquals(UNUSABLE_RESPONSE_MESSAGE, numericId.safeMessage)

        val numericTitle = GatewayRestClient {
            RecordingGatewayHttp(success("""{"ok":true,"title":42}"""))
        }.updateSession("a1b2", title = "x") as GatewayRestResult.Failed
        assertNull(numericTitle.statusCode)

        // The success gate gets the same treatment: a quoted "true" is not one.
        val quotedOk = GatewayRestClient {
            RecordingGatewayHttp(success("""{"ok":"true","already_absent":false}"""))
        }.deleteSession("a1b2") as GatewayRestResult.Failed
        assertNull(quotedOk.statusCode)
    }

    @Test
    fun `an empty title is a rename that clears, not a request that asks for nothing`() = runTest {
        // The route stores what it is given and echoes the stored title back,
        // empty string included (`sessions.py:711-716,723`). An empty string is
        // a value; only *no* field at all is "nothing to update" (`:701-710`).
        val http = RecordingGatewayHttp(success("""{"ok":true,"title":""}"""))

        val update = GatewayRestClient { http }.updateSession("a1b2", title = "").valueOrFail()

        assertEquals("""{"title":""}""", http.bodies.single())
        assertEquals("", update.title)
        // Nothing else was asked for, so nothing else is reported.
        assertNull(update.archived)
        assertNull(update.pinned)
        assertNull(update.unread)
    }

    @Test
    fun `speaks the routes over the real transport, path and query included`() = runTest {
        var captured: Request? = null
        val transport = OkHttpGatewayHttp(
            http = clientResponding { request ->
                captured = request
                httpResponse(request, 200, """{"ok":true}""")
            },
            resolveEndpoint = { "https://gateway.example/root/" },
            resolveAuthorization = { "Authorization" to "Bearer token" },
        )

        GatewayRestClient { transport }.deleteSession("a1b2", profile = "work").valueOrFail()

        assertEquals("DELETE", captured?.method)
        assertEquals("/root/api/sessions/a1b2", captured?.url?.encodedPath)
        assertEquals("work", captured?.url?.queryParameter("profile"))
        assertNull(captured?.body)
    }
}

// `READ_FILE_MAX_CHARS` and `MAX_BYTES_PER_CHAR` are the client's own
// (`GatewayRestClient.kt`), deliberately not restated here: the bound under
// test is their product, and a second copy of either would let the two drift
// apart while the assertion stayed green.

private fun <T> GatewayRestResult<T>.valueOrFail(): T = when (this) {
    is GatewayRestResult.Success -> value
    is GatewayRestResult.Failed -> throw AssertionError("expected success, got $statusCode $safeMessage")
}

private fun <T> GatewayRestResult<T>.assertMalformed() {
    val failed = this as GatewayRestResult.Failed
    assertNull(failed.statusCode)
    assertEquals(MALFORMED_REQUEST_MESSAGE, failed.safeMessage)
}

private fun success(body: String): GatewayHttpResult =
    GatewayHttpResult.Success(200, body.toByteArray(Charsets.UTF_8))

private fun clientResponding(block: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(Interceptor { chain -> block(chain.request()) })
    .build()

private fun httpResponse(request: Request, code: Int, body: String): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message(if (code in 200..299) "OK" else "Rejected")
    .body(body.toResponseBody("application/json".toMediaType()))
    .build()
