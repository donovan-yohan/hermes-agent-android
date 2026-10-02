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
import com.hermesagent.mobile.plugins.McpSource
import com.hermesagent.mobile.ui.common.ComingSoonAction
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

class BotMcpActions(val onToggle: (String) -> Unit = {}, val onRefresh: () -> Unit = {})

@Composable
internal fun BotMcpEditor(state: BotMcpState, actions: BotMcpActions) {
    val tokens = HermesTheme.tokens
    Text("MCP servers", style = HermesTheme.type.sectionLabel, color = tokens.textSecondary)
    Text("Configured MCP servers", style = HermesTheme.type.body)
    Text("Changes save immediately. Cancel does not undo MCP changes.",
        style = HermesTheme.type.caption, color = tokens.textSecondary)
    Text("Selection applies to future sessions or Gateway starts, not live server health.",
        style = HermesTheme.type.caption, color = tokens.textSecondary)
    if (state.loading) Text("Reading MCP servers…", style = HermesTheme.type.caption)
    if (state.busy) Text("Saving MCP selection…", style = HermesTheme.type.caption)
    state.rows?.let { rows ->
        if (rows.isEmpty()) Text("No configured MCP servers.", style = HermesTheme.type.caption)
        rows.forEach { row ->
            val writable = state.editable && row.source == McpSource.Config
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = row.enabled, enabled = writable, role = Role.Switch,
                    onValueChange = { actions.onToggle(row.name) })
                .semantics(mergeDescendants = true) { contentDescription = "MCP server ${redact(row.name)}" },
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(redact(row.name), modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    style = HermesTheme.type.body, color = tokens.textPrimary)
                Switch(checked = row.enabled, onCheckedChange = null, enabled = writable,
                    colors = SwitchDefaults.colors(checkedThumbColor = tokens.cardSurface,
                        checkedTrackColor = tokens.accent, uncheckedThumbColor = tokens.textSecondary,
                        uncheckedTrackColor = tokens.cardSurface, uncheckedBorderColor = tokens.textSecondary,
                        disabledCheckedThumbColor = tokens.cardSurface, disabledCheckedTrackColor = tokens.textSecondary,
                        disabledUncheckedThumbColor = tokens.textSecondary, disabledUncheckedTrackColor = tokens.cardSurface,
                        disabledUncheckedBorderColor = tokens.textSecondary))
            }
            if (row.source != McpSource.Config) Text(
                if (row.source == McpSource.Plugin) "Plugin-managed · Read-only" else "Unknown source · Read-only",
                style = HermesTheme.type.caption, color = tokens.textSecondary)
        }
    }
    state.message?.let { Text(it, style = HermesTheme.type.caption, color = tokens.textSecondary) }
    TextButton("Refresh MCP servers", actions.onRefresh, enabled = state.ticket != null && !state.loading && !state.busy)
    ComingSoonAction("Browse MCP catalog")
    ComingSoonAction("Add MCP server")
    ComingSoonAction("OAuth and credentials")
    ComingSoonAction("Remove MCP server")
    ComingSoonAction("Probe MCP server")
    ComingSoonAction("Per-tool controls")
}
