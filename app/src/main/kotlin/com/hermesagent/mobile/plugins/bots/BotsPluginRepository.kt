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
 * The bots plugin's Gateway door: the roster read, the canonical-chat lookup,
 * and (Phase B) the one open-or-create path.
 *
 * The roster handler is `tui_gateway/methods_profiles.py:237-254` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`. `include_sessions` defaults to
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

/** The only conclusions a read-only canonical lookup is allowed to make. */
sealed interface BotChatLookup {
    /** The registry named exactly one exact-title row; `resolved_id` wins over `id`. */
    data class Found(val durableId: String) : BotChatLookup

    /**
     * The registry confirmed this profile has no canonical chat: a successful,
     * well-formed answer with zero rows, and a roster that claims no
     * `canonical_session` either. Only this outcome can license a creation.
     */
    data object Missing : BotChatLookup

    /** Nothing could be concluded — refusal, unavailable, malformed or ambiguous. */
    data object Unsafe : BotChatLookup
}

/** What one open-or-create attempt established for the tapped roster row. */
sealed interface BotChatOpen {
    /**
     * The registry now names exactly one exact-title row — the row that was
     * already there, or the one this attempt just created and titled.
     */
    data class Opened(val durableId: String) : BotChatOpen

    /** Nothing could be confirmed. Fail closed: no navigation, no prompt, no second mint. */
    data object Unsafe : BotChatOpen
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

    /** Hidden canonical chats bypass SessionCache and are resolved by exact title. */
    suspend fun findCanonicalChat(profile: String, rosterCanonicalId: String?): BotChatLookup {
        val result = host.request(
            method = SESSION_LIST,
            params = buildJsonObject {
                put("profile", JsonPrimitive(profile))
                put("title", JsonPrimitive(CANONICAL_CHAT_TITLE))
                put("limit", JsonPrimitive(CANONICAL_LOOKUP_LIMIT))
                put("include_hidden", JsonPrimitive(true))
            },
        )
        if (result !is PluginHostResult.Success) return BotChatLookup.Unsafe
        val sessions = (result.result as? JsonObject)?.get("sessions") as? JsonArray ?: return BotChatLookup.Unsafe
        if (sessions.isEmpty()) return if (rosterCanonicalId.isNullOrBlank()) BotChatLookup.Missing else BotChatLookup.Unsafe
        // `title` makes this a constrained lookup, not a ranking request. A
        // surprising extra or malformed row therefore means the response no
        // longer proves which hidden chat is canonical; never pick arbitrarily.
        val exact = sessions.singleOrNull() as? JsonObject ?: return BotChatLookup.Unsafe
        if (exact.text("title") != CANONICAL_CHAT_TITLE) return BotChatLookup.Unsafe
        val id = exact.text("resolved_id")?.trim()?.takeIf(String::isNotEmpty)
            ?: exact.text("id")?.trim()?.takeIf(String::isNotEmpty)
            ?: return BotChatLookup.Unsafe
        return BotChatLookup.Found(id)
    }

    /**
     * Phase B: the bot's one forever-chat, created only from a registry that
     * twice confirmed none exists.
     *
     * The pinned Desktop path is `openBotCanonicalChat` / `createCanonicalChat`
     * (`apps/desktop/src/plugins/hermes-bots/canonical-chat.ts:485-519`,
     * `:290-475` @ the pin) and this mirrors its order:
     *
     * 1. Consult the registry. A row opens as-is; an unreadable answer fails
     *    closed without touching `session.create`.
     * 2. Adopt before minting (`:335-346`): the lookup runs a second time, so a
     *    chat created by another surface between the tap and the create is
     *    opened rather than forked.
     * 3. Create it titled, hidden and profile-following, then write the title
     *    eagerly so the row exists before anything is opened or sent.
     * 4. If that title write did not land, re-read the registry and adopt the
     *    exact-title row a concurrent writer won (`:387-410`). No winner means
     *    the attempt is abandoned — the stray lazy session holds no messages and
     *    the gateway prunes it — never a second titled chat.
     *
     * Deliberately absent, and ledgered in `docs/parity/bot-chat.md`: Desktop's
     * kickoff intro. `createCanonicalChat` submits it only on New Agent
     * creation (`kickoff`) or as a legacy-gateway persistence fallback; the pin's
     * gateway materializes the row through the eager title write instead, so
     * opening a chat stays inert and the person's first message is the one that
     * arms live delivery.
     *
     * [isCurrent] is the caller's endpoint fence, consulted before every call
     * after the first: a switch mid-flight must never let this attempt read,
     * create, title or adopt on the machine the app has moved to.
     */
    suspend fun openCanonicalChat(
        profile: String,
        rosterCanonicalId: String?,
        isCurrent: () -> Boolean = { true },
    ): BotChatOpen {
        when (val first = findCanonicalChat(profile, rosterCanonicalId)) {
            is BotChatLookup.Found -> return BotChatOpen.Opened(first.durableId)
            BotChatLookup.Unsafe -> return BotChatOpen.Unsafe
            BotChatLookup.Missing -> Unit
        }
        if (!isCurrent()) return BotChatOpen.Unsafe
        when (val concurrent = findCanonicalChat(profile, rosterCanonicalId)) {
            is BotChatLookup.Found -> return BotChatOpen.Opened(concurrent.durableId)
            BotChatLookup.Unsafe -> return BotChatOpen.Unsafe
            BotChatLookup.Missing -> Unit
        }
        if (!isCurrent()) return BotChatOpen.Unsafe
        val created = createCanonicalChat(profile, isCurrent) ?: return BotChatOpen.Unsafe
        return BotChatOpen.Opened(created)
    }

    /**
     * Create the bot's canonical chat, then make its identity durable.
     *
     * `session.create` is lazy on the pinned gateway — its row appears on the
     * first prompt or on this title write (`tui_gateway/methods_session.py:325-390`
     * @ the pin) — so the eager `session.title` is what closes the untitled
     * window a second tap could mint through (`canonical-chat.ts:368-412`).
     *
     * The request is Desktop's exactly, `source` included in its absence: the
     * bot-chat create does not send one (`canonical-chat.ts:348-363`), so the
     * gateway resolves it from its own environment. This app's own
     * `createSession` sends `"desktop"`, and that difference is deliberate —
     * `source` decides `track_liveness` and the desktop-only cleanup lifecycle
     * (`tui_gateway/session_lifecycle.py:37` @ the pin), and a canonical chat
     * is not this app's ordinary session.
     *
     * Returns the durable id to resume, or null when the chat's identity could
     * not be confirmed.
     */
    private suspend fun createCanonicalChat(profile: String, isCurrent: () -> Boolean): String? {
        val created = host.request(
            method = SESSION_CREATE,
            params = buildJsonObject {
                put("profile", JsonPrimitive(profile))
                put("title", JsonPrimitive(CANONICAL_CHAT_TITLE))
                put("hidden", JsonPrimitive(true))
                put("follow_profile_config", JsonPrimitive(true))
            },
        )
        // A refused, unavailable or unreadable creation is never partially
        // adopted: without both ids there is no durable row to open or title.
        val result = (created as? PluginHostResult.Success)?.result as? JsonObject ?: return null
        val storedId = result.text("stored_session_id")?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val runtimeId = result.text("session_id")?.trim()?.takeIf(String::isNotEmpty) ?: return null

        if (!isCurrent()) return null
        val titled = host.request(
            method = SESSION_TITLE,
            params = buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("title", JsonPrimitive(CANONICAL_CHAT_TITLE))
            },
        )
        if (!isCurrent()) return null
        // A `{"pending": true}` answer still names this runtime's title as
        // queued and the gateway persisted its row before answering, so it is
        // the titled case, exactly as Desktop reads it.
        if (titled is PluginHostResult.Success) return storedId

        // The title write did not land. Only the registry can say whether a
        // concurrent writer took the canonical title (adopt its row) or the
        // write could not be made at all (abandon the attempt).
        if (!isCurrent()) return null
        val winner = findCanonicalChat(profile, null)
        if (!isCurrent()) return null
        return when (winner) {
            is BotChatLookup.Found -> winner.durableId
            BotChatLookup.Missing, BotChatLookup.Unsafe -> null
        }
    }

    private companion object {
        const val PROFILES_LIST = "profiles.list"
        const val SESSION_LIST = "session.list"
        const val SESSION_CREATE = "session.create"
        const val SESSION_TITLE = "session.title"
        const val CANONICAL_CHAT_TITLE = "Bot Chat"
        const val CANONICAL_LOOKUP_LIMIT = 200

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
