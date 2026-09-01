package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionKind
import com.hermesagent.mobile.ui.common.HermesIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The registry's kind chooser, against Desktop's at
 * `connections-registry.tsx:648-671` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * Order and labels are Desktop's; the fourth entry is the one this app cannot
 * be, kept visible and disabled rather than dropped.
 */
class ConnectionsSectionTest {

    @Test
    fun `the chooser offers Desktop's four kinds, in Desktop's order`() {
        assertEquals(
            listOf("Local", "Hermes Cloud", "Remote gateway", "SSH"),
            CONNECTION_KIND_CHOICES.map { it.label },
        )
    }

    @Test
    fun `Hermes Cloud is offered but cannot be chosen`() {
        val cloud = CONNECTION_KIND_CHOICES.single { it.label == "Hermes Cloud" }
        // `ConnectionKind` has no Cloud member, so no saved row can be one and
        // the button has nothing to select. Unsupported is disabled, not absent.
        assertNull(cloud.kind)
    }

    @Test
    fun `every kind a row can be is a button the chooser offers`() {
        assertEquals(ConnectionKind.entries.toSet(), OFFERED_CONNECTION_KINDS.toSet())
        assertEquals(
            "a duplicate button would be two ways to pick one kind",
            OFFERED_CONNECTION_KINDS.size,
            OFFERED_CONNECTION_KINDS.toSet().size,
        )
    }

    @Test
    fun `Local is anchored first, as Desktop anchors it`() {
        assertEquals(ConnectionKind.Local, OFFERED_CONNECTION_KINDS.first())
        assertEquals("Local", CONNECTION_KIND_CHOICES.first().label)
    }

    /**
     * Desktop's `KIND_ICONS` (`connections-registry.tsx:26-31`): cloud/local/
     * remote/ssh to Cloud/Monitor/Globe/Terminal. Local used to be drawn with
     * `device-mobile` here; the parity gate calls a changed glyph drift, so it
     * is Desktop's monitor again and the ownership difference lives in the
     * description instead.
     */
    @Test
    fun `each kind carries Desktop's glyph`() {
        assertEquals(HermesIcon.Monitor, ConnectionKind.Local.glyph)
        assertEquals(HermesIcon.Globe, ConnectionKind.Remote.glyph)
        assertEquals(HermesIcon.Terminal, ConnectionKind.Ssh.glyph)
    }
}
