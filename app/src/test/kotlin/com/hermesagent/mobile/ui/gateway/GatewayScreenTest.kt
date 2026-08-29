package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The route selector's options, for the same reason
 * [ConnectionsSectionTest] gates the kind chooser's: a saved route that is not
 * among the segments leaves the control with nothing lit, on the one screen
 * whose whole job is changing that route.
 *
 * The saved mode is a projection of the active row's kind, so this list and
 * [OFFERED_CONNECTION_KINDS] are two views of one set — a kind that can be
 * saved is a route that can be selected.
 */
class GatewayScreenTest {

    @Test
    fun `every route a connection can be saved as is a segment the selector offers`() {
        assertEquals(GatewayConnectionMode.entries.toSet(), GATEWAY_ROUTE_OPTIONS.toSet())
        assertEquals(GATEWAY_ROUTE_OPTIONS.size, GATEWAY_ROUTE_OPTIONS.toSet().size)
    }

    @Test
    fun `the routes and the kinds are the same set`() {
        assertEquals(
            OFFERED_CONNECTION_KINDS.map { it.mode }.toSet(),
            GATEWAY_ROUTE_OPTIONS.toSet(),
        )
    }
}
