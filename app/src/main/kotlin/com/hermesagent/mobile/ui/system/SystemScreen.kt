package com.hermesagent.mobile.ui.system

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.Hairline
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.WIP_SPOKEN
import com.hermesagent.mobile.ui.common.WipPill
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
 * section — which ships visible and disabled behind the `WIP` chip, because log
 * fetching is a slice of its own and a control that is silently missing is a
 * parity finding.
 */
@Composable
fun SystemScreen(
    state: SystemUiState,
    actions: SystemActions,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    LaunchedEffect(Unit) { actions.onRefresh() }

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
        RecentLogsSection()
    }
}

/**
 * Desktop's recent-logs block, shipped visible and disabled.
 *
 * Every control Desktop has is here — the heading, the four log files, the four
 * levels, the filter field and the empty state — and none of them does
 * anything. That is the honest rendering of "this app does not fetch logs yet":
 * omitting the block would claim the surface was never meant to have one, and a
 * silently missing control is a parity finding rather than a smaller surface.
 */
@Composable
private fun RecentLogsSection() {
    val tokens = HermesTheme.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${SystemCopy.RECENT_LOGS}. $WIP_SPOKEN"
                disabled()
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionLabel(SystemCopy.RECENT_LOGS)
            WipPill()
        }
        DisabledTabs(label = SystemCopy.LOG_FILE, options = SystemCopy.LOG_FILES)
        DisabledTabs(label = SystemCopy.LOG_LEVEL, options = SystemCopy.LOG_LEVELS)
        Text(
            text = SystemCopy.LOG_SEARCH_PLACEHOLDER,
            style = HermesTheme.type.body,
            color = tokens.textQuaternary,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .border(1.dp, tokens.strokeQuaternary, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 12.dp)
                .testTag(SYSTEM_LOG_FILTER_TAG),
        )
        Text(
            text = SystemCopy.NO_LOGS,
            style = HermesTheme.type.caption,
            color = tokens.textQuaternary,
            modifier = Modifier.padding(bottom = 8.dp).testTag(SYSTEM_NO_LOGS_TAG),
        )
    }
}

/**
 * Desktop's `ResponsiveTabs` row, drawn and not wired.
 *
 * Deliberately not the app's [com.hermesagent.mobile.ui.common.SegmentedControl]:
 * that primitive is a `selectableGroup`, and a disabled radio group publishes
 * four selectable nodes a screen reader will offer and none of them will answer.
 * The whole block is one silent, disabled node instead; the heading above it
 * says the phrase.
 */
@Composable
private fun DisabledTabs(label: String, options: List<String>) {
    val tokens = HermesTheme.tokens
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = HermesTheme.type.caption, color = tokens.textQuaternary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (option in options) {
                Text(
                    text = option,
                    style = HermesTheme.type.caption,
                    color = tokens.textQuaternary,
                    modifier = Modifier
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
