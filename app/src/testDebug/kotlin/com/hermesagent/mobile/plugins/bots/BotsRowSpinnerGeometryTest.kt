package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * The roster row's loading glyph geometry.
 *
 * These pin the two properties that were wrong while the indicator was
 * Material's `CircularProgressIndicator`: it must occupy a fixed square of its
 * own, and every frame must turn about that square's centre rather than about
 * some larger ring's centre. Each case drives a manual phase, so no run depends
 * on wall-clock animation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BotsRowSpinnerGeometryTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * The spinner's measured square is the same at every phase.
     *
     * A frame that re-measured per phase — or that let a child overflow the
     * slot — would move here, which is exactly the bug being guarded.
     */
    @Test
    fun `the spinner occupies one fixed square at every phase`() {
        var phase by mutableFloatStateOf(0f)
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                Box(Modifier.size(HOST_SIZE).testTag(HOST_TAG), contentAlignment = Alignment.Center) {
                    BotRowSpinnerFrame(
                        sizeDp = BOT_ROW_SPINNER_SIZE,
                        phase = phase,
                        color = HermesTheme.tokens.accent,
                        strokeWidthDp = STROKE_DP,
                        modifier = Modifier.testTag(SPINNER_TAG),
                    )
                }
            }
        }
        compose.waitForIdle()

        val host = compose.onNodeWithTag(HOST_TAG).getUnclippedBoundsInRoot()
        for (next in phases()) {
            compose.runOnIdle { phase = next }
            val bounds = compose.onNodeWithTag(SPINNER_TAG).assertIsDisplayed()
                .getUnclippedBoundsInRoot()

            assertEquals("phase $next width", BOT_ROW_SPINNER_SIZE.value, (bounds.right - bounds.left).value, TOLERANCE_DP)
            assertEquals("phase $next height", BOT_ROW_SPINNER_SIZE.value, (bounds.bottom - bounds.top).value, TOLERANCE_DP)
            // Centred in its host: equal gaps on both sides, every phase. The
            // ring this replaced sat centred on a 40dp circle inside an 18dp
            // slot, so its visible arc was off-centre by 11dp.
            val horizontalGap = ((host.right - host.left).value - (bounds.right - bounds.left).value) / 2f
            assertEquals("phase $next left gap", horizontalGap, (bounds.left - host.left).value, TOLERANCE_DP)
            assertEquals("phase $next top gap", horizontalGap, (bounds.top - host.top).value, TOLERANCE_DP)
        }
    }

    /**
     * Every rotated frame stays inside the square it is measured in.
     *
     * This is the property the clipped Material ring failed: a 40dp ring in an
     * 18dp slot drew outside its own bounds. The arc's endpoints are computed
     * at the same radius and turned with the same rotation the composable
     * applies, so containment is proven from the geometry rather than from
     * pixels.
     */
    @Test
    fun `every phase keeps the arc inside its own square`() {
        val size = BOT_ROW_SPINNER_SIZE.value
        // A stroke straddles the path, so the drawn arc's outer edge is the
        // path radius plus half a stroke.
        val outerRadius = (size - STROKE_DP.value) / 2f + STROKE_DP.value / 2f
        val center = size / 2f

        for (phase in phases()) {
            val turn = phase * 360f
            for (angle in listOf(BOT_ROW_SPINNER_START_DEGREES, ARC_END)) {
                val radians = Math.toRadians(angle.toDouble())
                val point = rotateAbout(
                    point = Offset(
                        center + outerRadius * cos(radians).toFloat(),
                        center + outerRadius * sin(radians).toFloat(),
                    ),
                    center = center,
                    degrees = turn,
                )
                assertInsideSquare(point, size, phase, angle)
            }
        }
    }

    private fun phases(): List<Float> = listOf(0f, 0.13f, 0.25f, 0.5f, 0.75f, 0.97f)

    private fun rotateAbout(point: Offset, center: Float, degrees: Float): Offset {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = (point.x - center).toDouble()
        val dy = (point.y - center).toDouble()
        val cos = cos(radians)
        val sin = sin(radians)
        return Offset(
            (center + dx * cos - dy * sin).toFloat(),
            (center + dx * sin + dy * cos).toFloat(),
        )
    }

    private fun assertInsideSquare(point: Offset, size: Float, phase: Float, angle: Float) {
        // A hair of slack for float rounding at exactly 0/90/180/270.
        val slack = 1e-3f
        for ((axis, value) in listOf("x" to point.x, "y" to point.y)) {
            assertTrue(
                "phase $phase angle $angle escaped the square on $axis: $value",
                value >= -slack && value <= size + slack,
            )
        }
    }

    private companion object {
        const val HOST_TAG = "spinner host"
        const val SPINNER_TAG = "spinner frame"
        val HOST_SIZE = 120.dp
        val STROKE_DP = 2.dp
        /** The arc's far end, read off the production constants. */
        val ARC_END = BOT_ROW_SPINNER_START_DEGREES + BOT_ROW_SPINNER_SWEEP_DEGREES
        const val TOLERANCE_DP = 0.5f
    }
}
