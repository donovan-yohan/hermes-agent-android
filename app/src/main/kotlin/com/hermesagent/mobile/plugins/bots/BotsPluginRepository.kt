package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.plugins.PluginHost
import com.hermesagent.mobile.plugins.PluginHostResult
import com.hermesagent.mobile.data.profiles.AvatarRosterCoordinator
import com.hermesagent.mobile.data.profiles.ProfileAvatarRef
import com.hermesagent.mobile.data.profiles.avatarWireName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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

/**
 * The bot-scoped Routines read.
 *
 * The handler is `cron.manage` (`tui_gateway/methods_tools.py:1075-1085` @
 * `d177b119e9c56c9ddc0b7379ffce52341ec06584`), which forwards
 * `{action:"list", include_disabled:<bool>}` to `cronjob()` and honours an
 * optional `profile` by scoping the whole read to that profile's cron store,
 * echoing it back as `scoped`. The profile has to exist in the Gateway's own
 * registry — an unknown one answers JSON-RPC `4064 profile '<p>' not found`
 * (`methods_tools.py:56-60`) — so the name is sent verbatim from the roster row
 * that came off that same Gateway, never slugged or lower-cased on the way out.
 *
 * `enabled:true` is not something this app can ask for. It is a member of the
 * job's own record, and the Gateway derives `state` from it and passes an
 * unrecognised stored state through verbatim (`cron/jobs.py:525-538`), which is
 * why [parseRoutineJobs] reads a closed set of words and calls anything else
 * [RoutineRunState.Unknown] rather than rendering it.
 *
 * Reads never mutate, including legacy jobs. Existing-row writes use the
 * separate [BotsPluginRepository.mutateRoutine] door; creation and Desktop's
 * legacy auto-pause sweep (`cron.tsx:131-166`) remain outside this slice.
 */
sealed interface BotsRoutinesLoad {
    /** The Gateway answered with a list scoped to the requested bot. */
    data class Loaded(val jobs: List<RoutineRow>, val scoped: String?) : BotsRoutinesLoad

    /**
     * The Gateway answered, and the answer is not this bot's store: its
     * `scoped` echo names a different profile, so the rows belong to a bot the
     * person did not ask for. Refused rather than filtered — see
     * [selectRoutineJobs].
     */
    data class MismatchedScope(val requested: String) : BotsRoutinesLoad

    /** The Gateway answered `success:false` inside a successful envelope. */
    data object Rejected : BotsRoutinesLoad

    /** This Gateway build does not serve `cron.manage` (`-32601`). */
    data object UnavailableOnGateway : BotsRoutinesLoad

    /** The call reached the Gateway and did not produce a readable list. */
    data class Refused(val safeMessage: String) : BotsRoutinesLoad
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

/**
 * Why an open-or-create could not be confirmed.
 *
 * These are the four failures the wire can actually produce, and they are
 * deliberately distinct because the *person's next action is different for
 * each*: reconnect, update the Gateway, retry, or report an unreadable answer.
 * Collapsing them into one `Unsafe` is what made every start failure read as
 * "Check the Gateway and try again" — true for at most one of them, and
 * unactionable for the other three.
 *
 * The classification is made in [BotsPluginRepository] because that is the only
 * layer that still holds the evidence: `PluginHostResult.Refused` carries the
 * JSON-RPC code, and `UnavailableOnGateway` is the `-32601` method-not-found
 * signal. By the time a failure reaches the surface as a boolean, the wire
 * evidence is gone.
 */
enum class BotChatFailure {
    /**
     * Nothing answered: no live connection, so the request was never sent, or
     * the exchange timed out before the Gateway did.
     *
     * This is the door's own `code == 0` refusal — `RECONNECT_MESSAGE` and
     * `TIMED_OUT_MESSAGE` are its two sentences — and deliberately not
     * `PluginHostResult.Refused`'s `code == 0` branch read as "the Gateway
     * refused": nothing was refused, and the next action is to wait or retry
     * rather than to go looking at the Gateway's configuration.
     */
    NotAnswered,

    /** This Gateway build does not serve a method the open needs (`-32601`). */
    UnavailableOnGateway,

    /** The Gateway answered with a JSON-RPC error. */
    Refused,

    /** The Gateway answered, and this app could not read the answer. */
    Unreadable,
}

/** What one open-or-create attempt established for the tapped roster row. */
sealed interface BotChatOpen {
    /**
     * The registry now names exactly one exact-title row — the row that was
     * already there, or the one this attempt just created and titled.
     */
    data class Opened(val durableId: String) : BotChatOpen

    /**
     * Nothing could be confirmed. Fail closed: no navigation, no prompt, no
     * second mint. [failure] is the classified reason, which the surface turns
     * into one actionable sentence and a preserved retry.
     */
    data class Unsafe(val failure: BotChatFailure) : BotChatOpen
}

/** One canonical lookup, with its failure classified rather than erased. */
private sealed interface Lookup {
    data class Found(val durableId: String) : Lookup

    /** A well-formed successful answer that proves no canonical chat exists. */
    data object Missing : Lookup

    data class Unreadable(val failure: BotChatFailure) : Lookup
}

class BotsPluginRepository(
    private val host: PluginHost,
    private val avatarProducer: AvatarRosterCoordinator.Producer? = null,
) {

    internal fun invalidateAvatarRoster() { avatarProducer?.invalidate() }

    suspend fun loadRoster(): BotsRosterLoad {
        if (avatarProducer == null) return loadRosterWithoutAvatars()
        val read = avatarProducer.begin() ?: return BotsRosterLoad.Refused(UNREADABLE_ROSTER)
        return when (val result = read.request(includeSessions = true)) {
            is PluginHostResult.Success -> {
                if ((result.result as? JsonObject)?.get("profiles") !is JsonArray) return BotsRosterLoad.Refused(UNREADABLE_ROSTER)
                val refs = avatarProducer.accept(read, result.result) ?: return BotsRosterLoad.Refused(UNREADABLE_ROSTER)
                BotsRosterLoad.Loaded(checkNotNull(parseBotsRoster(result.result, refs)))
            }
            PluginHostResult.UnavailableOnGateway -> {
                avatarProducer.invalidate()
                BotsRosterLoad.UnavailableOnGateway
            }
            is PluginHostResult.Refused -> BotsRosterLoad.Refused(result.safeMessage)
        }
    }

    private suspend fun loadRosterWithoutAvatars(): BotsRosterLoad = when (
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

    /**
     * Read the routines scoped to [profile].
     *
     * `include_disabled` is always `true` and is asserted in the request
     * shape, not only in the parse: the Gateway hides paused jobs by default
     * (`tools/cronjob_tools.py:_action_list`, forward via
     * `methods_tools.py:1079-1083`), and a paused routine silently missing from
     * a read-only list reads to a person as a routine that was deleted.
     *
     * [expectedEndpointGeneration] binds the read to the Gateway the bot was
     * chosen on, exactly as the canonical-chat lookups do: the host re-validates
     * the endpoint at the dispatch itself, so a switch that lands mid-read
     * cannot render the replacement machine's jobs under this bot.
     */
    suspend fun loadRoutines(
        profile: String,
        expectedEndpointGeneration: Long,
    ): BotsRoutinesLoad = when (
        val result = host.requestAtEndpoint(
            expectedGeneration = expectedEndpointGeneration,
            method = CRON_MANAGE,
            params = buildJsonObject {
                put("action", JsonPrimitive(CRON_LIST))
                put("include_disabled", JsonPrimitive(true))
                put("profile", JsonPrimitive(profile))
            },
        )
    ) {
        is PluginHostResult.Success -> when (val parsed = parseRoutineJobs(result.result)) {
            is RoutineJobsParse.Answered ->
                if (routineScopeAgrees(parsed.scoped, profile)) {
                    BotsRoutinesLoad.Loaded(parsed.jobs, parsed.scoped)
                } else {
                    BotsRoutinesLoad.MismatchedScope(profile)
                }

            RoutineJobsParse.Rejected -> BotsRoutinesLoad.Rejected
            RoutineJobsParse.Unreadable -> BotsRoutinesLoad.Refused(UNREADABLE_ROUTINES)
        }

        PluginHostResult.UnavailableOnGateway -> BotsRoutinesLoad.UnavailableOnGateway

        is PluginHostResult.Refused -> BotsRoutinesLoad.Refused(result.safeMessage)
    }

    /**
     * Existing-row mutations, bound to the endpoint that served the owner.
     * Wire: tui_gateway/methods_tools.py:1097-1098 at
     * d177b119e9c56c9ddc0b7379ffce52341ec06584. No retry after uncertainty.
     */
    suspend fun mutateRoutine(target: RoutineTarget, action: RoutineAction): Boolean {
        val response = host.requestAtEndpoint(
            expectedGeneration = target.endpoint,
            method = CRON_MANAGE,
            params = buildJsonObject {
                put("action", action.wire)
                put("name", target.jobId)
                put("profile", target.owner)
            },
        )
        val result = (response as? PluginHostResult.Success)?.result as? JsonObject
        return result?.literalBoolean("success") == true
    }

    /** Hidden canonical chats bypass SessionCache and are resolved by exact title. */
    suspend fun findCanonicalChat(
        profile: String,
        rosterCanonicalId: String?,
        expectedEndpointGeneration: Long? = null,
    ): BotChatLookup = when (val lookup = lookupCanonicalChat(profile, rosterCanonicalId, expectedEndpointGeneration)) {
        is Lookup.Found -> BotChatLookup.Found(lookup.durableId)
        is Lookup.Unreadable -> BotChatLookup.Unsafe
        Lookup.Missing -> BotChatLookup.Missing
    }

    /**
     * The canonical lookup with its failure classified.
     *
     * [findCanonicalChat] is the adapter that keeps the three-way answer
     * [BotsPluginRepository.findCanonicalChat]'s callers have always branched
     * on; this is where the *reason* survives, which is what an open's failure
     * report needs — see [BotChatFailure].
     */
    private suspend fun lookupCanonicalChat(
        profile: String,
        rosterCanonicalId: String?,
        expectedEndpointGeneration: Long?,
    ): Lookup {
        val params = buildJsonObject {
            put("profile", JsonPrimitive(profile))
            put("title", JsonPrimitive(CANONICAL_CHAT_TITLE))
            put("limit", JsonPrimitive(CANONICAL_LOOKUP_LIMIT))
            put("include_hidden", JsonPrimitive(true))
        }
        val result = expectedEndpointGeneration?.let { endpoint ->
            host.requestAtEndpoint(endpoint, SESSION_LIST, params)
        } ?: host.request(SESSION_LIST, params)
        if (result !is PluginHostResult.Success) {
            return Lookup.Unreadable(result.failure())
        }
        val sessions = (result.result as? JsonObject)?.get("sessions") as? JsonArray
            ?: return Lookup.Unreadable(BotChatFailure.Unreadable)
        if (sessions.isEmpty()) {
            return if (rosterCanonicalId.isNullOrBlank()) {
                Lookup.Missing
            } else {
                Lookup.Unreadable(BotChatFailure.Unreadable)
            }
        }
        // `title` makes this a constrained lookup, not a ranking request. A
        // surprising extra or malformed row therefore means the response no
        // longer proves which hidden chat is canonical; never pick arbitrarily.
        val exact = sessions.singleOrNull() as? JsonObject
            ?: return Lookup.Unreadable(BotChatFailure.Unreadable)
        if (exact.string("title") != CANONICAL_CHAT_TITLE) {
            return Lookup.Unreadable(BotChatFailure.Unreadable)
        }
        val id = exact.string("resolved_id")?.trim()?.takeIf(String::isNotEmpty)
            ?: exact.string("id")?.trim()?.takeIf(String::isNotEmpty)
            ?: return Lookup.Unreadable(BotChatFailure.Unreadable)
        return Lookup.Found(id)
    }

    /**
     * Phase B: the bot's one forever-chat, created only from a registry that
     * twice confirmed none exists.
     *
     * The Desktop path this mirrors is `openBotCanonicalChat`
     * (`apps/desktop/src/plugins/hermes-bots/canonical-chat.ts:485-519` @
     * `564aef2946c436500a5e80ee117b66b789b3f99a`), which is a composition of
     * `createCanonicalChat` (`canonical-chat.ts:290-475` @
     * `564aef2946c436500a5e80ee117b66b789b3f99a`). Its order is kept:
     *
     * 1. Consult the registry. A row opens as-is; an unreadable answer fails
     *    closed without touching `session.create`.
     * 2. Adopt before minting (`canonical-chat.ts:335-346` @
     *    `564aef2946c436500a5e80ee117b66b789b3f99a`): the lookup runs a second
     *    time, so a chat created by another surface between the tap and the
     *    create is opened rather than forked.
     * 3. Create it titled, hidden and profile-following, then write the title
     *    eagerly so the row exists before anything is opened or sent.
     * 4. If that title write did not land, re-read the registry and adopt the
     *    exact-title row a concurrent writer won (`canonical-chat.ts:387-410` @
     *    `564aef2946c436500a5e80ee117b66b789b3f99a`). No winner means the attempt
     *    is abandoned — the stray lazy session holds no messages and the gateway
     *    prunes it — never a second titled chat.
     *
     * Those citations name the revision the file was ported from, which is
     * **not** this app's theme/theme-adjacent pin: the canonical-chat construct
     * was read at `564aef2946c436500a5e80ee117b66b789b3f99a`, and re-pointing
     * them at a newer revision would claim a provenance the spans do not have.
     *
     * Deliberately absent, and ledgered in `docs/parity/bot-chat.md`: Desktop's
     * kickoff intro. `createCanonicalChat` submits it only on New Agent
     * creation (`kickoff`) or as a legacy-gateway persistence fallback; the
     * gateway materializes the row through the eager title write instead, so
     * opening a chat stays inert and the person's first message is the one that
     * arms live delivery.
     *
     * [expectedEndpointGeneration] binds every call to the Gateway the roster
     * row came from. The host snapshots the client under that generation, so a
     * switch cannot redirect any create or title mutation to the replacement.
     */
    suspend fun openCanonicalChat(
        profile: String,
        rosterCanonicalId: String?,
        expectedEndpointGeneration: Long = host.endpointGeneration.value,
    ): BotChatOpen {
        when (val first = lookupCanonicalChat(profile, rosterCanonicalId, expectedEndpointGeneration)) {
            is Lookup.Found -> return BotChatOpen.Opened(first.durableId)
            is Lookup.Unreadable -> return BotChatOpen.Unsafe(first.failure)
            Lookup.Missing -> Unit
        }
        when (val concurrent = lookupCanonicalChat(profile, rosterCanonicalId, expectedEndpointGeneration)) {
            is Lookup.Found -> return BotChatOpen.Opened(concurrent.durableId)
            is Lookup.Unreadable -> return BotChatOpen.Unsafe(concurrent.failure)
            Lookup.Missing -> Unit
        }
        val created = createCanonicalChat(profile, expectedEndpointGeneration)
        return when (created) {
            is Created.Confirmed -> BotChatOpen.Opened(created.durableId)
            is Created.Failed -> BotChatOpen.Unsafe(created.failure)
        }
    }

    /**
     * One `PluginHostResult`'s failure, classified.
     *
     * `UnavailableOnGateway` is its own reason and never a generic refusal: it
     * is the `-32601` method-not-found answer, which retrying can never fix and
     * an update might, so the surface must not tell the person to retry.
     *
     * Code zero also represents a Gateway error without a numeric code. Only
     * the door's known local connection/timeout sentences identify an unanswered
     * request; unknown refusals retain the generic refusal instead.
     */
    private fun PluginHostResult.failure(): BotChatFailure = when {
        this is PluginHostResult.Success -> BotChatFailure.Unreadable
        this === PluginHostResult.UnavailableOnGateway -> BotChatFailure.UnavailableOnGateway
        this is PluginHostResult.Refused ->
            if (code == 0 && (safeMessage == "Reconnect to the Gateway and try again." ||
                    safeMessage == "The Gateway did not answer in time.")) {
                BotChatFailure.NotAnswered
            } else {
                BotChatFailure.Refused
            }
        // Unreachable while the sealed hierarchy is three-wide; stated so a
        // fourth result added later fails to "unreadable", never to "refused".
        else -> BotChatFailure.Unreadable
    }

    /** What one create-and-title attempt established. */
    private sealed interface Created {
        data class Confirmed(val durableId: String) : Created

        data class Failed(val failure: BotChatFailure) : Created
    }

    /**
     * Create the bot's canonical chat, then make its identity durable.
     *
     * `session.create` is lazy on the pinned gateway — its row appears on the
     * first prompt or on this title write
     * (`tui_gateway/methods_session.py:325-390` @
     * `564aef2946c436500a5e80ee117b66b789b3f99a`) — so the eager `session.title`
     * is what closes the untitled window a second tap could mint through
     * (`canonical-chat.ts:368-412` @
     * `564aef2946c436500a5e80ee117b66b789b3f99a`).
     *
     * The request is Desktop's exactly, `source` included in its absence: the
     * bot-chat create does not send one (`canonical-chat.ts:348-363` @
     * `564aef2946c436500a5e80ee117b66b789b3f99a`), so the gateway resolves it
     * from its own environment. This app's own `createSession` sends `"desktop"`,
     * and that difference is deliberate — `source` decides `track_liveness` and
     * the desktop-only cleanup lifecycle
     * (`tui_gateway/session_lifecycle.py:37` @
     * `564aef2946c436500a5e80ee117b66b789b3f99a`), and a canonical chat is not
     * this app's ordinary session.
     *
     * Every citation above names `564aef2946c436500a5e80ee117b66b789b3f99a`,
     * the revision they were read at, rather than the pin this app's own
     * comment header names: a span is a claim about one revision, and moving
     * one stamp does not move the others.
     *
     * Returns the confirmed durable id, or the classified reason the chat's
     * identity could not be confirmed.
     */
    private suspend fun createCanonicalChat(profile: String, expectedEndpointGeneration: Long): Created {
        val created = host.requestAtEndpoint(
            expectedGeneration = expectedEndpointGeneration,
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
        // Ids are JSON strings on this wire; a number or boolean is a
        // malformed answer, not an id to coerce into one.
        val result = (created as? PluginHostResult.Success)?.result as? JsonObject
        val storedId = result?.string("stored_session_id")?.trim()?.takeIf(String::isNotEmpty)
        val runtimeId = result?.string("session_id")?.trim()?.takeIf(String::isNotEmpty)
        if (created !is PluginHostResult.Success || storedId == null || runtimeId == null) {
            // A create the Gateway refused or never answered keeps *its* reason;
            // a successful answer whose ids are missing or malformed is an
            // unreadable answer, not a refusal.
            return Created.Failed(
                if (created is PluginHostResult.Success) {
                    BotChatFailure.Unreadable
                } else {
                    created.failure()
                },
            )
        }

        val titled = host.requestAtEndpoint(
            expectedGeneration = expectedEndpointGeneration,
            method = SESSION_TITLE,
            params = buildJsonObject {
                put("session_id", JsonPrimitive(runtimeId))
                put("title", JsonPrimitive(CANONICAL_CHAT_TITLE))
            },
        )
        val titleResult = (titled as? PluginHostResult.Success)?.result as? JsonObject
        // `pending:false` is the Gateway's explicit durability receipt. With
        // `pending:true`, row creation did not take and only the runtime holds
        // a deferred title; opening it would revive the duplicate-mint window.
        // Only a literal JSON boolean is a receipt: an absent member, a string
        // or a number is a malformed answer that proves nothing about
        // durability, so it takes the same route as `pending:true` and is
        // reconciled against the registry rather than adopted as canonical.
        if (
            titleResult?.literalBoolean("pending") == false &&
            titleResult.string("title") == CANONICAL_CHAT_TITLE
        ) {
            return Created.Confirmed(storedId)
        }

        // The title write did not land. Only the registry can say whether a
        // concurrent writer took the canonical title (adopt its row) or the
        // write could not be made at all (abandon the attempt).
        val winner = lookupCanonicalChat(profile, null, expectedEndpointGeneration)
        return when (winner) {
            is Lookup.Found -> Created.Confirmed(winner.durableId)
            // A registry that could not be read is its own reason — the
            // attempt abandoned for want of evidence, not a Gateway refusal.
            is Lookup.Unreadable -> Created.Failed(winner.failure)
            // No winner and a readable registry: the title write did not take
            // and no concurrent writer holds it. The failure to report is the
            // title write's own, when it had one.
            Lookup.Missing -> Created.Failed(
                if (titled is PluginHostResult.Success) {
                    BotChatFailure.Unreadable
                } else {
                    titled.failure()
                },
            )
        }
    }

    private companion object {
        const val PROFILES_LIST = "profiles.list"
        const val SESSION_LIST = "session.list"
        const val SESSION_CREATE = "session.create"
        const val SESSION_TITLE = "session.title"
        const val CRON_MANAGE = "cron.manage"
        const val CRON_LIST = "list"
        const val CANONICAL_CHAT_TITLE = "Bot Chat"
        const val CANONICAL_LOOKUP_LIMIT = 200

        /** This app's sentence; the backend's own text is never shown. */
        const val UNREADABLE_ROSTER = "The Gateway sent a roster this app could not read."

        /** This app's sentence for a `cron.manage` answer it cannot read. */
        const val UNREADABLE_ROUTINES = "The Gateway sent a routine list this app could not read."
    }
}

/**
 * Parse a `profiles.list` answer into roster rows.
 *
 * A row without a usable `name` is dropped rather than invented, and a
 * malformed envelope answers null so the caller keeps its last good roster —
 * the same contract as `parseProfileList` in `data/profiles`.
 *
 * Row fields (`methods_profiles.py:245-250` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`): `name`, `path`,
 * `is_default`, `model`, `provider`, `description`, `display_name`,
 * `skill_count`, plus `last_session` / `worker_session` / `canonical_session` /
 * `ui_meta` / `has_avatar` when `include_sessions` is on.
 */
fun parseBotsRoster(
    result: JsonElement,
    avatars: Map<String, ProfileAvatarRef> = emptyMap(),
): List<BotRosterRow>? {
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
            avatarRef = avatarWireName(row)?.let(avatars::get),
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
        id = row.string("id")?.trim()?.takeIf(String::isNotEmpty),
        resolvedId = row.string("resolved_id")?.trim()?.takeIf(String::isNotEmpty),
        lastActiveSeconds = row.epochSeconds("last_active"),
        preview = row.text("preview"),
    )
}

/**
 * An epoch-seconds stamp, read off the wire as the Gateway actually sends it.
 *
 * The Gateway hands these out straight from SQLite, where the columns are
 * `REAL` (`hermes_state_common.py:319` @ `564aef2946c436500a5e80ee117b66b789b3f99a`: `last_activity_at REAL`,
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

/**
 * A member the contract types as a JSON string.
 *
 * [text] coerces a number or a boolean into its content, which is right for
 * prose fields and wrong for the ids and titles this file adopts as backend
 * identity: a coerced `7` is an id no Gateway minted, and a coerced title is a
 * row this app did not find. `JsonNull` is a `JsonPrimitive` that reports
 * `isString`, so it is excluded by name.
 */
private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/**
 * A literal JSON boolean — the shape of `session.title`'s `pending` receipt —
 * and null for an absent member or any other primitive.
 *
 * [flag] cannot state this contract: it reads an absent key, a string and a
 * number as false, and `false` is exactly the value that turns a malformed
 * answer into a durability claim.
 */
private fun JsonObject.literalBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.flag(name: String): Boolean = when (val value = this[name]) {
    is JsonPrimitive -> value.content.equals("true", ignoreCase = true) || value.content == "1"
    else -> false
}
