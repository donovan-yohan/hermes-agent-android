package com.hermesagent.mobile.data.session

import android.icu.text.DateFormat
import android.icu.util.ULocale
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Desktop's month divider copy, from ICU
 * (`apps/desktop/src/lib/time.ts:30-31` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`):
 *
 * ```ts
 * export const fmtMonth = new Intl.DateTimeFormat(undefined, { month: 'long' })
 * export const fmtMonthYear = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' })
 * ```
 *
 * `month: 'long'` + `year: 'numeric'` is a *skeleton* in ICU's own terms, and
 * the field-order question it leaves open — "September 2025" in English, a
 * different order and different separators elsewhere — is exactly what a
 * skeleton resolves and what a hand-written pattern string gets wrong for every
 * locale but one. So the pattern is asked for, never enumerated:
 * `DateFormat.getInstanceForSkeleton` resolves and binds ICU's layout. A locale whose month form ICU spells
 * differently is then correct without this file knowing which locales those
 * are.
 *
 * Android-only on purpose. ICU lives in `android.icu`, which a plain JVM unit
 * test has not got; keeping the implementation in its own file is what lets
 * [buildSessionRows] stay a pure function a deterministic JVM suite drives with
 * its own [SessionBucketLabel]. Robolectric supplies the real ICU, so genuine
 * locale formatting gets its own test there.
 *
 * Constructing this label must not touch ICU either, and that is load-bearing:
 * [buildSessionRows]'s default argument builds one on *every* call, including
 * the plain JVM callpaths that never render a month divider. Resolving the
 * locale and the two formatters up front made construction a `Stub!` call
 * there (`ULocale.forLocale`), so a whole unit-test process died for a question
 * it never asked. All ICU state is therefore resolved on first use. A label
 * genuinely asked for a month *is* an ICU question, and on a plain JVM it still
 * fails loudly rather than degrading to hand-written copy — that is the point
 * of asking ICU at all, and the reason the seam exists.
 *
 * @param locale the reader's locale — ICU resolves the skeleton against it.
 * @param timeZone the zone the buckets were cut in. The bucket instant is
 *   already a local nominal day start, so formatting it in any other zone would
 *   name the wrong month near a boundary.
 */
class IcuSessionBucketLabel(
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
) : SessionBucketLabel {

    private val ulocale: ULocale by lazy { ULocale.forLocale(locale) }

    private val month: DateFormat by lazy { formatter(MONTH_SKELETON, timeZone) }
    private val monthYear: DateFormat by lazy { formatter(MONTH_YEAR_SKELETON, timeZone) }

    private fun formatter(skeleton: String, zone: TimeZone): DateFormat {
        return DateFormat.getInstanceForSkeleton(skeleton, ulocale).apply {
            timeZone = android.icu.util.TimeZone.getTimeZone(zone.id)
        }
    }

    override fun label(bucket: SessionBucket): String = when (bucket.kind) {
        SessionBucketKind.Month -> month.format(Date(bucket.atMillis))
        SessionBucketKind.MonthYear -> monthYear.format(Date(bucket.atMillis))
        // The five relative strings are Desktop's i18n, not ICU's — see
        // `SessionBucket.relativeLabel()`.
        else -> bucket.relativeLabel().orEmpty()
    }

    private companion object {
        /** `{ month: 'long' }`. */
        const val MONTH_SKELETON = "MMMM"

        /** `{ month: 'long', year: 'numeric' }`. */
        const val MONTH_YEAR_SKELETON = "yMMMM"
    }
}
