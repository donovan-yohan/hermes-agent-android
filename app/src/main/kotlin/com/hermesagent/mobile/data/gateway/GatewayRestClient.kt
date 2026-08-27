package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The verbs this client may put on the wire.
 *
 * A typed verb rather than a string at the one call site that can delete
 * something: [GatewayHttp] takes a method name, and a method name is a value a
 * caller can mistype. Nothing outside this file names a verb, so the set of
 * things this client can do to a Gateway is this declaration.
 */
internal enum class GatewayRestVerb { GET, PATCH, DELETE }

/**
 * `archived` on the session list: hide soft-archived sessions, return only
 * them, or return both (hermes-agent @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`,
 * `hermes_cli/web_routers/sessions.py:61,72-75`; the route 400s anything else
 * at `:85-89`).
 */
enum class GatewaySessionArchivedFilter(val wire: String) {
    Exclude("exclude"),
    Only("only"),
    Include("include"),
}

/**
 * `order` on the session list: by original start time, or by latest activity
 * across the compression chain (`sessions.py:62,77-80`; refused at `:90-94`).
 */
enum class GatewaySessionOrder(val wire: String) {
    Created("created"),
    Recent("recent"),
}

/** `order` on the transcript route (`sessions.py:607,610-614`). */
enum class GatewayMessageOrder(val wire: String) {
    Oldest("oldest"),
    Latest("latest"),
}

/**
 * One answered REST call.
 *
 * [Failed.statusCode] mirrors the failing hop the way the rest of this app
 * already spells it:
 *
 * - `0` — no authenticated route reached the Gateway at all: no transport, no
 *   endpoint, no credential, or a connection that never completed. Nothing ran
 *   on the host, which is what makes a *deliberate* second attempt safe even
 *   for a destructive verb.
 * - a real HTTP code — the Gateway's own answer, or the status this client
 *   mirrors when it refuses locally what that route would have refused. A 2xx
 *   here means the route answered and the answer overran the bound this call
 *   asked for; the request did reach the host and did whatever it does
 *   (`GatewayHttp.kt` [GatewayHttpResult.Rejected]).
 * - `null` — there is no status to report: either nothing was sent, or a 2xx
 *   answer did not match the pinned contract. [Failed.safeMessage] tells those
 *   two apart.
 *
 * Callers need the difference — a `404` is how an older Gateway says it does
 * not serve a route, which is a capability to remember rather than a failure to
 * retry.
 *
 * [Failed.safeMessage] is the only thing a surface may show, and it is always
 * either this client's own sentence or the transport's; no byte the Gateway
 * wrote is ever carried out of here.
 */
sealed interface GatewayRestResult<out T> {
    data class Success<out T>(val value: T) : GatewayRestResult<T>

    data class Failed(val statusCode: Int?, val safeMessage: String) : GatewayRestResult<Nothing>
}

/**
 * One page of the session list.
 *
 * [rows] stay as parsed JSON objects on purpose: the row contract — archived,
 * pinned, unread, cwd, branch, model, token and cost fields — belongs to the
 * session model, not to the transport, and inventing a row type here would
 * mean two shapes to keep in step. What this client does own is the envelope:
 * a body without a `sessions` array, or with an element that is not an object,
 * fails the whole page rather than rendering a half-truth.
 *
 * Envelope shape: `{"sessions": [...], "total": N, "limit": L, "offset": O}`
 * (`sessions.py:159` @ `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`).
 */
data class GatewaySessionPage(
    val rows: List<JsonObject>,
    val total: Long?,
    val limit: Long?,
    val offset: Long?,
)

/**
 * One page of a transcript. [messages] stay as JSON objects for the same
 * reason [GatewaySessionPage.rows] do.
 *
 * Envelope shape: `{"session_id": …, "messages": [...], "pagination":
 * {"limit","offset","order","returned"}}` (`sessions.py:645-654` @ the pin).
 * `session_id` is the *resolved* id — the route accepts a unique prefix and
 * follows the resume chain (`:619-622`) — so a caller that paged by prefix
 * learns the real id here.
 *
 * What is carried out of `pagination` is what the *route* decided, which can
 * differ from what the caller asked for: it clamps `limit` to 500 and picks an
 * `order` when none was given (`:626-630,651`). Its `returned` is not carried,
 * because it is `len(messages)` by construction (`:652`) — a second copy of
 * something [messages] already says.
 */
data class GatewaySessionMessagePage(
    val sessionId: String,
    val messages: List<JsonObject>,
    val limit: Long?,
    val offset: Long?,
    val order: String?,
)

/**
 * What a session update actually changed. Only the fields the request asked
 * for come back (`sessions.py:723-730` @ the pin), so an absent flag here means
 * "not requested", never "false".
 */
data class GatewaySessionUpdate(
    val title: String,
    val archived: Boolean?,
    val pinned: Boolean?,
    val unread: Boolean?,
)

/**
 * A completed session delete. [alreadyAbsent] is the route's own answer for an
 * id it could not resolve (`sessions.py:674-676` @ the pin): DELETE's contract
 * there is "ensure it is gone", so a missing row is a success, not a 404.
 */
data class GatewaySessionDeletion(val alreadyAbsent: Boolean)

/**
 * Authenticated REST client for the Gateway's session routes.
 *
 * It owns request shaping and fail-closed parsing; it owns no credential. The
 * transport it borrows through [http] is the connection-owned [GatewayHttp],
 * which resolves the active leg's credential itself — a remote bearer or the
 * SSH loopback session token — so nothing here ever sees a token, a URL origin
 * or a ticket (`GatewayHttp.kt:71-76`). Borrowing the transport per call rather
 * than holding one also means a client built before a reconnect cannot keep
 * speaking to the connection that has since gone away.
 *
 * Three rules this client keeps that the transport cannot:
 *
 * - **Refuse before requesting.** A path segment or query value this client
 *   cannot vouch for never reaches the wire. Session ids and profile names go
 *   into a URL and select a row and a database on the host, so they are
 *   matched against an allowlist and refused — never escaped, never trimmed
 *   into something that resolves to a different resource.
 * - **One hop per call.** There is no retry anywhere in here, least of all on
 *   [deleteSession]. An implicit retry makes the number of times a destructive
 *   request ran unknowable to the caller that asked for it; the caller owns the
 *   row, so the caller owns the decision to ask again.
 * - **Nothing the Gateway wrote is shown.** Failures carry the transport's
 *   fixed sentence or this client's own. Desktop summarises a backend error and
 *   falls back when it is long or unreadable
 *   (`apps/desktop/src/store/notifications.ts:142,147-155` @ the pin); this
 *   goes further and never carries backend text out at all, because on this app
 *   a response body can hold a token, a host name or a fingerprint.
 */
class GatewayRestClient(private val http: () -> GatewayHttp?) {

    /**
     * One page of sessions from `GET /api/sessions` (`sessions.py:53` @ the
     * pin).
     *
     * [limit] and [offset] are refused rather than clamped when they fall
     * outside the window: clamping would let a caller's broken paging
     * arithmetic silently read a page it did not ask for. The ceiling is the
     * route's (`limit` is capped `le=100` at `:58`, so one request cannot drag
     * every row out of SQLite); the floor is this client's own — the route
     * accepts `limit=0` and answers with a total and no rows, which is
     * indistinguishable from an empty list at every surface that would call
     * this.
     *
     * [minMessages] drops conversations with fewer than N persisted messages
     * (`:60,108`). Desktop's sidebar always asks for `1`, because a hard
     * replace of its in-memory list would otherwise make a chat that is
     * mid-first-response vanish the moment any other chat finishes
     * (`apps/desktop/src/hermes.ts:501-517`, reasoned at
     * `apps/desktop/src/store/session.ts:379-386`). The default here is the
     * route's own `0` rather than Desktop's `1`: what a caller should ask for
     * depends on whether its list can evict, and that is the caller's fact to
     * know, not this client's to assume.
     *
     * `order` defaults to `recent` because that is what Desktop's own
     * `listSessions` defaults to (`hermes.ts:504`), not what the route defaults
     * to (`created`, `:62`). A sidebar ordered by creation buries a
     * long-running conversation the moment it auto-compresses onto a fresh id;
     * `recent` orders by activity across the compression chain (`:77-80`).
     *
     * Note for callers: this GET is not read-only on the host. Auto-archive
     * runs on this path (`:99-102`) and can retire rows nobody touched, so a
     * row that stops appearing is not evidence that it was deleted.
     */
    suspend fun listSessions(
        limit: Int = DEFAULT_SESSION_PAGE,
        offset: Int = 0,
        minMessages: Int = 0,
        archived: GatewaySessionArchivedFilter = GatewaySessionArchivedFilter.Exclude,
        order: GatewaySessionOrder = GatewaySessionOrder.Recent,
        profile: String? = null,
    ): GatewayRestResult<GatewaySessionPage> {
        if (limit !in 1..MAX_SESSION_PAGE) return malformed()
        if (offset < 0) return malformed()
        // The route clamps a negative `min_messages` to 0 itself (`:108`).
        // Refusing instead keeps the same rule this client applies to `limit`
        // and `offset`: a caller's broken arithmetic must not silently read a
        // page it did not ask for.
        if (minMessages < 0) return malformed()
        val scope = scopeQuery(profile) ?: return malformed()
        return send(
            path = SESSIONS_PATH,
            verb = GatewayRestVerb.GET,
            query = buildMap {
                put("limit", limit.toString())
                put("offset", offset.toString())
                put("min_messages", minMessages.toString())
                put("archived", archived.wire)
                put("order", order.wire)
                putAll(scope)
            },
            timeoutMillis = LIST_TIMEOUT_MILLIS,
            maxResponseBytes = LIST_MAX_RESPONSE_BYTES,
            parse = ::parseSessionPage,
        )
    }

    /**
     * One page of a transcript from `GET /api/sessions/{id}/messages`
     * (`sessions.py:601` @ the pin).
     *
     * An omitted [limit] takes the route's own default page: the latest 500
     * messages in chronological order (`:626-630`). A [limit] past that 500-row
     * ceiling is refused here rather than quietly truncated by the host, and
     * `0` is refused for the same reason it is on the session list.
     */
    suspend fun sessionMessages(
        sessionId: String,
        limit: Int? = null,
        offset: Int = 0,
        order: GatewayMessageOrder? = null,
        includeCompacted: Boolean = false,
        profile: String? = null,
    ): GatewayRestResult<GatewaySessionMessagePage> {
        val id = safeSessionId(sessionId) ?: return malformed()
        if (limit != null && limit !in 1..MAX_MESSAGE_PAGE) return malformed()
        if (offset < 0) return malformed()
        val scope = scopeQuery(profile) ?: return malformed()
        return send(
            path = "$SESSIONS_PATH/$id/messages",
            verb = GatewayRestVerb.GET,
            query = buildMap {
                limit?.let { put("limit", it.toString()) }
                put("offset", offset.toString())
                order?.let { put("order", it.wire) }
                if (includeCompacted) put("include_compacted", "true")
                putAll(scope)
            },
            timeoutMillis = MESSAGES_TIMEOUT_MILLIS,
            maxResponseBytes = messagesResponseBound(limit),
            parse = ::parseMessagePage,
        )
    }

    /**
     * Rename, archive, pin or mark a session unread through
     * `PATCH /api/sessions/{id}` (`sessions.py:685` @ the pin).
     *
     * This is the route that scopes itself through the **body**, not the query:
     * its handler reads `body.profile` and takes no `profile` parameter
     * (`:686,696`, model at `hermes_cli/web_models.py:330-342`). A profile sent
     * as a query parameter here would be ignored and the edit would land on the
     * default profile's session — which is why scoping is a property of each
     * route in this client rather than one shared rule.
     *
     * A request that asks for nothing is refused before it is sent, under the
     * status the route itself answers for it (`:701-710`).
     */
    suspend fun updateSession(
        sessionId: String,
        title: String? = null,
        archived: Boolean? = null,
        pinned: Boolean? = null,
        unread: Boolean? = null,
        profile: String? = null,
    ): GatewayRestResult<GatewaySessionUpdate> {
        val id = safeSessionId(sessionId) ?: return malformed()
        if (title == null && archived == null && pinned == null && unread == null) {
            return GatewayRestResult.Failed(NOTHING_TO_UPDATE_STATUS, MALFORMED_REQUEST_MESSAGE)
        }
        val scope = profile?.let { safeProfile(it) ?: return malformed() }
        // Encoded once and measured before it is sent, so the bytes that were
        // bounded are the bytes that go on the wire. The bound is this client's
        // own: the Gateway owns what a valid title is (`:711-716`), but a
        // shared authenticated transport should not carry an unbounded caller
        // string, and no session title is 64 KiB.
        val encoded = buildJsonObject {
            title?.let { put("title", it) }
            archived?.let { put("archived", it) }
            pinned?.let { put("pinned", it) }
            unread?.let { put("unread", it) }
            scope?.let { put("profile", it) }
        }.toString().toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_UPDATE_BODY_BYTES) return malformed()
        return send(
            path = "$SESSIONS_PATH/$id",
            verb = GatewayRestVerb.PATCH,
            query = emptyMap(),
            body = encoded.toRequestBody(JSON_MEDIA_TYPE),
            timeoutMillis = WRITE_TIMEOUT_MILLIS,
            maxResponseBytes = ACK_MAX_RESPONSE_BYTES,
            parse = ::parseSessionUpdate,
        )
    }

    /**
     * Delete one session through `DELETE /api/sessions/{id}`
     * (`sessions.py:657` @ the pin), scoped by query parameter (`:658`).
     *
     * Sent exactly once. The route is idempotent — an id it cannot resolve
     * answers `already_absent` rather than 404 (`:674-676`) — so a *deliberate*
     * second attempt by a caller is safe; what this client will not do is
     * decide on its own that a delete should run again.
     */
    suspend fun deleteSession(
        sessionId: String,
        profile: String? = null,
    ): GatewayRestResult<GatewaySessionDeletion> {
        val id = safeSessionId(sessionId) ?: return malformed()
        val scope = scopeQuery(profile) ?: return malformed()
        return send(
            path = "$SESSIONS_PATH/$id",
            verb = GatewayRestVerb.DELETE,
            query = scope,
            timeoutMillis = WRITE_TIMEOUT_MILLIS,
            maxResponseBytes = ACK_MAX_RESPONSE_BYTES,
            parse = ::parseSessionDeletion,
        )
    }

    /**
     * The single hop every helper above makes.
     *
     * No retry, no fallback verb, no second endpoint: one request, one answer.
     * The response buffer is consumed through [consumeBody], which wipes it
     * whether the parse succeeded or threw, so a decoded Gateway body never
     * outlives the call that asked for it. [GatewayHttpRequest.captureEnvelope]
     * stays off — this client classifies refusals by status code alone, and
     * asking for a refusal body it would not read would mean holding a
     * backend-authored buffer it never wipes.
     */
    private suspend fun <T> send(
        path: String,
        verb: GatewayRestVerb,
        query: Map<String, String>,
        body: RequestBody? = null,
        timeoutMillis: Long,
        maxResponseBytes: Long,
        parse: (ByteArray) -> T?,
    ): GatewayRestResult<T> = withContext(Dispatchers.IO) {
        val transport = http()
            ?: return@withContext GatewayRestResult.Failed(0, RECONNECT_MESSAGE)
        val request = GatewayHttpRequest(
            path = path,
            method = verb.name,
            body = body,
            timeoutMillis = timeoutMillis,
            query = query,
            maxResponseBytes = maxResponseBytes,
        )
        when (val result = transport.execute(request)) {
            is GatewayHttpResult.Rejected ->
                GatewayRestResult.Failed(result.statusCode, result.safeMessage)

            is GatewayHttpResult.Success -> result.consumeBody(parse)
                ?.let { GatewayRestResult.Success(it) }
                ?: GatewayRestResult.Failed(null, UNUSABLE_RESPONSE_MESSAGE)
        }
    }
}

// ---------------------------------------------------------------------------
// Local refusals — nothing below sends anything.
// ---------------------------------------------------------------------------

private fun <T> malformed(): GatewayRestResult<T> =
    GatewayRestResult.Failed(null, MALFORMED_REQUEST_MESSAGE)

/**
 * The `profile` query parameter, or nothing when the caller wants the
 * connection's current profile. Null return means the name was refused.
 */
private fun scopeQuery(profile: String?): Map<String, String>? {
    if (profile == null) return emptyMap()
    val safe = safeProfile(profile) ?: return null
    return mapOf("profile" to safe)
}

/**
 * A session id this client is willing to put in a path.
 *
 * An allowlist, and deliberately not an escaper: the id selects the row a
 * DELETE removes, so anything this build cannot vouch for is refused rather
 * than encoded into something that resolves elsewhere. Requiring the first
 * character to be alphanumeric is what makes `.` and `..` impossible — a URL
 * builder resolves dot segments, so an id of `..` would otherwise address the
 * collection instead of a member of it.
 */
private fun safeSessionId(raw: String): String? =
    raw.takeIf { it.length <= MAX_ID_LENGTH && SAFE_ID.matches(it) }

/**
 * A profile name this client is willing to send.
 *
 * The grammar is not this file's to invent: [requireProfile] already defines
 * what a Hermes profile name is for the process this app starts, and a name
 * that cannot name a profile there cannot name one here either. Read the same
 * way the settings field reads it (`ui/ssh/SshViewModel.kt:129`) — as a
 * question rather than an assertion, because a caller passing an unusable
 * scope is a request to refuse, not a crash.
 */
private fun safeProfile(raw: String): String? =
    raw.takeIf { runCatching { requireProfile(it) }.isSuccess }

// ---------------------------------------------------------------------------
// Parsing — fail-closed on the pinned envelopes. A body that is not the shape
// the route documents yields null, which the caller reads as "keep what you
// had" rather than as data.
// ---------------------------------------------------------------------------

private fun parseSessionPage(bytes: ByteArray): GatewaySessionPage? {
    val root = parseObject(bytes) ?: return null
    return GatewaySessionPage(
        rows = root.objectArray("sessions") ?: return null,
        total = root.number("total"),
        limit = root.number("limit"),
        offset = root.number("offset"),
    )
}

private fun parseMessagePage(bytes: ByteArray): GatewaySessionMessagePage? {
    val root = parseObject(bytes) ?: return null
    val sessionId = root.jsonString("session_id") ?: return null
    val messages = root.objectArray("messages") ?: return null
    val pagination = root.child("pagination")
    return GatewaySessionMessagePage(
        sessionId = sessionId,
        messages = messages,
        limit = pagination?.number("limit"),
        offset = pagination?.number("offset"),
        order = pagination?.jsonString("order"),
    )
}

private fun parseSessionUpdate(bytes: ByteArray): GatewaySessionUpdate? {
    val root = parseObject(bytes) ?: return null
    if (root.boolean("ok") != true) return null
    return GatewaySessionUpdate(
        // The route always echoes the stored title, empty string included
        // (`sessions.py:723` @ the pin); an answer without it is not that
        // route's answer.
        title = root.jsonString("title") ?: return null,
        archived = root.boolean("archived"),
        pinned = root.boolean("pinned"),
        unread = root.boolean("unread"),
    )
}

private fun parseSessionDeletion(bytes: ByteArray): GatewaySessionDeletion? {
    val root = parseObject(bytes) ?: return null
    if (root.boolean("ok") != true) return null
    return GatewaySessionDeletion(alreadyAbsent = root.boolean("already_absent") == true)
}

private fun parseObject(bytes: ByteArray): JsonObject? =
    runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()

private fun JsonObject.number(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject

/**
 * A named array of objects, or null if it is absent or holds anything else.
 *
 * One unreadable element poisons the whole array: a page that silently drops
 * the rows it could not read is a page that lies about what exists.
 */
private fun JsonObject.objectArray(name: String): List<JsonObject>? {
    val rows = this[name] as? JsonArray ?: return null
    val parsed = ArrayList<JsonObject>(rows.size)
    for (row in rows) {
        parsed.add(row as? JsonObject ?: return null)
    }
    return parsed
}

private const val SESSIONS_PATH = "api/sessions"

/** The route's own page cap; an unbounded limit is a query it refuses. */
internal const val MAX_SESSION_PAGE = 100
private const val DEFAULT_SESSION_PAGE = 20

/** The transcript route truncates to 500 rows itself (`sessions.py:630`). */
private const val MAX_MESSAGE_PAGE = 500

private const val MAX_ID_LENGTH = 200
private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

private const val NOTHING_TO_UPDATE_STATUS = 400
private const val MAX_UPDATE_BODY_BYTES = 64 * 1024

private const val LIST_TIMEOUT_MILLIS = 15_000L
private const val MESSAGES_TIMEOUT_MILLIS = 30_000L
private const val WRITE_TIMEOUT_MILLIS = 15_000L

/**
 * A list page is 100 rows with the payload-dominating fields already stripped
 * server-side (`sessions.py:126-129,157-158`), and an ack is one line. Both are
 * bounds this client would rather fail on than hold.
 */
private const val LIST_MAX_RESPONSE_BYTES = 1024L * 1024L
private const val ACK_MAX_RESPONSE_BYTES = 64L * 1024L

/**
 * A transcript page cannot take a flat bound: one message can carry a whole
 * tool result, so 500 of them is tens of megabytes while fifty is a few. Sizing
 * the bound to the page the caller asked for is what keeps a legitimate page
 * from being refused after the host has already paid for it, without standing
 * ready to hold a page nobody asked for.
 *
 * The floor is one whole row, not an ack: a bound that cannot hold the largest
 * single message the host will emit refuses a legitimate one-message page after
 * the query has already run, which is the worst of both — the cost is paid and
 * the data is thrown away. The ceiling is the transport's own default: past
 * that, the read never happens anyway.
 */
private fun messagesResponseBound(limit: Int?): Long =
    ((limit ?: MAX_MESSAGE_PAGE).toLong() * BYTES_PER_MESSAGE)
        .coerceIn(BYTES_PER_MESSAGE, DEFAULT_MAX_RESPONSE_BYTES)

/**
 * One message row at the largest the pinned host will emit.
 *
 * Sized from the widest tool result, not the narrowest: `read_file` returns up
 * to `file_read_max_chars` = 100,000 **characters** per call
 * (`hermes_cli/config_defaults.py:566-569`, `tools/file_tools.py:65` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`), which is twice
 * `tool_output.max_bytes`, the 50,000-char terminal cap (`:617,625`) this bound
 * used to be derived from. Characters are not bytes: those 100,000 can be four
 * UTF-8 bytes each, and JSON escaping can spend six per character on control
 * runs, so the row this must hold is a multiple of its character count. 256 KiB
 * covers the realistic worst case with headroom and still costs nothing when
 * the row is small — the bound caps a read, it does not reserve memory.
 */
private const val BYTES_PER_MESSAGE = 256L * 1024L

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * This client's own sentence, for the one failure the transport cannot have an
 * opinion about: the Gateway answered, and the answer was not the contract.
 * Every other message a caller can see here is the transport's
 * ([RECONNECT_MESSAGE], [MALFORMED_REQUEST_MESSAGE] and the rest), reused
 * rather than retyped so the two files cannot drift apart.
 */
internal const val UNUSABLE_RESPONSE_MESSAGE = "The Gateway returned an unusable response. Try again."
