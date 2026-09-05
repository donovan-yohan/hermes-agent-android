package com.hermesagent.mobile.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.ToolActivity
import com.hermesagent.mobile.data.session.ToolState
import com.hermesagent.mobile.data.session.TranscriptEntry
import com.hermesagent.mobile.data.session.UserTurn
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesSpacing
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesThemeMode
import kotlin.math.abs
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lifting text out of a reply.
 *
 * Desktop gets this from the browser: the message subtree is `user-select:
 * text` and everything else is `user-select: none` (`styles.css:1176-1186` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), and its own test asserts the
 * behaviour on the user bubble (`user-message-selection.test.ts`). Compose
 * inverts that default — nothing selects unless a `SelectionContainer` says so
 * — which moves the risk: the tests below pin *both* halves, that prose selects
 * and that scaffolding still does not.
 *
 * The platform toolbar is recorded rather than drawn. What the app owes is the
 * offer — a live selection whose menu contains Copy; the floating bar itself is
 * Android's, and asserting its pixels would be asserting the platform.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-width screen, because the reply action bar is a fixed row of four
// live 48dp controls and the only way it can fail is by running off the right
// edge. Robolectric's default screen is narrower than any device this ships to,
// which would have made that assertion untestable.
@Config(sdk = [34], qualifiers = "w411dp-h891dp", shadows = [ShadowSelectionMagnifier::class])
// A live selection draws Compose's two vector handles. Robolectric's legacy
// graphics pipeline hands the vector rasteriser a null Bitmap and the draw
// pass dies, so this class renders natively; nothing here asserts pixels.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val contextMenu = RecordingContextMenu()
    private var state by mutableStateOf(ChatUiState())

    /**
     * Compose's own drag handles, which exist only for a live selection. Read
     * by property name rather than by the key itself: `SelectionHandleInfoKey`
     * is public in the bytecode but `internal` to Kotlin.
     */
    private fun selectionHandles(): Int = compose.onAllNodes(
        SemanticsMatcher("is a selection handle") { node ->
            node.config.any { (key, _) -> key.name == "SelectionHandleInfo" }
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    private val clipboard: ClipboardManager
        get() = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val clipboardText: String?
        get() = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()

    private fun launch(
        transcript: List<TranscriptEntry> = reply(),
        onBranchFromReply: ((entryId: String) -> Unit)? = null,
        onRegenerateReply: ((entryId: String) -> Unit)? = null,
        isWorking: Boolean = false,
    ) {
        state = ChatUiState(
            activeSession = SessionSummary(
                id = SESSION,
                title = "Selection",
                preview = "",
                lastActiveAtMillis = NOW,
                status = if (isWorking) SessionStatus.Working else SessionStatus.Idle,
            ),
            transcript = transcript,
        )
        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                CompositionLocalProvider(
                    LocalTextContextMenuToolbarProvider provides contextMenu,
                ) {
                    ChatScreen(
                        state = state,
                        actions = ChatActions(
                            onBranchFromReply = onBranchFromReply,
                            onRegenerateReply = onRegenerateReply,
                        ),
                        onOpenSettings = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** One reply worth copying, plus the scaffolding that must stay out of it. */
    private fun reply(
        markdown: String = REPLY_MARKDOWN,
        streaming: Boolean = false,
    ): List<TranscriptEntry> = listOf(
        UserTurn(id = "$SESSION-u1", text = "summarise the change", atMillis = NOW),
        ToolActivity(
            id = "$SESSION-t1",
            label = "read_file",
            detail = "",
            state = ToolState.Done,
            argsText = """{"path":"notes.md"}""",
        ),
        AssistantTurn(id = "$SESSION-a1", markdown = markdown, atMillis = NOW, streaming = streaming),
    )

    // ── Selection ─────────────────────────────────────────────────────────

    @Test
    fun `a long press on assistant prose starts a selection`() {
        launch()
        assertEquals("nothing is selected before the press", 0, selectionHandles())

        compose.onNodeWithText(FIRST_PARAGRAPH).performTouchInput { longClick() }
        compose.waitForIdle()

        // Compose's own drag handles, which only exist for a live selection.
        assertTrue("long press must start a selection", selectionHandles() > 0)
    }

    @Test
    fun `a selection offers the platform copy action`() {
        launch()

        compose.onNodeWithText(FIRST_PARAGRAPH).performTouchInput { longClick() }
        compose.waitForIdle()

        assertTrue(
            "the platform toolbar must offer Copy, saw ${contextMenu.labels}",
            contextMenu.labels.any { it.equals("copy", ignoreCase = true) },
        )
    }

    @Test
    fun `a long press on a user bubble starts a selection too`() {
        launch(listOf(UserTurn(id = "$SESSION-u1", text = "summarise the change", atMillis = NOW)))

        // The bubble owns the accessible label and its Text child is cleared,
        // so the bubble is the node; the press still lands on the words.
        compose.onNodeWithContentDescription("You said: summarise the change")
            .performTouchInput { longClick() }
        compose.waitForIdle()

        assertTrue("the user bubble must select, like Desktop's", selectionHandles() > 0)
    }

    @Test
    fun `a scaffold row is not selectable`() {
        launch(reply().dropLast(1))

        // The tool row renders "Read notes.md" as a disclosure control, not as
        // prose: it lives in no SelectionContainer, so a long press has nothing
        // to select and no handle is ever raised.
        compose.onNodeWithContentDescription("Tool Read notes.md, done")
            .performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("scaffolding must never select", 0, selectionHandles())
        assertEquals("scaffolding must never offer Copy", emptyList<String>(), contextMenu.labels)
    }

    @Test
    fun `a live selection does not take the transcript's scroll`() {
        launch(reply(LONG_REPLY))

        // The same downward drag, run twice: once with nothing selected, once
        // with a selection live. A real drag, not performScrollTo — that
        // dispatches the ScrollBy semantics action and never touches pointer
        // input, so a container that swallowed every drag would still pass.
        val plain = dragDownFromTail()

        compose.onNodeWithText(TAIL).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithText(TAIL).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(selectionHandles() > 0)

        val selected = dragDownFromTail()

        // Both drags must travel, and travel about as far. Exact equality would
        // be brittle — a live selection adds the handle popups and shifts the
        // starting offset by a few pixels — but a container that ate the
        // gesture would leave the second number pinned at the tail.
        assertTrue("the drag has to actually scroll, or this proves nothing", plain <= 20)
        assertTrue(
            "a live selection must not stop the drag scrolling (plain $plain, selected $selected)",
            selected <= 20,
        )
        assertTrue(
            "a live selection must not materially change the drag (plain $plain, selected $selected)",
            abs(plain - selected) <= 5,
        )
    }

    /** Drags down on the tail and reports the paragraph number left at the top. */
    private fun dragDownFromTail(): Int {
        compose.onNodeWithText(TAIL).performTouchInput {
            swipe(start = center, end = center + Offset(0f, 240f), durationMillis = 200)
        }
        compose.waitForIdle()
        return topmostParagraph()
    }

    /** The number of the reply paragraph closest to the top of the viewport. */
    private fun topmostParagraph(): Int =
        compose.onAllNodesWithText(PARAGRAPH_SUFFIX, substring = true)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f }
            .minByOrNull { it.boundsInRoot.top }
            ?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?.removePrefix("Paragraph ")?.substringBefore(' ')?.toIntOrNull()
            ?: error("no reply paragraph is on screen")

    @Test
    fun `a streaming delta does not drop a selection in the settled prefix`() {
        val settled = "First paragraph of the reply."
        launch(
            listOf(AssistantTurn(id = "$SESSION-a1", markdown = settled, atMillis = NOW, streaming = true)),
        )

        compose.onNodeWithText(settled).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(selectionHandles() > 0)

        // The delta rewrites the same entry under the same id, which is what a
        // real stream does. The container is not remounted, so the anchors in
        // the settled prefix survive.
        state = state.copy(
            transcript = listOf(
                AssistantTurn(
                    id = "$SESSION-a1",
                    markdown = "$settled\n\nA second paragraph arrives.",
                    atMillis = NOW,
                    streaming = true,
                ),
            ),
        )
        compose.waitForIdle()

        assertTrue("a token must not clear the reader's selection", selectionHandles() > 0)
    }

    /**
     * Pins today's behaviour, which is a limitation rather than a contract.
     *
     * A delta that rewrites the block a selection is anchored in clears that
     * selection outright — handles go from two to zero on the next token, and
     * the reader is left holding nothing. Compose re-lays the tail block from a
     * new string and the anchors do not survive it; nothing here can prevent
     * that from the app side, so the honest move is to write it down.
     *
     * The assertion is deliberately exact so that a Compose upgrade which
     * starts re-anchoring a rewritten block *fails* this test instead of
     * quietly improving behaviour the docs still describe as broken. If it
     * fails that way: relax it, and correct
     * `docs/parity/transcript-selection-copy.md` ("Streaming"), the roadmap
     * line, and the PR/limitations copy that all say the selection is lost.
     */
    @Test
    fun `a streaming delta clears a selection inside the block it rewrites`() {
        val settled = "Settled first paragraph."
        val partialTail = "The tail is still"
        launch(
            listOf(
                AssistantTurn(
                    id = "$SESSION-a1",
                    markdown = "$settled\n\n$partialTail",
                    atMillis = NOW,
                    streaming = true,
                ),
            ),
        )

        compose.onNodeWithText(partialTail).performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue("the press must select before the delta lands", selectionHandles() > 0)

        state = state.copy(
            transcript = listOf(
                AssistantTurn(
                    id = "$SESSION-a1",
                    markdown = "$settled\n\n$partialTail arriving.",
                    atMillis = NOW,
                    streaming = true,
                ),
            ),
        )
        compose.waitForIdle()

        assertEquals(
            "a delta that rewrites the selected block clears the selection — measured, " +
                "not desired; see the KDoc before relaxing this",
            0,
            selectionHandles(),
        )
    }

    @Test
    fun `the pinned prompt is chrome and does not select`() {
        val prompt = "the prompt that gets pinned"
        launch(
            listOf(
                UserTurn(id = "$SESSION-u1", text = prompt, atMillis = NOW),
                AssistantTurn(id = "$SESSION-a1", markdown = LONG_REPLY, atMillis = NOW),
            ),
        )

        // The pin renders the same bubble as the transcript through
        // UserTurnBubble, so selection has to be opt-in there or the pin's own
        // drag and return tap end up competing with a selection gesture.
        compose.onNodeWithContentDescription("Current prompt: $prompt")
            .performTouchInput { longClick() }
        compose.waitForIdle()

        assertEquals("the pin must not select", 0, selectionHandles())
    }

    // ── Copy ──────────────────────────────────────────────────────────────

    @Test
    fun `the copy control puts the whole rendered reply on the clipboard`() {
        launch()

        compose.onNodeWithContentDescription("Copy reply").performClick()
        compose.waitForIdle()

        assertEquals(
            "First paragraph of the reply.\n\n• one\n• two\n\nval x = 1",
            clipboardText,
        )
    }

    @Test
    fun `copying confirms in place rather than over the system notice`() {
        launch()

        compose.onNodeWithContentDescription("Copy reply").performClick()
        compose.waitForIdle()

        // Android 13+ raises its own clipboard notice, so the app's whole
        // confirmation is the control's state.
        compose.onNodeWithContentDescription("Reply copied").assertIsDisplayed()
    }

    @Test
    fun `the copy control is reachable by TalkBack and by a finger`() {
        launch()

        val floor = HermesSpacing().touchTarget
        compose.onNodeWithContentDescription("Copy reply")
            .assertHeightIsAtLeast(floor)
            .assertWidthIsAtLeast(floor)
            .assert(
                SemanticsMatcher("offers the 'Copy reply' accessibility action") { node ->
                    node.config.getOrNull(SemanticsActions.CustomActions)
                        ?.any { it.label == "Copy reply" } == true
                },
            )
    }

    /**
     * Desktop mounts four controls under every assistant reply, in this order:
     * Branch, Copy, Read aloud, Refresh
     * (`assistant-message.tsx:625-642` @ `3ca096de`). All four are live in
     * this build.
     */
    @Test
    fun `branch control is enabled when not streaming and invokes the callback`() {
        var tapped = false
        launch(
            transcript = reply(),
            onBranchFromReply = { entryId ->
                assertEquals("$SESSION-a1", entryId)
                tapped = true
            },
        )

        compose.onNodeWithContentDescription("Branch in new chat")
            .assertIsEnabled()
            .performClick()

        assertTrue(tapped)
    }

    @Test
    fun `without a handler the Branch control is disabled`() {
        launch(
            onBranchFromReply = null,
        )
        compose.onNodeWithContentDescription("Branch in new chat")
            .assertIsNotEnabled()
    }

    @Test
    fun `a streaming reply's Branch control is disabled`() {
        launch(
            transcript = listOf(AssistantTurn("assistant-1", "response", 100, streaming = true)),
            isWorking = false,
            onBranchFromReply = {},
        )
        compose.onNodeWithContentDescription("Branch in new chat")
            .assertIsNotEnabled()
    }

    @Test
    fun `the action bar keeps Desktop's live controls in order at the touch floor`() {
        launch()

        val floor = HermesSpacing().touchTarget
        DESKTOP_ACTION_BAR.forEach { name ->
            compose.onNodeWithContentDescription(name)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(floor)
        }

        // Desktop's order, left to right.
        val boxes = DESKTOP_ACTION_BAR.map {
            compose.onNodeWithContentDescription(it).getUnclippedBoundsInRoot()
        }
        boxes.zipWithNext().forEach { (left, right) ->
            assertTrue("the bar must keep Desktop's order", right.left > left.left)
        }
        // The bar is a `Row`, which cannot wrap; assert its four targets fit
        // on screen.
        val screenRight = compose.onRoot().getUnclippedBoundsInRoot().right
        val rightmost = boxes.maxOf { it.right }
        assertTrue(
            "the bar overran the screen: its right edge is $rightmost of $screenRight",
            rightmost <= screenRight,
        )
        // And a wrap would still be caught if the bar ever became a `FlowRow`.
        val spread = boxes.maxOf { it.top } - boxes.minOf { it.top }
        assertTrue("the bar wrapped: its controls span $spread of height", spread <= BASELINE_SLACK)
    }

    @Test
    fun `a reply with nothing to copy grows no copy control`() {
        launch(
            listOf(
                UserTurn(id = "$SESSION-u1", text = "show me", atMillis = NOW),
                AssistantTurn(id = "$SESSION-a1", markdown = "   ", atMillis = NOW),
            ),
        )

        compose.onNodeWithContentDescription("Copy reply").assertDoesNotExist()
        compose.onNodeWithContentDescription("Read aloud").assertDoesNotExist()
    }

    @Test
    fun `read aloud states cycle and disable appropriately`() {
        var toggledId: String? = null
        val actions = ChatActions(onToggleReadAloud = { toggledId = it })
        state = ChatUiState(
            activeSession = SessionSummary(
                id = SESSION,
                title = "Selection",
                preview = "",
                lastActiveAtMillis = NOW,
                status = SessionStatus.Idle,
            ),
            transcript = listOf(
                AssistantTurn(id = "entry1", markdown = "First", atMillis = NOW),
                AssistantTurn(id = "entry2", markdown = "Second", atMillis = NOW),
            ),
        )

        compose.setContent {
            HermesTheme(AppearanceSelection("nous", HermesThemeMode.Dark)) {
                CompositionLocalProvider(
                    LocalTextContextMenuToolbarProvider provides contextMenu,
                ) {
                    ChatScreen(state = state, actions = actions, onOpenSettings = {})
                }
            }
        }
        compose.waitForIdle()

        val readAloudNodes = compose.onAllNodesWithContentDescription("Read aloud")
        assertEquals(2, readAloudNodes.fetchSemanticsNodes().size)

        readAloudNodes.onFirst().assertIsDisplayed().performClick()
        assertEquals("entry1", toggledId)

        state = state.copy(readAloud = ReadAloudUiState.Preparing("entry1"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Preparing audio...").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Read aloud").assertIsDisplayed().assertIsNotEnabled()

        state = state.copy(readAloud = ReadAloudUiState.Speaking("entry1"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Stop reading").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Read aloud").assertIsDisplayed().assertIsNotEnabled()
    }


    /**
     * The control tracks what is *drawn*, not what the projection yields.
     *
     * Assistant prose is rendered without the `@image:` strip the user bubble
     * applies, so a reply whose only content is a ref line puts that line on
     * screen as ordinary text. Gating the control on the projection left that
     * text visible with no way to lift it — the exact gap #48 exists to close.
     *
     * There is still nothing to hand over, because #48 asks for refs to be
     * stripped and the strip is what empties the projection. So the control is
     * present but *disabled*, and offers no TalkBack action: confirming a
     * clipboard write that carried no text would be worse than the missing
     * control it replaces. The words stay long-pressable either way.
     */
    @Test
    fun `a reply that renders only an image ref offers a disabled control`() {
        launch(
            listOf(
                UserTurn(id = "$SESSION-u1", text = "show me", atMillis = NOW),
                AssistantTurn(id = "$SESSION-a1", markdown = "@image:/staged/shot.png", atMillis = NOW),
            ),
        )

        compose.onNodeWithText("@image:/staged/shot.png").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy reply")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .assert(
                SemanticsMatcher("offers no 'Copy reply' accessibility action") { node ->
                    node.config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty()
                },
            )

        // And the press is inert: nothing is claimed, nothing is written.
        compose.onNodeWithContentDescription("Copy reply").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Reply copied").assertDoesNotExist()
    }

    @Test
    fun `refresh control is enabled when not streaming and invokes the callback for newest reply`() {
        var tapped = false
        launch(
            transcript = reply(),
            onRegenerateReply = { entryId ->
                assertEquals("${SESSION}-a1", entryId)
                tapped = true
            },
        )

        compose.onNodeWithContentDescription("Refresh")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertTrue(tapped)
    }

    @Test
    fun `refresh control is disabled for older reply`() {
        launch(
            transcript = reply() + listOf(AssistantTurn("${SESSION}-a2", "newer", NOW)),
            onRegenerateReply = { },
        )

        compose.onAllNodesWithContentDescription("Refresh")[0]
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun `refresh control is disabled when streaming`() {
        launch(
            transcript = reply(streaming = true),
            onRegenerateReply = { },
        )

        compose.onNodeWithContentDescription("Refresh")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    /**
     * Records what Compose offers the platform selection toolbar.
     *
     * Compose 1.11 raises the selection menu through
     * [LocalTextContextMenuToolbarProvider], not the older `TextToolbar`; on a
     * device those items become the floating ActionMode the app never draws.
     * Recording the offer is the app-side half of that contract, and the fake
     * suspends like the real provider does — a menu that returns immediately
     * reads as one that was never opened.
     */
    private class RecordingContextMenu : TextContextMenuProvider {
        val labels = mutableListOf<String>()

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            dataProvider.data().components
                .filterIsInstance<TextContextMenuItem>()
                .mapTo(labels) { it.label }
            awaitCancellation()
        }
    }

    private companion object {
        const val SESSION = "s-selection"
        const val NOW = 1_755_600_000_000L

        /**
         * The bar's four spoken names in Desktop's order. `Copy reply` is this
         * app's own word for Desktop's `Copy` — a rendered-text projection
         * rather than the markdown source, ledgered on the parity page.
         */
        val DESKTOP_ACTION_BAR = listOf(
            "Branch in new chat",
            "Copy reply",
            "Read aloud",
            "Refresh",
        )

        const val FIRST_PARAGRAPH = "First paragraph of the reply."

        /** Layout rounds to whole pixels, and a wrap would cost a whole target. */
        val BASELINE_SLACK = 2.dp
        val REPLY_MARKDOWN = """
            First paragraph of the reply.

            - one
            - two

            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        const val PARAGRAPH_SUFFIX = " of the reply."
        const val TAIL = "Paragraph 40 of the reply."
        val LONG_REPLY = (1..40).joinToString("\n\n") { "Paragraph $it$PARAGRAPH_SUFFIX" }
    }
}
