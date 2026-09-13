package com.hermesagent.mobile.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.hermesagent.mobile.data.session.ComposerBackgroundProcess
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerGoalState
import com.hermesagent.mobile.data.session.ComposerPreviewArtifact
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerSubagentStatus
import com.hermesagent.mobile.data.session.ComposerTodoState
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.delay

/**
 * Session-scoped Gateway status, deliberately separate from transcript rows.
 * The fixed max height gives a long status feed its own scroll region instead
 * of displacing the editor below the IME.
 */
@Composable
fun ComposerStatusStack(
    activeSessionId: String?,
    status: ComposerStatusState?,
    onRefreshProcesses: () -> Unit = {},
    onReconcileProcesses: suspend () -> Unit = {},
    onKillProcess: (String) -> Unit = {},
    hasQueue: Boolean = false,
    queueContent: (@Composable () -> Unit)? = null,
    fusedToComposer: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visiblePreviews = remember(activeSessionId, status?.previewArtifacts) {
        status?.previewArtifacts.orEmpty().take(MAX_PREVIEW_ROWS).distinctBy(ComposerPreviewArtifact::id)
    }
    val visibleBackgroundProcesses = status?.backgroundProcesses.orEmpty().take(MAX_BACKGROUND_ROWS)
    var dismissedPreviewIds by rememberSaveable(activeSessionId) { mutableStateOf(emptySet<String>()) }
    val previews = visiblePreviews.filterNot { it.id in dismissedPreviewIds }
    val visibleGroupCount = composerStatusGroupCount(status, hasQueue, previews.size)
    if (visibleGroupCount == 0) return
    val fuseSingleGroup = fusedToComposer && visibleGroupCount == 1
    ReconcileSilentExits(
        activeSessionId,
        visibleBackgroundProcesses,
        onReconcileProcesses,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = MAX_STACK_HEIGHT)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Composer status" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        status?.goal?.takeIf { it.state != ComposerGoalState.None }?.let { goal ->
            StatusGroup(
                stateKey = "${activeSessionId}:goal",
                title = goal.state.groupLabel(),
                // Only the task list opens itself:
                // `defaultCollapsed={group.type !== 'todo'}`
                // (`apps/desktop/src/app/chat/composer/status-stack/index.tsx:236`
                // @ `564aef2946`). That condition used to spare the goal group as
                // well, and `5b181e511a` deliberately stopped sparing it.
                //
                // The exception is Unknown, which Desktop has no equivalent of:
                // its goal state is a typed field, ours is parsed out of a text
                // status line and is left neutral rather than fabricating an
                // active goal (`GatewaySessionRepository.kt:5458-5463`). A header
                // that cannot name the state cannot stand in for the body, so an
                // unrecognised goal line keeps the group open — the raw text is
                // then the only thing on screen that says anything at all.
                defaultExpanded = goal.state == ComposerGoalState.Unknown,
                followDefaultExpandedChanges = true,
                fusedToComposer = fuseSingleGroup,
            ) {
                StatusText(goal.title ?: goal.rawText)
                goal.detail?.takeIf(String::isNotBlank)?.let { StatusText(it) }
            }
        }
        status?.todos?.takeIf { it.isNotEmpty() }?.let { todos ->
            // Desktop counts every visible task in the denominator. Cancelled
            // rows stay visible/muted but do not count as completed.
            val done = todos.count { it.state == ComposerTodoState.Completed }
            StatusGroup(
                stateKey = "${activeSessionId}:todo",
                title = "Tasks $done/${todos.size}",
                defaultExpanded = true,
                icon = HermesIcon.Checklist,
                fusedToComposer = fuseSingleGroup,
            ) {
                todos.forEach { todo -> TodoStatusRow(todo) }
            }
        }
        status?.subagents?.take(MAX_SUBAGENT_ROWS)?.takeIf { it.isNotEmpty() }?.let { agents ->
            StatusGroup(
                "${activeSessionId}:subagents",
                "Subagents",
                defaultExpanded = false,
                count = agents.size,
                fusedToComposer = fuseSingleGroup,
            ) {
                agents.forEach { agent ->
                    StatusText(agent.currentTool?.let { "${agent.title} · $it" } ?: agent.title)
                }
            }
        }
        visibleBackgroundProcesses.takeIf { it.isNotEmpty() }?.let { processes ->
            StatusGroup(
                "${activeSessionId}:background",
                "Background",
                defaultExpanded = false,
                count = processes.size,
                fusedToComposer = fuseSingleGroup,
            ) {
                processes.forEach { process ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusText("${process.state.label()} · ${process.title}", Modifier.weight(1f))
                        if (process.state == ComposerBackgroundProcessState.Running) {
                            TextButton(
                                label = "Stop",
                                onClick = { onKillProcess(process.id) },
                                color = HermesTheme.tokens.destructive,
                                modifier = Modifier.semantics { contentDescription = "Stop background process ${process.title}" },
                            )
                        }
                    }
                }
                TextButton(
                    label = "Refresh",
                    onClick = onRefreshProcesses,
                    modifier = Modifier.semantics { contentDescription = "Refresh background processes" },
                )
            }
        }
        if (previews.isNotEmpty()) {
            StatusGroup(
                "${activeSessionId}:previews",
                "Previews",
                defaultExpanded = false,
                count = previews.size,
                fusedToComposer = fuseSingleGroup,
            ) {
                previews.forEach { preview ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusText(preview.detail?.let { "${preview.title} · $it" } ?: preview.title, Modifier.weight(1f))
                        TextButton(
                            label = "Dismiss",
                            onClick = { dismissedPreviewIds = dismissedPreviewIds + preview.id },
                            modifier = Modifier.semantics { contentDescription = "Dismiss preview ${preview.title}" },
                        )
                    }
                }
            }
        }
        if (status?.isCompacting == true) StatusText("Hermes is compacting this session.")
        status?.gatewayQueuedPrompts?.takeIf { it.isNotEmpty() }?.let { prompts ->
            StatusGroup(
                "${activeSessionId}:gateway-queue",
                "Queued next",
                defaultExpanded = false,
                count = prompts.size,
                fusedToComposer = fuseSingleGroup,
            ) {
                prompts.forEach { prompt -> StatusText(prompt.text) }
            }
        }
        // Queue is intentionally the final status-stack group so its expanded
        // rows share this bounded scroll region rather than pushing the IME
        // composer off screen.
        queueContent?.invoke()
    }
}

/**
 * Retire a background row whose process died without saying so.
 *
 * A process started without `notify_on_complete` emits no event when it exits,
 * so nothing retires its row: it keeps reading `Running · <title>` and keeps
 * offering a Stop for something already gone. Desktop's answer is a 5 second
 * `process.list` interval, armed while a running row is on screen and disarmed
 * with the pane
 * (`apps/desktop/src/app/chat/composer/status-stack/index.tsx:41-43,151-163`
 * @ `564aef2946`).
 *
 * That interval is the one thing this path must not copy. Every tick is a
 * radio wake on a phone, and this app deliberately owns no timer here: the
 * session-open seed (`ChatScreen.kt:803`), the repository's coalesced
 * event-driven refresh (`GatewaySessionRepository.kt:5355-5371`) and the
 * Background group's own Refresh are the whole refresh story, and #233 rates
 * that calm as the property worth keeping.
 *
 * So the net is bounded instead of periodic, and every edge it uses is one the
 * app already owns:
 *
 *  * it exists only while a row *claims* Running, in the session on screen —
 *    the composable is only in the tree for the open session, and it leaves
 *    the tree the moment nothing claims to be running;
 *  * it runs only while the host is RESUMED, so a backgrounded app never wakes
 *    the radio, and returning to the foreground re-arms it — the mobile shape
 *    of Desktop's `paneVisible` gate, and the moment a silent exit is most
 *    likely to have happened unseen;
 *  * it walks a short widening ladder and then *stops*. Three checks, not an
 *    interval: a check that repeats forever is the polling this app does not
 *    do. New evidence — a change in which ids claim Running — starts a fresh
 *    ladder, so the work is bounded by real events rather than by a clock, and
 *    when the picture stops changing the app goes quiet again and the manual
 *    Refresh stays the explicit escape.
 *
 * What it does not catch, and `docs/parity/composer-status-stack.md` records:
 * a process that dies silently while the reader keeps the session in front of
 * them for longer than the ladder. Desktop catches that within five seconds;
 * here it waits for the next foreground return, the next process event, or
 * Refresh.
 */
@Composable
private fun ReconcileSilentExits(
    activeSessionId: String?,
    processes: List<ComposerBackgroundProcess>,
    onReconcileProcesses: suspend () -> Unit,
) {
    val runningKey = processes
        .filter { it.state == ComposerBackgroundProcessState.Running }
        .map(ComposerBackgroundProcess::id)
        .sorted()
        .joinToString("|")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // The ladder must not restart because the caller handed down a new lambda;
    // only the session and the running set may re-arm it.
    val reconcile by rememberUpdatedState(onReconcileProcesses)
    // A conditional call, not an early return: this is what disposes the
    // ladder the moment the answer retires the last Running claim, and what
    // starts a fresh one when the set of claims changes.
    if (activeSessionId != null && runningKey.isNotEmpty()) {
        LaunchedEffect(activeSessionId, runningKey, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                RECONCILE_LADDER_MILLIS.forEach { wait ->
                    delay(wait)
                    reconcile()
                }
            }
        }
    }
}

internal fun composerStatusGroupCount(
    status: ComposerStatusState?,
    hasQueue: Boolean,
    previewCount: Int = status?.previewArtifacts?.size ?: 0,
): Int = listOf(
    status?.goal?.state?.let { it != ComposerGoalState.None } == true,
    status?.todos?.isNotEmpty() == true,
    status?.subagents?.isNotEmpty() == true,
    status?.backgroundProcesses?.isNotEmpty() == true,
    previewCount > 0,
    status?.isCompacting == true,
    status?.gatewayQueuedPrompts?.isNotEmpty() == true,
    hasQueue,
).count { it }

@Composable
private fun StatusGroup(
    stateKey: String,
    title: String,
    defaultExpanded: Boolean,
    followDefaultExpandedChanges: Boolean = false,
    count: Int? = null,
    icon: HermesIcon? = null,
    fusedToComposer: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(defaultExpanded) }
    var automaticallyExpanded by rememberSaveable(stateKey) { mutableStateOf(defaultExpanded) }
    if (followDefaultExpandedChanges) {
        LaunchedEffect(defaultExpanded) {
            if (defaultExpanded) {
                if (!expanded) {
                    expanded = true
                    automaticallyExpanded = true
                }
            } else if (automaticallyExpanded) {
                expanded = false
                automaticallyExpanded = false
            }
        }
    }
    val tokens = HermesTheme.tokens
    val headerText = tokens.textTertiary.alphaMultiply(0.92f)
    val groupIcon = tokens.textTertiary.alphaMultiply(0.70f)
    val shape = if (fusedToComposer) {
        RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
    } else {
        RoundedCornerShape(10.dp)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, tokens.strokeTertiary, shape)
            .background(if (fusedToComposer) tokens.cardSurface else tokens.widgetSurface, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable {
                    expanded = !expanded
                    automaticallyExpanded = false
                }
                .semantics {
                    contentDescription = buildString {
                        append(title)
                        count?.let { append(", $it") }
                        append(if (expanded) ", collapse" else ", expand")
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HermesIconGlyph(
                icon = if (expanded) HermesIcon.ChevronDown else HermesIcon.ChevronRight,
                color = headerText,
                size = 13.sp,
            )
            if (icon != null) {
                HermesIconGlyph(icon = icon, color = groupIcon, size = 13.sp)
            }
            Text(
                text = count?.let { "$title · $it" } ?: title,
                style = HermesTheme.type.caption,
                color = headerText,
                modifier = Modifier.padding(start = if (icon == null) 6.dp else 8.dp),
            )
        }
        if (expanded) Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) { content() }
    }
}

@Composable
private fun TodoStatusRow(todo: ComposerTodoStatus) {
    val tokens = HermesTheme.tokens
    val mutedTask = tokens.textTertiary.alphaMultiply(0.75f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .semantics { contentDescription = "${todo.state.spokenLabel()} task: ${todo.title}" }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            when (todo.state) {
                ComposerTodoState.Pending -> PendingTodoGlyph()
                ComposerTodoState.InProgress -> TodoSpinner()
                ComposerTodoState.Completed -> HermesIconGlyph(
                    icon = HermesIcon.PassFilled,
                    color = tokens.taskCompleted,
                    size = 13.sp,
                )
                ComposerTodoState.Cancelled -> HermesIconGlyph(
                    icon = HermesIcon.CircleSlash,
                    color = tokens.textTertiary.alphaMultiply(0.45f),
                    size = 13.sp,
                )
                ComposerTodoState.Unknown -> HermesIconGlyph(
                    icon = HermesIcon.Error,
                    color = tokens.textTertiary.alphaMultiply(0.45f),
                    size = 13.sp,
                )
            }
        }
        Text(
            text = todo.title,
            style = HermesTheme.type.caption,
            color = if (todo.state == ComposerTodoState.InProgress) tokens.textPrimary else mutedTask,
            maxLines = 2,
        )
    }
}

@Composable
private fun PendingTodoGlyph() {
    val color = HermesTheme.tokens.textTertiary.alphaMultiply(0.60f)
    Canvas(Modifier.size(11.dp).clearAndSetSemantics {}) {
        drawCircle(
            color = color,
            style = Stroke(
                width = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.7.dp.toPx())),
            ),
        )
    }
}

@Composable
private fun TodoSpinner() {
    val frames = remember { listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏") }
    val transition = rememberInfiniteTransition(label = "todo-spinner")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = frames.size.toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 800, easing = LinearEasing)),
        label = "todo-spinner-frame",
    )
    Text(
        text = frames[phase.toInt().coerceIn(frames.indices)],
        style = HermesTheme.type.caption,
        color = HermesTheme.tokens.textTertiary.alphaMultiply(0.80f),
        modifier = Modifier.clearAndSetSemantics {},
    )
}

@Composable
private fun StatusText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = HermesTheme.type.caption,
        color = HermesTheme.tokens.scaffoldText,
        modifier = modifier.padding(vertical = 4.dp),
    )
}

private fun ComposerTodoState.spokenLabel(): String = when (this) {
    ComposerTodoState.Pending -> "Pending"
    ComposerTodoState.InProgress -> "In progress"
    ComposerTodoState.Completed -> "Completed"
    ComposerTodoState.Cancelled -> "Cancelled"
    ComposerTodoState.Unknown -> "Unknown"
}

/**
 * Desktop labels the goal group with the goal's state, not with the bare word
 * "Goal": `Goal active` / `Goal paused` / `Goal waiting` / `Goal done`
 * (`apps/desktop/src/app/chat/composer/status-stack/index.tsx:60-69`, strings at
 * `apps/desktop/src/i18n/en.ts:2894,2896-2898`, both @ `564aef2946`). That is
 * what lets Desktop keep the group collapsed: the header alone still says what
 * the goal is doing.
 *
 * Desktop's chain ends at `goalActive` for a status it cannot name; this app
 * does not, because `Unknown` here means the parser refused the line rather
 * than a typed field being absent, and calling that "active" would invent a
 * state the Gateway never sent.
 */
private fun ComposerGoalState.groupLabel(): String = when (this) {
    ComposerGoalState.Active -> "Goal active"
    ComposerGoalState.Waiting -> "Goal waiting"
    ComposerGoalState.Paused -> "Goal paused"
    ComposerGoalState.Done -> "Goal done"
    // None never reaches a header — the group does not render at all.
    ComposerGoalState.Unknown, ComposerGoalState.None -> "Goal"
}

private fun ComposerBackgroundProcessState.label(): String = when (this) {
    ComposerBackgroundProcessState.Running -> "Running"
    ComposerBackgroundProcessState.Done -> "Done"
    ComposerBackgroundProcessState.Failed -> "Failed"
}

private fun Color.alphaMultiply(multiplier: Float): Color = copy(alpha = alpha * multiplier)

private val MAX_STACK_HEIGHT = 240.dp

/**
 * The waits between the silent-exit checks, and the fact that there are only
 * three of them: a claim that stays unchanged through ~2 minutes of foreground
 * attention is treated as true rather than re-asked forever. Widening rather
 * than fixed, so the cheap common case (a process that was already gone when
 * the row arrived) is caught quickly without the expensive one paying for it.
 */
private val RECONCILE_LADDER_MILLIS = listOf(10_000L, 30_000L, 90_000L)
private const val MAX_SUBAGENT_ROWS = 6
private const val MAX_BACKGROUND_ROWS = 6
private const val MAX_PREVIEW_ROWS = 4
