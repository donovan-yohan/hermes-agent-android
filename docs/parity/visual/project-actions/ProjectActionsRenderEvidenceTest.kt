package com.hermesagent.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.toArgb
import com.hermesagent.mobile.ui.theme.ProjectAppearance
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.TimeZone

/** Additive evidence only. Real debug Activity -> ChatScreen -> ViewModel -> repository.
 * Native software decor draws are NOT device, IME, compositor, or accessibility proof.
 * Set PROJECT_ACTIONS_CAPTURE_DIR explicitly; no private path is embedded in this fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectActionsRenderEvidenceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ProjectActionsParityActivity
    private lateinit var prefix: String
    private val directory: File get() = File(requireNotNull(System.getenv("PROJECT_ACTIONS_CAPTURE_DIR")))
    private val color = "hsl(30 68% 58%)"
    private val explicitLabels = listOf("Rename…", "Add folder", "Set active", "Appearance", "Copy path", "Delete…")

    @Before fun fixedEnvironment() {
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // Clock used by fixture/repository is fixed at 1800000000000.
    }

    private fun launch(theme: String, state: String = "overview", body: () -> Unit) {
        prefix = "$theme-$state"
        val intent = Intent(ApplicationProvider.getApplicationContext(), ProjectActionsParityActivity::class.java)
            .putExtra("visual_parity_state", state).putExtra("visual_parity_theme", theme)
        val controller = Robolectric.buildActivity(ProjectActionsParityActivity::class.java, intent).setup()
        activity = controller.get()
        try {
            compose.waitUntil(10_000) { activity.fixture.ready }
            compose.waitForIdle()
            // Resolve the shipped faces, and exercise the platform mono family actually used by mono.
            assertNotNull(ResourcesCompat.getFont(activity, R.font.codicon))
            assertNotNull(ResourcesCompat.getFont(activity, R.font.collapse_bold))
            val paint = Paint().apply { typeface = Typeface.MONOSPACE; textSize = 15f }
            assertTrue("Native platform glyph advances exist", paint.measureText("Synthetic project") > 0f)
            compose.onNodeWithContentDescription("Open sessions").performClick()
            body()
            writeJson("$prefix-requests.json", JsonArray(activity.fixture.rpc.requests.map { (method, params) ->
                buildJsonObject { put("method", method); put("params", params) }
            }))
        } finally { controller.pause().stop().destroy() }
    }

    private fun menu(id: String = "synthetic-project") {
        compose.onNodeWithTag("Project row $id").performTouchInput { longClick() }
    }
    private fun headerMenu() { compose.onNodeWithTag("Project header").performTouchInput { longClick() } }
    private fun click(label: String) { compose.onNodeWithText(label).performClick() }
    private fun cancel() { click("Cancel") }
    private fun oneWrite(method: String): JsonObject = activity.fixture.rpc.requests.filter { it.first == method }.single().second
    private fun assertString(params: JsonObject, key: String, value: String) {
        assertEquals("Literal $key request parameter", value, params[key]?.jsonPrimitive?.content)
    }
    private fun ordered(labels: List<String>) {
        val bounds = labels.map { compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInWindow }
        bounds.zipWithNext().forEach { (a, b) -> assertTrue("Exact menu order and nonoverlap", a.bottom <= b.top) }
    }

    @Test fun lightOverviewMutationAndRecoveryJourney() = overview("light")
    @Test fun darkOverviewMutationAndRecoveryJourney() = overview("dark")
    private fun overview(theme: String) = launch(theme) {
        capture("overview", descriptions = listOf("Open project Synthetic project. 1 session"))
        menu(); ordered(explicitLabels)
        capture("explicit-context", explicitLabels + "Cancel")
        click("Rename…")
        capture("rename-dialog", listOf("Rename project", "Save", "Cancel"), listOf("Name"))
        compose.onNodeWithContentDescription("Name").performTextReplacement("Renamed project")
        click("Save")
        compose.onNodeWithContentDescription("Open project Renamed project. 1 session").assertIsDisplayed()
        assertString(oneWrite("projects.update"), "id", "synthetic-project")
        assertString(oneWrite("projects.update"), "name", "Renamed project")
        menu(); click("Add folder")
        compose.onNodeWithContentDescription("Remote folder").performTextInput("/workspace/added")
        compose.onNodeWithContentDescription("Label (optional)").performTextInput("Added")
        click("Make primary folder")
        capture("add-folder-dialog", listOf("Folder on the connected Gateway", "Primary folder ✓", "Cancel"), listOf("Remote folder", "Label (optional)"))
        compose.onAllNodesWithText("Add folder").onLast().performClick()
        val add = oneWrite("projects.add_folder")
        assertString(add, "id", "synthetic-project"); assertString(add, "path", "/workspace/added")
        assertString(add, "label", "Added"); assertEquals(true, add["is_primary"]?.jsonPrimitive?.boolean)
        menu(); click("Copy path"); compose.onNodeWithText("Copied").assertIsDisplayed()
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("/workspace/added", clipboard.primaryClip!!.getItemAt(0).text.toString())
        cancel()
        menu(); click("Set active"); assertString(oneWrite("projects.set_active"), "id", "synthetic-project")
        menu(); compose.onNodeWithText("Set active").assertIsNotEnabled()
        click("Delete…")
        capture("delete-dialog", listOf("Delete \"Renamed project\"?", "This removes the saved project from Hermes. Files, git repos, and worktrees stay untouched.", "Delete", "Cancel"))
        cancel(); compose.onNodeWithTag("Project row synthetic-project").assertIsDisplayed()
        assertEquals(0, activity.fixture.rpc.requests.count { it.first == "projects.delete" })
        menu(); click("Delete…"); click("Delete")
        compose.onNodeWithTag("Project row synthetic-project").assertDoesNotExist()
        assertString(oneWrite("projects.delete"), "id", "synthetic-project")

        menu("synthetic-auto"); ordered(listOf("Appearance", "Copy path", "Hide from sidebar"))
        explicitLabels.filter { it !in listOf("Appearance", "Copy path") }.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        capture("auto-context", listOf("Appearance", "Copy path", "Hide from sidebar", "Cancel"))
        click("Hide from sidebar")
        compose.onNodeWithTag("Project row synthetic-auto").assertDoesNotExist()
        compose.onNodeWithText("Undo").assertIsDisplayed()
        capture("hidden-undo", listOf("Hidden from sidebar", "Undo"))
        click("Undo"); compose.onNodeWithTag("Project row synthetic-auto").assertIsDisplayed()
        menu("synthetic-auto"); click("Hide from sidebar")
        // Dispatchers.Main's actual delayed Android Handler runnable, on Robolectric virtual looper time.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8_100))
        compose.waitForIdle()
        assertNull("Real ViewModel Undo expires after 8 seconds", activity.fixture.model.uiState.value.projectManagement.undo)
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithContentDescription("Hidden projects").performClick()
        compose.onNodeWithContentDescription("Search").performTextInput("/workspace/repository")
        capture("hidden-searched-after-undo-expiry", listOf("Hidden projects", "Synthetic repository", "Restore Synthetic repository", "Done"), listOf("Search"))
        click("Restore Synthetic repository")
        capture("hidden-restored-empty", listOf("Hidden projects", "No hidden projects", "Done"))
        click("Done"); compose.onNodeWithTag("Project row synthetic-auto").assertIsDisplayed()
        // Hide/restore use real local storage, not remote deletion. Only the explicit project was deleted.
        assertEquals(1, activity.fixture.rpc.requests.count { it.first == "projects.delete" })
        menu("synthetic-auto"); click("Appearance")
        compose.onNodeWithContentDescription("rocket").performClick()
        compose.onNodeWithTag("Project row synthetic-auto").assertDoesNotExist()
        compose.onNodeWithTag("Project row synthetic-adopted").assertIsDisplayed()
        val adoption = oneWrite("projects.create")
        assertString(adoption, "name", "Synthetic repository")
        assertString(adoption, "primary_path", "/workspace/repository")
        assertString(adoption, "icon", "rocket")
        capture("auto-adopted", descriptions = listOf("Open project Synthetic repository. 0 sessions"))
    }

    @Test fun lightEnteredHeaderAppearanceSelectedAndClear() = entered("light")
    @Test fun darkEnteredHeaderAppearanceSelectedAndClear() = entered("dark")
    private fun entered(theme: String) = launch(theme, "entered") {
        headerMenu(); ordered(explicitLabels)
        capture("header-context", explicitLabels + "Cancel")
        click("Appearance")
        compose.onNodeWithContentDescription(color).assertIsNotSelected()
        compose.onNodeWithContentDescription("rocket").assertIsNotSelected()
        capture("appearance-neutral", listOf("Appearance", "No color", "Cancel"), listOf(color, "rocket"))
        compose.onNodeWithContentDescription(color).performClick()
        headerMenu(); click("Appearance"); compose.onNodeWithContentDescription("rocket").performClick()
        headerMenu(); click("Appearance")
        compose.onNodeWithContentDescription(color).assertIsSelected()
        compose.onNodeWithContentDescription("rocket").assertIsSelected()
        capture("appearance-selected", listOf("Appearance", "No color", "Cancel"), listOf(color, "rocket"))
        click("No color")
        headerMenu(); click("Appearance")
        compose.onNodeWithContentDescription(color).assertIsNotSelected()
        compose.onNodeWithContentDescription("rocket").assertIsSelected().performClick()
        headerMenu(); click("Appearance")
        compose.onNodeWithContentDescription("rocket").assertIsNotSelected()
        capture("appearance-cleared", listOf("Appearance", "No color", "Cancel"), listOf(color, "rocket"))
        cancel()
        val writes = activity.fixture.rpc.requests.filter { it.first == "projects.update" }.map { it.second }
        assertEquals(4, writes.size)
        writes.forEach { assertString(it, "id", "synthetic-project") }
        assertString(writes[0], "color", color); assertString(writes[1], "icon", "rocket")
        assertString(writes[2], "color", ""); assertString(writes[3], "icon", "")
    }

    @Test fun lightHomeLongPressExcluded() = home("light")
    @Test fun darkHomeLongPressExcluded() = home("dark")
    private fun home(theme: String) = launch(theme, "home") {
        compose.onNodeWithTag("Project header").assert(SemanticsMatcher("Home has no long-click action") {
            !it.config.contains(SemanticsActions.OnLongClick)
        }).performTouchInput { longClick() }
        assertNull("Actual long touch on Home opens no action dialog", activity.fixture.model.uiState.value.projectManagement.dialog)
        explicitLabels.forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        capture("long-press-excluded", listOf("HOME"))
        assertTrue(activity.fixture.rpc.requests.none { it.first in listOf("projects.update", "projects.create", "projects.delete", "projects.add_folder", "projects.set_active") })
    }

    private fun writeJson(name: String, value: JsonElement) {
        directory.mkdirs()
        File(directory, name).writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value) + "\n")
    }

    private fun capture(name: String, texts: List<String> = emptyList(), descriptions: List<String> = emptyList()) {
        compose.waitForIdle()
        val textLayouts = mutableListOf<JsonObject>()
        val overflowFailures = mutableListOf<String>()
        val targets = buildList {
            texts.forEach { text ->
                val node = compose.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode()
                add(buildJsonObject {
                    put("text", text); put("boundsInWindow", node.boundsInWindow.toString())
                    put("enabled", !node.config.contains(SemanticsProperties.Disabled))
                })
                // Text's own layout, not merely a merged parent's label. Overflow is a real failed gate.
                compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().forEach { leaf ->
                    leaf.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.let { get ->
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        get(layouts)
                        layouts.forEach { layout ->
                            val glyphs = (0 until layout.layoutInput.text.length).filter { !layout.layoutInput.text[it].isWhitespace() }.map(layout::getBoundingBox)
                            val glyphLeft = glyphs.minOfOrNull { it.left } ?: 0f
                            val glyphRight = glyphs.maxOfOrNull { it.right } ?: 0f
                            val glyphTop = glyphs.minOfOrNull { it.top } ?: 0f
                            val glyphBottom = glyphs.maxOfOrNull { it.bottom } ?: 0f
                            textLayouts += buildJsonObject {
                                put("text", text); put("size", layout.size.toString()); put("constraints", layout.layoutInput.constraints.toString())
                                put("overflowWidth", layout.didOverflowWidth); put("overflowHeight", layout.didOverflowHeight)
                                put("lineCount", layout.lineCount); put("font", layout.layoutInput.style.toString())
                                put("paragraphWidth", layout.multiParagraph.width); put("paragraphHeight", layout.multiParagraph.height)
                                put("glyphLeft", glyphLeft); put("glyphRight", glyphRight); put("glyphTop", glyphTop); put("glyphBottom", glyphBottom)
                            }
                            // MultiParagraph retains the max constraint (285px) while Text shrinks to
                            // actual intrinsic width (e.g. 52px); didOverflowWidth compares those two
                            // and is true for fully painted short labels. Assert actual glyph boxes.
                            if (glyphLeft < -0.5f || glyphRight > layout.size.width + 0.5f || glyphTop < -0.5f || glyphBottom > layout.size.height + 0.5f)
                                overflowFailures += "Visible exact glyph bounds must fit allocated text: $text"
                        }
                    }
                }
            }
            descriptions.forEach { description ->
                val node = compose.onNodeWithContentDescription(description).assertIsDisplayed().fetchSemanticsNode()
                add(buildJsonObject {
                    put("contentDescription", description); put("boundsInWindow", node.boundsInWindow.toString())
                    put("enabled", !node.config.contains(SemanticsProperties.Disabled))
                    node.config.getOrNull(SemanticsProperties.Selected)?.let { put("selected", it) }
                })
            }
        }
        // Exact production inventories; four icons on the last row, not six missing glyph slots.
        val appearanceBounds = if (name.startsWith("appearance-")) {
            ProjectAppearance.swatches.forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
            ProjectAppearance.icons.keys.forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
            val swatches = ProjectAppearance.swatches.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInWindow }
            val icons = ProjectAppearance.icons.keys.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInWindow }
            val clear = compose.onNodeWithText("No color").fetchSemanticsNode().boundsInWindow
            assertTrue(swatches.maxOf { it.bottom } <= clear.top)
            assertTrue(clear.bottom <= icons.minOf { it.top })
            listOf(swatches, icons).forEach { grid ->
                grid.forEach { assertTrue(it.width >= 48f); assertTrue(it.height >= 48f) }
                grid.zipWithNext().forEach { (a, b) -> assertTrue(a.top < b.top || (a.top == b.top && a.left < b.left)) }
                grid.forEachIndexed { i, a -> grid.drop(i + 1).forEach { b -> assertFalse(a.overlaps(b)) } }
            }
            ProjectAppearance.swatches.zip(swatches).toMap() + ProjectAppearance.icons.keys.zip(icons).toMap()
        } else emptyMap()
        val images = mutableListOf<JsonObject>()
        compose.runOnIdle {
            val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
            val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
            val roots = (field.get(global) as List<*>).filterIsInstance<View>().filter { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            val main = activity.window.decorView
            val composite = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
            main.draw(Canvas(composite))
            val mainPosition = IntArray(2).also(main::getLocationOnScreen)
            roots.filter { it !== main }.forEach { root ->
                val position = IntArray(2).also(root::getLocationOnScreen)
                val canvas = Canvas(composite)
                canvas.save(); canvas.translate((position[0] - mainPosition[0]).toFloat(), (position[1] - mainPosition[1]).toFloat())
                root.draw(canvas); canvas.restore()
            }
            fun save(bitmap: Bitmap, suffix: String) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val unique = pixels.toSet().size
                val opaque = pixels.count { (it ushr 24) > 0 }
                assertTrue("Native draw must have actual glyph/color variation ($name $suffix)", unique > 30)
                assertTrue("Native draw is not transparent ($name $suffix)", opaque > 1000)
                val file = "$prefix-$name-$suffix.png"
                File(directory, file).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                images += buildJsonObject { put("file", file); put("width", bitmap.width); put("height", bitmap.height); put("uniqueColors", unique); put("nontransparentPixels", opaque) }
            }
            directory.mkdirs()
            save(composite, "composite"); composite.recycle()
            if (roots.any { it !== main }) {
                val modal = roots.last()
                val bitmap = Bitmap.createBitmap(modal.width, modal.height, Bitmap.Config.ARGB_8888)
                modal.draw(Canvas(bitmap))
                if (appearanceBounds.isNotEmpty()) {
                    val selected = name == "appearance-selected"
                    for (swatch in ProjectAppearance.swatches) {
                        val bounds = appearanceBounds.getValue(swatch)
                        val x = bounds.center.x.toInt(); val y = bounds.center.y.toInt()
                        val ink = ProjectAppearance.color(swatch, androidx.compose.ui.graphics.Color.Unspecified).toArgb()
                        assertEquals("Every production swatch paints its literal color", ink, bitmap.getPixel(x, y))
                        assertNotEquals("Swatch stays small within hit box", ink, bitmap.getPixel(x + 16, y))
                        if (selected && swatch == color) assertEquals("Selected ring actually paints", ink, bitmap.getPixel(x + 13, y))
                        else assertNotEquals("Unselected has no ring", ink, bitmap.getPixel(x + 13, y))
                    }
                    if (selected) {
                        val rocket = appearanceBounds.getValue("rocket")
                        var tintPixels = 0
                        for (y in rocket.top.toInt() until rocket.bottom.toInt()) for (x in rocket.left.toInt() until rocket.right.toInt()) {
                            val pixel = bitmap.getPixel(x, y)
                            val red = (pixel ushr 16) and 255; val green = (pixel ushr 8) and 255; val blue = pixel and 255
                            if (red - blue > 40 && kotlin.math.abs((red - blue) - 2 * (green - blue)) <= 3) tintPixels++
                        }
                        assertTrue("Selected rocket Codicon paints project tint, not merely selected semantics", tintPixels > 0)
                    }
                }
                save(bitmap, "modal"); bitmap.recycle()
            }
        }
        writeJson("$prefix-$name.json", buildJsonObject {
            put("renderer", "Robolectric SDK34 native graphics synchronous View.draw; not device/IME proof")
            put("composition", "ProjectActionsParityActivity -> real ChatScreen -> real ChatViewModel -> LiveGatewaySessionRepository; synthetic wire and transient store")
            put("theme", prefix.substringBefore('-')); put("preset", "mono"); put("locale", "en-US"); put("timezone", "UTC")
            put("clockMillis", 1_800_000_000_000L); put("fontScale", 1); put("density", 1)
            put("fontResolution", "Platform SansSerif UI and Monospace code per unchanged Mono preset; ResourcesCompat codicon.ttf and collapse_bold.otf resolve")
            put("appearanceInventory", buildJsonObject {
                put("swatches", ProjectAppearance.swatches.size); put("icons", ProjectAppearance.icons.size)
                put("bounds", buildJsonObject { appearanceBounds.forEach { (label, bounds) -> put(label, bounds.toString()) } })
            })
            put("compositeLimit", "Decor draws composited at measured screen positions, without OS WindowManager dim/IME/system-bar compositing")
            put("targets", JsonArray(targets)); put("images", JsonArray(images)); put("textLayouts", JsonArray(textLayouts))
            put("overflowFailures", JsonArray(overflowFailures.map(::JsonPrimitive)))
        })
        assertTrue(overflowFailures.joinToString("; "), overflowFailures.isEmpty())
    }
}
