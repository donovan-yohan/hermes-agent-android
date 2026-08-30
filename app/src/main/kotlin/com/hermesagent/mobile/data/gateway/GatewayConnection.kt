package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.HostAnchor
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.data.ssh.SshOpenResult
import com.hermesagent.mobile.data.ssh.SshSessionOpener
import com.hermesagent.mobile.data.ssh.SshTransport
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

enum class GatewayConnectionStatus(val label: String) {
    Disconnected("Disconnected"),
    Connecting("Connecting"),
    Connected("Connected"),
    NeedsAttention("Needs attention"),
}

data class GatewayConnectionState(
    val status: GatewayConnectionStatus = GatewayConnectionStatus.Disconnected,
    val message: String? = null,
)

internal sealed interface GatewayConnectResult {
    data object Connected : GatewayConnectResult
    data class HostKeyPending(val fingerprint: String, val keyType: String, val anchor: HostAnchor) : GatewayConnectResult
    data class HostKeyMismatch(val expected: String, val actual: String) : GatewayConnectResult
    data class Failed(
        val kind: ProbeFailure?,
        val message: String,
        val retryable: Boolean = true,
    ) : GatewayConnectResult
}

/** The process-scoped Gateway controls consumed by the SSH configuration UI. */
internal interface GatewayConnectionController {
    val state: StateFlow<GatewayConnectionState>

    suspend fun connect(
        profile: HostProfile,
        credential: SshCredential,
    ): GatewayConnectResult

    suspend fun connectRemote(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher,
    ): GatewayConnectResult

    /**
     * Starts an interactive Remote sign-in that outlives the screen that asked
     * for it.
     *
     * The person leaves this app for a browser, and Android is free to destroy
     * the Activity behind them. A flow launched in `viewModelScope` dies there,
     * silently, with the loopback listener it owns — which is exactly what a
     * rotation or a memory-pressure kill during provider auth looks like from
     * the outside: nothing happens, ever. So the coroutine belongs to the
     * process-scoped connection layer, which is where the connection it is
     * opening belongs anyway.
     *
     * Starting a second one supersedes the first. [cancelRemoteSignIn] is the
     * only other thing that ends it: destroying a screen is not a decision to
     * abandon a sign-in.
     *
     * Abstract rather than defaulted, deliberately. This is the method the
     * Connect button routes through, so a `= Unit` default would let any
     * implementer — a fake, a future controller — turn that button into a
     * silent no-op with nothing to catch it.
     */
    fun startRemoteSignIn(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher)

    /** Abandons an interactive sign-in, if one is running. */
    fun cancelRemoteSignIn()

    /** Restores a saved Remote Gateway without opening an interactive browser. */
    suspend fun restoreRemote(profile: RemoteGatewayProfile): GatewayConnectResult =
        GatewayConnectResult.Failed(null, "Reconnect to this Gateway from settings.")

    /**
     * Opens the Local route: a Hermes the person runs on this device, reached
     * over loopback with a saved session token.
     *
     * There is no interactive step and no process to start, so this is the
     * whole of the route — but it is still explicit, because a token this app
     * holds is not permission to dial a server the person may have stopped.
     */
    suspend fun connectLocal(profile: LocalGatewayProfile): GatewayConnectResult =
        GatewayConnectResult.Failed(null, LocalGatewayCopy.UNAVAILABLE, retryable = false)

    /**
     * Brings the active Local row up on its own, with the token it already
     * owns and no scrape.
     *
     * Null means it declined because something is already on, or dialling, the
     * loopback route: nothing was dialled and nothing was published.
     */
    suspend fun restoreLocal(profile: LocalGatewayProfile): GatewayConnectResult? =
        GatewayConnectResult.Failed(null, LocalGatewayCopy.UNAVAILABLE, retryable = false)

    suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile)

    /** Erases one Local row's session token. Addressable by row id alone. */
    suspend fun forgetLocalAuthentication(profile: LocalGatewayProfile) = Unit

    /**
     * Stores one Local row's session token, bound to the address that row
     * names.
     *
     * Takes ownership of [token] and zeroes it — including on a build that
     * cannot store it at all. A caller that has handed over its only mutable
     * copy must not be left holding a live one because the route was
     * unavailable.
     */
    suspend fun saveLocalSessionToken(profile: LocalGatewayProfile, token: ByteArray) {
        token.fill(0)
    }

    /**
     * Rotate the live leg's credential once, without user interaction, for a
     * REST caller the Gateway just refused.
     *
     * False means this leg has nothing to rotate — the managed SSH leg's
     * loopback session token lives for the lifetime of the forward that
     * carries it — or the rotation was refused. The
     * caller's next honest move is the app's ordinary sign-in, never a second
     * rotation.
     */
    suspend fun refreshCredential(): Boolean = false

    /**
     * Whether a sign-in on this device could supply the live leg's credential.
     *
     * True only on the host-owned Remote Gateway leg, which has a sign-in.
     * Managed SSH does not: its credential is created by the connection and
     * dies with it, so copy that sends someone to sign in there points at a
     * door that is not in the building. Reconnecting is.
     */
    suspend fun signInAvailable(): Boolean = false

    suspend fun disconnect()
}

fun interface GatewayInstallStore {
    suspend fun ownershipId(): String
}

internal fun interface GatewayReadinessVerifier {
    suspend fun verify(localPort: Int, token: ByteArray, expectedOwnerNonce: String)
}

internal class GatewayHttpVerifier(private val http: OkHttpClient) : GatewayReadinessVerifier {
    override suspend fun verify(localPort: Int, token: ByteArray, expectedOwnerNonce: String) =
        withContext(Dispatchers.IO) {
            val tokenText = token.toString(Charsets.US_ASCII)
            request(localPort, "api/health", tokenText)
            val ownership = request(localPort, "api/ssh/ownership", tokenText)
            val body = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(ownership).jsonObject }
                .getOrNull()
                ?: throw GatewayConnectionException("Hermes returned an invalid ownership response.")
            val ok = body["ok"] as? JsonPrimitive
            val ownerNonce = body["sshOwnerNonce"] as? JsonPrimitive
            val protocolVersion = body["protocolVersion"] as? JsonPrimitive
            if (
                ok?.isString != false || ok.booleanOrNull != true ||
                ownerNonce?.isString != true || ownerNonce.contentOrNull != expectedOwnerNonce ||
                protocolVersion?.isString != false || protocolVersion.intOrNull != 1
            ) {
                throw GatewayConnectionException("Hermes did not confirm this app's remote process.")
            }
        }

    private fun request(port: Int, path: String, token: String): String {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/$path")
            .header("X-Hermes-Session-Token", token)
            .get()
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GatewayConnectionException("Hermes refused the authenticated connection check.")
            }
            val source = response.body?.source()
                ?: throw GatewayConnectionException("Hermes returned an empty connection check.")
            val boundedBytes = MAX_BODY_BYTES + 1L
            source.request(boundedBytes)
            val bytes = source.buffer.readByteArray(minOf(source.buffer.size, boundedBytes))
            try {
                if (bytes.size > MAX_BODY_BYTES) {
                    throw GatewayConnectionException("Hermes returned an oversized connection check.")
                }
                bytes.toString(Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 64 * 1024
    }
}

internal fun interface GatewayServedTokenResolver {
    /** Returns one owned mutable candidate, or null for the spawn-token fallback. */
    suspend fun resolve(localPort: Int): ByteArray?
}

/**
 * The same scrape, addressed by a whole loopback base URL rather than a
 * forwarded port, for the Local route — where the person may have named
 * `localhost` or `[::1]` and assuming `127.0.0.1` would read a different
 * server than the one they saved.
 */
internal fun interface GatewayServedTokenScraper {
    suspend fun scrape(normalizedBaseUrl: String): ByteArray?
}

/**
 * Reads only the public dashboard root through the established loopback
 * forward. Fetch or parse failure deliberately falls back to the spawn token,
 * matching pinned Desktop's adoption contract.
 *
 * Source: f82f2dbabd9e66b714f2b4f8a40447fe0c13e732,
 * apps/desktop/electron/dashboard-token.ts:10-101 and
 * hermes_cli/web_server.py:17242-17310.
 */
internal class GatewayDashboardTokenResolver(
    http: OkHttpClient,
    private val onCandidateReady: (ByteArray) -> Unit = {},
) : GatewayServedTokenResolver, GatewayServedTokenScraper {
    private val publicHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun resolve(localPort: Int): ByteArray? {
        require(localPort in 1..65535) { "The local Gateway port is invalid." }
        return fetch(requireNotNull("http://127.0.0.1:$localPort/".toHttpUrlOrNull()))
    }

    override suspend fun scrape(normalizedBaseUrl: String): ByteArray? =
        fetch(normalizeLocalGatewayUrl(normalizedBaseUrl)?.toHttpUrlOrNull() ?: return null)

    private suspend fun fetch(url: HttpUrl): ByteArray? {
        val pending = AtomicReference<ByteArray?>(null)
        return try {
            runInterruptible(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                val call = publicHttp.newCall(request)
                call.timeout().timeout(FETCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                call.execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body ?: return@use
                    val declaredLength = body.contentLength()
                    if (declaredLength > MAX_DASHBOARD_BYTES) return@use
                    val source = body.source()
                    val boundedBytes = MAX_DASHBOARD_BYTES + 1L
                    source.request(boundedBytes)
                    val bytes = source.buffer.readByteArray(minOf(source.buffer.size, boundedBytes))
                    if (bytes.size > MAX_DASHBOARD_BYTES) {
                        bytes.fill(0)
                        return@use
                    }
                    consumeInjectedDashboardToken(bytes)?.let { candidate ->
                        pending.set(candidate)
                        onCandidateReady(candidate)
                    }
                }
            }
            // No suspension follows this transfer, so prompt cancellation can
            // no longer discard an owned token between production and delivery.
            pending.getAndSet(null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            pending.getAndSet(null)?.fill(0)
        }
    }

    private companion object {
        const val FETCH_TIMEOUT_MILLIS = 3_000L
        const val MAX_DASHBOARD_BYTES = 256 * 1024
    }
}

/**
 * Consumes and wipes one dashboard body. The only accepted form is the exact
 * bootstrap emitted by pinned Hermes: `<script>` + marker + JSON string + `;`.
 */
internal fun consumeInjectedDashboardToken(html: ByteArray): ByteArray? {
    var decoded: CharBuffer? = null
    try {
        decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(html))

        val marker = DASHBOARD_TOKEN_PREFIX
        val start = html.indexOf(marker)
        if (start < 0 || html.indexOf(marker, start + marker.size) >= 0) return null
        return decodeBoundedJsonAsciiString(html, start + marker.size)
    } catch (_: CharacterCodingException) {
        return null
    } finally {
        decoded?.run {
            clear()
            while (hasRemaining()) put('\u0000')
        }
        html.fill(0)
    }
}

private fun decodeBoundedJsonAsciiString(source: ByteArray, offset: Int): ByteArray? {
    if (source.getOrNull(offset) != JSON_QUOTE.toByte()) return null
    val decoded = ByteArray(MAX_SERVED_TOKEN_BYTES)
    var size = 0
    var index = offset + 1
    try {
        while (index < source.size) {
            val byte = source[index++].toInt() and 0xff
            val value = when (byte) {
                JSON_QUOTE -> {
                    if (source.getOrNull(index) != JSON_SEMICOLON.toByte() || size == 0) return null
                    return decoded.copyOf(size)
                }

                JSON_BACKSLASH -> {
                    val escaped = source.getOrNull(index++)?.toInt()?.and(0xff) ?: return null
                    when (escaped) {
                        JSON_QUOTE, JSON_BACKSLASH, JSON_SLASH -> escaped
                        JSON_UNICODE_ESCAPE -> decodeJsonAsciiEscape(source, index).also { index += 4 }
                        else -> return null
                    }
                }

                in 0x21..0x7e -> byte
                else -> return null
            }
            if (value !in 0x21..0x7e || size == decoded.size) return null
            decoded[size++] = value.toByte()
        }
        return null
    } finally {
        decoded.fill(0)
    }
}

private fun decodeJsonAsciiEscape(source: ByteArray, offset: Int): Int {
    if (offset + 4 > source.size) return -1
    var value = 0
    repeat(4) { index ->
        val digit = when (val char = source[offset + index].toInt() and 0xff) {
            in 0x30..0x39 -> char - 0x30
            in 0x61..0x66 -> char - 0x61 + 10
            in 0x41..0x46 -> char - 0x41 + 10
            else -> return -1
        }
        value = value * 16 + digit
    }
    return value
}

private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int = 0): Int {
    if (needle.isEmpty()) return startIndex.coerceAtMost(size)
    for (index in startIndex.coerceAtLeast(0)..size - needle.size) {
        if (needle.indices.all { offset -> this[index + offset] == needle[offset] }) return index
    }
    return -1
}

private val DASHBOARD_TOKEN_PREFIX =
    "<script>window.__HERMES_SESSION_TOKEN__=".toByteArray(Charsets.US_ASCII)
private const val MAX_SERVED_TOKEN_BYTES = 512
private const val JSON_QUOTE = 0x22
private const val JSON_BACKSLASH = 0x5c
private const val JSON_SLASH = 0x2f
private const val JSON_SEMICOLON = 0x3b
private const val JSON_UNICODE_ESCAPE = 0x75

internal class GatewayConnectionException(message: String) : Exception(message)

/** Process-scoped owner of SSH, remote process, forward, HTTP and WebSocket. */
internal class GatewayConnectionManager(
    private val scope: CoroutineScope,
    private val installStore: GatewayInstallStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val sshOpen: suspend (HostProfile, SshCredential) -> SshOpenResult =
        { profile, credential -> SshSessionOpener().open(profile, credential) },
    private val lifecycleFactory: (RemoteCommandRunner) -> RemoteHermesLifecycle = ::RemoteHermesLifecycle,
    private val httpVerifier: GatewayReadinessVerifier = GatewayHttpVerifier(http),
    private val servedTokenResolver: GatewayServedTokenResolver = GatewayDashboardTokenResolver(http),
    private val rpcOpen: suspend (Int, ByteArray) -> GatewayRpcClient = { port, token ->
        OkHttpGatewayRpcClient.connect(http, port, token)
    },
    private val remoteConnector: RemoteGatewayConnector? = null,
    private val localConnector: LocalGatewayConnector? = null,
    private val reconnectWait: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val reconnectJitter: () -> Double = { Random.nextDouble() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Test seam for the cancellation/foreground interleave; production is immediate. */
    private val beforeReconnectCancellationCleanup: suspend () -> Unit = {},
    /**
     * Names the *type* of a failure that came from this app rather than the
     * network. A no-op by default: `android.util.Log` is deliberately not
     * mocked in this project's JVM unit tests (`app/build.gradle.kts:71-77`),
     * so the process wires the real one.
     */
    private val logAppFailure: (String) -> Unit = {},
) : Closeable, GatewayConnectionController {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(GatewayConnectionState())
    private val _client = MutableStateFlow<GatewayRpcClient?>(null)
    private val _gatewayHttp = MutableStateFlow<GatewayHttp?>(null)
    private val _imageLoader = MutableStateFlow<GatewayImageLoader?>(null)
    private var active: ActiveConnection? = null

    /**
     * The live remote profile, mirrored out of [active] at every write.
     *
     * [refreshCredential] and [signInAvailable] must answer without waiting on
     * [mutex]: even now that `openRemote` releases the lock across the browser
     * round trip, a rotation that parks behind an in-flight connect is a hang
     * rather than a rotation. Written only where [active] is.
     */
    private val liveRemoteProfile = AtomicReference<RemoteGatewayProfile?>(null)

    /**
     * Whether the loopback route is live *or* being opened, readable without
     * [mutex].
     *
     * The network handlers cancel the in-flight connect intent before they take
     * the lock, so a guard that only inspected [active] would still abort a
     * Local connect that was going to succeed — [active] is null for the whole
     * of a connect. The counter covers the open; the flag covers the connection
     * it leaves behind. Written where [active] is, plus around [connectLocal].
     */
    private val localConnectsInFlight = AtomicInteger(0)
    private val localRouteActive = AtomicBoolean(false)

    /** Every authenticated loopback hop, redirect following off. */
    private val loopbackHttp by lazy { loopbackClient(http) }
    private val connectIntent = AtomicReference(ConnectIntent(generation = 0, job = null))
    private var rpcMonitor: Job? = null
    private var reconnectJob: Job? = null

    /**
     * The running interactive sign-in, held by the process rather than by the
     * screen that started it — see [GatewayConnectionController.startRemoteSignIn].
     * Atomic rather than mutex-guarded because it is claimed and abandoned from
     * the main thread, and taking [mutex] to do that would put a UI tap behind
     * whatever open is in flight.
     */
    private val signInJob = AtomicReference<Job?>(null)
    private var desiredRemoteProfile: RemoteGatewayProfile? = null
    private var remoteConnectedAtMillis: Long? = null
    private var remoteReconnectAttempts = 0
    /** Wall-clock start of the current failure episode; null while healthy. */
    private var remoteFailingSinceMillis: Long? = null
    /** Once raised, the actionable surface stays stable until this episode ends. */
    private var remoteReconnectEscalated = false
    private var networkAvailable = true
    private val networkAvailableSignal = AtomicBoolean(true)
    /** Automatic redials are foreground-only; explicit opens remain user-owned. */
    private val applicationForegroundSignal = AtomicBoolean(true)
    private var reconnectGeneration = 0L
    private val networkEventGeneration = AtomicLong()

    override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()
    val client: StateFlow<GatewayRpcClient?> = _client.asStateFlow()

    /** Monotonic connection identity for fencing voice/audio operations. */
    internal val currentGeneration: Long get() = connectIntent.get().generation

    /**
     * Connection-owned authenticated HTTP transport for Gateway REST routes.
     * Null while disconnected; resolved per active leg so callers never hold
     * credentials, origins, or tickets.
     */
    val gatewayHttp: StateFlow<GatewayHttp?> = _gatewayHttp.asStateFlow()

    /**
     * Connection-owned authenticated loader for attached-image bytes. Null
     * while disconnected; same per-leg credential resolvers as [gatewayHttp].
     */
    val imageLoader: StateFlow<GatewayImageLoader?> = _imageLoader.asStateFlow()

    override suspend fun connect(
        profile: HostProfile,
        credential: SshCredential,
    ): GatewayConnectResult {
        val config = RemoteHermesConfig(profile = profile.remoteHermesProfile.ifBlank { null })
        val intent = beginConnectIntent(currentCoroutineContext()[Job])
        return try {
            try {
                mutex.withLock {
                    if (!isCurrentConnectIntent(intent)) throw CancellationException()
                    clearRemoteRouteLocked()
                    closeActive()
                    _state.value = GatewayConnectionState(GatewayConnectionStatus.Connecting)
                    when (val opened = sshOpen(profile, credential)) {
                        is SshOpenResult.HostKeyPending -> {
                            _state.value = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, "Review this host key to continue.")
                            GatewayConnectResult.HostKeyPending(opened.fingerprint, opened.keyType, profile.anchor)
                        }

                        is SshOpenResult.HostKeyMismatch -> {
                            _state.value = GatewayConnectionState(
                                GatewayConnectionStatus.NeedsAttention,
                                "The host key changed. Verify the host before reconnecting.",
                            )
                            GatewayConnectResult.HostKeyMismatch(opened.expected, opened.actual)
                        }

                        is SshOpenResult.Failed -> fail(opened.kind, opened.message)
                        is SshOpenResult.Connected -> finishConnect(opened.transport, config)
                    }
                }
            } catch (cancelled: CancellationException) {
                // A form edit can cancel before finishConnect owns a transport.
                // Reset the published state without overwriting a newer connect.
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (releaseConnectIntent(intent)) {
                            closeActive()
                            _state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
                        }
                    }
                }
                throw cancelled
            }
        } finally {
            releaseConnectIntent(intent)
        }
    }

    override suspend fun connectLocal(profile: LocalGatewayProfile): GatewayConnectResult {
        // Claimed before the intent exists, so a network edge arriving during
        // this dial cannot cancel the job that is doing it.
        localConnectsInFlight.incrementAndGet()
        val intent = beginConnectIntent(currentCoroutineContext()[Job])
        return try {
            try {
                mutex.withLock {
                    if (!isCurrentConnectIntent(intent)) throw CancellationException()
                    // The Local route carries no automatic redial: it is one
                    // explicit dial at a server the person started themselves.
                    // Clearing the remote route is what stops a previously
                    // desired Remote Gateway from reconnecting over it.
                    clearRemoteRouteLocked()
                    closeActive()
                    _state.value = GatewayConnectionState(GatewayConnectionStatus.Connecting)
                    val connector = localConnector
                        ?: return@withLock fail(null, LocalGatewayCopy.UNAVAILABLE, retryable = false)
                    val baseUrl = profile.normalizedBaseUrl
                        ?: return@withLock fail(null, LocalGatewayCopy.INVALID_URL, retryable = false)
                    finishLocalConnect(connector, profile, baseUrl)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (releaseConnectIntent(intent)) {
                            closeActive()
                            _state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
                        }
                    }
                }
                throw cancelled
            }
        } finally {
            releaseConnectIntent(intent)
            localConnectsInFlight.decrementAndGet()
        }
    }

    /**
     * Restores the Local route without anybody asking, at launch and after a
     * connection switch lands on a Local row.
     *
     * The difference from [connectLocal] is what it refuses to do, not what it
     * does: the dial itself *is* [connectLocal], so a restored connection has
     * been through exactly the same readiness gate as one somebody tapped for.
     * What is added is a decision taken before any socket exists — this row
     * must already hold a token this app may use. A restore never scrapes: a
     * scrape asks whatever answers on loopback for a credential, which is a
     * reasonable convenience for a person standing at the form and a bad thing
     * to do unattended at every launch.
     *
     * There is no automatic redial behind it either. A Hermes that is not
     * answering has been stopped in Termux, and the only thing that starts it
     * again is the person, so this lands on the same retryable failure an
     * explicit dial does and stops.
     */
    override suspend fun restoreLocal(profile: LocalGatewayProfile): GatewayConnectResult? {
        // Every branch here that does not dial says one sentence and stops, so
        // the sentence is the only thing that varies between them.
        //
        // Whether it gets to say it is fenced the way every other publishing
        // path in this class is, and against *any* connection rather than a
        // loopback one. The loopback flags are false while a Remote or SSH leg
        // is up, so on their own they would let a sentence about a Local row
        // this app is not even on replace a live connection's state. The pair
        // that actually answers "is anything live or opening" is the one
        // `restoreRemote` gates its arming on: no active leg, and no connect
        // holding the intent.
        suspend fun refuse(message: String): GatewayConnectResult? = mutex.withLock {
            val somethingElseOwnsTheState = loopbackRouteBusy() ||
                active != null ||
                connectIntent.get().job != null
            if (somethingElseOwnsTheState) null else fail(null, message, retryable = false)
        }

        val connector = localConnector ?: return refuse(LocalGatewayCopy.UNAVAILABLE)
        if (!profile.isValid) return refuse(LocalGatewayCopy.INVALID_URL)
        // A restore is the passive caller on this route. Anything already on it
        // or dialling it is either this app's own explicit Connect or a restore
        // that got there first, and both are dialling the same server.
        if (loopbackRouteBusy()) return null
        val stored = connector.storedToken(profile)
        // Asked again, because the slot read is a suspending hop and a tap on
        // Connect during it would already own the route by now.
        if (loopbackRouteBusy()) return null
        return when (stored) {
            StoredSessionToken.Present -> connectLocal(profile)

            // Nothing to restore, so nothing is dialled. Said rather than
            // passed over in silence: the pane would otherwise offer a Connect
            // that fails for a reason it never named.
            StoredSessionToken.Absent -> refuse(LocalGatewayCopy.TOKEN_MISSING)

            // Something is stored that this row may not use — a token minted
            // for an address the row has since been moved off. It lands where
            // a token the Gateway itself refuses lands, for the same reason:
            // one refusal carrying its next action, never a retry.
            StoredSessionToken.Refused -> refuse(LocalGatewayCopy.TOKEN_REFUSED)
        }
    }

    /** True while this app is on, or dialling, a Gateway on this device. */
    private fun loopbackRouteBusy(): Boolean =
        localConnectsInFlight.get() > 0 || localRouteActive.get()

    /**
     * Called only while [mutex] is held. The readiness boundary is the same as
     * the Managed SSH leg's — authenticated health, then socket, then one
     * authenticated JSON-RPC round trip — minus the ownership proof, because
     * this app owns no process here.
     */
    private suspend fun finishLocalConnect(
        connector: LocalGatewayConnector,
        profile: LocalGatewayProfile,
        baseUrl: String,
    ): GatewayConnectResult {
        var leg: LocalGatewayLeg? = null
        try {
            leg = connector.open(profile)
            val rpc = leg.rpc
            // A successful upgrade alone is not readiness. This authenticated
            // round trip proves the leg the app will actually use.
            rpc.request("session.list", buildJsonObject { put("limit", JsonPrimitive(1)) })

            active = ActiveConnection.Local(rpc, profile)
            liveRemoteProfile.set(null)
            localRouteActive.set(true)
            val authorized = leg
            // The loopback client, not the shared one: every hop that carries
            // the session token refuses to follow a redirect off the device.
            _gatewayHttp.value = OkHttpGatewayHttp(
                http = loopbackHttp,
                resolveEndpoint = { baseUrl },
                resolveAuthorization = { authorized.authorization() },
            )
            _imageLoader.value = OkHttpGatewayImageLoader(
                http = loopbackHttp,
                resolveEndpoint = { baseUrl },
                resolveAuthorization = { authorized.authorization() },
            )
            _client.value = rpc
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
            watchRpc(rpc)
            return GatewayConnectResult.Connected
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { leg?.rpc?.close() } }
            if (failure is CancellationException) throw failure
            return fail(
                null,
                safeConnectionMessage(failure),
                retryable = failure.isRetryableRemoteConnectionFailure(),
            )
        }
    }

    override suspend fun connectRemote(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher,
    ): GatewayConnectResult {
        val intent = beginConnectIntent(currentCoroutineContext()[Job])
        // Whether the person was actually sent to a browser is the difference
        // between "this attempt stopped" and "your sign-in was abandoned
        // half-way through", and only this seam can see it.
        val handedOff = AtomicBoolean(false)
        // Delegation, not hand-forwarding: a member added to
        // [GatewayBrowserLauncher] later must keep reaching the real launcher
        // rather than silently falling back to the interface default here.
        val observed = object : GatewayBrowserLauncher by browser {
            override suspend fun open(url: String) {
                handedOff.set(true)
                browser.open(url)
            }
        }
        return openRemote(
            profile,
            observed,
            intent,
            requireForeground = false,
            abortMessage = { GatewaySignInCopy.CANCELLED.takeIf { handedOff.get() } },
        ) {
            claimRemoteRouteLocked(profile)
        }
    }

    override fun startRemoteSignIn(profile: RemoteGatewayProfile, browser: GatewayBrowserLauncher) {
        // UNDISPATCHED so the connect intent is claimed on the caller's thread,
        // before this returns: the screen that asked is entitled to know the
        // attempt it superseded is already superseded.
        val started = scope.launch(start = CoroutineStart.UNDISPATCHED) { connectRemote(profile, browser) }
        // Claim first, then arm: `invokeOnCompletion` fires inline for a job that
        // already finished, and if it ran before the swap its compare-and-set
        // would miss and strand a dead job in the reference.
        signInJob.getAndSet(started)?.cancel()
        started.invokeOnCompletion { signInJob.compareAndSet(started, null) }
    }

    override fun cancelRemoteSignIn() {
        signInJob.getAndSet(null)?.cancel()
    }

    override suspend fun restoreRemote(profile: RemoteGatewayProfile): GatewayConnectResult {
        val intent = beginConnectIntent(currentCoroutineContext()[Job])
        val result = openRemote(profile, browser = null, intent, requireForeground = false) {
            claimRemoteRouteLocked(profile)
        }
        if (remoteConnector != null && result is GatewayConnectResult.Failed && result.retryable) {
            mutex.withLock {
                // A cold-start restore has no established socket whose monitor
                // could arm retries. Join the same reconnect loop explicitly,
                // after openRemote releases its connect intent.
                // `active == null` and a released intent are both required: an
                // open that succeeded between the two lock passes leaves no job
                // but does leave a live connection.
                if (remoteRouteLiveLocked(profile) && active == null && connectIntent.get().job == null) {
                    armRemoteReconnectLocked(profile, failingSinceMillis = nowMillis())
                }
            }
        }
        return result
    }

    /**
     * Opens the Remote route.
     *
     * [mutex] deliberately covers only the two phases that touch shared state:
     * the admission/teardown/`Connecting` phase, and the publish phase that
     * installs the live connection. Everything in between — an interactive
     * browser sign-in, the token exchange, the socket, and the authenticated
     * readiness round trip — runs with the lock released. It used to be held
     * across all of it, which meant one person standing in a browser for up to
     * five minutes parked every other caller of this class behind them, and a
     * cancelled sign-in could not even publish its own outcome until they
     * finished. Nothing is lost by releasing it: [requireRemoteOpenCurrentLocked]
     * is re-checked under the lock before anything is published, so an open
     * that was superseded while it was outside is discarded there.
     *
     * [abortMessage] answers what to say if this attempt is cancelled — null
     * for the non-interactive paths, where a cancellation is bookkeeping rather
     * than something that happened to a person.
     */
    private suspend fun openRemote(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher?,
        intent: ConnectIntent,
        requireForeground: Boolean,
        abortMessage: () -> String? = { null },
        prepareAdmissionLocked: () -> RemoteOpenAdmission,
    ): GatewayConnectResult {
        var admission: RemoteOpenAdmission? = null
        return try {
            try {
                admission = mutex.withLock {
                    if (!isCurrentConnectIntent(intent)) throw CancellationException()
                    prepareAdmissionLocked()
                }
                var unavailable: GatewayConnectResult.Failed? = null
                val connector = mutex.withLock {
                    requireRemoteOpenCurrentLocked(intent, profile, checkNotNull(admission), requireForeground)
                    val candidate = remoteConnector
                    if (candidate == null) {
                        unavailable = fail(null, "Remote Gateway connections are unavailable in this build.")
                    } else {
                        closeActive()
                        publishUnlessEscalatedLocked(GatewayConnectionState(GatewayConnectionStatus.Connecting))
                    }
                    candidate
                }
                if (connector == null) {
                    checkNotNull(unavailable)
                } else {
                    val rpc = connector.open(profile, browser)
                    try {
                        // The authenticated RPC round trip, not merely a WS
                        // upgrade, is the readiness boundary.
                        rpc.request("session.list", buildJsonObject { put("limit", JsonPrimitive(1)) })
                        mutex.withLock {
                            // `cancelRemoteSignIn` is the one competing path that
                            // does not bump the connect-intent generation — it
                            // cancels this coroutine and nothing else — and
                            // `Mutex.lock` takes an uncontended fast path with no
                            // cancellation check, so without this an abandoned
                            // sign-in could still publish a live connection.
                            currentCoroutineContext().ensureActive()
                            requireRemoteOpenCurrentLocked(intent, profile, checkNotNull(admission), requireForeground)
                            active = ActiveConnection.Remote(rpc, profile)
                            liveRemoteProfile.set(profile)
                            localRouteActive.set(false)
                            _gatewayHttp.value = OkHttpGatewayHttp(
                                http = http,
                                resolveEndpoint = { profile.normalizedBaseUrl },
                                resolveAuthorization = {
                                    runCatching { connector.accessToken(profile) }.getOrNull()
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { "Authorization" to "Bearer $it" }
                                },
                            )
                            _imageLoader.value = OkHttpGatewayImageLoader(
                                http = http,
                                resolveEndpoint = { profile.normalizedBaseUrl },
                                resolveAuthorization = {
                                    runCatching { connector.accessToken(profile) }.getOrNull()
                                        ?.takeIf(String::isNotBlank)
                                        ?.let { "Authorization" to "Bearer $it" }
                                },
                            )
                            remoteConnectedAtMillis = nowMillis()
                            beginRemoteFailureEpisodeLocked(null)
                            _client.value = rpc
                            _state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
                            watchRpc(rpc)
                            GatewayConnectResult.Connected
                        }
                    } catch (failure: Throwable) {
                        runCatching { rpc.close() }
                        throw failure
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (requireForeground) beforeReconnectCancellationCleanup()
                    mutex.withLock {
                        if (releaseConnectIntent(intent)) {
                            closeActive()
                            when {
                                !networkAvailableSignal.get() -> _state.value = GatewayConnectionState(
                                    GatewayConnectionStatus.NeedsAttention,
                                    NETWORK_WAIT_MESSAGE,
                                )

                                requireForeground && remoteRouteLiveLocked(profile) -> {
                                    if (applicationForegroundSignal.get()) {
                                        // Foreground can return after admission
                                        // rejects a background retry but before
                                        // this intent is released. Re-arm here
                                        // because the queued nudge sees this
                                        // dying intent and deliberately yields.
                                        armRemoteReconnectLocked(profile, failingSinceMillis = null)
                                    } else {
                                        publishUnlessEscalatedLocked(
                                            GatewayConnectionState(GatewayConnectionStatus.Disconnected),
                                        )
                                    }
                                }

                                // A sign-in the person was actually sent to a
                                // browser for says so; a bare `Disconnected`
                                // here is indistinguishable from the app having
                                // forgotten they asked.
                                else -> _state.value = abortMessage()
                                    ?.let { GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, it) }
                                    ?: GatewayConnectionState(GatewayConnectionStatus.Disconnected)
                            }
                        }
                    }
                }
                throw cancelled
            } catch (failure: Throwable) {
                val failedAdmission = admission ?: throw failure
                mutex.withLock {
                    requireRemoteOpenCurrentLocked(intent, profile, failedAdmission, requireForeground)
                    closeActive()
                    val retryable = failure.isRetryableRemoteConnectionFailure()
                    if (!retryable && desiredRemoteProfile == profile) {
                        // Keep the persisted profile for the explicit sign-in
                        // action, but stop foreground/network nudges from
                        // replaying a terminal non-interactive failure.
                        desiredRemoteProfile = null
                        remoteReconnectAttempts = 0
                        beginRemoteFailureEpisodeLocked(null)
                    }
                    val message = safeConnectionMessage(failure)
                    publishUnlessEscalatedLocked(
                        GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, message),
                    )
                    GatewayConnectResult.Failed(null, message, retryable)
                }
            }
        } finally {
            releaseConnectIntent(intent)
        }
    }

    override suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile) {
        remoteConnector?.signOut(profile)
    }

    override suspend fun forgetLocalAuthentication(profile: LocalGatewayProfile) {
        localConnector?.forget(profile)
    }

    override suspend fun saveLocalSessionToken(profile: LocalGatewayProfile, token: ByteArray) {
        val connector = localConnector
        if (connector == null) {
            token.fill(0)
            return
        }
        connector.remember(profile, token)
    }

    override suspend fun refreshCredential(): Boolean {
        // Only the remote leg carries a rotatable bearer. Read it from the
        // mirror rather than under [mutex]: `openRemote` can hold that lock for
        // the whole of an interactive browser sign-in, and a non-interactive
        // rotation parked behind it would never answer at all. Aiming at a
        // profile that has just been replaced is harmless — the authenticator
        // rotates the stored pair under its own lock and hands back what is
        // already current if someone else rotated first.
        val profile = liveRemoteProfile.get() ?: return false
        val connector = remoteConnector ?: return false
        // A rotation that throws is a rotation that did not happen; the caller
        // falls through to sign-in rather than treating it as an outage.
        return runCatching { connector.refreshAccessToken(profile) }.getOrDefault(false)
    }

    override suspend fun signInAvailable(): Boolean = liveRemoteProfile.get() != null

    private suspend fun finishConnect(
        transport: SshTransport,
        config: RemoteHermesConfig,
    ): GatewayConnectResult {
        var backend: RemoteBackend? = null
        var forward: com.hermesagent.mobile.data.ssh.SshForward? = null
        var rpc: GatewayRpcClient? = null
        try {
            val runner = SshRemoteCommandRunner(transport)
            val ownershipId = installStore.ownershipId()
            backend = lifecycleFactory(runner).start(ownershipId, config)
            forward = runner.openLoopbackForward(backend.remotePort)
            if (forward.bindAddress != "127.0.0.1") {
                throw GatewayConnectionException("The local Gateway forward was not loopback-only.")
            }
            httpVerifier.verify(
                localPort = forward.localPort,
                token = backend.token,
                expectedOwnerNonce = backend.process.nonce,
            )
            val servedToken = servedTokenResolver.resolve(forward.localPort)
            backend.adoptServedToken(servedToken)
            rpc = rpcOpen(forward.localPort, backend.token)
            // A successful upgrade alone is not readiness. This authenticated
            // JSON-RPC round trip proves the leg the app will actually use.
            rpc.request("session.list", buildJsonObject { put("limit", JsonPrimitive(1)) })

            active = ActiveConnection.Ssh(transport, backend, forward, rpc)
            liveRemoteProfile.set(null)
            localRouteActive.set(false)
            // Capture the loopback session token eagerly — the same pattern
            // the RPC client uses — because the backend clears its buffer once
            // connect finishes. The header contract matches the readiness
            // verifier exactly (ASCII token).
            val gatewayToken = backend.token.toString(Charsets.US_ASCII)
            _gatewayHttp.value = OkHttpGatewayHttp(
                http = http,
                resolveEndpoint = { "http://127.0.0.1:${'$'}{forward.localPort}" },
                resolveAuthorization = {
                    if (gatewayToken.isBlank()) null else "X-Hermes-Session-Token" to gatewayToken
                },
            )
            _imageLoader.value = OkHttpGatewayImageLoader(
                http = http,
                resolveEndpoint = { "http://127.0.0.1:${'$'}{forward.localPort}" },
                resolveAuthorization = {
                    if (gatewayToken.isBlank()) null else "X-Hermes-Session-Token" to gatewayToken
                },
            )
            _client.value = rpc
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Connected)
            watchRpc(rpc)
            backend.clearToken()
            return GatewayConnectResult.Connected
        } catch (failure: Throwable) {
            backend?.clearToken()
            // Cancellation must not cancel its own cleanup. The positively
            // owned process, forward and socket are still bounded operations.
            withContext(NonCancellable) {
                runCatching { rpc?.close() }
                runCatching { forward?.close() }
                backend?.let { runCatching { it.shutdown() } }
                runCatching { transport.close() }
            }
            if (failure is CancellationException) throw failure
            return fail(null, safeConnectionMessage(failure))
        }
    }

    override suspend fun disconnect() {
        invalidateConnectIntent()
        mutex.withLock {
            clearRemoteRouteLocked()
            closeActive()
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        }
    }

    fun networkAvailabilityChanged(available: Boolean) {
        networkAvailableSignal.set(available)
        val eventGeneration = networkEventGeneration.incrementAndGet()
        // A Gateway on this device is reached over loopback, which is up whether
        // or not the phone has a network at all. Tearing that leg down on an
        // airplane-mode edge would disconnect the one route that still works,
        // and Termux is exactly where someone is when they have no network. The
        // cancellation is skipped as well as the teardown: it is what would
        // abort a dial already in flight.
        val loopback = loopbackRouteBusy()
        if (!loopback) invalidateConnectIntent()
        scope.launch {
            mutex.withLock {
                if (networkEventGeneration.get() != eventGeneration) return@withLock
                val wasAvailable = networkAvailable
                // The flag is still recorded, so a later switch to a route that
                // does use the network starts from the truth.
                networkAvailable = available
                if (loopback || active is ActiveConnection.Local) return@withLock
                val profile = desiredRemoteProfile
                if (!available) {
                    cancelReconnectLocked()
                    if (active == null && _state.value.status != GatewayConnectionStatus.Connecting && profile == null) {
                        return@withLock
                    }
                    _state.value = GatewayConnectionState(
                        GatewayConnectionStatus.NeedsAttention,
                        NETWORK_WAIT_MESSAGE,
                    )
                    closeActive()
                    return@withLock
                }
                if (profile == null) {
                    val sshWasActive = active is ActiveConnection.Ssh
                    if (sshWasActive || (!wasAvailable && _state.value.message == NETWORK_WAIT_MESSAGE)) {
                        cancelReconnectLocked()
                        _state.value = GatewayConnectionState(
                            GatewayConnectionStatus.NeedsAttention,
                            "The network changed. Reconnect to the Gateway.",
                        )
                        closeActive()
                    }
                    return@withLock
                }
                closeActive()
                // The outage ended; any failure episode from before it is a
                // different episode. Without the null clock, one post-tunnel
                // failure inherits the old one and escalates instantly.
                armRemoteReconnectLocked(profile, failingSinceMillis = null)
            }
        }
    }

    /** Compatibility seam for callers that cannot distinguish loss from recovery. */
    fun networkChanged() {
        // Same reason as the availability edge: loopback does not travel over
        // the network that just changed, and cancelling here would abort a dial
        // in flight.
        if (loopbackRouteBusy()) return
        val invalidatedIntent = invalidateConnectIntent()
        scope.launch {
            mutex.withLock {
                if (connectIntent.get().generation != invalidatedIntent.generation) return@withLock
                if (active is ActiveConnection.Local) return@withLock
                if (active == null && _state.value.status != GatewayConnectionStatus.Connecting) return@withLock
                cancelReconnectLocked()
                _state.value = GatewayConnectionState(
                    GatewayConnectionStatus.NeedsAttention,
                    "The network changed. Reconnect to the Gateway.",
                )
                closeActive()
            }
        }
    }

    private suspend fun closeActive() {
        rpcMonitor?.takeIf { it !== currentCoroutineContext()[Job] }?.cancel()
        rpcMonitor = null
        val closing = active
        active = null
        liveRemoteProfile.set(null)
        localRouteActive.set(false)
        _client.value = null
        _gatewayHttp.value = null
        _imageLoader.value = null
        if (closing != null) {
            runCatching { closing.rpc.close() }
            when (closing) {
                is ActiveConnection.Remote -> Unit
                // Closing the socket is the whole teardown. The runtime on this
                // device belongs to whoever started it in Termux; this app has
                // no claim on it and never stops it.
                is ActiveConnection.Local -> Unit
                is ActiveConnection.Ssh -> {
                    runCatching { closing.forward.close() }
                    runCatching { closing.backend.shutdown() }
                    runCatching { closing.transport.close() }
                }
            }
        }
    }

    private fun watchRpc(rpc: GatewayRpcClient) {
        rpcMonitor?.cancel()
        rpcMonitor = scope.launch {
            val cause = rpc.closed.first()
            mutex.withLock {
                if (active?.rpc !== rpc) return@withLock
                val reconnect = (active as? ActiveConnection.Remote)?.profile
                    ?.takeIf { desiredRemoteProfile == it }
                // A Local socket that closed is a Hermes that stopped, never a
                // network that went away. Someone in airplane mode with the one
                // Gateway they have beside them in Termux must not be told to
                // wait for a network: there is no waiting that would help.
                //
                // Named for the route, not the address, because the Managed SSH
                // leg's socket is loopback too — it is the far end of a forward,
                // and a network that goes away does take it with it.
                val wasLocalRoute = active is ActiveConnection.Local
                val stableConnection = remoteConnectedAtMillis
                    ?.let { connectedAt -> nowMillis() - connectedAt >= STABLE_REMOTE_CONNECTION_MILLIS }
                    ?: false
                if (stableConnection) {
                    remoteReconnectAttempts = 0
                    beginRemoteFailureEpisodeLocked(null)
                }
                if (!networkAvailable && !wasLocalRoute) {
                    cancelReconnectLocked()
                    _state.value = GatewayConnectionState(
                        GatewayConnectionStatus.NeedsAttention,
                        NETWORK_WAIT_MESSAGE,
                    )
                } else if (reconnect == null) {
                    // No desired route to retry against. A Local route whose
                    // transport failed is a Hermes that stopped — the socket
                    // went with the process — so it names the device that
                    // stopped answering and what starts it, which is also what
                    // the next Connect will say.
                    //
                    // Only the transport failure, which is why the cause is
                    // carried here at all. The Gateway closing the socket
                    // itself, and this client failing the connection over its
                    // own event buffer, are both a server that is still running
                    // and still holding the port: "start Hermes" would be a
                    // false cause and an action that cannot succeed. They keep
                    // the neutral close, as every non-Local route does.
                    val stoppedOnThisDevice =
                        wasLocalRoute && cause == GatewayCloseCause.TransportFailure
                    _state.value = GatewayConnectionState(
                        GatewayConnectionStatus.NeedsAttention,
                        if (stoppedOnThisDevice) {
                            LocalGatewayCopy.NOT_ANSWERING
                        } else {
                            "The Gateway connection closed. Reconnect to continue."
                        },
                    )
                } else {
                    recordRemoteFailureLocked()
                }
                // Publish the user-facing edge before SSH cleanup, which may
                // need a bounded remote command and must not leave stale Connected UI.
                closeActive()
                if (reconnect != null && networkAvailable) {
                    scheduleRemoteReconnectLocked(reconnect)
                }
            }
        }
    }

    /**
     * Called only while [mutex] is held. Arms a fresh retry ladder for [profile].
     *
     * [failingSinceMillis] is the episode clock the escalation copy is measured
     * against: `null` starts it at the next failure (a nudge or a recovered
     * network has not failed yet), while a cold-start restore passes the moment
     * of the failure that is arming the loop.
     */
    private fun armRemoteReconnectLocked(
        profile: RemoteGatewayProfile,
        failingSinceMillis: Long?,
    ) {
        remoteReconnectAttempts = 1
        beginRemoteFailureEpisodeLocked(failingSinceMillis)
        if (scheduleRemoteReconnectLocked(profile)) {
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Connecting)
        }
    }

    /**
     * Called only while [mutex] is held. Records one failed remote attempt and
     * publishes the user-facing edge. Desktop parity: retries are unbounded with
     * full-jitter backoff, so escalation is time-based — after
     * RECONNECT_ESCALATE_AFTER_MILLIS of continuous failure the state explains
     * itself, but the loop keeps retrying underneath instead of stranding the
     * user on a button.
     */
    private fun recordRemoteFailureLocked() {
        remoteReconnectAttempts += 1
        val now = nowMillis()
        val failingSince = remoteFailingSinceMillis ?: now.also {
            remoteFailingSinceMillis = it
        }
        if (now - failingSince >= RECONNECT_ESCALATE_AFTER_MILLIS) {
            remoteReconnectEscalated = true
        }
        _state.value = if (remoteReconnectEscalated) {
            GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, STILL_TRYING_MESSAGE)
        } else {
            GatewayConnectionState(GatewayConnectionStatus.Connecting)
        }
    }

    /**
     * Called only while [mutex] is held. Arms the retry job, or parks the route
     * and returns false when the process-lifecycle gate is closed. The gate that
     * owns the decision owns the user-facing edge, so no caller can arm nothing
     * and leave a stale `Connecting` behind.
     */
    private fun scheduleRemoteReconnectLocked(profile: RemoteGatewayProfile): Boolean {
        cancelReconnectLocked()
        if (!applicationForegroundSignal.get()) {
            publishUnlessEscalatedLocked(
                GatewayConnectionState(GatewayConnectionStatus.Disconnected),
            )
            return false
        }
        val generation = reconnectGeneration
        val expectedIntentGeneration = connectIntent.get().generation
        reconnectJob = scope.launch { runRemoteReconnect(profile, generation, expectedIntentGeneration) }
        return true
    }

    private suspend fun runRemoteReconnect(
        profile: RemoteGatewayProfile,
        generation: Long,
        expectedIntentGeneration: Long,
    ) {
        var reconnectIntentGeneration = expectedIntentGeneration
        while (true) {
            val delayMillis = mutex.withLock {
                if (!canReconnectLocked(profile, generation)) return
                // Full-jitter exponential backoff, Desktop's exact shape:
                // uniform in [0, min(cap, base * 2^attempt)) so a restarting
                // Gateway is not redialed in lockstep by every client.
                minOf(RECONNECT_BACKOFF_CAP_MILLIS, RECONNECT_BACKOFF_BASE_MILLIS shl (remoteReconnectAttempts - 1).coerceAtMost(16))
                    .let { ceiling -> (reconnectJitter() * ceiling).toLong() }
            }
            reconnectWait(delayMillis)
            val intent = beginReconnectIntent(
                expectedGeneration = reconnectIntentGeneration,
                job = currentCoroutineContext()[Job],
            ) ?: return

            val result = openRemote(profile, browser = null, intent, requireForeground = true) {
                if (!canReconnectLocked(profile, generation)) throw CancellationException()
                RemoteOpenAdmission(
                    routeGeneration = generation,
                    networkGeneration = networkEventGeneration.get(),
                )
            }
            reconnectIntentGeneration = intent.generation
            when (result) {
                GatewayConnectResult.Connected -> {
                    mutex.withLock {
                        if (reconnectGeneration == generation) reconnectJob = null
                    }
                    return
                }

                is GatewayConnectResult.Failed -> {
                    mutex.withLock {
                        // The generation gate runs before the route gate: a
                        // terminal failure clears desiredRemoteProfile, and the
                        // NeedsAttention edge below still has to be published.
                        if (reconnectGeneration != generation) return
                        if (!result.retryable) {
                            reconnectJob = null
                            _state.value = GatewayConnectionState(
                                GatewayConnectionStatus.NeedsAttention,
                                result.message,
                            )
                            return
                        }
                        if (!canReconnectLocked(profile, generation)) return
                        // Escalation is informational: the loop continues.
                        recordRemoteFailureLocked()
                    }
                }

                else -> return
            }
        }
    }

    /**
     * Called only while [mutex] is held. The route is still the one we want and
     * the network is up by both the synchronous signal and the guarded flag —
     * they disagree in both directions mid-transition, so both must hold.
     */
    private fun remoteRouteLiveLocked(profile: RemoteGatewayProfile): Boolean =
        networkAvailableSignal.get() &&
            networkAvailable &&
            desiredRemoteProfile == profile

    private fun canReconnectLocked(profile: RemoteGatewayProfile, generation: Long): Boolean =
        applicationForegroundSignal.get() &&
            remoteRouteLiveLocked(profile) &&
            reconnectGeneration == generation

    /**
     * Process lifecycle gate for automatic Remote Gateway redials. A retry
     * already inside a bounded network call may finish after backgrounding,
     * but no later automatic attempt is admitted until foreground resume.
     */
    fun applicationForegroundChanged(foreground: Boolean) {
        applicationForegroundSignal.set(foreground)
        if (foreground) nudgeRemoteReconnect()
    }

    /**
     * Wake/online/foreground nudge: when a desired remote route exists but
     * nothing is connected, reset the backoff ladder and redial immediately.
     * A healthy connection is never torn down; a user-driven Disconnected
     * state is never overridden because [desiredRemoteProfile] is null there.
     */
    fun nudgeRemoteReconnect() {
        scope.launch {
            mutex.withLock {
                if (!applicationForegroundSignal.get()) return@withLock
                // Any open already in flight outranks the nudge: bumping the
                // generation here could cancel an interactive connect and then
                // claim its released intent non-interactively.
                if (connectIntent.get().job != null) return@withLock
                val profile = desiredRemoteProfile ?: return@withLock
                if (remoteRouteLiveLocked(profile) && active == null) {
                    armRemoteReconnectLocked(profile, failingSinceMillis = null)
                }
            }
        }
    }

    private fun requireRemoteOpenCurrentLocked(
        intent: ConnectIntent,
        profile: RemoteGatewayProfile,
        admission: RemoteOpenAdmission,
        requireForeground: Boolean,
    ) {
        if (
            !isCurrentConnectIntent(intent) ||
            !networkAvailableSignal.get() ||
            !networkAvailable ||
            desiredRemoteProfile != profile ||
            reconnectGeneration != admission.routeGeneration ||
            networkEventGeneration.get() != admission.networkGeneration ||
            (requireForeground && !applicationForegroundSignal.get())
        ) {
            throw CancellationException()
        }
    }

    /** Called only while [mutex] is held. */
    private fun cancelReconnectLocked() {
        reconnectGeneration += 1
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * Called only while [mutex] is held. Claims [profile] for an explicit open:
     * a fresh failure episode, no pending retry, and a snapshot of both
     * generations. Explicit opens are user-owned, so they are never fenced by
     * the process-lifecycle gate.
     */
    private fun claimRemoteRouteLocked(profile: RemoteGatewayProfile): RemoteOpenAdmission {
        desiredRemoteProfile = profile
        remoteReconnectAttempts = 0
        beginRemoteFailureEpisodeLocked(null)
        cancelReconnectLocked()
        return RemoteOpenAdmission(
            routeGeneration = reconnectGeneration,
            networkGeneration = networkEventGeneration.get(),
        )
    }

    /** Called only while [mutex] is held. */
    private fun clearRemoteRouteLocked() {
        desiredRemoteProfile = null
        remoteConnectedAtMillis = null
        remoteReconnectAttempts = 0
        beginRemoteFailureEpisodeLocked(null)
        cancelReconnectLocked()
    }

    /**
     * Called only while [mutex] is held. The escalation latch belongs to the
     * failure episode, so the episode clock owns it: starting or clearing the
     * clock always lowers the latch, and nothing else may raise it except
     * [recordRemoteFailureLocked].
     */
    private fun beginRemoteFailureEpisodeLocked(startedAtMillis: Long?) {
        remoteFailingSinceMillis = startedAtMillis
        remoteReconnectEscalated = false
    }

    /**
     * Called only while [mutex] is held. Once an episode has escalated, its
     * actionable surface is latched for the rest of that episode: retries keep
     * running underneath, but they must not replace it with `Connecting` or a
     * transient transport message.
     */
    private fun publishUnlessEscalatedLocked(state: GatewayConnectionState) {
        if (!remoteReconnectEscalated) _state.value = state
    }

    private fun fail(
        kind: ProbeFailure?,
        message: String,
        retryable: Boolean = true,
    ): GatewayConnectResult.Failed {
        _state.value = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, message)
        return GatewayConnectResult.Failed(kind, message, retryable)
    }

    /**
     * What a person is told about a failed connection, and — just as
     * important — what they are *not* told.
     *
     * The third branch is the lesson of #114. A `Throwable` that is neither a
     * domain failure nor an [IOException] did not come from the network: it is
     * this app breaking. Reporting that as "check the host and reconnect" sends
     * the person to inspect the one component that is demonstrably fine, and it
     * is what let a crash inside the sign-in hand-off look exactly like an
     * unreachable Gateway for a whole device run. Its type is logged so the
     * next one is one grep away; its message never is, because a message
     * routinely carries a host or a path.
     */
    private fun safeConnectionMessage(failure: Throwable): String = when {
        failure is RemoteLifecycleException ||
            failure is GatewayConnectionException ||
            failure is GatewayAuthException ||
            failure is GatewayRpcException ->
            failure.message ?: HOST_FAILURE_MESSAGE

        failure !is IOException -> {
            logAppFailure(failure.javaClass.name)
            APP_FAILURE_MESSAGE
        }

        else -> HOST_FAILURE_MESSAGE
    }

    override fun close() {
        scope.launch { disconnect() }
    }

    private sealed interface ActiveConnection {
        val rpc: GatewayRpcClient

        data class Ssh(
            val transport: SshTransport,
            val backend: RemoteBackend,
            val forward: com.hermesagent.mobile.data.ssh.SshForward,
            override val rpc: GatewayRpcClient,
        ) : ActiveConnection

        data class Remote(
            override val rpc: GatewayRpcClient,
            val profile: RemoteGatewayProfile,
        ) : ActiveConnection

        /**
         * A Hermes running on this device. The profile carries an address and a
         * row id and nothing else — the session token is held by the leg that
         * opened the socket, never by the connection record.
         */
        data class Local(
            override val rpc: GatewayRpcClient,
            val profile: LocalGatewayProfile,
        ) : ActiveConnection
    }

    /** Generation and job are published together, so invalidation cannot miss a new job. */
    private data class ConnectIntent(val generation: Long, val job: Job?)

    private data class RemoteOpenAdmission(
        val routeGeneration: Long,
        val networkGeneration: Long,
    )

    private fun beginConnectIntent(job: Job?): ConnectIntent {
        val previous = connectIntent.getAndUpdate { current ->
            ConnectIntent(current.generation + 1, job)
        }
        previous.job?.takeIf { it !== job }?.cancel()
        return ConnectIntent(previous.generation + 1, job)
    }

    /** Claims an idle intent only if no user, profile, or network action superseded the retry. */
    private fun beginReconnectIntent(expectedGeneration: Long, job: Job?): ConnectIntent? {
        while (true) {
            val current = connectIntent.get()
            if (current.generation != expectedGeneration || current.job != null) return null
            val replacement = ConnectIntent(current.generation + 1, job)
            if (connectIntent.compareAndSet(current, replacement)) return replacement
        }
    }

    private fun invalidateConnectIntent(): ConnectIntent {
        val previous = connectIntent.getAndUpdate { current ->
            ConnectIntent(current.generation + 1, null)
        }
        previous.job?.cancel()
        return ConnectIntent(previous.generation + 1, null)
    }

    private fun isCurrentConnectIntent(intent: ConnectIntent): Boolean =
        connectIntent.get().let { current ->
            current.generation == intent.generation && current.job === intent.job
        }

    /** Releases a completed job without changing its generation. */
    private fun releaseConnectIntent(intent: ConnectIntent): Boolean {
        while (true) {
            val current = connectIntent.get()
            if (current.generation != intent.generation || current.job !== intent.job) return false
            if (connectIntent.compareAndSet(current, current.copy(job = null))) return true
        }
    }

    private companion object {
        const val HOST_FAILURE_MESSAGE = "The Gateway connection failed. Check the host and reconnect."
        const val APP_FAILURE_MESSAGE =
            "Hermes hit a problem in the app while connecting. Try again, and report it if it repeats."
        const val NETWORK_WAIT_MESSAGE = "Waiting for a network connection."
        const val STILL_TRYING_MESSAGE =
            "Still trying to reach the Gateway. Check your connection or the host."
        /** A connection that held this long counts as healthy again. */
        const val STABLE_REMOTE_CONNECTION_MILLIS = 30_000L
        /** Full-jitter backoff shape, from Desktop's reconnect-backoff.ts. */
        const val RECONNECT_BACKOFF_BASE_MILLIS = 300L
        const val RECONNECT_BACKOFF_CAP_MILLIS = 15_000L
        /**
         * After this long failing continuously, surface a calm actionable
         * state — while retries continue underneath. Time-based, matching
         * Desktop's ~45s calibration.
         */
        const val RECONNECT_ESCALATE_AFTER_MILLIS = 45_000L
    }
}

internal fun Throwable.isRetryableRemoteConnectionFailure(): Boolean =
    this !is GatewayAuthException || (statusCode != null && statusCode !in setOf(401, 403))
