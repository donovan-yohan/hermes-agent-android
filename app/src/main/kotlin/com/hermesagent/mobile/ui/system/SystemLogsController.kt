package com.hermesagent.mobile.ui.system

import com.hermesagent.mobile.data.gateway.GatewayHttp
import com.hermesagent.mobile.data.gateway.GatewayHttpRequest
import com.hermesagent.mobile.data.gateway.GatewayHttpResult
import com.hermesagent.mobile.data.gateway.EndpointDispatchingGatewayHttp
import com.hermesagent.mobile.data.gateway.consumeBody
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.data.updates.safeBackendLine
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
                is GatewayHttpResult.Success -> response.consumeBody { bytes -> parseLogTail(bytes) }
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
internal fun parseLogTail(bytes: ByteArray): List<String>? = try {
    val root = Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
    val lines = root?.get("lines") as? JsonArray
    if (lines == null || lines.size > 200 || lines.any { it !is JsonPrimitive || !it.isString }) null
    else sanitizeLogTail(lines.map { (it as JsonPrimitive).content })
} catch (_: IllegalArgumentException) { null }

// Logs carry more than SSH diagnostics. Redact across line boundaries before capping,
// so truncated PEMs and quoted credentials cannot leave their continuation visible.
private val LOG_CREDENTIAL = Regex(
    "(?i)(?<![a-z_])([\\\"']?(?:authorization|bearer|[a-z_]*(?:token|password|secret|api[_-]?key))[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*(?:\\\"|$)|'[^']*(?:'|$)|[^\\s,;]+)",
)
private val LOG_AUTH = Regex("(?im)(authorization[\\\"']?\\s*[:=]\\s*)[^\\r\\n]+|\\bBearer\\s+[^\\s\\\"',;]+")
private val LOG_HOST = Regex("(?i)((?:host(?:name)?|fingerprint)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)")
// product-copy-allow: This is a bounded network-identity redaction regex, never rendered product copy.
private val LOG_ADDRESS = Regex("(?i)(?:https?|wss?|ssh)://[^\\s\\\"'<>]+|SHA256:[A-Za-z0-9+/=]+|\\b(?:[a-z0-9_-]+\\.){1,127}[a-z][a-z0-9-]*\\b|\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b|(?<![a-z0-9])(?=[0-9a-f:]{0,39}::)(?:[0-9a-f]{0,4}:){2,8}[0-9a-f:]{0,39}|(?<![a-z0-9])(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?![a-z0-9])", RegexOption.IGNORE_CASE)
internal fun sanitizeLogTail(lines: List<String>): List<String> {
    if (lines.isEmpty()) return emptyList()
    var text = redact(lines.joinToString("\n"))
    text = LOG_AUTH.replace(text, "<redacted>")
    text = LOG_CREDENTIAL.replace(text, "$1<redacted>")
    text = LOG_HOST.replace(text, "$1<redacted>")
    text = LOG_ADDRESS.replace(text, "<redacted>")
    return text.lines().take(200).map { safeBackendLine(it).filter { ch -> !ch.isISOControl() || ch == '\t' } }
}
