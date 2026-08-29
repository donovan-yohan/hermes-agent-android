package com.hermesagent.mobile.ui.gateway

import com.hermesagent.mobile.data.connections.ConnectionKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The kind chooser's options, as a set rather than as a picture.
 *
 * `SegmentedControl` has no representation for a `selected` value that is not
 * among its `options`: it renders the row with nothing lit, and the person is
 * left looking at a form whose kind they cannot see or change. That was a real
 * possibility while the chooser offered a hand-curated subset of the kinds a
 * saved row can be. This is the gate that keeps the two in step, so a fourth
 * kind cannot be added to the registry without also being offered here.
 */
class ConnectionsSectionTest {

    @Test
    fun `every kind a row can be is a segment the chooser offers`() {
        assertEquals(ConnectionKind.entries.toSet(), OFFERED_CONNECTION_KINDS.toSet())
        assertEquals(
            "a duplicate segment would be two ways to pick one kind",
            OFFERED_CONNECTION_KINDS.size,
            OFFERED_CONNECTION_KINDS.toSet().size,
        )
    }

    @Test
    fun `Local is anchored first, as Desktop anchors it`() {
        assertEquals(ConnectionKind.Local, OFFERED_CONNECTION_KINDS.first())
    }
}
