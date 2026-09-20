package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.data.session.AssistantTurn
import com.hermesagent.mobile.data.session.TurnErrorDetails
import com.hermesagent.mobile.data.session.safeTurnErrorDetails
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.common.WipPill
import com.hermesagent.mobile.ui.theme.HermesTheme

/**
 * Recovery order and tint: apps/desktop/src/components/assistant-ui/thread/assistant-message.tsx:243-264,590-627
 * @ 437116f9497c80d242ce034ff7f5d81dc277a337. Details/title follow the requested newer Desktop presentation.
 * Disclosure/dismissal are UI-only: neither edits the backend transcript nor clears the failure outcome.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION") // Same synchronous clipboard contract as transcript copy.
@Composable
internal fun TurnErrorPanel(
    turn: AssistantTurn,
    retryEnabled: Boolean,
    onRetry: (() -> Unit)?,
    onSendDiagnostics: (() -> Unit)? = null,
    onViewGatewayLogs: (() -> Unit)? = null,
) {
    val summary = safeTurnErrorDetails(turn.error ?: return)
    val details = turn.errorDetails ?: TurnErrorDetails(details = summary)
    var dismissed by rememberSaveable(turn.id, turn.error, turn.errorDetails) { mutableStateOf(false) }
    var expanded by rememberSaveable(turn.id, turn.error, turn.errorDetails) { mutableStateOf(false) }
    var copied by rememberSaveable(turn.id, turn.error, turn.errorDetails) { mutableStateOf(false) }
    if (dismissed) return
    val tokens = HermesTheme.tokens
    val clipboard = LocalClipboardManager.current
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth()
            .testTag("Turn error ${turn.id}")
            .border(1.dp, tokens.destructive.copy(alpha = 0.35f), shape)
            .background(tokens.destructive.copy(alpha = 0.07f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(details.title, style = HermesTheme.type.caption.copy(fontWeight = FontWeight.Medium), color = tokens.destructive)
                Text(summary, style = HermesTheme.type.caption, color = tokens.destructive)
            }
            HermesIconButton(HermesIcon.Close, "Dismiss error", onClick = { dismissed = true })
        }
        TextButton(
            label = if (expanded) "▾ Details" else "▸ Details",
            onClick = { expanded = !expanded },
            color = tokens.destructive,
            modifier = Modifier.testTag("Error details toggle").semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        )
        if (expanded) {
            SelectionContainer {
                Text(
                    details.copyText(summary),
                    style = HermesTheme.type.code,
                    color = tokens.textSecondary,
                    modifier = Modifier.testTag("Error details"),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (details.retryable) {
                TurnErrorAction("Retry", HermesIcon.Refresh, enabled = retryEnabled && onRetry != null) { onRetry?.invoke() }
            }
            if (details.layer in setOf("auth", "billing", "endpoint", "provider")) {
                TurnErrorAction("Switch provider", HermesIcon.SettingsGear, workInProgress = true)
            }
            TurnErrorAction("View Gateway logs", HermesIcon.File, enabled = onViewGatewayLogs != null) {
                onViewGatewayLogs?.invoke()
            }
            TurnErrorAction("Send diagnostics", HermesIcon.ArrowUp, enabled = onSendDiagnostics != null) {
                onSendDiagnostics?.invoke()
            }
            TurnErrorAction(
                label = if (copied) "Copied" else "Copy error details",
                icon = if (copied) HermesIcon.Check else HermesIcon.Copy,
                onClick = {
                    clipboard.setText(AnnotatedString(details.copyText(summary)))
                    copied = true
                },
            )
        }
        Text(
            "Gateway logs cover the backend, not just this error.",
            style = HermesTheme.type.caption,
            color = tokens.textTertiary,
        )
    }
}

/** Desktop's outlined recovery pills inside Android-sized touch targets. */
@Composable
private fun TurnErrorAction(
    label: String,
    icon: HermesIcon,
    enabled: Boolean = true,
    workInProgress: Boolean = false,
    onClick: () -> Unit = {},
) {
    val tokens = HermesTheme.tokens
    val active = enabled && !workInProgress
    val ink = if (active) tokens.destructive else tokens.textQuaternary
    Box(
        modifier = Modifier.heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .then(if (workInProgress) Modifier.semantics {
                contentDescription = "$label. Work in progress."
            } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.border(1.dp, ink.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HermesIconGlyph(icon, color = ink)
            Text(label, style = HermesTheme.type.caption, color = ink)
            if (workInProgress) WipPill()
        }
    }
}
