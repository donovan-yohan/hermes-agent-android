package com.hermesagent.mobile.data.session

/**
 * ON-DEMAND OLDER-PAGE BACKFILL for the transcript window.
 *
 * A session hydrates with its newest page only; `Show earlier messages` asks
 * for the page before it and prepends the rows the store is missing. Ported
 * from `apps/desktop/src/app/chat/transcript-backfill.ts` and
 * `apps/desktop/src/store/transcript-tail.ts` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 *
 * Offsets follow the route's `order=latest` semantics: measured back from the
 * NEWEST persisted row, with each page returned in chronological order
 * (`hermes_state.py:12900-12904`). Rows persisted after hydration shift that
 * origin, so a fetched page can overlap rows already held — the prepend dedupes
 * on the durable row id first and the offset still advances by the fetched
 * count, which self-corrects the drift on the next page
 * (`transcript-backfill.ts:10-15`).
 */

/**
 * Prepend an older page, dropping the rows the store already holds.
 *
 * Dedupe order is the durable [TranscriptEntry.rowId] first and the rendering
 * [TranscriptEntry.id] second (`transcript-backfill.ts:44-57`): the row id is
 * the backend's own address and survives a re-projection, while the rendering
 * key only has to be unique down one list.
 *
 * An empty store refuses the prepend (`:37-42`). Backfill only makes sense
 * under an already-hydrated tail; an empty transcript here means the session
 * was swapped or wiped while the page was in flight, and prepending would paint
 * an old page as the whole conversation.
 *
 * Reference identity survives a no-op, so a page that adds nothing does not
 * recompose the transcript.
 */
internal fun mergeOlderTranscriptPage(
    existing: List<TranscriptEntry>,
    olderPage: List<TranscriptEntry>,
): List<TranscriptEntry> {
    if (existing.isEmpty() || olderPage.isEmpty()) return existing

    val heldRowIds = HashSet<Long>(existing.size)
    val heldIds = HashSet<String>(existing.size)
    for (entry in existing) {
        entry.rowId?.let { heldRowIds += it.value }
        heldIds += entry.id
    }

    val fresh = olderPage.filter { entry ->
        val rowId = entry.rowId
        (rowId == null || rowId.value !in heldRowIds) && entry.id !in heldIds
    }
    if (fresh.isEmpty()) return existing

    // Both halves are already chronological and every fresh row is strictly
    // older than the tail, so a prepend never reorders or rewrites what is on
    // screen: a whole turn stays whole and the sticky prompt keeps its owner.
    return fresh + existing
}

/** A refreshed tail landed on a transcript that had already backfilled. */
internal data class GraftedTranscript(
    val entries: List<TranscriptEntry>,
    /**
     * Whether older pages survived ahead of the refreshed tail — so the caller
     * knows to keep paging from where it had already read rather than from the
     * fresh tail's own end.
     *
     * Deliberately not a count. An offset counts stored rows and this list holds
     * projected entries, and the projection both splits rows (one assistant turn
     * carrying reasoning becomes two) and drops them (`display_kind: "hidden"`,
     * `[System:` notices, tool-call-only assistant rows). Neither direction can
     * be recovered from the entries, so the row arithmetic stays where the row
     * counts are: the window's own offsets.
     */
    val keptPrefix: Boolean,
)

/**
 * Re-anchor a refreshed TAIL onto a transcript that has backfilled older pages
 * (`transcript-backfill.ts:66-93`).
 *
 * Re-opening a session re-reads only its newest page; replacing the store with
 * that page outright would silently drop everything `Show earlier messages`
 * already loaded. Find where the refreshed tail begins inside the previous
 * transcript and keep the older prefix. When no anchor is found — a compaction
 * rewrite, or a different session — the refreshed tail is authoritative, which
 * is the behaviour from before the window existed.
 *
 * [GraftedTranscript.keptPrefix] says only whether anything survived ahead of
 * the tail. How far the next page then starts is the window's arithmetic, in the
 * stored rows an offset is actually made of.
 */
internal fun graftRefreshedTailOntoBackfill(
    refreshedTail: List<TranscriptEntry>,
    previous: List<TranscriptEntry>,
): GraftedTranscript {
    if (refreshedTail.isEmpty() || previous.isEmpty()) {
        return GraftedTranscript(refreshedTail, keptPrefix = false)
    }

    val first = refreshedTail.first()
    val anchor = previous.indexOfFirst { entry ->
        (first.rowId != null && entry.rowId == first.rowId) || entry.id == first.id
    }
    if (anchor <= 0) return GraftedTranscript(refreshedTail, keptPrefix = false)

    return GraftedTranscript(previous.take(anchor) + refreshedTail, keptPrefix = true)
}

/** Where the next older page starts, and whether one is believed to exist. */
internal data class TranscriptPageState(
    /** Offset, measured back from the newest row, of the next older page. */
    val nextOffset: Int,
    /** The page came back full, so older rows likely exist beyond it. */
    val possiblyTruncated: Boolean,
)

/**
 * Read one transcript page's bookkeeping off the answer
 * (`transcript-tail.ts:82-96`).
 *
 * The wire carries no `has_more`: `pagination` reports `limit`, `offset` and
 * `returned` and nothing else (`hermes_cli/web_routers/sessions.py:709-714` @
 * `3ca096de`), so truncation is inferred from a page that came back full.
 * A missing or non-positive `limit` is a backend that ignored the paging query
 * and answered the whole transcript: nothing is truncated, and the next offset
 * is simply everything it returned.
 */
internal fun transcriptPageState(
    requestedOffset: Int,
    echoedOffset: Int?,
    echoedLimit: Int?,
    returned: Int,
): TranscriptPageState {
    if (echoedLimit == null || echoedLimit <= 0) {
        return TranscriptPageState(nextOffset = returned, possiblyTruncated = false)
    }
    return TranscriptPageState(
        nextOffset = (echoedOffset ?: requestedOffset) + returned,
        possiblyTruncated = returned >= echoedLimit,
    )
}
