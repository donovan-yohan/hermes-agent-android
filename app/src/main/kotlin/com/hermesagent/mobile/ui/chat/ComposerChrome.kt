package com.hermesagent.mobile.ui.chat

import com.hermesagent.mobile.data.session.ComposerStatusState

/**
 * The chrome directly above the editor, in the order it renders.
 *
 * These surfaces are one stack, not separate panels: they share a width and one
 * horizontal inset, adjacent joins are flat, and only the outer corners of the
 * whole stack are rounded. Their order is fixed, but which of them is present is
 * not — a session with no background work has no status strip, one with no
 * worktree has no coding row — so the stack is re-derived from what is actually
 * visible and the corners re-flow.
 */
internal enum class ComposerChromePane {
    /** The status strip: background processes, tasks, goal, queue. */
    StatusStack,

    /** The coding row: branch, worktree path, PR number, diff counters. */
    CodingRow,

    /** The composer shell itself. Always present. */
    Composer,
}

/** Which chrome surfaces are on screen right now, and how each is shaped. */
internal data class ComposerChromeInputs(
    /** Whether the status stack renders at least one group. */
    val hasStatusStack: Boolean = false,
    /** Kept as input compatibility; every visible status group joins the run. */
    val statusStackIsSingleGroup: Boolean = false,
    /** Whether an authenticated coding context is available. */
    val hasCodingRow: Boolean = false,
) {
    /** Whether the strip is a pane of the stack rather than a block above it. */
    val statusStackJoinsStack: Boolean get() = hasStatusStack
}

/**
 * The stack's panes in render order, with absent surfaces left out.
 *
 * The composer is always the last pane, so it is always the stack's bottom-most
 * surface: everything else is chrome that can come and go above it, and the
 * stack's rounded top moves to whichever surface is left standing on top.
 */
internal fun composerChromePanes(inputs: ComposerChromeInputs): List<ComposerChromePane> = buildList {
    if (inputs.statusStackJoinsStack) add(ComposerChromePane.StatusStack)
    if (inputs.hasCodingRow) add(ComposerChromePane.CodingRow)
    add(ComposerChromePane.Composer)
}

/** Whether a status stack would render anything at all for this session. */
internal fun statusStackVisible(status: ComposerStatusState?, hasQueue: Boolean): Boolean =
    composerStatusGroupCount(status, hasQueue) > 0

/**
 * Whether a status stack collapses to one group, and so to one contiguous
 * surface that can be joined to the chrome below it.
 */
internal fun statusStackIsSingleGroup(status: ComposerStatusState?, hasQueue: Boolean): Boolean =
    composerStatusGroupCount(status, hasQueue) == 1
