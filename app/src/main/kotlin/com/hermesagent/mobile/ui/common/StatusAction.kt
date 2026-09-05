package com.hermesagent.mobile.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Where a status line goes when it is naming a problem some other surface
 * fixes, and what that surface is called.
 *
 * [spokenDestination] is the accessible half: a line that says "sign in before
 * reconnecting" tells an eye what is wrong but not where to go, so the spoken
 * name of the destination is appended to it. Carried with the callback rather
 * than hardcoded by the control, so a status line does not have to know which
 * surface this particular state points at.
 */
data class StatusAction(
    val spokenDestination: String,
    val onClick: () -> Unit,
)

/**
 * A hit area at least [minHeight] tall that does not make the line it sits on
 * that tall.
 *
 * The touch floor is a *pointer* rule, not a typographic one. Taken as layout
 * height it pushes its own row apart: the chat chrome's one-line status sat in
 * a 48dp box, which made the title-and-subtitle block 73dp against 48dp icon
 * buttons and left the title floating above the chrome's centre line. This
 * reports the content's own height to the parent and measures the content at
 * the floor, so the clickable overflows evenly above and below the words it
 * names — invisible, unclipped, and still a full 48dp band to a thumb and to
 * TalkBack.
 *
 * It must sit *outside* the `clickable` it is expanding, and whatever is inside
 * has to centre itself in the band it is handed: a `Row` does that with
 * `verticalAlignment`, a `Text` with `wrapContentHeight`.
 *
 * **What this adds over what Compose already does.** Compose expands any
 * `clickable` smaller than `ViewConfiguration.minimumTouchTargetSize` — 48dp —
 * during hit testing on its own, so a bare one-line clickable is *already*
 * tappable outside its bounds. But that expansion is a **fallback**: pointer
 * input resolves the visible bounds of every candidate first and only consults
 * the expanded areas when nothing was hit directly, so a tap in the overflow
 * loses to any sibling whose real bounds cover it — which on a crowded status
 * row is the neighbouring control, not empty space. The band this modifier
 * measures is first-class: it is the node's own pointer region, it wins ties by
 * being an actual hit, and it costs the row no layout height, which the plain
 * `heightIn(min = 48.dp)` it replaces did.
 *
 * **Constraints, both inherited from asking for an intrinsic height.**
 *
 * - `minIntrinsicHeight` **throws** on content that subcomposes —
 *   `BoxWithConstraints`, `LazyColumn`, `LazyRow`, anything built on
 *   `SubcomposeLayout` — because there is nothing to measure until composition
 *   runs. Wrapping one of those in this is a crash, not a layout bug.
 * - The reported height is the intrinsic one while the *measured* height is the
 *   band, so content whose real height is not what its intrinsic reports —
 *   anything sized by weight, aspect ratio or a fill — draws outside the height
 *   its parent reserved and overlaps its neighbours.
 *
 * Both say the same thing: this is for **one-line controls** whose height is a
 * line of type. That is every caller today — the chat status door, the context
 * meter, the approval chip — and a second-guessing use belongs in a `Box` with
 * an explicit height instead.
 */
fun Modifier.touchTargetOverflow(minHeight: Dp): Modifier = layout { measurable, constraints ->
    val floor = minHeight.roundToPx()
    // The height the content would have taken on its own. Asked before the
    // measure, because a measured child can only be measured once and the
    // whole point is to hand it constraints it would not have chosen.
    val visible = measurable.minIntrinsicHeight(constraints.maxWidth)
    val band = maxOf(floor, visible)
    val placeable = measurable.measure(
        constraints.copy(minHeight = band, maxHeight = maxOf(band, constraints.maxHeight)),
    )
    val height = visible.coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) {
        placeable.place(0, (height - placeable.height) / 2)
    }
}

/**
 * Makes a status line the door it is already describing, or leaves it as prose.
 *
 * One home for the three rules a tappable status owes: a touch-target floor, a
 * button role, and a spoken name that carries both the line and where it goes.
 * Two surfaces render such a line — the chat header subtitle and the composer
 * status — and an accessibility fix to the spoken form has to reach both.
 *
 * The floor is [touchTargetOverflow]'s, so a status line still costs one line
 * of layout in the row it shares.
 */
@Composable
fun Modifier.statusAction(line: String, action: StatusAction?): Modifier =
    if (action == null) {
        this
    } else {
        touchTargetOverflow(HermesTheme.spacing.touchTarget)
            .clickable(role = Role.Button, onClick = action.onClick)
            .semantics { contentDescription = "$line. ${action.spokenDestination}" }
            .wrapContentHeight(Alignment.CenterVertically)
    }
