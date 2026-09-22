package com.hermesagent.mobile.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeSystemBarsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `system bars follow rendered skin rather than requested light mode`() {
        val skin = BuiltinThemes.Cyberpunk.copy(
            name = "dark-skin",
            darkColors = BuiltinThemes.Cyberpunk.colors,
        )
        val selection = mutableStateOf(AppearanceSelection(skin.name, HermesThemeMode.Light))
        val window = compose.activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        compose.runOnIdle {
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
        compose.setContent { HermesTheme(selection.value, customThemes = listOf(skin)) {} }
        compose.runOnIdle {
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
            selection.value = AppearanceSelection("nous", HermesThemeMode.Light)
        }
        compose.runOnIdle {
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
    }
}
