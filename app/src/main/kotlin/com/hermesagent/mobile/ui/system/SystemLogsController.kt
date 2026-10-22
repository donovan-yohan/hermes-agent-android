package com.hermesagent.mobile.ui.system

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayHttp
import com.hermesagent.mobile.data.gateway.consumeBody
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.coroutineContext

/** Immutable ready-leg identity; transport is borrowed from the authenticated host. */
internal data class SystemLogsOwner(
    val http: GatewayHttp?, val endpoint: Long, val profile: String?, val profileGeneration: Long = 0,
)

data class SystemLogsState(
    val file: String = "agent",
    val level: String = "all",
    val query: String = "",
    val lines: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val filteredLines: List<String> get() = lines.filter { it.contains(query.trim(), ignoreCase = true) }
}

/**
 * Read contract: api/config.ts:94-128, web_routers/status.py:841-881 @
 * 81a55437f3f31a9f12a30c53b6c5d49d420898aa. Selections/query are UI-only;
 * the bounded redacted tail is ephemeral connection/profile-owned host data.
 */
internal class SystemLogsController(
    private val scope: CoroutineScope,
    private val owners: Flow<SystemLogsOwner>,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val currentOwner: () -> SystemLogsOwner,
) {
    private val mutable = MutableStateFlow(SystemLogsState())
    val state: StateFlow<SystemLogsState> = mutable
    @Volatile private var visible = false
    @Volatile private var generation = 0L
    private var watch: Job? = null
    private var read: Job? = null

    fun resume() {
        if (visible) return
        visible = true
        watch = scope.launch {
            owners.collectLatest {
                invalidate()
                mutable.value = SystemLogsState()
                refresh()
            }
        }
    }

    fun pause() {
        visible = false
        watch?.cancel()
        watch = null
        invalidate()
        mutable.value = SystemLogsState()
    }

    fun selectFile(file: String) {
        if (file !in SystemCopy.LOG_FILES || file == state.value.file) return
        mutable.value = state.value.copy(file = file)
        refresh()
    }

    fun selectLevel(level: String) {
        if (level !in SystemCopy.LOG_LEVELS || level == state.value.level) return
        mutable.value = state.value.copy(level = level)
        refresh()
    }

    fun filter(query: String) { mutable.value = state.value.copy(query = query.take(512)) }

    fun refresh() {
        invalidate()
        if (!visible) return
        val owner = currentOwner()
        val http = owner.http
        if (http == null) {
            mutable.value = state.value.copy(lines = emptyList(), loading = false, error = RECONNECT)
            return
        }
        val selected = state.value
        val ticket = generation
        val current = { visible && generation == ticket && owner == currentOwner() }
        mutable.value = selected.copy(lines = emptyList(), loading = true, error = null)
        read = scope.launch {
            val query = buildMap {
                put("file", selected.file)
                put("lines", "200")
                if (selected.level != "all") put("level", selected.level.uppercase(java.util.Locale.ROOT))
                owner.profile?.let { put("profile", it) }
            }
            val request = GatewayHttpRequest(
                path = "/api/logs", method = "GET", body = null, timeoutMillis = 15_000,
                query = query, maxResponseBytes = 512L * 1024L, isCurrent = current,
            )
            // Production hosts support cancellable enqueue. No unguarded fallback:
            // pause must cancel the actual call, not just ignore its answer.
            val response = if (http is EndpointDispatchingGatewayHttp) {
                http.executeAtDispatch(request) { admit -> current() && admit() }
            } else GatewayHttpResult.Rejected(0, LOAD_FAILED)
            val result = when (response) {
                is GatewayHttpResult.Success -> response.consumeBody { bytes ->
                    withContext(decodeDispatcher) {
                        val decodeContext = coroutineContext
                        parseLogTail(bytes) { decodeContext.ensureActive() }
                    }
                }
                is GatewayHttpResult.Rejected -> {
                    response.envelopeBytes.fill(0)
                    null
                }
            }
            coroutineContext.ensureActive()
            if (!current()) return@launch
            mutable.value = state.value.copy(
                lines = result.orEmpty(), loading = false,
                error = if (result == null) LOAD_FAILED else null,
            )
        }
    }

    private fun invalidate() {
        generation++
        read?.cancel()
        read = null
    }

    companion object {
        const val RECONNECT = "Reconnect to the Gateway to load logs."
        const val LOAD_FAILED = "Couldn’t load logs. Try again."
    }
}

/** Reject malformed tails; never render a raw envelope or transport exception. */
internal fun parseLogTail(bytes: ByteArray, checkpoint: () -> Unit = {}): List<String>? = try {
    checkpoint()
    val root = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    val lines = root?.get("lines") as? JsonArray
    if (lines == null || lines.size > 200 || lines.any { it !is JsonPrimitive || !it.isString }) null
    else sanitizeLogTail(lines.map { (it as JsonPrimitive).content }, checkpoint)
} catch (_: IllegalArgumentException) { null }
