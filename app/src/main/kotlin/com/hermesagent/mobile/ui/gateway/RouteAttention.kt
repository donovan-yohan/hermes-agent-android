package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionAttentionAction
import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.data.gateway.GatewayConnectionStatus

/**
 * What the active route is asking for, and the button that answers it.
 *
 * The word and the callback travel together because they must come from one
 * source. The registry row and the route pane are two projections of the active
 * connection, and after a switch they agree only eventually: taking the word
 * from the row's kind while taking the callback from the pane's mode can render
 * `Sign in` wired to the Local connect for as long as the re-projection takes
 * (#116 S-U5). Built from [forRoute], which reads the mode alone, so the two
 * cannot disagree by construction.
 */
internal data class RouteAttention(
    val action: ConnectionAttentionAction,
    val onConnect: () -> Unit,
) {
    companion object {
        /**
         * The action the pane on [mode] is asking for, or null when it is
         * asking for nothing this list should offer.
         *
         * Null in four cases, and each is a case where a button would lie:
         * the connection is not asking for attention; the route has no action a
         * row can start (Managed SSH, whose credential is typed above); the
         * registry has not loaded an active row yet; or that row is not on this
         * pane's route, which is the re-projection window itself. The last one
         * is belt to the braces above — with the word and the callback both
         * from [mode] they already agree, and hiding the control keeps it off
         * a row whose kind it does not describe.
         */
        fun forRoute(
            mode: GatewayConnectionMode,
            status: GatewayConnectionStatus,
            activeKind: ConnectionKind?,
            onConnect: () -> Unit,
        ): RouteAttention? {
            if (status != GatewayConnectionStatus.NeedsAttention) return null
            val kind = ConnectionKind.of(mode)
            if (activeKind != kind) return null
            return kind.attentionAction?.let { RouteAttention(it, onConnect) }
        }
    }
}
