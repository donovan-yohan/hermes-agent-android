package com.hermesagent.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesagent.mobile.data.session.ReasoningActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Android Canvas evidence; not emulator or physical-device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActivityLabelPaintBoundsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `thinking shimmer keeps resting glyph geometry above expanded reasoning`() {
        val configuration = mutableStateOf(PaintConfiguration())
        val owner = PaintLifecycleOwner()
        var background = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val config = configuration.value
            CompositionLocalProvider(
                LocalDensity provides Density(LocalDensity.current.density, config.fontScale),
                LocalLifecycleOwner provides owner,
            ) {
                HermesTheme(AppearanceSelection(config.theme, config.mode)) {
                    background = HermesTheme.tokens.chatSurface.toArgb()
                    Box(Modifier.fillMaxSize().background(HermesTheme.tokens.chatSurface)) {
                        Transcript(
                            entries = listOf(ReasoningActivity("synthetic-thinking", BODY, ToolState.Running, elapsedSeconds = 1.0)),
                            listState = rememberLazyListState(),
                            modifier = Modifier.width(config.width.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        val failures = mutableListOf<String>()
        for (config in listOf(
            PaintConfiguration(),
            PaintConfiguration(width = 180),
            PaintConfiguration(width = 180, fontScale = 2f),
            PaintConfiguration(width = 180, fontScale = 2f, theme = "cyberpunk", mode = HermesThemeMode.Light),
        )) {
            compose.runOnIdle {
                configuration.value = config
                owner.registry.currentState = Lifecycle.State.STARTED
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()

            // The actual motion gate is at rest while backgrounded. Resuming
            // starts the same label's real shimmer, not a test-only brush. Capture
            // the initially expanded row before any disclosure interaction so a
            // toggle's recomposition cannot mask the original layout regression.
            val rest = snapshot("${config.name}-rest", background)
            val referenceHeader = rest.header
            val referenceText = rest.text
            assertTrue("header must reserve a 48dp touch target: $referenceHeader", referenceHeader.height >= 48f)
            if (rest.body.top < referenceHeader.bottom) {
                failures += "${config.name}: reasoning overlaps its header by ${referenceHeader.bottom - rest.body.top}px; header=$referenceHeader, label=$referenceText, body=${rest.body}"
            }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.mainClock.advanceTimeBy(32)

            var animatedColorChanges = false
            for (millis in listOf(240L, 480L, 720L, 400L)) {
                compose.mainClock.advanceTimeBy(millis)
                val frame = snapshot("${config.name}-${compose.mainClock.currentTime}", background)
                animatedColorChanges = animatedColorChanges || frame.labelPixels != rest.labelPixels
                assertEquals("animation must not remeasure the header", referenceHeader, frame.header)
                assertEquals("animation must not remeasure the label", referenceText, frame.text)
                assertEquals("animation must preserve font size", rest.layout.layoutInput.style.fontSize, frame.layout.layoutInput.style.fontSize)
                assertEquals("animation must preserve line height", rest.layout.layoutInput.style.lineHeight, frame.layout.layoutInput.style.lineHeight)

                if (frame.body.top < frame.header.bottom) {
                    failures += "${config.name} t=${compose.mainClock.currentTime}: animated reasoning overlaps header; header=${frame.header}, body=${frame.body}"
                }

                val outside = frame.ink.filter { pixel ->
                    val x = pixel % frame.width
                    val y = pixel / frame.width
                    // Colour may change, glyph coverage may not. One pixel of
                    // raster-edge slack allows antialiasing at different alpha.
                    (-1..1).none { dy -> (-1..1).any { dx ->
                        val nx = x + dx
                        val ny = y + dy
                        nx in 0 until rest.width && ny >= 0 && ny * rest.width + nx in rest.ink
                    } }
                }
                if (outside.isNotEmpty()) {
                    val xs = outside.map { it % frame.width }
                    val ys = outside.map { it / frame.width }
                    failures += "${config.name} t=${compose.mainClock.currentTime}: ${outside.size} unexpected ink pixels at [${xs.min()},${ys.min()}..${xs.max()},${ys.max()}], header=${frame.header}, label=${frame.text}, body=${frame.body}, layout=${frame.layout.size}, lineBottom=${frame.layout.getLineBottom(0)}"
                }
            }
            assertTrue("${config.name}: native label pixels must change while shimmer runs", animatedColorChanges)
        }
        assertTrue("thinking header must precede reasoning and shimmer must stay within its resting glyphs:\n${failures.joinToString("\n")}", failures.isEmpty())

        // Exercise both disclosure states only after the native paint checks.
        // A semantic click queues recomposition; one manual frame is not a
        // settled layout. Drain frames and Android's layout/draw work together.
        compose.onNodeWithContentDescription("Reasoning, expanded").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText(BODY).assertDoesNotExist()
        compose.onNodeWithContentDescription("Reasoning, collapsed").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        compose.onNodeWithText(BODY).assertExists()
    }

    private fun snapshot(name: String, background: Int): PaintSnapshot {
        val header = compose.onNodeWithContentDescription("Reasoning, expanded").fetchSemanticsNode().boundsInWindow
        val textNode = compose.onNodeWithText("Thinking", useUnmergedTree = true)
        val text = textNode.fetchSemanticsNode().boundsInWindow
        val body = compose.onNodeWithText(BODY, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val layouts = mutableListOf<TextLayoutResult>()
        textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        return compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(bitmap))
                System.getenv("THINKING_CAPTURE_DIR")?.let { directory ->
                    File(directory, "$name.png").also { it.parentFile?.mkdirs() }.outputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                val ink = buildSet {
                    for (y in floor(header.top).toInt() until ceil(maxOf(header.bottom, body.bottom)).toInt()) {
                        for (x in floor(header.left).toInt() until ceil(header.right).toInt()) {
                            if (bitmap.getPixel(x, y) != background) add(y * bitmap.width + x)
                        }
                    }
                }
                println("THINKING $name header=$header text=$text body=$body layout=${layout.size} font=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight} inkPixels=${ink.size}")
                assertTrue("native draw must actually paint text", ink.isNotEmpty())
                val labelPixels = buildList {
                    for (y in floor(text.top).toInt() until ceil(text.bottom).toInt()) {
                        for (x in floor(text.left).toInt() until ceil(text.right).toInt()) {
                            add(bitmap.getPixel(x, y))
                        }
                    }
                }
                assertTrue("native draw must actually paint the label", labelPixels.any { it != background })
                PaintSnapshot(header, text, body, layout, bitmap.width, ink, labelPixels)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private data class PaintConfiguration(
        val width: Int = 360,
        val fontScale: Float = 1f,
        val theme: String = "nous",
        val mode: HermesThemeMode = HermesThemeMode.Dark,
    ) {
        val name get() = "$theme-$mode-w$width-f$fontScale"
    }

    private data class PaintSnapshot(
        val header: Rect,
        val text: Rect,
        val body: Rect,
        val layout: TextLayoutResult,
        val width: Int,
        val ink: Set<Int>,
        val labelPixels: List<Int>,
    )

    private class PaintLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private companion object {
        const val BODY = "Synthetic reasoning: compare the example and record a next action."
    }
}