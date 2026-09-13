package com.hermesagent.mobile.ui.chat

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesagent.mobile.data.gateway.GatewayConnectionState
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import com.hermesagent.mobile.ui.ChatActions
import com.hermesagent.mobile.ui.gateway.ConnectionsCopy
import com.hermesagent.mobile.ui.theme.AppearanceSelection
import com.hermesagent.mobile.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A send another surface's lease refused has one way out, and the status line
 * that reports it is where this app puts it (#220).
 *
 * Desktop renders the same escape as a button on the failed turn's error card,
 * ahead of every other recovery action, and hides Retry beside it
 * (`apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:548-551,559-563`
 * @ `564aef2946c436500a5e80ee117b66b789b3f99a`). Here the refusal's own line is
 * the control — the same seam the connection door uses — so what has to be
 * asserted is that the line became a button, that it is *named* for where it
 * goes, and that a notice without an escape did not quietly acquire one.
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
            .performClick()

        assertEquals(1, created)
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
                    state = ChatUiState(
                        connection = GatewayConnectionState(GatewayConnectionStatus.NeedsAttention),
                        notice = ChatNotice(REPORTING_NOTICE),
                    ),
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
     * Where the escape is *not*, stated rather than assumed.
     *
     * The composer prints its status line only in the `Full` layout — above
     * 560dp of composer width (`Composer.kt:80-83`, `:293-302`) — and below
     * that the model control has the slot. So on a phone this refusal is
     * reported by nothing at all, which is the half of #220 that stays open;
     * it is ledgered as drift in `docs/parity/live-owner-refusal.md` and owned
     * by #242. This test fails the day that changes, which is the point: the
     * ledger has to move with it.
     */
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a phone has nowhere to show the escape yet`() {
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

        compose.onNodeWithText(NOT_OWNED_NOTICE).assertDoesNotExist()
        compose.onNodeWithContentDescription("$NOT_OWNED_NOTICE. $START_NEW_SESSION")
            .assertDoesNotExist()
    }

    private fun refused() = ChatUiState(
        connection = GatewayConnectionState(GatewayConnectionStatus.Connected),
        notice = ChatNotice(NOT_OWNED_NOTICE, ChatNoticeAction.StartNewSession),
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
