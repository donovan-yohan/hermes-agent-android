package com.hermesagent.mobile.plugins.kanban

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

@Composable
fun KanbanScreen(state: KanbanUiState, onBack: () -> Unit, onRefresh: () -> Unit, onOpenTask: (KanbanTask) -> Unit, onCloseDetail: () -> Unit) {
    val detail = state.detail
    val showingDetail = detail !is KanbanDetail.None
    BackHandler(enabled = showingDetail) { onCloseDetail() }
    OverlayScaffold(title = if (showingDetail) "Task" else "Kanban", backDescription = if (showingDetail) "Back to board" else "Back", onBack = { if (showingDetail) onCloseDetail() else onBack() }) {
        if (!showingDetail) {
            TextButton(label = "Refresh", onClick = onRefresh, modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset))
            if (state.stale) Text(KANBAN_STALE, style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary, modifier = Modifier.padding(HermesTheme.spacing.pageInset))
            when (state.phase) {
                KanbanPhase.Loading -> EmptyState("Loading board…", "Hermes is asking the Gateway for its current board.")
                KanbanPhase.Empty -> EmptyState("No tasks on this board", "Refresh to check the current board again.")
                KanbanPhase.Unavailable -> EmptyState("Kanban unavailable", KANBAN_UNAVAILABLE)
                KanbanPhase.Refused -> EmptyState("Board could not be loaded", "Try refreshing the board.")
                KanbanPhase.Ready -> Board(state.columns, onOpenTask)
            }
        } else when (detail) {
            KanbanDetail.Loading -> EmptyState("Loading task…", "Hermes is asking for this task's details.")
            KanbanDetail.Gone -> EmptyState("This task is no longer available", "Return to the board and refresh the snapshot.")
            KanbanDetail.Refused -> EmptyState("Task details could not be loaded", "Return to the board and refresh the snapshot.")
            is KanbanDetail.Value -> TaskDetail(detail.task)
            KanbanDetail.None -> Unit
        }
    }
}

@Composable private fun Board(columns: List<KanbanColumn>, open: (KanbanTask) -> Unit) = LazyColumn(modifier = Modifier.testTag("Kanban board")) {
    columns.forEach { column ->
        item("header-${column.name}") { Text(column.name, style = HermesTheme.type.sectionLabel, color = HermesTheme.tokens.textTertiary, modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = HermesTheme.spacing.pageInset)) }
        items(column.tasks, key = { it.id }) { task -> TaskRow(task) { open(task) } }
    }
}
@Composable private fun TaskRow(task: KanbanTask, click: () -> Unit) = Column(Modifier.fillMaxWidth().heightIn(min = HermesTheme.spacing.touchTarget).clickable(onClick = click).padding(horizontal = HermesTheme.spacing.pageInset, vertical = HermesTheme.spacing.pageInset).semantics { role = Role.Button; contentDescription = "${task.title}. ${task.status}" }.testTag("Kanban task ${task.id}"), verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap)) {
    Text(task.title, style = HermesTheme.type.sessionTitle, color = HermesTheme.tokens.textPrimary)
    Text(task.status, style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
}
@Composable private fun TaskDetail(task: KanbanTask) = Column(Modifier.fillMaxWidth().padding(HermesTheme.spacing.pageInset), verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap)) {
    Text(task.title, style = HermesTheme.type.bodyStrong, color = HermesTheme.tokens.textPrimary)
    Text("${task.id} · ${task.status}", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
    Text("Read-only task snapshot.", style = HermesTheme.type.body, color = HermesTheme.tokens.textSecondary)
}
