package com.hermesagent.mobile.data.session

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeAgeTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `relative age matches Desktop compact sidebar shapes`() {
        assertEquals("now", relativeAgeLabel(now - 5_000L, now))
        assertEquals("12m", relativeAgeLabel(now - 12L * 60_000L, now))
        assertEquals("9h", relativeAgeLabel(now - 9L * 3_600_000L, now))
        assertEquals("1d", relativeAgeLabel(now - 24L * 3_600_000L, now))
    }

    /**
     * The two consumers of this rule — the sidebar row and the bots roster —
     * share it precisely so they cannot diverge. Its floors are asserted here,
     * once, at the seam both read.
     */
    @Test
    fun `the bucket rule floors to the coarsest unit`() {
        assertEquals(RelativeAge(RelativeAgeUnit.Second, 0L), relativeAge(0L))
        assertEquals(RelativeAge(RelativeAgeUnit.Second, 59L), relativeAge(59_999L))
        assertEquals(RelativeAge(RelativeAgeUnit.Minute, 1L), relativeAge(60_000L))
        assertEquals(RelativeAge(RelativeAgeUnit.Minute, 52L), relativeAge(52L * 60_000L))
        assertEquals(RelativeAge(RelativeAgeUnit.Hour, 1L), relativeAge(60L * 60_000L))
        assertEquals(RelativeAge(RelativeAgeUnit.Day, 18L), relativeAge(18L * 24L * 3_600_000L))
        assertEquals(RelativeAge(RelativeAgeUnit.Second, 0L), relativeAge(-5_000L))
    }

    @Test
    fun `future timestamps clamp to now`() {
        assertEquals("now", relativeAgeLabel(now + 5_000L, now))
    }

    @Test
    fun `relative age suffixes are overridable`() {
        val labels = RelativeAgeLabels(now = "just now", day = " days", hour = " hours", minute = " min")

        assertEquals("just now", relativeAgeLabel(now - 5_000L, now, labels))
        assertEquals("12 min", relativeAgeLabel(now - 12L * 60_000L, now, labels))
    }

    @Test
    fun `the spoken age spells the unit the compact label abbreviates`() {
        assertEquals("just now", spokenRelativeAgeLabel(now - 5_000L, now))
        assertEquals("1 minute ago", spokenRelativeAgeLabel(now - 90_000L, now))
        assertEquals("12 minutes ago", spokenRelativeAgeLabel(now - 12L * 60_000L, now))
        assertEquals("1 hour ago", spokenRelativeAgeLabel(now - 60L * 60_000L, now))
        assertEquals("9 hours ago", spokenRelativeAgeLabel(now - 9L * 3_600_000L, now))
        assertEquals("1 day ago", spokenRelativeAgeLabel(now - 24L * 3_600_000L, now))
        assertEquals("39 days ago", spokenRelativeAgeLabel(now - 39L * 86_400_000L, now))
    }

    @Test
    fun `the spoken age clamps a future timestamp like the compact one`() {
        assertEquals("just now", spokenRelativeAgeLabel(now + 5_000L, now))
    }
}
