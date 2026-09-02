package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.CoroutineContext

/**
 * The verbs this client may put on the wire.
 *
 * A typed verb rather than a string at the one call site that can delete
 * something: [GatewayHttp] takes a method name, and a method name is a value a
 * caller can mistype. Nothing outside this file names a verb, so the set of
 * things this client can do to a Gateway is this declaration.
 */
internal enum class GatewayRestVerb { GET, POST, PATCH, DELETE }

/**
 * The host actions this client is willing to name in a path.
 *
 * `GET /api/actions/{name}/status` resolves `name` against the host's own
 * action table and 404s anything else (`hermes_cli/web_server.py:5817-5819` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), and that table holds seventeen
 * entries including `backup`, `import` and `security-audit` (`:4572-4590`). A
 * free string here would let any caller tail any of them; this app starts
 * exactly two, so exactly two are addressable.
 */
enum class GatewayAction(val wire: String) {
    /** `hermes update` on the host (`web_server.py:5139-5145` @ the pin). */
    HermesUpdate("hermes-update"),

    /**
     * `hermes gateway restart` — the *messaging* gateway, not the `hermes
     * serve` process this app is talking to (`web_server.py:4842-4843,4939` @
     * the pin). The HTTP server survives it, which is what makes polling
     * across it possible at all.
     */
    GatewayRestart("gateway-restart"),
}

/**
 * `archived` on the session list: hide soft-archived sessions, return only
 * them, or return both (hermes-agent @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`,
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
 * (`sessions.py:159` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
 */
data class GatewaySessionPage(
    val rows: List<JsonObject>,
    val total: Long?,
    val limit: Long?,
    val offset: Long?,
)

/**
 * The answer to one session search.
 *
 * [results] stay as parsed JSON objects for the same reason
 * [GatewaySessionPage.rows] do: the row contract belongs to the session model.
 * The envelope is this client's — `{"results": [...]}` (`sessions.py:421` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) — and a body without a `results`
 * array, or with an element that is not an object, fails the whole search
 * rather than rendering a half-truth.
 *
 * A result carries `{session_id, lineage_root, model, role, session_started,
 * snippet, source}` (`apps/desktop/src/types/hermes.ts:1193-1208` @ the pin).
 * The route stamps a richer row on the payload when it can resolve one
 * (`sessions.py:326-348`); none of that is read here, because Desktop's own
 * type does not carry it and a field only this client read would be a second
 * contract to keep in step.
 */
data class GatewaySessionSearchPage(
    val results: List<JsonObject>,
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
 * What `GET /api/status` says about the backend, as the System panel needs it
 * (payload `web_server.py:4011-4031` @ the pin).
 *
 * Four fields out of thirty. This is where Desktop reads the backend version —
 * its System panel's sub-line is `Hermes {version} · Active sessions {count}`
 * (`apps/desktop/src/app/command-center/index.tsx:440-442`) — and nothing else
 * on that panel reads the rest. [gatewayRunning] is the one field its status dot
 * looks at; `gateway_state` exists and Desktop deliberately ignores it
 * (`index.tsx:430-435`), so it is not carried here either.
 */
data class GatewayStatusSummary(
    val version: String,
    val activeSessions: Long,
    val gatewayRunning: Boolean,
    /**
     * Whether the host's own updater is usable here at all
     * (`web_server.py:4016`, derived at `:2593-2605`). Nullable because a
     * Gateway older than the pin simply omits it, which is a capability to
     * remember rather than a `false` to act on.
     */
    val canUpdateHermes: Boolean?,
)

/** One upstream commit from the update check (`web_server.py:5200` @ the pin). */
data class GatewayUpdateCommit(val sha: String, val summary: String)

/**
 * `GET /api/hermes/update/check` (`web_server.py:5211-5303` @ the pin). Always
 * HTTP 200; the fields carry the verdict.
 */
data class GatewayUpdateCheck(
    val installMethod: String?,
    val currentVersion: String?,
    /**
     * How far behind: `null` means the check itself failed, `-1` that the count
     * is unknown, `0` up to date, `>= 1` behind (`:5219-5235`). The three
     * non-positive answers are different facts, so they are not collapsed.
     */
    val behind: Long?,
    val updateAvailable: Boolean,
    /** True only for a git install — the one the host can apply in place (`:5259`). */
    val canApply: Boolean,
    val updateCommand: String?,
    val message: String?,
    /** Only sent for a git install that is behind (`:5300-5301`); `[]` otherwise. */
    val commits: List<GatewayUpdateCommit>,
)

/**
 * What `POST /api/hermes/update` answered.
 *
 * **A refusal is HTTP 200 with `ok: false`** (`web_server.py:5088-5095,
 * 5117-5124` @ the pin), so the status code cannot classify this and the
 * envelope has to. That is the whole reason this is a sealed type rather than a
 * nullable field: a caller cannot forget to look.
 */
sealed interface GatewayUpdateStart {
    /**
     * The host spawned, or already had, a detached `hermes update`.
     *
     * [actionId] is what makes success provable across the restart the update
     * performs on the host: it appears in `update.log` as `=== hermes-update
     * completed <id> ===` (`web_server.py:4814-4839`). A Gateway older than
     * that recovery sends none, which is why it is nullable rather than
     * required.
     */
    data class Started(
        val name: String,
        val actionId: String?,
        /** The host adopted a run already in flight (`web_server.py:5126-5137`). */
        val alreadyRunning: Boolean,
    ) : GatewayUpdateStart

    /**
     * The host will not update itself in place, and said what to run instead.
     *
     * [message] and [updateCommand] are Gateway-authored: they may be shown
     * only after redaction and a length cap, like every other backend string.
     */
    data class Refused(
        val error: String?,
        val message: String?,
        val updateCommand: String?,
    ) : GatewayUpdateStart
}

/**
 * The durable receipt summary the action-status route attaches to a
 * `hermes-update` poll (`web_server.py:5890-5920` @ the pin).
 *
 * It is the answer to "did the update this app started actually finish", which
 * liveness cannot answer across the restart the update performs on itself.
 */
data class GatewayActionReceipt(
    val outcome: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val postVersion: String?,
)

/** `GET /api/actions/{name}/status` (`web_server.py:5876-5886` @ the pin). */
data class GatewayActionStatus(
    val name: String,
    val running: Boolean,
    /**
     * `null` while the host has no answer — which is also what it reports right
     * after an update restarted the process that was watching the child. Never
     * read as "failed"; that is what [receipt] and the log marker are for.
     */
    val exitCode: Long?,
    val actionId: String?,
    /** The action log tail, Gateway-authored. Redact and cap before showing. */
    val lines: List<String>,
    val receipt: GatewayActionReceipt?,
)

/**
 * The full update receipt (`GET /api/hermes/update/receipt`,
 * `web_server.py:5923-5945` @ the pin; shape `hermes_cli/update_receipt.py:60-73`).
 *
 * Only the fields this app can act on are carried. [serveUnitsVerified] and
 * [staleRuntimes] are new at this pin (`update_receipt.py:135-155`) and describe
 * `hermes serve`'s *own* post-update recovery — the process this app talks to.
 * The action-status summary does not project them (`web_server.py:5908-5918`),
 * so this endpoint is the only place they exist.
 */
data class GatewayUpdateReceipt(
    val outcome: String?,
    val preVersion: String?,
    val postVersion: String?,
    /** Units a supervisor confirmed back up. The only bucket allowed to claim coverage. */
    val serveUnitsVerified: List<String>,
    val serveUnitsFailed: List<String>,
    /** Processes still alive on the pre-update generation. */
    val staleRuntimes: Int,
)

/** `POST /api/gateway/restart` (`web_server.py:4988-5002` @ the pin). */
data class GatewayRestartStart(val name: String, val pid: Long?)

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
 *   (`apps/desktop/src/store/notifications.ts:146,151-159` @ the pin); this
 *   goes further and never carries backend text out at all, because on this app
 *   a response body can hold a token, a host name or a fingerprint.
 */
class GatewayRestClient(
    /**
     * Where [send] does its blocking work.
     *
     * `GatewayHttp.execute` is a blocking OkHttp call, so production keeps it
     * on [Dispatchers.IO]. That is a *real* thread, and a test driving this
     * client on virtual time cannot see it: the hop lands the transport call on
     * a pool thread that races the test's own `runCurrent`, so whether a
     * background read has landed by the next assertion depends on the machine
     * rather than on the schedule. A test injects the scheduler it is already
     * driving — or [kotlin.coroutines.EmptyCoroutineContext], which keeps the
     * call on the caller's coroutine — and gets one answer every run.
     */
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val http: () -> GatewayHttp?,
) {

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
     * `apps/desktop/src/store/session.ts:419-426`). The default here is the
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
     * Full-text session search through `GET /api/sessions/search`
     * (`sessions.py:205-213` @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`).
     *
     * The route answers over *every* session the profile owns, not only the
     * page this client has loaded: direct session-id hits first, then FTS5
     * message-content hits with an automatic prefix wildcard, deduped by
     * compression lineage root (`:306-321,353-420`). That is why the app can
     * find a conversation the list has never paged in.
     *
     * [query] is sent as `q` and is URL-encoded by the transport. A control
     * character is refused here rather than escaped: the route's FTS parser is
     * the wrong place to discover that a paste carried a newline, and this
     * client's rule everywhere else is that a caller's broken input is a
     * request to refuse. A blank query is refused for the same reason — the
     * route answers it with an empty list (`:224-225`), so sending one is a
     * round trip that can only say nothing.
     *
     * [limit] defaults to the route's own `20`, which is also what Desktop
     * gets: it sends `q` alone (`apps/desktop/src/api/sessions.ts:348-352`).
     * The route clamps to `1..100` itself (`:229`); refusing outside that range
     * keeps the same rule [listSessions] applies, so a caller's arithmetic
     * never quietly reads a page it did not ask for.
     *
     * [profile] is this client's own addition — Desktop's sidebar is one
     * profile's by construction, while this app's rail can stand in a named
     * profile, and a search that ignored the scope would answer with
     * conversations the list beside it does not show. It travels the way every
     * other leg's scope does, through [scopeQuery]. Ledgered as a
     * mobile-adaptation in `docs/parity/session-search.md`.
     */
    suspend fun searchSessions(
        query: String,
        limit: Int = DEFAULT_SESSION_SEARCH,
        profile: String? = null,
    ): GatewayRestResult<GatewaySessionSearchPage> {
        if (query.isBlank()) return malformed()
        if (query.any(Char::isISOControl)) return malformed()
        if (limit !in 1..MAX_SESSION_SEARCH) return malformed()
        val scope = scopeQuery(profile) ?: return malformed()
        return send(
            path = SESSION_SEARCH_PATH,
            verb = GatewayRestVerb.GET,
            query = buildMap {
                put("q", query)
                put("limit", limit.toString())
                putAll(scope)
            },
            timeoutMillis = LIST_TIMEOUT_MILLIS,
            maxResponseBytes = LIST_MAX_RESPONSE_BYTES,
            parse = ::parseSessionSearchPage,
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

    // -----------------------------------------------------------------------
    // System panel: backend status, the host's own updater, and the messaging
    // gateway's restart. Every route below is public HTTP on the Gateway and
    // route-agnostic; nothing here is Remote-specific.
    // -----------------------------------------------------------------------

    /**
     * The backend's version, session count and messaging-gateway state from
     * `GET /api/status` (`web_server.py:3771` @ the pin).
     *
     * Fail-closed on the three fields the panel renders: a body missing any of
     * them is not that route's answer, and half a status line is worse than the
     * loading state it would replace. `can_update_hermes` is the exception — an
     * older Gateway omits it, so its absence is a null rather than a refusal.
     */
    suspend fun status(): GatewayRestResult<GatewayStatusSummary> = send(
        path = STATUS_PATH,
        verb = GatewayRestVerb.GET,
        query = emptyMap(),
        timeoutMillis = LIST_TIMEOUT_MILLIS,
        maxResponseBytes = ACK_MAX_RESPONSE_BYTES,
        parse = ::parseStatusSummary,
    )

    /**
     * `GET /api/hermes/update/check` (`web_server.py:5211-5303` @ the pin).
     *
     * [force] busts the host's six-hour `.update_check` cache (`:5279-5283`).
     * Desktop always forces (`apps/desktop/src/store/updates.ts:374`), because a
     * person who opened the updates surface is asking *now*.
     */
    suspend fun checkHermesUpdate(force: Boolean): GatewayRestResult<GatewayUpdateCheck> = send(
        path = UPDATE_CHECK_PATH,
        verb = GatewayRestVerb.GET,
        query = mapOf("force" to force.toString()),
        timeoutMillis = LIST_TIMEOUT_MILLIS,
        maxResponseBytes = CHECK_MAX_RESPONSE_BYTES,
        parse = ::parseUpdateCheck,
    )

    /**
     * Start `hermes update` on the host through `POST /api/hermes/update`
     * (`web_server.py:5078-5154` @ the pin).
     *
     * Asynchronous: the host spawns a detached child and answers immediately,
     * so this returning does not mean anything was updated. It also does not
     * mean anything was *started* — see [GatewayUpdateStart.Refused], which
     * arrives as a 200.
     *
     * Sent once, like every other write here. The route is not idempotent in
     * the ordinary sense but it is coalescing: a second call while one is in
     * flight adopts the running child rather than starting a second
     * (`:5126-5137`).
     */
    suspend fun startHermesUpdate(): GatewayRestResult<GatewayUpdateStart> = send(
        path = UPDATE_PATH,
        verb = GatewayRestVerb.POST,
        query = emptyMap(),
        // The route takes no body (`web_server.py:5079`), and this transport
        // will not POST without one, so it carries zero bytes rather than an
        // invented object the host would parse and discard.
        body = EMPTY_BODY.toRequestBody(JSON_MEDIA_TYPE),
        timeoutMillis = WRITE_TIMEOUT_MILLIS,
        maxResponseBytes = ACK_MAX_RESPONSE_BYTES,
        parse = ::parseUpdateStart,
    )

    /**
     * Tail one of this app's two host actions
     * (`GET /api/actions/{name}/status`, `web_server.py:5814-5887` @ the pin).
     *
     * [lines] is refused rather than clamped outside the route's own
     * `[1, 2000]` window (`:5822`), for the reason the session list refuses a
     * bad page: a caller's broken arithmetic must not silently read something
     * else. The timeout is deliberately the shortest in this file — this call
     * runs on a 1500 ms cadence across a host that is restarting itself, and a
     * dead host must fail it fast enough that the next tick is still a tick.
     */
    suspend fun actionStatus(
        action: GatewayAction,
        lines: Int = DEFAULT_ACTION_LINES,
    ): GatewayRestResult<GatewayActionStatus> {
        if (lines !in 1..MAX_ACTION_LINES) return malformed()
        return send(
            path = "$ACTIONS_PATH/${action.wire}/status",
            verb = GatewayRestVerb.GET,
            query = mapOf("lines" to lines.toString()),
            timeoutMillis = ACTION_STATUS_TIMEOUT_MILLIS,
            maxResponseBytes = ACTION_STATUS_MAX_RESPONSE_BYTES,
            parse = ::parseActionStatus,
        )
    }

    /**
     * The durable record of the last `hermes update`
     * (`GET /api/hermes/update/receipt`, `web_server.py:5923-5945` @ the pin).
     *
     * A **404** here is the host saying no update has ever been recorded
     * (`:5940-5944`) — a capability and a fact, not a failure to retry.
     */
    suspend fun updateReceipt(): GatewayRestResult<GatewayUpdateReceipt> = send(
        path = UPDATE_RECEIPT_PATH,
        verb = GatewayRestVerb.GET,
        query = emptyMap(),
        timeoutMillis = LIST_TIMEOUT_MILLIS,
        maxResponseBytes = RECEIPT_MAX_RESPONSE_BYTES,
        parse = ::parseUpdateReceipt,
    )

    /**
     * Restart the **messaging gateway** through `POST /api/gateway/restart`
     * (`web_server.py:4988-5002` @ the pin).
     *
     * Not the `hermes serve` process this app is connected to: the host runs
     * `hermes [--profile P] gateway restart` (`:4842-4843,4939`) and the HTTP
     * server survives it. No HTTP endpoint restarts `hermes serve` itself;
     * Desktop's "Restart backend" is Electron IPC.
     *
     * Asynchronous, and completion is not readiness: the child exits as soon as
     * it has handed the restart to the supervisor (`:4598-4604`). Poll
     * [actionStatus] with [GatewayAction.GatewayRestart] for the child, and read
     * `exit_code == 0` as a successful handoff rather than a running gateway.
     */
    suspend fun restartGateway(profile: String? = null): GatewayRestResult<GatewayRestartStart> {
        val scope = scopeQuery(profile) ?: return malformed()
        return send(
            path = GATEWAY_RESTART_PATH,
            verb = GatewayRestVerb.POST,
            query = scope,
            body = EMPTY_BODY.toRequestBody(JSON_MEDIA_TYPE),
            timeoutMillis = WRITE_TIMEOUT_MILLIS,
            maxResponseBytes = ACK_MAX_RESPONSE_BYTES,
            parse = ::parseRestartStart,
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
    ): GatewayRestResult<T> = withContext(ioContext) {
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

/**
 * The route clamps its own answer to 100 rows (`sessions.py:229,316` @ the
 * pin), so a longer list is a backend this client does not recognise rather
 * than a bigger page. Refused whole, like every other envelope that does not
 * match the contract — truncating would render a page nobody asked for.
 */
private fun parseSessionSearchPage(bytes: ByteArray): GatewaySessionSearchPage? {
    val root = parseObject(bytes) ?: return null
    val results = root.objectArray("results") ?: return null
    if (results.size > MAX_SESSION_SEARCH) return null
    return GatewaySessionSearchPage(results = results)
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

private fun parseStatusSummary(bytes: ByteArray): GatewayStatusSummary? {
    val root = parseObject(bytes) ?: return null
    return GatewayStatusSummary(
        version = root.jsonString("version") ?: return null,
        activeSessions = root.number("active_sessions") ?: return null,
        gatewayRunning = root.boolean("gateway_running") ?: return null,
        canUpdateHermes = root.boolean("can_update_hermes"),
    )
}

private fun parseUpdateCheck(bytes: ByteArray): GatewayUpdateCheck? {
    val root = parseObject(bytes) ?: return null
    return GatewayUpdateCheck(
        installMethod = root.jsonString("install_method"),
        currentVersion = root.jsonString("current_version"),
        // Absent and explicitly null are the same answer here: the check did
        // not produce a count (`web_server.py:5292` @ the pin).
        behind = root.number("behind"),
        // The two booleans decide what the surface offers, so a body without
        // them is not this route's answer.
        updateAvailable = root.boolean("update_available") ?: return null,
        canApply = root.boolean("can_apply") ?: return null,
        updateCommand = root.jsonString("update_command"),
        message = root.jsonString("message"),
        // One unreadable commit poisons the list, for the same reason one
        // unreadable session row poisons a page.
        commits = root.commitArray() ?: return null,
    )
}

/**
 * `commits` is optional: the route omits it entirely for a non-git install or a
 * host that is up to date (`web_server.py:5300-5301` @ the pin). Absent is an
 * empty changelog; present-and-malformed is a body this client will not read.
 */
private fun JsonObject.commitArray(): List<GatewayUpdateCommit>? {
    val raw = this["commits"] ?: return emptyList()
    if (raw is JsonNull) return emptyList()
    val rows = raw as? JsonArray ?: return null
    val parsed = ArrayList<GatewayUpdateCommit>(rows.size)
    for (row in rows) {
        val commit = row as? JsonObject ?: return null
        parsed.add(
            GatewayUpdateCommit(
                sha = commit.jsonString("sha") ?: return null,
                summary = commit.jsonString("summary") ?: return null,
            ),
        )
    }
    return parsed
}

private fun parseUpdateStart(bytes: ByteArray): GatewayUpdateStart? {
    val root = parseObject(bytes) ?: return null
    val ok = root.boolean("ok") ?: return null
    if (!ok) {
        return GatewayUpdateStart.Refused(
            error = root.jsonString("error"),
            message = root.jsonString("message"),
            updateCommand = root.jsonString("update_command"),
        )
    }
    return GatewayUpdateStart.Started(
        name = root.jsonString("name") ?: return null,
        actionId = root.jsonString("action_id"),
        alreadyRunning = root.boolean("already_running") == true,
    )
}

private fun parseActionStatus(bytes: ByteArray): GatewayActionStatus? {
    val root = parseObject(bytes) ?: return null
    return GatewayActionStatus(
        name = root.jsonString("name") ?: return null,
        running = root.boolean("running") ?: return null,
        exitCode = root.number("exit_code"),
        actionId = root.jsonString("action_id"),
        lines = root.stringArray("lines") ?: return null,
        receipt = root.child("receipt")?.let { receipt ->
            GatewayActionReceipt(
                outcome = receipt.jsonString("outcome"),
                startedAt = receipt.jsonString("started_at"),
                finishedAt = receipt.jsonString("finished_at"),
                postVersion = receipt.jsonString("post_version"),
            )
        },
    )
}

private fun parseUpdateReceipt(bytes: ByteArray): GatewayUpdateReceipt? {
    val root = parseObject(bytes) ?: return null
    val receipt = root.child("receipt") ?: return null
    val recovery = receipt.child("gateway_restart")?.child("fresh_recovery")
    val serveUnits = recovery?.child("serve_units")
    return GatewayUpdateReceipt(
        outcome = receipt.jsonString("outcome"),
        preVersion = receipt.child("pre_update")?.jsonString("version"),
        postVersion = receipt.child("post_update")?.jsonString("version"),
        serveUnitsVerified = serveUnits?.stringArray("verified").orEmpty(),
        serveUnitsFailed = serveUnits?.stringArray("failed").orEmpty(),
        staleRuntimes = (recovery?.get("stale_runtimes") as? JsonArray)?.size ?: 0,
    )
}

private fun parseRestartStart(bytes: ByteArray): GatewayRestartStart? {
    val root = parseObject(bytes) ?: return null
    if (root.boolean("ok") != true) return null
    return GatewayRestartStart(
        name = root.jsonString("name") ?: return null,
        pid = root.number("pid"),
    )
}

/** A named array of strings, or null if it is present and holds anything else. */
private fun JsonObject.stringArray(name: String): List<String>? {
    val raw = this[name] ?: return null
    val rows = raw as? JsonArray ?: return null
    val parsed = ArrayList<String>(rows.size)
    for (row in rows) {
        parsed.add((row as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null)
    }
    return parsed
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
private const val SESSION_SEARCH_PATH = "$SESSIONS_PATH/search"
private const val STATUS_PATH = "api/status"
private const val UPDATE_CHECK_PATH = "api/hermes/update/check"
private const val UPDATE_PATH = "api/hermes/update"
private const val UPDATE_RECEIPT_PATH = "api/hermes/update/receipt"
private const val ACTIONS_PATH = "api/actions"
private const val GATEWAY_RESTART_PATH = "api/gateway/restart"

/** The route's own window; anything outside it is a query it would clamp (`:5822`). */
internal const val MAX_ACTION_LINES = 2000

/**
 * What Desktop's own System panel asks for (`store/system-actions.ts:19-32` @
 * the pin). Kept as this client's default so the two ask the host the same
 * question.
 */
internal const val DEFAULT_ACTION_LINES = 180

/**
 * A body of zero bytes. Both POST routes here take no body at all
 * (`web_server.py:5079`, `:4989` @ the pin) and this transport refuses a POST
 * without one, so they send nothing rather than an invented object.
 */
private val EMPTY_BODY = ByteArray(0)

/** The route's own page cap; an unbounded limit is a query it refuses. */
internal const val MAX_SESSION_PAGE = 100
private const val DEFAULT_SESSION_PAGE = 20

/**
 * The search route's own clamp, `max(1, min(limit, 100))` (`sessions.py:229` @
 * the pin), and its own default. Desktop sends neither and takes the `20`
 * (`apps/desktop/src/api/sessions.ts:348-352`), so this client's default is
 * that same 20 rather than a bigger page nothing on Desktop ever renders.
 */
internal const val MAX_SESSION_SEARCH = 100
internal const val DEFAULT_SESSION_SEARCH = 20

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
 * The shortest timeout in this file, and deliberately.
 *
 * The action-status poll runs on a 1500 ms cadence against a host that is in
 * the middle of restarting itself, so a hop that hangs for the shared 15 s does
 * not slow the loop down — it *replaces* it, turning a 1.5-second cadence into a
 * 16.5-second one for the whole of the restart window. Five seconds is long
 * enough for a host that is merely busy and short enough that a host which has
 * gone away is observed as gone rather than as slow.
 */
private const val ACTION_STATUS_TIMEOUT_MILLIS = 5_000L

/**
 * A list page is 100 rows with the payload-dominating fields already stripped
 * server-side (`sessions.py:126-129,157-158`), and an ack is one line. Both are
 * bounds this client would rather fail on than hold.
 */
private const val LIST_MAX_RESPONSE_BYTES = 1024L * 1024L
private const val ACK_MAX_RESPONSE_BYTES = 64L * 1024L

/**
 * The update check carries up to twenty commit subjects (`git log HEAD..origin/main
 * -n20`, `web_server.py:5157-5208` @ the pin) plus a remediation message. That is
 * an ack with a list bolted on, not a page.
 */
private const val CHECK_MAX_RESPONSE_BYTES = 256L * 1024L

/**
 * An action log tail: up to [MAX_ACTION_LINES] lines of somebody else's build
 * output. Bounded like a list rather than like an ack, because a single `pip`
 * or `npm` line is routinely hundreds of characters and a wedged action's log
 * is exactly where a runaway one appears.
 */
private const val ACTION_STATUS_MAX_RESPONSE_BYTES = 1024L * 1024L

/**
 * The full receipt carries every step, skip and fleet row of one update
 * (`hermes_cli/update_receipt.py:60-73` @ the pin) — larger than an ack and
 * nowhere near a transcript page.
 */
private const val RECEIPT_MAX_RESPONSE_BYTES = 256L * 1024L

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
 *
 * So the bound is `limit` × [BYTES_PER_MESSAGE], and it stops growing once that
 * product passes [DEFAULT_MAX_RESPONSE_BYTES] — at 24 MiB over ~586 KiB a row,
 * from the 42nd row on. A page of 41 is the largest still sized to what it
 * asked for; every larger page, [MAX_MESSAGE_PAGE] included, shares that one
 * ceiling. That is the intended shape: the per-row worst case is what a *page*
 * of one has to survive, not what 500 of them are allowed to cost together.
 */
private fun messagesResponseBound(limit: Int?): Long =
    ((limit ?: MAX_MESSAGE_PAGE).toLong() * BYTES_PER_MESSAGE)
        .coerceIn(BYTES_PER_MESSAGE, DEFAULT_MAX_RESPONSE_BYTES)

/**
 * The widest single tool result the pinned host will emit, in **characters**.
 *
 * `read_file` returns up to `file_read_max_chars` = 100,000 characters per call
 * (`hermes_cli/config_defaults.py:592`, `tools/file_tools.py:65` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) — twice `tool_output.max_bytes`,
 * the 50,000-char terminal cap this bound used to be derived from. The value is
 * host-configurable (`file_tools.py:63,82`), so it is the number to move when a
 * host raises its cap, and everything below moves with it.
 */
internal const val READ_FILE_MAX_CHARS = 100_000L

/**
 * Bytes one of those characters can cost on the wire, worst case.
 *
 * Characters are not bytes twice over: a UTF-8 astral character is four, and
 * JSON escaping spends six (`\uXXXX`) on a control character. A tool result can
 * be entirely either, so the bound takes the larger — six.
 */
internal const val MAX_BYTES_PER_CHAR = 6L

/**
 * One message row at the largest the pinned host will emit: ~586 KiB.
 *
 * Derived, not fitted. [READ_FILE_MAX_CHARS] is what the host will put in a row
 * and [MAX_BYTES_PER_CHAR] is what the wire will charge for it; the product is
 * the row a one-message page must be able to carry. Reading a number off that
 * multiplication is the point — a bound guessed at instead would go quietly
 * wrong the moment either input moved. It costs nothing when the row is small:
 * the bound caps a read, it does not reserve memory.
 */
private const val BYTES_PER_MESSAGE = READ_FILE_MAX_CHARS * MAX_BYTES_PER_CHAR

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * This client's own sentence, for the one failure the transport cannot have an
 * opinion about: the Gateway answered, and the answer was not the contract.
 * Every other message a caller can see here is the transport's
 * ([RECONNECT_MESSAGE], [MALFORMED_REQUEST_MESSAGE] and the rest), reused
 * rather than retyped so the two files cannot drift apart.
 */
internal const val UNUSABLE_RESPONSE_MESSAGE = "The Gateway returned an unusable response. Try again."
