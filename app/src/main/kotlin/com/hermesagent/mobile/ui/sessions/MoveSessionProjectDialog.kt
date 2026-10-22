package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class SessionProjectActions(
    val load: suspend (String) -> List<ProjectSummary>,
    val move: suspend (String, ProjectSummary) -> Unit,
)

@Composable
internal fun MoveSessionProjectDialog(
    load: suspend () -> List<ProjectSummary>,
    move: suspend (ProjectSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<ProjectSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        error = null
        projects = null
        try { projects = load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Projects could not be loaded. Try again." }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().background(tokens.cardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(MOVE_TO_PROJECT, style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (projects == null && error == null) Text("Loading…", color = tokens.textSecondary)
                if (projects?.isEmpty() == true) Text("No other projects", color = tokens.textSecondary)
                projects?.forEach { project ->
                    TextButton(label = redact(project.label), enabled = !busy, onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try { move(project); onDismiss() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { error = "Could not move session. Reopen the session to check, then try again." }
                            finally { busy = false }
                        }
                    })
                }
            }
            error?.let { Text(it, style = HermesTheme.type.caption, color = tokens.destructive) }
            if (error != null && projects == null) TextButton(label = "Retry", onClick = { reload++ })
            TextButton(label = "Cancel", enabled = !busy, onClick = onDismiss)
        }
    }
}
