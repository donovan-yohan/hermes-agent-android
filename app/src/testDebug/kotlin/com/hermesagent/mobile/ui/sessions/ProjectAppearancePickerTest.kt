package com.hermesagent.mobile.ui.sessions

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hermesagent.mobile.data.gateway.ProjectOperation
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.ui.chat.ProjectActionDialog
import com.hermesagent.mobile.ui.chat.ProjectActionPage
import com.hermesagent.mobile.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Desktop 41dfcf2c ColorSwatches + ProjectAppearancePicker, through the production dialog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectAppearancePickerTest {
    @get:Rule val compose = createComposeRule()
    private val color = "hsl(30 68% 58%)"
    private val project = ProjectSummary("p", "Project", "/workspace/project", color = color, icon = "rocket")
    private val calls = mutableListOf<ProjectOperation>()
    private val busy = mutableStateOf(false)
    private fun mount() {
        compose.setContent { HermesTheme(AppearanceSelection("mono", HermesThemeMode.Dark)) {
            ProjectActionsDialog(ProjectActionDialog(7, project, ProjectActionPage.Appearance, busy.value), null,
                ProjectActions(execute = { token, operation -> assertEquals(7L, token); calls += operation }))
        } }
        compose.waitForIdle()
    }

    @Test fun swatchesThenClearThenIconsWithOrderedNonoverlappingTouchTargets() {
        mount()
        val swatches = ProjectAppearance.swatches.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
        val clear = compose.onNodeWithText("No color").fetchSemanticsNode().boundsInRoot
        val icons = ProjectAppearance.icons.keys.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot }
        assertTrue("Swatch grid precedes No color", swatches.maxOf { it.bottom } <= clear.top)
        assertTrue("No color precedes icons", clear.bottom <= icons.minOf { it.top })
        for (grid in listOf(swatches, icons)) {
            grid.forEach { assertTrue(it.width >= 48f); assertTrue(it.height >= 48f) }
            grid.zipWithNext().forEach { (a, b) -> assertTrue("Source order is row-major", a.top < b.top || (a.top == b.top && a.left < b.left)) }
            grid.forEachIndexed { i, a -> grid.drop(i + 1).forEach { b -> assertFalse("Touch targets overlap", a.overlaps(b)) } }
        }
    }

    @Test fun selectedSemanticsAndLiteralActionsSurviveBusy() {
        mount()
        compose.onNodeWithContentDescription(color).assertIsSelected()
        compose.onNodeWithContentDescription("rocket").assertIsSelected()
        compose.onNodeWithContentDescription(ProjectAppearance.swatches.first()).assertIsNotSelected().performClick()
        compose.onNodeWithText("No color").performClick()
        compose.onNodeWithContentDescription("rocket").performScrollTo().performClick()
        assertEquals(listOf(ProjectOperation.Color("hsl(0 68% 58%)"), ProjectOperation.Color(null), ProjectOperation.Icon(null)), calls)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithContentDescription(color).assertIsNotEnabled()
        compose.onNodeWithContentDescription("rocket").assertIsNotEnabled().performClick()
        assertEquals(3, calls.size)
    }

    private fun bitmap(): Bitmap {
        lateinit var result: Bitmap
        compose.runOnIdle {
            val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
            val root = (field.get(global) as List<*>).last() as android.view.View
            result = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(result))
        }
        return result
    }

    @Test fun swatchesPaintSmallFilledCirclesAndSelectedOuterRing() {
        mount()
        val selected = compose.onNodeWithContentDescription(color).fetchSemanticsNode().boundsInWindow
        val unselected = compose.onNodeWithContentDescription("hsl(0 68% 58%)").fetchSemanticsNode().boundsInWindow
        val image = bitmap()
        try {
            for ((bounds, value) in listOf(selected to color, unselected to "hsl(0 68% 58%)")) {
                val ink = ProjectAppearance.color(value, androidx.compose.ui.graphics.Color.Unspecified).toArgb()
                val x = bounds.center.x.toInt(); val y = bounds.center.y.toInt()
                assertEquals("Filled center, not a glyph", ink, image.getPixel(x, y))
                assertEquals(ink, image.getPixel(x + 7, y))
                assertNotEquals("20dp fill remains small inside 48dp hit target", ink, image.getPixel(x + 16, y))
                if (value == color) {
                    assertNotEquals("2dp elevated gap", ink, image.getPixel(x + 11, y))
                    assertEquals("Outer selection ring uses swatch color", ink, image.getPixel(x + 13, y))
                } else assertNotEquals("Unselected has no ring", ink, image.getPixel(x + 13, y))
            }
        } finally { image.recycle() }
    }

    @Test fun selectedIconPaintUsesProjectTint() {
        mount()
        val node = compose.onNodeWithContentDescription("rocket").performScrollTo()
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val image = bitmap()
        try {
            var tintedPixels = 0
            // A 13sp codicon can be entirely antialiased; compare chroma, not full-coverage pixels.
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val pixel = image.getPixel(x, y)
                val red = (pixel ushr 16) and 255
                val green = (pixel ushr 8) and 255
                val blue = pixel and 255
                if (red - blue > 40 && kotlin.math.abs((red - blue) - 2 * (green - blue)) <= 3) tintedPixels++
            }
            assertTrue("Selected codicon must actually paint the project tint", tintedPixels > 0)
        } finally { image.recycle() }
    }
}
