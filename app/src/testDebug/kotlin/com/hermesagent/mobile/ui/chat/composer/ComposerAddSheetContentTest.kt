package com.hermesagent.mobile.ui.chat.composer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import com.hermesagent.mobile.data.attachments.RecentImage
import com.hermesagent.mobile.data.attachments.RecentImageAccess
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sheet's own composition, without its dialog. The Recent images shortcut
 * has to sit above the acquisition rows that were there before it, and those
 * rows have to still be there, in their order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h400dp")
class ComposerAddSheetContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the image shortcut sits above files url and prompt snippets`() {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ComposerAddSheetContent(
                    recentImages = RecentImagesUiState(
                        access = RecentImageAccess.Granted,
                        images = listOf(RecentImage(id = 1L, displayName = "shot.png", mimeType = "image/png")),
                    ),
                    onAddRecentImage = {},
                    onRequestRecentImageAccess = {},
                    onPickPhotos = {},
                    onChooseFiles = {},
                    onChooseUrl = {},
                    onChooseSnippets = {},
                )
            }
        }

        val label = compose.onNodeWithTag("Recent images label").assertIsDisplayed()
            .fetchSemanticsNode().positionInRoot.y
        val choose = compose.onNodeWithTag("Choose photos action").assertIsDisplayed()
            .fetchSemanticsNode().positionInRoot.y
        val files = compose.onNodeWithContentDescription("Files. Attach a file from this device")
            .assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
        val url = compose.onNodeWithContentDescription("URL. Add a remote URL reference")
            .assertIsDisplayed().fetchSemanticsNode().positionInRoot.y
        // The last row sits below a short phone's fold, so it has no window
        // bounds to compare; composition order is what this test claims, and
        // `positionInRoot` is the coordinate that survives that clipping.
        val snippets = compose.onNodeWithContentDescription("Prompt snippets. Insert a reusable prompt")
            .assertExists().fetchSemanticsNode().positionInRoot.y

        assertTrue(
            "the Recent images label is not above the Choose photos row: $label vs $choose",
            label < choose,
        )
        assertTrue(
            "Choose photos is not above Files: $choose vs $files",
            choose < files,
        )
        assertTrue("Files is not above URL: $files vs $url", files < url)
        assertTrue("URL is not above Prompt snippets: $url vs $snippets", url < snippets)
    }
}
