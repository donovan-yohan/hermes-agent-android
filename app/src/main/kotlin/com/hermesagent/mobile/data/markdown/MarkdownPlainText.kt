package com.hermesagent.mobile.data.markdown

import com.hermesagent.mobile.data.attachments.ImageRefLines

/**
 * What "copy this reply" puts on the clipboard.
 *
 * Desktop has no copy control — the browser's own selection is the affordance,
 * so what lands on the clipboard there is the *rendered* text of the message
 * element, not its markdown source (`styles.css:1176-1180` @
 * `f82f2dbabd9e66b714f2b4f8a40447fe0c13e732` makes exactly that subtree
 * selectable). A phone cannot practically drag-select a long reply, so Android
 * adds an explicit action — but it must hand over the same thing the browser
 * would, or the two clients disagree about what a reply *is*.
 *
 * So this is a projection of the blocks the transcript already parsed, not of
 * the source string — which also means deciding what to copy costs no second
 * CommonMark pass per streamed token:
 *
 *  - Emphasis, strong and inline-code markers are gone; only their text is
 *    left, because that is what the reader sees and what a browser copies.
 *  - Fences hand over their code without the fence line or the language label
 *    (the label is chrome, and [com.hermesagent.mobile.ui.chat] renders it
 *    outside the selectable region for the same reason).
 *  - List markers are *kept*. A browser drops them because the marker is not a
 *    text node; here the whole reply is copied at once and a bullet list that
 *    arrives as unlabelled lines has lost its structure.
 *  - `@image:` reference lines are stripped where they stand alone, but never
 *    inside a fence — see [copyText].
 *
 * Tool runs, reasoning and status rows are separate transcript entries, so
 * they cannot reach this function — a reply's scaffolding is excluded by
 * construction rather than by filtering.
 */
fun List<MarkdownBlock>.replyPlainText(): String =
    mapNotNull { block -> block.copyText().takeIf(String::isNotBlank) }
        .joinToString("\n\n")

/**
 * One block's contribution, with the `@image:` strip applied per block rather
 * than to the source.
 *
 * Stripping the raw markdown first would be simpler and is what the *user*
 * bubble does ([ImageRefLines.split] at its render site), but the two inputs
 * are not the same: a user turn's refs are appended by the gateway, whereas an
 * assistant reply that mentions `@image:` is far more likely to be *explaining*
 * the format inside a fence. A line-anchored regex over the source cannot tell
 * those apart and would gut the fence, so the strip runs on the projection of
 * each block and never on a fence at all.
 *
 * The residue: a ref that CommonMark folds into a paragraph through a soft line
 * break stops being on a line of its own, so it survives. That is the shape the
 * gateway writes — and it writes it only on user turns, which never reach here.
 */
private fun MarkdownBlock.copyText(): String = when (this) {
    is MarkdownBlock.CodeFence -> code.trimEnd('\n')
    else -> ImageRefLines.split(plainText()).first
}

private fun MarkdownBlock.plainText(): String = when (this) {
    is MarkdownBlock.Paragraph -> spans.spansText()
    is MarkdownBlock.Heading -> spans.spansText()

    is MarkdownBlock.Bullets ->
        items.joinToString("\n") { item -> "\u2022 ${item.spansText()}" }

    is MarkdownBlock.Numbered ->
        items.mapIndexed { index, item -> "${start + index}. ${item.spansText()}" }
            .joinToString("\n")

    // Tab-separated, header first: the shape every spreadsheet and editor
    // pastes back as the same grid the transcript drew.
    is MarkdownBlock.Table ->
        (listOf(header) + rows).joinToString("\n") { row ->
            row.joinToString("\t") { cell -> cell.spans.spansText() }
        }

    is MarkdownBlock.CodeFence -> code.trimEnd('\n')
}

/** Erasure makes this a clash with the block projection, hence the name. */
private fun List<InlineSpan>.spansText(): String = joinToString("") { it.text }
