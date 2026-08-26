package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * Scroll to the *bottom* of a list, not merely to its last item.
 *
 * A single entry routinely outgrows the viewport — a streaming reply in chat, a
 * long Relay message — and `scrollToItem` only puts an item's top edge on
 * screen, which is precisely where the tail disappears. Walking forward until
 * the list reports it cannot scroll any further lands on the bottom edge
 * instead. A failed scroll is the terminating condition: it cannot spin if
 * layout cannot make progress, and it imposes no arbitrary cap on a
 * legitimately long entry.
 *
 * Shared because two transcripts now need the same landing, and a second copy
 * of this loop would be a second place for the cap-and-top-edge bug to come
 * back.
 */
internal suspend fun LazyListState.scrollToTail() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return

    if (firstVisibleItemIndex != lastIndex) scrollToItem(lastIndex)
    val viewport = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).toFloat()
    if (viewport <= 0f) return

    while (canScrollForward) {
        val before = anchor()
        val consumed = scrollBy(viewport)
        if (consumed <= 0f || anchor() == before) return
    }
}

/** Where the list is parked: the first visible item and how far into it. */
private fun LazyListState.anchor(): Pair<Int, Int> =
    firstVisibleItemIndex to firstVisibleItemScrollOffset
