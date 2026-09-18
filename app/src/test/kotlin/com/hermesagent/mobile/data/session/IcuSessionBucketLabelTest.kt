package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The month half of the divider copy, with the **real** ICU formatter rather
 * than a stub. `SessionGroupingTest` pins the algorithm against an injected
 * label; this pins the shipped one.
 *
 * Desktop reads the same two forms from `Intl`
 * (`apps/desktop/src/lib/time.ts:30-31` @
 * `437116f9497c80d242ce034ff7f5d81dc277a337`):
 *
 * ```ts
 * export const fmtMonth = new Intl.DateTimeFormat(undefined, { month: 'long' })
 * export const fmtMonthYear = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' })
 * ```
 *
 * The assertions are properties of ICU's own answer for the locale, not this
 * app's spelling of it: a month name is the locale's long month, a month + year
 * contains both, and neither is enumerated here. That is deliberate — the point
 * of asking ICU is that a locale whose layout differs is still right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IcuSessionBucketLabelTest {

    private val zone: TimeZone = TimeZone.getTimeZone("UTC")
    private val locale: Locale = Locale.US

    private val label = IcuSessionBucketLabel(locale, zone)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance(zone, locale).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis

    private fun monthBucket(year: Int, month: Int): SessionBucket =
        calendarBucket(at(year, month, 15), at(2026, Calendar.AUGUST, 19), zone, locale)

    private fun monthYearBucket(year: Int, month: Int, now: Long): SessionBucket =
        calendarBucket(at(year, month, 15), now, zone, locale)

    /** `{ month: 'long' }` — the month's own long name and nothing else. */
    @Test
    fun `a month bucket renders the locale's long month name`() {
        val july = monthBucket(2026, Calendar.JULY)

        assertEquals(SessionBucketKind.Month, july.kind)
        assertEquals("July", label.label(july))
        assertEquals("March", label.label(monthBucket(2026, Calendar.MARCH)))

        // No year in the month form — that is the whole difference from the
        // other one, and it is what makes the two buckets distinguishable.
        assertTrue(
            "the month form must not carry a year",
            !label.label(july).contains("2026"),
        )
    }

    /** `{ month: 'long', year: 'numeric' }`. */
    @Test
    fun `a month-year bucket renders the month and the year`() {
        val now = at(2026, Calendar.AUGUST, 19)
        val lastJuly = monthYearBucket(2025, Calendar.JULY, now)

        assertEquals(SessionBucketKind.MonthYear, lastJuly.kind)
        assertEquals("July 2025", label.label(lastJuly))

        // ICU's own best order for the locale, not a hand-written pattern.
        val rendered = label.label(lastJuly)
        assertTrue(rendered.contains("July"))
        assertTrue(rendered.contains("2025"))
    }

    /**
     * The reason the tail could not ship as one terminal bucket: two adjacent
     * months are two identities *and* two different words.
     */
    @Test
    fun `two month buckets word differently and neither captions the other`() {
        val july = monthBucket(2026, Calendar.JULY)
        val june = monthBucket(2026, Calendar.JUNE)

        assertNotEquals(july.key, june.key)
        assertEquals("July", label.label(july))
        assertEquals("June", label.label(june))
    }

    /**
     * The bucket instant is a nominal day start, so a row late on the last day
     * of a month still names that month — the reason `atMillis` travels with the
     * bucket rather than the row's own instant.
     */
    @Test
    fun `a row late on the last day of a month still names that month`() {
        val now = at(2026, Calendar.AUGUST, 19)
        val lateJuly = calendarBucket(
            Calendar.getInstance(zone, locale).apply {
                clear()
                set(2026, Calendar.JULY, 31, 23, 50, 0)
            }.timeInMillis,
            now,
            zone,
            locale,
        )

        assertEquals("m-2026-6", lateJuly.key)
        assertEquals("July", label.label(lateJuly))
    }

    /**
     * A locale whose month-year layout ICU orders differently is right without
     * this app knowing which locales those are — the whole argument for asking
     * for a best pattern instead of writing one. Japanese puts the year first.
     */
    @Test
    fun `a locale with a different field order is ICU's answer, not ours`() {
        val japanese = IcuSessionBucketLabel(Locale.JAPANESE, zone)
        val now = at(2026, Calendar.AUGUST, 19)
        val rendered = japanese.label(monthYearBucket(2025, Calendar.JULY, now))

        assertTrue("the year is present", rendered.contains("2025"))
        // English puts the month first; Japanese puts the year first. Same two
        // fields, two layouts, neither of them written down here.
        assertEquals("July 2025", label.label(monthYearBucket(2025, Calendar.JULY, now)))
        assertTrue(
            "Japanese orders the year before the month; got $rendered",
            rendered.indexOf("2025") == 0,
        )
        assertTrue("the Japanese month name follows the year", rendered.contains("7月"))
    }

    /** The five relative strings are i18n, not ICU, and are unaffected by locale. */
    @Test
    fun `the relative buckets keep Desktop's fixed copy in every locale`() {
        val now = at(2026, Calendar.AUGUST, 19)
        val japanese = IcuSessionBucketLabel(Locale.JAPANESE, zone)

        for (bucketLabel in listOf(label, japanese)) {
            assertEquals("Earlier today", bucketLabel.label(calendarBucket(now - 60_000, now, zone, locale)))
            assertEquals("Yesterday", bucketLabel.label(calendarBucket(now - 86_400_000, now, zone, locale)))
        }
    }
}
