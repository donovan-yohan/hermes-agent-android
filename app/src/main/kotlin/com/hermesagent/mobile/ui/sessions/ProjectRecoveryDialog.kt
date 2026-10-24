package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.chat.ProjectRecoveryDialog
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme

@Composable
internal fun ProjectRecoveryDialog(state: ProjectRecoveryDialog, actions: ProjectActions) {
    val tokens = HermesTheme.tokens
    val token = state.token
    Dialog(onDismissRequest = { actions.recoveryDismiss(token) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.imePadding().padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 600.dp)
            .background(tokens.cardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review project changes", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            if (state.busy) Text("Loading…", color = tokens.textSecondary)
            val selected = state.attempts.firstOrNull { it.attemptId == state.selectedAttempt }
            if (selected == null) {
                if (!state.busy && state.error == null && state.attempts.isEmpty()) Text("No project changes to review", color = tokens.textSecondary)
                state.attempts.forEach { attempt ->
                    TextButton(label = "Review ${redact(attempt.label)}", enabled = !state.busy,
                        onClick = { actions.recoverySelect(token, attempt.attemptId) })
                }
            } else {
                Text(redact(selected.label), style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
                Text(redact(selected.path), style = HermesTheme.type.caption, color = tokens.textSecondary)
                TextButton(label = "Refresh projects", enabled = !state.busy, onClick = { actions.recoveryRefresh(token) })
                state.projects?.let { projects ->
                    Text("Saved projects", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
                    if (projects.isEmpty()) Text("No saved projects", color = tokens.textSecondary)
                    projects.forEach { project ->
                        Text(redact(project.name), style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
                        project.paths.forEach { path -> Text(redact(path), style = HermesTheme.type.caption, color = tokens.textSecondary) }
                        if (project.archived) Text("Archived", style = HermesTheme.type.caption, color = tokens.textSecondary)
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = state.reviewed, enabled = !state.busy && state.projects != null, role = Role.Checkbox,
                        onValueChange = { actions.recoveryReviewed(token) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.reviewed, onCheckedChange = null,
                        enabled = !state.busy && state.projects != null)
                    Text("I reviewed these projects", color = tokens.textPrimary)
                }
                // product-copy-allow: the duplicate risk is material to this explicit new-attempt authorization.
                Text("The earlier request may still finish. Another attempt could create a duplicate project.",
                    style = HermesTheme.type.caption, color = tokens.textSecondary)
                TextButton(label = "Allow another attempt", enabled = !state.busy && state.projects != null && state.reviewed,
                    onClick = { actions.recoveryAllow(token) })
            }
            state.error?.let { Text(redact(it), style = HermesTheme.type.caption, color = tokens.destructive) }
            TextButton(label = "Close", onClick = { actions.recoveryDismiss(token) })
        }
    }
}
