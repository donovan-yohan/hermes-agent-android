package com.hermesagent.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.hermesagent.mobile.data.themes.GatewayTheme
import com.hermesagent.mobile.data.themes.GatewayThemesState
import com.hermesagent.mobile.data.themes.GatewayThemesStatus
import com.hermesagent.mobile.ui.appearance.AppearanceScreen
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AppearanceThemesJourneyTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `builtins remain selectable while gateway themes are idle`() {
        val selections = mutableListOf<String>()
        launch(GatewayThemesState(status = GatewayThemesStatus.Idle), selections::add)

        compose.onNodeWithContentDescription("Nous skin. GitHub chrome, Nous blue accent")
            .performClick()
        compose.waitForIdle()
        assertEquals(listOf("nous"), selections)
    }

    @Test
    fun `builtins remain selectable while gateway themes are unreachable`() {
        val selections = mutableListOf<String>()
        launch(GatewayThemesState(status = GatewayThemesStatus.Unreachable), selections::add)
        compose.onNodeWithContentDescription("Nous skin. GitHub chrome, Nous blue accent")
            .performClick()
        compose.waitForIdle()
        assertEquals(listOf("nous"), selections)
    }

    @Test
    fun `ready gateway theme renders its row and reports its name`() {
        val selections = mutableListOf<String>()
        launch(GatewayThemesState(themes = listOf(customTheme()), status = GatewayThemesStatus.Ready), selections::add)
        scrollToContentDescription("Harbor skin. A calm Gateway palette.")

        compose.onNodeWithContentDescription("Harbor skin. A calm Gateway palette.").assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf("harbor"), selections)
    }

    @Test
    fun `failure explains the result and retry reports once`() {
        var retries = 0
        launch(GatewayThemesState(status = GatewayThemesStatus.Unusable), onRetry = { retries += 1 })
        scrollTo("The Gateway returned themes this app could not read.")

        compose.onNodeWithText("The Gateway returned themes this app could not read.").assertIsDisplayed()
        scrollToContentDescription("Retry Gateway themes")
        compose.onNodeWithContentDescription("Retry Gateway themes").performClick()
        compose.waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun `cached theme remains selectable before Dashboard connects`() {
        assertCustomThemeSelectable(GatewayThemesStatus.Idle)
    }

    @Test
    fun `socket theme remains selectable when Dashboard themes are unsupported`() {
        assertCustomThemeSelectable(GatewayThemesStatus.Unsupported)
        compose.onNodeWithText(GatewayThemesStatus.Unsupported.productCopy()).assertDoesNotExist()
    }

    private fun assertCustomThemeSelectable(status: GatewayThemesStatus) {
        val selections = mutableListOf<String>()
        launch(GatewayThemesState(themes = listOf(customTheme()), status = status), selections::add)
        scrollToContentDescription("Harbor skin. A calm Gateway palette.")
        compose.onNodeWithContentDescription("Harbor skin. A calm Gateway palette.")
            .assertIsDisplayed().performClick()
        compose.waitForIdle()
        assertEquals(listOf("harbor"), selections)
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
        compose.waitForIdle()
    }

    private fun scrollToContentDescription(description: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(description))
        compose.waitForIdle()
    }

    private fun launch(
        state: GatewayThemesState,
        onSelectTheme: (String) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark), customThemes = state.themes.map { it.preset }) {
                AppearanceScreen(
                    selection = AppearanceSelection("nous", HermesThemeMode.Dark),
                    gatewayThemes = state,
                    actions = AppearanceActions(onSelectTheme = onSelectTheme, onRetryThemes = onRetry),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun customTheme(): GatewayTheme {
        val preset = BuiltinThemes.ALL.first().copy(
            name = "harbor",
            label = "Harbor",
            description = "A calm Gateway palette.",
        )
        return GatewayTheme("harbor", "Harbor", "A calm Gateway palette.", preset)
    }
}
