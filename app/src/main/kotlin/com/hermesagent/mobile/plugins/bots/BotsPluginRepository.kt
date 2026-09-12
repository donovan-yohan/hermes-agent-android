package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.RoundingMode

/**
 * `profiles.list` over the plugin host door — the roster's only data source.
 *
 * The handler is `tui_gateway/methods_profiles.py:237-254` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`. `include_sessions` defaults to
 * true there and is what attaches `last_session` / `canonical_session`; the
 * roster renders both, so it is requested explicitly rather than relied on.
 */
sealed interface BotsRosterLoad {
    /** The Gateway answered with a roster. */
    data class Loaded(val rows: List<BotRosterRow>) : BotsRosterLoad

    /** This Gateway build does not serve `profiles.list` (`-32601`). */
    data object UnavailableOnGateway : BotsRosterLoad

    /** The call reached the Gateway and did not produce a roster. */
    data class Refused(val safeMessage: String) : BotsRosterLoad
}

class BotsPluginRepository(private val host: PluginHost) {

    suspend fun loadRoster(): BotsRosterLoad = when (
        val result = host.request(
            method = PROFILES_LIST,
            params = buildJsonObject { put("include_sessions", JsonPrimitive(true)) },
        )
    ) {
        is PluginHostResult.Success ->
            parseBotsRoster(result.result)
                ?.let(BotsRosterLoad::Loaded)
                ?: BotsRosterLoad.Refused(UNREADABLE_ROSTER)

        PluginHostResult.UnavailableOnGateway -> BotsRosterLoad.UnavailableOnGateway

        is PluginHostResult.Refused -> BotsRosterLoad.Refused(result.safeMessage)
    }

    private companion object {
        const val PROFILES_LIST = "profiles.list"

        /** This app's sentence; the backend's own text is never shown. */
        const val UNREADABLE_ROSTER = "The Gateway sent a roster this app could not read."
    }
}

/**
 * Parse a `profiles.list` answer into roster rows.
 *
 * A row without a usable `name` is dropped rather than invented, and a
 * malformed envelope answers null so the caller keeps its last good roster —
 * the same contract as `parseProfileList` in `data/profiles`.
 *
 * Row fields (`methods_profiles.py:245-250` @ the pin): `name`, `path`,
 * `is_default`, `model`, `provider`, `description`, `display_name`,
 * `skill_count`, plus `last_session` / `worker_session` / `canonical_session` /
 * `ui_meta` / `has_avatar` when `include_sessions` is on.
 */
fun parseBotsRoster(result: JsonElement): List<BotRosterRow>? {
    val root = result as? JsonObject ?: return null
    val rows = root["profiles"] as? JsonArray ?: return null
    return rows.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val name = row.text("name")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        BotRosterRow(
            name = name,
            connectionId = null,
            connectionLabel = null,
            description = row.text("description").orEmpty().trim(),
            displayName = row.text("display_name").orEmpty().trim(),
            canonicalSession = parseSessionPreview(row["canonical_session"]),
            lastSession = parseSessionPreview(row["last_session"]),
            workerSession = parseSessionPreview(row["worker_session"]),
            hasAvatar = row.flag("has_avatar"),
        )
    }
}

/**
 * One session preview. `last_active` is seconds on the wire; the millisecond
 * conversion happens once, in [BotRosterRow.lastActiveMillis].
 */
private fun parseSessionPreview(element: JsonElement?): BotSessionPreview? {
    val row = element as? JsonObject ?: return null
    return BotSessionPreview(
        id = row.text("id")?.trim()?.takeIf(String::isNotEmpty),
        resolvedId = row.text("resolved_id")?.trim()?.takeIf(String::isNotEmpty),
        lastActiveSeconds = row.epochSeconds("last_active"),
        preview = row.text("preview"),
    )
}

/**
 * An epoch-seconds stamp, read off the wire as the Gateway actually sends it.
 *
 * The Gateway hands these out straight from SQLite, where the columns are
 * `REAL` (`hermes_state_common.py:319` @ the pin: `last_activity_at REAL`,
 * `started_at REAL`), so the JSON content is `1700000900.5` or
 * `1700000900.0` — not a whole number `toLongOrNull()` can read. That parse
 * answered `0` for every row, which is the bug that would have left the worker
 * signal dead on a real Gateway while every integer fixture passed.
 * [BigDecimal] reads both shapes and truncates the fraction; anything else
 * (NaN, a non-number) is `0`, which reads as "no activity" rather than as an
 * age.
 */
private fun JsonObject.epochSeconds(name: String): Long =
    text(name)
        ?.toBigDecimalOrNull()
        ?.setScale(0, RoundingMode.DOWN)
        ?.let { runCatching { it.longValueExact() }.getOrNull() }
        ?.coerceAtLeast(0L)
        ?: 0L

private fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.flag(name: String): Boolean = when (val value = this[name]) {
    is JsonPrimitive -> value.content.equals("true", ignoreCase = true) || value.content == "1"
    else -> false
}
