package com.hermesagent.mobile.ui.appearance

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.hermesagent.mobile.ui.AppearanceActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Appearance's `Intro Splash` row.
 *
 * Desktop's is a `ListRow` with an Off/On `SegmentedControl`
 * (`apps/desktop/src/app/settings/appearance-settings.tsx:715-736` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), copy at `i18n/en.ts:588-589` and
 * `:44-45`. Without this test, deleting the row leaves the suite green: nothing
 * else renders it and nothing else calls `onSetIntroSplash`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class IntroSplashSettingJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the row carries Desktop's title and description verbatim`() {
        launch(introSplash = true)
        scrollToRow()

        compose.onNodeWithText("Intro Splash").assertIsDisplayed()
        compose.onNodeWithText("The wordmark and prompt shown on an empty chat.").assertIsDisplayed()
        compose.onNodeWithText("On").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
    }

    @Test
    fun `turning the splash off reports it once, with false`() {
        val choices = mutableListOf<Boolean>()
        launch(introSplash = true, onSetIntroSplash = choices::add)
        scrollToRow()

        compose.onNodeWithContentDescription("Intro Splash off").performClick()
        compose.waitForIdle()

        assertEquals(listOf(false), choices)
    }

    @Test
    fun `turning the splash back on reports it with true`() {
        val choices = mutableListOf<Boolean>()
        launch(introSplash = false, onSetIntroSplash = choices::add)
        scrollToRow()

        compose.onNodeWithContentDescription("Intro Splash on").performClick()
        compose.waitForIdle()

        assertEquals(listOf(true), choices)
    }

    /**
     * The row sits under the whole skin list, which is Desktop's own ordering —
     * `Intro Splash` follows the theme picker there too. In a `LazyColumn` that
     * means it is not composed until the list is scrolled, so every assertion
     * here goes through the scroll first. That it is reachable at all is part
     * of what this test proves.
     */
    private fun scrollToRow() {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Intro Splash"))
        compose.waitForIdle()
    }

    private fun launch(
        introSplash: Boolean,
        onSetIntroSplash: (Boolean) -> Unit = {},
    ) {
        val selection = AppearanceSelection("nous", HermesThemeMode.Dark)
        compose.setContent {
            HermesTheme(selection) {
                AppearanceScreen(
                    selection = selection,
                    actions = AppearanceActions(onSetIntroSplash = onSetIntroSplash),
                    introSplash = introSplash,
                )
            }
        }
        compose.waitForIdle()
    }
}
