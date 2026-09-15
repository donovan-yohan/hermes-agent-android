package com.hermesagent.mobile.ui.chat.composer

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.attachments.RecentImage
import com.hermesagent.mobile.data.attachments.RecentImageAccess
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h400dp")
class RecentImagesRailTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `loading renders its named placeholder row`() {
        setRail(RecentImagesUiState(loading = true))
        compose.onNodeWithContentDescription("Loading recent images").assertIsDisplayed()
        compose.onNodeWithTag("Recent images loading").assertIsDisplayed()
    }

    @Test
    fun `unknown offers access and photo picker without a rail`() {
        setRail(RecentImagesUiState())
        compose.onAllNodesWithTag("Recent images rail").assertCountEquals(0)
        compose.onNodeWithTag("Recent images access action").assertHasClickAction()
        compose.onNodeWithContentDescription("Choose photos. Open Android's photo picker").assertIsDisplayed()
    }

    @Test
    fun `denied offers photo access without a rail`() {
        setRail(RecentImagesUiState(access = RecentImageAccess.Denied))
        compose.onAllNodesWithTag("Recent images rail").assertCountEquals(0)
        compose.onNodeWithTag("Recent images access action").assertHasClickAction()
    }

    @Test
    fun `granted empty state names the device result`() {
        setRail(RecentImagesUiState(access = RecentImageAccess.Granted))
        compose.onNodeWithTag("Recent images empty").assertIsDisplayed()
        compose.onNodeWithTag("Recent images unavailable").assertDoesNotExist()
    }

    @Test
    fun `a refused read says so and still offers the picker`() {
        setRail(RecentImagesUiState(access = RecentImageAccess.Granted, failed = true))
        compose.onNodeWithTag("Recent images unavailable").assertIsDisplayed()
        compose.onNodeWithText("Recent images couldn't be read. Choose photos instead.").assertIsDisplayed()
        compose.onNodeWithTag("Recent images empty").assertDoesNotExist()
        compose.onNodeWithTag("Choose photos action").assertHasClickAction()
    }

    @Test
    fun `partial empty state describes the selected set`() {
        setRail(RecentImagesUiState(access = RecentImageAccess.Partial))
        compose.onNodeWithTag("Recent images empty").assertIsDisplayed()
        compose.onNodeWithText("Showing the photos you allowed.").assertIsDisplayed()
    }

    @Test
    fun `rail names images and adds each selected image once`() {
        val added = mutableListOf<Long>()
        setRail(populated(), onAdd = added::add)

        compose.onNodeWithTag("Recent images label").assertIsDisplayed()
        compose.onNodeWithTag("Recent images rail").assertIsDisplayed()
        val first = compose.onNodeWithTag("Recent image 1").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue(first.width >= min && first.height >= min)
        compose.onNodeWithContentDescription("Add first.png to the message").performClick()
        compose.onNodeWithContentDescription("Add second.png to the message").performClick()
        assertEquals(listOf(1L, 2L), added)
    }

    @Test
    fun `added and full images are disabled`() {
        var added = 0
        setRail(populated(added = setOf(1L)), onAdd = { added++ })
        // The state is in the name, not only in the ink: a screen reader hears it too.
        compose.onNodeWithContentDescription("first.png added to the message").assertIsDisplayed()
        compose.onNodeWithTag("Recent image 1").assertIsNotEnabled()
            .performTouchInput { click(center) }
        assertEquals(0, added)
    }

    @Test
    fun `full rail disables every thumbnail and names the next step`() {
        var added = 0
        setRail(populated(full = true), onAdd = { added++ })
        compose.onNodeWithText("This message is full. Remove an attachment to add another.").assertIsDisplayed()
        compose.onNodeWithTag("Recent image 2").assertIsNotEnabled()
            .performTouchInput { click(center) }
        assertEquals(0, added)
    }

    @Test
    fun `null thumbnail still has the stable touch target and accessible name`() {
        setRail(
            RecentImagesUiState(
                access = RecentImageAccess.Granted,
                images = listOf(RecentImage(9, "unreadable.png")),
            ),
        )
        val bounds = compose.onNodeWithTag("Recent image 9").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val min = with(compose.density) { 48.dp.toPx() }
        assertTrue(bounds.width >= min && bounds.height >= min)
        compose.onNodeWithContentDescription("Add unreadable.png to the message").assertIsDisplayed()
    }

    @Test
    fun `rail scrolls horizontally without moving its header`() {
        val rows = (1L..12L).map { RecentImage(it, "image-$it.png") }
        setRail(RecentImagesUiState(access = RecentImageAccess.Granted, images = rows))
        val headerTop = compose.onNodeWithTag("Recent images label").fetchSemanticsNode().boundsInWindow.top
        compose.onNodeWithTag("Recent images rail").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("Recent images rail").performScrollToNode(hasTestTag("Recent image 12"))
        compose.onNodeWithTag("Recent image 12").assertIsDisplayed()
        assertEquals(headerTop, compose.onNodeWithTag("Recent images label").fetchSemanticsNode().boundsInWindow.top)
    }

    @Test
    fun `add sheet keeps its rail open and retains existing action layout order`() {
        var opened = 0
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                ComposerAddControl(
                    onInsertText = {},
                    enabled = true,
                    recentImages = populated(),
                    onSheetOpened = { opened++ },
                )
            }
        }
        compose.onNodeWithTag("Composer add control").performClick()
        compose.onNodeWithTag("Recent images label").assertIsDisplayed()
        compose.onNodeWithTag("Recent image 1").performClick()
        compose.onNodeWithTag("Composer add sheet").assertIsDisplayed()
        val filesTop = compose.onNodeWithText("Files").fetchSemanticsNode().positionInRoot.y
        val urlTop = compose.onNodeWithText("URL").fetchSemanticsNode().positionInRoot.y
        val snippetsTop = compose.onNodeWithText("Prompt snippets").fetchSemanticsNode().positionInRoot.y
        assertTrue(
            compose.onNodeWithTag("Recent images label").fetchSemanticsNode().positionInRoot.y <
                filesTop,
        )
        assertTrue(filesTop < urlTop && urlTop < snippetsTop)
        compose.onNodeWithText("Files").performScrollTo().fetchSemanticsNode()
        compose.onNodeWithText("URL").performScrollTo().fetchSemanticsNode()
        compose.onNodeWithText("Prompt snippets").performScrollTo().fetchSemanticsNode()
        assertEquals(1, opened)
    }

    private fun populated(added: Set<Long> = emptySet(), full: Boolean = false) = RecentImagesUiState(
        access = RecentImageAccess.Granted,
        images = listOf(RecentImage(1, "first.png"), RecentImage(2, "second.png")),
        thumbnails = mapOf(1L to thumbnail(), 2L to thumbnail()),
        addedIds = added,
        full = full,
    )

    private fun setRail(state: RecentImagesUiState, onAdd: (Long) -> Unit = {}) {
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                RecentImagesSection(state, onAdd, onRequestAccess = {}, onChoosePhotos = {})
            }
        }
    }

    private fun thumbnail(): ImageBitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
}
