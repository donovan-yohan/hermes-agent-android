package com.hermesagent.mobile.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** F3: loading must paint the semantic fill/ink pair even when interaction is disabled.
 * Actual native production Canvas over PendingCard's widgetSurface, not a token-only check.
 * No animation-liveness, physical accessibility or backend claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrimaryButtonLoadingPaintTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun assertLoadingPaint(mode: HermesThemeMode) {
        var fill = Color.Unspecified
        var ink = Color.Unspecified
        compose.setContent { HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, mode)) {
            fill = HermesTheme.tokens.accent
            ink = HermesTheme.tokens.filledActionInk
            Column(Modifier.background(HermesTheme.tokens.widgetSurface)) {
                PrimaryButton("Confirm and continue", {}, Modifier.testTag("loading"), enabled = false, loading = true)
            }
        } }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("loading").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Confirm and continue").assertExists()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        var paintedFill = 0
        var inkPixels = 0
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                paintedFill = bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + bounds.height * 0.12f).toInt())
                val expected = ink.toArgb()
                for (y in (bounds.top + bounds.height * 0.2f).toInt() until (bounds.bottom - bounds.height * 0.2f).toInt()) {
                    for (x in (bounds.left + bounds.width * 0.2f).toInt() until (bounds.right - bounds.width * 0.2f).toInt()) {
                        val pixel = bitmap.getPixel(x, y)
                        if (listOf(16, 8, 0).all { shift -> abs(((pixel ushr shift) and 255) - ((expected ushr shift) and 255)) <= 1 }) inkPixels++
                    }
                }
            } finally { bitmap.recycle() }
        }
        println("F3 $mode actualFill=${Integer.toHexString(paintedFill)} pairedFill=${Integer.toHexString(fill.toArgb())} pairedInkPixels=$inkPixels")
        assertEquals("$mode active loading must retain the fill paired with its only visible progress ink", fill.toArgb(), paintedFill)
        assertTrue("$mode progress must paint its paired ink", inkPixels > 0)
    }

    @Test fun `disabled loading paints paired fill and progress ink in light mode`() = assertLoadingPaint(HermesThemeMode.Light)
    @Test fun `disabled loading paints paired fill and progress ink in dark mode`() = assertLoadingPaint(HermesThemeMode.Dark)

    @Test fun `loading disables focused keyboard and pointer dispatch with retained action meaning`() {
        val loading = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var clicks = 0
        lateinit var inputMode: InputModeManager
        compose.setContent { HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Light)) {
            inputMode = LocalInputModeManager.current
            PrimaryButton("Confirm and continue", { clicks++ }, Modifier.testTag("action"), enabled = enabled.value, loading = loading.value)
        } }
        val action = compose.onNodeWithTag("action")
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        action.performSemanticsAction(SemanticsActions.RequestFocus)
        action.assertIsFocused()
        compose.runOnIdle { enabled.value = false; loading.value = true }
        action.assertIsNotEnabled().performClick()
        action.performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        compose.onNodeWithContentDescription("Confirm and continue").assertExists()
        compose.runOnIdle { assertEquals(0, clicks); enabled.value = true }
        action.assertIsNotEnabled().performClick()
        action.performKeyInput { pressKey(Key.Enter); pressKey(Key.Spacebar) }
        compose.runOnIdle { assertEquals(0, clicks); loading.value = false }
        action.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
