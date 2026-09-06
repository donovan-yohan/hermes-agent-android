package com.hermesagent.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesagent.mobile.plugins.PluginKind
import com.hermesagent.mobile.plugins.PluginRecord
import com.hermesagent.mobile.plugins.PluginStatus
import com.hermesagent.mobile.plugins.PluginStore
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.Pill
import com.hermesagent.mobile.ui.common.PillDensity
import com.hermesagent.mobile.ui.common.PillTone
import com.hermesagent.mobile.ui.common.SettingsListRow
import com.hermesagent.mobile.ui.common.SettingsSectionHeading
import com.hermesagent.mobile.ui.common.TokenSwitch
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.launch

/**
 * Settings ▸ Plugins.
 *
 * Desktop source: `apps/desktop/src/app/settings/plugins-settings.tsx` (bundled
 * section only) @ `3ca096de5f8183cb2e0ec23673f294d5978656a3`.
 */
@Composable
fun PluginsScreen(
    store: PluginStore,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val scope = rememberCoroutineScope()
    val records = store.records.collectAsStateWithLifecycle(emptyMap()).value

    val ordered = remember(records) {
        records.values.sortedWith(
            compareBy<PluginRecord> { kindOrder(it.kind) }.thenBy { it.name.lowercase() }.thenBy { it.id },
        )
    }

    Column(modifier.fillMaxSize().background(tokens.chatSurface)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(modifier),
            contentPadding = PaddingValues(
                start = HermesTheme.spacing.pageInset,
                end = HermesTheme.spacing.pageInset,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                SettingsSectionHeading(
                    HermesIcon.Monitor,
                    PluginsCopy.TITLE,
                    modifier = Modifier.testTag(PLUGINS_TITLE_TAG),
                )
                Text(
                    text = PluginsCopy.count(ordered.size),
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Text(
                    text = PluginsCopy.BLURB,
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                )
            }

            item { Hairline(Modifier.padding(vertical = 10.dp)) }

            if (ordered.isEmpty()) {
                item {
                    Text(
                        text = PluginsCopy.EMPTY,
                        style = HermesTheme.type.caption,
                        color = tokens.textTertiary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(items = ordered, key = { it.id }) { record ->
                    PluginRow(
                        record = record,
                        onToggle = { enabled ->
                            scope.launch { store.setPluginEnabled(record.id, enabled) }
                        },
                    )
                    Hairline()
                }
            }
        }
    }
}

@Composable
private fun PluginRow(
    record: PluginRecord,
    onToggle: (Boolean) -> Unit,
) {
    val tokens = HermesTheme.tokens
    val enabled = record.status != PluginStatus.Disabled
    val actionLabel = "${if (enabled) PluginsCopy.DISABLE else PluginsCopy.ENABLE} ${record.name}"
    val kindLabel = when (record.kind) {
        PluginKind.Bundled -> PluginsCopy.KIND_BUNDLED
        PluginKind.Disk -> PluginsCopy.KIND_DISK
        PluginKind.Runtime -> PluginsCopy.KIND_RUNTIME
    }

    // Switches are pure UI affordances; keep selection out of the transcript.
    DisableSelection {
        SettingsListRow(
            modifier = Modifier
                .testTag(pluginRowTag(record.id))
                .fillMaxWidth(),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = record.name,
                            style = HermesTheme.type.bodyStrong,
                            color = tokens.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                // Let the pills wrap instead of forcing the name off-screen.
                                .widthIn(max = 280.dp),
                        )
                        Pill(kindLabel, density = PillDensity.Compact)
                        if (record.status == PluginStatus.Error) {
                            Pill(PluginsCopy.FAILED, tone = PillTone.Primary, density = PillDensity.Compact)
                        }
                    }

                    val detail = when {
                        record.status == PluginStatus.Error -> record.error.orEmpty()
                        !record.description.isNullOrBlank() -> record.description
                        else -> record.id
                    }
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = HermesTheme.type.caption,
                            color = if (record.status == PluginStatus.Error) tokens.destructive else tokens.textTertiary,
                        )
                    }
                }
            },
            action = {
                PluginToggle(
                    checked = enabled,
                    enabled = true,
                    label = actionLabel,
                    onCheckedChange = onToggle,
                    modifier = Modifier
                        .testTag(pluginToggleTag(record.id))
                        .padding(top = 2.dp),
                )
            },
        )
    }
}

@Composable
private fun PluginToggle(
    checked: Boolean,
    enabled: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(HermesTheme.spacing.touchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        TokenSwitch(on = checked, enabled = enabled)
    }
}

private fun kindOrder(kind: PluginKind): Int = when (kind) {
    PluginKind.Disk -> 0
    PluginKind.Runtime -> 1
    PluginKind.Bundled -> 2
}

internal const val PLUGINS_TITLE_TAG: String = "plugins-title"

internal fun pluginRowTag(id: String): String = "plugins-row-$id"
internal fun pluginToggleTag(id: String): String = "plugins-toggle-$id"

