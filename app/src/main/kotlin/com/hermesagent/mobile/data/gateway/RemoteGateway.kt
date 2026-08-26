package com.hermesagent.mobile.data.gateway

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Which Desktop-compatible route owns the app's one live Gateway connection. */
enum class GatewayConnectionMode {
    Remote,
    Ssh,
}

/** Non-secret configuration for a host-owned Remote Gateway. */
data class RemoteGatewayProfile(
    val baseUrl: String = "",
    val provider: String = "",
) {
    val normalizedBaseUrl: String?
        get() = normalizeRemoteGatewayUrl(baseUrl)

    val isValid: Boolean get() = normalizedBaseUrl != null
}

/** Persisted remote route settings. Tokens deliberately live behind [GatewayTokenStore]. */
interface RemoteGatewayProfileStore {
    val remoteGatewayProfile: kotlinx.coroutines.flow.Flow<RemoteGatewayProfile>
    val gatewayConnectionMode: kotlinx.coroutines.flow.Flow<GatewayConnectionMode>

    suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile)
    suspend fun saveGatewayConnectionMode(mode: GatewayConnectionMode)
}

/** Opens the system browser. The callback URI is always a temporary loopback listener. */
fun interface GatewayBrowserLauncher {
    suspend fun open(url: String)
}

internal data class GatewayAuthStatus(
    val authRequired: Boolean,
    val authFlows: Set<String>,
)

/**
 * Normalized native-app tokens returned by `/auth/native/token` and refresh.
 *
 * The hand-written string form is load-bearing: credentials must not enter a
 * crash report through a generated data-class `toString()`.
 */
internal data class GatewayNativeTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val provider: String,
    val userId: String,
) {
    override fun toString(): String =
        "GatewayNativeTokens(accessToken=<redacted>, refreshToken=<redacted>, " +
            "expiresAt=$expiresAt, provider=$provider, userId=<redacted>)"
}

internal interface GatewayTokenStore {
    suspend fun load(baseUrl: String): GatewayNativeTokens?
    suspend fun save(baseUrl: String, tokens: GatewayNativeTokens)
    suspend fun clear(baseUrl: String)
}

internal class GatewayAuthException(
    message: String,
    val statusCode: Int? = null,
) : Exception(message)

/** Network seam for the gateway-brokered RFC 8252 flow. */
internal interface GatewayNativeAuthApi {
    suspend fun status(baseUrl: String): GatewayAuthStatus
    suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens
    suspend fun refresh(baseUrl: String, refreshToken: String, provider: String): GatewayNativeTokens?
    suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String?
}

internal fun interface GatewayNativeLogin {
    suspend fun login(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher): GatewayNativeTokens
}

/**
 * Resolves one single-use WebSocket ticket without ever sharing Desktop's token.
 *
 * Source authority: NousResearch/hermes-agent @
 * 59795c40fff95b3029b8f2b02164da892429070f,
 * `apps/desktop/electron/native-oauth*.ts` and
 * `hermes_cli/dashboard_auth/routes.py:248-423,927-961,965-1097`.
 */
internal class NativeGatewayAuthenticator(
    private val api: GatewayNativeAuthApi,
    private val store: GatewayTokenStore,
    private val login: GatewayNativeLogin,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    /**
     * Serializes load → refresh → save for one process.
     *
     * `/auth/native/refresh` hands back a *new* refresh token and retires the
     * one it was given (hermes-agent @
     * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732`,
     * `hermes_cli/dashboard_auth/routes.py:1027-1079`). Two callers — the
     * reconnect path's [ticket] and a REST leg's [refreshAccessToken] — that
     * POST the same one-time token race each other into a rejection, and their
     * two [GatewayTokenStore.save] calls race over which rotation survives.
     * Interactive sign-in deliberately happens *outside* this lock, so a
     * browser round trip can never park another caller behind it.
     */
    private val rotation = Mutex()

    /** Stored tokens without triggering a sign-in flow; null when absent. */
    suspend fun tokens(baseUrl: String): GatewayNativeTokens? = store.load(baseUrl)

    suspend fun ticket(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher?): String {
        val baseUrl = profile.normalizedBaseUrl
            ?: throw GatewayAuthException("Enter a valid HTTPS Gateway URL.")
        val status = api.status(baseUrl)
        if (!status.authRequired) {
            throw GatewayAuthException(
                "This Gateway is not using remote authentication. Enable the Gateway auth gate before connecting.",
            )
        }
        if (NATIVE_FLOW !in status.authFlows) {
            throw GatewayAuthException(
                "This Gateway does not support native sign-in. Update Hermes on the remote host.",
            )
        }

        var tokens = store.load(baseUrl)
        if (tokens == null) {
            tokens = signIn(profile.copy(baseUrl = baseUrl), browser)
        } else if (tokens.needsRefresh(nowSeconds())) {
            tokens = refreshOrSignIn(profile.copy(baseUrl = baseUrl), tokens, browser)
        }

        api.mintWebSocketTicket(baseUrl, tokens.accessToken)?.let { return it }

        // A token may be revoked between the expiry check and ticket mint. One
        // refresh/sign-in retry is bounded; another rejection is terminal.
        tokens = refreshOrSignIn(profile.copy(baseUrl = baseUrl), tokens, browser)
        return api.mintWebSocketTicket(baseUrl, tokens.accessToken)
            ?: throw GatewayAuthException("Hermes rejected the refreshed sign-in. Sign in again.", 401)
    }

    /**
     * Deliberately outside [rotation]: a person tapping sign out must not wait
     * on a network refresh. [rotate] re-reads the store before it writes, so a
     * clear that lands mid-rotation still wins.
     */
    suspend fun signOut(profile: RemoteGatewayProfile) {
        profile.normalizedBaseUrl?.let { store.clear(it) }
    }

    /**
     * Rotate the stored access token once, without a browser — the same
     * rotation step [refreshOrSignIn] performs, minus its interactive
     * fallback. A REST leg that was refused can spend exactly one of these
     * before the app has to ask the person to sign in again; it deliberately
     * cannot start a sign-in on its own.
     *
     * False means no rotation happened, for any reason. It is never a partial
     * success: the stored tokens are replaced only when a whole new set
     * arrives.
     */
    suspend fun refreshAccessToken(profile: RemoteGatewayProfile): Boolean {
        val baseUrl = profile.normalizedBaseUrl ?: return false
        val observed = store.load(baseUrl) ?: return false
        return rotate(baseUrl, observed) != null
    }

    /**
     * One rotation of the stored pair, serialized by [rotation].
     *
     * [observed] is what the caller saw before it decided a rotation was
     * needed. Under the lock the store is read again: a caller that arrives
     * after someone else already rotated past [observed] takes that result
     * instead of spending a refresh token the Gateway has already retired,
     * which is both a wasted round trip and a rejection waiting to happen. The
     * refresh token is what the comparison is on, because it is the one-time
     * half — the access token only happens to rotate with it.
     *
     * The store is read once more after the network call, because [signOut]
     * deliberately does *not* take this lock: parking a person's sign-out
     * behind a bounded network refresh would be worse than the window it
     * closes. Saving into a store that was cleared mid-flight would silently
     * undo that sign-out and leave a usable credential on disk.
     *
     * Null means no rotation is available — nothing stored, nothing to rotate
     * with, the Gateway refused, or the credential was signed out from under
     * it. It is never a partial success: the stored tokens are replaced only
     * when a whole new set arrives.
     */
    private suspend fun rotate(
        baseUrl: String,
        observed: GatewayNativeTokens,
    ): GatewayNativeTokens? = rotation.withLock {
        // An already-cleared store is a deliberate "there is nothing here";
        // rotating [observed] anyway would resurrect what was just cleared.
        val current = store.load(baseUrl) ?: return@withLock null
        if (current.refreshToken != observed.refreshToken) return@withLock current
        val refreshToken = current.refreshToken.takeIf(String::isNotBlank) ?: return@withLock null
        val refreshed = api.refresh(baseUrl, refreshToken, current.provider) ?: return@withLock null
        // Same question again, because the answer can have changed while the
        // refresh was on the wire.
        if (store.load(baseUrl) == null) return@withLock null
        store.save(baseUrl, refreshed)
        refreshed
    }

    private suspend fun refreshOrSignIn(
        profile: RemoteGatewayProfile,
        tokens: GatewayNativeTokens,
        browser: GatewayBrowserLauncher?,
    ): GatewayNativeTokens =
        rotate(requireNotNull(profile.normalizedBaseUrl), tokens) ?: signIn(profile, browser)

    private suspend fun signIn(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher?,
    ): GatewayNativeTokens = login.login(
        profile,
        browser ?: throw GatewayAuthException("Sign in to this Gateway before reconnecting.", 401),
    ).also { tokens ->
        store.save(requireNotNull(profile.normalizedBaseUrl), tokens)
    }

    private fun GatewayNativeTokens.needsRefresh(now: Long): Boolean =
        expiresAt <= 0L || now >= expiresAt - REFRESH_SKEW_SECONDS

    private companion object {
        const val NATIVE_FLOW = "native_pkce"
        const val REFRESH_SKEW_SECONDS = 60L
    }
}

/** Bounded OkHttp implementation of the native-auth REST contract. */
internal class OkHttpGatewayNativeAuthApi(
    private val http: OkHttpClient,
) : GatewayNativeAuthApi {
    override suspend fun status(baseUrl: String): GatewayAuthStatus {
        val body = requestJson(endpoint(baseUrl, "api/status"), null, null)
        val flows = (body["auth_flows"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()
        return GatewayAuthStatus(
            authRequired = (body["auth_required"] as? JsonPrimitive)?.booleanOrNull == true,
            authFlows = flows,
        )
    }

    override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens =
        parseTokens(
            requestJson(
                endpoint(baseUrl, "auth/native/token"),
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("code_verifier", JsonPrimitive(verifier))
                },
                null,
            ),
        )

    override suspend fun refresh(
        baseUrl: String,
        refreshToken: String,
        provider: String,
    ): GatewayNativeTokens? = try {
        parseTokens(
            requestJson(
                endpoint(baseUrl, "auth/native/refresh"),
                buildJsonObject {
                    put("refresh_token", JsonPrimitive(refreshToken))
                    if (provider.isNotBlank()) put("provider", JsonPrimitive(provider))
                },
                null,
            ),
        )
    } catch (failure: GatewayAuthException) {
        if (failure.statusCode == 401) null else throw failure
    }

    override suspend fun mintWebSocketTicket(baseUrl: String, accessToken: String): String? = try {
        requestJson(endpoint(baseUrl, "api/auth/ws-ticket"), JsonObject(emptyMap()), accessToken)
            .string("ticket")
            ?.takeIf(String::isNotBlank)
            ?: throw GatewayAuthException("Hermes returned an invalid WebSocket ticket.")
    } catch (failure: GatewayAuthException) {
        if (failure.statusCode == 401 || failure.statusCode == 403) null else throw failure
    }

    private suspend fun requestJson(url: HttpUrl, body: JsonObject?, bearer: String?): JsonObject =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            bearer?.let { builder.header("Authorization", "Bearer $it") }
            if (body == null) {
                builder.get()
            } else {
                builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            }
            http.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw GatewayAuthException(
                        when (response.code) {
                            401, 403 -> "Hermes rejected this sign-in."
                            else -> "Hermes could not complete remote authentication."
                        },
                        response.code,
                    )
                }
                val source = response.body?.source()
                    ?: throw GatewayAuthException("Hermes returned an empty authentication response.")
                source.request(MAX_AUTH_BODY_BYTES + 1L)
                val bytes = source.buffer.readByteArray(minOf(source.buffer.size, MAX_AUTH_BODY_BYTES + 1L))
                try {
                    if (bytes.size > MAX_AUTH_BODY_BYTES) {
                        throw GatewayAuthException("Hermes returned an oversized authentication response.")
                    }
                    runCatching { JSON.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject }
                        .getOrElse { throw GatewayAuthException("Hermes returned an invalid authentication response.") }
                } finally {
                    bytes.fill(0)
                }
            }
        }

    private fun parseTokens(body: JsonObject): GatewayNativeTokens {
        val accessToken = body.string("access_token").orEmpty()
        if (accessToken.isBlank()) throw GatewayAuthException("Hermes returned no access token.")
        return GatewayNativeTokens(
            accessToken = accessToken,
            refreshToken = body.string("refresh_token").orEmpty(),
            expiresAt = (body["expires_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            provider = body.string("provider").orEmpty(),
            userId = body.string("user_id").orEmpty(),
        )
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_AUTH_BODY_BYTES = 64 * 1024
    }
}

/** Android/desktop-compatible RFC 8252 loopback login driver. */
internal class LoopbackGatewayNativeLogin(
    private val api: GatewayNativeAuthApi,
    private val random: SecureRandom = SecureRandom(),
    private val callbackReadTimeoutMillis: Int = CALLBACK_READ_TIMEOUT_MILLIS,
) : GatewayNativeLogin {
    override suspend fun login(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher,
    ): GatewayNativeTokens {
        val baseUrl = requireNotNull(profile.normalizedBaseUrl)
        val verifier = randomUrlToken(32)
        val state = randomUrlToken(24)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val listener = withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = CALLBACK_ACCEPT_POLL_MILLIS
            }
        }
        return try {
            val redirectUri = "http://127.0.0.1:${listener.localPort}/callback"
            browser.open(authorizeUrl(baseUrl, challenge, redirectUri, state, profile.provider))
            val code = withGatewayLoginTimeout(LOGIN_TIMEOUT_MILLIS) {
                awaitAuthorizationCode(listener, state)
            }
            api.exchange(baseUrl, code, verifier)
        } finally {
            runCatching { listener.close() }
        }
    }

    private suspend fun awaitAuthorizationCode(listener: ServerSocket, expectedState: String): String =
        runInterruptible(Dispatchers.IO) {
            while (true) {
                val socket = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    continue
                }
                socket.use {
                    socket.soTimeout = callbackReadTimeoutMillis
                    val target = try {
                        readRequestTarget(socket.getInputStream())
                    } catch (_: IOException) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        // Browsers may probe loopback before navigating to the callback.
                        // An idle or abandoned probe must not cancel the real sign-in.
                        continue
                    }
                    runCatching {
                        val output = socket.getOutputStream()
                        output.write(DONE_RESPONSE)
                        output.flush()
                    }
                    val parsed = ("http://127.0.0.1$target").toHttpUrlOrNull() ?: continue
                    val error = parsed.queryParameter("error")
                    if (error != null) throw GatewayAuthException("Hermes sign-in was cancelled or refused.")
                    val code = parsed.queryParameter("code") ?: continue
                    val state = parsed.queryParameter("state")
                    if (state != expectedState) {
                        throw GatewayAuthException("The sign-in callback did not match this request.")
                    }
                    return@runInterruptible code
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw GatewayAuthException("Hermes sign-in did not complete.")
        }

    private fun readRequestTarget(input: java.io.InputStream): String {
        val bytes = ByteArray(MAX_REQUEST_LINE_BYTES)
        var size = 0
        try {
            while (size < bytes.size) {
                val next = input.read()
                if (next < 0) break
                bytes[size++] = next.toByte()
                if (size >= 2 && bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()) break
            }
            if (size == bytes.size) throw GatewayAuthException("The sign-in callback was oversized.")
            val line = bytes.copyOf(size).toString(Charsets.US_ASCII).trim()
            val pieces = line.split(' ')
            if (pieces.size < 3 || pieces[0] != "GET") return "/"
            return pieces[1].takeIf { it.startsWith('/') } ?: "/"
        } finally {
            bytes.fill(0)
        }
    }

    private fun randomUrlToken(size: Int): String = ByteArray(size)
        .also(random::nextBytes)
        .let(::base64Url)

    private companion object {
        const val LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val CALLBACK_ACCEPT_POLL_MILLIS = 1_000
        const val CALLBACK_READ_TIMEOUT_MILLIS = 5_000
        const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        val DONE_RESPONSE = (
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n" +
                "<!doctype html><meta charset=\"utf-8\"><title>Signed in</title>" +
                "<p>Signed in to Hermes. You can return to the app.</p>"
            ).toByteArray(Charsets.UTF_8)
    }
}

internal suspend fun <T> withGatewayLoginTimeout(
    timeoutMillis: Long,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMillis) { block() }
} catch (_: TimeoutCancellationException) {
    throw GatewayAuthException("Sign-in timed out. Try again.", 408)
}

/** Authenticates and opens a socket to the host-owned Gateway. */
internal class RemoteGatewayConnector(
    private val authenticator: NativeGatewayAuthenticator,
    private val rpcOpen: suspend (String, String) -> GatewayRpcClient,
) {
    /** Bearer token for the connection-owned audio HTTP leg; null when absent. */
    suspend fun accessToken(profile: RemoteGatewayProfile): String? =
        profile.normalizedBaseUrl?.let { authenticator.tokens(it)?.accessToken }

    /** Rotate that bearer once, non-interactively, for a refused REST leg. */
    suspend fun refreshAccessToken(profile: RemoteGatewayProfile): Boolean =
        authenticator.refreshAccessToken(profile)

    suspend fun open(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher?): GatewayRpcClient {
        val baseUrl = profile.normalizedBaseUrl
            ?: throw GatewayAuthException("Enter a valid HTTPS Gateway URL.")
        val ticket = authenticator.ticket(profile.copy(baseUrl = baseUrl), browser)
        return rpcOpen(baseUrl, ticket)
    }

    suspend fun signOut(profile: RemoteGatewayProfile) = authenticator.signOut(profile)
}

internal fun normalizeRemoteGatewayUrl(raw: String): String? {
    val parsed = raw.trim().toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "https") return null
    if (parsed.host.isBlank() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
    if (parsed.querySize > 0 || parsed.fragment != null) return null
    return parsed.newBuilder()
        .encodedPath(parsed.encodedPath.trimEnd('/').ifBlank { "/" })
        .build()
        .toString()
        .trimEnd('/')
}

internal fun endpoint(baseUrl: String, path: String): HttpUrl =
    requireNotNull(normalizeRemoteGatewayUrl(baseUrl)?.toHttpUrlOrNull())
        .newBuilder()
        .addPathSegments(path)
        .build()

internal fun authorizeUrl(
    baseUrl: String,
    challenge: String,
    redirectUri: String,
    state: String,
    provider: String,
): String = endpoint(baseUrl, "auth/native/authorize").newBuilder()
    .addQueryParameter("code_challenge", challenge)
    .addQueryParameter("code_challenge_method", "S256")
    .addQueryParameter("redirect_uri", redirectUri)
    .addQueryParameter("state", state)
    .apply { if (provider.isNotBlank()) addQueryParameter("provider", provider) }
    .build()
    .toString()

private fun base64Url(bytes: ByteArray): String = try {
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
} finally {
    bytes.fill(0)
}
