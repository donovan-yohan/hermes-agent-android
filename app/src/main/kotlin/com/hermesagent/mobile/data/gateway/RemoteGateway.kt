package com.hermesagent.mobile.data.gateway

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
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
import okhttp3.ConnectionPool
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

    /**
     * A Hermes the person runs on this same device, in Termux, reached over
     * loopback. The app never hosts or starts it — see [LocalGatewayProfile].
     */
    Local,
}

/** Non-secret configuration for a host-owned Remote Gateway. */
data class RemoteGatewayProfile(
    val baseUrl: String = "",
    val provider: String = "",
    /**
     * Which saved connection's Keystore slot holds this Gateway's sign-in.
     *
     * A local, random row id — not an endpoint, an account, or anything the
     * Gateway is told. Blank means "no registry row supplied one", which the
     * token store reads as the pre-registry, URL-derived slot so an install
     * that upgrades is not silently signed out.
     */
    val secretSlotId: String = "",
) {
    val normalizedBaseUrl: String?
        get() = normalizeRemoteGatewayUrl(baseUrl)

    val isValid: Boolean get() = normalizedBaseUrl != null

    /** The one secret slot this profile may read or write; null when the URL is unusable. */
    internal val secretSlot: GatewaySecretSlot?
        get() = normalizedBaseUrl?.let { GatewaySecretSlot(secretSlotId, it) }

    /**
     * The slot to erase, which must be reachable even when [secretSlot] is not.
     *
     * A Remote row whose URL was blanked or mistyped still owns a file, and
     * "we cannot parse where this credential was for" is the worst possible
     * reason to leave a credential on disk. Null only when there is nothing
     * addressable at all — no row and no usable URL.
     */
    internal val eraseSlot: GatewaySecretSlot?
        get() = when {
            secretSlotId.isNotBlank() -> GatewaySecretSlot(secretSlotId, normalizedBaseUrl)
            else -> normalizedBaseUrl?.let { GatewaySecretSlot("", it) }
        }
}

/**
 * The active row's route: which row it is, which way it connects, and the
 * Gateway it names — as one value.
 *
 * Three projections of one saved row are three emissions, and a reader that
 * pairs them observes tuples the store never held: a row's id beside the route
 * and address of the row before it. A single value cannot be seen half-changed,
 * which is the only form of that guarantee a reader can actually rely on.
 */
data class ActiveGatewayRoute(
    val connectionId: String? = null,
    val mode: GatewayConnectionMode = GatewayConnectionMode.Remote,
    val remote: RemoteGatewayProfile = RemoteGatewayProfile(),
)

/** Persisted remote route settings. Tokens deliberately live behind [GatewayTokenStore]. */
interface RemoteGatewayProfileStore {
    val remoteGatewayProfile: Flow<RemoteGatewayProfile>
    val gatewayConnectionMode: Flow<GatewayConnectionMode>

    /**
     * The active row's Local route, when it has one. A store that keeps no
     * Local row reports an empty profile, which is never valid — the same
     * answer a fresh install gives.
     */
    val localGatewayProfile: Flow<LocalGatewayProfile>
        get() = flowOf(LocalGatewayProfile())

    /**
     * [gatewayConnectionMode] and [remoteGatewayProfile] together with the row
     * they are projections *of*.
     *
     * A surface that renders all three reads this rather than combining those
     * two, so a switch reaches it as one change. The identity has to come from
     * the store because the values cannot carry it: two rows can name the same
     * URL, the same route, or both. A store with no registry answers the fresh
     * defaults, and keeps answering them.
     */
    val activeGatewayRoute: Flow<ActiveGatewayRoute>
        get() = flowOf(ActiveGatewayRoute())

    /**
     * Writes the active row's Gateway URL and provider, unless the edit was
     * composed against a row that is no longer active.
     *
     * [RemoteGatewayProfile.secretSlotId] names the row the caller was editing.
     * A non-blank one that no longer matches is dropped rather than written
     * where it landed: the route form has no discrete save — it persists on
     * every keystroke — so one of its writes can still be in flight when the
     * marker moves, and the row a character was typed against is the only thing
     * that tells that apart from an edit of the row now active. A blank stamp is
     * a caller with no row in mind and writes wherever the marker points.
     */
    suspend fun saveRemoteGatewayProfile(profile: RemoteGatewayProfile)

    /**
     * The same rule for the route itself, which is a bare enum and cannot carry
     * the row, so [expectedConnectionId] names it instead. Null means the caller
     * has no row in mind.
     *
     * Returns whether the change was written. This one costs the live connection
     * and can erase the session token of a row that has stopped naming a
     * loopback address, so its caller has to know: a dropped write moved
     * nothing, and everything those two acts would reach now belongs to the row
     * that replaced it.
     */
    suspend fun saveGatewayConnectionMode(
        mode: GatewayConnectionMode,
        expectedConnectionId: String? = null,
    ): Boolean
}

/**
 * Opens the sign-in page. The callback URI is always a temporary loopback
 * listener this app binds first and closes when the flow ends.
 *
 * The two defaulted members are what make the hand-off survive a real phone.
 * A plain `ACTION_VIEW` leaves this app cached with nothing raising its
 * importance, and on Android 12+ the cached-app freezer then SIGSTOPs it: the
 * kernel still accepts the browser's callback into the listener's backlog, but
 * the loop that would read it never runs, and the Gateway's authorization code
 * expires 120 s later (hermes-agent @
 * `29112bef099274229cadff79cdff7bf7b99c4b77`,
 * `hermes_cli/dashboard_auth/native_flow.py:89`). A held service binding is
 * what keeps the process runnable across that window.
 */
fun interface GatewayBrowserLauncher {
    suspend fun open(url: String)

    /**
     * Binds whatever keeps this process running while the browser is in front
     * of it, before [open]. The returned handle is closed exactly where the
     * loopback listener is closed, because the two protect the same window.
     *
     * Null means this launcher has nothing to bind — no Custom Tabs provider,
     * or a caller (a test, a headless fake) that never leaves the process. The
     * flow still runs; it is only unprotected against the freezer.
     */
    suspend fun bindForSignIn(): AutoCloseable? = null

    /**
     * Brings the app forward once a callback has been *accepted*, so finishing
     * in the browser finishes in the app. Never called for a callback this app
     * rejected: the person is left where the refusal is explained.
     */
    suspend fun returnToApp() = Unit
}

/**
 * Every step the sign-in hand-off can report, and the whole vocabulary of it.
 *
 * An enum rather than a string because the rule this seam exists under — a step
 * name and nothing else, never an authorization code, a `state`, a token, a
 * Gateway URL or a port — is not enforceable as prose. This reaches logcat,
 * which every app on the device could read before Android 11 and which crash
 * reporters still collect, so there must be no way to interpolate a value into
 * it at a call site.
 */
internal enum class GatewaySignInStep(private val label: String) {
    SignInStartFailed("could not start the sign-in"),
    ListenerBound("callback listener bound"),
    ForegroundHeld("held in the foreground"),
    ForegroundUnavailable("no foreground hold"),
    BrowserBindFailed("browser service bind failed"),
    BrowserBound("browser service bound"),
    BrowserUnbound("no browser service to bind"),
    FellBackToBrowser("fell back to the default browser"),
    BrowserLaunchFailed("browser would not open"),
    CallbackReceived("callback received"),
    CallbackAccepted("callback accepted"),
    CodeReceived("authorization code in hand"),
    SignInAbandoned("sign-in abandoned"),
    CallbackRefused("callback reported a refusal"),
    StateMismatch("callback state did not match"),
    ListenerClosed("callback listener closed early"),
    ExchangeStarting("token exchange starting"),
    ExchangeDispatched("token exchange reached the wire thread"),
    ExchangeRequesting("token exchange request going out"),
    ExchangeAnswered("token exchange answered"),
    ExchangeRefused("token exchange refused"),
    ExchangeRetrying("token exchange retrying, unanswered"),
    ExchangeTimedOut("token exchange timed out"),
    ExchangeFailed("token exchange failed"),
    TokensStored("sign-in stored"),
    ReturnBlocked("could not bring the app forward"),
    ;

    override fun toString(): String = "sign-in: $label"
}

/**
 * Breadcrumbs for the sign-in hand-off, in the one place a silent failure used
 * to be indistinguishable from a hang.
 */
/**
 * The whole cause chain of a failure, rendered as types and nothing else.
 *
 * A wrapper's own class name is almost never the answer. r7 spent a device run
 * on `failed (GatewayUnansweredException)` — the name of the envelope this code
 * puts round every unanswered call, which says only "the call failed", which was
 * already known. What was needed was what it wrapped.
 *
 * Types only, walked through `cause`, deduplicated against cycles and capped:
 * a throwable's *message* routinely carries a hostname, a path or a URL, and
 * this reaches logcat.
 */
/**
 * Whether this throwable is *this* coroutine being cancelled, rather than a
 * `CancellationException` something downstream raised as a value.
 *
 * The two are the same type and mean opposite things. This package throws
 * `CancellationException` deliberately, as an "abandon this attempt" signal
 * decoupled from any `Job.cancel()` (`GatewayConnection.kt`: the abort paths
 * around `openRemote` and the reconnect loop), so a catch that reads the type
 * alone cannot tell "the person abandoned this" from "the thing I called gave
 * up". Only the context answers it: a coroutine that is genuinely being
 * cancelled is no longer active.
 *
 * Structured concurrency requires the first kind to propagate. The second is an
 * ordinary failure and belongs to whatever the catch site does with those.
 */
internal suspend fun Throwable.cancelsThisCoroutine(): Boolean =
    this is CancellationException && !currentCoroutineContext().isActive

internal fun throwableChain(failure: Throwable, limit: Int = THROWABLE_CHAIN_LIMIT): String {
    val names = mutableListOf<String>()
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = failure
    while (current != null && names.size < limit && seen.add(current)) {
        names += current.javaClass.name
        current = current.cause
    }
    if (current != null) names += "..."
    return names.joinToString(" < ")
}

private const val THROWABLE_CHAIN_LIMIT = 6

/**
 * Holds this process in the foreground for the length of a sign-in.
 *
 * Not an optimisation. Android 17 blocks a cached app's uid from the network
 * outright (`blocked=APP_BACKGROUND`) and destroys its live sockets, so the
 * token exchange fails with `UnknownHostException` before it reaches a network
 * at all — see [SignInForegroundService] for the measurements. Null means the
 * platform refused the hold; the flow continues, because a sign-in that might
 * work is better than one that certainly will not.
 */
internal fun interface GatewaySignInForeground {
    fun hold(): AutoCloseable?
}

/**
 * Waits for the default network to look usable again, bounded.
 *
 * A seam because the retry below is worthless if the second attempt is
 * identical to the first, and because "is there a working network yet" is a
 * platform question a test must be able to answer instantly.
 */
internal fun interface GatewayNetworkGate {
    suspend fun awaitUsable(timeoutMillis: Long)
}

internal fun interface GatewaySignInLog {
    fun step(step: GatewaySignInStep)

    /**
     * A step that failed, and the *type* of what went wrong.
     *
     * The type only. A throwable's message routinely carries a host, a path or
     * a URL, and this reaches logcat. The type alone is what turns "it failed
     * somewhere" into one grep on a device — which is exactly what was missing
     * when a crash in this app's own sign-in plumbing surfaced as "check the
     * host and reconnect".
     */
    fun failed(step: GatewaySignInStep, cause: Throwable) = step(step)
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

/**
 * Where one saved connection's Gateway sign-in is kept.
 *
 * [connectionId] is the registry row, and it is what the slot is named after,
 * so removing a connection removes exactly that connection's credential and
 * nothing else. [normalizedBaseUrl] is carried alongside rather than as the
 * key: it still identifies the pre-registry slot an upgrading install has on
 * disk, and it is what the token store adopts once, into the row's slot.
 */
internal data class GatewaySecretSlot(
    val connectionId: String,
    /**
     * The Gateway this slot's credential is for. Null when the slot is only
     * being *addressed* — erasing a row whose URL was blanked or mistyped still
     * has to reach that row's file, and a row id is enough to name it. Reading
     * or writing a credential always requires it.
     */
    val normalizedBaseUrl: String?,
) {
    init {
        require(connectionId.isNotBlank() || !normalizedBaseUrl.isNullOrBlank()) {
            "A secret slot needs a connection id, a Gateway URL, or both."
        }
    }

    companion object {
        /** Address a row's slot by id alone, for erasure. */
        fun forRow(connectionId: String): GatewaySecretSlot = GatewaySecretSlot(connectionId, null)
    }
}

internal interface GatewayTokenStore {
    suspend fun load(slot: GatewaySecretSlot): GatewayNativeTokens?
    suspend fun save(slot: GatewaySecretSlot, tokens: GatewayNativeTokens)
    suspend fun clear(slot: GatewaySecretSlot)
}

/**
 * The same slots, holding the other kind of credential this app can be given:
 * a Hermes dashboard session token, which is static for the life of the server
 * process and has no refresh (`hermes_cli/web_server.py:499-504` @
 * `29112bef099274229cadff79cdff7bf7b99c4b77`).
 *
 * A separate interface rather than more methods on [GatewayTokenStore] because
 * the two credentials have no caller in common: the Local route never signs
 * in, and the Remote route never presents a session token. One slot still holds
 * at most one of them — a saved connection has exactly one kind.
 */
internal interface GatewaySessionTokenStore {
    /**
     * What this slot has to say, which is three answers rather than two: a
     * token, an empty slot, or a refusal. The caller owns and must zero the
     * bytes of a [SessionTokenRead.Found].
     */
    suspend fun loadSessionToken(slot: GatewaySecretSlot): SessionTokenRead

    /** Stores one session token. Takes ownership of [token] and zeroes it. */
    suspend fun saveSessionToken(slot: GatewaySecretSlot, token: ByteArray)

    /** Erases whatever this slot holds. Addressable by row id alone. */
    suspend fun clearSessionToken(slot: GatewaySecretSlot)
}

/**
 * The Gateway never answered: the call failed before any response existed —
 * name resolution, connect, or the call timeout expiring with nothing back.
 *
 * The distinction is the whole reason this type exists. An authorization code
 * is single use, so it may only be presented a second time when it is certain
 * it was never presented at all. Anything thrown once a response is in hand is
 * an ordinary failure and is never retried.
 */
internal class GatewayUnansweredException(cause: Throwable) : IOException(cause)

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
    /** No-op by default for the same reason [LoopbackGatewayNativeLogin]'s is. */
    private val log: GatewaySignInLog = GatewaySignInLog {},
) {
    /**
     * Serializes load → refresh → save for one process.
     *
     * `/auth/native/refresh` hands back a *new* access/refresh pair in its
     * response body, and answers a refresh token every provider rejects —
     * dead, expired, or reuse-detected — with a 401 (hermes-agent @
     * `29112bef099274229cadff79cdff7bf7b99c4b77`,
     * `hermes_cli/dashboard_auth/routes.py:1027-1079`; where the presented
     * token is actually retired is the session provider's business, which that
     * route does not show). Two callers — the
     * reconnect path's [ticket] and a REST leg's [refreshAccessToken] — that
     * POST the same one-time token race each other into a rejection, and their
     * two [GatewayTokenStore.save] calls race over which rotation survives.
     * Interactive sign-in deliberately happens *outside* this lock, so a
     * browser round trip can never park another caller behind it.
     */
    private val rotation = Mutex()

    /** Stored tokens without triggering a sign-in flow; null when absent. */
    suspend fun tokens(profile: RemoteGatewayProfile): GatewayNativeTokens? =
        profile.secretSlot?.let { store.load(it) }

    suspend fun ticket(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher?): String {
        val baseUrl = profile.normalizedBaseUrl
            ?: throw GatewayAuthException("Enter a valid HTTPS Gateway URL.")
        // A readable/writable slot always has this URL; `secretSlot` is null
        // only when `normalizedBaseUrl` is, which the line above already threw
        // on.
        val slot = requireNotNull(profile.secretSlot)
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

        var tokens = store.load(slot)
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
     * on a network refresh. [rotate] re-checks *which* credential the store
     * holds before it writes, so a clear that lands mid-rotation still wins —
     * and so does whatever the person signs in as next.
     */
    suspend fun signOut(profile: RemoteGatewayProfile) {
        profile.eraseSlot?.let { store.clear(it) }
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
        val slot = profile.secretSlot ?: return false
        val observed = store.load(slot) ?: return false
        return rotate(slot, observed) != null
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
     * The store is read once more after the network call, and what it has to
     * still hold is the *exact refresh token this rotation spent* — identity,
     * not presence. Both of the store's other writers are deliberately outside
     * this lock: [signOut], because parking a person's sign out behind a
     * bounded network refresh would be worse than the window it closes, and
     * interactive sign-in, because a browser round trip must never park
     * another caller behind it. Between the POST and the save, then, the store
     * can be cleared *and* filled again — possibly as a different account. A
     * presence check reads that store as fine and writes the pre-sign-out
     * identity over the credential the person just signed in as; only identity
     * catches it. Either way the save silently undoes the person's most recent
     * action.
     *
     * Null means no rotation is available — nothing stored, nothing to rotate
     * with, the Gateway refused, or the stored credential changed underneath
     * the rotation. It is never a partial success: the stored tokens are
     * replaced only when a whole new set arrives.
     */
    private suspend fun rotate(
        slot: GatewaySecretSlot,
        observed: GatewayNativeTokens,
    ): GatewayNativeTokens? = rotation.withLock {
        // An already-cleared store is a deliberate "there is nothing here";
        // rotating [observed] anyway would resurrect what was just cleared.
        val current = store.load(slot) ?: return@withLock null
        if (current.refreshToken != observed.refreshToken) return@withLock current
        val refreshToken = current.refreshToken.takeIf(String::isNotBlank) ?: return@withLock null
        // A slot that reached a rotation was built from a usable URL.
        val refreshed = api.refresh(requireNotNull(slot.normalizedBaseUrl), refreshToken, current.provider)
            ?: return@withLock null
        // Same question again, because the answer can have changed while the
        // refresh was on the wire — and it is the same question: not "is
        // anything stored" but "is this still the credential I am rotating".
        if (store.load(slot)?.refreshToken != refreshToken) return@withLock null
        store.save(slot, refreshed)
        refreshed
    }

    private suspend fun refreshOrSignIn(
        profile: RemoteGatewayProfile,
        tokens: GatewayNativeTokens,
        browser: GatewayBrowserLauncher?,
    ): GatewayNativeTokens =
        rotate(requireNotNull(profile.secretSlot), tokens) ?: signIn(profile, browser)

    /**
     * One interactive sign-in: get the tokens, persist them, and only then try
     * to bring the app back.
     *
     * The order is the whole point. The authorization code is single use and
     * expires in 120 s, so everything up to persistence is irreversible work on
     * a deadline; coming forward is a courtesy that can be denied. Android
     * withdraws an app's background activity-launch grace about ten seconds
     * after it loses the foreground, which is well inside the time a person
     * spends typing a password — so the launch this makes is expected to fail
     * sometimes, and it must cost nothing when it does. A person who never gets
     * pulled back returns to the app themselves, which is what they were about
     * to do anyway, and finds it connected.
     *
     * The sanctioned way to pull someone back from the background is a
     * notification, and it is deliberately not done here: [NotificationSurface]
     * is keyed by durable session and kind across two channels that are both
     * about a session's work, so this would need a third channel, its own copy,
     * and its own intent shape — and it would depend on a `POST_NOTIFICATIONS`
     * grant this app asks for once, at the first live Gateway, and never
     * re-asks for. That is a change worth making on its own evidence rather
     * than inside this fix; it is filed on #114.
     */
    private suspend fun signIn(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher?,
    ): GatewayNativeTokens {
        val launcher = browser
            ?: throw GatewayAuthException("Sign in to this Gateway before reconnecting.", 401)
        val tokens = login.login(profile, launcher)
        store.save(requireNotNull(profile.secretSlot), tokens)
        log.step(GatewaySignInStep.TokensStored)
        try {
            launcher.returnToApp()
        } catch (failure: Throwable) {
            // Only a cancellation of *this* coroutine is allowed past here: it
            // is a decision, and something abandoned the sign-in. A
            // `CancellationException` a launcher raised on its own, while this
            // job is perfectly alive, is a post-persist failure of the
            // *courtesy* — and rethrowing on the type discarded a sign-in
            // already spent and on disk, leaving the person reading
            // "cancelled" about something that had finished.
            if (failure.cancelsThisCoroutine()) throw failure
            // A blocked launch is the ordinary case, not an error worth showing
            // anyone: the sign-in is already done and saved.
            log.failed(GatewaySignInStep.ReturnBlocked, failure)
        }
        return tokens
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
    http: OkHttpClient,
    private val log: GatewaySignInLog = GatewaySignInLog {},
    callTimeoutMillis: Long = AUTH_CALL_TIMEOUT_MILLIS,
    /**
     * No-op by default, and the process wires the real one — the same
     * convention as [log], and the same reason: a unit test must not wait on a
     * platform it does not have.
     */
    private val networkGate: GatewayNetworkGate = GatewayNetworkGate { },
) : GatewayNativeAuthApi {
    /**
     * The shared client with a *call* timeout, and this is the bound that
     * matters.
     *
     * `connectTimeout` does not cover name resolution: OkHttp's `Dns.SYSTEM` is
     * `InetAddress.getAllByName`, which blocks in the platform resolver with no
     * timeout of its own. A backgrounded process whose default network was
     * re-evaluated underneath it can sit in that call indefinitely — no bytes
     * sent, no exception, nothing to report — which is exactly what a device
     * showed for 96 s with every breadcrumb around it clean. `callTimeout`
     * covers the whole call including that leg, and a coroutine-side `withTimeout`
     * cannot substitute for it: cancelling a coroutine does not unblock a thread
     * already inside a blocking socket or resolver call.
     *
     * Derived with `newBuilder` so it keeps the shared connection pool and
     * dispatcher; only these short auth legs get the bound.
     */
    private val http: OkHttpClient = http.newBuilder()
        .callTimeout(callTimeoutMillis, TimeUnit.MILLISECONDS)
        // A pool of its own. The retry below evicts it, and evicting the shared
        // pool would drop the RPC and media connections of a session that has
        // nothing to do with signing in.
        .connectionPool(ConnectionPool())
        .build()

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

    /**
     * A code the Gateway will not redeem is a 400, and deliberately a generic
     * one: unknown, expired, already redeemed and PKCE mismatch are one reply,
     * so the code is consumed on every path and there is no verifier oracle
     * (hermes-agent @ `29112bef099274229cadff79cdff7bf7b99c4b77`,
     * `hermes_cli/dashboard_auth/routes.py:988-1005`). The app cannot tell
     * those apart either, so it says the one thing they have in common and the
     * one action that fixes all of them.
     *
     * Rethrown without a status code, which is what marks it terminal for the
     * automatic redial ([Throwable.isRetryableRemoteConnectionFailure]): only a
     * fresh sign-in can produce another code, and a retry loop cannot.
     */
    override suspend fun exchange(baseUrl: String, code: String, verifier: String): GatewayNativeTokens = try {
        // (a) Before the dispatch. If this is the last thing a device shows, the
        // coroutine never reached the wire thread at all.
        log.step(GatewaySignInStep.ExchangeStarting)
        redeem(baseUrl, code, verifier, retryUnanswered = true)
    } catch (failure: GatewayAuthException) {
        if (failure.statusCode == 400) throw GatewayAuthException(GatewaySignInCopy.EXPIRED_CODE)
        throw failure
    }

    /**
     * One presentation of the code, retried exactly once when the Gateway never
     * answered.
     *
     * The stall this exists for is name resolution on a connection handle the
     * default-network re-evaluation killed, seconds after the person left for
     * the browser. By the time the call timeout expires the new network resolves
     * immediately, so the retry is not a hope — it is the same request on a
     * working network, and it turns the scenario into a sign-in instead of a
     * polite failure. The code's 120 s life leaves room for both attempts.
     *
     * It is safe even in the case it cannot distinguish. If the request did
     * reach the Gateway and only its answer was lost, the code is spent and the
     * retry gets a 400 — which surfaces as "sign in again", exactly what the
     * un-retried timeout would have surfaced. The retry can turn a failure into
     * a success and cannot turn a success into a failure.
     */
    private suspend fun redeem(
        baseUrl: String,
        code: String,
        verifier: String,
        retryUnanswered: Boolean,
    ): GatewayNativeTokens = try {
        parseTokens(
            requestJson(
                endpoint(baseUrl, "auth/native/token"),
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    put("code_verifier", JsonPrimitive(verifier))
                },
                null,
                trace = true,
            ),
        )
    } catch (unanswered: GatewayUnansweredException) {
        if (!retryUnanswered) throw unanswered
        log.step(GatewaySignInStep.ExchangeRetrying)
        // An immediate retry down the same pooled socket is not a second
        // attempt, it is the same attempt twice — r7 measured 2 ms between them
        // and an identical failure. Evict, then give the platform a bounded
        // moment to have a usable network, so attempt two can actually differ.
        // Note this cannot be the whole story on its own: OkHttp's
        // `retryOnConnectionFailure` is on by default and is not overridden
        // anywhere here, so it already recovers a stale pooled connection by
        // itself. If a named cause turns out to be policy rather than plumbing,
        // this hardening will not help and the name is what drives the fix.
        withContext(Dispatchers.IO) { runCatching { http.connectionPool.evictAll() } }
        networkGate.awaitUsable(RETRY_NETWORK_WAIT_MILLIS)
        redeem(baseUrl, code, verifier, retryUnanswered = false)
    }

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

    /**
     * [trace] instruments the one leg that has gone dark on a device. The other
     * legs share this body but not its breadcrumbs, because a status probe
     * failing is loud already.
     */
    private suspend fun requestJson(
        url: HttpUrl,
        body: JsonObject?,
        bearer: String?,
        trace: Boolean = false,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            // (b) First line inside the dispatch. Reaching this and not (c)
            // separates "the continuation never ran" from "the call blocked".
            if (trace) log.step(GatewaySignInStep.ExchangeDispatched)
            val builder = Request.Builder().url(url)
            bearer?.let { builder.header("Authorization", "Bearer $it") }
            if (body == null) {
                builder.get()
            } else {
                builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            }
            // (c) About to execute. Everything past here is OkHttp: name
            // resolution, connect, write, read.
            if (trace) log.step(GatewaySignInStep.ExchangeRequesting)
            // Only `execute()` is wrapped. Everything it throws happened before
            // a response existed; everything thrown inside `use` happened after
            // one did, and that difference decides whether a single-use code may
            // be presented again.
            val call = http.newCall(builder.build())
            val answer = try {
                call.execute()
            } catch (unanswered: IOException) {
                throw GatewayUnansweredException(unanswered)
            }
            answer.use { response ->
                // (d) Something came back.
                if (trace) log.step(GatewaySignInStep.ExchangeAnswered)
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

    /** What the whole call is bounded by, so a test can pin that it is bounded at all. */
    internal val callTimeoutMillis: Int get() = http.callTimeoutMillis

    /** The auth legs' own pool, so a test can pin that evicting it disturbs nothing else. */
    internal val connectionPool: ConnectionPool get() = http.connectionPool

    internal companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_AUTH_BODY_BYTES = 64 * 1024

        /**
         * Every auth leg is one small request and one small response. Nothing
         * here may hang: a stuck sign-in costs a single-use code with a 120 s
         * life, so the whole call is bounded well inside that.
         */
        const val AUTH_CALL_TIMEOUT_MILLIS = 15_000L

        /** Bounded wait for a usable network before the one retry. */
        const val RETRY_NETWORK_WAIT_MILLIS = 3_000L
    }
}

/**
 * What a person is told when a native sign-in does not finish.
 *
 * Each one names a different thing that happened and the one safe next step,
 * because before this they were all the same silence. None of them echoes a
 * code, a `state` value, a token or a host.
 */
internal object GatewaySignInCopy {
    // There is deliberately no "that reply did not match" message. A callback
    // carrying the wrong `state` is indistinguishable from any other process on
    // the device probing the loopback port, so it is ignored rather than
    // reported, and a sign-in that never receives its real callback ends at the
    // timeout below instead. See the `state` gate in [LoopbackGatewayNativeLogin].
    const val REFUSED =
        "Hermes sign-in was cancelled or refused. Sign in again to continue."
    const val LISTENER_CLOSED =
        "Hermes stopped waiting for the browser before sign-in finished. Sign in again."
    const val EXPIRED_CODE =
        "The sign-in took too long to finish, so Hermes would not accept it. Sign in again."
    const val CANCELLED =
        "Sign-in was cancelled before it finished. Sign in again when you are ready."
    const val EXCHANGE_TIMED_OUT =
        "Finishing sign-in took too long to reach the Gateway. Sign in again."
    const val EXCHANGE_FAILED =
        "Hermes could not finish sign-in with the Gateway. Sign in again."
    const val INTERRUPTED =
        "Sign-in was interrupted before Hermes could finish it. Sign in again."
    const val NO_BROWSER =
        "This device has no browser to sign in with. Install one, then sign in again."
    const val START_FAILED =
        "Hermes could not start sign-in on this device. Try again, and reconnect from Gateways if it repeats."
    const val BROWSER_LAUNCH_FAILED =
        "Hermes could not open the browser to sign in. Try again, or set a default browser first."
}

/** Any free loopback port; the Gateway is told which one in `redirect_uri`. */
private const val CALLBACK_ANY_PORT = 0

/**
 * Above one on purpose: browsers open speculative connections to a loopback
 * origin before navigating to it, and with a single slot one idle probe is
 * enough for the kernel to refuse the callback that matters.
 */
private const val CALLBACK_BACKLOG = 4

/** How often `accept()` returns so the flow can notice it was cancelled. */
private const val CALLBACK_ACCEPT_POLL_MILLIS = 1_000

/**
 * How long one accepted connection may take to produce its request line.
 *
 * Short on purpose, and shorter than it was. The accept loop is single
 * threaded, so every silent browser preconnect holds the real callback behind
 * it for this long — and raising [CALLBACK_BACKLOG] admits more of them. A
 * genuine callback's request line arrives with the connection; only a probe
 * ever spends this budget.
 */
private const val CALLBACK_READ_TIMEOUT_MILLIS = 1_500

/** The loopback listener a native sign-in redirects back to. */
private fun loopbackCallbackListener(): ServerSocket =
    ServerSocket(CALLBACK_ANY_PORT, CALLBACK_BACKLOG, InetAddress.getByName("127.0.0.1")).apply {
        soTimeout = CALLBACK_ACCEPT_POLL_MILLIS
    }

/** Android/desktop-compatible RFC 8252 loopback login driver. */
internal class LoopbackGatewayNativeLogin(
    private val api: GatewayNativeAuthApi,
    private val random: SecureRandom = SecureRandom(),
    private val callbackReadTimeoutMillis: Int = CALLBACK_READ_TIMEOUT_MILLIS,
    /**
     * Deliberately a no-op by default. `android.util.Log` is not mocked in this
     * project's JVM unit tests (`app/build.gradle.kts:71-77`), so a default that
     * reached it would make every plain unit test of this class throw. The
     * process wires the real one in `HermesApplication`; tests pass a recorder.
     */
    private val log: GatewaySignInLog = GatewaySignInLog {},
    /** Test seam: the callback listener, so a test can close it mid-flow. */
    private val openListener: () -> ServerSocket = ::loopbackCallbackListener,
    /**
     * Test seam. Production waits the full window because a person really may
     * take minutes in a provider's browser; a test fixture that cannot deliver
     * its callback must fail in seconds rather than stall a CI job for five
     * minutes.
     */
    private val loginTimeoutMillis: Long = LOGIN_TIMEOUT_MILLIS,
    /**
     * Bounds everything after the code is in hand. A person waits minutes for a
     * browser; a Gateway answering a token POST does not get minutes, and the
     * code it is redeeming expires in 120 s anyway.
     */
    private val exchangeTimeoutMillis: Long = EXCHANGE_TIMEOUT_MILLIS,
    /**
     * No-op by default; the process wires the real one, the same convention as
     * [log]. A test has no service to start and must not try.
     */
    private val foreground: GatewaySignInForeground = GatewaySignInForeground { null },
) : GatewayNativeLogin {
    override suspend fun login(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher,
    ): GatewayNativeTokens {
        val baseUrl = requireNotNull(profile.normalizedBaseUrl)
        val verifier: String
        val state: String
        val challenge: String
        val listener: ServerSocket
        try {
            verifier = randomUrlToken(32)
            state = randomUrlToken(24)
            challenge = base64Url(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            )
            listener = withContext(Dispatchers.IO) { openListener() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // None of this is the person's doing and none of it is the host's:
            // a device that will not bind a loopback port, or a security
            // provider with no SHA-256, used to reach the UI as "check the host
            // and reconnect" — pointing at the one thing demonstrably fine. The
            // type is logged so a device run localizes in one grep.
            log.failed(GatewaySignInStep.SignInStartFailed, failure)
            throw GatewayAuthException(GatewaySignInCopy.START_FAILED)
        }
        // The browser binding and the listener have one lifetime. The binding is
        // the only thing keeping this process runnable while the tab is in front
        // of it, and the listener is what it is protecting, so the binding is
        // released in the same `finally` that closes the socket — never earlier.
        // The app is brought forward before the token exchange, but "forward"
        // is a request to the window manager, not a guarantee it has happened
        // by the time the exchange needs the process to still be running.
        var binding: AutoCloseable? = null
        var held: AutoCloseable? = null
        return try {
            val redirectUri = "http://127.0.0.1:${listener.localPort}/callback"
            log.step(GatewaySignInStep.ListenerBound)
            // Before the browser, and that ordering is not cosmetic: Android 12+
            // refuses to let a process that is already in the background start a
            // foreground service, so the one moment this can be claimed is while
            // the tap that started it still has the app in front.
            held = try {
                foreground.hold()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: Throwable) {
                log.failed(GatewaySignInStep.ForegroundUnavailable, refused)
                null
            }
            if (held != null) log.step(GatewaySignInStep.ForegroundHeld)
            else log.step(GatewaySignInStep.ForegroundUnavailable)
            // A browser service that will not bind costs the freezer protection
            // and nothing else. It must never cost the sign-in, so this degrades
            // to the same unbound path a device with no provider takes.
            binding = try {
                browser.bindForSignIn()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                log.failed(GatewaySignInStep.BrowserBindFailed, failure)
                null
            }
            log.step(if (binding != null) GatewaySignInStep.BrowserBound else GatewaySignInStep.BrowserUnbound)
            try {
                browser.open(authorizeUrl(baseUrl, challenge, redirectUri, state, profile.provider))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (domain: GatewayAuthException) {
                throw domain
            } catch (failure: Throwable) {
                log.failed(GatewaySignInStep.BrowserLaunchFailed, failure)
                throw GatewayAuthException(GatewaySignInCopy.BROWSER_LAUNCH_FAILED)
            }
            val code = withGatewayLoginTimeout(loginTimeoutMillis) {
                awaitAuthorizationCode(listener, state)
            }
            // Deliberately its own step. `CallbackAccepted` is logged from
            // inside the blocking accept loop, which completes even when the
            // coroutine around it has been cancelled — so "accepted, then
            // nothing" says the callback arrived and tells you nothing about
            // why it went no further. This one is logged after the resumption,
            // and it is the line that separates "cancelled while waiting" from
            // "the exchange itself failed".
            log.step(GatewaySignInStep.CodeReceived)
            // Deliberately nothing between accepting the callback and spending
            // it. Coming forward used to happen here, and after ~10 s in the
            // background Android withdraws this app's activity-launch grace: a
            // blocked or throwing launch unwound the whole flow between
            // `CallbackAccepted` and the exchange, so a person who took ten
            // seconds to type a password lost a sign-in that had already
            // succeeded. Whoever owns persistence owns coming forward, and does
            // it afterwards — see [NativeGatewayAuthenticator.signIn].
            try {
                // A second bound, above OkHttp's own. This one cannot unstick a
                // thread already blocked inside a socket or resolver call — only
                // `callTimeout` can — but it does bound everything else on this
                // leg and it is what turns a stall into a message.
                withTimeout(exchangeTimeoutMillis) { api.exchange(baseUrl, code, verifier) }
            } catch (timedOut: TimeoutCancellationException) {
                log.step(GatewaySignInStep.ExchangeTimedOut)
                throw GatewayAuthException(GatewaySignInCopy.EXCHANGE_TIMED_OUT)
            } catch (refused: GatewayAuthException) {
                log.step(GatewaySignInStep.ExchangeRefused)
                throw refused
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Anything else on this leg — a resolver failure, a socket
                // timeout, a malformed reply — used to reach the UI as "check
                // the host", which is not what a spent authorization code needs
                // to say.
                log.failed(GatewaySignInStep.ExchangeFailed, failure)
                throw GatewayAuthException(GatewaySignInCopy.EXCHANGE_FAILED)
            }
        } catch (cancelled: CancellationException) {
            // Someone else ended this. Whoever it was names itself at its own
            // call site now; this records that the sign-in is where it landed.
            log.failed(GatewaySignInStep.SignInAbandoned, cancelled)
            throw cancelled
        } finally {
            runCatching { binding?.close() }
            // Released on every exit — success, refusal, timeout, cancellation —
            // in the same place the listener it protects is closed.
            runCatching { held?.close() }
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
                } catch (_: IOException) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    // Narrowed to the one condition this claims to detect. A
                    // browser's speculative loopback connection torn down between
                    // SYN and accept also lands here, and aborting a sign-in that
                    // would have completed a second later is exactly the silent
                    // failure this whole change is about.
                    if (!listener.isClosed) continue
                    // The listener went away underneath the flow. Before this was
                    // caught the browser simply hit a refused connection and the
                    // app said nothing at all.
                    log.step(GatewaySignInStep.ListenerClosed)
                    throw GatewayAuthException(GatewaySignInCopy.LISTENER_CLOSED)
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
                    val parsed = target?.let { ("http://127.0.0.1$it").toHttpUrlOrNull() }
                    // RFC 8252 §8.9. Every process on the device can reach this
                    // port, and nothing but `state` says a request came from the
                    // authorization this app started. So `state` is checked
                    // before any other field is read and before any branch can
                    // end the flow: otherwise one unauthenticated GET carrying
                    // `error=` — or a request line long enough to be refused —
                    // cancels a stranger's sign-in without knowing anything at
                    // all. Anything that fails this gets the same neutral 404 as
                    // a browser's stray probe, tells the attacker nothing, and
                    // leaves the listener waiting. The five-minute timeout is
                    // the backstop, and it is the honest one: a wrong `state` is
                    // indistinguishable from an attacker, so it cannot be
                    // reported to the person as their own failure.
                    if (parsed == null || parsed.queryParameter("state") != expectedState) {
                        respond(socket, NOT_A_CALLBACK_RESPONSE)
                        if (parsed != null) log.step(GatewaySignInStep.StateMismatch)
                        continue
                    }
                    log.step(GatewaySignInStep.CallbackReceived)
                    // Past the gate, and every branch below still decides before
                    // it writes: a page that says "signed in" must never be the
                    // reply to a callback this app is about to reject.
                    if (parsed.queryParameter("error") != null) {
                        respond(socket, REJECTED_RESPONSE)
                        log.step(GatewaySignInStep.CallbackRefused)
                        throw GatewayAuthException(GatewaySignInCopy.REFUSED)
                    }
                    val code = parsed.queryParameter("code")
                    if (code == null) {
                        respond(socket, NOT_A_CALLBACK_RESPONSE)
                        continue
                    }
                    respond(socket, SIGNED_IN_RESPONSE)
                    log.step(GatewaySignInStep.CallbackAccepted)
                    return@runInterruptible code
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw GatewayAuthException("Hermes sign-in did not complete.")
        }

    /**
     * Best effort by design: the person's browser may already be gone, and a
     * page this app could not write is never a reason to drop a callback it has
     * already validated.
     *
     * [Socket.shutdownOutput] rather than a bare close. Only the request line is
     * read ([readRequestTarget]), so the headers after it are still sitting in
     * the receive buffer, and closing a socket with unread inbound data sends
     * RST instead of FIN — which discards the queued response. The refusal page
     * is the only place a person learns their callback was rejected, so it has
     * to actually arrive.
     */
    private fun respond(socket: Socket, page: ByteArray) {
        runCatching {
            val output = socket.getOutputStream()
            output.write(page)
            output.flush()
            socket.shutdownOutput()
        }
    }

    /**
     * The request target, or null when this was not a request line worth
     * parsing — oversized, not a `GET`, or not a path.
     *
     * Null rather than an exception. The read is bounded either way, but an
     * unauthenticated caller must not be able to end a sign-in by sending eight
     * kilobytes of anything: the caller answers null with the same neutral 404
     * every other unauthenticated request gets, and keeps waiting.
     */
    private fun readRequestTarget(input: java.io.InputStream): String? {
        val bytes = ByteArray(MAX_REQUEST_LINE_BYTES)
        var size = 0
        try {
            while (size < bytes.size) {
                val next = input.read()
                if (next < 0) break
                bytes[size++] = next.toByte()
                if (size >= 2 && bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()) break
            }
            if (size == bytes.size) return null
            val line = bytes.copyOf(size).toString(Charsets.US_ASCII).trim()
            val pieces = line.split(' ')
            if (pieces.size < 3 || pieces[0] != "GET") return null
            return pieces[1].takeIf { it.startsWith('/') }
        } finally {
            bytes.fill(0)
        }
    }

    private fun randomUrlToken(size: Int): String = ByteArray(size)
        .also(random::nextBytes)
        .let(::base64Url)

    internal companion object {
        const val LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        /**
         * Covers both attempts of [GatewayNativeAuthApi.exchange] plus slack, and
         * is deliberately well above the per-call bound so OkHttp's own timeout
         * fires first — a coroutine timeout produces no retry, because it cannot
         * tell whether the Gateway answered.
         */
        const val EXCHANGE_TIMEOUT_MILLIS = 60_000L
        const val MAX_REQUEST_LINE_BYTES = 8 * 1024

        val SIGNED_IN_RESPONSE = htmlResponse(
            status = "200 OK",
            title = "Signed in",
            body = "Signed in to Hermes. You can close this tab \u2014 the app is finishing sign-in.",
        )
        val REJECTED_RESPONSE = htmlResponse(
            status = "400 Bad Request",
            title = "Sign-in not accepted",
            body = "Hermes did not accept this sign-in. Close this tab and start the sign-in again from the app.",
        )
        val NOT_A_CALLBACK_RESPONSE = htmlResponse(
            status = "404 Not Found",
            title = "Nothing here",
            body = "This address is only used while you are signing in to Hermes.",
        )

        /**
         * The page a browser is left on. Product copy, and the only copy in this
         * app a person reads outside it — so it says what happened and what to
         * do, and it never echoes a query value back into a rendered page.
         */
        private fun htmlResponse(status: String, title: String, body: String): ByteArray {
            val page = "<!doctype html><meta charset=\"utf-8\"><title>$title</title><p>$body</p>"
                .toByteArray(Charsets.UTF_8)
            // Length-delimited, not close-delimited: a close-delimited body is
            // indistinguishable from a reset connection, and this page is the
            // only thing that explains a refusal.
            val head = (
                "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${page.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            return head + page
        }
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
        authenticator.tokens(profile)?.accessToken

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
