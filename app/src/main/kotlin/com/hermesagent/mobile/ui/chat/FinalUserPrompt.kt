package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.launch

/** UI-only disclosure shared by the source bubble and its sticky owner. */
internal class FinalPromptDisclosure(val messageId: String?, val expanded: MutableState<Boolean>) {
    var bounds: Rect? = null
    var rootOffset = Offset.Zero
}

internal val LocalFinalPromptDisclosure = staticCompositionLocalOf<FinalPromptDisclosure?> { null }

/** Observe completed taps, including consumed child taps, but never consume or classify drags as taps. */
internal fun Modifier.collapsePromptOnOutsideTap(disclosure: FinalPromptDisclosure): Modifier =
    onGloballyPositioned { disclosure.rootOffset = it.positionInRoot() }
        .pointerInput(disclosure) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val wasExpanded = disclosure.expanded.value
                var moved = false
                var up = down.position
                var upTime = down.uptimeMillis
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) moved = true
                    event.changes.forEach { change ->
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        up = change.position
                        upTime = change.uptimeMillis
                    }
                } while (event.changes.any { it.pressed })
                val bounds = disclosure.bounds
                if (wasExpanded && !moved && upTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis && bounds != null &&
                    !bounds.contains(down.position + disclosure.rootOffset) &&
                    !bounds.contains(up + disclosure.rootOffset)) {
                    disclosure.expanded.value = false
                }
            }
        }

/** Same decoration/width as every user turn, with a viewport-bounded body, not a second excerpt. */
@Composable
internal fun FinalUserPromptBubble(
    body: String,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    onReturn: (() -> Unit)? = null,
    onScrollTranscript: ((Float) -> Unit)? = null,
) {
    val disclosure = LocalFinalPromptDisclosure.current ?: return
    val expanded by disclosure.expanded
    val collapsedHeight = minOf(240.dp, viewportHeight * .35f).coerceAtLeast(1.dp)
    val density = LocalDensity.current
    val capPx = with(density) { collapsedHeight.toPx() }
    var overflows by remember(body, capPx) { mutableStateOf(false) }
    val fill = HermesTheme.tokens.userBubble
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded) { if (!expanded) scrollState.scrollTo(0) }
    Box(
        Modifier.heightIn(min = HermesTheme.spacing.touchTarget).widthIn(min = HermesTheme.spacing.touchTarget)
            .testTag(if (onReturn != null) "Pinned final prompt" else "Inline final prompt")
            .then(if (hidden) Modifier else Modifier
            .onGloballyPositioned { disclosure.bounds = it.boundsInRoot() }
            .then(if (overflows || onReturn != null) Modifier.clickable(
                role = Role.Button,
                onClickLabel = if (overflows) { if (expanded) "Collapse prompt" else "Expand prompt" } else "Return to prompt",
            ) { if (overflows) disclosure.expanded.value = !expanded else onReturn?.invoke() } else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "You said: $body"
                if (overflows) stateDescription = if (expanded) "Expanded" else "Collapsed"
                if (onReturn != null) customActions = listOf(CustomAccessibilityAction("Return to prompt") { onReturn(); true })
                if (expanded || onScrollTranscript != null) scrollBy { _, y ->
                    if (expanded) scope.launch { scrollState.scrollBy(y) } else onScrollTranscript?.invoke(y)
                    true
                }
            }),
        contentAlignment = Alignment.TopEnd,
    ) {
    UserTurnBubble(
        body = body,
        contentDescription = null,
        modifier = modifier.then(if (onReturn == null) Modifier.testTag("Final prompt body") else Modifier),
        selectable = !hidden && onReturn == null && (expanded || !overflows),
        textModifier = Modifier
            .heightIn(max = if (expanded) (viewportHeight * .8f).coerceAtLeast(1.dp) else collapsedHeight)
            .verticalScroll(scrollState, enabled = expanded),
        onTextLayout = { overflows = it.size.height > capPx + 1f },
        overlay = {
            if (overflows && !expanded && !hidden) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(minOf(32.dp, collapsedHeight * .45f))
                    .testTag("Prompt clipping fade")
                    .drawBehind { drawRect(Brush.verticalGradient(listOf(fill.copy(alpha = 0f), fill))) })
            }
        },
    )
    }
}
