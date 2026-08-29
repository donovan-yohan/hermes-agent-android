package com.hermesagent.mobile.data.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Local route: a Hermes the person runs on this same phone, reached over
 * loopback.
 *
 * Nothing about that runtime belongs to this app. Upstream ships Android as a
 * Termux install (`website/docs/getting-started/termux.md` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`), the person starts and stops
 * `hermes serve` there, and this app is one more client of it — the same
 * host-owned boundary ADR-0002 draws for the Remote route, with the host
 * happening to be this device. Disconnecting closes a socket and touches no
 * process.
 *
 * The one thing that stands between any app on this phone and that server is
 * the dashboard session token: on loopback there is no TLS, no sign-in and no
 * host key, and the server compares the `X-Hermes-Session-Token` header against
 * a value fixed for the life of the process
 * (`hermes_cli/web_server.py:499-504` and `:567-584` @ `f82f2dba`). So the
 * token is required here, it is kept in the same Keystore-encrypted slot a
 * Remote row's sign-in uses, and it is bound to the address that minted it.
 *
 * Any other app on this phone can bind a loopback port without a permission,
 * so *which* port a row names is a security decision, not a convenience. That
 * is why the address rule below refuses rather than guesses, and why nothing
 * here ever substitutes a default for a port somebody typed.
 */

/** The port `hermes serve` listens on by default; the address is loopback-only. */
const val DEFAULT_LOCAL_GATEWAY_PORT: Int = 9119

/** What the Local route prefills, and the only address that needs no typing. */
const val DEFAULT_LOCAL_GATEWAY_URL: String = "http://127.0.0.1:$DEFAULT_LOCAL_GATEWAY_PORT"

/** The header the Gateway authenticates a loopback request with. */
internal const val SESSION_TOKEN_HEADER: String = "X-Hermes-Session-Token"

/** Non-secret configuration for a Hermes running on this device. */
data class LocalGatewayProfile(
    val baseUrl: String = "",
    /**
     * Which saved connection's Keystore slot holds this Gateway's session
     * token. A random local row id, exactly as a Remote row's is.
     *
     * Blank means no registry row supplied one. Unlike the Remote route there
     * is no pre-registry, URL-named file to fall back to — the Local route has
     * never shipped before — so a blank id addresses no slot at all rather than
     * a shared one.
     */
    val secretSlotId: String = "",
) {
    val normalizedBaseUrl: String?
        get() = normalizeLocalGatewayUrl(baseUrl)

    val isValid: Boolean get() = normalizedBaseUrl != null

    /**
     * What a row shows: `127.0.0.1:9119`. Desktop's `connectionEndpoint` drops
     * the scheme for the same reason (`connection-display.ts:61-75` @
     * `f82f2dba`) — the scheme is the one part of a loopback address that
     * carries no information. The port is never dropped: it is what says
     * *which* server on this device the row is.
     */
    val displayEndpoint: String?
        get() = normalizedBaseUrl?.removePrefix(LOCAL_SCHEME)
            ?: baseUrl.trim().takeIf(String::isNotBlank)

    /** The one slot this profile may read or write; null without both a row and an address. */
    internal val secretSlot: GatewaySecretSlot?
        get() = secretSlotId.takeIf(String::isNotBlank)
            ?.let { row -> normalizedBaseUrl?.let { GatewaySecretSlot(row, it) } }

    /**
     * The slot to erase, addressable by row id alone.
     *
     * A row whose address was blanked or mistyped still owns a file, and being
     * unable to parse where a token was for is the worst possible reason to
     * leave it on disk.
     */
    internal val eraseSlot: GatewaySecretSlot?
        get() = secretSlotId.takeIf(String::isNotBlank)?.let(GatewaySecretSlot::forRow)
}

/**
 * The Local route's address rule, mirroring [normalizeRemoteGatewayUrl]:
 * refuse rather than guess, and hand back one canonical form.
 *
 * Accepted: a literal `http://` prefix, a loopback host, an optional port that
 * defaults to [DEFAULT_LOCAL_GATEWAY_PORT], and an optional path. Refused: any
 * other scheme, any other host, userinfo, a query, a fragment — plus two shapes
 * that exist only because the URL parser is more forgiving than this rule can
 * afford to be.
 *
 * An abbreviated scheme, because the parser accepts `http:host:port` and
 * `http:/host:port`, where the port is then only findable in the raw text. And
 * a backslash anywhere in the input, because the parser ends the authority at
 * one: `http://127.0.0.1\\evil:9200` puts the port in the *path* and reports
 * the scheme default, and `http://127.0.0.1:92\\00` reports port 92. In both
 * cases a port the person typed is replaced by one they did not, and the row —
 * with its session token bound to it — then names a different server on this
 * device than the one they meant. Any app on this phone can bind a loopback
 * port with no permission, so that substitution hands the token away.
 *
 * The set is what a sweep found rather than what seemed likely: every code
 * point from U+0000 to U+0020, plus U+007F, U+00A0, `%5C`, `%2F`, `%09`, `%00`
 * and `%20`, was fed through as an authority separator, and the backslash is
 * the only one the parser and this rule disagreed about.
 *
 * The returned form always names its port, including port 80, so normalizing a
 * normalized address is a no-op: the value is written to disk, re-read, and
 * used as the identity a stored token is bound to, and an identity that drifted
 * on a second pass would refuse the credential it just minted.
 */
internal fun normalizeLocalGatewayUrl(raw: String): String? {
    val trimmed = raw.trim()
    // The one shape whose authority the raw text can be read from. Everything
    // below depends on it, so it is checked before the parser is asked.
    if (!trimmed.startsWith(LOCAL_SCHEME, ignoreCase = true)) return null
    // The parser ends an authority at a backslash; [namesPort] does not, and a
    // rule that reads the port out of the raw text cannot survive the two
    // disagreeing. Refused outright rather than reconciled: a backslash has no
    // place in a loopback address, so there is nothing here worth rescuing.
    if (BACKSLASH in trimmed) return null
    val parsed = trimmed.toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http") return null
    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.querySize > 0 || parsed.fragment != null) return null
    if (parsed.host !in LOOPBACK_HOSTS) return null
    val port = if (namesPort(trimmed)) parsed.port else DEFAULT_LOCAL_GATEWAY_PORT
    if (port !in 1..65535) return null
    // Built by hand rather than through HttpUrl, which drops a port that equals
    // the scheme default and would make `http://127.0.0.1:80` normalize to an
    // address that then re-normalizes to port 9119.
    val host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
    return LOCAL_SCHEME + host + ":" + port + parsed.encodedPath.trimEnd('/')
}

internal fun localGatewayEndpoint(normalizedBaseUrl: String, path: String): HttpUrl =
    requireNotNull(normalizeLocalGatewayUrl(normalizedBaseUrl)?.toHttpUrlOrNull())
        .newBuilder()
        .addPathSegments(path)
        .build()

/** The socket the Local route opens. The Gateway takes its token in the query here. */
internal fun localGatewayWebSocketUrl(normalizedBaseUrl: String, token: ByteArray): HttpUrl =
    localGatewayEndpoint(normalizedBaseUrl, "api/ws")
        .newBuilder()
        .addQueryParameter("token", token.toString(Charsets.US_ASCII))
        .build()

/**
 * The readiness request, built where a test can read it back. The header name
 * is the contract with pinned Hermes, and it is also the name [redact] knows,
 * so a drift here would both fail to authenticate and stop being redacted.
 */
internal fun localGatewayHealthRequest(normalizedBaseUrl: String, token: ByteArray): Request =
    Request.Builder()
        .url(localGatewayEndpoint(normalizedBaseUrl, "api/health"))
        .header(SESSION_TOKEN_HEADER, token.toString(Charsets.US_ASCII))
        .get()
        .build()

/**
 * Whether the address names a port at all.
 *
 * OkHttp reports `http`'s default 80 for an address that named none, so the raw
 * text is the only place the difference survives. Callers must have established
 * a literal `http://` prefix first: without it there is no authority to read
 * here, and this would report "no port" for an address that named one.
 * Userinfo is refused before this runs, so the first colon after the host
 * cannot be a password separator, and so is a backslash, which the parser
 * treats as an authority terminator and this cut deliberately does not have to.
 */
private fun namesPort(raw: String): Boolean {
    val authority = raw.substringAfter("://", "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    val hostEnd = when {
        !authority.startsWith("[") -> 0
        // A bracketed host with no closing bracket is not an address the parser
        // accepted, so this is only ever reached with a well-formed one.
        else -> authority.indexOf(']').takeIf { it >= 0 }?.plus(1) ?: return false
    }
    return authority.indexOf(':', hostEnd) >= 0
}

private const val LOCAL_SCHEME = "http://"
private const val BACKSLASH = '\\'

/**
 * Every spelling of "this device". OkHttp lowercases hosts and unwraps IPv6
 * brackets, so `[::1]` arrives here as `::1`.
 */
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

/**
 * One opened Local leg: the socket, plus the header its REST siblings send.
 *
 * Not a data class, and the token is not a constructor `val`: a generated
 * `toString()` on this type would print a live credential into whatever log or
 * crash report happened to be holding it.
 *
 * The token is held as a `String`, for the life of the connection, because that
 * is what an HTTP header is. The `ByteArray` discipline elsewhere in this file
 * bounds the *mutable* copies — the one the store hands out, the one the
 * connector holds — and it is not a claim that no copy of the token exists in
 * the heap while the connection is open. What is guaranteed is narrower and
 * checkable: no copy reaches disk unencrypted, a log, a generated `toString`,
 * `redact` output, or anything a surface renders.
 */
internal class LocalGatewayLeg(
    val rpc: GatewayRpcClient,
    private val sessionToken: String,
) {
    fun authorization(): Pair<String, String>? =
        sessionToken.takeIf(String::isNotBlank)?.let { SESSION_TOKEN_HEADER to it }

    override fun toString(): String = "LocalGatewayLeg(sessionToken=<redacted>)"
}

/** What one slot had to say when asked for a session token. */
internal sealed interface SessionTokenRead {
    /** This row has no stored credential at all. */
    data object Absent : SessionTokenRead

    /**
     * Something is stored and this row may not have it: it was minted for
     * another address, it is the other kind of credential, or it could not be
     * read back. Distinct from [Absent] because only an empty slot may be
     * filled in by reading a credential off whatever is answering — doing that
     * for a refusal would undo the refusal on the spot.
     */
    data object Refused : SessionTokenRead

    /** Fresh ASCII bytes the caller owns and must zero. */
    class Found(val token: ByteArray) : SessionTokenRead {
        override fun toString(): String = "Found(token=<redacted>)"
    }
}

/** Bounded, authenticated readiness check against a Hermes on this device. */
internal fun interface LocalGatewayHealthCheck {
    /**
     * Throws [GatewayAuthException] with a 401 when the token is refused, and
     * [GatewayConnectionException] when the server is not answering usefully.
     */
    suspend fun verify(normalizedBaseUrl: String, token: ByteArray)
}

internal class OkHttpLocalGatewayHealthCheck(http: OkHttpClient) : LocalGatewayHealthCheck {
    private val loopbackHttp = loopbackClient(http)

    override suspend fun verify(normalizedBaseUrl: String, token: ByteArray) =
        withContext(Dispatchers.IO) {
            loopbackHttp.newCall(localGatewayHealthRequest(normalizedBaseUrl, token)).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        throw GatewayAuthException(LocalGatewayCopy.TOKEN_REFUSED, response.code)

                    !response.isSuccessful ->
                        throw GatewayConnectionException(LocalGatewayCopy.NOT_ANSWERING)

                    else -> Unit
                }
            }
        }
}

/**
 * The client every loopback hop uses: this app's shared one, with redirect
 * following off.
 *
 * OkHttp strips `Authorization` across a host change but knows nothing about
 * `X-Hermes-Session-Token`, and the socket carries its token in the query
 * besides — so a `302` from the loopback port would re-send this app's token to
 * whatever host the redirect names. A Hermes on loopback never legitimately
 * redirects, so the honest setting is to refuse to follow one at all. Named
 * here rather than spelled out at each call site, so the Local route cannot
 * grow a fourth hop that quietly forwards the token off the device.
 */
internal fun loopbackClient(http: OkHttpClient): OkHttpClient = http.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Opens a socket to a Hermes on this device.
 *
 * The order is the readiness boundary, and it is the Managed SSH leg's order
 * minus the parts that belong to a process this app owns: an authenticated
 * `GET /api/health`, then the WebSocket, then one authenticated JSON-RPC round
 * trip. There is no ownership check, because there is no ownership: the
 * runtime is Termux's.
 */
internal class LocalGatewayConnector(
    private val tokens: GatewaySessionTokenStore,
    private val health: LocalGatewayHealthCheck,
    private val rpcOpen: suspend (String, ByteArray) -> GatewayRpcClient,
    /**
     * The dashboard-root scrape, tried **only** when this row's slot is empty —
     * an opportunistic convenience for someone who has not saved a token yet,
     * never a fallback. A token the Gateway has already refused is a wrong
     * token, and answering a refusal by reading a second credential off the
     * same server would turn "this token is stale, fix it" into a silent retry
     * loop. A slot that refused what it holds is not empty, which is why
     * [SessionTokenRead] tells the two apart.
     */
    private val scraper: GatewayServedTokenScraper? = null,
) {
    suspend fun open(profile: LocalGatewayProfile): LocalGatewayLeg {
        val baseUrl = profile.normalizedBaseUrl
            ?: throw GatewayConnectionException(LocalGatewayCopy.INVALID_URL)
        val token = resolveToken(profile, baseUrl)
            ?: throw GatewayAuthException(LocalGatewayCopy.TOKEN_MISSING, 401)
        return try {
            health.verify(baseUrl, token)
            LocalGatewayLeg(rpcOpen(baseUrl, token), token.toString(Charsets.US_ASCII))
        } finally {
            token.fill(0)
        }
    }

    /** Erases this row's session token. Addressable by row id alone. */
    suspend fun forget(profile: LocalGatewayProfile) {
        profile.eraseSlot?.let { tokens.clearSessionToken(it) }
    }

    private suspend fun resolveToken(profile: LocalGatewayProfile, baseUrl: String): ByteArray? {
        val stored = profile.secretSlot?.let { tokens.loadSessionToken(it) } ?: SessionTokenRead.Absent
        return when (stored) {
            is SessionTokenRead.Found -> stored.token
            SessionTokenRead.Refused -> null
            SessionTokenRead.Absent -> scraper?.scrape(baseUrl)
        }
    }
}

/** The Local route's refusal vocabulary, named so two callers say one sentence. */
internal object LocalGatewayCopy {
    const val INVALID_URL = "Enter a loopback Gateway address, such as http://127.0.0.1:9119."

    const val TOKEN_MISSING = "Save this Gateway's session token, then connect."

    const val TOKEN_REFUSED = "Session token was refused. Save the token Hermes is running with, then connect."

    const val NOT_ANSWERING = "Hermes is not answering on this device. Start it, then connect."

    const val UNAVAILABLE = "Local Gateway connections are unavailable in this build."
}
