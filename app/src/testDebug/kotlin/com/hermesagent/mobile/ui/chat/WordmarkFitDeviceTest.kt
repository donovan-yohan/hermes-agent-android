package com.hermesagent.mobile.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wordmark fit against the **real platform face**, at every phone width.
 *
 * `WordmarkFitTest` proves the arithmetic from the ratio the pinned Desktop
 * capture recorded. This proves the same thing in the font Android actually
 * draws, which is the one that has to fit.
 *
 * `@GraphicsMode(NATIVE)` is load-bearing and not decoration. Robolectric's
 * default legacy graphics has no font: it measures the whole twelve-character
 * wordmark at `32.5px` when asked for `48sp`, and not even linearly in the
 * size, so a fit asserted under it measures the stub. Under NATIVE the same
 * probe measures `405px` — `8.44 em` for Roboto Bold at `0.08em` tracking,
 * against Collapse's `7.756 em`. The platform face is the wider of the two,
 * which is why Desktop's `2.75rem` floor is even less reachable here than the
 * Desktop-derived table suggests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WordmarkFitDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Fit(val screenDp: Int, val columnDp: Float, val fontSp: Float, val runDp: Float)

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
     * The reason there is no floor. If this ever fails, either the type scale
     * moved or the platform face did, and the table in
     * `docs/parity/empty-states.md` needs recomputing.
     */
    @Test
    fun `the two narrow phone columns cannot hold Desktop's floor`() {
        for (fit in fits().filter { it.screenDp < 411 }) {
            assertTrue(
                "w${fit.screenDp}dp fitted to ${fit.fontSp}sp; a ${WORDMARK_MIN_FONT_SIZE_DESKTOP.value}sp " +
                    "floor would have raised it and clipped the run",
                fit.fontSp < WORDMARK_MIN_FONT_SIZE_DESKTOP.value,
            )
        }
    }

    @Test
    fun `the widest phone column clears the floor without reaching the ceiling`() {
        val widest = fits().last()
        assertTrue(widest.fontSp > WORDMARK_MIN_FONT_SIZE_DESKTOP.value)
        assertTrue(widest.fontSp < WORDMARK_MAX_FONT_SIZE.value)
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
                            INTRO_WORDMARK,
                            style,
                            columnDp.toPx(),
                        )
                        val runPx = measurer.measure(
                            text = AnnotatedString(INTRO_WORDMARK),
                            style = style.copy(fontSize = size),
                            softWrap = false,
                            maxLines = 1,
                        ).size.width.toFloat()
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

    private companion object {
        /** The narrowest supported phone, the common baseline, and a large one. */
        val PHONE_WIDTHS_DP = listOf(320, 360, 411)

        /** A device pixel of rounding, generously in dp. */
        const val TOLERANCE_DP = 1f
    }
}
