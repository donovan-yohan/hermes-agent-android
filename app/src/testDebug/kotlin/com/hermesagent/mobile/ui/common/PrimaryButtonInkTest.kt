package com.hermesagent.mobile.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import com.hermesagent.mobile.ui.theme.HermesTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * `PrimaryButton`'s label ink, read off the pixels it actually paints.
 *
 * The theme test next door proves the theme *resolves* a legible ink on every
 * fill; this one proves the button *paints* it. Those are different failures. A
 * component that ignored its tokens entirely would pass every assertion over
 * `HermesTokens` — which is precisely the shape #140 had, where the fill came
 * from a parameter and the ink from a constant.
 *
 * The default light skin is the discriminating case the issue measured: both
 * fills carry white there, while `accentForeground` — the ink the button used
 * to paint on both — is `#1F2328`. Counting near-white against near-black
 * pixels inside each button's box therefore separates the fix from the defect
 * without depending on where any single glyph lands.
 *
 * [GraphicsMode.Mode.NATIVE] because Robolectric's legacy canvas draws nothing a
 * pixel read could distinguish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrimaryButtonInkTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var tokens: HermesTokens

    private fun render(preset: String = BuiltinThemes.DEFAULT_NAME, mode: HermesThemeMode = HermesThemeMode.Light) {
        compose.setContent {
            HermesTheme(AppearanceSelection(preset, mode)) {
                tokens = HermesTheme.tokens
                Column {
                    PrimaryButton(
                        label = "Save",
                        onClick = {},
                        modifier = Modifier.testTag(ACCENT_TAG),
                    )
                    PrimaryButton(
                        label = "Delete",
                        onClick = {},
                        modifier = Modifier.testTag(DESTRUCTIVE_TAG),
                        variant = FilledActionVariant.Destructive,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * A synchronous draw of the window, then the pixels inside a node's box —
     * `captureToImage` never fires under Robolectric, which is why the ink
     * tests in `ui/chat` read the decor view the same way.
     *
     * The sampled window is the node's middle 60%: the label is centred, so
     * every glyph pixel is in there, and the box's rounded corners — which show
     * the page behind the fill — are not.
     */
    private fun pixelsIn(tag: String): List<Color> {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))

        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val insetX = bounds.width * 0.2f
        val insetY = bounds.height * 0.2f
        val xRange = (bounds.left + insetX).toInt()..(bounds.right - insetX).toInt()
        val yRange = (bounds.top + insetY).toInt()..(bounds.bottom - insetY).toInt()

        val out = mutableListOf<Color>()
        for (y in yRange) {
            for (x in xRange) {
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    out += Color(bitmap.getPixel(x, y))
                }
            }
        }
        return out
    }

    /** How many sampled pixels are within [tolerance] of [ink] on every channel. */
    private fun List<Color>.countNear(ink: Color, tolerance: Float = 0.16f): Int =
        count { pixel ->
            abs(pixel.red - ink.red) <= tolerance &&
                abs(pixel.green - ink.green) <= tolerance &&
                abs(pixel.blue - ink.blue) <= tolerance
        }

    /**
     * The fill, read from the button's top padding — above the label and inside
     * the horizontal middle, so no glyph and no rounded corner reaches it.
     */
    private fun fillOf(tag: String): Color {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))

        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val x = (bounds.left + bounds.width / 2f).toInt()
        val y = (bounds.top + bounds.height * 0.12f).toInt()
        return Color(bitmap.getPixel(x, y))
    }

    private val accentTag get() = ACCENT_TAG
    private val destructiveTag get() = DESTRUCTIVE_TAG

    @Test
    fun `each variant paints its own token's fill`() {
        render()

        assertEquals(
            "the accent variant must paint tokens.accent",
            tokens.accent.toArgbHex(),
            fillOf(accentTag).toArgbHex(),
        )
        assertEquals(
            "the destructive variant must paint tokens.destructive",
            tokens.destructive.toArgbHex(),
            fillOf(destructiveTag).toArgbHex(),
        )
        assertNotEquals(
            "the two fills are the same colour, so this render cannot tell the variants apart",
            tokens.accent.toArgbHex(),
            tokens.destructive.toArgbHex(),
        )
    }

    @Test
    fun `the accent label is the paired ink, not the fill-independent accentForeground`() {
        render()

        val painted = pixelsIn(accentTag)
        val paired = painted.countNear(tokens.filledActionInk)
        val legacy = painted.countNear(tokens.accentForeground)

        assertTrue("no label pixels found for the accent variant", paired > 0)
        assertTrue(
            "the accent label is ${tokens.accentForeground.toArgbHex()} (the pre-#140 ink), " +
                "not ${tokens.filledActionInk.toArgbHex()}: matched $legacy against $paired",
            paired > legacy,
        )
    }

    @Test
    fun `the destructive label is the paired ink, not the fill-independent accentForeground`() {
        render()

        val painted = pixelsIn(destructiveTag)
        val paired = painted.countNear(tokens.destructiveActionInk)
        val legacy = painted.countNear(tokens.accentForeground)

        assertTrue("no label pixels found for the destructive variant", paired > 0)
        assertTrue(
            "the destructive label is ${tokens.accentForeground.toArgbHex()} (the pre-#140 ink), " +
                "not ${tokens.destructiveActionInk.toArgbHex()}: matched $legacy against $paired",
            paired > legacy,
        )
    }

    @Test
    fun `the accent and destructive labels differ wherever their paired inks do`() {
        // `cyberpunk` dark is the skin whose two filled actions carry genuinely
        // different inks: bright-green accent with near-black, red destructive
        // with Desktop's `#000A00`. One fixed foreground cannot produce both.
        render(preset = "cyberpunk", mode = HermesThemeMode.Dark)

        assertNotEquals(tokens.filledActionInk, tokens.destructiveActionInk)

        val accentPixels = pixelsIn(accentTag)
        val destructivePixels = pixelsIn(destructiveTag)

        assertTrue(
            "the accent button paints ${tokens.filledActionInk.toArgbHex()}",
            accentPixels.countNear(tokens.filledActionInk) > 0,
        )
        assertTrue(
            "the destructive button paints ${tokens.destructiveActionInk.toArgbHex()}",
            destructivePixels.countNear(tokens.destructiveActionInk) > 0,
        )
    }

    private companion object {
        const val ACCENT_TAG = "primary-button-accent"
        const val DESTRUCTIVE_TAG = "primary-button-destructive"
    }
}

/** `#aarrggbb`, matching the theme tests' own spelling. */
private fun Color.toArgbHex(): String {
    fun channel(value: Float) =
        (value * 255f).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${channel(alpha)}${channel(red)}${channel(green)}${channel(blue)}"
}
