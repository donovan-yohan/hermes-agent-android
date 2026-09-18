package com.hermesagent.mobile.plugins.bots

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure creation contract; deliberately not wired to a host or UI.
 * Source pin: d177b119e9c56c9ddc0b7379ffce52341ec06584.
 * apps/desktop/src/plugins/hermes-bots/cron.tsx:603-696,767-919,925-1035.
 */
internal enum class RoutineFrequency(val id: String) {
    Once("once"), Hourly("hourly"), Daily("daily"), Weekdays("weekdays"),
    Weekly("weekly"), Monthly("monthly"), Interval("interval"), Advanced("advanced");

    val showsTime get() = when (this) {
        Daily, Weekdays, Weekly, Monthly -> true
        else -> false
    }
    val showsOnceAmount get() = this == Once
    val showsIntervalAmount get() = this == Interval
    val showsWeekday get() = this == Weekly
    val showsMonthday get() = this == Monthly
    val showsRaw get() = this == Advanced
    val showsRepeat get() = this != Once && this != Advanced
}

internal data class RoutineTimeOption(val id: String, val label: String)

internal fun routineTimeOptions(): List<RoutineTimeOption> = (0..23).flatMap { hour ->
    listOf(0, 30).map { minute ->
        RoutineTimeOption(
            "$hour:$minute",
            "${if (hour % 12 == 0) 12 else hour % 12}:${if (minute == 0) "00" else "30"} ${if (hour < 12) "AM" else "PM"}",
        )
    }
}

internal fun routineWeekdayIds(): List<String> = listOf("1", "2", "3", "4", "5", "6", "0")
internal fun sanitizeRoutineAmount(value: String): String = value.filter { it in '0'..'9' }.take(4)
internal fun sanitizeRoutineMonthday(value: String): String = value.filter { it in '0'..'9' }.take(2)

/**
 * Detail strings are constrained to Desktop's sanitized picker domain, NOT arbitrary
 * parseInt input. Call the sanitizers on edits; invalid constructor/copy input is refused.
 * Blank repeat may include whitespace (Desktop omits it). Advanced text is unrestricted.
 * copy(frequency = ...) retains hidden fields, just like Desktop's partial state patch.
 */
internal data class RoutineScheduleDraft(
    val frequency: RoutineFrequency = RoutineFrequency.Daily,
    val time: String = "9:0",
    val weekday: String = "1",
    val monthday: String = "1",
    val intervalN: String = "2",
    val intervalUnit: String = "h",
    val onceN: String = "30",
    val onceUnit: String = "m",
    val repeatN: String = "",
    val raw: String = "",
) {
    init {
        require(onceN == sanitizeRoutineAmount(onceN))
        require(intervalN == sanitizeRoutineAmount(intervalN))
        require(repeatN.isBlank() || repeatN == sanitizeRoutineAmount(repeatN))
        require(monthday == sanitizeRoutineMonthday(monthday))
        require(time.isEmpty() || routineTimeOptions().any { it.id == time })
        require(weekday.isEmpty() || weekday in routineWeekdayIds())
        require(onceUnit in listOf("", "m", "h", "d"))
        require(intervalUnit in listOf("", "m", "h", "d"))
    }

    fun compose(): String {
        val (hour, minute) = time.ifEmpty { "9:0" }.split(':').map(String::toInt)
        return when (frequency) {
            // Desktop Once is a bare duration. Gateway interprets it as recurring;
            // preserve this mismatch, do not invent `in ` or repeat=1.
            // Same pin: cron/jobs.py:770-834,1794-1802,1848-1850.
            RoutineFrequency.Once -> "${positiveAmount(onceN)}${onceUnit.ifEmpty { "h" }}"
            RoutineFrequency.Hourly -> "every 1h"
            RoutineFrequency.Daily -> "$minute $hour * * *"
            RoutineFrequency.Weekdays -> "$minute $hour * * 1-5"
            RoutineFrequency.Weekly -> "$minute $hour * * ${weekday.ifEmpty { "1" }}"
            RoutineFrequency.Monthly -> "$minute $hour ${monthday.ifEmpty { "1" }} * *"
            RoutineFrequency.Interval -> "every ${positiveAmount(intervalN)}${intervalUnit.ifEmpty { "h" }}"
            RoutineFrequency.Advanced -> raw
        }
    }
}

private fun positiveAmount(value: String): Int = (value.toIntOrNull() ?: 1).coerceAtLeast(1)

internal enum class RoutineDelivery { History, BotChat }

internal data class RoutineCreationDraft(
    val title: String = "",
    val instruction: String = "",
    val schedule: RoutineScheduleDraft = RoutineScheduleDraft(),
    val continuity: Boolean = false,
    val delivery: RoutineDelivery = RoutineDelivery.History,
) {
    /** Params for cron.manage only; null is local validation refusal, never a dispatch. */
    fun payload(rawOwnerProfile: String, activeProfile: String): JsonObject? {
        val name = title.trim()
        val task = instruction.trim()
        val composed = schedule.compose().trim()
        if (rawOwnerProfile.isBlank() || name.isEmpty() || task.isEmpty() || composed.isEmpty() ||
            '\u0000' in name || '\u0000' in task
        ) return null
        return buildJsonObject {
            put("action", "add")
            put("name", "[bot:$rawOwnerProfile] $name")
            put("schedule", composed)
            put("prompt", creationPrompt(rawOwnerProfile, name, task, activeProfile))
            put("profile", rawOwnerProfile)
            if (schedule.frequency.showsRepeat && schedule.repeatN.isNotBlank()) put("repeat", positiveAmount(schedule.repeatN))
            if (continuity) put("continuity", true)
            if (delivery == RoutineDelivery.BotChat) put("deliver", "bot-chat")
        }
    }
}

// Same pin: apps/desktop/src/plugins/hermes-bots/cron.tsx:74,250-286.
private fun creationPrompt(bot: String, title: String, instruction: String, activeProfile: String): String {
    if (bot.trim().isNotEmpty() && bot.trim().lowercase() == activeProfile.trim().lowercase()) return instruction
    fun quote(value: String) = "'${value.replace("'", "'\"'\"'")}'"
    return "[bot-mode:routine:v2] You are running the scheduled routine \"$title\" for agent '$bot'. " +
        "Execute it AS that agent so the run lands in its own history: run this in the terminal and relay the output:\n\n" +
        "hermes -p ${quote(bot)} chat -c ${quote("Routine: $title")} -q ${quote("[Scheduled routine] $instruction")}\n\n" +
        "If the command fails, report the error instead."
}

/**
 * Android policy on the INNER tool result, not RPC envelope success.
 * Same pin: tui_gateway/methods_tools.py:1089-1096; tools/cronjob_tools.py:616-630;
 * cron/scheduler.py:3488-3531 (persistence precedes registration).
 * No outcome authorizes automatic add retry. Unconfirmed needs owner-scoped
 * reconciliation; a name-only match cannot establish identity. Never keep backend prose.
 */
internal sealed interface RoutineCreationAck {
    val automaticRecreationAllowed: Boolean get() = false
    data class Created(val jobId: String) : RoutineCreationAck
    data class SavedRegistrationFailed(val jobId: String) : RoutineCreationAck
    data object Rejected : RoutineCreationAck
    data class Unconfirmed(val jobId: String? = null) : RoutineCreationAck
}

/** null also represents transport failure after possible dispatch; before-dispatch refusal is separate. */
internal fun classifyRoutineCreationAck(payload: JsonElement?): RoutineCreationAck {
    val body = payload as? JsonObject ?: return RoutineCreationAck.Unconfirmed()
    fun boolean(key: String): Boolean? = (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    val id = (body["job_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl) }
    val flags = listOf("job_saved", "scheduler_registered", "retry_create")
    if (flags.any { it in body && boolean(it) == null }) return RoutineCreationAck.Unconfirmed(id)
    val success = boolean("success")
    val saved = boolean("job_saved")
    val registered = boolean("scheduler_registered")
    val retry = boolean("retry_create")
    if (success == false && id != null && saved == true && registered == false && retry == false) {
        return RoutineCreationAck.SavedRegistrationFailed(id)
    }
    if (success == true && id != null && saved != false && registered != false && retry != true && "error" !in body) {
        return RoutineCreationAck.Created(id)
    }
    if (success == false && "job_id" !in body && flags.none { it in body }) return RoutineCreationAck.Rejected
    return RoutineCreationAck.Unconfirmed(id)
}
