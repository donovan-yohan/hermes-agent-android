package com.hermesagent.mobile.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether reaching the head of the transcript pages
 * earlier turns on its own, offline and without a list.
 *
 * Upstream keeps the same shape: one invariant test over the pure predicate,
 * plus one rendered-list journey
 * (`apps/desktop/src/components/assistant-ui/thread/should-auto-show-earlier.test.ts`
 * and `list-auto-show-earlier.test.tsx` @
 * `564aef2946c436500a5e80ee117b66b789b3f99a`). Splitting the matrix into a
 * case per gate would be eleven renders proving one boolean each; this proves
 * the boolean, and `ShowEarlierJourneyTest` proves it is wired to a finger.
 */
class AutoShowEarlierGateTest {

    /**
     * A reader pulling against the clamped head of a transcript that is longer
     * than the screen and has a page behind it — every gate open.
     */
    private fun reaching(
        canShowEarlier: Boolean = true,
        tailOnScreen: Boolean = false,
        firstRowIndex: Int = 0,
        firstRowOffsetPx: Int = 0,
        reachDeltaPx: Float = 24f,
    ) = shouldAutoShowEarlier(
        canShowEarlier = canShowEarlier,
        tailOnScreen = tailOnScreen,
        firstRowIndex = firstRowIndex,
        firstRowOffsetPx = firstRowOffsetPx,
        reachDeltaPx = reachDeltaPx,
    )

    @Test
    fun `pages only for a reader pulling past the clamped head with more to show`() {
        assertTrue("a drag the list refused at its head is the ask", reaching())
        // A fling arrives as leftover velocity rather than leftover distance;
        // the predicate reads both through the same input, so the smallest
        // positive remainder still counts.
        assertTrue("a fling that ran out at the head is the same ask", reaching(reachDeltaPx = 1f))

        // Every gate that must keep a page from loading on its own.
        assertFalse(
            "nothing earlier to ask for: the pill does not render either",
            reaching(canShowEarlier = false),
        )
        assertFalse(
            "a transcript that fits the screen is at its head and its tail at once",
            reaching(tailOnScreen = true),
        )
        assertFalse("mid-transcript, on a row that is not the first", reaching(firstRowIndex = 1))
        assertFalse("scrolled into the first row rather than clamped at it", reaching(firstRowOffsetPx = 1))
        assertFalse("the leftover points at later turns, not earlier ones", reaching(reachDeltaPx = -24f))
        assertFalse("the list consumed the whole gesture, so nothing was refused", reaching(reachDeltaPx = 0f))
    }
}
