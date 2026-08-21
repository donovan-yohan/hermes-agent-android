package com.hermesagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.hermesagent.mobile.data.session.ProjectSummary
import com.hermesagent.mobile.data.prefs.SidebarGrouping
import com.hermesagent.mobile.data.session.SessionListRow
import com.hermesagent.mobile.data.session.SessionStatus
import com.hermesagent.mobile.data.session.SessionSummary
import com.hermesagent.mobile.data.session.label
import com.hermesagent.mobile.ui.common.EmptyState
import com.hermesagent.mobile.ui.common.DitherMark
import com.hermesagent.mobile.ui.common.HermesIcon
import com.hermesagent.mobile.ui.common.HermesIconButton
import com.hermesagent.mobile.ui.common.HermesIconGlyph
import com.hermesagent.mobile.ui.common.LabelledField
import com.hermesagent.mobile.ui.common.PrimaryButton
import com.hermesagent.mobile.ui.common.SearchField
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.common.StatusDot
import com.hermesagent.mobile.ui.common.TextButton
import com.hermesagent.mobile.ui.theme.HermesTheme
import com.hermesagent.mobile.ui.theme.HermesTokens

/**
 * Sessions.
 *
 * On a phone this is a drawer; on a wide screen it is the persistent rail.
 * Same composable either way — the layout decides where it lives, not what it
 * is, which is what keeps "switching context is a re-home, not a reboot"
 * (`apps/desktop/AGENTS.md` @ `f82f2dba`) true on both.
 *
 * Flat by construction: dividers only where the calendar bucket changes, no
 * per-row card, no nested rounded boxes. The active row is marked by fill and
 * by an accent edge, not by an outline.
 */
@Composable
fun SessionList(
    rows: List<SessionListRow>,
    projects: List<ProjectSummary>,
    projectsAvailable: Boolean?,
    sidebarGrouping: SidebarGrouping,
    selectedProject: ProjectSummary?,
    projectLoading: Boolean,
    activeSessionId: String?,
    query: String,
    canCreate: Boolean,
    onQueryChange: (String) -> Unit,
    onSidebarGroupingChange: (SidebarGrouping) -> Unit,
    onSelectProject: (String) -> Unit,
    onExitProject: () -> Unit,
    onCreateProject: (name: String, folderPath: String) -> Unit,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = HermesTheme.tokens
    val showingProjectOverview = sidebarGrouping == SidebarGrouping.Project && selectedProject == null
    val title = selectedProject?.label ?: if (showingProjectOverview) "Projects" else "Sessions"
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var projectCreateVisible by rememberSaveable { mutableStateOf(false) }
    val searchIsVisible = searchVisible || query.isNotBlank()

    Column(modifier.fillMaxSize().background(tokens.sidebarSurface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DitherMark(tokens.accent)
                Text(
                    text = title.uppercase(),
                    style = HermesTheme.type.panelLabel,
                    color = tokens.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HermesIconButton(
                icon = HermesIcon.Add,
                contentDescription = if (showingProjectOverview) "New project" else "New session",
                onClick = {
                    if (showingProjectOverview) projectCreateVisible = true else onCreate()
                },
                enabled = canCreate && (!showingProjectOverview || projectsAvailable == true),
            )
            if (selectedProject != null) {
                HermesIconButton(
                    icon = HermesIcon.ListUnordered,
                    contentDescription = "All projects",
                    onClick = onExitProject,
                )
            }
            Box {
                HermesIconButton(
                    icon = HermesIcon.ListFilter,
                    contentDescription = "Filters",
                    onClick = { menuVisible = !menuVisible },
                    active = menuVisible || query.isNotBlank() || sidebarGrouping != SidebarGrouping.Date,
                )
                SidebarViewMenu(
                    expanded = menuVisible,
                    grouping = sidebarGrouping,
                    projectGroupingAvailable = projectsAvailable != false,
                    searchVisible = searchIsVisible,
                    searchesProjects = showingProjectOverview,
                    onDismiss = { menuVisible = false },
                    onGroupingChange = { grouping ->
                        menuVisible = false
                        onSidebarGroupingChange(grouping)
                    },
                    onToggleSearch = {
                        menuVisible = false
                        searchVisible = !searchIsVisible
                        if (searchIsVisible) onQueryChange("")
                    },
                )
            }
        }

        if (searchIsVisible) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = if (showingProjectOverview) "Search projects" else "Search sessions",
                modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp),
            )
        }

        if (sidebarGrouping == SidebarGrouping.Project && projectsAvailable == null) {
            Text(
                text = if (canCreate) "Loading projects…" else "Connect to a Gateway to load projects.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else if (sidebarGrouping == SidebarGrouping.Project && projectsAvailable == false) {
            Text(
                text = "Project views aren’t available on this Gateway.",
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textTertiary,
                modifier = Modifier.padding(horizontal = HermesTheme.spacing.pageInset, vertical = 4.dp),
            )
        }

        when {
            showingProjectOverview && projectsAvailable == true && projects.isEmpty() -> EmptyState(
                title = if (query.isBlank()) "No projects" else "Nothing matches",
                description = when {
                    query.isNotBlank() -> "No project or recent session contains “$query”."
                    canCreate -> "Create a project with the + above."
                    else -> "No projects were returned by this Gateway."
                },
            )

            showingProjectOverview -> LazyColumn(
                modifier = Modifier.weight(1f).testTag("Project list"),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(items = projects, key = { "project-${it.id}" }) { project ->
                    ProjectRow(
                        project = project,
                        activeSessionId = activeSessionId,
                        onOpen = { onSelectProject(project.id) },
                        onSelectSession = onSelect,
                    )
                }
            }

            projectLoading -> EmptyState(
                title = "Opening project…",
                description = "Hermes is loading this project’s session history.",
            )

            rows.isEmpty() -> EmptyState(
                title = when {
                    query.isBlank() -> "No sessions"
                    else -> "Nothing matches"
                },
                description = when {
                    query.isNotBlank() -> "No session title or preview contains “$query”."
                    canCreate -> "Start one with the + above."
                    else -> "Connect to a Gateway to start a session."
                },
            )

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("Session list"),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(items = rows, key = { it.key() }) { row ->
                        when (row) {
                            is SessionListRow.Divider -> SectionLabel(
                                text = row.bucket.label(),
                                modifier = Modifier.padding(
                                    start = HermesTheme.spacing.pageInset,
                                    top = 14.dp,
                                    bottom = 4.dp,
                                ),
                            )

                            is SessionListRow.Row -> SessionRow(
                                session = row.session,
                                active = row.session.id == activeSessionId,
                                onClick = { onSelect(row.session.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (projectCreateVisible) {
        ProjectCreateDialog(
            onDismiss = { projectCreateVisible = false },
            onCreate = { name, folderPath ->
                projectCreateVisible = false
                onCreateProject(name, folderPath)
            },
        )
    }
}

@Composable
private fun SidebarViewMenu(
    expanded: Boolean,
    grouping: SidebarGrouping,
    projectGroupingAvailable: Boolean,
    searchVisible: Boolean,
    searchesProjects: Boolean,
    onDismiss: () -> Unit,
    onGroupingChange: (SidebarGrouping) -> Unit,
    onToggleSearch: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(min = 220.dp)
            .border(1.dp, tokens.strokePrimary, RoundedCornerShape(6.dp))
            .testTag("Sidebar view menu"),
        shape = RoundedCornerShape(6.dp),
        containerColor = tokens.cardSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.selectableGroup()) {
            Text(
                text = "GROUPING",
                style = HermesTheme.type.panelLabel,
                color = tokens.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            SidebarGroupingOption(
                label = "Updated",
                icon = HermesIcon.Clock,
                selected = grouping == SidebarGrouping.Date,
                onClick = { onGroupingChange(SidebarGrouping.Date) },
            )
            SidebarGroupingOption(
                label = "Project",
                icon = HermesIcon.RootFolder,
                selected = grouping == SidebarGrouping.Project,
                enabled = projectGroupingAvailable,
                onClick = { onGroupingChange(SidebarGrouping.Project) },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.strokeTertiary))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(role = Role.Button, onClick = onToggleSearch)
                .semantics {
                    contentDescription = when {
                        searchVisible -> "Hide search"
                        searchesProjects -> "Search projects"
                        else -> "Search sessions"
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HermesIconGlyph(HermesIcon.Search, size = 13.sp)
            Text(
                text = if (searchVisible) "Hide search" else "Search",
                style = HermesTheme.type.scaffold,
                color = tokens.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SidebarGroupingOption(
    label: String,
    icon: HermesIcon,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val tokens = HermesTheme.tokens
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics {
                contentDescription = "$label grouping"
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val contentColor = if (enabled) tokens.textSecondary else tokens.textQuaternary
        HermesIconGlyph(icon, color = contentColor, size = 13.sp)
        Text(
            text = label,
            style = HermesTheme.type.scaffold,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (selected) HermesIconGlyph(HermesIcon.Check, color = tokens.accent, size = 13.sp)
    }
}

@Composable
private fun ProjectCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, folderPath: String) -> Unit,
) {
    val tokens = HermesTheme.tokens
    var name by rememberSaveable { mutableStateOf("") }
    var folderPath by rememberSaveable { mutableStateOf("") }
    val canSubmit = name.isNotBlank() && folderPath.isNotBlank()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .background(tokens.cardSurface, RoundedCornerShape(10.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New project", style = HermesTheme.type.bodyStrong, color = tokens.textPrimary)
            Text(
                "Projects group sessions by a folder on the connected Gateway.",
                style = HermesTheme.type.caption,
                color = tokens.textTertiary,
            )
            LabelledField(
                label = "Name",
                value = name,
                placeholder = "Project name",
                onValueChange = { name = it },
            )
            LabelledField(
                label = "Remote folder",
                value = folderPath,
                placeholder = "/home/you/project",
                onValueChange = { folderPath = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(label = "Cancel", onClick = onDismiss, color = tokens.textSecondary)
                PrimaryButton(
                    label = "Create project",
                    onClick = { onCreate(name.trim(), folderPath.trim()) },
                    enabled = canSubmit,
                )
            }
        }
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    activeSessionId: String?,
    onOpen: () -> Unit,
    onSelectSession: (String) -> Unit,
) {
    val tokens = HermesTheme.tokens
    val countLabel = if (project.sessionCount == 1) "1 session" else "${project.sessionCount} sessions"
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HermesTheme.spacing.touchTarget)
                .clickable(onClick = onOpen)
                .testTag("Project row ${project.id}")
                .padding(horizontal = HermesTheme.spacing.pageInset, vertical = 8.dp)
                .semantics { contentDescription = "Open project ${project.label}. $countLabel" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = project.label,
                style = HermesTheme.type.sessionTitle,
                color = if (project.isHome) tokens.accent else tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = project.sessionCount.toString(),
                style = HermesTheme.type.scaffoldMeta,
                color = tokens.textTertiary,
            )
        }
        project.previewSessions.forEach { session ->
            SessionRow(
                session = session,
                active = session.id == activeSessionId,
                onClick = { onSelectSession(session.id) },
            )
        }
    }
}

private fun SessionListRow.key(): String = when (this) {
    is SessionListRow.Divider -> "divider-${bucket.name}"
    is SessionListRow.Row -> session.id
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tokens = HermesTheme.tokens
    val dot = session.status.dot(tokens)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HermesTheme.spacing.touchTarget)
            .clickable(onClick = onClick)
            .testTag("Session row ${session.id}")
            .background(if (active) tokens.widgetSurface else tokens.sidebarSurface)
            .padding(start = HermesTheme.spacing.pageInset - 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            .semantics {
                selected = active
                contentDescription = "${session.title}. ${dot.description}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Active edge: a 2dp accent bar, not a box around the row.
        Box(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(
                    if (active) tokens.accent else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )

        StatusDot(
            color = dot.color,
            filled = dot.filled,
            contentDescription = null,
            size = if (session.status == SessionStatus.Idle) 6.dp else 7.dp,
        )

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = session.title,
                style = HermesTheme.type.sessionTitle,
                color = if (active) tokens.textPrimary else tokens.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.preview.isNotBlank()) {
                Text(
                    text = session.preview,
                    style = HermesTheme.type.sessionPreview,
                    color = tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The dot lookup. Colour and fill carry the meaning; nothing animates. */
private data class DotStyle(val color: Color, val filled: Boolean, val description: String)

private fun SessionStatus.dot(tokens: HermesTokens): DotStyle = when (this) {
    SessionStatus.NeedsInput -> DotStyle(tokens.statusNeedsInput, true, "Waiting for your answer")
    SessionStatus.Working -> DotStyle(tokens.statusWorking, true, "Running")
    SessionStatus.Stalled -> DotStyle(tokens.statusWorking, false, "Running, nothing arriving")
    SessionStatus.Background -> DotStyle(tokens.textTertiary, false, "Background process still open")
    SessionStatus.Unread -> DotStyle(tokens.statusUnread, true, "Finished, unread")
    SessionStatus.Idle -> DotStyle(tokens.statusIdle, false, "Idle")
}
