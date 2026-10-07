package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import kotlinx.serialization.json.*

data class BotToolset(val name: String, val enabled: Boolean, val description: String,
    val label: String = name, val toolCount: Int? = null)
data class BotToolsetsRead(val pinned: Boolean, val rows: List<BotToolset>) {
    val enabled: Set<String> get() = rows.filter { it.enabled }.map { it.name }.toSet()
}

sealed interface BotToolsetsSave {
    data class Saved(val actual: BotToolsetsRead) : BotToolsetsSave
    data object Stale : BotToolsetsSave
    data object Rejected : BotToolsetsSave
    data object Unconfirmed : BotToolsetsSave
}

/** 587e673e methods_profiles.py:345-405,663-724; platform_toolsets.cli, not runtime tools. */
class BotsToolsetsRepository(private val host: PluginHost, private val timeoutMillis: Long = 20_000L) {
    private suspend fun call(target: BotManagementTarget, method: String, params: JsonObject,
        dispatchAllowed: (() -> Boolean)? = null): JsonObject? =
        host.requestBotObject(target, method, params, timeoutMillis, dispatchAllowed)
    suspend fun save(target: BotManagementTarget, baseline: BotToolsetsRead, desired: Set<String>,
        dispatchAllowed: () -> Boolean): BotToolsetsSave {
        if (desired.isEmpty() || desired.any { name -> baseline.rows.none { it.name == name } })
            return BotToolsetsSave.Rejected
        return configure(target, baseline, desired, dispatchAllowed)
    }

    // The UI requires separate, explicit consent. Empty is reset, NEVER disable-all.
    suspend fun restoreDefaults(target: BotManagementTarget, baseline: BotToolsetsRead,
        dispatchAllowed: () -> Boolean): BotToolsetsSave = configure(target, baseline, emptySet(), dispatchAllowed)

    private suspend fun configure(target: BotManagementTarget, baseline: BotToolsetsRead, desired: Set<String>,
        dispatchAllowed: () -> Boolean): BotToolsetsSave {
        if (!dispatchAllowed()) return BotToolsetsSave.Rejected
        val fresh = describe(target) ?: return BotToolsetsSave.Unconfirmed
        // This detects known staleness only. Upstream has no toolset CAS; a concurrent server write can still race.
        if (fresh != baseline) return BotToolsetsSave.Stale
        val value = call(target, "profiles.configure", buildJsonObject {
            put("name", target.name)
            put("enabled_toolsets", JsonArray(baseline.rows.filter { it.name in desired }.map { JsonPrimitive(it.name) }))
        }, dispatchAllowed) ?: return BotToolsetsSave.Unconfirmed
        if (value.flag("ok") != true || (value["applied"] as? JsonObject)?.flag("toolsets") != true)
            return BotToolsetsSave.Unconfirmed
        val actual = describe(target) ?: return BotToolsetsSave.Unconfirmed
        if (!dispatchAllowed()) return BotToolsetsSave.Unconfirmed
        if (desired.isEmpty()) return if (!actual.pinned) BotToolsetsSave.Saved(actual) else BotToolsetsSave.Unconfirmed
        if (!actual.pinned || actual.enabled != desired) return BotToolsetsSave.Unconfirmed
        val returned = actual.rows.map { it.name }.toSet()
        if (baseline.rows.any { it.name !in returned && (it.name in desired || it.name !in HIDDEN_WHEN_DISABLED) })
            return BotToolsetsSave.Unconfirmed
        return BotToolsetsSave.Saved(actual)
    }

    suspend fun describe(target: BotManagementTarget): BotToolsetsRead? {
        val value = call(target, "profiles.describe", buildJsonObject { put("name", target.name) }) ?: return null
        if (value.text("name") != target.name) return null
        val pinned = value.flag("toolsets_pinned") ?: return null
        val rows = value["toolsets"] as? JsonArray ?: return null
        val seen = mutableSetOf<String>()
        val parsed = rows.map { item ->
            val row = item as? JsonObject ?: return null
            val name = row.text("name") ?: return null
            if (name.isBlank() || name != name.trim() || !seen.add(name)) return null
            BotToolset(name, row.flag("enabled") ?: return null, row.text("description").orEmpty(),
                row.text("label")?.takeIf { it.isNotBlank() } ?: name,
                (row["tool_count"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 })
        }
        return BotToolsetsRead(pinned, parsed)
    }
}
// 587e673e tools_config.py:111 + methods_profiles.py:581-584. Unknown disappearance fails closed.
private val HIDDEN_WHEN_DISABLED = setOf("homeassistant", "spotify", "discord", "discord_admin", "video",
    "video_gen", "x_search", "a2a", "kanban", "yuanbao")
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
