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
     * carries no information.
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
 * Accepted: `http`, a loopback host, an optional port that defaults to
 * [DEFAULT_LOCAL_GATEWAY_PORT], and an optional path. Refused: any other
 * scheme, any other host, userinfo, a query, a fragment. `https` is refused
 * rather than silently downgraded — a loopback Hermes serves plain HTTP, and a
 * person who typed `https` is describing a different server.
 *
 * The returned form always names its port, including port 80, so normalizing a
 * normalized address is a no-op: the value is written to disk, re-read, and
 * used as the identity a stored token is bound to, and an identity that drifts
 * on a second pass would refuse the credential it just minted.
 */
internal fun normalizeLocalGatewayUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
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

/**
 * Whether the address names a port at all.
 *
 * OkHttp reports `http`'s default 80 for an address that named none, so the raw
 * text is the only place the difference survives. Userinfo is refused before
 * this runs, so the first colon after the host cannot be a password separator.
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
 */
internal class LocalGatewayLeg(
    val rpc: GatewayRpcClient,
    private val sessionToken: String,
) {
    fun authorization(): Pair<String, String>? =
        sessionToken.takeIf(String::isNotBlank)?.let { SESSION_TOKEN_HEADER to it }

    override fun toString(): String = "LocalGatewayLeg(sessionToken=<redacted>)"
}

/** Bounded, authenticated readiness check against a Hermes on this device. */
internal fun interface LocalGatewayHealthCheck {
    /**
     * Throws [GatewayAuthException] with a 401 when the token is refused, and
     * [GatewayConnectionException] when the server is not answering usefully.
     */
    suspend fun verify(normalizedBaseUrl: String, token: ByteArray)
}

internal class OkHttpLocalGatewayHealthCheck(
    private val http: OkHttpClient,
) : LocalGatewayHealthCheck {
    override suspend fun verify(normalizedBaseUrl: String, token: ByteArray) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(localGatewayEndpoint(normalizedBaseUrl, "api/health"))
                .header(SESSION_TOKEN_HEADER, token.toString(Charsets.US_ASCII))
                .get()
                .build()
            http.newCall(request).execute().use { response ->
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
     * The dashboard-root scrape, tried **only** when this row has no saved
     * token — an opportunistic convenience, never a fallback. A token the
     * Gateway has already refused is a wrong token, and answering a refusal by
     * reading a second credential off the same server would turn "this token is
     * stale, fix it" into a silent retry loop.
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

    private suspend fun resolveToken(profile: LocalGatewayProfile, baseUrl: String): ByteArray? =
        profile.secretSlot?.let { tokens.loadSessionToken(it) }
            ?: scraper?.scrape(baseUrl)
}

/** The Local route's refusal vocabulary, named so two callers say one sentence. */
internal object LocalGatewayCopy {
    const val INVALID_URL = "Enter a loopback Gateway address, such as http://127.0.0.1:9119."

    const val TOKEN_MISSING = "Save this Gateway's session token, then connect."

    const val TOKEN_REFUSED = "Session token was refused. Save the token Hermes is running with, then connect."

    const val NOT_ANSWERING = "Hermes is not answering on this device. Start it, then connect."

    const val UNAVAILABLE = "Local Gateway connections are unavailable in this build."
}
