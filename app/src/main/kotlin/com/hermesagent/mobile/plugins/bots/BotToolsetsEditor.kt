package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

class BotToolsetsActions(
    val onToggle: (String) -> Unit = {}, val onSave: () -> Unit = {},
    val onRestore: () -> Unit = {}, val onConfirm: () -> Unit = {}, val onCancel: () -> Unit = {},
)

@Composable
internal fun BotToolsetsEditor(state: BotToolsetsState, actions: BotToolsetsActions) {
    val tokens = HermesTheme.tokens
    Text("Toolsets", style = HermesTheme.type.sectionLabel, color = tokens.textSecondary)
    Text("Choose the toolsets saved for this bot’s profile.",
        style = HermesTheme.type.caption, color = tokens.textSecondary)
    if (state.loading) Text("Reading toolsets…", style = HermesTheme.type.caption)
    state.original?.let { original ->
        Text(if (original.pinned) "Custom selection" else "Using defaults", style = HermesTheme.type.caption)
        Text("${state.draft.size} of ${original.rows.size} enabled", style = HermesTheme.type.caption)
        if (original.rows.isEmpty()) Text("No toolsets available.", style = HermesTheme.type.caption)
        original.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = row.name in state.draft, enabled = state.editable && !state.confirmDefaults,
                    role = Role.Checkbox, onValueChange = { actions.onToggle(row.name) })
                .semantics(mergeDescendants = true) { contentDescription = "Toolset ${redact(row.name)}" },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = row.name in state.draft, onCheckedChange = null,
                    enabled = state.editable && !state.confirmDefaults,
                    colors = CheckboxDefaults.colors(checkedColor = tokens.accent, uncheckedColor = tokens.textSecondary,
                        checkmarkColor = tokens.cardSurface, disabledCheckedColor = tokens.textSecondary,
                        disabledUncheckedColor = tokens.textSecondary, disabledIndeterminateColor = tokens.textSecondary))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(redact(row.label), style = HermesTheme.type.body, color = tokens.textPrimary)
                    row.toolCount?.let { Text("$it tools", style = HermesTheme.type.caption, color = tokens.textSecondary) }
                    if (row.description.isNotBlank()) Text(redact(row.description), style = HermesTheme.type.caption, color = tokens.textSecondary)
                }
            }
        }
        if (state.editable && state.draft.isEmpty() && original.rows.isNotEmpty()) Text(
            "Select at least one toolset. Restore defaults does not disable all tools.",
            style = HermesTheme.type.caption, color = tokens.textSecondary)
    }
    state.message?.let { Text(it, style = HermesTheme.type.body, color = tokens.textSecondary) }
    PrimaryButton(if (state.busy) "Saving toolsets…" else "Save toolsets", actions.onSave,
        enabled = state.canSave, modifier = Modifier.fillMaxWidth())
    TextButton("Restore defaults", actions.onRestore,
        enabled = state.editable && state.original?.pinned == true && !state.confirmDefaults)
    if (state.confirmDefaults) {
        Text("Restore this bot’s default toolsets? This removes the custom selection; it does not disable all tools.",
            style = HermesTheme.type.body, color = tokens.textSecondary)
        PrimaryButton("Confirm restore defaults", actions.onConfirm, enabled = state.editable, modifier = Modifier.fillMaxWidth())
        TextButton("Cancel restore", actions.onCancel, enabled = state.editable)
    }
}
