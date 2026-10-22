package com.hermesagent.mobile.plugins.kanban

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

data class KanbanCreation(
    val defaults: KanbanCreateDefaults? = null,
    val loading: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun KanbanNewTaskScreen(state: KanbanCreation, close: () -> Unit, submit: (KanbanNewTask) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("0") }
    OverlayScaffold(title = "New task in Ready", backDescription = "Back to board", onBack = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(HermesTheme.spacing.pageInset),
            verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap)) {
            LabelledField("Title", title, "Title", { title = it })
            SectionLabel("Description")
            BasicTextField(
                value = description, onValueChange = { description = it }, minLines = 3,
                textStyle = HermesTheme.type.body.copy(color = HermesTheme.tokens.textPrimary),
                cursorBrush = SolidColor(HermesTheme.tokens.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = HermesTheme.spacing.touchTarget)
                    .semantics { contentDescription = "Description" },
                decorationBox = { editor ->
                    Box {
                        if (description.isEmpty()) Text("Description (optional)", style = HermesTheme.type.body,
                            color = HermesTheme.tokens.textQuaternary)
                        editor()
                    }
                },
            )
            LabelledField("Priority", priority, "0", { priority = it }, keyboardType = KeyboardType.Number)
            KanbanWipMenuRow("Workspace")
            state.defaults?.let { defaults ->
                Text("Board: ${defaults.board}", style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary)
                Text("Assignee: ${defaults.assignee}", style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary)
            }
            Text("Uses this board's default workspace and assignee.", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
            listOf("Assignee", "Skills (comma-separated)", "Model", "Parent (blocks until it's done)",
                "Goal mode (worker loops until a judge agrees it's done)", "Estimate").forEach { KanbanWipMenuRow(it) }
            state.error?.let { Text(it, style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary) }
            if (state.loading) Text("Loading…", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
            TextButton(label = "Create task", enabled = !state.loading && !state.submitted && state.defaults != null &&
                title.isNotBlank() && priority.toIntOrNull() != null,
                onClick = { submit(KanbanNewTask(title, description, priority.toIntOrNull() ?: 0)) })
            TextButton(label = "Cancel", onClick = close)
        }
    }
}
