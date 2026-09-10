package com.hermesagent.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import com.hermesagent.mobile.ui.theme.HermesTokens
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Which ink each diff row actually paints.
 *
 * [ToolRowFidelityTest] pins the *text* an inline diff renders — that the
 * gateway's ANSI chrome and hunk noise never reach the screen. It cannot see
 * colour, and colour is the panel's only remaining mechanism once the `+`/`-`
 * gutter markers are stripped (`chat/diff-lines.tsx:83-93` @
 * `72a3277cd7937fd0f0a2a3e3fddbed21d7b1c8bd`: "changes read by colour alone").
 *
 * So this is a separate class rather than more cases in that one: reading a
 * pixel needs an Activity's decor view and Robolectric's native canvas, and
 * [ToolRowFidelityTest]'s twenty text cases have no reason to pay for either.
 * The technique is `ContextUsageSwatchInkTest`'s, for its reason: Robolectric
 * never delivers the redraw callback `captureToImage` waits on.
 *
 * Desktop's contract is `diff-lines.tsx:42-52` — `border-l-2` in
 * `--ui-diff-*-border` over `--ui-diff-*-background`, with the ink from
 * `--ui-diff-*-foreground`, and a context row transparent on both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineDiffPanelInkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var tokens: HermesTokens

    private fun render(diff: String) {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                tokens = HermesTheme.tokens
                ChatScreen(
                    state = ChatUiState(
                        activeSession = SessionSummary(
                            id = SESSION,
                            title = "Tool output",
                            preview = "",
                            lastActiveAtMillis = NOW,
                            status = SessionStatus.Idle,
                        ),
                        transcript = listOf<TranscriptEntry>(
                            UserTurn("$SESSION-u1", "patch it", NOW),
                            ToolActivity(
                                id = "$SESSION-t1",
                                label = "apply_patch",
                                detail = "",
                                state = ToolState.Done,
                                toolName = "apply_patch",
                                argsText = """{"path":"notes.md"}""",
                                inlineDiff = diff,
                                startedAtMillis = NOW,
                            ),
                        ),
                        isStreaming = false,
                    ),
                    actions = ChatActions(),
                    onOpenSettings = {},
                )
            }
        }
        compose.waitForIdle()
    }

    /** A pixel of the tagged row, read off a synchronous draw of the window. */
    private fun pixelOf(tag: String, offsetX: Int): Color {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        return Color(bitmap.getPixel(bounds.left.toInt() + offsetX, bounds.center.y.toInt()))
    }

    /**
     * The 2 dp gutter sits flush at the row's left edge, so the first pixel
     * column is the border. The rect is axis-aligned, so there is no
     * antialiasing to dodge; the second pixel is inside it at any density.
     */
    private fun gutterOf(tag: String): Color = pixelOf(tag, 1)

    /**
     * The tint, sampled between the gutter and the row's 10 dp left padding, so
     * no glyph can be in the way at either of the densities this suite runs at.
     */
    private fun tintOf(tag: String): Color = pixelOf(tag, 6)

    /**
     * What the row's tint lands on. Read rather than assumed: a context row is
     * transparent on both border and background (`diff-lines.tsx:44,50` @
     * `72a3277cd7`), so whatever it paints there *is* the backdrop, and the two
     * changed rows must be exactly their token composited over it.
     */
    private fun backdrop(): Color = tintOf(inlineDiffLineTag(2))

    /** Composite this colour over an opaque [backdrop]. */
    private fun Color.over(backdrop: Color): Color = Color(
        red = red * alpha + backdrop.red * (1f - alpha),
        green = green * alpha + backdrop.green * (1f - alpha),
        blue = blue * alpha + backdrop.blue * (1f - alpha),
        alpha = 1f,
    )

    /** Equal after the 8-bit quantisation a bitmap already applied. */
    private fun assertSameInk(what: String, expected: Color, actual: Color) {
        fun channels(c: Color) = listOf(c.red, c.green, c.blue).map { (it * 255f + 0.5f).toInt() }
        val want = channels(expected)
        val got = channels(actual)
        val apart = want.zip(got).maxOf { (a, b) -> kotlin.math.abs(a - b) }
        org.junit.Assert.assertTrue("$what: expected $want but painted $got", apart <= 1)
    }

    @Test
    fun `a removed row paints the remove tint behind the remove gutter`() {
        render(GATEWAY_DIFF)

        // parseDiff drops the arrow line and the `@@` header, so row 0 is the
        // removal, row 1 the addition and row 2 the context line.
        assertSameInk(
            "the removed row's tint",
            tokens.diffRemovedBackground.over(backdrop()),
            tintOf(inlineDiffLineTag(0)),
        )
        assertSameInk("the removed row's gutter", tokens.diffRemoved, gutterOf(inlineDiffLineTag(0)))
    }

    @Test
    fun `an added row paints the add tint behind the add gutter`() {
        render(GATEWAY_DIFF)

        assertSameInk(
            "the added row's tint",
            tokens.diffAddedBackground.over(backdrop()),
            tintOf(inlineDiffLineTag(1)),
        )
        assertSameInk("the added row's gutter", tokens.diffAdded, gutterOf(inlineDiffLineTag(1)))
    }

    @Test
    fun `the two rows are not painted the same, and neither is the wrong semantic`() {
        render(GATEWAY_DIFF)

        assertNotEquals(gutterOf(inlineDiffLineTag(0)), gutterOf(inlineDiffLineTag(1)))
        assertNotEquals(tintOf(inlineDiffLineTag(0)), tintOf(inlineDiffLineTag(1)))
        // docs/parity/inline-diff-tokens.md — the panel once tinted with these.
        assertNotEquals(tokens.statusUnread, gutterOf(inlineDiffLineTag(1)))
        assertNotEquals(tokens.destructive, gutterOf(inlineDiffLineTag(0)))
    }

    @Test
    fun `a context row has no tint and a transparent gutter`() {
        render(GATEWAY_DIFF)

        // `diff-lines.tsx:44,50` — transparent border, no tint: the gutter
        // column and the tint column of a context row are the same pixel value,
        // and neither is either changed row's.
        assertSameInk("the context row's gutter", backdrop(), gutterOf(inlineDiffLineTag(2)))
        assertNotEquals(backdrop(), tintOf(inlineDiffLineTag(0)))
        assertNotEquals(backdrop(), tintOf(inlineDiffLineTag(1)))
    }

    private companion object {
        const val SESSION = "s-diff-ink"
        const val NOW = 1_756_000_000_000L

        /** The Kotlin escape for `ESC`, so no control byte sits in this source. */
        const val KESC = "\u001B"

        /**
         * The wire shape, as `agent/display.py:656-690` @ `72a3277cd7` renders
         * it: banner, collapsed arrow header, hunk header, then one removal, one
         * addition and one context line in truecolour.
         */
        val GATEWAY_DIFF = listOf(
            "  ┊ review diff",
            "$KESC[38;2;180;160;255ma/notes.md → b/notes.md$KESC[0m",
            "$KESC[38;2;120;120;140m@@ -1,3 +1,3 @@$KESC[0m",
            "$KESC[38;2;255;255;255;48;2;60;10;10m-old line$KESC[0m",
            "$KESC[38;2;255;255;255;48;2;10;45;10m+new line$KESC[0m",
            "$KESC[38;2;150;150;150m unchanged line$KESC[0m",
        ).joinToString("\n")
    }
}
