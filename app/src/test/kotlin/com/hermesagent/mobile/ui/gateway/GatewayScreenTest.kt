package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.gateway.GatewayConnectionMode
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.MODE_CARD_TWO_COLUMN_DP
import com.hermesagent.mobile.ui.common.MODE_CARD_WIDE_DP
import com.hermesagent.mobile.ui.common.modeCardColumnsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **Connection mode** cards, against Desktop at
 * `29112bef099274229cadff79cdff7bf7b99c4b77`.
 *
 * Three things are gated here and none of them need a device: the card *order*
 * (`gateway-settings.tsx:1049-1082`), the *words* (`en.ts:776-783`, `:865-868`
 * — a copy edit that drifts from Desktop has to come through this file and
 * face the citation), and the *totality* that keeps a saved route reachable.
 */
class GatewayScreenTest {

    @Test
    fun `the cards are Desktop's four, in Desktop's order`() {
        assertEquals(
            listOf("Local gateway", "Hermes Cloud", "Remote gateway", "Connect via SSH"),
            GATEWAY_MODE_CARDS.map { it.title },
        )
    }

    @Test
    fun `each card carries Desktop's glyph`() {
        assertEquals(
            listOf(HermesIcon.Monitor, HermesIcon.Cloud, HermesIcon.Globe, HermesIcon.Terminal),
            GATEWAY_MODE_CARDS.map { it.icon },
        )
    }

    @Test
    fun `the descriptions and hints are Desktop's, or say why not`() {
        val cards = GATEWAY_MODE_CARDS.associateBy { it.title }

        // Verbatim, `en.ts:783`.
        assertEquals(
            "Sign in once to Hermes Cloud and pick from the agents on your account — no URL to paste.",
            cards.getValue("Hermes Cloud").description,
        )
        // `en.ts:866-867` minus one adjective: Desktop says "key-based", and
        // this route offers Tailscale SSH and Password as well as a key
        // (`data/ssh/SshModel.kt:80`), so the word would turn people away.
        assertEquals(
            "Hermes is launched on the remote over SSH and tunneled to this app — nothing to start " +
                "or expose yourself. Requires working SSH access to the host.",
            cards.getValue("Connect via SSH").description,
        )
        // Verbatim, `en.ts:781` and `:868`.
        assertEquals(
            "Hosted gateways use OAuth or a username and password; self-hosted ones may use a session token.",
            cards.getValue("Remote gateway").hint,
        )
        assertEquals(
            "The first presented host key is trusted and pinned; later changes fail closed.",
            cards.getValue("Connect via SSH").hint,
        )

        // The two deliberate deviations, recorded in
        // `docs/parity/gateway-connections.md`. Desktop's `en.ts:780` says
        // "this desktop shell" and `:778` says the app starts the backend;
        // neither is true of this app, so both are asserted as changed rather
        // than allowed to drift back silently.
        assertEquals(
            "Connect this app to a remote Hermes backend.",
            cards.getValue("Remote gateway").description,
        )
        assertEquals(
            "Connect to a private Hermes backend you run on this phone. Works offline.",
            cards.getValue("Local gateway").description,
        )
    }

    @Test
    fun `only Remote and SSH carry a hint, as only they do on Desktop`() {
        assertEquals(
            listOf("Remote gateway", "Connect via SSH"),
            GATEWAY_MODE_CARDS.filter { it.hint != null }.map { it.title },
        )
    }

    @Test
    fun `Hermes Cloud is rendered but is not a route this app can be on`() {
        val cloud = GATEWAY_MODE_CARDS.single { it.title == "Hermes Cloud" }
        // No `GatewayConnectionMode` at all: the card cannot be selected, and a
        // saved connection cannot end up on a route the app cannot dial.
        assertNull(cloud.mode)
        assertTrue(
            "a Cloud card that is absent teaches a different surface than Desktop's",
            GATEWAY_MODE_CARDS.any { it.title == "Hermes Cloud" },
        )
    }

    @Test
    fun `every route a connection can be saved as has exactly one card`() {
        assertEquals(GatewayConnectionMode.entries.toSet(), GATEWAY_ROUTE_OPTIONS.toSet())
        assertEquals(
            "two cards for one route would be two ways to pick it",
            GATEWAY_ROUTE_OPTIONS.size,
            GATEWAY_ROUTE_OPTIONS.toSet().size,
        )
    }

    @Test
    fun `the routes and the kinds are the same set`() {
        assertEquals(
            OFFERED_CONNECTION_KINDS.map { it.mode }.toSet(),
            GATEWAY_ROUTE_OPTIONS.toSet(),
        )
    }

    /**
     * Desktop's `grid-cols-1 sm:grid-cols-2 min-[72rem]:grid-cols-4`
     * (`gateway-settings.tsx:1048`), mapped in
     * `Primitives.kt:modeCardColumnsFor`. A viewport query on both sides, so
     * the numbers are the window's.
     */
    @Test
    fun `the grid is one column on a phone, two at 600dp and four when wide`() {
        assertEquals(1, modeCardColumnsFor(360))
        assertEquals(1, modeCardColumnsFor(MODE_CARD_TWO_COLUMN_DP - 1))
        assertEquals(2, modeCardColumnsFor(MODE_CARD_TWO_COLUMN_DP))
        assertEquals(2, modeCardColumnsFor(MODE_CARD_WIDE_DP - 1))
        assertEquals(4, modeCardColumnsFor(MODE_CARD_WIDE_DP))
        assertEquals(4, modeCardColumnsFor(1280))
    }

    @Test
    fun `every card lands in some column at every width`() {
        // A zero or negative column count would divide the grid by nothing.
        listOf(0, 1, 320, 599, 600, 719, 720, 2000).forEach { width ->
            assertTrue("$width dp gave ${modeCardColumnsFor(width)} columns", modeCardColumnsFor(width) >= 1)
        }
    }
}
