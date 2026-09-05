package com.hermesagent.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wordmark's fit, against real type metrics.
 *
 * Desktop's `.fit-text` carries a `2.75rem` floor (`wordmark.tsx:22` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) it can afford because its chat
 * column is never narrower than the one line it sets. This port stacks the
 * wordmark — `HERMES` over `AGENT` — so the run that has to fit is the wider
 * *line*, not the whole string, and the floor becomes reachable on every phone
 * width. That is the claim this class holds, and the reason the table in
 * `docs/parity/empty-states.md` was recomputed.
 *
 * **Where the ems come from.** From the shipped face itself:
 * [CollapseBoldFont] reads `res/font/collapse_bold.otf` and reports its
 * advances, and `CollapseBoldFontTest` pins those against the pinned Desktop
 * capture. Nothing here is a number somebody typed from a screenshot.
 *
 * **Why the arithmetic is separated from the measuring.** Robolectric in its
 * default legacy graphics mode loads no device font: it measures the whole
 * twelve-character wordmark at `32.5px` when asked for `48sp`, and not even
 * linearly in the size. So a fit asserted under it would be measuring the stub.
 * [fitWordmarkSp] takes the run as an argument and is driven here from the face
 * on disk; the *rendered* face is measured in
 * `app/src/testDebug/.../WordmarkFitDeviceTest.kt` under `@GraphicsMode(NATIVE)`.
 */
class WordmarkFitTest {

    @Test
    fun `the wider line is HERMES, which is the one the column has to hold`() {
        assertEquals(listOf("HERMES", "AGENT"), INTRO_WORDMARK_LINES)
        assertEquals(INTRO_WORDMARK, INTRO_WORDMARK_LINES.joinToString(" "))
        assertTrue(hermesEm > agentEm)
    }

    /**
     * The table in `docs/parity/empty-states.md`. If these move, that page is
     * stale.
     */
    @Test
    fun `every phone column holds the stacked wordmark`() {
        for ((screenDp, columnDp) in PHONE_COLUMNS) {
            val fitted = fitAt(columnDp)
            val run = fitted * hermesEm
            assertTrue(
                "w${screenDp}dp: fitted ${fitted}sp runs ${run}dp in a ${columnDp}dp column",
                run <= columnDp + TOLERANCE,
            )
        }
    }

    /**
     * The whole point of stacking. On one line Desktop's floor needed `341dp`
     * of glyph run against the `300dp` a `w320dp` phone leaves, so this port
     * had to fit *below* it and say so. Two lines clear it everywhere.
     */
    @Test
    fun `the stacked fit clears Desktop's floor on every phone width`() {
        for ((screenDp, columnDp) in PHONE_COLUMNS) {
            val fitted = fitAt(columnDp)
            assertTrue(
                "w${screenDp}dp fitted to ${fitted}sp, below Desktop's " +
                    "${WORDMARK_MIN_FONT_SIZE_DESKTOP.value}sp floor",
                fitted >= WORDMARK_MIN_FONT_SIZE_DESKTOP.value,
            )
        }
        // And the one-line run is still the reason there is no floor *clamp*:
        // on the narrowest phone `HERMES AGENT` at 2.75rem overruns its column.
        val wholeString = CollapseBoldFont.emRun(INTRO_WORDMARK) +
            INTRO_WORDMARK.length * CollapseBoldFontTest.WORDMARK_TRACKING_EM
        assertTrue(
            "one line at the floor needs ${WORDMARK_MIN_FONT_SIZE_DESKTOP.value * wholeString}dp",
            WORDMARK_MIN_FONT_SIZE_DESKTOP.value * wholeString > PHONE_COLUMNS.first().second,
        )
    }

    @Test
    fun `a wide column is capped rather than growing without bound`() {
        assertEquals(WORDMARK_MAX_FONT_SIZE.value, fitAt(2_000f), TOLERANCE)
        assertTrue(fitAt(200f) < WORDMARK_MAX_FONT_SIZE.value)
    }

    /**
     * The height guard. Two lines are twice as tall as one, so a short slot —
     * a phone in landscape — is where filling the width would push the line of
     * copy the wordmark titles out of the surface.
     */
    @Test
    fun `a short slot lowers the fit rather than overflowing it`() {
        val slotSp = 200f
        val fitted = fitWordmarkSp(
            probeSp = PROBE_SP,
            probeRunPx = PROBE_SP * hermesEm,
            targetWidthPx = 2_000f,
            maxSp = WORDMARK_MAX_FONT_SIZE.value,
            lineCount = INTRO_WORDMARK_LINES.size,
            slotHeightSp = slotSp,
        )
        val block = fitted * INTRO_WORDMARK_LINES.size * WORDMARK_LINE_HEIGHT
        assertTrue("the guard did not bind: ${fitted}sp", fitted < WORDMARK_MAX_FONT_SIZE.value)
        assertEquals(slotSp * WORDMARK_HEIGHT_SHARE, block, TOLERANCE)
    }

    /** A larger font scale narrows the fit; it must never push the run out. */
    @Test
    fun `a raised font scale still fits, because the ratio is measured not assumed`() {
        // `fitWordmarkSp` works in the probe's own units, so a font scale that
        // widens the probe run shrinks the answer by the same factor.
        val columnDp = 300f
        val normal = fitWordmarkSp(PROBE_SP, PROBE_SP * hermesEm, columnDp, WORDMARK_MAX_FONT_SIZE.value)
        val scaled = fitWordmarkSp(PROBE_SP, PROBE_SP * hermesEm * 1.3f, columnDp, WORDMARK_MAX_FONT_SIZE.value)
        assertTrue("a wider run must yield a smaller size", scaled < normal)
        assertTrue(scaled * hermesEm * 1.3f <= columnDp + TOLERANCE)
    }

    @Test
    fun `an unmeasured column or an unmeasurable font yields no lettering`() {
        assertEquals(0f, fitAt(0f), 0f)
        assertEquals(0f, fitAt(-10f), 0f)
        assertEquals(0f, fitWordmarkSp(PROBE_SP, 0f, 300f, WORDMARK_MAX_FONT_SIZE.value), 0f)
        assertEquals(0f, fitWordmarkSp(0f, 100f, 300f, WORDMARK_MAX_FONT_SIZE.value), 0f)
        assertEquals(0f, fitWordmarkSp(PROBE_SP, 100f, 300f, WORDMARK_MAX_FONT_SIZE.value, lineCount = 0), 0f)
        assertEquals(
            0f,
            fitWordmarkSp(PROBE_SP, 100f, 300f, WORDMARK_MAX_FONT_SIZE.value, slotHeightSp = 0f),
            0f,
        )
    }

    /** How wide a run is in this face, tracking included, in ems. */
    private fun trackedEm(line: String): Float =
        CollapseBoldFont.emRun(line) + line.length * CollapseBoldFontTest.WORDMARK_TRACKING_EM

    private val hermesEm: Float get() = trackedEm("HERMES")
    private val agentEm: Float get() = trackedEm("AGENT")

    private fun fitAt(columnDp: Float): Float = fitWordmarkSp(
        probeSp = PROBE_SP,
        probeRunPx = PROBE_SP * hermesEm,
        targetWidthPx = columnDp,
        maxSp = WORDMARK_MAX_FONT_SIZE.value,
    )

    private companion object {
        val PROBE_SP = WORDMARK_PROBE_FONT_SIZE.value

        /**
         * Screen width to the column `Wordmark` actually receives:
         * `screen - 2 * INTRO_SPLASH_GUTTER - WORDMARK_INSET`, which is
         * `screen - 20dp`.
         */
        val PHONE_COLUMNS = listOf(320 to 300f, 360 to 340f, 411 to 391f)

        const val TOLERANCE = 0.01f
    }
}
