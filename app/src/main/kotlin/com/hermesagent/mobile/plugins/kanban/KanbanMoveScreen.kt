package com.hermesagent.mobile.plugins.kanban

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.hermesagent.mobile.ui.OverlayScaffold
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.theme.HermesTheme

data class KanbanMoving(
    val choices: KanbanMoveChoices? = null,
    val loading: Boolean = false,
    val submitted: Boolean = false,
    val error: String? = null,
)
@Composable
internal fun KanbanMoveScreen(state: KanbanMoving, close: () -> Unit, move: (String) -> Unit) {
    var selected by remember { mutableStateOf<String?>(null) }
    OverlayScaffold(title = "Move to", backDescription = "Back to task", onBack = close) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(HermesTheme.spacing.pageInset),
            verticalArrangement = Arrangement.spacedBy(HermesTheme.spacing.turnGap)) {
            state.choices?.let { choices ->
                Text(choices.task.title, style = HermesTheme.type.bodyStrong, color = HermesTheme.tokens.textPrimary)
                Text("Board: ${choices.board}", style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary)
                Text("${choices.task.id} · ${choices.task.status}", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
                choices.statuses.forEach { status ->
                    Row(Modifier.fillMaxWidth().heightIn(min = HermesTheme.spacing.touchTarget)
                        .clickable(enabled = !state.loading && !state.submitted, role = Role.Button) { selected = status }
                        .semantics { this.selected = selected == status }, verticalAlignment = Alignment.CenterVertically) {
                        Text(moveStatusLabel(status), style = HermesTheme.type.body,
                            color = HermesTheme.tokens.textPrimary, modifier = Modifier.weight(1f))
                        if (selected == status) HermesIconGlyph(HermesIcon.Check, color = HermesTheme.tokens.textSecondary)
                    }
                }
            }
            state.error?.let { Text(it, style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary) }
            if (state.loading) Text("Loading…", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
            TextButton("Move to", enabled = selected != null && !state.loading && !state.submitted,
                modifier = Modifier.testTag("Kanban confirm move"),
                onClick = { selected?.let(move) })
            TextButton("Cancel", close)
        }
    }
}
internal fun moveStatusLabel(status: String) = when (status) {
    "triage" -> "Triage"
    "todo" -> "Todo"
    "ready" -> "Ready"
    "blocked" -> "Blocked"
    "done" -> "Done"
    "archived" -> "Archived"
    else -> status
}
