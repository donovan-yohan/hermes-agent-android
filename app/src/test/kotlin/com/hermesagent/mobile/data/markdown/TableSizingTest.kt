package com.hermesagent.mobile.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Column distribution under a width budget, in pixels — pure math, no
 * Compose, no Robolectric font metrics.
 *
 * The scenario these rules encode: a table whose columns want more room than
 * the phone has. Columns give up their wrappable slack proportionally and stop
 * at the floor set by their widest unbreakable run; whatever still does not
 * fit becomes the block's horizontal scroll extent instead of clipping.
 */
class TableSizingTest {

    @Test
    fun `targets that fit the budget pass through untouched`() {
        val resolved = TableSizing.resolve(
            targets = intArrayOf(120, 80),
            floors = intArrayOf(40, 30),
            budget = 300,
        )

        assertEquals(intArrayOf(120, 80).toList(), resolved.toList())
    }

    @Test
    fun `an unbounded budget never wraps anything`() {
        val resolved = TableSizing.resolve(
            targets = intArrayOf(5000, 4000, 3000),
            floors = intArrayOf(50, 40, 30),
            budget = null,
        )

        assertEquals(intArrayOf(5000, 4000, 3000).toList(), resolved.toList())
    }

    @Test
    fun `over budget shrinks proportionally to wrappable slack`() {
        // Targets 200 + 100 against a 240 budget: deficit 60. Slacks are
        // 100 and 30 (total 130), so column A loses 6000/130 = 46 and column
        // B loses 1800/130 = 13.
        val resolved = TableSizing.resolve(
            targets = intArrayOf(200, 100),
            floors = intArrayOf(100, 70),
            budget = 240,
        )

        assertEquals(intArrayOf(154, 87).toList(), resolved.toList())
    }

    @Test
    fun `a column at its floor stops shrinking and others absorb the rest`() {
        // Column A is all one unbreakable token (floor == target); column B
        // absorbs the entire deficit down to its own floor.
        val resolved = TableSizing.resolve(
            targets = intArrayOf(150, 150),
            floors = intArrayOf(150, 50),
            budget = 220,
        )

        assertEquals(intArrayOf(150, 70).toList(), resolved.toList())
    }

    @Test
    fun `nothing shrinkable keeps the overflow for the scroll extent`() {
        val resolved = TableSizing.resolve(
            targets = intArrayOf(180, 180),
            floors = intArrayOf(180, 180),
            budget = 360,
        )

        assertEquals(intArrayOf(180, 180).toList(), resolved.toList())
    }

    @Test
    fun `floors are never violated even by a huge deficit`() {
        val resolved = TableSizing.resolve(
            targets = intArrayOf(300, 300),
            floors = intArrayOf(90, 90),
            budget = 10,
        )

        assertEquals(intArrayOf(90, 90).toList(), resolved.toList())
    }

    @Test
    fun `integer division residue lands within the floor bound`() {
        // Deficit 7 across three equal slacks: each loses 2, the lost 1 is the
        // grid being 1px wider than the budget — scroll handles it.
        val resolved = TableSizing.resolve(
            targets = intArrayOf(100, 100, 100),
            floors = intArrayOf(50, 50, 50),
            budget = 293,
        )

        assertEquals(intArrayOf(98, 98, 98).toList(), resolved.toList())
    }
}
