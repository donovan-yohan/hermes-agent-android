package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
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
import com.hermesagent.mobile.data.gateway.ProjectOperation
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.ssh.redact
import com.hermesagent.mobile.ui.chat.*
import com.hermesagent.mobile.ui.common.*
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.ProjectAppearance

class ProjectActions(
    val open: (ProjectSummary, () -> Boolean, Long) -> Unit = { _, _, _ -> },
    val dismiss: (Long) -> Unit = {},
    val page: (Long, ProjectActionPage) -> Unit = { _, _ -> },
    val execute: (Long, ProjectOperation) -> Unit = { _, _ -> },
    val hide: (Long) -> Unit = {},
    val restore: (String, Long) -> Unit = { _, _ -> },
    val path: (Long) -> String? = { null },
    val recoveryOpen: (() -> Boolean, Long) -> Unit = { _, _ -> },
    val recoveryDismiss: (Long) -> Unit = {},
    val recoverySelect: (Long, String) -> Unit = { _, _ -> },
    val recoveryRefresh: (Long) -> Unit = {},
    val recoveryReviewed: (Long) -> Unit = {},
    val recoveryAllow: (Long) -> Unit = {},
)

/** Shared context-order model; unlike Desktop's kebab, Appearance follows Set active. */
internal fun projectContextLabels(project: ProjectSummary): List<String> = if (project.isHome) emptyList() else buildList {
    if (!project.isAuto) addAll(listOf("Rename…", "Add folder", "Set active"))
    if (!project.isAuto || !project.path.isNullOrBlank()) add("Appearance")
    add("Copy path")
    add(if (project.isAuto) "Hide from sidebar" else "Delete…")
}

@Composable
internal fun ProjectActionsDialog(dialog: ProjectActionDialog, activeId: String?, actions: ProjectActions) {
    val tokens = HermesTheme.tokens
    val project = dialog.project
    val token = dialog.token
    var name by remember(token) { mutableStateOf(project.label) }
    var path by remember(token) { mutableStateOf("") }
    var label by remember(token) { mutableStateOf("") }
    var primary by remember(token) { mutableStateOf(false) }
    var copyStatus by remember(token) { mutableStateOf<String?>(null) }
    val clipboard = rememberClipboardWriter()
    Dialog(onDismissRequest = { if (!dialog.busy) actions.dismiss(token) },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 560.dp)
            .background(tokens.cardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp))
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(redact(when (dialog.page) {
                ProjectActionPage.Menu -> project.label
                ProjectActionPage.Rename -> "Rename project"
                ProjectActionPage.AddFolder -> "Add folder"
                ProjectActionPage.Appearance -> "Appearance"
                ProjectActionPage.Delete -> "Delete \"${project.label}\"?"
            }), style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            when (dialog.page) {
                ProjectActionPage.Menu -> projectContextLabels(project).forEach { item ->
                    if (item == "Copy path" || item == "Delete…" || item == "Hide from sidebar") {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.strokePrimary))
                    }
                    ProjectContextActionRow(label = if (item == "Copy path") copyStatus ?: item else item,
                        icon = when (item) { "Rename…" -> HermesIcon.Edit; "Add folder" -> HermesIcon.NewFolder;
                            "Set active" -> HermesIcon.Target; "Appearance" -> HermesIcon.SymbolColor;
                            "Copy path" -> HermesIcon.Copy; else -> HermesIcon.Trash },
                        destructive = item == "Delete…" || item == "Hide from sidebar",
                        enabled = !dialog.busy && (item != "Copy path" || !project.path.isNullOrBlank()) && (item != "Set active" || activeId != project.id),
                        onClick = {
                            when (item) {
                                "Rename…" -> actions.page(token, ProjectActionPage.Rename)
                                "Add folder" -> actions.page(token, ProjectActionPage.AddFolder)
                                "Set active" -> actions.execute(token, ProjectOperation.SetActive)
                                "Appearance" -> actions.page(token, ProjectActionPage.Appearance)
                                "Copy path" -> actions.path(token)?.let { copyStatus = if (clipboard.write("Project path", it)) "Copied" else "Could not copy" }
                                "Delete…" -> actions.page(token, ProjectActionPage.Delete)
                                "Hide from sidebar" -> actions.hide(token)
                            }
                        })
                }
                ProjectActionPage.Rename -> {
                    LabelledField(label = "Name", value = name, onValueChange = { name = it }, placeholder = "Project name")
                    TextButton(label = "Save", enabled = !dialog.busy && name.isNotBlank(), onClick = { actions.execute(token, ProjectOperation.Rename(name)) })
                }
                ProjectActionPage.AddFolder -> {
                    Text("Folder on the connected Gateway", style = HermesTheme.type.caption, color = tokens.textSecondary)
                    LabelledField(label = "Remote folder", value = path, onValueChange = { path = it }, placeholder = "Folder path")
                    LabelledField(label = "Label (optional)", value = label, onValueChange = { label = it }, placeholder = "Label")
                    TextButton(label = if (primary) "Primary folder ✓" else "Make primary folder", enabled = !dialog.busy, onClick = { primary = !primary })
                    TextButton(label = "Add folder", enabled = !dialog.busy && path.isNotBlank(), onClick = {
                        actions.execute(token, ProjectOperation.AddFolder(path.trim(), label.trim().takeIf { it.isNotBlank() }, primary))
                    })
                }
                ProjectActionPage.Delete -> {
                    Text("This removes the saved project from Hermes. Files, git repos, and worktrees stay untouched.", style = HermesTheme.type.caption, color = tokens.textSecondary)
                    TextButton(label = "Delete", enabled = !dialog.busy, onClick = { actions.execute(token, ProjectOperation.Delete) })
                }
                ProjectActionPage.Appearance -> {
                    ProjectAppearance.swatches.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { color ->
                                val ink = ProjectAppearance.color(color, tokens.textPrimary)
                                val selected = project.color == color
                                Box(Modifier.size(HermesTheme.spacing.touchTarget)
                                    .selectable(selected = selected, enabled = !dialog.busy, role = Role.Button,
                                        onClick = { actions.execute(token, ProjectOperation.Color(color)) })
                                    .semantics { contentDescription = color }, contentAlignment = Alignment.Center) {
                                    // Desktop size-5 fill + 2px elevated gap + 1.5px swatch-colored ring.
                                    Canvas(Modifier.size(27.dp)) {
                                        drawCircle(ink, radius = 10.dp.toPx())
                                        if (selected) drawCircle(ink, radius = 12.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                                    }
                                }
                            }
                        }
                    }
                    TextButton(label = "No color", enabled = !dialog.busy, onClick = { actions.execute(token, ProjectOperation.Color(null)) })
                    ProjectAppearance.icons.entries.chunked(6).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            row.forEach { (name, icon) ->
                                HermesIconButton(icon = icon, contentDescription = name, active = project.icon == name,
                                    modifier = Modifier.semantics { selected = project.icon == name },
                                    tint = if (project.icon == name) ProjectAppearance.color(project.color, tokens.textPrimary) else tokens.textTertiary,
                                    enabled = !dialog.busy, onClick = { actions.execute(token, ProjectOperation.Icon(name.takeUnless { it == project.icon })) })
                            }
                        }
                    }
                }
            }
            if (dialog.busy) Text("Saving…", style = HermesTheme.type.caption, color = tokens.textSecondary)
            dialog.error?.let { Text(redact(it), style = HermesTheme.type.caption, color = tokens.destructive) }
            TextButton(label = "Cancel", enabled = !dialog.busy, onClick = { actions.dismiss(token) })
        }
    }
}

@Composable
private fun ProjectContextActionRow(label: String, icon: HermesIcon, enabled: Boolean, destructive: Boolean, onClick: () -> Unit) {
    val tokens = HermesTheme.tokens
    val ink = if (!enabled) tokens.textQuaternary else if (destructive) tokens.destructive else tokens.textSecondary
    Row(Modifier.fillMaxWidth().heightIn(min = HermesTheme.spacing.touchTarget)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HermesIconGlyph(icon, color = ink, size = 13.sp)
        Text(label, style = HermesTheme.type.scaffold, color = ink)
    }
}

@Composable
internal fun HiddenProjectsDialog(state: ProjectManagementUi, actions: ProjectActions, onDismiss: () -> Unit) {
    val tokens = HermesTheme.tokens
    var query by remember(state.hiddenOwnerToken) { mutableStateOf("") }
    // A fixed available window width prevents wrap-content dialog measurement from
    // oscillating between its platform minimum and the single-line field's full width.
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = 520.dp).background(tokens.cardSurface, RoundedCornerShape(10.dp))
            .border(1.dp, tokens.strokePrimary, RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hidden projects", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            LabelledField(label = "Search", value = query, onValueChange = { query = it }, placeholder = "Search names or paths")
            if (state.hiddenLoading) Text("Loading…", color = tokens.textSecondary)
            state.hiddenError?.let { Text(redact(it), color = tokens.destructive) }
            val rows = state.hiddenProjects.filter { it.label.contains(query, true) || it.path.orEmpty().contains(query, true) }
            if (!state.hiddenLoading && rows.isEmpty()) Text("No hidden projects", color = tokens.textSecondary)
            rows.forEach { project ->
                Text(redact(project.label), style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
                Text(redact(project.path.orEmpty()), style = HermesTheme.type.caption, color = tokens.textSecondary)
                TextButton(label = "Restore ${redact(project.label)}", enabled = !state.hiddenBusy,
                    onClick = { actions.restore(project.id, state.hiddenOwnerToken) })
            }
            TextButton(label = "Done", onClick = onDismiss)
        }
    }
}
