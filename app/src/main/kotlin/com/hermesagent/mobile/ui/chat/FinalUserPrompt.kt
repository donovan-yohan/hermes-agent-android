package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import kotlinx.coroutines.launch

/** One message's UI-only state; source and sticky mounts share this object. */
internal class FinalPromptDisclosure(val messageId: String?, val expanded: MutableState<Boolean>)

/**
 * One mounted endpoint/session owns disclosure. Durable user-row identity survives re-projection;
 * rendering identity is a tagged fallback, never text, chronology or a list index.
 * Only the same rendering id may enrich its previous fallback into a durable row.
 */
internal class PromptDisclosures {
    private val entries = linkedMapOf<String, FinalPromptDisclosure>()
    private val renderKeys = linkedMapOf<String, String>()
    private data class Mount(val entry: FinalPromptDisclosure, val bounds: Rect)
    private val mounts = mutableMapOf<Any, Mount>()
    var rootOffset = Offset.Zero

    fun entry(turn: com.hermesagent.mobile.data.session.UserTurn): FinalPromptDisclosure {
        val key = turn.rowId?.let { "row:${it.value}" } ?: "render:${turn.id}"
        val previous = renderKeys.put(turn.id, key)
        if (previous == "render:${turn.id}" && previous != key && key !in entries) {
            entries.remove(previous)?.let { entries[key] = it }
        }
        return entries.getOrPut(key) { FinalPromptDisclosure(turn.id, mutableStateOf(false)) }
    }

    fun register(token: Any, entry: FinalPromptDisclosure, bounds: Rect) {
        if (bounds.width > 0 && bounds.height > 0) mounts[token] = Mount(entry, bounds)
        else mounts.remove(token)
    }
    fun unregister(token: Any) { mounts.remove(token) }
    fun expandedMounts(): Map<Any, FinalPromptDisclosure> = mounts
        .filterValues { it.entry.expanded.value }.mapValues { it.value.entry }
    fun collapseOutside(snapshot: Map<Any, FinalPromptDisclosure>, down: Offset, up: Offset) {
        snapshot.values.toSet().forEach { entry ->
            // A retiring source cannot speak for a newly mounted sticky owner (or vice versa).
            val current = snapshot.filter { (token, captured) -> captured === entry && mounts[token]?.entry === entry }
                .keys.mapNotNull { mounts[it]?.bounds }
            if (current.isNotEmpty() && current.none { it.contains(down) || it.contains(up) }) entry.expanded.value = false
        }
    }

    companion object {
        val Saver = androidx.compose.runtime.saveable.mapSaver(
            save = { owner: PromptDisclosures -> mapOf(
                "entries" to owner.entries.flatMap { (key, entry) -> listOf(key, entry.expanded.value.toString()) },
                "renders" to owner.renderKeys.flatMap { (id, key) -> listOf(id, key) },
            ) },
            restore = { saved -> PromptDisclosures().apply {
                (saved["entries"] as List<*>).chunked(2).forEach { (key, expanded) ->
                    entries[key as String] = FinalPromptDisclosure(null, mutableStateOf(expanded == "true"))
                }
                (saved["renders"] as List<*>).chunked(2).forEach { (id, key) -> renderKeys[id as String] = key as String }
            } },
        )
    }
}

internal val LocalPromptDisclosures = staticCompositionLocalOf<PromptDisclosures?> { null }
internal val LocalFinalPromptDisclosure = staticCompositionLocalOf<FinalPromptDisclosure?> { null }

/** Observe completed taps without consuming composer, selection or scrolling gestures. */
internal fun Modifier.collapsePromptOnOutsideTap(disclosures: PromptDisclosures): Modifier =
    onGloballyPositioned { disclosures.rootOffset = it.positionInRoot() }
        .pointerInput(disclosures) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val expandedAtDown = disclosures.expandedMounts()
                var moved = false
                var up = down.position
                var upTime = down.uptimeMillis
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) moved = true
                    event.changes.forEach { change ->
                        if (change.id != down.id || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        up = change.position
                        upTime = change.uptimeMillis
                    }
                } while (event.changes.any { it.pressed })
                if (!moved && upTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                    disclosures.collapseOutside(expandedAtDown, down.position + disclosures.rootOffset, up + disclosures.rootOffset)
                }
            }
        }

/** Three rendered lines by default; expansion is selectable inline and bounded only in chrome. */
@Composable
internal fun FinalUserPromptBubble(
    body: String,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
    hidden: Boolean = false,
    onReturn: (() -> Unit)? = null,
    onScrollTranscript: ((Float) -> Unit)? = null,
    disclosure: FinalPromptDisclosure? = LocalFinalPromptDisclosure.current,
) {
    val disclosure = disclosure ?: return
    val owner = LocalPromptDisclosures.current
    val mountToken = remember(disclosure, hidden) { Any() }
    androidx.compose.runtime.DisposableEffect(owner, mountToken, hidden) {
        onDispose { owner?.unregister(mountToken) }
    }
    val pinned = onReturn != null
    val expanded = disclosure.expanded.value
    val density = LocalDensity.current
    val textStyle = HermesTheme.type.body
    val textMeasurer = rememberTextMeasurer()
    var collapsedLayout by remember(body, density, textStyle) { mutableStateOf<TextLayoutResult?>(null) }
    val overflows = collapsedLayout?.didOverflowHeight == true
    val fill = HermesTheme.tokens.userBubble
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded) { if (!expanded) scrollState.scrollTo(0) }
    Box(
        Modifier.heightIn(min = HermesTheme.spacing.touchTarget).widthIn(min = HermesTheme.spacing.touchTarget)
            .testTag(if (onReturn != null) "Pinned final prompt" else "Inline final prompt")
            .then(if (hidden) Modifier else Modifier
            .onGloballyPositioned { owner?.register(mountToken, disclosure, it.boundsInRoot()) }
            .then(if (overflows || onReturn != null) Modifier.clickable(
                role = Role.Button,
                onClickLabel = if (overflows) { if (expanded) "Collapse prompt" else "Expand prompt" } else "Return to prompt",
            ) { if (overflows) disclosure.expanded.value = !expanded else onReturn?.invoke() } else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = "You said: $body"
                if (overflows) stateDescription = if (expanded) "Expanded" else "Collapsed"
                if (onReturn != null) customActions = listOf(CustomAccessibilityAction("Return to prompt") { onReturn(); true })
                if (pinned && (expanded || onScrollTranscript != null)) scrollBy { _, y ->
                    if (expanded) scope.launch { scrollState.scrollBy(y) } else onScrollTranscript?.invoke(y)
                    true
                }
            }),
        contentAlignment = Alignment.TopEnd,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(1f, fill = false)) {
                UserTurnBubble(
                    body = body,
                    contentDescription = null,
                    modifier = modifier.then(if (onReturn == null) Modifier.testTag("Final prompt body") else Modifier),
                    // Selection belongs to the inline source; sticky chrome never selects.
                    selectable = !hidden && onReturn == null,
                    maxLines = if (!expanded) 3 else Int.MAX_VALUE,
                    textModifier = if (pinned && expanded) Modifier
                        .heightIn(max = (viewportHeight * .8f).coerceAtLeast(1.dp))
                        .verticalScroll(scrollState) else Modifier,
                    onTextLayout = {
                        // Expanded owners can remount or reflow without ever laying out collapsed.
                        // Measure at this body's actual constraint, including return-control space.
                        collapsedLayout = if (expanded) textMeasurer.measure(
                            text = body,
                            style = textStyle,
                            maxLines = 3,
                            constraints = Constraints(maxWidth = it.layoutInput.constraints.maxWidth),
                        ) else it
                    },
                    overlay = {
                        if (overflows && !expanded && !hidden) {
                            val layout = checkNotNull(collapsedLayout)
                            val fadeHeight = with(density) { (layout.getLineBottom(2) - layout.getLineTop(2)).toDp() }
                            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(fadeHeight)
                                .testTag("Prompt clipping fade")
                                .drawBehind { drawRect(Brush.verticalGradient(listOf(fill.copy(alpha = 0f), fill))) })
                        }
                    },
                )
            }
            // Keep return beside the capped body, not in a second chrome row.
            if (!hidden && onReturn != null && overflows) {
                HermesIconButton(
                    icon = HermesIcon.ArrowUp,
                    contentDescription = "Return to prompt",
                    onClick = onReturn,
                )
            }
        }
    }
}
