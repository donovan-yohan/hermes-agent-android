package com.hermesagent.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.plugins.AgentPluginsPhase
import com.hermesagent.mobile.plugins.AgentPluginsState
import com.hermesagent.mobile.ui.common.*
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.data.ssh.redact

/** Copy: apps/desktop/src/i18n/en.ts:2104-2137 @ 81a55437f3f31a9f12a30c53b6c5d49d420898aa. */
internal fun LazyListScope.agentPluginItems(
    state: AgentPluginsState,
    profile: String,
    onRefresh: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    item {
        Hairline(Modifier.padding(vertical = 10.dp))
        SettingsSectionHeading(HermesIcon.Monitor, PluginsCopy.AGENT_TITLE, Modifier.testTag(PLUGINS_AGENT_HALF_TAG))
        Text("Agent in ${redact(profile)}", style = HermesTheme.type.caption, color = HermesTheme.tokens.textSecondary)
        Text(PluginsCopy.AGENT_BLURB, style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
        val message = when (state.phase) {
            AgentPluginsPhase.Loading -> "Loading…"
            AgentPluginsPhase.Disconnected -> "Connect to a Gateway to load agent plugins."
            AgentPluginsPhase.Unavailable -> "Agent plugins are unavailable on this Gateway. Update Hermes and reconnect."
            AgentPluginsPhase.Error -> "Could not load agent plugins"
            AgentPluginsPhase.Ready -> if (state.rows.isEmpty()) "No agent plugins installed for this profile." else null
        }
        message?.let { Text(it, style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary) }
        if (state.unconfirmed) {
            Text("The change could not be confirmed. Refresh before trying again.",
                style = HermesTheme.type.caption, color = HermesTheme.tokens.destructive)
        }
        if (state.phase == AgentPluginsPhase.Ready || state.phase == AgentPluginsPhase.Error) {
            TextButton("Refresh", onRefresh, enabled = state.busyKey == null, modifier = Modifier.testTag("agent-plugins-refresh"))
        }
    }
    itemsIndexed(state.rows, key = { index, row -> row.key?.let { "agent-key:$it" } ?: "agent-legacy:$index" }) { index, row ->
        SettingsListRow(
            modifier = Modifier.testTag("agent-plugin-row-${row.key ?: index}"),
            description = row.description.takeIf(String::isNotBlank),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.name, style = HermesTheme.type.bodyStrong, color = HermesTheme.tokens.textPrimary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Pill("Agent", density = PillDensity.Compact)
                        if (row.version.isNotBlank()) Pill(row.version, density = PillDensity.Compact)
                        if (row.source.isNotBlank()) Pill(row.source, density = PillDensity.Compact)
                        if (row.portable) Pill("portable", density = PillDensity.Compact)
                        row.catalogName?.takeIf(String::isNotBlank)?.let { Pill(it, density = PillDensity.Compact) }
                        row.catalogTier?.takeIf(String::isNotBlank)?.let { Pill(it, density = PillDensity.Compact) }
                    }
                    if (row.key == null) {
                        Text("This backend predates key-addressed plugin toggles — update Hermes to manage it here.",
                            style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
                    }
                }
            },
            action = {
                PluginToggle(
                    checked = row.enabled,
                    enabled = row.key != null && state.busyKey == null && !state.unconfirmed && state.phase == AgentPluginsPhase.Ready,
                    label = "${if (row.enabled) PluginsCopy.DISABLE else PluginsCopy.ENABLE} ${row.name}",
                    onCheckedChange = { enabled -> row.key?.let { onToggle(it, enabled) } },
                    modifier = Modifier.testTag("agent-plugin-toggle-${row.key ?: "legacy-$index"}"),
                )
                if (state.busyKey == row.key && row.key != null) {
                    Text("Saving…", style = HermesTheme.type.caption, color = HermesTheme.tokens.textTertiary)
                }
            },
        )
        Hairline(Modifier.fillMaxWidth())
    }
}
