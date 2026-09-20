package com.hermesagent.mobile.ui.chat

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.data.gateway.DiagnosticsResult
import com.hermesagent.mobile.data.gateway.safeDiagnosticsViewUrl
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

@Composable
internal fun SendDiagnosticsDialog(
    state: SendDiagnosticsState,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    val context = LocalContext.current
    var browserFailed by remember(state.generation) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .background(tokens.cardSurface, RoundedCornerShape(10.dp))
                .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp))
                .padding(20.dp).verticalScroll(rememberScrollState()).testTag("Send diagnostics dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Send diagnostics", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.phase) {
                    SendDiagnosticsState.Phase.Consent -> {
                        // product-copy-allow: informed consent must disclose the full backend bundle and configurable destination.
                        Text("Upload the connected Gateway’s debug report, full logs, and these error details? Android photos, files, and device logs are not attached.", style = HermesTheme.type.body, color = tokens.textSecondary)
                        // product-copy-allow: redaction is best effort, not a guarantee that shared-host logs are private.
                        Text("The Gateway redacts known secrets before upload, but logs may still contain conversations and personal data, including other activity on a shared Gateway. Only continue if you are authorized to share them.", style = HermesTheme.type.body, color = tokens.textSecondary)
                        // product-copy-allow: upstream permits an operator override; do not falsely promise a fixed destination.
                        Text("The default destination is Nous Research diagnostics storage. Your Gateway operator can change it; Android cannot verify that setting. Upload only if you trust this Gateway and its operator.", style = HermesTheme.type.body, color = tokens.textSecondary)
                        Text("Consent applies to this upload only.", style = HermesTheme.type.caption, color = tokens.textTertiary)
                    }
                    SendDiagnosticsState.Phase.Uploading -> {
                        Text("Uploading diagnostics…", style = HermesTheme.type.body, color = tokens.textSecondary)
                        Text("Closing this dialog does not cancel an upload already started by the Gateway.", style = HermesTheme.type.caption, color = tokens.textTertiary)
                    }
                    SendDiagnosticsState.Phase.Finished -> when (val result = state.result) {
                        is DiagnosticsResult.Uploaded -> {
                            Text("Diagnostics uploaded", style = HermesTheme.type.body, color = tokens.textSecondary)
                            result.uploadId?.let { Text("Upload ID: $it", style = HermesTheme.type.code, color = tokens.textSecondary) }
                            result.expiresAt?.let { Text("Expires: $it", style = HermesTheme.type.caption, color = tokens.textTertiary) }
                            val url = safeDiagnosticsViewUrl(result.viewUrl)
                            if (url != null) TextButton("Open private report", onClick = {
                                browserFailed = runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                                }.isFailure
                            })
                            else Text("No safe report link was returned. Share the upload ID with support.", style = HermesTheme.type.caption, color = tokens.textTertiary)
                        }
                        else -> Text(
                            when (result) {
                                DiagnosticsResult.Unsupported -> "This Gateway does not support diagnostic uploads. Ask its operator to update it."
                                DiagnosticsResult.Refused -> "The Gateway refused this upload. Check your access with its operator."
                                else -> "The upload could not be confirmed. It may have completed. Check with support before starting another upload."
                            },
                            style = HermesTheme.type.body, color = tokens.destructive,
                        )
                    }
                }
                if (browserFailed) Text("The report could not be opened. Use the upload ID with support.", style = HermesTheme.type.caption, color = tokens.destructive)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(if (state.phase == SendDiagnosticsState.Phase.Consent) "Cancel" else "Close", onClick = onDismiss)
                if (state.phase == SendDiagnosticsState.Phase.Consent) {
                    PrimaryButton("Upload diagnostics", onClick = { onConfirm(state.generation) })
                }
            }
        }
    }
}
