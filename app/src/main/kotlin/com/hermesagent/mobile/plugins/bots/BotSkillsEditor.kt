package com.hermesagent.mobile.plugins.bots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

class BotSkillsActions(val onToggle: (String) -> Unit = {}, val onRefresh: () -> Unit = {})

@Composable
internal fun BotSkillsEditor(state: BotSkillsState, actions: BotSkillsActions) {
    val tokens = HermesTheme.tokens
    Text("Skills", style = HermesTheme.type.sectionLabel, color = tokens.textSecondary)
    Text("Installed skills", style = HermesTheme.type.body)
    Text("Changes save immediately. Cancel does not undo skill changes.",
        style = HermesTheme.type.caption, color = tokens.textSecondary)
    if (state.loading) Text("Reading skills…", style = HermesTheme.type.caption)
    if (state.busy) Text("Saving skill…", style = HermesTheme.type.caption)
    state.rows?.let { rows ->
        if (rows.isEmpty()) Text("No installed skills.", style = HermesTheme.type.caption)
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = row.enabled, enabled = state.editable, role = Role.Switch,
                    onValueChange = { actions.onToggle(row.name) })
                .semantics(mergeDescendants = true) { contentDescription = "Skill ${redact(row.name)}" },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(redact(row.name), modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    style = HermesTheme.type.body, color = tokens.textPrimary)
                Switch(checked = row.enabled, onCheckedChange = null, enabled = state.editable,
                    colors = SwitchDefaults.colors(checkedThumbColor = tokens.cardSurface,
                        checkedTrackColor = tokens.accent, uncheckedThumbColor = tokens.textSecondary,
                        uncheckedTrackColor = tokens.cardSurface, uncheckedBorderColor = tokens.textSecondary,
                        disabledCheckedThumbColor = tokens.cardSurface, disabledCheckedTrackColor = tokens.textSecondary,
                        disabledUncheckedThumbColor = tokens.textSecondary, disabledUncheckedTrackColor = tokens.cardSurface,
                        disabledUncheckedBorderColor = tokens.textSecondary))
            }
        }
    }
    state.message?.let { Text(it, style = HermesTheme.type.caption, color = tokens.textSecondary) }
    TextButton("Refresh skills", actions.onRefresh, enabled = state.ticket != null && !state.loading && !state.busy)
    ComingSoonAction("Browse and install skills")
    ComingSoonAction("Skill details and editing")
    ComingSoonAction("Bulk actions and archive")
}
