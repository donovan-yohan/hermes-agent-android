package com.hermesagent.mobile.data.markdown

/**
 * Column sizing for pipe tables, as a pure function so it can be tested
 * without Robolectric's fake font metrics (where a 100-character token renders
 * 100px wide and no overflow scenario is constructible).
 *
 * Targets are each column's unwrapped content width; floors are each column's
 * widest unbreakable run. If the targets fit [budget] they pass through
 * untouched — every cell renders on its own lines. Otherwise columns shrink
 * proportionally toward their floors (CSS table behaviour) and cells wrap on
 * the shared boundaries. What genuinely cannot fit leaves the grid wider than
 * the viewport and the block's own horizontal scroll takes over; nothing is
 * clipped out of reach.
 */
object TableSizing {
    fun resolve(targets: IntArray, floors: IntArray, budget: Int?): IntArray {
        val wanted = targets.sum()
        if (budget == null || wanted <= budget) return targets.copyOf()

        val deficit = wanted - budget
        val shrinkableTotal = targets.foldIndexed(0) { index, acc, target ->
            acc + (target - floors[index])
        }
        return IntArray(targets.size) { index ->
            val shrinkable = targets[index] - floors[index]
            if (shrinkableTotal == 0 || shrinkable == 0) {
                targets[index]
            } else {
                targets[index] - shrinkable * deficit / shrinkableTotal
            }.coerceAtLeast(floors[index])
        }
    }
}
