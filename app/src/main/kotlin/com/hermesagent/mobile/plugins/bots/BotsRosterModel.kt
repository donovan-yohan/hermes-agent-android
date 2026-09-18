package com.hermesagent.mobile.plugins.bots

/**
 * The Bots roster's data model: one row per bot, the local presentation
 * metadata that never rides the Gateway, and the two filter axes.
 *
 * The roster's only data source is `profiles.list`
 * (`tui_gateway/methods_profiles.py:205-249` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`, the revision this app's profile
 * model was ported from). Everything in [BotMeta] is deliberately *not* server
 * state — Desktop keeps pin/hide/section on the machine that made them
 * (`apps/desktop/src/plugins/hermes-bots/hidden-bots.ts:23-31`,
 * `user-sections.ts` @ `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`), and this
 * port keeps that split so a hidden bot still works, stays mentionable, and
 * keeps its history.
 */

/**
 * One conversation preview a `profiles.list` row carries.
 *
 * `last_active` is seconds, not milliseconds — Desktop multiplies by 1000 at
 * the point of use (`data.ts:1419`), and every age computation here goes
 * through [BotRosterRow.lastActiveMillis] so the unit is converted exactly
 * once.
 */
data class BotSessionPreview(
    val id: String? = null,
    val resolvedId: String? = null,
    val lastActiveSeconds: Long = 0L,
    val preview: String? = null,
)

/**
 * One roster row, built from a `profiles.list` row.
 *
 * [displayName] is core profile state (`display_name`, set by
 * `hermes profile rename`); [name] is the canonical key every other surface
 * routes by. [connectionId] is null on this app's single connection and stays
 * null until a second source exists — the roster key falls back to Desktop's
 * own `legacy` qualifier, which is what keeps the key shape stable when one
 * is added rather than re-keying every row.
 */
data class BotRosterRow(
    val name: String,
    val connectionId: String? = null,
    val connectionLabel: String? = null,
    val description: String = "",
    val displayName: String = "",
    val canonicalSession: BotSessionPreview? = null,
    val lastSession: BotSessionPreview? = null,
    /**
     * The profile's freshest kanban/tool worker session, when one exists.
     *
     * Workers never reach a conversation list, so this is the only signal that
     * a profile is grinding through a long task; see [workerActiveAt]. Null on
     * a Gateway that does not send it (`worker_session` arrives with
     * `include_sessions`, `tui_gateway/methods_profiles.py:216` @ the pin).
     */
    val workerSession: BotSessionPreview? = null,
    val hasAvatar: Boolean = false,
    /** In-memory permission from this accepted roster, not a reusable saved-row credential. */
    val avatarRef: com.hermesagent.mobile.data.profiles.ProfileAvatarRef? = null,
) {
    /** Source-qualified identity — the list key and the meta key. */
    val rosterKey: String get() = botRosterKey(this)

    /** The session whose activity best represents this bot; see [botActivitySession]. */
    val activity: BotSessionPreview? get() = botActivitySession(this)

    /** [activity] as a millisecond stamp, or null when the bot has never run. */
    val lastActiveMillis: Long?
        get() = activity?.lastActiveSeconds?.takeIf { it > 0L }?.times(1000L)

    val handle: String get() = botHandle(name)
}

/**
 * Per-bot presentation metadata. Local by design: Desktop's `$botMeta` is the
 * machine's own preference, and no field here is ever written back to the
 * Gateway.
 */
data class BotMeta(
    val pinned: Boolean = false,
    val hidden: Boolean = false,
    /** The user section this bot is filed under, or null for Unassigned. */
    val sectionId: String? = null,
)

/** A user-authored section — a folder the user makes, not one the topology makes. */
data class BotSection(val id: String, val name: String)

/**
 * One rendered block: a user section in display order, or the always-last
 * Unassigned bucket ([id] null, [key] `section:unassigned`).
 */
data class BotSectionBlock(
    val id: String?,
    val key: String,
    val name: String,
    val rows: List<BotRosterRow>,
)

/** The activity axis: Desktop's `RosterActivityFilter`. */
enum class RosterActivityFilter { All, Active, Recent, Older }

/** The kind axis: Desktop's `RosterKindFilter`. Groups arrive in a later slice. */
enum class RosterKindFilter { All, Bots, Groups }

/**
 * The windows and thresholds the derivation reads.
 *
 * Every one of these is Desktop's own value:
 * - `ACTIVE_WINDOW_S = 90` (`row-helpers.ts:66` @ the pin)
 * - `RECENT_ACTIVITY_WINDOW_S = 7 days` (`row-helpers.ts:67` @ the pin)
 * - `BOT_ROSTER_SEARCH_THRESHOLD = 8` (`row-helpers.ts:68` @ the pin)
 */
object BotsRosterLimits {
    const val ACTIVE_WINDOW_SECONDS: Long = 90L
    const val RECENT_ACTIVITY_WINDOW_SECONDS: Long = 7L * 24L * 60L * 60L
    const val BOT_ROSTER_SEARCH_THRESHOLD: Int = 8

    /**
     * Desktop's worker liveness window (`row-helpers.ts:76` @ the pin): wider
     * than [ACTIVE_WINDOW_SECONDS] because a live worker heartbeats at least
     * every 60 s, so this bridges one missed beat.
     */
    const val WORKER_ACTIVE_WINDOW_SECONDS: Long = 150L

    /** Desktop's Unassigned bucket key (`user-sections.ts:26` @ the pin). */
    const val UNASSIGNED_SECTION_KEY: String = "section:unassigned"

    /** Desktop truncates the stored attention message to 200 chars (`data.ts:139`). */
    const val ATTENTION_MESSAGE_LIMIT: Int = 200
}

/**
 * The roster's copy, verbatim from the *plugin bundle* — not core `en.ts`.
 *
 * Source: `apps/desktop/src/plugins/hermes-bots/i18n.ts:270-306` (roster) and
 * `:308-329` (sections) at `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`. The
 * interpolated forms keep their placeholders; the curly quotes are the source
 * characters, not a rendering choice.
 */
object BotsRosterCopy {
    const val SEARCH: String = "Search bots and group chats"
    const val SEARCH_PLACEHOLDER: String = "Search bots and group chats…"
    const val EMPTY_TITLE: String = "No bots yet"
    const val EMPTY_DESC: String = "Create your first bot."
    const val NO_MATCH_FILTERS: String = "No bots or group chats match these filters."
    const val CLEAR_FILTERS: String = "Clear filters"
    const val ALL_HIDDEN: String = "All bots are hidden"
    const val ALL_HIDDEN_DESC: String = "They keep working and retain their history."
    const val SHOW_HIDDEN: String = "Show hidden bots"
    const val NO_HIDDEN_MATCH: String = "No hidden bots match these filters."
    const val HIDDEN_FROM_ROSTER: String = "Hidden from the roster"
    const val PINNED: String = "Pinned"
    const val NEEDS_ATTENTION: String = "needs attention"
    const val BOTS_AND_GROUPS: String = "Bots and group chats"
    const val BOTS_ONLY: String = "Bots only"
    const val GROUPS_ONLY: String = "Group chats only"
    const val ANY_ACTIVITY: String = "Any activity"
    const val ACTIVE_NOW: String = "Active now"
    const val RECENTLY_ACTIVE: String = "Recently active"
    const val OLDER: String = "Older"
    const val RETRY_NOW: String = "Retry now"
    const val WAITING_FOR_GATEWAY: String =
        "Waiting for the gateway connection… (remote gateways can take a few seconds; retries automatically)"

    /** `sections.unassigned` (`i18n.ts:318` @ the pin). */
    const val UNASSIGNED: String = "Unassigned"

    /** `roster.noMatchQuery` (`i18n.ts:277` @ the pin). */
    fun noMatchQuery(query: String): String = "No bots or group chats match “$query”"

    /** `roster.rosterUnavailable` (`i18n.ts:303-304` @ the pin). */
    fun rosterUnavailable(reason: String): String =
        "Roster unavailable: $reason. If your gateway predates profiles.list, update Hermes and restart the gateway."

    /**
     * Desktop's stale banner (`roster-pane.tsx:396-399` @ the pin) — a literal
     * in the pane, not in `i18n.ts`, and kept character for character
     * including the space the concatenation leaves before "Waiting".
     *
     * Desktop adds the second half while its socket is closed, so the sentence
     * says which of the two situations the person is in.
     */
    fun refreshFailed(connectionUp: Boolean): String =
        REFRESH_FAILED + if (connectionUp) "" else WAITING_FOR_RECONNECT

    private const val REFRESH_FAILED = "Roster refresh failed — showing the last good list."
    private const val WAITING_FOR_RECONNECT = " Waiting for the gateway to reconnect…"
}
