package com.hermesagent.mobile.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.common.WipPill
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.hermesagent.mobile.ui.common.WorkingDots
import com.hermesagent.mobile.ui.theme.HermesTheme

/** What the System panel can ask for. Navigation stays with the shell. */
class SystemActions(
    val onRefresh: () -> Unit = {},
    val onRestartGateway: () -> Unit = {},
    val onOpenUpdates: () -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
    val onApplyUpdate: () -> Unit = {},
    val onCloseUpdates: () -> Unit = {},
    val onLogsResume: () -> Unit = {},
    val onLogsPause: () -> Unit = {},
    val onLogsRefresh: () -> Unit = {},
    val onLogFile: (String) -> Unit = {},
    val onLogLevel: (String) -> Unit = {},
    val onLogQuery: (String) -> Unit = {},
)

/**
 * Hermes Desktop's command-center **System panel**
 * (`apps/desktop/src/app/command-center/index.tsx:423-505` @
 * `3ca096de5f8183cb2e0ec23673f294d5978656a3`), rendered as a Settings
 * destination.
 *
 * Desktop reaches it through a command palette, which a phone does not have;
 * the panel's own content, order and words are unchanged. Top to bottom, that
 * order is: the messaging gateway's state, the backend version and session
 * count, the two actions, the action's progress line, and the recent-logs
 * section. Logs use their own lifecycle-bound, connection/profile-fenced read.
 */
@Composable
fun SystemScreen(
    state: SystemUiState,
    actions: SystemActions,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    LaunchedEffect(Unit) { actions.onRefresh() }
    LifecycleResumeEffect(Unit) {
        actions.onLogsResume()
        onPauseOrDispose { actions.onLogsPause() }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(tokens.chatSurface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val status = state.status
        if (status == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag(SYSTEM_LOADING_TAG),
            ) {
                WorkingDots()
                Text(
                    text = SystemCopy.LOADING_STATUS,
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Desktop paints emerald when the messaging gateway is running
                // and amber when it is not (`index.tsx:430-435`). Those are the
                // two Tailwind literals this theme's tokens already hold — the
                // token is chosen by the ink Desktop specified, not by the
                // session-status meaning each token's name records, because a
                // raw colour here would stop following the palette.
                StatusDot(
                    color = if (status.gatewayRunning) tokens.statusUnread else tokens.statusWarning,
                    filled = true,
                    contentDescription = null,
                    size = 8.dp,
                )
                Text(
                    text = if (status.gatewayRunning) {
                        SystemCopy.GATEWAY_RUNNING
                    } else {
                        SystemCopy.GATEWAY_STOPPED
                    },
                    style = HermesTheme.type.bodyStrong,
                    color = tokens.textPrimary,
                    modifier = Modifier.testTag(SYSTEM_GATEWAY_STATE_TAG),
                )
            }
            Text(
                text = SystemCopy.hermesActiveSessions(status.version, status.activeSessions),
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
                modifier = Modifier.testTag(SYSTEM_VERSION_TAG),
            )
            // Desktop puts these at the end of the status row and stacks them
            // below it under 47.5rem (`index.tsx:427,444`), which every phone
            // is; the stacked form here is that same narrow rendering.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    label = SystemCopy.RESTART_GATEWAY,
                    onClick = actions.onRestartGateway,
                    enabled = !state.actionRunning,
                    color = tokens.textTertiary,
                    modifier = Modifier.testTag(SYSTEM_RESTART_TAG),
                )
                TextButton(
                    label = SystemCopy.UPDATE_HERMES,
                    onClick = actions.onOpenUpdates,
                    enabled = !state.actionRunning,
                    color = tokens.textSecondary,
                    strong = true,
                    modifier = Modifier.testTag(SYSTEM_UPDATE_TAG),
                )
            }
            val action = state.action
            if (action != null) {
                Text(
                    text = SystemCopy.actionProgress(
                        action = action.action,
                        state = when (action.phase) {
                            SystemActionPhase.Running -> SystemCopy.ACTION_RUNNING
                            SystemActionPhase.Done -> SystemCopy.ACTION_DONE
                            SystemActionPhase.Failed -> SystemCopy.ACTION_FAILED
                        },
                    ),
                    style = HermesTheme.type.caption,
                    color = tokens.textTertiary,
                    modifier = Modifier.testTag(SYSTEM_ACTION_TAG),
                )
            }
        }

        // Desktop keeps its one error line inside the logs header row
        // (`index.tsx:497-501`). A phone column has no header row to put it in,
        // so it sits directly under the action it is about, which is where a
        // narrow layout has to put a message about a control.
        val problem = state.actionError ?: state.statusError
        if (problem != null) {
            Text(
                text = problem,
                style = HermesTheme.type.caption,
                color = tokens.destructive,
                modifier = Modifier.testTag(SYSTEM_ERROR_TAG),
            )
        }

        Hairline(Modifier.padding(vertical = 4.dp))
        RecentLogsSection(state.logs, actions)
    }
}

/** File → level → local substring filter → bounded tail. See system-panel.md. */
@Composable
internal fun RecentLogsSection(state: SystemLogsState, actions: SystemActions) {
    val tokens = HermesTheme.tokens
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel(SystemCopy.RECENT_LOGS)
            TextButton(label = "Refresh", onClick = actions.onLogsRefresh)
        }
        LogTabs(SystemCopy.LOG_FILE, SystemCopy.LOG_FILES, state.file, actions.onLogFile)
        LogTabs(SystemCopy.LOG_LEVEL, SystemCopy.LOG_LEVELS, state.level, actions.onLogLevel)
        SearchField(
            value = state.query, onValueChange = actions.onLogQuery,
            placeholder = SystemCopy.LOG_SEARCH_PLACEHOLDER,
            modifier = Modifier.testTag(SYSTEM_LOG_FILTER_TAG),
        )
        if (state.query.isNotBlank()) {
            // Current Desktop search navigation is not a substring filter.
            // Keep its unsupported controls visible, not silently absent (#127).
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(label = "Previous match", onClick = {}, enabled = false)
                TextButton(label = "Next match", onClick = {}, enabled = false)
                WipPill()
            }
        }
        if (state.loading) WorkingDots(Modifier.testTag("system-logs-loading"))
        state.error?.let {
            Text(it, style = HermesTheme.type.caption, color = tokens.destructive,
                modifier = Modifier.testTag("system-logs-error"))
        }
        if (!state.loading && state.error == null && state.filteredLines.isEmpty()) {
            Text(SystemCopy.NO_LOGS, style = HermesTheme.type.caption, color = tokens.textQuaternary,
                modifier = Modifier.testTag(SYSTEM_NO_LOGS_TAG))
        }
        // The latest shared Desktop LogTail adds these beyond the original WIP
        // block. Keep unported actions visible, rather than claiming parity (#127).
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(label = "Top of log", onClick = {}, enabled = false)
            TextButton(label = "Bottom of log", onClick = {}, enabled = false)
            TextButton(label = "Copy", onClick = {}, enabled = false)
            WipPill()
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            .background(tokens.widgetSurface, RoundedCornerShape(8.dp)).padding(8.dp)) {
            state.filteredLines.forEach { line ->
                Text(line, style = HermesTheme.type.code,
                    color = tokens.textSecondary)
            }
        }
    }
}

@Composable
private fun LogTabs(label: String, options: List<String>, selected: String, select: (String) -> Unit) {
    val tokens = HermesTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = HermesTheme.type.caption, color = tokens.textQuaternary)
        Row(Modifier.horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in options) {
                Text(
                    text = option,
                    style = HermesTheme.type.caption,
                    color = if (selected == option) tokens.textPrimary else tokens.textTertiary,
                    modifier = Modifier
                        .selectable(selected == option, role = Role.Tab, onClick = { select(option) })
                        .heightIn(min = HermesTheme.spacing.touchTarget)
                        .widthIn(min = HermesTheme.spacing.touchTarget)
                        .semantics { contentDescription = "$label: $option" }
                        .background(tokens.widgetSurface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

internal const val SYSTEM_LOADING_TAG = "system-loading"
internal const val SYSTEM_GATEWAY_STATE_TAG = "system-gateway-state"
internal const val SYSTEM_VERSION_TAG = "system-version"
internal const val SYSTEM_RESTART_TAG = "system-restart-gateway"
internal const val SYSTEM_UPDATE_TAG = "system-update-hermes"
internal const val SYSTEM_ACTION_TAG = "system-action-progress"
internal const val SYSTEM_ERROR_TAG = "system-error"
internal const val SYSTEM_LOG_FILTER_TAG = "system-log-filter"
internal const val SYSTEM_NO_LOGS_TAG = "system-no-logs"
