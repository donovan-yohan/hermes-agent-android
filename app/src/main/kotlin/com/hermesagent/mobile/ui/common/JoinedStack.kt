package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Where one surface sits inside the composer's chrome run.
 *
 * The run is the mobile adaptation for chrome that has to read as one panel:
 * the status strip (background work, tasks, queue), the coding row, and the
 * composer shell. Stood up as independent panels, each rounded all four of its
 * own corners and kept its own horizontal inset and its own gap, so a flat
 * bottom met a rounded top across a visible seam and the widths did not line up.
 * In a run every surface shares one width and one horizontal inset, the joints
 * between adjacent surfaces are flat, and only the two outer corners of the
 * whole run are rounded.
 *
 * Only the geometry is the run's business. Each surface still paints its own
 * fill and outline, with the [JoinedPaneLayout] the run hands it — which is what
 * keeps a status group's own stroke token and the composer's own stroke token
 * intact — and because adjacent shapes meet exactly, the line closing a joint is
 * the same line drawn from both sides.
 */
enum class JoinedEdge {
    /** The only surface in the run: fully rounded. */
    Only,

    /** Top of the run: rounded above, flat below. */
    Start,

    /** Interior: flat on every side. */
    Middle,

    /** Bottom of the run: flat above, rounded below. */
    End,
}

/**
 * The joint position of [index] in a run of [count] surfaces.
 *
 * Visibility is expressed by leaving a surface out of the list, so a surface
 * that appears or disappears moves every joint below it: the corners are
 * re-derived from the list that actually renders, never remembered.
 */
internal fun joinedEdge(index: Int, count: Int): JoinedEdge = when {
    count <= 1 -> JoinedEdge.Only
    index <= 0 -> JoinedEdge.Start
    index >= count - 1 -> JoinedEdge.End
    else -> JoinedEdge.Middle
}

/**
 * Folds a surface's position inside its container into its position in the
 * enclosing run, so a run of runs still rounds only the outer corners.
 *
 * The outer side comes from the container's own position ([this]) and the inner
 * side from where the surface sits inside it ([inner]). A container that is the
 * whole run passes [inner] through unchanged.
 */
internal fun JoinedEdge.combine(inner: JoinedEdge): JoinedEdge {
    if (this == JoinedEdge.Only) return inner
    if (inner == JoinedEdge.Only) return this
    val topRounded = this == JoinedEdge.Start && inner == JoinedEdge.Start
    val bottomRounded = this == JoinedEdge.End && inner == JoinedEdge.End
    return when {
        topRounded && bottomRounded -> JoinedEdge.Only
        topRounded -> JoinedEdge.Start
        bottomRounded -> JoinedEdge.End
        else -> JoinedEdge.Middle
    }
}

/**
 * The clip/fill/border shape one surface of a run owns.
 *
 * A joint is square, so a surface in the middle has no radius at all: its
 * outline is a plain rectangle, whose top and bottom edges land exactly on the
 * neighbours' edges.
 */
internal fun joinedPaneShape(edge: JoinedEdge, radius: Dp): RoundedCornerShape = when (edge) {
    JoinedEdge.Only -> RoundedCornerShape(radius)
    JoinedEdge.Start -> RoundedCornerShape(
        topStart = radius,
        topEnd = radius,
        bottomEnd = 0.dp,
        bottomStart = 0.dp,
    )
    JoinedEdge.Middle -> RoundedCornerShape(0.dp)
    JoinedEdge.End -> RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 0.dp,
        bottomEnd = radius,
        bottomStart = radius,
    )
}

/** The one radius a run rounds its outer corners with. */
internal val JoinedStackRadius: Dp = 10.dp

/** The radius a lone panel — one that is not in a run — rounds its corners with. */
internal val StandaloneChromeRadius: Dp = 16.dp

/** Where one surface landed in its run, and the geometry it must paint with. */
data class JoinedPaneLayout(
    val edge: JoinedEdge,
    val radius: Dp = JoinedStackRadius,
) {
    val shape: RoundedCornerShape get() = joinedPaneShape(edge, radius)

    /** True while this surface owns no rounded corner at all. */
    val isInterior: Boolean get() = edge == JoinedEdge.Middle

    /** Whether this surface is the run's topmost, and so carries its rounded top. */
    val isTop: Boolean get() = edge == JoinedEdge.Only || edge == JoinedEdge.Start

    /** Whether this surface is the run's bottom-most, and so rounds its bottom. */
    val isBottom: Boolean get() = edge == JoinedEdge.Only || edge == JoinedEdge.End

    /** This surface's own fill, clipped to its own corners. */
    fun fill(color: Color): Modifier = Modifier.background(color, shape)

    /** This surface's own outline, in the shape its position in the run allows. */
    fun outline(color: Color, width: Dp = 1.dp): Modifier = Modifier.border(width, color, shape)

    /** Both, in the order a surface paints them. */
    fun panel(color: Color, stroke: Color, width: Dp = 1.dp): Modifier =
        fill(color).then(outline(stroke, width))
}

/**
 * One surface of the chrome run.
 *
 * [fill] is painted by the run behind the content, so a surface that is
 * transparent in its own right still sits on the run's surface rather than on
 * the transcript.
 */
internal class JoinedPane(
    /** Stable identity, for the pane's own tag and for readability at call sites. */
    val key: String,
    val fill: Color = Color.Transparent,
    /**
     * The outline this pane wants, or null for a surface that has never had one
     * of its own.
     *
     * The run draws it in the pane's own stroke token, in the pane's own shape
     * for its position in the run. Two adjacent panes therefore draw the line
     * between them at the same pixels, in their own tokens, with no gap and no
     * radius: the joint is one line, not two arcs with a seam between them.
     */
    val stroke: Color? = null,
    val content: @Composable (JoinedPaneLayout) -> Unit,
)

/** Stable tag for one pane of a run, so a test can measure what a person sees. */
internal fun joinedPaneTag(key: String): String = "Joined pane $key"

/** The tag of the run itself. */
internal const val JOINED_STACK_TAG = "Composer chrome stack"

/**
 * A vertical run of surfaces that reads as one object: one width, one horizontal
 * inset, no gap, flat joints, rounded corners only at the two outer ends.
 *
 * Surfaces are passed in render order and are simply absent when they do not
 * apply, so chrome appearing or disappearing re-flows the corners.
 */
@Composable
internal fun JoinedChromeStack(
    panes: List<JoinedPane>,
    modifier: Modifier = Modifier,
    inset: Dp = HermesTheme.spacing.pageInset,
    radius: Dp = JoinedStackRadius,
) {
    if (panes.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = inset).testTag(JOINED_STACK_TAG)) {
        panes.forEachIndexed { index, pane ->
            val layout = JoinedPaneLayout(joinedEdge(index, panes.size), radius)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(layout.shape)
                    .background(pane.fill)
                    .then(pane.stroke?.let { Modifier.border(1.dp, it, layout.shape) } ?: Modifier)
                    .testTag(joinedPaneTag(pane.key)),
            ) {
                pane.content(layout)
            }
        }
    }
}

/** A lone panel's own outline, in the same pen the grouped surfaces use. */
internal fun Modifier.standaloneChromeOutline(
    stroke: Color,
    radius: Dp = StandaloneChromeRadius,
    width: Dp = 1.dp,
): Modifier = border(width, stroke, RoundedCornerShape(radius))
