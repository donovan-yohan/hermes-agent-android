package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A send another surface's lease refused has one way out, and the composer is
 * where this app puts it (#220) — at either width (#242).
 *
 * Desktop renders the same escape as a button on the failed turn's error card,
 * ahead of every other recovery action, and hides Retry beside it
 * (`apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:548-551,559-563`
 * @ `564aef2946c436500a5e80ee117b66b789b3f99a`). Here the refusal's own line is
 * the control — the same seam the connection door uses — so what has to be
 * asserted is that the line became a button, that it is *named* for where it
 * goes, and that a notice without an escape did not quietly acquire one.
 *
 * #242 added the second home. In the `Full` layout the line is the status line
 * it always was; below that the model control owns the bottom row's slot, so
 * the same sentence is drawn above the editor — same words, same door — and
 * both are asserted here. The door's pointer band is asserted the way
 * `ChatTopBarAlignmentTest` asserts its bands: by measuring the node's own
 * bounds against the 48dp floor *and* by sending a real pointer to a point in
 * the band rather than on the words, because a click's bounds already are the
 * band and reading them alone would pass for a line with no band at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h900dp")
class SessionNotOwnedEscapeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the refusal line starts a new session`() {
        var created = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = refused(),
                    actions = ChatActions(onCreateSession = { created += 1 }),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }

        compose.onNodeWithContentDescription("$NOT_OWNED_NOTICE. $START_NEW_SESSION")
            .assertExists()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, created)
    }

    /**
     * The half #242 was opened for: on a phone the sentence is reported too,
     * and the control that ends the state is the sentence itself.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a phone reports the refusal and its escape`() {
        var created = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = refused(),
                    actions = ChatActions(onCreateSession = { created += 1 }),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }
        compose.waitForIdle()

        val door = compose.onNodeWithContentDescription("$NOT_OWNED_NOTICE. $START_NEW_SESSION")
            .assertExists()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)

        // Where it is, not just that it is: above the editor, so the model
        // control keeps the bottom row it has at this width. The node's own
        // bounds are its pointer band, which overhangs the drawn sentence
        // evenly, so the band's centre — the sentence itself — is what has to
        // clear the editor.
        val band = door.getUnclippedBoundsInRoot()
        val editorBounds = compose.onNodeWithContentDescription("Message Hermes").getUnclippedBoundsInRoot()
        val lineCentre = (band.top + band.bottom) / 2
        assertTrue(
            "the notice must sit above the editor, was $lineCentre against ${editorBounds.top}",
            lineCentre < editorBounds.top,
        )
        compose.onNodeWithTag("Composer model control").assertExists()

        door.performClick()

        assertEquals(1, created)
    }

    /**
     * The thumb, on a phone, away from the words.
     *
     * A 48dp band is the reason this notice is a line of the composer rather
     * than a caption-sized link, and a click's own bounds already *are* that
     * band, so the band is proved by sending a pointer to a point inside it
     * that the drawn sentence does not cover.
     *
     * Honest limit, shared with `ChatTopBarAlignmentTest`: Compose grows every
     * clickable to the same floor during hit testing as a fallback, so this
     * does not isolate the modifier. What it keeps is the behaviour a thumb
     * gets — the control answers a tap beside its words, and nothing between it
     * and the window clips that away.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a phone's refusal answers a tap beside its words`() {
        var created = 0
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = refused(),
                    actions = ChatActions(onCreateSession = { created += 1 }),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }
        compose.waitForIdle()

        val band = compose.onNodeWithContentDescription("$NOT_OWNED_NOTICE. $START_NEW_SESSION")
            .getUnclippedBoundsInRoot()
        assertTrue("the door's band is ${band.height}, not the 48dp floor", band.height >= 48.dp)
        val x = (band.left + band.right) / 2
        val y = band.top + 1.dp
        compose.onRoot().performTouchInput { click(Offset(x.toPx(), y.toPx())) }
        compose.waitForIdle()

        assertEquals("a tap in the refusal's band should start the session", 1, created)
    }

    /**
     * The connection is the one thing this refusal is *not* about, so the line
     * must not also be reachable as the Gateways door: a person who followed it
     * would land on a screen that fixes nothing.
     */
    @Test
    fun `the refusal line is not the Gateways door`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = refused(),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }

        compose.onNodeWithContentDescription("$NOT_OWNED_NOTICE. ${ConnectionsCopy.MANAGE_GATEWAYS}")
            .assertDoesNotExist()
    }

    /**
     * The rule this change had to preserve: a notice that is only reporting
     * stays prose even while the connection itself has a door, because the
     * status line is showing the notice and not the connection.
     */
    @Test
    fun `a notice with no escape is still not a door`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = reporting(),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }

        compose.onNodeWithText(REPORTING_NOTICE).assertExists()
        compose.onNodeWithContentDescription("$REPORTING_NOTICE. ${ConnectionsCopy.MANAGE_GATEWAYS}")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("$REPORTING_NOTICE. $START_NEW_SESSION")
            .assertDoesNotExist()
    }

    /**
     * The same notice at phone width, with the connection asking for a door of
     * its own: the sentence on screen is the notice and not the connection's,
     * and it is still prose.
     *
     * Precedence is the point. Every notice was wide-only before #242, and the
     * narrow slot is now the notice's — the connection reports itself in the
     * header at every width and owns that door there, so the composer owes a
     * phone no permanent second row. A composer that printed the connection
     * instead would satisfy "the notice is visible" in this suite's other
     * cases and still send a person to Gateways for a project read failure.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a phone shows a notice that has no escape without making it a door`() {
        compose.setContent {
            HermesTheme(AppearanceSelection()) {
                ChatScreen(
                    state = reporting(),
                    actions = ChatActions(),
                    onOpenSettings = {},
                    onOpenGateways = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText(REPORTING_NOTICE).assertExists()
        compose.onNodeWithText("Open Gateways to reconnect").assertDoesNotExist()
        compose.onNodeWithContentDescription("$REPORTING_NOTICE. ${ConnectionsCopy.MANAGE_GATEWAYS}")
            .assertDoesNotExist()
        compose.onNodeWithContentDescription("$REPORTING_NOTICE. $START_NEW_SESSION")
            .assertDoesNotExist()
    }

    private fun refused() = ChatUiState(
        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
        notice = ChatNotice(NOT_OWNED_NOTICE, ChatNoticeAction.StartNewSession),
    )

    /** A notice that only reports, on a connection that has a door of its own. */
    private fun reporting() = ChatUiState(
        connection = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention),
        notice = ChatNotice(REPORTING_NOTICE),
    )

    private companion object {
        /**
         * Desktop's own label for this escape, verbatim: `errorStartNewSession:
         * 'Start new session'` (`apps/desktop/src/i18n/en.ts:3714` @
         * `564aef2946c436500a5e80ee117b66b789b3f99a`). Spelled out rather than
         * read from the screen's private constant, so a silent edit to the
         * words Desktop chose fails here.
         */
        const val START_NEW_SESSION = "Start new session"

        /** Any notice that is news rather than a door (`ChatViewModel.kt`'s project read). */
        const val REPORTING_NOTICE = "Projects could not be refreshed. Try opening Sessions again."
    }
}
