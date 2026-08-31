package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionAttentionAction
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The word on the registry row and the button behind it come from one source
 * (#116 C3).
 *
 * The row and the route pane are two projections of the active connection, and
 * after a switch they agree only eventually. Taking the word from the row's
 * kind while taking the callback from the pane's mode renders `Sign in` wired
 * to the Local connect for the width of that window.
 */
class RouteAttentionTest {

    @Test
    fun `a Remote route asks to sign in`() {
        val attention = forRoute(GatewayConnectionMode.Remote, activeKind = ConnectionKind.Remote)

        assertEquals(ConnectionAttentionAction.SignIn, attention?.action)
    }

    @Test
    fun `a Local route asks to connect, because its token is already here`() {
        val attention = forRoute(GatewayConnectionMode.Local, activeKind = ConnectionKind.Local)

        assertEquals(ConnectionAttentionAction.Connect, attention?.action)
    }

    @Test
    fun `Managed SSH asks for nothing this list can start`() {
        assertNull(forRoute(GatewayConnectionMode.Ssh, activeKind = ConnectionKind.Ssh))
    }

    @Test
    fun `a connection that is not asking for attention offers no action`() {
        GatewayConnectionStatus.entries
            .filter { it != GatewayConnectionStatus.NeedsAttention }
            .forEach { status ->
                assertNull(
                    "$status is not a state that asks the person for anything",
                    forRoute(GatewayConnectionMode.Remote, activeKind = ConnectionKind.Remote, status = status),
                )
            }
    }

    @Test
    fun `the re-projection window offers nothing rather than a word wired to another route`() {
        // The pane still projects the row that was left; the registry already
        // shows the row that was switched to. This is the S-U5 window.
        val attention = forRoute(GatewayConnectionMode.Local, activeKind = ConnectionKind.Remote)

        assertNull("a Local Connect must never be offered under a Remote row", attention)
    }

    @Test
    fun `a registry that has not loaded an active row yet offers nothing`() {
        assertNull(forRoute(GatewayConnectionMode.Remote, activeKind = null))
    }

    @Test
    fun `the callback is the one the caller supplied for that route`() {
        var pressed = 0
        val attention = forRoute(GatewayConnectionMode.Remote, ConnectionKind.Remote) { pressed += 1 }

        attention?.onConnect?.invoke()

        assertEquals(1, pressed)
    }

    private fun forRoute(
        mode: GatewayConnectionMode,
        activeKind: ConnectionKind?,
        status: GatewayConnectionStatus = GatewayConnectionStatus.NeedsAttention,
        onConnect: () -> Unit = {},
    ) = RouteAttention.forRoute(mode, status, activeKind, onConnect)
}
