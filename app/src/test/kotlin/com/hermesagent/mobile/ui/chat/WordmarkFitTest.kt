package com.hermesagent.mobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wordmark's fit, against real type metrics.
 *
 * Desktop's `.fit-text` carries a `2.75rem` floor (`wordmark.tsx:22` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`) it can afford because its chat
 * column is never narrower than the run. The pinned capture measures the
 * lettering at `1052.0px` wide in a `135.637px` face
 * (`docs/parity/visual/empty-states/empty-chat-intro-light/desktop/contract.json`,
 * node 4), so `HERMES AGENT` spans [COLLAPSE_EM] ems in Collapse. At the floor
 * that is over `341dp` of glyph run, against the `300dp` a `w320dp` phone
 * leaves. Under `maxLines = 1, softWrap = false` an overrun is clipped at both
 * ends in silence, so a floor that cannot fit is an invisible failure.
 *
 * **Why the ratio comes from the capture rather than from a font.** Robolectric
 * in its **default legacy** graphics mode loads no device font: it measures the
 * whole twelve-character wordmark at `32.5px` when asked for `48sp` — about
 * `0.68em` for twelve glyphs — and not even linearly in the size. So the
 * arithmetic is separated into [fitWordmarkSp] and driven here from the ratio
 * the Desktop contract actually recorded, which is a number a reader can check
 * against a stored file.
 *
 * The real platform face is measured too, in
 * `app/src/testDebug/.../WordmarkFitDeviceTest.kt`, which asks Robolectric for
 * `@GraphicsMode(NATIVE)` and gets `405px` for the same probe — `8.4375 em` for
 * Roboto Bold, wider than Collapse and therefore the stricter case.
 *
 * Collapse is a **narrow** display face; a bold platform sans is wider. Every
 * assertion below is therefore stated so that a wider face only strengthens it:
 * where the run already overruns in Collapse it overruns in Roboto too.
 */
class WordmarkFitTest {

    @Test
    fun `the wordmark spans the ems the pinned Desktop capture recorded`() {
        assertEquals(7.756f, DESKTOP_RUN_PX / DESKTOP_FONT_PX, 0.001f)
    }

    /**
     * The table in `docs/parity/empty-states.md`. If these move, that page is
     * stale.
     */
    @Test
    fun `the narrow phone columns cannot hold Desktop's floor`() {
        for ((screenDp, columnDp) in PHONE_COLUMNS) {
            val fitted = fitAt(columnDp)
            val run = fitted * COLLAPSE_EM
            assertTrue(
                "w${screenDp}dp: fitted ${fitted}sp runs ${run}dp in a ${columnDp}dp column",
                run <= columnDp + TOLERANCE,
            )
            val floorRun = WORDMARK_MIN_FONT_SIZE_DESKTOP.value * COLLAPSE_EM
            val floorFits = floorRun <= columnDp
            assertEquals(
                "w${screenDp}dp: Desktop's floor needs ${floorRun}dp of a ${columnDp}dp column",
                screenDp >= 411,
                floorFits,
            )
        }
    }

    @Test
    fun `the two narrowest columns fit only by falling below the floor`() {
        for ((screenDp, columnDp) in PHONE_COLUMNS.filter { it.first < 411 }) {
            val fitted = fitAt(columnDp)
            assertTrue(
                "w${screenDp}dp fitted to ${fitted}sp, which the old floor clamp would have " +
                    "raised to ${WORDMARK_MIN_FONT_SIZE_DESKTOP.value}sp and clipped",
                fitted < WORDMARK_MIN_FONT_SIZE_DESKTOP.value,
            )
        }
    }

    @Test
    fun `a wide column is capped rather than growing without bound`() {
        assertEquals(WORDMARK_MAX_FONT_SIZE.value, fitAt(2_000f), TOLERANCE)
        assertTrue(fitAt(391f) < WORDMARK_MAX_FONT_SIZE.value)
    }

    /** A larger font scale narrows the fit; it must never push the run out. */
    @Test
    fun `a raised font scale still fits, because the ratio is measured not assumed`() {
        // `fitWordmarkSp` works in the probe's own units, so a font scale that
        // widens the probe run shrinks the answer by the same factor.
        val columnDp = 300f
        val normal = fitWordmarkSp(PROBE_SP, PROBE_SP * COLLAPSE_EM, columnDp, WORDMARK_MAX_FONT_SIZE.value)
        val scaled = fitWordmarkSp(PROBE_SP, PROBE_SP * COLLAPSE_EM * 1.3f, columnDp, WORDMARK_MAX_FONT_SIZE.value)
        assertTrue("a wider run must yield a smaller size", scaled < normal)
        assertTrue(scaled * COLLAPSE_EM * 1.3f <= columnDp + TOLERANCE)
    }

    @Test
    fun `an unmeasured column or an unmeasurable font yields no lettering`() {
        assertEquals(0f, fitAt(0f), 0f)
        assertEquals(0f, fitAt(-10f), 0f)
        assertEquals(0f, fitWordmarkSp(PROBE_SP, 0f, 300f, WORDMARK_MAX_FONT_SIZE.value), 0f)
        assertEquals(0f, fitWordmarkSp(0f, 100f, 300f, WORDMARK_MAX_FONT_SIZE.value), 0f)
    }

    private fun fitAt(columnDp: Float): Float = fitWordmarkSp(
        probeSp = PROBE_SP,
        probeRunPx = PROBE_SP * COLLAPSE_EM,
        targetWidthPx = columnDp,
        maxSp = WORDMARK_MAX_FONT_SIZE.value,
    )

    private companion object {
        /** `contract.json` node 4: the visible fitted span. */
        const val DESKTOP_RUN_PX = 1052.0f
        const val DESKTOP_FONT_PX = 135.637f

        /** How many ems `HERMES AGENT` spans in Collapse. A platform sans is wider. */
        const val COLLAPSE_EM = DESKTOP_RUN_PX / DESKTOP_FONT_PX

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
