package com.hermesagent.mobile.plugins.kanban

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.common.WipPill
import com.hermesagent.mobile.ui.theme.HermesTheme

@Composable
fun KanbanScreen(
    state: KanbanUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTask: (KanbanTask) -> Unit,
    onCloseDetail: () -> Unit,
) {
    val detail = state.detail
    val showingDetail = detail !is KanbanDetail.None

    BackHandler(enabled = showingDetail) { onCloseDetail() }
    OverlayScaffold(
        title = if (showingDetail) "Task" else "Kanban",
        backDescription = if (showingDetail) "Back to board" else "Back",
        onBack = { if (showingDetail) onCloseDetail() else onBack() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HermesTheme.spacing.pageInset),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(label = "Refresh", onClick = onRefresh, modifier = Modifier.testTag("Kanban refresh"))
            KanbanControlsMenu(showingDetail)
        }

        if (!showingDetail) {
            if (state.stale) Notice(KANBAN_STALE)
            when (state.phase) {
                KanbanPhase.Loading -> EmptyState("Loading board…", "Hermes is asking the Gateway for its current board.")
                KanbanPhase.Empty -> EmptyState("No tasks on this board", "Refresh to check the current board again.")
                KanbanPhase.Unavailable -> EmptyState("Kanban unavailable", KANBAN_UNAVAILABLE)
                KanbanPhase.Refused -> EmptyState("Board could not be loaded", "Try refreshing the board.")
                KanbanPhase.Ready -> Board(state.columns, onOpenTask)
            }
        } else when (detail) {
            is KanbanDetail.Loading -> EmptyState("Loading task…", "Hermes is asking for this task's details.")
            is KanbanDetail.Gone -> EmptyState("This task is no longer available", "Refresh to check this task again.")
            is KanbanDetail.Unavailable -> EmptyState("Kanban unavailable", KANBAN_UNAVAILABLE)
            is KanbanDetail.Refused -> EmptyState("Task details could not be loaded", "Refresh to try this task again.")
            is KanbanDetail.Value -> {
                if (detail.stale) Notice(KANBAN_DETAIL_STALE)
                TaskDetail(detail.detail)
            }
            KanbanDetail.None -> Unit
        }
    }
}

@Composable
private fun KanbanControlsMenu(showingDetail: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (showingDetail) "Task actions" else "Board controls"

    Column {
        TextButton(
            label = label,
            onClick = { expanded = true },
            modifier = Modifier.testTag("Kanban $label"),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val actions = if (showingDetail) TASK_ACTIONS else BOARD_CONTROLS
            actions.forEach { action -> KanbanWipMenuRow(action) }
        }
    }
}

@Composable
private fun KanbanWipMenuRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(enabled = false, role = Role.Button, onClick = {})
            .clearAndSetSemantics {
                testTag = "Kanban WIP $label"
                contentDescription = "$label. $WIP_SPOKEN"
                disabled()
            }
            .padding(horizontal = HermesTheme.spacing.pageInset),
        horizontalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap),
    ) {
        Text(label, style = HermesTheme.type.body, color = HermesTheme.tokens.textQuaternary)
        WipPill()
    }
}

@Composable
private fun Notice(message: String) = Text(
    text = message,
    style = HermesTheme.type.caption,
    color = HermesTheme.tokens.textTertiary,
    modifier = Modifier.padding(HermesTheme.spacing.pageInset),
)

@Composable
private fun Board(columns: List<KanbanColumn>, open: (KanbanTask) -> Unit) = LazyColumn(
    modifier = Modifier.testTag("Kanban board"),
) {
    columns.forEach { column ->
        item("header-${column.name}") {
            Text(
                text = column.name,
                style = HermesTheme.type.sectionLabel,
                color = HermesTheme.tokens.textTertiary,
                modifier = Modifier.padding(
                    horizontal = HermesTheme.spacing.pageInset,
                    vertical = HermesTheme.spacing.turnGap,
                ),
            )
        }
        items(column.tasks, key = { it.id }) { task -> TaskRow(task) { open(task) } }
    }
}

@Composable
private fun TaskRow(task: KanbanTask, click: () -> Unit) = Column(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = HermesTheme.spacing.touchTarget)
        .clickable(onClick = click)
        .padding(
            horizontal = HermesTheme.spacing.pageInset,
            vertical = HermesTheme.spacing.turnGap,
        )
        .semantics {
            role = Role.Button
            contentDescription = "${task.title}. ${task.status}"
        }
        .testTag("Kanban task ${task.id}"),
    verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap),
) {
    Text(task.title, style = HermesTheme.type.sessionTitle, color = HermesTheme.tokens.textPrimary)
    Text(task.status, style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
}

@Composable
private fun TaskDetail(detail: KanbanTaskDetail) = SelectionContainer {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(HermesTheme.spacing.pageInset)
            .testTag("Kanban detail"),
        verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap),
    ) {
        val task = detail.task
        Text(task.title, style = HermesTheme.type.bodyStrong, color = HermesTheme.tokens.textPrimary)
        Text("${task.id} · ${task.status}", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
        task.assignee?.let { Field("Assignee", it) }
        task.priority?.let { Field("Priority", it.toString()) }
        task.body?.let { Field("Description", it) }
        task.latestSummary?.let { Field("Latest summary", it) }
        task.result?.let { Field("Result", it) }
        if (detail.parentIds.isNotEmpty()) Field("Parents", detail.parentIds.joinToString("\n"))
        if (detail.childIds.isNotEmpty()) Field("Children", detail.childIds.joinToString("\n"))
        if (detail.childResults.isNotEmpty()) {
            Field("Child results", detail.childResults.joinToString("\n") { "${it.id} · ${it.status}" })
        }
        Text("Read-only task snapshot.", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
    }
}

@Composable
private fun Field(label: String, value: String) = Column(
    verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap),
) {
    Text(label, style = HermesTheme.type.sectionLabel, color = HermesTheme.tokens.textTertiary)
    Text(value, style = HermesTheme.type.body, color = HermesTheme.tokens.textSecondary)
}

private val BOARD_CONTROLS = listOf("Board switcher", "Filters", "Search", "New task")
private val TASK_ACTIONS = listOf("Move task", "Archive task", "Delete task")
