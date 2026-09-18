package com.hermesagent.mobile.plugins.bots

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.OffsetDateTime

/**
 * The bot-scoped Routines read model: the `cron.manage {action:"list"}` row as
 * this app parses it, the derivation Desktop makes from it, and the copy the
 * surface renders.
 *
 * Everything here is a port of `apps/desktop/src/plugins/hermes-bots/cron.tsx`
 * and its own test (`cron-jobs-view.test.ts`, `cron-owner.test.tsx`) at
 * `d177b119e9c56c9ddc0b7379ffce52341ec06584`, read through the Gateway handler
 * the request actually reaches (`tui_gateway/methods_tools.py:1075-1099`) and
 * the row formatter behind it (`tools/cronjob_job_args.py:351-399`).
 *
 * Three facts about the wire decide this file, and each one is why a rule here
 * looks redundant:
 *
 * - The row's identity member is `job_id`, and it is the *only* member the
 *   contract requires (`types.ts:264-283`). A row without a usable one is
 *   dropped rather than rendered from a guess.
 * - "Paused" is claimed on evidence, not on a single member: `enabled == false`
 *   *or* `state == "paused"` (Desktop's own `serverActive`,
 *   `cron.tsx:489`/`cron-detail.test.tsx:74-78`). The pinned Gateway derives
 *   `state` from `enabled` and so never emits `{enabled:true, state:"paused"}`
 *   itself (`cron/jobs.py:525-538`) — but if such a row arrives from an older
 *   Gateway, a stale read or another writer, the row is rendered paused, and
 *   the surface does not claim a routine is running on evidence that
 *   contradicts itself.
 * - `schedule` is a plain display string, not a structured schedule
 *   (`types.ts:268-270`), so the only labels this app can honestly derive are
 *   the ones Desktop derives from it in [routineScheduleLabel] — anything else
 *   is shown as the Gateway's own string.
 */

/**
 * Desktop's routine tag: a job is namespaced `[bot:slug] <routine>` so it can
 * be scoped back to its bot (`cron.tsx:73`). Case-insensitive, per Desktop.
 */
private val BOT_TAG = Regex("^\\[bot:([a-z0-9][a-z0-9_-]*)\\]\\s*", RegexOption.IGNORE_CASE)

/**
 * The prompt prefix that marks a routine delegated to another profile — the
 * `hermes -p <bot> chat` wrapper Desktop writes for a job owned by a profile
 * other than the one the routine was created in (`cron.tsx:74-75`,
 * `:270-286`). A job carrying it runs *another* profile's chat on the
 * Gateway's own terms; Desktop refuses to toggle such a job from Bot Mode
 * (`cron.tsx:488-496`).
 */
private const val LEGACY_DELEGATED_ROUTINE_PREFIX = "You are running the scheduled routine \""

/**
 * What a routine's row says about itself, derived from `state` + `enabled`.
 *
 * It is deliberately not the raw wire string. `effective_job_state` passes an
 * unrecognised stored `state` through verbatim (`cron/jobs.py:537-538`), so
 * the wire is an open set and a surface that echoed it would print backend
 * vocabulary — the one thing this slice must never render. [Unknown] renders
 * no state word at all rather than inventing one.
 */
enum class RoutineRunState {
    /** It has a next run. */
    Scheduled,

    /** `enabled: false`, or the Gateway's own `paused`. */
    Paused,

    /** Terminal: a `repeat`-limited job that ran out, or a one-shot that fired. */
    Completed,

    /** The last fire failed; a recurring job in this state still has a next run. */
    Failed,

    /**
     * A `state` word this app does not know.
     *
     * The wire is an open set — `effective_job_state` passes an unrecognised
     * stored state through verbatim (`cron/jobs.py:537-538`) — so this is a
     * real answer, not a bug. It renders **no state word and no running dot**:
     * [RoutineRow.active] is false because nothing here licenses a promise that
     * the routine fires again, and the row's label is absent because nothing
     * here licenses saying it is paused either.
     */
    Unknown,
}

/**
 * One routine as the surface reads it.
 *
 * [title], [scheduleLabel], [repeat] and the relative next-run label are the
 * only things this app ever *renders*; [nextRunMillis] is the parsed instant
 * behind that label, kept so a test can pin the arithmetic without a
 * formatter.
 *
 * Nothing in here can hold backend prose. The failure, pause-reason and prompt
 * members the row carries are read for exactly one purpose — recognising
 * Desktop's legacy delegation wrapper ([legacyDelegated]) — and never kept,
 * so no surface downstream can render them by accident.
 */
data class RoutineRow(
    /** `job_id` — the row's durable identity. */
    val id: String,
    /** `name` with the `[bot:slug]` tag stripped, or Desktop's fallback. */
    val title: String,
    /** The schedule, as one short label; never backend prose. */
    val scheduleLabel: String,
    /** `repeat` as the Gateway's own display string (`forever`, `3 times`, …). */
    val repeat: String?,
    /**
     * Whether this routine will fire again — the one derived flag the row's
     * status dot paints from.
     *
     * True for [RoutineRunState.Scheduled] and [RoutineRunState.Failed] (a
     * failed recurring job still has its next occurrence), false for
     * [RoutineRunState.Paused], [RoutineRunState.Completed], and
     * [RoutineRunState.Unknown]. Unknown is deliberately *not* active: an
     * unrecognised state word is not evidence that the routine runs again, and
     * a surface must not paint a running dot on a job it cannot describe.
     */
    val active: Boolean,
    val state: RoutineRunState,
    /** `next_run_at` as an epoch instant, or null when absent/unreadable. */
    val nextRunMillis: Long?,
    /**
     * The bot this job is tagged for, lower-cased, or null when it carries no
     * `[bot:slug]` tag at all. Kept because the fallback filter runs on it.
     */
    val taggedBot: String?,
    /** True when the prompt carries Desktop's other-profile delegation wrapper. */
    val legacyDelegated: Boolean,
)

/**
 * The two things a `cron.manage {action:"list"}` answer can be wrong about.
 *
 * `success` is a member of the *tool* result, not of the JSON-RPC envelope
 * (`tui_gateway/methods_tools.py:1077-1085` forwards `cronjob()`'s own JSON),
 * so `success:false` arrives inside a perfectly good envelope and means the
 * Gateway answered "no" — a rejection, not a transport failure.
 */
sealed interface RoutineJobsParse {
    /** A readable list: [jobs] plus the profile the store was scoped to. */
    data class Answered(val jobs: List<RoutineRow>, val scoped: String?) : RoutineJobsParse

    /** The Gateway answered `success:false`, inside a successful envelope. */
    data object Rejected : RoutineJobsParse

    /** The answer is not a shape this app can read as a job list. */
    data object Unreadable : RoutineJobsParse
}

/**
 * Parse a `cron.manage {action:"list"}` result.
 *
 * Total by construction: a row without a string `job_id` is dropped rather
 * than crashing or being invented ([Answered] with fewer rows), an absent
 * `jobs` is an empty list, and a `jobs` that is not an array is
 * [RoutineJobsParse.Unreadable] rather than an empty success — a client that
 * read it as "no jobs" would report the *opposite* of what the Gateway said.
 */
fun parseRoutineJobs(result: JsonElement): RoutineJobsParse {
    val root = result as? JsonObject ?: return RoutineJobsParse.Unreadable
    if ((root["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == false) {
        return RoutineJobsParse.Rejected
    }
    val jobs = root["jobs"] ?: return RoutineJobsParse.Answered(emptyList(), root.profileEcho())
    val array = jobs as? JsonArray ?: return RoutineJobsParse.Unreadable
    return RoutineJobsParse.Answered(
        jobs = array.mapNotNull { element -> (element as? JsonObject)?.let(::parseRoutineJob) },
        scoped = root.profileEcho(),
    )
}

/** The profile the Gateway scoped the store to, or null when it echoed none. */
private fun JsonObject.profileEcho(): String? =
    routineJsonString("scoped")?.trim()?.takeIf(String::isNotEmpty)

private fun parseRoutineJob(row: JsonObject): RoutineRow? {
    // `job_id` is the contract's only required member, and it is an id: a
    // number or a boolean coerced into one would be an id no Gateway minted.
    val id = row.routineJsonString("job_id")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val name = row.routineJsonString("name").orEmpty()
    val rawState = row.routineJsonString("state").orEmpty().trim().lowercase()
    val enabled = row.routineLiteralBoolean("enabled")
    val paused = enabled == false || rawState == PAUSED_STATE
    val state = when {
        paused -> RoutineRunState.Paused
        rawState == COMPLETED_STATE -> RoutineRunState.Completed
        rawState == ERROR_STATE -> RoutineRunState.Failed
        rawState == SCHEDULED_STATE || rawState == RUNNING_STATE || rawState.isEmpty() ->
            RoutineRunState.Scheduled
        else -> RoutineRunState.Unknown
    }
    val preview = row.routineJsonString("prompt_preview") ?: row.routineJsonString("prompt")

    return RoutineRow(
        id = id,
        title = routineTitle(name),
        scheduleLabel = routineScheduleLabel(row.routineJsonString("schedule")),
        repeat = row.routineJsonString("repeat")?.trim()?.takeIf(String::isNotEmpty),
        // Both paused signals are evidence, and either one is enough: Desktop's
        // `serverActive` is `enabled !== false && state !== 'paused'`
        // (`cron.tsx:489`, pinned by `cron-detail.test.tsx:74-78`). A completed
        // routine does not fire again; a failed but still-enabled one does.
        active = state != RoutineRunState.Paused &&
            state != RoutineRunState.Completed &&
            state != RoutineRunState.Unknown,
        state = state,
        nextRunMillis = row.routineJsonString("next_run_at")?.let(::routineInstant),
        taggedBot = routineBot(name),
        legacyDelegated = routineBot(name) != null &&
            preview != null &&
            preview.startsWith(LEGACY_DELEGATED_ROUTINE_PREFIX),
    )
}

/** The bot a job's name is tagged for, lower-cased, or null for an untagged job. */
fun routineBot(name: String?): String? =
    BOT_TAG.find(name.orEmpty())?.groupValues?.get(1)?.lowercase()

/**
 * A routine's title: the name with the tag stripped, or Desktop's own fallback
 * (`cron.tsx:93-95`). An empty name and a name that was *only* a tag both land
 * on the same word, which is what Desktop does with both.
 */
fun routineTitle(name: String?): String =
    name.orEmpty().replaceFirst(BOT_TAG, "") .ifEmpty { UNTITLED_JOB }

/**
 * Desktop's schedule label (`cron.tsx:288-324`), ported whole.
 *
 * It humanises only the two shapes it recognises — `once in …`/`30m`, and
 * `every <n>m` — and returns the Gateway's own string for everything else,
 * which is the one place raw Gateway text is rendered. It has to be: `schedule`
 * is a display string on this wire (`types.ts:268-270`), so a client that
 * refused to show what it could not parse would show nothing at all for a
 * 5-field cron expression. It is a schedule, never prose, and Desktop's own
 * inspector keeps the raw string beside the label for the same reason
 * (`cron.tsx:380-382`).
 */
fun routineScheduleLabel(schedule: String?): String {
    val raw = schedule.orEmpty()
    val once = ONCE_IN.find(raw)
    if (once != null) return BotsRoutinesCopy.onceIn(once.groupValues[1])
    val bare = BARE_ONCE.find(raw)
    if (bare != null) return BotsRoutinesCopy.onceIn(bare.groupValues[1] + bare.groupValues[2])
    val everyMinutes = EVERY_MINUTES.find(raw) ?: return raw
    val minutes = everyMinutes.groupValues[1].toIntOrNull() ?: return raw
    if (minutes <= 0) return raw
    if (minutes % MINUTES_PER_DAY == 0) {
        val days = minutes / MINUTES_PER_DAY
        return if (days == 1) BotsRoutinesCopy.DAILY else BotsRoutinesCopy.everyNDays(days)
    }
    if (minutes % MINUTES_PER_HOUR == 0) {
        val hours = minutes / MINUTES_PER_HOUR
        return if (hours == 1) BotsRoutinesCopy.HOURLY else BotsRoutinesCopy.everyNHours(hours)
    }
    return BotsRoutinesCopy.everyNMinutes(minutes)
}

/**
 * An ISO-8601 instant off the wire as an epoch instant, or null.
 *
 * `next_run_at` is `datetime.isoformat()` on the Gateway
 * (`cron/jobs.py:1155`), which always carries an offset, so the parse is
 * offset-anchored and never guesses a zone. A shape this cannot read leaves the
 * label absent rather than anchored to the device's own clock.
 */
private fun routineInstant(value: String): Long? =
    runCatching { OffsetDateTime.parse(value.trim()).toInstant().toEpochMilli() }.getOrNull()

/**
 * Which routines this surface shows.
 *
 * The whole of Desktop's `selectRoutineJobs` (`cron.tsx:215-230`) and the rule
 * its own test pins. The `scoped` echo is the Gateway's receipt that the
 * `profile` parameter was honoured; when it agrees with the requested bot the
 * reply *is* that bot's store, so untagged jobs in it are that bot's own. When
 * it is absent, the Gateway predates profile scoping and the reply is the
 * launch profile's store, so the `[bot:slug]` tag is the only honest filter.
 *
 * The third case is the one this app *refuses*: a `scoped` echo naming a
 * different profile. Desktop falls back to tag filtering there, which is safe
 * on Desktop because both profiles are machines that Desktop is in — but on
 * this app the answer is about a profile the person did not ask for, so the
 * caller treats the mismatch as an unreadable answer and shows the read failure
 * with Retry instead of the store's contents. Ledgered as drift in
 * `docs/parity/bot-routines.md`.
 */
fun selectRoutineJobs(jobs: List<RoutineRow>, scoped: String?, bot: String): List<RoutineRow> {
    if (normalizedProfileName(scoped) == normalizedProfileName(bot)) return jobs
    return jobs.filter { (it.taggedBot ?: DEFAULT_PROFILE) == normalizedProfileName(bot) }
}

/**
 * Whether the Gateway scoped this answer to the bot that was asked for.
 *
 * Not the same question as [selectRoutineJobs]'s first branch, and deliberately
 * a separate function: absence of a `scoped` echo is a readable legacy answer,
 * while an echo naming *someone else* is not a readable answer at all.
 */
fun routineScopeAgrees(scoped: String?, bot: String): Boolean =
    scoped == null || normalizedProfileName(scoped) == normalizedProfileName(bot)

/** Desktop's profile comparison: trimmed, lower-cased, nothing else (`cron.tsx:250-252`). */
fun normalizedProfileName(profile: String?): String = profile.orEmpty().trim().lowercase()

/**
 * Desktop's explanation for an empty pane over a non-empty store
 * (`cron.tsx:242-248`): jobs exist on the profile, but none are tagged for this
 * bot, and the generic empty state would otherwise deny they exist.
 */
fun routineFilterHint(all: List<RoutineRow>, visible: List<RoutineRow>): String? =
    if (visible.isEmpty() && all.isNotEmpty()) BotsRoutinesCopy.FILTER_HINT else null

/**
 * Desktop's relative next-run stamp, in Desktop's own words
 * (`cron.tsx:328-332` with `apps/desktop/src/lib/time.ts:38-56`).
 *
 * `relativeTime` is `Intl.RelativeTimeFormat(style: 'short')`, whose English
 * output is `in 5 min` / `in 2 hr` / `in 3 days`: the coarsest unit that
 * describes the distance, rounded half-up, with `sec`/`min`/`hr` abbreviated
 * and `day` spelled out. The words are in [BotsRoutinesCopy]; a negative
 * distance is impossible here (a past `next_run_at` is the Gateway's next
 * recompute, not a claim this surface makes), and the absolute-difference
 * rounding is why one rule covers both signs.
 */
fun routineRelativeLabel(atMillis: Long, nowMillis: Long): String {
    val diff = atMillis - nowMillis
    val abs = kotlin.math.abs(diff)
    // `in 5 min` / `30 sec ago`: `Intl.RelativeTimeFormat`'s English short form
    // puts the direction word on the side the sign says.
    val (value, unit) = when {
        abs < MILLIS_PER_MINUTE -> rounded(abs, MILLIS_PER_SECOND) to BotsRoutinesCopy.UNIT_SECONDS
        abs < MILLIS_PER_HOUR -> rounded(abs, MILLIS_PER_MINUTE) to BotsRoutinesCopy.UNIT_MINUTES
        abs < MILLIS_PER_DAY -> rounded(abs, MILLIS_PER_HOUR) to BotsRoutinesCopy.UNIT_HOURS
        else -> rounded(abs, MILLIS_PER_DAY) to BotsRoutinesCopy.dayUnit(rounded(abs, MILLIS_PER_DAY))
    }
    return if (diff < 0) {
        "$value $unit ${BotsRoutinesCopy.AGO_SUFFIX}"
    } else {
        "${BotsRoutinesCopy.IN_PREFIX} $value $unit"
    }
}

/** `Intl` rounds .5 away from zero; the sign is already handled by the caller. */
private fun rounded(abs: Long, unit: Long): Long = (abs + unit / 2) / unit

private const val UNTITLED_JOB = "Untitled job"
private const val DEFAULT_PROFILE = "default"
private const val PAUSED_STATE = "paused"
private const val COMPLETED_STATE = "completed"
private const val ERROR_STATE = "error"
private const val SCHEDULED_STATE = "scheduled"
private const val RUNNING_STATE = "running"

private val ONCE_IN = Regex("^once in (.+)$")
private val BARE_ONCE = Regex("^(\\d+)([mhd])$")
private val EVERY_MINUTES = Regex("^every (\\d+)m$")
private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 1440

private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val MILLIS_PER_HOUR = 3_600_000L
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * A member the contract types as a JSON string.
 *
 * A number or a boolean coerced into text is wrong for every field this file
 * reads: an id, a state word, a schedule and a preview are all strings on this
 * wire, and a coerced `7` is a value the Gateway never sent. `JsonNull` is a
 * `JsonPrimitive` that reports `isString`, so it is excluded by name.
 */
private fun JsonObject.routineJsonString(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/**
 * A literal JSON boolean, or null for an absent member or any other primitive.
 *
 * `enabled` decides whether a row reads as paused, so `"false"` and `0` must
 * not be read as the value `false`: a malformed member proves nothing about the
 * job, and the row's own `state` is then the only evidence left.
 */
private fun JsonObject.routineLiteralBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/**
 * The Routines surface's copy.
 *
 * Sources, at `d177b119e9c56c9ddc0b7379ffce52341ec06584`:
 *
 * - the *plugin bundle* `apps/desktop/src/plugins/hermes-bots/i18n.ts:513-551`
 *   for the cron block's own sentences ([FILTER_HINT], [NEEDS_ROSTER_FIRST],
 *   [STALE_NOTICE], [READ_FAILURE], the schedule-label forms);
 * - core `apps/desktop/src/i18n/en.ts:2545-2645` for the words the pane takes
 *   from core, exactly as Desktop does: `title`, `emptyTitleNew`,
 *   `emptyDescNew`, `newCron`, `failedLoad`, `states.*`, `scheduleLabels.*`;
 * - `t.common.retry` (`en.ts:143`) for the retry control, which this app
 *   already renders as `Retry now` on the roster — the roster's own string is
 *   reused here rather than a second word for one control.
 */
object BotsRoutinesCopy {
    /** `cron.title` (core `en.ts:2545`) — the pane's own noun, so the surface, the tab and the entry point agree. */
    const val TITLE: String = "Scheduled jobs"

    /** `cron.filterHint` (`i18n.ts:514-515`). */
    const val FILTER_HINT: String =
        "Scheduled jobs exist in this profile but none are tagged for this bot. " +
            "Name a job \"[bot:<name>] …\" to show it here, or see them in Cron below."

    /** `cron.needsRosterFirst` (`i18n.ts:516`). */
    const val NEEDS_ROSTER_FIRST: String = "This bot has to appear in the roster first."

    /** `cron.staleNotice` (`i18n.ts:517`). */
    const val STALE_NOTICE: String = "Could not refresh scheduled jobs. Showing the last list we had."

    /** `cron.readFailure` (`i18n.ts:518`). */
    const val READ_FAILURE: String = "The list may still be there — this was a read failure, not a delete."

    /** `cron.failedLoad` (core `en.ts:2645`). */
    const val FAILED_LOAD: String = "Failed to load cron jobs"

    /**
     * This Gateway does not serve `cron.manage` at all.
     *
     * Desktop has no analogue — every Desktop talks to a Gateway that has this
     * method — so this follows the app's existing Gateway-predates sentence
     * shape on the roster rather than inventing a new one.
     */
    const val UNAVAILABLE: String =
        "Scheduled jobs unavailable: this Gateway does not serve cron.manage. " +
            "Update Hermes and restart the gateway."

    /**
     * The answer named a different profile.
     *
     * This state is this app's own, not Desktop's: Desktop falls back to tag
     * filtering a mismatched `scoped` echo (`cron.tsx:221-229`), while this app
     * refuses it outright. Ledgered as drift in `docs/parity/bot-routines.md`.
     * The words say what happened and what to do, and name no profile.
     */
    const val MISMATCHED_TITLE: String = "Not this bot's scheduled jobs"
    const val MISMATCHED_DESC: String =
        "The Gateway answered about a different profile. Nothing from that store is shown here."

    /** The Gateway answered `success:false` inside a good envelope. */
    const val REJECTED_TITLE: String = "The Gateway refused this list"
    const val REJECTED_DESC: String =
        "It would not hand over this bot's scheduled jobs. Try again in a moment."

    /** `cron.emptyTitleNew` (core `en.ts:2619`). */
    const val EMPTY_TITLE: String = "No scheduled jobs yet"

    /** `cron.emptyDescNew` (core `en.ts:2616-2617`). */
    const val EMPTY_DESC: String =
        "Schedule a prompt to run on a cron expression. Hermes will run it and deliver results to the " +
            "destination you pick."

    /**
     * Desktop's own copy for the create action, kept for the record rather than
     * for a renderer: this app's empty card offers no create action in the
     * read-only slice, so the sentence has no slot yet. It is what the create
     * slice will render, and its absence from a surface is what the parity
     * ledger's "coming soon" row records.
     */
    const val NEW_CRON: String = "New cron"

    /** `cron.manage` (core `en.ts:2624`) — the row's own button, which opens the inspector. */
    const val MANAGE: String = "Manage"

    /**
     * The row's control names. Desktop's row carries a Switch and a delete
     * button (`cron.tsx:559-575`); this slice issues no mutation, so both ship
     * visible and disabled behind a `WIP` chip, and each keeps Desktop's own
     * core label for the verb it will eventually perform.
     */
    const val PAUSE_CRON: String = "Pause cron"
    const val RESUME_CRON: String = "Resume cron"
    const val DELETE: String = "Delete"

    /** `common.retry` (`en.ts:143`). */
    const val RETRY: String = "Retry"

    /** `cron.states.*` (core `en.ts:2561-2568`), lower-cased as Desktop renders them. */
    const val STATE_PAUSED: String = "paused"
    const val STATE_COMPLETED: String = "completed"
    const val STATE_FAILED: String = "last run failed"

    /** `cron.next` (core `en.ts:2622`) — the prefix of the next-run label. */
    const val NEXT_PREFIX: String = "Next:"

    /**
     * This slice's own sentence for a routine Desktop refuses to toggle, and
     * the one place this port deliberately does *not* repeat Desktop's words.
     *
     * Desktop pauses a legacy delegated job on load and says so — "Paused for
     * security: delete and recreate this legacy job before running it again."
     * (`cron.tsx:591-595`). This slice performs no mutation at all, so a
     * routine it did not pause must not be labelled paused: the notice says
     * what is true here — the job is one this app cannot manage yet — and the
     * divergence is ledgered in `docs/parity/bot-routines.md`.
     */
    const val LEGACY_NOTICE: String =
        "Legacy delegated routine — this app cannot run, edit, pause or delete it yet."

    /** `cron.onceIn` (`i18n.ts:527`). */
    fun onceIn(whenLabel: String): String = "Once ($whenLabel)"

    /** `cron.everyNDays` (`i18n.ts:528`). */
    fun everyNDays(days: Int): String = "Every $days days"

    /** `cron.everyNHours` (`i18n.ts:529`). */
    fun everyNHours(hours: Int): String = "Every ${hours}h"

    /** `cron.everyNMinutes` (`i18n.ts:530`). */
    fun everyNMinutes(minutes: Int): String = "Every ${minutes}m"

    /** `cron.scheduleLabels.daily` (core `en.ts:2581`). */
    const val DAILY: String = "Daily"

    /** `cron.scheduleLabels.hourly` (core `en.ts:2585`). */
    const val HOURLY: String = "Hourly"

    /** `cron.repeat`/`cron.count` vocabulary: "N times" (`cronjob_job_args.py:136-143`). */
    fun repeatTimes(repeat: String): String = "Repeats $repeat"

    /** `Intl.RelativeTimeFormat`'s English short forms (`lib/time.ts:35-56`). */
    const val IN_PREFIX: String = "in"
    const val AGO_SUFFIX: String = "ago"
    const val UNIT_SECONDS: String = "sec"
    const val UNIT_MINUTES: String = "min"
    const val UNIT_HOURS: String = "hr"

    fun dayUnit(days: Long): String = if (days == 1L) "day" else "days"
}
