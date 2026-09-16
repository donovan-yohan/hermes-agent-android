package com.hermesagent.mobile.data.session

private const val SECOND_MILLIS = 1_000L
private const val MINUTE_MILLIS = 60L * SECOND_MILLIS
private const val HOUR_MILLIS = 60L * MINUTE_MILLIS
private const val DAY_MILLIS = 24L * HOUR_MILLIS

/** The coarsest elapsed unit for a non-negative duration. */
enum class RelativeAgeUnit { Second, Minute, Hour, Day }

data class RelativeAge(val unit: RelativeAgeUnit, val value: Long)

/**
 * Compact elapsed time, floored to the coarsest unit — Desktop's
 * `coarseElapsed` (`apps/desktop/src/lib/time.ts:199-215` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`). The caller owns rendering, so no
 * format is baked in here: the row's visible age and the same age spelled for
 * speech are two renderings of this one answer. Negative deltas clamp to zero,
 * so clock rollback never produces a misleading negative age.
 */
fun relativeAge(deltaMillis: Long): RelativeAge {
    val millis = deltaMillis.coerceAtLeast(0L)
    return when {
        millis >= DAY_MILLIS -> RelativeAge(RelativeAgeUnit.Day, millis / DAY_MILLIS)
        millis >= HOUR_MILLIS -> RelativeAge(RelativeAgeUnit.Hour, millis / HOUR_MILLIS)
        millis >= MINUTE_MILLIS -> RelativeAge(RelativeAgeUnit.Minute, millis / MINUTE_MILLIS)
        else -> RelativeAge(RelativeAgeUnit.Second, millis / SECOND_MILLIS)
    }
}

/** The compact suffixes the sidebar row's age is drawn from (`i18n/en.ts:2789-2792` @ the pin). */
data class RelativeAgeLabels(
    val now: String = "now",
    val day: String = "d",
    val hour: String = "h",
    val minute: String = "m",
)

/**
 * The compact age a sidebar row shows — `now`, `12m`, `9h`, `1d` — read off the
 * one bucket rule Desktop's own row uses (`session-row.tsx:116-122,211-245`;
 * units `i18n/en.ts:2789-2792`, both @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`). Under a minute reads `now`,
 * because Desktop's row never shows a seconds tick.
 */
fun relativeAgeLabel(
    atMillis: Long,
    nowMillis: Long,
    labels: RelativeAgeLabels = RelativeAgeLabels(),
): String {
    val elapsed = relativeAge(nowMillis - atMillis)
    return when (elapsed.unit) {
        RelativeAgeUnit.Second -> labels.now
        RelativeAgeUnit.Day -> "${elapsed.value}${labels.day}"
        RelativeAgeUnit.Hour -> "${elapsed.value}${labels.hour}"
        RelativeAgeUnit.Minute -> "${elapsed.value}${labels.minute}"
    }
}

/**
 * The same age, spelled for speech: `just now`, `1 minute ago`, `12 minutes ago`.
 *
 * Desktop shows the age twice, and neither form is usable here as it stands. The
 * visible one is the compact `12m` (`formatAge`, `session-row.tsx:116-122`), and
 * the accessible one rides a focusable `<time>` as `aria-label={`${age},
 * ${absoluteAge}`}` (`:229-237`) whose units are still abbreviated — `now`,
 * `m ago`, `h ago`, `d ago` (`i18n/en.ts:1837-1841`; all @ the pin). This app's
 * row is one merged semantics node with no focusable child, and a screen reader
 * pronounces `12m ago` as a number and a letter, so the age joins the sentence
 * the row already speaks. The bucket and its value are Desktop's; the
 * spelling-out is this app's, for speech.
 */
fun spokenRelativeAgeLabel(atMillis: Long, nowMillis: Long): String {
    val elapsed = relativeAge(nowMillis - atMillis)
    return when (elapsed.unit) {
        RelativeAgeUnit.Second -> "just now"
        RelativeAgeUnit.Minute -> elapsed.value.spelled("minute")
        RelativeAgeUnit.Hour -> elapsed.value.spelled("hour")
        RelativeAgeUnit.Day -> elapsed.value.spelled("day")
    }
}

private fun Long.spelled(unit: String): String =
    if (this == 1L) "1 $unit ago" else "$this ${unit}s ago"
