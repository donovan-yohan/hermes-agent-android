package com.hermesagent.mobile.plugins.bots

import com.hermesagent.mobile.data.session.safeTurnErrorDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Backend display text is redacted before clipping, never the other way round. */
fun routineDisplay(raw: String?, limit: Int = 512): String = safeTurnErrorDetails(raw).take(limit)

/** Desktop app/cron/job-state.ts at e27448b: strictly more than fifteen minutes. */
fun RoutineRow.overdueMillis(nowMillis: Long): Long? {
    if (!active || state !in setOf(RoutineRunState.Scheduled, RoutineRunState.Failed)) return null
    val at = nextRunMillis ?: return null
    if (nowMillis <= at) return null
    val age = try { Math.subtractExact(nowMillis, at) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    return age.takeIf { it > 900_000L }
}

data class RoutineDetailField(val label: String, val value: String)

/** Held-list facts only; no request, full prompt, or editable draft belongs here. */
fun routineDetailRows(
    job: RoutineRow,
    nowMillis: Long,
    timestampFormat: DateTimeFormatter = defaultRoutineTimestampFormat(),
): List<RoutineDetailField> = buildList {
    fun field(label: String, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { add(RoutineDetailField(label, it)) }
    }
    field("Status", when (job.state) {
        RoutineRunState.Completed -> "Completed"
        RoutineRunState.Unknown -> null
        RoutineRunState.Paused -> "Paused"
        else -> if (job.active) "Active" else "Paused"
    })
    field("Schedule", routineDisplay(job.scheduleLabel))
    field("Schedule (raw)", job.rawSchedule?.takeIf { it != job.scheduleLabel }?.let { routineDisplay(it) })
    field("Repeat", job.repeat?.let { routineDisplay(it) })
    if (job.active) field(if (job.overdueMillis(nowMillis) != null) "Overdue since" else "Next run",
        job.nextRunMillis?.let { routineTimestamp(it, nowMillis, timestampFormat) })
    field("Last run", job.lastRunMillis?.let { routineTimestamp(it, nowMillis, timestampFormat) })
    field("Last result", job.lastResult?.let { routineDisplay(it) })
    field("Deliver to", job.delivery?.let { routineDisplay(it) })
    field("Model", job.model?.let { routineDisplay(it) })
    field("Working directory", job.workdir?.let { routineDisplay(it) })
}

internal fun defaultRoutineTimestampFormat(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun routineTimestamp(at: Long, now: Long, format: DateTimeFormatter): String =
    "${routineRelativeLabel(at, now)} · ${format.format(Instant.ofEpochMilli(at))}"

internal fun routineLastResult(raw: String?): String? = when (raw?.trim()) {
    null, "" -> null
    "ok" -> "Succeeded"
    "error" -> "Failed"
    "delivery_failed" -> "Ran, but delivery failed"
    "blocked_config" -> "Blocked by configuration (not run)"
    else -> routineDisplay(raw)
}
