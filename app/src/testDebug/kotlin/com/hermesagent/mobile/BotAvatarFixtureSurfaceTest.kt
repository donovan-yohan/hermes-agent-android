package com.hermesagent.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotAvatarFixtureSurfaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightSurfacePaintsAndHeaderClearsSystemBars() = verify(HermesThemeMode.Light)
    @Test fun darkSurfacePaintsAndHeaderClearsSystemBars() = verify(HermesThemeMode.Dark)

    private fun verify(mode: HermesThemeMode) {
        var surface = 0
        compose.setContent {
            HermesTheme(AppearanceSelection("mono", mode)) {
                surface = HermesTheme.tokens.chatSurface.toArgb()
                // Deliberately larger than fixture padding: catches missing insets.
                BotAvatarParityContent("bot-avatar-loaded", WindowInsets(top = 120, bottom = 80))
            }
        }
        compose.waitForIdle()
        val header = compose.onNodeWithText("Avatar").fetchSemanticsNode().boundsInWindow
        val decor = compose.activity.window.decorView
        assertTrue("header must clear status area", header.top >= 120)
        assertTrue("header inside safe viewport", header.bottom <= decor.height - 80)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { decor.draw(Canvas(bitmap)) }
        // Blank margin next to the header, not text or an avatar pixel.
        assertEquals("semantic surface must be painted", surface, bitmap.getPixel(2, header.center.y.toInt()))
        bitmap.recycle()
    }
}
