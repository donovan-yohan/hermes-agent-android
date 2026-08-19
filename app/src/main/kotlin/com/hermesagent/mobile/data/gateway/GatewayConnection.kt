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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
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
    data class Failed(val kind: ProbeFailure?, val message: String) : GatewayConnectResult
}

/** The process-scoped Gateway controls consumed by the SSH configuration UI. */
internal interface GatewayConnectionController {
    val state: StateFlow<GatewayConnectionState>

    suspend fun connect(
        profile: HostProfile,
        credential: SshCredential,
    ): GatewayConnectResult

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
) : Closeable, GatewayConnectionController {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(GatewayConnectionState())
    private val _client = MutableStateFlow<GatewayRpcClient?>(null)
    private var active: ActiveConnection? = null
    private val connectIntent = AtomicReference(ConnectIntent(generation = 0, job = null))
    private var rpcMonitor: Job? = null

    override val state: StateFlow<GatewayConnectionState> = _state.asStateFlow()
    val client: StateFlow<GatewayRpcClient?> = _client.asStateFlow()

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

            active = ActiveConnection(transport, backend, forward, rpc)
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
            closeActive()
            _state.value = GatewayConnectionState(GatewayConnectionStatus.Disconnected)
        }
    }

    fun networkChanged() {
        val invalidatedIntent = invalidateConnectIntent()
        scope.launch {
            mutex.withLock {
                if (connectIntent.get().generation != invalidatedIntent.generation) return@withLock
                if (active == null && _state.value.status != GatewayConnectionStatus.Connecting) return@withLock
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
        if (closing != null) {
            runCatching { closing.rpc.close() }
            runCatching { closing.forward.close() }
            runCatching { closing.backend.shutdown() }
            runCatching { closing.transport.close() }
        }
    }

    private fun watchRpc(rpc: GatewayRpcClient) {
        rpcMonitor?.cancel()
        rpcMonitor = scope.launch {
            rpc.closed.first()
            mutex.withLock {
                if (active?.rpc !== rpc) return@withLock
                _state.value = GatewayConnectionState(
                    GatewayConnectionStatus.NeedsAttention,
                    "The Gateway connection closed. Reconnect to continue.",
                )
                closeActive()
            }
        }
    }

    private fun fail(kind: ProbeFailure?, message: String): GatewayConnectResult.Failed {
        _state.value = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention, message)
        return GatewayConnectResult.Failed(kind, message)
    }

    private fun safeConnectionMessage(failure: Throwable): String = when (failure) {
        is RemoteLifecycleException,
        is GatewayConnectionException,
        is GatewayRpcException,
        -> failure.message ?: "The Gateway connection failed. Check the host and reconnect."

        else -> "The Gateway connection failed. Check the host and reconnect."
    }

    override fun close() {
        scope.launch { disconnect() }
    }

    private data class ActiveConnection(
        val transport: SshTransport,
        val backend: RemoteBackend,
        val forward: com.hermesagent.mobile.data.ssh.SshForward,
        val rpc: GatewayRpcClient,
    )

    /** Generation and job are published together, so invalidation cannot miss a new job. */
    private data class ConnectIntent(val generation: Long, val job: Job?)

    private fun beginConnectIntent(job: Job?): ConnectIntent {
        val previous = connectIntent.getAndUpdate { current ->
            ConnectIntent(current.generation + 1, job)
        }
        previous.job?.takeIf { it !== job }?.cancel()
        return ConnectIntent(previous.generation + 1, job)
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
}
