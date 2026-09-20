package com.hermesagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.hermesagent.mobile.data.gateway.GatewayLogsResult
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

@Composable
internal fun GatewayLogsDialog(state: GatewayLogsState, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val tokens = HermesTheme.tokens
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(
            Modifier.fillMaxWidth().background(tokens.cardSurface, RoundedCornerShape(10.dp))
                .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp))
                .padding(20.dp).verticalScroll(rememberScrollState()).testTag("Gateway logs dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Gateway logs", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            when (state.phase) {
                GatewayLogsState.Phase.Consent -> {
                    // product-copy-allow: the shared operator-dashboard scope must be explicit before reading.
                    Text("Read up to 100 recent error-log lines from this Gateway? These are backend-wide logs from the Gateway process, not logs for this error, session, or selected profile.", style = HermesTheme.type.body, color = tokens.textSecondary)
                    // product-copy-allow: best-effort redaction cannot guarantee private raw server logs are safe.
                    Text("Logs may include other sessions and sensitive information. The server returns raw logs; Android hides known secrets, but cannot remove everything. Continue only if you are authorized to view them.", style = HermesTheme.type.body, color = tokens.textSecondary)
                    Text("View only. No copy, export, upload, or automatic refresh. Closing clears this view.", style = HermesTheme.type.caption, color = tokens.textTertiary)
                }
                GatewayLogsState.Phase.Loading -> Text("Reading Gateway logs…", style = HermesTheme.type.body, color = tokens.textSecondary)
                GatewayLogsState.Phase.Finished -> when (val result = state.result) {
                    is GatewayLogsResult.Content -> {
                        Text("Gateway-wide error log · redacted excerpt", style = HermesTheme.type.caption, color = tokens.textTertiary)
                        Text(result.text, style = HermesTheme.type.code, color = tokens.textSecondary, modifier = Modifier.testTag("Gateway log excerpt"))
                        if (result.truncated) Text("Display limit reached. This excerpt may be incomplete.", style = HermesTheme.type.caption, color = tokens.textTertiary)
                    }
                    else -> Text(when (result) {
                        GatewayLogsResult.Empty -> "The Gateway returned no error-log lines."
                        GatewayLogsResult.Refused -> "The Gateway refused access to logs. Check your access with its operator."
                        GatewayLogsResult.Unsupported -> "This Gateway does not support reading logs here. Ask its operator for help."
                        GatewayLogsResult.Oversize -> "The log response exceeded the safe size limit. Nothing is displayed."
                        else -> "Gateway logs could not be read. Check the connection and try again."
                    }, style = HermesTheme.type.body, color = tokens.textSecondary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(if (state.phase == GatewayLogsState.Phase.Consent) "Cancel" else "Close", onClick = onDismiss)
                if (state.phase == GatewayLogsState.Phase.Consent) PrimaryButton("Read Gateway logs", onClick = { onConfirm(state.generation) })
            }
        }
    }
}
