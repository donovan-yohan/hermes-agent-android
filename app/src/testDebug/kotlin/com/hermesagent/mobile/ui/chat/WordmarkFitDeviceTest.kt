package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.hermesagent.mobile.R
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The stacked wordmark against the **real loaded face**, at every phone width.
 *
 * `WordmarkFitTest` proves the arithmetic from the advances the shipped font
 * file reports. This proves the same thing through the font Android actually
 * loads from `res/font`, which is the one that has to fit — and, first of all,
 * that it loads at all.
 *
 * `@GraphicsMode(NATIVE)` is load-bearing and not decoration. Robolectric's
 * default legacy graphics has no font: it measures the whole twelve-character
 * wordmark at `32.5px` when asked for `48sp`, and not even linearly in the
 * size, so a fit asserted under it measures the stub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordmarkFitDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Fit(val screenDp: Int, val columnDp: Float, val fontSp: Float, val runDp: Float)

    /**
     * The resource resolves to a real typeface.
     *
     * `res/font/collapse_bold.otf` is a binary this repo carries. A truncated,
     * mis-flavoured or unparseable one fails here with an exception rather than
     * silently falling back to the platform sans on a device, which is a
     * difference nothing else in this suite could see.
     */
    @Test
    fun `the bundled Collapse Bold loads as a platform typeface`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNotNull(ResourcesCompat.getFont(context, R.font.collapse_bold))
    }

    /** The face is the wordmark's whatever the preset asks for. */
    @Test
    fun `the monospace-everything preset still sets the wordmark in Collapse`() {
        var cyberpunk: Float by mutableStateOf(-1f)
        var nous: Float by mutableStateOf(-1f)
        compose.setContent {
            val measurer = rememberTextMeasurer()
            HermesTheme(AppearanceSelection("cyberpunk", HermesThemeMode.Dark)) {
                cyberpunk = probeRun(measurer, HermesTheme.type.wordmark)
            }
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                nous = probeRun(measurer, HermesTheme.type.wordmark)
            }
        }
        compose.waitForIdle()
        assertTrue("nothing measured", cyberpunk > 0f)
        // `cyberpunk` sets both theme families to a monospace, so if the
        // wordmark took the preset's sans this run would be the wider of the
        // two rather than identical to `nous`'s.
        assertEquals(nous, cyberpunk, 0.5f)
    }

    @Test
    fun `the fitted wordmark fits the column on every phone width`() {
        for (fit in fits()) {
            assertTrue(
                "w${fit.screenDp}dp: the run measures ${fit.runDp}dp at ${fit.fontSp}sp, " +
                    "overrunning a ${fit.columnDp}dp column — it would be clipped at both ends",
                fit.runDp <= fit.columnDp + TOLERANCE_DP,
            )
        }
    }

    /**
     * Stacking is what makes Desktop's `--fit-min` affordable. On one line this
     * same suite asserted the opposite — that the two narrow columns could only
     * fit *below* the floor — and the fix was not a clamp but a second line.
     */
    @Test
    fun `every phone width now clears Desktop's floor without reaching the ceiling`() {
        for (fit in fits()) {
            assertTrue(
                "w${fit.screenDp}dp fitted to ${fit.fontSp}sp, below Desktop's " +
                    "${WORDMARK_MIN_FONT_SIZE_DESKTOP.value}sp floor",
                fit.fontSp >= WORDMARK_MIN_FONT_SIZE_DESKTOP.value,
            )
            assertTrue(fit.fontSp <= WORDMARK_MAX_FONT_SIZE.value + TOLERANCE_DP)
        }
    }

    /**
     * Two lines, one size, stacked — the layout claim, made on the string the
     * composable actually hands to `Text`.
     */
    @Test
    fun `the wordmark lays out as two stacked lines at one size`() {
        var layout: TextLayoutResult? = null
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                val measurer = rememberTextMeasurer()
                val style = HermesTheme.type.wordmark
                val size = 60.sp
                layout = measurer.measure(
                    text = AnnotatedString(INTRO_WORDMARK_LINES.joinToString("\n")),
                    style = style.copy(
                        fontSize = size,
                        lineHeight = (size.value * WORDMARK_LINE_HEIGHT).sp,
                    ),
                    softWrap = false,
                    maxLines = INTRO_WORDMARK_LINES.size,
                )
            }
        }
        compose.waitForIdle()

        val measured = requireNotNull(layout)
        assertEquals(INTRO_WORDMARK_LINES.size, measured.lineCount)
        assertTrue(
            "the second line is not below the first",
            measured.getLineTop(1) > measured.getLineTop(0),
        )
        // The wider line is the one that sets the block's width, and it is
        // `HERMES` — the fit measures that one for exactly this reason.
        val hermes = measured.getLineRight(0) - measured.getLineLeft(0)
        val agent = measured.getLineRight(1) - measured.getLineLeft(1)
        assertTrue("AGENT is not narrower than HERMES", agent < hermes)
    }

    /**
     * **Font scale is not tested here, and could not be.** Robolectric does not
     * plumb one into text layout: a `Density(platform, 1.5f)` provided through
     * `LocalDensity`, and one handed straight to a constructed `TextMeasurer`
     * as its `defaultDensity`, both return exactly the size the unscaled case
     * returns. A test written against either would have divided and multiplied
     * by the same factor and proved nothing.
     *
     * The invariant itself — a run that measures wider yields a proportionally
     * smaller fit, which is what a raised scale does — is proved in
     * `WordmarkFitTest.a raised font scale still fits, because the ratio is
     * measured not assumed`, where the widened run is supplied directly and no
     * platform has to cooperate. The real device is the third leg:
     * `docs/parity/visual/empty-states/empty-chat-intro-w320dp-dark/` is the
     * narrowest column at the emulator's own scale of 1.0.
     */
    private fun fits(): List<Fit> {
        var measured by mutableStateOf(emptyList<Fit>())
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = HermesTheme.type.wordmark
                measured = PHONE_WIDTHS_DP.map { screenDp ->
                    with(density) {
                        val columnDp = screenDp.dp - INTRO_SPLASH_GUTTER * 2 - WORDMARK_INSET
                        val size = fitWordmarkFontSize(
                            measurer,
                            INTRO_WORDMARK_LINES,
                            style,
                            columnDp.toPx(),
                        )
                        val runPx = INTRO_WORDMARK_LINES.maxOf { line ->
                            measurer.measure(
                                text = AnnotatedString(line),
                                style = style.copy(fontSize = size),
                                softWrap = false,
                                maxLines = 1,
                            ).size.width.toFloat()
                        }
                        Fit(
                            screenDp = screenDp,
                            columnDp = columnDp.value,
                            fontSp = size.value,
                            runDp = runPx.toDp().value,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("no widths measured", measured.size == PHONE_WIDTHS_DP.size)
        return measured
    }

    private fun probeRun(
        measurer: androidx.compose.ui.text.TextMeasurer,
        style: androidx.compose.ui.text.TextStyle,
    ): Float = measurer.measure(
        text = AnnotatedString(INTRO_WORDMARK_LINES.first()),
        style = style.copy(fontSize = WORDMARK_PROBE_FONT_SIZE),
        softWrap = false,
        maxLines = 1,
    ).size.width.toFloat()

    private companion object {
        /** The narrowest supported phone, the common baseline, and a large one. */
        val PHONE_WIDTHS_DP = listOf(320, 360, 411)

        /** A device pixel of rounding, generously in dp. */
        const val TOLERANCE_DP = 1f
    }
}
