package com.hermesagent.mobile.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.BuiltinThemes
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import com.hermesagent.mobile.ui.theme.HermesTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pinned current prompt masks the thread that scrolls behind it.
 *
 * Desktop shipped this as a bug fix. Its sticky human bubble floats
 * `--sticky-human-top` — `0.23rem`, `apps/desktop/src/styles.css:497` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a` — below the scroll viewport's top
 * edge, and that sliver was transparent, so the thread ran through it. The fix
 * paints a `[data-slot='aui_user-message-root']::before` with
 * `--ui-chat-surface-background` over the sliver and pushes the bubble down a
 * further pixel so the cover overlaps rather than abuts it
 * (`styles.css:1562-1578`, commit `e7c819a7e1`).
 *
 * This port has no sliver to cover: the pin is a sibling overlay aligned to the
 * top of the same box the transcript fills (`ChatScreen.kt:636`), so its own
 * opaque `chatSurface` box *is* the cover and it starts where the viewport
 * starts. The Android equivalent of that `::before` is therefore an invariant
 * about a colour rather than a new element — which is exactly the claim a text
 * assertion cannot see and a silent refactor can drop.
 *
 * So this reads pixels. The thread behind the pin is one tall fenced block,
 * because a fence paints `widgetSurface` edge to edge
 * (`Transcript.kt:2061-2072`) where prose would leave the same `chatSurface` a
 * missing mask shows, and prove nothing.
 *
 * Hence [GraphicsMode.Mode.NATIVE]: Robolectric's legacy canvas draws nothing a
 * pixel read could distinguish.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickyPromptMaskInkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var tokens: HermesTokens
    private var turnGapPx = 0

    private fun render() {
        compose.setContent {
            HermesTheme(AppearanceSelection(BuiltinThemes.DEFAULT_NAME, HermesThemeMode.Dark)) {
                tokens = HermesTheme.tokens
                with(LocalDensity.current) { turnGapPx = HermesTheme.spacing.turnGap.roundToPx() }
                ChatScreen(state = transcriptState(), actions = ChatActions(), onOpenSettings = {})
            }
        }
        compose.waitForIdle()
    }

    private fun transcriptState() = ChatUiState(
        activeSession = SessionSummary(
            id = SESSION,
            title = "Masked prompt",
            preview = "",
            lastActiveAtMillis = NOW,
            status = SessionStatus.Idle,
        ),
        transcript = listOf(
            UserTurn(id = "$SESSION-u1", text = PROMPT, atMillis = NOW),
            AssistantTurn(id = "$SESSION-a1", markdown = FENCE, atMillis = NOW),
        ),
    )

    /**
     * The pixel at ([x], [y]) of a synchronous draw of the window rather than
     * `captureToImage`: Robolectric never delivers the redraw callback that one
     * waits on.
     */
    private fun inkAt(x: Int, y: Int): Color {
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return Color(bitmap.getPixel(x, y))
    }

    @Test
    fun `the pinned prompt paints the thread surface over the turn behind it`() {
        render()

        val bubble = compose.onNodeWithContentDescription("Current prompt: $PROMPT")
            .fetchSemanticsNode()
            .boundsInWindow
        val x = bubble.center.x.toInt()

        // The band the pin's own box adds above and below the bubble. On
        // Desktop the band above is the one the `::before` had to cover.
        assertEquals(
            "the gap above the pinned bubble is the thread surface, not the turn behind it",
            tokens.chatSurface,
            inkAt(x, bubble.top.toInt() - turnGapPx / 2),
        )
        assertEquals(
            "the gap below the pinned bubble is the thread surface too",
            tokens.chatSurface,
            inkAt(x, bubble.bottom.toInt() + turnGapPx / 2),
        )

        // Without this the two assertions above would also pass on a transcript
        // with nothing behind the pin. The very next row down is the fence, so
        // an unmasked band would have read as that fill instead.
        // Measure the actual painted fence band instead of assuming the tail's
        // scroll offset. The fence is taller than the viewport, so at least one
        // sample below the pin must be its opaque fill.
        val fenceSamples = (bubble.bottom.toInt() until compose.activity.window.decorView.height)
            .filter { y -> inkAt(x, y) == tokens.widgetSurface }
        assertTrue("the measured transcript contains a painted fence below the pin", fenceSamples.isNotEmpty())
        assertNotEquals(tokens.chatSurface, tokens.widgetSurface)
    }

    private companion object {
        const val SESSION = "sticky-mask"
        const val NOW = 1_755_600_000_000L
        const val PROMPT = "Summarise the fenced block."

        /**
         * Taller than the viewport on its own, so the row under the pin is the
         * fence's fill at every offset the opening jump to the tail can land on.
         */
        val FENCE = buildString {
            appendLine("```text")
            repeat(160) { appendLine("fenced line $it, wide enough to fill the row") }
            appendLine("```")
        }

        /** Clear of the fence's 10dp corner radius and its own top inset. */
        const val FENCE_PROBE_PX = 24
    }
}
