package com.hermesagent.mobile.data.gateway

import com.hermesagent.mobile.data.ssh.HostAnchor
import com.hermesagent.mobile.data.ssh.HostProfile
import com.hermesagent.mobile.data.ssh.ProbeFailure
import com.hermesagent.mobile.data.ssh.SshCredential
import com.hermesagent.mobile.data.ssh.SshOpenResult
import com.hermesagent.mobile.data.ssh.SshSessionOpener
import com.hermesagent.mobile.data.ssh.SshTransport
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

    /** Restores a saved Remote Gateway without opening an interactive browser. */
    suspend fun restoreRemote(profile: RemoteGatewayProfile): GatewayConnectResult =
        GatewayConnectResult.Failed(null, "Reconnect to this Gateway from settings.")

    suspend fun forgetRemoteAuthentication(profile: RemoteGatewayProfile)

    /**
     * Rotate the live leg's credential once, without user interaction, for a
     * REST caller the Gateway just refused.
     *
     * False means this leg has nothing to rotate — an SSH-tunneled loopback
     * session token and a token-mode gateway token both live for the lifetime
     * of the connection that carries them — or the rotation was refused. The
     * caller's next honest move is the app's ordinary sign-in, never a second
     * rotation.
     */
    suspend fun refreshCredential(): Boolean = false

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
) : GatewayServedTokenResolver {
    private val publicHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun resolve(localPort: Int): ByteArray? {
        require(localPort in 1..65535) { "The local Gateway port is invalid." }
        val pending = AtomicReference<ByteArray?>(null)
        return try {
            runInterruptible(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("http://127.0.0.1:$localPort/")
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
    private val reconnectWait: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val reconnectJitter: () -> Double = { Random.nextDouble() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** Test seam for the cancellation/foreground interleave; production is immediate. */
    private val beforeReconnectCancellationCleanup: suspend () -> Unit = {},
) : Closeable, GatewayConnectionController {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(GatewayConnectionState())
    private val _client = MutableStateFlow<GatewayRpcClient?>(null)
    private val _gatewayHttp = MutableStateFlow<GatewayHttp?>(null)
    private val _imageLoader = MutableStateFlow<GatewayImageLoader?>(null)
    private var active: ActiveConnection? = null
    private val connectIntent = AtomicReference(ConnectIntent(generation = 0, job = null))
    private var rpcMonitor: Job? = null
    private var reconnectJob: Job? = null
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

    override suspend fun connectRemote(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher,
    ): GatewayConnectResult {
        val intent = beginConnectIntent(currentCoroutineContext()[Job])
        return openRemote(profile, browser, intent, requireForeground = false) {
            claimRemoteRouteLocked(profile)
        }
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

    private suspend fun openRemote(
        profile: RemoteGatewayProfile,
        browser: GatewayBrowserLauncher?,
        intent: ConnectIntent,
        requireForeground: Boolean,
        prepareAdmissionLocked: () -> RemoteOpenAdmission,
    ): GatewayConnectResult {
        var admission: RemoteOpenAdmission? = null
        return try {
            try {
                admission = mutex.withLock {
                    if (!isCurrentConnectIntent(intent)) throw CancellationException()
                    prepareAdmissionLocked()
                }
                mutex.withLock {
                    requireRemoteOpenCurrentLocked(intent, profile, checkNotNull(admission), requireForeground)
                    val connector = remoteConnector
                        ?: return@withLock fail(null, "Remote Gateway connections are unavailable in this build.")
                    closeActive()
                    publishUnlessEscalatedLocked(GatewayConnectionState(GatewayConnectionStatus.Connecting))
                    val rpc = connector.open(profile, browser)
                    try {
                        // The authenticated RPC round trip, not merely a WS
                        // upgrade, is the readiness boundary.
                        rpc.request("session.list", buildJsonObject { put("limit", JsonPrimitive(1)) })
                        requireRemoteOpenCurrentLocked(intent, profile, admission, requireForeground)
                        active = ActiveConnection.Remote(rpc, profile)
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

                                else -> _state.value =
                                    GatewayConnectionState(GatewayConnectionStatus.Disconnected)
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

    override suspend fun refreshCredential(): Boolean {
        // Only the remote leg carries a rotatable bearer. Read the live
        // connection under the same lock that installs it, so a rotation can
        // never be aimed at a profile that is already gone.
        val profile = mutex.withLock { (active as? ActiveConnection.Remote)?.profile } ?: return false
        val connector = remoteConnector ?: return false
        // A rotation that throws is a rotation that did not happen; the caller
        // falls through to sign-in rather than treating it as an outage.
        return runCatching { connector.refreshAccessToken(profile) }.getOrDefault(false)
    }

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
        invalidateConnectIntent()
        scope.launch {
            mutex.withLock {
                if (networkEventGeneration.get() != eventGeneration) return@withLock
                val wasAvailable = networkAvailable
                networkAvailable = available
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
        val invalidatedIntent = invalidateConnectIntent()
        scope.launch {
            mutex.withLock {
                if (connectIntent.get().generation != invalidatedIntent.generation) return@withLock
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
        _client.value = null
        _gatewayHttp.value = null
        _imageLoader.value = null
        if (closing != null) {
            runCatching { closing.rpc.close() }
            when (closing) {
                is ActiveConnection.Remote -> Unit
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
            rpc.closed.first()
            mutex.withLock {
                if (active?.rpc !== rpc) return@withLock
                val reconnect = (active as? ActiveConnection.Remote)?.profile
                    ?.takeIf { desiredRemoteProfile == it }
                val stableConnection = remoteConnectedAtMillis
                    ?.let { connectedAt -> nowMillis() - connectedAt >= STABLE_REMOTE_CONNECTION_MILLIS }
                    ?: false
                if (stableConnection) {
                    remoteReconnectAttempts = 0
                    beginRemoteFailureEpisodeLocked(null)
                }
                if (!networkAvailable) {
                    cancelReconnectLocked()
                    _state.value = GatewayConnectionState(
                        GatewayConnectionStatus.NeedsAttention,
                        NETWORK_WAIT_MESSAGE,
                    )
                } else if (reconnect == null) {
                    // No desired route to retry against (user-initiated close).
                    _state.value = GatewayConnectionState(
                        GatewayConnectionStatus.NeedsAttention,
                        "The Gateway connection closed. Reconnect to continue.",
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

    private fun safeConnectionMessage(failure: Throwable): String = when (failure) {
        is RemoteLifecycleException,
        is GatewayConnectionException,
        is GatewayAuthException,
        is GatewayRpcException,
        -> failure.message ?: "The Gateway connection failed. Check the host and reconnect."

        else -> "The Gateway connection failed. Check the host and reconnect."
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
