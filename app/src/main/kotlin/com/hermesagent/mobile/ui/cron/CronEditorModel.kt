package com.hermesagent.mobile.ui.cron

import com.hermesagent.mobile.data.cron.CronJob
import com.hermesagent.mobile.data.cron.CronResult
import com.hermesagent.mobile.data.cron.CronFailure
import com.hermesagent.mobile.data.ssh.redact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.chrono.IsoChronology
import java.util.Locale
import androidx.compose.runtime.staticCompositionLocalOf
import com.hermesagent.mobile.data.cron.CronAction

/** Opaque ids never come back through redacted or duplicate display labels. */
internal data class CronChoiceItem<T>(val id: T, val label: String)

internal data class CronSchedule(val label: String, val expression: String?, val hint: String)
internal val cronSchedules = listOf(
    CronSchedule("Daily", "0 9 * * *", "Every day at 9:00 AM"),
    CronSchedule("Weekdays", "0 9 * * 1-5", "Monday through Friday at 9:00 AM"),
    CronSchedule("Weekly", "0 9 * * 1", "Every Monday at 9:00 AM"),
    CronSchedule("Monthly", "0 9 1 * *", "The first day of each month at 9:00 AM"),
    CronSchedule("Hourly", "0 * * * *", "At the top of every hour"),
    CronSchedule("Every 15 minutes", "*/15 * * * *", "Every 15 minutes"),
    CronSchedule("Custom", null, "Cron syntax or natural language"),
)
internal fun scheduleFor(expression: String): CronSchedule {
    val normalized = expression.trim().replace(Regex("\\s+"), " ")
    cronSchedules.find { it.expression == normalized }?.let { return it }
    val parts = normalized.split(" ")
    if (parts.size != 5) return cronSchedules.last()
    val (minute, hour, day, month, weekday) = parts
    fun number(s: String) = s.matches(Regex("\\d+"))
    val index = when {
        number(minute) && number(hour) && day == "*" && month == "*" && weekday == "*" -> 0
        number(minute) && number(hour) && day == "*" && month == "*" && weekday == "1-5" -> 1
        number(minute) && number(hour) && day == "*" && month == "*" && number(weekday) -> 2
        number(minute) && number(hour) && number(day) && month == "*" && weekday == "*" -> 3
        number(minute) && hour == "*" && day == "*" && month == "*" && weekday == "*" -> 4
        else -> 6
    }
    return cronSchedules[index]
}
internal fun deliveryIds(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotBlank).distinct().ifEmpty { listOf("local") }
internal fun toggleDelivery(value: String, target: String, checked: Boolean): String {
    val ids = deliveryIds(value)
    return (if (checked) (ids + target).distinct() else if (ids.size == 1) ids else ids - target).joinToString(",")
}
internal fun safe(value: String?): String = redact(value).take(16_000)
internal fun cronState(job: CronJob): String = job.state.trim().ifBlank { if (job.enabled) "scheduled" else "disabled" }
internal fun overdue(job: CronJob, now: Long): Boolean = cronState(job) !in setOf("paused", "completed", "disabled") &&
    runCatching { now - Instant.parse(job.nextRunAt).toEpochMilli() > 900_000 }.getOrDefault(false)
/** Unsupported terminal/unknown state transitions stay visible but cannot assert safe resume. */
internal fun pauseResumeAction(job: CronJob): CronAction? = when (cronState(job)) {
    "paused" -> CronAction.Resume
    "enabled", "scheduled", "running", "error" -> CronAction.Pause
    else -> null
}
internal fun nextRunMillis(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
internal fun sidebarJobs(jobs: List<CronJob>): List<CronJob> = jobs.sortedWith(
    compareBy<CronJob> { nextRunMillis(it.nextRunAt) ?: Long.MAX_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
).take(50)

/** Fixture overrides only this subtree; production follows the user's locale/timezone. */
internal data class CronDisplayClock(val locale: Locale = Locale.getDefault(), val zone: ZoneId = ZoneId.systemDefault()) {
    fun displayTime(value: String?): String = value?.let {
        runCatching {
            val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, FormatStyle.MEDIUM, IsoChronology.INSTANCE, locale)
                .replace(Regex("y+"), "yyyy")
            DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.parse(it))
        }.getOrElse { safe(value) }
    } ?: "—"
    fun runTime(value: Double?, compact: Boolean = false): String = value?.takeIf { it.isFinite() }?.let {
        val instant = Instant.ofEpochMilli((it * 1000).toLong())
        if (compact) DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdjm"), locale).withZone(zone).format(instant) else displayTime(instant.toString())
    } ?: "—"
    fun relativeTime(target: Long, now: Long): String {
        val diff = target - now
        val abs = kotlin.math.abs(diff)
        val (size, unit) = when {
            abs < 60_000 -> 1_000L to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS
            abs < 3_600_000 -> 60_000L to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
            abs < 86_400_000 -> 3_600_000L to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
            else -> 86_400_000L to android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
        }
        val value = kotlin.math.floor(abs.toDouble() / size + 0.5)
        val formatter = android.icu.text.RelativeDateTimeFormatter.getInstance(android.icu.util.ULocale.forLocale(locale), null,
            android.icu.text.RelativeDateTimeFormatter.Style.SHORT, android.icu.text.DisplayContext.CAPITALIZATION_NONE)
        val direction = if (diff < 0) android.icu.text.RelativeDateTimeFormatter.Direction.LAST else android.icu.text.RelativeDateTimeFormatter.Direction.NEXT
        // numeric:auto uses yesterday/today/tomorrow for day-sized offsets.
        // Use the API-24 overloads; RelativeDateTimeUnit requires API 28.
        if (unit == android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS && value <= 1) {
            return formatter.format(if (value == 0.0) android.icu.text.RelativeDateTimeFormatter.Direction.THIS else direction,
                android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        }
        if (unit == android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS && value == 0.0) {
            return formatter.format(android.icu.text.RelativeDateTimeFormatter.Direction.PLAIN, android.icu.text.RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        }
        return formatter.format(value, direction, unit)
    }
}
internal val LocalCronDisplayClock = staticCompositionLocalOf { CronDisplayClock() }
internal fun failureCopy(failure: CronResult.Failure): String = when {
    failure.kind == CronFailure.SavedRegistrationFailed -> "Cron saved, but scheduling failed. Review the saved job before trying again."
    failure.mutationMayHaveApplied || failure.kind == CronFailure.Unconfirmed -> "The outcome could not be confirmed. Refresh and review before trying again."
    failure.kind == CronFailure.Unavailable -> "Scheduled jobs are unavailable on this backend."
    failure.kind == CronFailure.InvalidScope || failure.kind == CronFailure.StaleScope -> "The selected profile changed. Refresh to continue."
    failure.kind == CronFailure.Refused -> "The request was refused. Review the job and try again."
    else -> "Failed to load cron jobs. Check the connection and refresh."
}
