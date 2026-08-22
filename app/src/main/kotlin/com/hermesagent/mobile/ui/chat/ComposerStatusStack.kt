package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.ComposerBackgroundProcess
import com.hermesagent.mobile.data.session.ComposerBackgroundProcessState
import com.hermesagent.mobile.data.session.ComposerPreviewArtifact
import com.hermesagent.mobile.data.session.ComposerStatusState
import com.hermesagent.mobile.data.session.ComposerSubagentStatus
import com.hermesagent.mobile.data.session.ComposerTodoStatus
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Session-scoped Gateway status, deliberately separate from transcript rows.
 * The fixed max height gives a long status feed its own scroll region instead
 * of displacing the editor below the IME.
 */
@Composable
fun ComposerStatusStack(
    activeSessionId: String?,
    status: ComposerStatusState?,
    codingContextProvider: CodingContextProvider = CodingContextProvider.Unavailable,
    onRefreshProcesses: () -> Unit = {},
    onKillProcess: (String) -> Unit = {},
    hasQueue: Boolean = false,
    queueContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val visiblePreviews = remember(activeSessionId, status?.previewArtifacts) {
        status?.previewArtifacts.orEmpty().take(MAX_PREVIEW_ROWS).distinctBy(ComposerPreviewArtifact::id)
    }
    var dismissedPreviewIds by rememberSaveable(activeSessionId) { mutableStateOf(emptySet<String>()) }
    val previews = visiblePreviews.filterNot { it.id in dismissedPreviewIds }
    val hasRows = status != null && (
        status.goal != null || status.todos.isNotEmpty() || status.subagents.isNotEmpty() ||
            status.backgroundProcesses.isNotEmpty() || previews.isNotEmpty() ||
            status.genericProgress != null || status.isCompacting
        ) || hasQueue
    val codingContext = codingContextProvider.contextFor(activeSessionId)
    if (!hasRows && codingContext is CodingContext.Unavailable) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = MAX_STACK_HEIGHT)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "Composer status" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        status?.goal?.let { goal ->
            StatusGroup("${activeSessionId}:goal", "Goal", defaultExpanded = true) {
                StatusText(goal.title ?: goal.rawText)
                goal.detail?.takeIf(String::isNotBlank)?.let { StatusText(it) }
            }
        }
        status?.todos?.take(MAX_TODO_ROWS)?.takeIf { it.isNotEmpty() }?.let { todos ->
            StatusGroup("${activeSessionId}:todo", "To do", defaultExpanded = true, count = todos.size) {
                todos.forEach { todo -> StatusText("${todo.state.label()} · ${todo.title}") }
            }
        }
        status?.subagents?.take(MAX_SUBAGENT_ROWS)?.takeIf { it.isNotEmpty() }?.let { agents ->
            StatusGroup("${activeSessionId}:subagents", "Subagents", defaultExpanded = false, count = agents.size) {
                agents.forEach { agent ->
                    StatusText(agent.currentTool?.let { "${agent.title} · $it" } ?: agent.title)
                }
            }
        }
        status?.backgroundProcesses?.take(MAX_BACKGROUND_ROWS)?.takeIf { it.isNotEmpty() }?.let { processes ->
            StatusGroup("${activeSessionId}:background", "Background", defaultExpanded = false, count = processes.size) {
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
            StatusGroup("${activeSessionId}:previews", "Previews", defaultExpanded = false, count = previews.size) {
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
        status?.genericProgress?.text?.takeIf(String::isNotBlank)?.let { StatusText(it) }
        if (status?.isCompacting == true) StatusText("Hermes is compacting this session.")
        // No coding row controls exist for Unavailable: Android has no
        // authorized remote git/repo-status transport in the current Gateway.
        if (codingContext is CodingContext.Available) {
            StatusGroup("${activeSessionId}:coding", "Coding context", defaultExpanded = false) {
                StatusText(codingContext.worktreeLabel?.let { "${codingContext.branch} · $it" } ?: codingContext.branch)
            }
        }
        // Queue is intentionally the final status-stack group so its expanded
        // rows share this bounded scroll region rather than pushing the IME
        // composer off screen.
        queueContent?.invoke()
    }
}

@Composable
private fun StatusGroup(
    stateKey: String,
    title: String,
    defaultExpanded: Boolean,
    count: Int? = null,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(defaultExpanded) }
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, tokens.strokeTertiary, RoundedCornerShape(10.dp))
            .background(tokens.widgetSurface, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable { expanded = !expanded }
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
            Text(
                text = count?.let { "$title · $it" } ?: title,
                style = HermesTheme.type.caption,
                color = tokens.textSecondary,
            )
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { content() }
    }
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

private fun com.hermesagent.mobile.data.session.ComposerTodoState.label(): String = when (this) {
    com.hermesagent.mobile.data.session.ComposerTodoState.Pending -> "Pending"
    com.hermesagent.mobile.data.session.ComposerTodoState.InProgress -> "In progress"
    com.hermesagent.mobile.data.session.ComposerTodoState.Completed -> "Done"
    com.hermesagent.mobile.data.session.ComposerTodoState.Cancelled -> "Cancelled"
    com.hermesagent.mobile.data.session.ComposerTodoState.Unknown -> "Unknown"
}

private fun ComposerBackgroundProcessState.label(): String = when (this) {
    ComposerBackgroundProcessState.Running -> "Running"
    ComposerBackgroundProcessState.Done -> "Done"
    ComposerBackgroundProcessState.Failed -> "Failed"
}

private val MAX_STACK_HEIGHT = 240.dp
private const val MAX_TODO_ROWS = 8
private const val MAX_SUBAGENT_ROWS = 6
private const val MAX_BACKGROUND_ROWS = 6
private const val MAX_PREVIEW_ROWS = 4
