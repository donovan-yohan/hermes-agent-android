package com.hermesagent.mobile.plugins

import com.hermesagent.mobile.data.ssh.redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

/** Wire identity is never derived from redacted display text or a colliding leaf name. */
data class AgentPluginRow(
    val key: String?,
    val name: String,
    val description: String,
    val version: String,
    val source: String,
    val status: String,
    val portable: Boolean,
    val catalogName: String?,
    val catalogTier: String?,
) {
    val enabled: Boolean get() = status == "enabled"
}

enum class AgentPluginsPhase { Loading, Ready, Disconnected, Unavailable, Error }
data class AgentPluginsState(
    val phase: AgentPluginsPhase = AgentPluginsPhase.Loading,
    val rows: List<AgentPluginRow> = emptyList(),
    val busyKey: String? = null,
    val unconfirmed: Boolean = false,
)

/**
 * One visible owner, explicit profile, endpoint and ready leg. Never follows a replacement route.
 * Source: apps/desktop/src/store/agent-plugins.ts:36-69,113-249 and
 * tui_gateway/methods_tools.py:1656-1690,1725-1748,1854-1866 @
 * 81a55437f3f31a9f12a30c53b6c5d49d420898aa.
 */
class AgentPluginsStore(
    private val host: PluginHost,
    val profile: String,
    private val ownerCurrent: () -> Boolean = { true },
) {
    private val endpoint = host.endpointGeneration.value
    private val leg = host.connectionToken.value
    @Volatile private var closed = false
    private var operation = 0L
    private val mutable = MutableStateFlow(AgentPluginsState(
        phase = if (leg == null || profile.isBlank()) AgentPluginsPhase.Disconnected else AgentPluginsPhase.Loading,
    ))
    val state = mutable.asStateFlow()

    fun close() { closed = true; operation++ }
    private fun current(): Boolean = !closed && ownerCurrent() && profile.isNotBlank() && leg != null &&
        host.endpointGeneration.value == endpoint && host.connectionToken.value === leg

    private suspend fun call(action: String, key: String? = null, enable: Boolean? = null): PluginHostResult {
        if (!current()) return PluginHostResult.Refused(0, "Reconnect to load agent plugins.")
        return host.requestAtEndpointGuarded(endpoint, "plugins.manage", buildJsonObject {
            put("action", action); put("profile", profile)
            if (key != null) put("key", key)
            if (enable != null) put("enable", enable)
        }, ::current)
    }

    suspend fun refresh() {
        if (!current() || mutable.value.busyKey != null) return
        val ticket = ++operation
        mutable.value = AgentPluginsState()
        val reply = call("list")
        if (!current() || ticket != operation) return
        mutable.value = listState(reply)
    }

    /** Success requires an independent inventory readback of exactly this key and status. */
    suspend fun toggle(key: String, enable: Boolean) {
        val before = mutable.value
        val row = before.rows.singleOrNull { it.key == key } ?: return
        if (!current() || before.phase != AgentPluginsPhase.Ready || before.busyKey != null || before.unconfirmed ||
            key.isBlank() || key != key.trim() || row.enabled == enable) return
        val ticket = ++operation
        mutable.value = before.copy(busyKey = key, unconfirmed = false)
        try {
            val receipt = call("toggle", key, enable)
            if (!current() || ticket != operation) return
            if (receipt === PluginHostResult.UnavailableOnGateway) {
                mutable.value = AgentPluginsState(AgentPluginsPhase.Unavailable)
                return
            }
            val ok = ((receipt as? PluginHostResult.Success)?.result as? JsonObject)
                ?.get("ok") == JsonPrimitive(true)
            // Even a refused/timed-out mutation can have reached the host. Refresh, never retry it.
            val after = listState(call("list"))
            if (!current() || ticket != operation) return
            val confirmed = ok && after.phase == AgentPluginsPhase.Ready &&
                after.rows.singleOrNull { it.key == key }?.status == if (enable) "enabled" else "disabled"
            mutable.value = after.copy(unconfirmed = !confirmed)
        } catch (cancelled: CancellationException) {
            if (current() && ticket == operation) mutable.value = before.copy(unconfirmed = true)
            throw cancelled
        }
    }

    private fun listState(result: PluginHostResult): AgentPluginsState = when (result) {
        PluginHostResult.UnavailableOnGateway -> AgentPluginsState(AgentPluginsPhase.Unavailable)
        is PluginHostResult.Refused -> AgentPluginsState(AgentPluginsPhase.Error)
        is PluginHostResult.Success -> try {
            AgentPluginsState(AgentPluginsPhase.Ready, parseAgentPlugins(result.result))
        } catch (_: IllegalArgumentException) { AgentPluginsState(AgentPluginsPhase.Error) }
    }
}

/** Keep only display metadata; never retain install paths, settings, or credential declarations. */
internal fun parseAgentPlugins(value: JsonElement): List<AgentPluginRow> {
    val list = (value as? JsonObject)?.get("plugins") as? JsonArray ?: throw IllegalArgumentException()
    val rows = list.map { raw ->
        val row = raw as? JsonObject ?: throw IllegalArgumentException()
        val name = row.text("name")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException()
        val key = row.text("key")?.takeIf { it.isNotBlank() && it == it.trim() }
        val status = row.text("status")?.takeIf { it in setOf("enabled", "disabled", "not enabled") }
            ?: throw IllegalArgumentException()
        AgentPluginRow(key, display(name), display(row.text("description").orEmpty()),
            display(row.text("version").orEmpty()), display(row.text("source").orEmpty()), status,
            row["portable"] == JsonPrimitive(true), row.text("catalog_name")?.let(::display),
            row.text("catalog_tier")?.let(::display))
    }
    require(rows.mapNotNull { it.key }.let { it.size == it.distinct().size })
    return rows.filter { row ->
        if (row.source == "bundled") (row.key ?: row.name) in setOf("disk-cleanup", "security-guidance")
        else row.key?.let { key -> listOf("dashboard_auth/", "model-providers/", "platforms/").none(key::startsWith) } ?: true
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it.isString }?.content
private fun display(raw: String): String = redact(raw).replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().take(600)
